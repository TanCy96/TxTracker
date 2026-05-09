package cy.txtracker.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.DbRule
import cy.txtracker.data.Direction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.TimeBucket
import cy.txtracker.parsing.ParsedTransaction
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TxIngestorTest {

    @get:Rule val dbRule = DbRule()

    private fun ingestor() = TxIngestor(
        repository = TransactionRepository(
            transactionDao = dbRule.transactionDao,
            categoryDao = dbRule.categoryDao,
            merchantMappingDao = dbRule.merchantMappingDao,
            descriptionMappingDao = dbRule.descriptionMappingDao,
        ),
    )

    private fun parsed(
        merchantRaw: String = "CHONG TYRE AUTO SVC",
        amountMinor: Long = 53000,
        occurredAt: Instant = Instant.parse("2026-05-09T04:30:00Z"), // 12:30 KL → MIDDAY
        sourceApp: String = "com.google.android.apps.walletnfcrel",
        rawText: String = "$merchantRaw RM530.00 with CIMB Cash Rebate Plat MasterCard **1868",
    ) = ParsedTransaction(
        amountMinor = amountMinor,
        currency = "MYR",
        merchantRaw = merchantRaw,
        occurredAt = occurredAt,
        sourceApp = sourceApp,
        rawText = rawText,
        direction = Direction.OUT,
    )

    @Test
    fun ingest_normalizes_merchant_computes_bucket_and_inserts() = runTest {
        val id = ingestor().ingest(parsed())!!
        val row = dbRule.transactionDao.getById(id)!!

        assertThat(row.merchantRaw).isEqualTo("CHONG TYRE AUTO SVC")
        assertThat(row.merchantNormalized).isEqualTo("CHONG TYRE AUTO")  // SVC suffix stripped
        assertThat(row.timeBucket).isEqualTo(TimeBucket.MIDDAY)
        assertThat(row.amountMinor).isEqualTo(53000L)
        assertThat(row.currency).isEqualTo("MYR")
        assertThat(row.direction).isEqualTo(Direction.OUT)
        assertThat(row.sourceApp).isEqualTo("com.google.android.apps.walletnfcrel")
    }

    @Test
    fun ingest_leaves_categoryId_and_description_null_for_user_to_assign() = runTest {
        val id = ingestor().ingest(parsed())!!
        val row = dbRule.transactionDao.getById(id)!!
        // Auto-categorization and description suggestions arrive in tasks 7 & 8.
        // Until then, ingested transactions land in the "Unverified" filter.
        assertThat(row.categoryId).isNull()
        assertThat(row.description).isNull()
    }

    @Test
    fun ingest_dedupes_same_payment_seen_by_two_source_apps() = runTest {
        val ing = ingestor()
        // Google Wallet sees "CHONG TYRE AUTO SVC".
        val gwalletId = ing.ingest(
            parsed(
                merchantRaw = "CHONG TYRE AUTO SVC",
                sourceApp = "com.google.android.apps.walletnfcrel",
            ),
        )
        // Bank app fires 30 seconds later for the same payment, and uses a slightly
        // different merchant string. Normalization + 5-minute bucket → same dedupe key.
        val bankId = ing.ingest(
            parsed(
                merchantRaw = "CHONG TYRE AUTO",
                occurredAt = Instant.parse("2026-05-09T04:30:30Z"),
                sourceApp = "com.cimb.cimbmalaysia",
            ),
        )

        assertThat(gwalletId).isNotNull()
        assertThat(bankId).isNull()  // dropped on dedupe — only one row in the DB.
    }

    @Test
    fun ingest_keeps_separate_rows_for_payments_in_different_5min_windows() = runTest {
        val ing = ingestor()
        val first = ing.ingest(parsed(occurredAt = Instant.parse("2026-05-09T04:30:00Z")))
        val second = ing.ingest(parsed(occurredAt = Instant.parse("2026-05-09T04:36:00Z")))
        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun ingest_keeps_separate_rows_for_different_amounts_in_same_window() = runTest {
        val ing = ingestor()
        val a = ing.ingest(parsed(amountMinor = 1250))
        val b = ing.ingest(parsed(amountMinor = 1500))
        assertThat(a).isNotNull()
        assertThat(b).isNotNull()
    }
}
