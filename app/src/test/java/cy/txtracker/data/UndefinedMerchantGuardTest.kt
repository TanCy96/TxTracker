package cy.txtracker.data

import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.domain.TimeBucket
import cy.txtracker.parsing.FundingSourceClassifier
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * The parser emits `UNDEFINED_MERCHANT` for notifications that have an amount and an
 * out-verb but no recipient (HSBC SMS-style). UNDEFINED is a sentinel, not a real merchant —
 * if learning paths were allowed to upsert mappings keyed by it, the user's first manual
 * label would poison every future unattributed tx.
 *
 * These tests pin the gating in `TransactionRepository.setCategory` / `setDescription` /
 * `setMerchantNote`: when the tx's normalized merchant is UNDEFINED, the merchant-keyed
 * write must be skipped, while category-keyed writes (which the user opted into) still
 * proceed normally for non-UNDEFINED txs.
 */
class UndefinedMerchantGuardTest {

    private val now = Instant.parse("2026-05-28T12:00:00Z")
    private val later = Instant.parse("2026-05-28T13:00:00Z")

    private fun tx(
        id: Long,
        merchant: String,
        categoryId: Long? = null,
        description: String? = null,
    ) = Transaction(
        id = id,
        amountMinor = 500L,
        currency = "MYR",
        merchantRaw = merchant,
        merchantNormalized = merchant,
        categoryId = categoryId,
        description = description,
        occurredAt = now,
        timeBucket = TimeBucket.MIDDAY,
        sourceApp = "com.hsbc.hsbcclassic",
        rawText = null,
        direction = Direction.OUT,
        createdAt = now,
        notificationDedupeKey = "k-$id",
    )

    @Test
    fun setCategory_skips_merchant_mapping_for_undefined_merchant() = runTest {
        val txDao = mockk<TransactionDao>(relaxUnitFun = true)
        val merchantMapping = mockk<MerchantMappingDao>(relaxUnitFun = true)
        val descriptionMapping = mockk<DescriptionMappingDao>(relaxUnitFun = true)
        coEvery { txDao.getById(1L) } returns tx(1L, UNDEFINED_MERCHANT)

        val repo = makeRepo(txDao, merchantMapping, descriptionMapping)
        repo.setCategory(txId = 1L, categoryId = 7L, now = later)

        coVerify(exactly = 1) { txDao.updateCategory(1L, 7L) }
        coVerify(exactly = 0) { merchantMapping.upsert(any()) }
    }

    @Test
    fun setCategory_still_writes_merchant_mapping_for_real_merchant() = runTest {
        val txDao = mockk<TransactionDao>(relaxUnitFun = true)
        val merchantMapping = mockk<MerchantMappingDao>(relaxUnitFun = true)
        val descriptionMapping = mockk<DescriptionMappingDao>(relaxUnitFun = true)
        coEvery { txDao.getById(1L) } returns tx(1L, "STARBUCKS")

        val repo = makeRepo(txDao, merchantMapping, descriptionMapping)
        repo.setCategory(txId = 1L, categoryId = 7L, now = later)

        coVerify(exactly = 1) {
            merchantMapping.upsert(
                MerchantMapping(merchantNormalized = "STARBUCKS", categoryId = 7L, learnedAt = later),
            )
        }
    }

    @Test
    fun setDescription_skips_merchant_description_mapping_for_undefined_merchant() = runTest {
        val txDao = mockk<TransactionDao>(relaxUnitFun = true)
        val merchantMapping = mockk<MerchantMappingDao>(relaxUnitFun = true)
        val descriptionMapping = mockk<DescriptionMappingDao>(relaxUnitFun = true)
        coEvery { txDao.getById(1L) } returns tx(1L, UNDEFINED_MERCHANT)

        val repo = makeRepo(txDao, merchantMapping, descriptionMapping)
        repo.setDescription(txId = 1L, description = "lunch with team", now = later)

        coVerify(exactly = 1) { txDao.updateDescription(1L, "lunch with team") }
        coVerify(exactly = 0) { descriptionMapping.upsertMerchant(any()) }
    }

