package cy.txtracker.data

import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * Verifies that [TransactionRepository.setCategory] does NOT learn a global
 * merchant→category mapping when the target category is trip-scoped
 * (Category.tripId != null), even when learnMapping = true.
 */
class SetCategoryLearnGuardTest {

    private val now = Instant.parse("2026-07-01T00:00:00Z")
    private val database = mockk<TxDatabase>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val merchantMappingDao = mockk<MerchantMappingDao>(relaxed = true)

    private val stubTx = Transaction(
        id = 1L,
        amountMinor = 1000L,
        currency = "MYR",
        merchantRaw = "Starbucks",
        merchantNormalized = "starbucks",
        categoryId = null,
        description = null,
        occurredAt = now,
        timeBucket = cy.txtracker.domain.bucketOf(now),
        sourceApp = "manual",
        rawText = null,
        direction = Direction.OUT,
        createdAt = now,
        notificationDedupeKey = "test:1",
        needsVerification = false,
    )

    @Test
    fun setCategory_with_tripCategory_does_NOT_learn_mapping() = runTest {
        // Arrange: category is trip-scoped (tripId != null)
        val tripCategory = Category(
            id = 42L,
            name = "Food",
            color = 0,
            isCustom = false,
            sortOrder = 0,
            tripId = 55L, // non-null => trip-scoped
        )
        coEvery { categoryDao.getById(42L) } returns tripCategory
        coEvery { txDao.getById(1L) } returns stubTx

        val repo = makeRepo()
        repo.setCategory(txId = 1L, categoryId = 42L, learnMapping = true, now = now)

        // The transaction's category MUST be set
        coVerify(exactly = 1) { txDao.updateCategory(1L, 42L) }
        // But the merchant→category mapping must NOT be written
        coVerify(exactly = 0) { merchantMappingDao.upsert(any()) }
    }

    @Test
    fun setCategory_with_globalCategory_DOES_learn_mapping() = runTest {
        // Arrange: category is global (tripId == null)
        val globalCategory = Category(
            id = 10L,
            name = "Groceries",
            color = 0,
            isCustom = false,
            sortOrder = 0,
            tripId = null, // null => global
        )
        coEvery { categoryDao.getById(10L) } returns globalCategory
        coEvery { txDao.getById(1L) } returns stubTx

        val repo = makeRepo()
        repo.setCategory(txId = 1L, categoryId = 10L, learnMapping = true, now = now)

        // The transaction's category MUST be set
        coVerify(exactly = 1) { txDao.updateCategory(1L, 10L) }
        // And the merchant→category mapping MUST be learned
        coVerify(exactly = 1) { merchantMappingDao.upsert(any()) }
    }

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = database,
        transactionDao = txDao,
        categoryDao = categoryDao,
        merchantMappingDao = merchantMappingDao,
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
        reimbursementEntryDao = mockk(relaxed = true),
        customSourceLabelDao = mockk(relaxed = true),
        autoPromoteSourceDao = mockk(relaxed = true),
        categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
        descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk<FundingSourceClassifier>().also {
            coEvery { it.classify(any(), any(), any()) } returns 1L
        },
    )
}
