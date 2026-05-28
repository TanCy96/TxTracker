package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.domain.TimeBucket
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class CategoryBackfillTest {

    private val now = Instant.parse("2026-05-15T12:00:00Z")
    private val txDao = mockk<TransactionDao>(relaxUnitFun = true)
    private val categorization = mockk<CategorizationEngine>()
    private val description = mockk<DescriptionEngine>()

    private fun tx(
        id: Long,
        merchant: String,
        bucket: TimeBucket = TimeBucket.MIDDAY,
        categoryId: Long? = null,
        desc: String? = null,
    ) = Transaction(
        id = id,
        amountMinor = 100L,
        currency = "MYR",
        merchantRaw = merchant,
        merchantNormalized = merchant,
        categoryId = categoryId,
        description = desc,
        occurredAt = now,
        timeBucket = bucket,
        sourceApp = "x",
        rawText = null,
        direction = Direction.OUT,
        createdAt = now,
        notificationDedupeKey = "k-$id",
    )

    @Test
    fun recategorize_updates_only_rows_engine_can_resolve() = runTest {
        val rows = listOf(
            tx(1L, "STARBUCKS KLCC"),
            tx(2L, "RANDOM SHOP"),
        )
        coEvery { txDao.getNullCategoryRows() } returns rows
        coEvery { categorization.categorize("STARBUCKS KLCC") } returns 7L
        coEvery { categorization.categorize("RANDOM SHOP") } returns null

        val repo = makeRepo(txDao, categorization, description)
        val count = repo.recategorizeNullRows()

        assertThat(count).isEqualTo(1)
        coVerify(exactly = 1) { txDao.updateCategory(1L, 7L) }
        coVerify(exactly = 0) { txDao.updateCategory(2L, any()) }
    }

    @Test
    fun redescribe_updates_only_rows_engine_can_resolve() = runTest {
        val rows = listOf(
            tx(1L, "STARBUCKS KLCC", categoryId = 7L),
            tx(2L, "UNKNOWN", categoryId = null),
        )
        coEvery { txDao.getNullDescriptionRows() } returns rows
        coEvery { description.suggest("STARBUCKS KLCC", 7L, TimeBucket.MIDDAY) } returns "coffee"
        coEvery { description.suggest("UNKNOWN", null, TimeBucket.MIDDAY) } returns null

        val repo = makeRepo(txDao, categorization, description)
        val count = repo.redescribeNullRows()

        assertThat(count).isEqualTo(1)
        coVerify(exactly = 1) { txDao.updateDescription(1L, "coffee") }
        coVerify(exactly = 0) { txDao.updateDescription(2L, any()) }
    }

    private fun makeRepo(
        txDao: TransactionDao,
        cat: CategorizationEngine,
        desc: DescriptionEngine,
    ): TransactionRepository = TransactionRepository(
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
        categorizationEngine = cat,
        descriptionEngine = desc,
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk<FundingSourceClassifier>().also {
            io.mockk.coEvery { it.classify(any(), any(), any()) } returns 1L
        },
    )
}