    @Test
    fun setDescription_still_writes_merchant_mapping_for_real_merchant() = runTest {
        val txDao = mockk<TransactionDao>(relaxUnitFun = true)
        val merchantMapping = mockk<MerchantMappingDao>(relaxUnitFun = true)
        val descriptionMapping = mockk<DescriptionMappingDao>(relaxUnitFun = true)
        coEvery { txDao.getById(1L) } returns tx(1L, "STARBUCKS")

        val repo = makeRepo(txDao, merchantMapping, descriptionMapping)
        repo.setDescription(txId = 1L, description = "morning coffee", now = later)

        coVerify(exactly = 1) {
            descriptionMapping.upsertMerchant(
                MerchantDescriptionMapping(
                    merchantNormalized = "STARBUCKS",
                    timeBucket = TimeBucket.MIDDAY,
                    description = "morning coffee",
                    learnedAt = later,
                ),
            )
        }
    }

    @Test
    fun setMerchantNote_skips_write_for_undefined_merchant() = runTest {
        val merchantNote = mockk<MerchantNoteDao>(relaxUnitFun = true)
        val repo = makeRepo(
            txDao = mockk(relaxed = true),
            merchantMapping = mockk(relaxed = true),
            descriptionMapping = mockk(relaxed = true),
            merchantNote = merchantNote,
        )

        repo.setMerchantNote(UNDEFINED_MERCHANT, note = "should not save", now = later)

        coVerify(exactly = 0) { merchantNote.upsert(any()) }
        coVerify(exactly = 0) { merchantNote.delete(any()) }
    }

    @Test
    fun setMerchantNote_still_writes_for_real_merchant() = runTest {
        val merchantNote = mockk<MerchantNoteDao>(relaxUnitFun = true)
        val repo = makeRepo(
            txDao = mockk(relaxed = true),
            merchantMapping = mockk(relaxed = true),
            descriptionMapping = mockk(relaxed = true),
            merchantNote = merchantNote,
        )

        repo.setMerchantNote("STARBUCKS", note = "regular spot", now = later)

        coVerify(exactly = 1) {
            merchantNote.upsert(MerchantNote("STARBUCKS", "regular spot", later))
        }
    }

    private fun makeRepo(
        txDao: TransactionDao = mockk(relaxed = true),
        merchantMapping: MerchantMappingDao = mockk(relaxed = true),
        descriptionMapping: DescriptionMappingDao = mockk(relaxed = true),
        merchantNote: MerchantNoteDao = mockk(relaxed = true),
    ): TransactionRepository = TransactionRepository(
        database = mockk(relaxed = true),
        transactionDao = txDao,
        categoryDao = mockk(relaxed = true),
        merchantMappingDao = merchantMapping,
        descriptionMappingDao = descriptionMapping,
        merchantNoteDao = merchantNote,
        userFacingSourceDao = mockk(relaxed = true),
        approvedSourceDao = mockk(relaxed = true),
        capturedNotificationDao = mockk(relaxed = true),
        rejectedSourceDao = mockk(relaxed = true),
        trackedCurrencyDao = mockk(relaxed = true),
        tripWindowDao = mockk(relaxed = true),
        packageTextRewriteDao = mockk(relaxed = true),
        fundingSourceDao = mockk(relaxed = true),
        reimbursementEntryDao = mockk(relaxed = true),
        categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
        descriptionEngine = mockk<DescriptionEngine>(relaxed = true),
        heuristicExtractor = mockk(relaxed = true),
        rewriteEngine = mockk(relaxed = true),
        fundingSourceClassifier = mockk<FundingSourceClassifier>().also {
            coEvery { it.classify(any(), any(), any()) } returns 1L
        },
    )
}
