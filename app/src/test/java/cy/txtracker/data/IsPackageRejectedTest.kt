package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `isPackageRejected` is a thin passthrough to [RejectedSourceDao.isRejected]; this guards the
 * wiring (the listener relies on it to decide whether to pool-instead-of-parse).
 */
class IsPackageRejectedTest {

    private val rejectedDao = mockk<RejectedSourceDao>(relaxed = true)

    @Test
    fun returns_true_when_dao_reports_rejected() = runTest {
        coEvery { rejectedDao.isRejected("com.google.android.gm") } returns true
        assertThat(makeRepo().isPackageRejected("com.google.android.gm")).isTrue()
    }

    @Test
    fun returns_false_when_dao_reports_not_rejected() = runTest {
        coEvery { rejectedDao.isRejected("com.bank") } returns false
        assertThat(makeRepo().isPackageRejected("com.bank")).isFalse()
    }

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
        rejectedSourceDao = rejectedDao,
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
            io.mockk.coEvery { it.classify(any(), any(), any()) } returns 1L
        },
    )
}
