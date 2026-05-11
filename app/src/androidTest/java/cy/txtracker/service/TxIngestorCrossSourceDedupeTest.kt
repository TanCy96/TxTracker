package cy.txtracker.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.DbRule
import cy.txtracker.data.Direction
import cy.txtracker.data.MANUAL_SOURCE_APP
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.parsing.GrabParser
import cy.txtracker.parsing.ParsedTransaction
import cy.txtracker.parsing.SourceTierResolver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val GWALLET = "com.google.android.apps.walletnfcrel"

@RunWith(AndroidJUnit4::class)
class TxIngestorCrossSourceDedupeTest {

    @get:Rule val dbRule = DbRule()

    private val now = Instant.parse("2026-05-09T12:30:00Z")

    private fun ingestor(): TxIngestor {
        val repository = TransactionRepository(
            database = dbRule.db,
            transactionDao = dbRule.transactionDao,
            categoryDao = dbRule.categoryDao,
            merchantMappingDao = dbRule.merchantMappingDao,
            descriptionMappingDao = dbRule.descriptionMappingDao,
            merchantNoteDao = dbRule.merchantNoteDao,
            userFacingSourceDao = dbRule.userFacingSourceDao,
        )
        return TxIngestor(
            database = dbRule.db,
            repository = repository,
            categorizationEngine = CategorizationEngine(
                merchantMappingDao = dbRule.merchantMappingDao,
                categoryDao = dbRule.categoryDao,
            ),
            descriptionEngine = DescriptionEngine(
                descriptionMappingDao = dbRule.descriptionMappingDao,
            ),
            sourceTierResolver = SourceTierResolver(dbRule.userFacingSourceDao),
        )
    }

    private fun gwalletParsed(at: Instant = now, amount: Long = 2500): ParsedTransaction =
        ParsedTransaction(
            amountMinor = amount,
            currency = "MYR",
            merchantRaw = "GRAB RIDES-EC",
            occurredAt = at,
            sourceApp = GWALLET,
            rawText = "GRAB RIDES-EC RM25.00 with CIMB Plat **1868",
            direction = Direction.OUT,
        )

    private fun grabParsed(at: Instant = now, amount: Long = 2500): ParsedTransaction =
        ParsedTransaction(
            amountMinor = amount,
            currency = "MYR",
            merchantRaw = "GRAB",
            occurredAt = at,
            sourceApp = GrabParser.GRAB_PACKAGE,
            rawText = "Your Mastercard 1868 has been charged RM 25.00 for booking A-1",
            direction = Direction.OUT,
        )

