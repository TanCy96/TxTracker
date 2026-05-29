package cy.txtracker.data

import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FundingSourceMergeTest {

    /**
     * Verifies the merge sequence: relink transactions → delete source.
     *
     * Room's `withTransaction` is an inline suspend extension on `RoomDatabase`; mocking
     * it via `mockkStatic` requires type-inference workarounds that are too fragile across
     * mockk versions. Instead we stub the database as relaxed so the `withTransaction` call
     * returns immediately (the lambda is NOT executed by the mock), and we verify the DAO
     * calls by stubbing `withTransaction` to invoke its lambda argument directly via
     * `coEvery { db.withTransaction(captureLambda()) } coAnswers { lambda<...>().invoke() }`.
     *
     * Because the RoomDatabase.withTransaction extension is compiled to a static helper that
     * is difficult to intercept in unit-test JVM scope, the test instead works around it by
     * extracting the transactional body into `mergeBody` (an internal helper used only for
     * test isolation, analogous to `promotePoolEntryBody`). This mirrors the project's own
     * convention in PromotePoolEntryTest.
     */

    @Test
    fun mergeFundingSources_relinks_then_deletes() = runTest {
        val txDao = mockk<TransactionDao>(relaxUnitFun = true)
        val fundingDao = mockk<FundingSourceDao>(relaxUnitFun = true)
        val repo = makeRepo(mockk(relaxed = true), txDao, fundingDao)

        // Call the internal body directly to bypass withTransaction, following the same
        // isolation pattern as PromotePoolEntryTest → promotePoolEntryBody.
        repo.mergeFundingSourcesBody(sourceId = 7L, targetId = 12L)

        coVerifyOrder {
            txDao.relinkFundingSource(oldId = 7L, newId = 12L)
            fundingDao.deleteById(7L)
        }
    }

    @Test
    fun mergeFundingSources_noops_when_source_equals_target() = runTest {
        val txDao = mockk<TransactionDao>(relaxUnitFun = true)
        val fundingDao = mockk<FundingSourceDao>(relaxUnitFun = true)
        val repo = makeRepo(mockk(relaxed = true), txDao, fundingDao)

        repo.mergeFundingSources(sourceId = 5L, targetId = 5L)

        coVerify(exactly = 0) { txDao.relinkFundingSource(any(), any()) }
        coVerify(exactly = 0) { fundingDao.deleteById(any()) }
    }

    private fun makeRepo(
        database: TxDatabase,
        txDao: TransactionDao,
        fundingDao: FundingSourceDao,
    ): TransactionRepository = TransactionRepository(
        database = database,
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
        fundingSourceDao = fundingDao,
        slDebitDao = mockk(relaxed = true),
        categorizationEngine = mockk(relaxed = true),
        descriptionEngine = mockk(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk(relaxed = true),
    )
}
