package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class CustomLabelAndAutoPromoteTest {

    private val now = Instant.parse("2026-06-17T12:00:00Z")
    private val customLabelDao = mockk<CustomSourceLabelDao>(relaxed = true)

    @Test
    fun renameTrackedApp_upserts_row_with_trimmed_label() = runTest {
        val captured = slot<CustomSourceLabel>()
        val repo = makeRepo()
        repo.renameTrackedApp("my.com.gxsbank", "  My GX  ", now)
        coVerify { customLabelDao.upsert(capture(captured)) }
        assertThat(captured.captured.packageName).isEqualTo("my.com.gxsbank")
        assertThat(captured.captured.label).isEqualTo("My GX")
    }

    @Test
    fun renameTrackedApp_blank_label_clears_override() = runTest {
        val repo = makeRepo()
        repo.renameTrackedApp("my.com.gxsbank", "   ", now)
        coVerify { customLabelDao.delete("my.com.gxsbank") }
        coVerify(exactly = 0) { customLabelDao.upsert(any()) }
    }

    @Test
    fun setAutoPromote_true_inserts_and_false_deletes() = runTest {
        val dao = mockk<AutoPromoteSourceDao>(relaxed = true)
        val repo = makeRepoWith(autoPromote = dao)
        repo.setAutoPromote("my.com.gxsbank", true, now)
        coVerify { dao.insert(match { it.packageName == "my.com.gxsbank" }) }
        repo.setAutoPromote("my.com.gxsbank", false, now)
        coVerify { dao.delete("my.com.gxsbank") }
    }

    private fun makeRepoWith(autoPromote: AutoPromoteSourceDao): TransactionRepository =
        TransactionRepository(
            database = mockk(relaxed = true),
            transactionDao = mockk(relaxed = true),
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
            reimbursementEntryDao = mockk(relaxed = true),
            customSourceLabelDao = customLabelDao,
            autoPromoteSourceDao = autoPromote,
            categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
            descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
            heuristicExtractor = mockk(relaxed = true),
            rewriteEngine = mockk(relaxed = true),
            fundingSourceClassifier = mockk<FundingSourceClassifier>(relaxed = true),
        )

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = mockk(relaxed = true),
        transactionDao = mockk(relaxed = true),
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
        reimbursementEntryDao = mockk(relaxed = true),
        customSourceLabelDao = customLabelDao,
        autoPromoteSourceDao = mockk(relaxed = true),
        categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
        descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk<FundingSourceClassifier>(relaxed = true),
    )
}
