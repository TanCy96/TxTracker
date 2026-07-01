package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.domain.TimeBucket
import cy.txtracker.parsing.FundingSourceClassifier
import cy.txtracker.parsing.HeuristicExtractor
import cy.txtracker.parsing.ParsedTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class BatchTransactionOpsTest {

    private val now = Instant.parse("2026-06-17T12:00:00Z")
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val approvedDao = mockk<ApprovedSourceDao>(relaxed = true)
    private val capturedDao = mockk<CapturedNotificationDao>(relaxed = true)
    private val reimbursementDao = mockk<ReimbursementEntryDao>(relaxed = true)
    private val tripWindowDao = mockk<TripWindowDao>(relaxed = true)
    private val heuristic = mockk<HeuristicExtractor>(relaxed = true)

    @Test
    fun confirmTransactionsBody_clears_flag_and_approves_each_distinct_non_manual_source() = runTest {
        coEvery { txDao.distinctSourceAppsForIds(listOf(1L, 2L)) } returns
            listOf("my.com.gxsbank", MANUAL_SOURCE_APP)
        val repo = makeRepo()
        repo.confirmTransactionsBody(listOf(1L, 2L), now)
        coVerify { txDao.clearNeedsVerification(listOf(1L, 2L)) }
        coVerify(exactly = 1) { approvedDao.insert(match { it.packageName == "my.com.gxsbank" }) }
        coVerify(exactly = 0) { approvedDao.insert(match { it.packageName == MANUAL_SOURCE_APP }) }
    }

    @Test
    fun deleteTransactionsBody_returns_snapshots_and_deletes() = runTest {
        val tx = sampleTx(5L)
        coEvery { txDao.getById(5L) } returns tx
        coEvery { reimbursementDao.getForTransaction(5L) } returns emptyList()
        val repo = makeRepo()
        val snapshots = repo.deleteTransactionsBody(listOf(5L))
        assertThat(snapshots).hasSize(1)
        assertThat(snapshots[0].transaction.id).isEqualTo(5L)
        coVerify { txDao.delete(5L) }
    }

    @Test
    fun promotePoolEntriesBody_uses_heuristic_merchant_when_resolved() = runTest {
        val pending = CapturedNotification(
            id = 9L, packageName = "my.com.gxsbank", postedAt = now, amountMinor = 400L,
            currency = "MYR", rawText = "RM4.00 to CHEE NYOK LAN is successful",
            rewrittenText = null, disposition = CaptureDisposition.PENDING,
            promotedToTxId = null, capturedAt = now, dedupeKey = "d9",
        )
        coEvery { capturedDao.get(9L) } returns pending
        coEvery { txDao.insert(any()) } returns 77L
        coEvery {
            heuristic.extract("RM4.00 to CHEE NYOK LAN is successful", "my.com.gxsbank", now)
        } returns ParsedTransaction(
            amountMinor = 400L, currency = "MYR", merchantRaw = "CHEE NYOK LAN",
            occurredAt = now, sourceApp = "my.com.gxsbank",
            rawText = "RM4.00 to CHEE NYOK LAN is successful", direction = Direction.OUT,
        )
        val repo = makeRepo()
        repo.promotePoolEntriesBody(listOf(9L), now)
        coVerify { txDao.insert(match { it.merchantRaw == "CHEE NYOK LAN" && it.needsVerification }) }
        coVerify { capturedDao.markPromoted(9L, 77L) }
    }

    @Test
    fun promotePoolEntriesBody_falls_back_to_undefined_when_heuristic_fails() = runTest {
        val pending = CapturedNotification(
            id = 10L, packageName = "com.bank", postedAt = now, amountMinor = 999L,
            currency = "MYR", rawText = "balance RM9.99 something", rewrittenText = null,
            disposition = CaptureDisposition.PENDING, promotedToTxId = null,
            capturedAt = now, dedupeKey = "d10",
        )
        coEvery { capturedDao.get(10L) } returns pending
        coEvery { txDao.insert(any()) } returns 78L
        coEvery { heuristic.extract(any(), any(), any(), any()) } returns null
        val repo = makeRepo()
        repo.promotePoolEntriesBody(listOf(10L), now)
        coVerify { txDao.insert(match { it.merchantRaw == UNDEFINED_MERCHANT && it.needsVerification }) }
    }

    @Test
    fun promotePoolEntriesBody_skips_entry_and_count_when_insert_collides() = runTest {
        val pending = CapturedNotification(
            id = 11L, packageName = "com.bank", postedAt = now, amountMinor = 500L,
            currency = "MYR", rawText = "RM5.00 to SHOP is successful", rewrittenText = null,
            disposition = CaptureDisposition.PENDING, promotedToTxId = null,
            capturedAt = now, dedupeKey = "d11",
        )
        coEvery { capturedDao.get(11L) } returns pending
        coEvery { txDao.insert(any()) } returns -1L   // dedupe collision (OnConflictStrategy.IGNORE)
        val repo = makeRepo()
        val promotedCount = repo.promotePoolEntriesBody(listOf(11L), now)
        assertThat(promotedCount).isEqualTo(0)
        coVerify(exactly = 0) { capturedDao.markPromoted(any(), any()) }
        coVerify(exactly = 0) { approvedDao.insert(any()) }
    }

    // A foreign-currency entry promoted in a batch, with no trip open for that currency,
    // must be parked for currency review (needsCurrencyConfirmation = true) — otherwise it
    // is invisible: Home is MYR-only, and the Foreign tab only shows rows inside a trip window.
    @Test
    fun promotePoolEntriesBody_parks_foreign_entry_for_review_when_no_active_trip() = runTest {
        val pending = CapturedNotification(
            id = 12L, packageName = "com.wise.android", postedAt = now, amountMinor = 2500L,
            currency = "USD", rawText = "You spent 25.00 USD", rewrittenText = null,
            disposition = CaptureDisposition.PENDING, promotedToTxId = null,
            capturedAt = now, dedupeKey = "d12",
        )
        coEvery { capturedDao.get(12L) } returns pending
        coEvery { txDao.insert(any()) } returns 80L
        coEvery { heuristic.extract(any(), any(), any(), any()) } returns null
        coEvery { tripWindowDao.findActiveAt("USD", now) } returns null
        val repo = makeRepo()
        repo.promotePoolEntriesBody(listOf(12L), now)
        coVerify { txDao.insert(match { it.currency == "USD" && it.needsCurrencyConfirmation }) }
    }

    // When a trip IS open for the currency at that instant, the row belongs in the trip's
    // Foreign view and must NOT be parked for review.
    @Test
    fun promotePoolEntriesBody_does_not_flag_foreign_entry_when_active_trip_covers_it() = runTest {
        val pending = CapturedNotification(
            id = 13L, packageName = "com.wise.android", postedAt = now, amountMinor = 2500L,
            currency = "USD", rawText = "You spent 25.00 USD", rewrittenText = null,
            disposition = CaptureDisposition.PENDING, promotedToTxId = null,
            capturedAt = now, dedupeKey = "d13",
        )
        coEvery { capturedDao.get(13L) } returns pending
        coEvery { txDao.insert(any()) } returns 81L
        coEvery { heuristic.extract(any(), any(), any(), any()) } returns null
        coEvery { tripWindowDao.findActiveAt("USD", now) } returns TripWindow(
            id = 1L, currency = "USD", startAt = now, endAt = null, createdAt = now,
        )
        val repo = makeRepo()
        repo.promotePoolEntriesBody(listOf(13L), now)
        coVerify { txDao.insert(match { it.currency == "USD" && !it.needsCurrencyConfirmation }) }
    }

    // A plain MYR batch promote never needs currency review.
    @Test
    fun promotePoolEntriesBody_does_not_flag_myr_entry() = runTest {
        val pending = CapturedNotification(
            id = 14L, packageName = "com.bank", postedAt = now, amountMinor = 500L,
            currency = "MYR", rawText = "RM5.00 to SHOP is successful", rewrittenText = null,
            disposition = CaptureDisposition.PENDING, promotedToTxId = null,
            capturedAt = now, dedupeKey = "d14",
        )
        coEvery { capturedDao.get(14L) } returns pending
        coEvery { txDao.insert(any()) } returns 82L
        coEvery { heuristic.extract(any(), any(), any(), any()) } returns null
        val repo = makeRepo()
        repo.promotePoolEntriesBody(listOf(14L), now)
        coVerify { txDao.insert(match { it.currency == "MYR" && !it.needsCurrencyConfirmation }) }
    }

    private fun sampleTx(id: Long) = Transaction(
        id = id, amountMinor = 100L, currency = "MYR", merchantRaw = "X",
        merchantNormalized = "X", categoryId = null, description = null,
        occurredAt = now, timeBucket = TimeBucket.AFTERNOON, sourceApp = "p",
        rawText = null, direction = Direction.OUT, createdAt = now,
        notificationDedupeKey = "k",
    )

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = mockk(relaxed = true),
        transactionDao = txDao,
        categoryDao = mockk(relaxed = true),
        merchantMappingDao = mockk(relaxed = true),
        descriptionMappingDao = mockk(relaxed = true),
        merchantNoteDao = mockk(relaxed = true),
        userFacingSourceDao = mockk(relaxed = true),
        approvedSourceDao = approvedDao,
        capturedNotificationDao = capturedDao,
        rejectedSourceDao = mockk(relaxed = true),
        trackedCurrencyDao = mockk(relaxed = true),
        tripWindowDao = tripWindowDao,
        packageTextRewriteDao = mockk(relaxed = true),
        fundingSourceDao = mockk(relaxed = true),
        slDebitDao = mockk(relaxed = true),
        reimbursementEntryDao = reimbursementDao,
        customSourceLabelDao = mockk(relaxed = true),
        autoPromoteSourceDao = mockk(relaxed = true),
        categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
        descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
        heuristicExtractor = heuristic,
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk<FundingSourceClassifier>().also {
            coEvery { it.classify(any(), any(), any()) } returns 1L
        },
    )
}
