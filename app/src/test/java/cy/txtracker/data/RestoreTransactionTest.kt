package cy.txtracker.data

import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.domain.TimeBucket
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * Drives [TransactionRepository.restoreTransactionBody] directly to avoid mocking Room's
 * `withTransaction` extension (same approach as PromotePoolEntryTest). Asserts the parent
 * transaction is re-inserted before its reimbursement children (FK ordering) and that the
 * original ids are preserved so the children re-link.
 */
class RestoreTransactionTest {

    private val now = Instant.parse("2026-06-08T12:00:00Z")
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val reimbursementDao = mockk<ReimbursementEntryDao>(relaxed = true)

    private val tx = Transaction(
        id = 77L,
        amountMinor = 5000L,
        currency = "MYR",
        merchantRaw = "Coffee Shop",
        merchantNormalized = "COFFEE SHOP",
        categoryId = null,
        description = null,
        occurredAt = now,
        timeBucket = TimeBucket.AFTERNOON,
        sourceApp = "com.bank",
        rawText = "Paid RM50.00 to Coffee Shop",
        direction = Direction.OUT,
        createdAt = now,
        notificationDedupeKey = "dedupe-77",
        needsVerification = true,
    )
    private val entry = ReimbursementEntry(
        id = 5L,
        transactionId = 77L,
        amountMinor = 2000L,
        destinationKind = FundingSourceKind.CASH,
        personLabel = "Alex",
        createdAt = now,
    )

    @Test
    fun restore_reinserts_transaction_then_children_with_original_ids() = runTest {
        makeRepo().restoreTransactionBody(tx, listOf(entry))

        coVerifyOrder {
            txDao.insert(match { it.id == 77L })
            reimbursementDao.insert(match { it.id == 5L && it.transactionId == 77L })
        }
    }

    @Test
    fun restore_with_no_children_only_reinserts_transaction() = runTest {
        makeRepo().restoreTransactionBody(tx, emptyList())

        coVerify(exactly = 1) { txDao.insert(match { it.id == 77L }) }
        coVerify(exactly = 0) { reimbursementDao.insert(any()) }
    }

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = mockk(relaxed = true),
        transactionDao = txDao,
        categoryDao = mockk(relaxed = true),
        merchantMappingDao = mockk(relaxed = true),
        descriptionMappingDao = mockk(relaxed = true),
        merchantNoteDao = mockk(relaxed = true),
        userFacingSourceDao = mockk(relaxed = true),
        approvedSourceDao = mockk(relaxed = true),
        capturedNotificationDao = mockk(relaxed = true),
        rejectedSourceDao = mockk(relaxed = true),
        trackedCurrencyDao = mockk(relaxed = true),
        tripWindowDao = mockk(relaxed = true),
        packageTextRewriteDao = mockk(relaxed = true),
        fundingSourceDao = mockk(relaxed = true),
        slDebitDao = mockk(relaxed = true),
        reimbursementEntryDao = reimbursementDao,
        categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
        descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk<FundingSourceClassifier>().also {
            io.mockk.coEvery { it.classify(any(), any(), any()) } returns 1L
        },
    )
}
