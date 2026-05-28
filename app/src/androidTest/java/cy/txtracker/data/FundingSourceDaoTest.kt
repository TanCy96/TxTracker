package cy.txtracker.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FundingSourceDaoTest {

    @get:Rule val dbRule = DbRule()

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun fundingSource(
        kind: FundingSourceKind = FundingSourceKind.DEBIT_BANK,
        displayName: String = "Maybank",
        last4: String? = "1234",
        sourceAppHint: String? = "com.maybank2u.life",
    ) = FundingSource(
        kind = kind,
        displayName = displayName,
        last4 = last4,
        sourceAppHint = sourceAppHint,
        isUserNamed = false,
        createdAt = t0,
        updatedAt = t0,
    )

    // --- Unique-index enforcement ---

    @Test
    fun insert_two_sources_with_same_sourceAppHint_and_last4_throws_constraint_exception() = runTest {
        val first = fundingSource(kind = FundingSourceKind.DEBIT_BANK, displayName = "Maybank Debit")
        val second = fundingSource(kind = FundingSourceKind.CREDIT_CARD, displayName = "Maybank Credit")

        dbRule.fundingSourceDao.insert(first)

        var threw = false
        try {
            dbRule.fundingSourceDao.insert(second)
        } catch (e: SQLiteConstraintException) {
            threw = true
        }

        assertThat(threw).isTrue()
    }

    // --- ON DELETE SET NULL ---

    @Test
    fun deleting_funding_source_sets_fundingSourceId_null_on_referencing_transactions() = runTest {
        val sourceId = dbRule.fundingSourceDao.insert(fundingSource())

        val tx = txAt(
            occurredAt = t0,
            merchant = "MAMAK",
            dedupeKey = "fs-test-k1",
        ).copy(fundingSourceId = sourceId)
        val txId = dbRule.transactionDao.insert(tx)

        // Verify the FK is set before deletion.
        val before = dbRule.transactionDao.getById(txId)
        assertThat(before?.fundingSourceId).isEqualTo(sourceId)

        // Delete the source — FK ON DELETE SET NULL should null out the transaction's FK.
        dbRule.fundingSourceDao.deleteById(sourceId)

        val after = dbRule.transactionDao.getById(txId)
        assertThat(after?.fundingSourceId).isNull()
    }
}
