package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * Repository-level test for the promote-pool-entry flow. Asserts the three
 * spec-required effects of `promotePoolEntry`:
 *   1. Inserts a new `transactions` row built from the pool entry + edit overrides.
 *   2. Flips the pool entry's disposition to PROMOTED with the new tx id.
 *   3. Upserts an ApprovedSource for the pool entry's package (so future captures
 *      from that package land on the confident lane).
 *
 * Drives [TransactionRepository.promotePoolEntryBody] directly to avoid mocking
 * Room's `withTransaction` extension. Production callers go through
 * `promotePoolEntry` which wraps the body in a transaction; the wrapper is a
 * one-liner whose only job is atomicity, so the body covers the logic.
 */
class PromotePoolEntryTest {

    private val now = Instant.parse("2026-05-25T12:00:00Z")

    private val database = mockk<TxDatabase>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val capturedDao = mockk<CapturedNotificationDao>(relaxed = true)
    private val approvedDao = mockk<ApprovedSourceDao>(relaxed = true)
    private val rejectedDao = mockk<RejectedSourceDao>(relaxed = true)

    private val poolEntry = CapturedNotification(
        id = 42L,
        packageName = "com.example.bank",
        postedAt = now,
        amountMinor = 1234L,
        currency = "MYR",
        rawText = "RM12.34 to ML Dessert",
        rewrittenText = null,
        disposition = CaptureDisposition.PENDING,
        promotedToTxId = null,
        capturedAt = now,
    )

    @Test
    fun promote_inserts_tx_flips_disposition_and_upserts_approved_source() = runTest {
        coEvery { capturedDao.get(42L) } returns poolEntry
        val insertedTx = slot<Transaction>()
        coEvery { txDao.insert(capture(insertedTx)) } returns 99L

        val repo = makeRepo()
        val newTxId = repo.promotePoolEntryBody(
            id = 42L,
            edit = PromoteEdit(
                merchantRaw = "ML Traditional Dessert",
                amountMinor = poolEntry.amountMinor,
                currency = poolEntry.currency,
                occurredAt = poolEntry.postedAt,
                categoryId = 7L,
                description = "lunch",
            ),
            now = now,
        )

        assertThat(newTxId).isEqualTo(99L)

        // 1. Transactions row built from the pool entry + edit.
        with(insertedTx.captured) {
            assertThat(merchantRaw).isEqualTo("ML Traditional Dessert")
            assertThat(amountMinor).isEqualTo(1234L)
            assertThat(currency).isEqualTo("MYR")
            assertThat(categoryId).isEqualTo(7L)
            assertThat(description).isEqualTo("lunch")
            assertThat(sourceApp).isEqualTo("com.example.bank")
            assertThat(rawText).isEqualTo("RM12.34 to ML Dessert")
            assertThat(direction).isEqualTo(Direction.OUT)
            assertThat(needsVerification).isFalse()
            assertThat(needsCurrencyConfirmation).isFalse() // MYR
        }

        // 2. Pool entry flipped to PROMOTED with the new tx id.
        coVerify(exactly = 1) { capturedDao.markPromoted(42L, 99L) }

        // 3. ApprovedSource upserted for the source package.
        coVerify(exactly = 1) {
            approvedDao.insert(match { it.packageName == "com.example.bank" })
        }
        // Also un-rejects the package — if the user manually rejected it earlier and
        // is now promoting an entry, the explicit promote should override.
        coVerify(exactly = 1) { rejectedDao.delete("com.example.bank") }
    }

    @Test
    fun promote_returns_null_when_pool_entry_missing() = runTest {
        coEvery { capturedDao.get(42L) } returns null

        val repo = makeRepo()
        val result = repo.promotePoolEntryBody(
            id = 42L,
            edit = PromoteEdit(
                merchantRaw = "X",
                amountMinor = 100L,
                currency = "MYR",
                occurredAt = now,
                categoryId = null,
                description = null,
            ),
            now = now,
        )

        assertThat(result).isNull()
        coVerify(exactly = 0) { txDao.insert(any()) }
        coVerify(exactly = 0) { capturedDao.markPromoted(any(), any()) }
        coVerify(exactly = 0) { approvedDao.insert(any()) }
    }

    @Test
    fun promote_returns_null_when_merchant_is_blank() = runTest {
        coEvery { capturedDao.get(42L) } returns poolEntry

        val repo = makeRepo()
        val result = repo.promotePoolEntryBody(
            id = 42L,
            edit = PromoteEdit(
                merchantRaw = "   ",
                amountMinor = 1234L,
                currency = "MYR",
                occurredAt = now,
                categoryId = null,
                description = null,
            ),
            now = now,
        )

        assertThat(result).isNull()
        coVerify(exactly = 0) { txDao.insert(any()) }
        coVerify(exactly = 0) { capturedDao.markPromoted(any(), any()) }
    }

    private fun makeRepo(): TransactionRepository = TransactionRepository(
        database = database,
        transactionDao = txDao,
        categoryDao = mockk(relaxed = true),
        merchantMappingDao = mockk(relaxed = true),
        descriptionMappingDao = mockk(relaxed = true),
        merchantNoteDao = mockk(relaxed = true),
        userFacingSourceDao = mockk(relaxed = true),
        approvedSourceDao = approvedDao,
        capturedNotificationDao = capturedDao,
        rejectedSourceDao = rejectedDao,
        trackedCurrencyDao = mockk(relaxed = true),
        tripWindowDao = mockk(relaxed = true),
        packageTextRewriteDao = mockk(relaxed = true),
        categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
        descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
    )
}