    @Test
    fun promotes_existing_when_grab_arrives_after_gwallet() = runTest {
        val ingestor = ingestor()
        val gwalletId = ingestor.ingest(gwalletParsed(), needsVerification = false)!!

        ingestor.ingest(grabParsed(at = now.plusMillis(1_000)), needsVerification = false)

        val rows = dbRule.transactionDao.getAllOnce()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.id).isEqualTo(gwalletId)
        assertThat(row.sourceApp).isEqualTo(GrabParser.GRAB_PACKAGE)
        assertThat(row.merchantNormalized).isEqualTo("GRAB")
        assertThat(row.merchantRaw).isEqualTo("GRAB")
    }

    @Test
    fun drops_incoming_when_grab_already_exists_and_gwallet_arrives() = runTest {
        val ingestor = ingestor()
        val grabId = ingestor.ingest(grabParsed(), needsVerification = false)!!

        val secondId = ingestor.ingest(
            gwalletParsed(at = now.plusMillis(1_000)), needsVerification = false,
        )

        assertThat(secondId).isNull()
        val rows = dbRule.transactionDao.getAllOnce()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().id).isEqualTo(grabId)
        assertThat(rows.single().sourceApp).isEqualTo(GrabParser.GRAB_PACKAGE)
    }

    @Test
    fun preserves_categoryId_and_description_on_promotion() = runTest {
        val ingestor = ingestor()
        val gwalletId = ingestor.ingest(gwalletParsed(), needsVerification = false)!!
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        dbRule.transactionDao.updateCategory(gwalletId, food.id)
        dbRule.transactionDao.updateDescription(gwalletId, "ride home")

        ingestor.ingest(grabParsed(at = now.plusMillis(1_000)), needsVerification = false)

        val row = dbRule.transactionDao.getById(gwalletId)!!
        assertThat(row.categoryId).isEqualTo(food.id)
        assertThat(row.description).isEqualTo("ride home")
    }

    @Test
    fun preserves_row_id_and_createdAt_on_promotion() = runTest {
        val ingestor = ingestor()
        val gwalletId = ingestor.ingest(gwalletParsed(), needsVerification = false)!!
        val createdAtBefore = dbRule.transactionDao.getById(gwalletId)!!.createdAt

        ingestor.ingest(grabParsed(at = now.plusMillis(1_000)), needsVerification = false)

        val row = dbRule.transactionDao.getById(gwalletId)!!
        assertThat(row.id).isEqualTo(gwalletId)
        assertThat(row.createdAt).isEqualTo(createdAtBefore)
    }

    @Test
    fun does_not_collapse_across_different_amounts() = runTest {
        val ingestor = ingestor()
        ingestor.ingest(gwalletParsed(amount = 2500), needsVerification = false)
        ingestor.ingest(
            grabParsed(amount = 2600, at = now.plusMillis(1_000)),
            needsVerification = false,
        )

        val rows = dbRule.transactionDao.getAllOnce()
        assertThat(rows).hasSize(2)
    }

    @Test
    fun does_not_collapse_outside_5min_bucket() = runTest {
        // Now is 12:30:00Z so the bucket is [12:30:00, 12:35:00). 12:35:00 is the next bucket.
        val ingestor = ingestor()
        ingestor.ingest(
            gwalletParsed(at = Instant.parse("2026-05-09T12:34:00Z")),
            needsVerification = false,
        )
        ingestor.ingest(
            grabParsed(at = Instant.parse("2026-05-09T12:35:00Z")),
            needsVerification = false,
        )

        val rows = dbRule.transactionDao.getAllOnce()
        assertThat(rows).hasSize(2)
    }

    @Test
    fun user_added_package_is_treated_as_tier1() = runTest {
        val ingestor = ingestor()
        dbRule.userFacingSourceDao.insert(
            cy.txtracker.data.UserFacingSource("com.example.app", now),
        )
        ingestor.ingest(gwalletParsed(), needsVerification = false)
        ingestor.ingest(
            ParsedTransaction(
                amountMinor = 2500,
                currency = "MYR",
                merchantRaw = "Example",
                occurredAt = now.plusMillis(1_000),
                sourceApp = "com.example.app",
                rawText = "Example RM25.00",
                direction = Direction.OUT,
            ),
            needsVerification = false,
        )

        val rows = dbRule.transactionDao.getAllOnce()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().sourceApp).isEqualTo("com.example.app")
    }

    @Test
    fun manual_entries_treated_as_tier1_and_not_overwritten() = runTest {
        val ingestor = ingestor()
        val repo = TransactionRepository(
            database = dbRule.db,
            transactionDao = dbRule.transactionDao,
            categoryDao = dbRule.categoryDao,
            merchantMappingDao = dbRule.merchantMappingDao,
            descriptionMappingDao = dbRule.descriptionMappingDao,
            merchantNoteDao = dbRule.merchantNoteDao,
            userFacingSourceDao = dbRule.userFacingSourceDao,
        )
        val manualId = repo.addManualTransaction(
            amountMinor = 2500,
            merchantRaw = "Some Manual Merchant",
            categoryId = null,
            description = null,
            occurredAt = now,
            now = now,
        )!!

        // GWallet for same amount + bucket but different source → must NOT overwrite the manual row.
        ingestor.ingest(gwalletParsed(at = now.plusMillis(1_000)), needsVerification = false)

        val row = dbRule.transactionDao.getById(manualId)!!
        assertThat(row.sourceApp).isEqualTo(MANUAL_SOURCE_APP)
        assertThat(row.merchantRaw).isEqualTo("Some Manual Merchant")
    }

    @Test
    fun verification_flag_is_anded_on_promotion() = runTest {
        val ingestor = ingestor()
        // Existing row is permissive (needsVerification = true). Incoming Grab is strict
        // (needsVerification = false). After promotion the row should be verified.
        ingestor.ingest(gwalletParsed(), needsVerification = true)
        ingestor.ingest(grabParsed(at = now.plusMillis(1_000)), needsVerification = false)

        val row = dbRule.transactionDao.getAllOnce().single()
        assertThat(row.needsVerification).isFalse()
    }

    @Test
    fun promotion_recomputes_dedupe_key_so_repeat_grab_hash_collides() = runTest {
        val ingestor = ingestor()
        ingestor.ingest(gwalletParsed(), needsVerification = false)
        ingestor.ingest(grabParsed(at = now.plusMillis(1_000)), needsVerification = false)

        // A third identical Grab notification within the bucket must hash-dedupe-drop now,
        // not create a new row.
        val thirdId = ingestor.ingest(
            grabParsed(at = now.plusMillis(2_000)), needsVerification = false,
        )
        assertThat(thirdId).isNull()
        assertThat(dbRule.transactionDao.getAllOnce()).hasSize(1)
    }
}

private fun Instant.plusMillis(ms: Long): Instant =
    Instant.fromEpochMilliseconds(toEpochMilliseconds() + ms)
