package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class CapturedNotificationDedupTest {

    private val postedAt = Instant.parse("2026-05-28T12:00:00Z")
    private val now = Instant.parse("2026-05-28T12:00:01Z")

    @Test
    fun second_insert_with_same_key_is_dropped_by_dao_conflict() = runTest {
        val capturedDao = mockk<CapturedNotificationDao>()
        // First insert succeeds; second returns -1 to simulate UNIQUE-index conflict.
        val captured = slot<CapturedNotification>()
        coEvery { capturedDao.insert(capture(captured)) } returnsMany listOf(42L, -1L)

        val repo = makeRepo(capturedDao)

        val first = repo.insertCapturedNotification(
            packageName = "com.hsbc.hsbcclassic",
            postedAt = postedAt,
            amountMinor = 1300L,
            currency = "MYR",
            rawText = "Debited your A/C ending 0025 with MYR 13.00",
            rewrittenText = null,
            now = now,
        )
        val second = repo.insertCapturedNotification(
            packageName = "com.hsbc.hsbcclassic",
            postedAt = postedAt,
            amountMinor = 1300L,
            currency = "MYR",
            rawText = "Debited your A/C ending 0025 with MYR 13.00",
            rewrittenText = null,
            now = now,
        )

        assertThat(first).isEqualTo(42L)
        assertThat(second).isNull()
        coVerify(exactly = 2) { capturedDao.insert(any()) }
    }

    @Test
    fun computes_same_dedupe_key_for_identical_inputs() = runTest {
        val capturedDao = mockk<CapturedNotificationDao>()
        val keys = mutableListOf<String>()
        coEvery { capturedDao.insert(any()) } answers {
            keys.add(firstArg<CapturedNotification>().dedupeKey)
            keys.size.toLong()
        }

        val repo = makeRepo(capturedDao)
        repeat(2) {
            repo.insertCapturedNotification(
                packageName = "com.test",
                postedAt = postedAt,
                amountMinor = 500L,
                currency = "MYR",
                rawText = "RM 5.00 spent at FOO",
                rewrittenText = null,
                now = now,
            )
        }

        assertThat(keys).hasSize(2)
        assertThat(keys[0]).isEqualTo(keys[1])
        assertThat(keys[0]).isNotEmpty()
    }

    private fun makeRepo(capturedDao: CapturedNotificationDao): TransactionRepository =
        TransactionRepository(
            database = mockk(relaxed = true),
            transactionDao = mockk(relaxed = true),
            categoryDao = mockk(relaxed = true),
            merchantMappingDao = mockk(relaxed = true),
            descriptionMappingDao = mockk(relaxed = true),
            merchantNoteDao = mockk(relaxed = true),
            userFacingSourceDao = mockk(relaxed = true),
            approvedSourceDao = mockk(relaxed = true),
            capturedNotificationDao = capturedDao,
            rejectedSourceDao = mockk(relaxed = true),
            trackedCurrencyDao = mockk(relaxed = true),
            tripWindowDao = mockk(relaxed = true),
            packageTextRewriteDao = mockk(relaxed = true),
            fundingSourceDao = mockk(relaxed = true),
            categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
            descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
            heuristicExtractor = mockk(relaxed = true),
            rewriteEngine = mockk(relaxed = true),
            fundingSourceClassifier = mockk<FundingSourceClassifier>(relaxed = true),
        )
}
