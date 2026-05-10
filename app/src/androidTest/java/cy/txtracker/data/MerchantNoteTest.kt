package cy.txtracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MerchantNoteTest {

    @get:Rule val dbRule = DbRule()

    private fun repo() = TransactionRepository(
        database = dbRule.db,
        transactionDao = dbRule.transactionDao,
        categoryDao = dbRule.categoryDao,
        merchantMappingDao = dbRule.merchantMappingDao,
        descriptionMappingDao = dbRule.descriptionMappingDao,
        merchantNoteDao = dbRule.merchantNoteDao,
    )

    private val now = Instant.parse("2026-05-09T12:30:00Z")

    @Test
    fun setMerchantNote_inserts_then_replaces_for_same_merchant() = runTest {
        val r = repo()
        r.setMerchantNote("PERSON A", "P2P transfer, food stall context", now)
        assertThat(r.getMerchantNote("PERSON A")?.note).isEqualTo("P2P transfer, food stall context")

        // Re-set replaces (same merchant, only one note at a time).
        r.setMerchantNote("PERSON A", "actually the kopitiam", now)
        assertThat(r.getMerchantNote("PERSON A")?.note).isEqualTo("actually the kopitiam")
    }

    @Test
    fun setMerchantNote_blank_clears_the_row() = runTest {
        val r = repo()
        r.setMerchantNote("PERSON A", "warung uncle", now)
        assertThat(r.getMerchantNote("PERSON A")).isNotNull()

        r.setMerchantNote("PERSON A", "   ", now)  // whitespace only
        assertThat(r.getMerchantNote("PERSON A")).isNull()
    }

    @Test
    fun setMerchantNote_null_clears_the_row() = runTest {
        val r = repo()
        r.setMerchantNote("PERSON A", "warung uncle", now)
        r.setMerchantNote("PERSON A", null, now)
        assertThat(r.getMerchantNote("PERSON A")).isNull()
    }

    @Test
    fun setMerchantNote_trims_whitespace() = runTest {
        val r = repo()
        r.setMerchantNote("PERSON A", "   warung uncle   ", now)
        assertThat(r.getMerchantNote("PERSON A")?.note).isEqualTo("warung uncle")
    }

    @Test
    fun notes_are_keyed_per_merchant_so_two_merchants_keep_independent_notes() = runTest {
        val r = repo()
        r.setMerchantNote("PERSON A", "warung uncle", now)
        r.setMerchantNote("CHONG TYRE AUTO", "tyre shop", now)

        assertThat(r.getMerchantNote("PERSON A")?.note).isEqualTo("warung uncle")
        assertThat(r.getMerchantNote("CHONG TYRE AUTO")?.note).isEqualTo("tyre shop")
    }
}
