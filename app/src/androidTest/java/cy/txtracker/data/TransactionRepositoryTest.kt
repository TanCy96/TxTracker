package cy.txtracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.TimeBucket
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionRepositoryTest {

    @get:Rule val dbRule = DbRule()

    private val now = Instant.parse("2026-05-09T12:30:00Z")

    private fun repo() = TransactionRepository(
        database = dbRule.db,
        transactionDao = dbRule.transactionDao,
        categoryDao = dbRule.categoryDao,
        merchantMappingDao = dbRule.merchantMappingDao,
        descriptionMappingDao = dbRule.descriptionMappingDao,
        merchantNoteDao = dbRule.merchantNoteDao,
        userFacingSourceDao = dbRule.userFacingSourceDao,
        approvedSourceDao = dbRule.approvedSourceDao,
        trackedCurrencyDao = dbRule.trackedCurrencyDao,
        tripWindowDao = dbRule.tripWindowDao,
    )

    @Test
    fun insert_returns_id_first_time_and_null_on_dedupe_collision() = runTest {
        val repo = repo()
        val tx = txAt(now, dedupeKey = "fixed-key")

        val firstId = repo.insert(tx)
        val secondId = repo.insert(tx.copy(id = 0, amountMinor = 9999))

        assertThat(firstId).isNotNull()
        assertThat(secondId).isNull()
    }

    @Test
    fun setCategory_with_learning_writes_merchant_mapping() = runTest {
        val repo = repo()
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val txId = repo.insert(txAt(now, dedupeKey = "k"))!!

        repo.setCategory(txId = txId, categoryId = food.id, learnMapping = true, now = now)

        val mapping = dbRule.merchantMappingDao.get("MCDONALDS")
        assertThat(mapping).isNotNull()
        assertThat(mapping!!.categoryId).isEqualTo(food.id)
        assertThat(repo.getTransaction(txId)?.categoryId).isEqualTo(food.id)
    }

    @Test
    fun setCategory_propagates_existing_description_to_category_bucket_mapping() = runTest {
        // If the user labels description first then category, adopting a category should still
        // populate the (category, bucket) mapping so cross-merchant generalization works.
        val repo = repo()
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val txId = repo.insert(txAt(now, description = "lunch", dedupeKey = "k"))!!

        // No category yet → category-bucket mapping not yet written (by setDescription's flow,
        // because the tx had no categoryId at description-save time).
        // Now the user assigns a category.
        repo.setCategory(txId = txId, categoryId = food.id, learnMapping = true, now = now)

        val mapping = dbRule.descriptionMappingDao.getCategoryBucket(food.id, TimeBucket.MIDDAY)
        assertThat(mapping?.description).isEqualTo("lunch")
    }

    @Test
    fun setCategory_without_learning_skips_mapping_write() = runTest {
        val repo = repo()
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val txId = repo.insert(txAt(now, dedupeKey = "k"))!!

        repo.setCategory(txId = txId, categoryId = food.id, learnMapping = false, now = now)

        assertThat(dbRule.merchantMappingDao.get("MCDONALDS")).isNull()
        assertThat(repo.getTransaction(txId)?.categoryId).isEqualTo(food.id)
    }

    @Test
    fun setDescription_writes_both_mapping_tables_when_learning() = runTest {
        val repo = repo()
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val txId = repo.insert(txAt(now, categoryId = food.id, dedupeKey = "k"))!!

        repo.setDescription(txId = txId, description = "lunch", learnMappings = true, now = now)

        val merchantMapping = dbRule.descriptionMappingDao
            .getMerchantBucket("MCDONALDS", TimeBucket.MIDDAY)
        val categoryMapping = dbRule.descriptionMappingDao
            .getCategoryBucket(food.id, TimeBucket.MIDDAY)
        assertThat(merchantMapping?.description).isEqualTo("lunch")
        assertThat(categoryMapping?.description).isEqualTo("lunch")
        assertThat(repo.getTransaction(txId)?.description).isEqualTo("lunch")
    }

    @Test
    fun setDescription_blank_clears_field_and_does_not_learn() = runTest {
        val repo = repo()
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val txId = repo.insert(txAt(now, categoryId = food.id, dedupeKey = "k"))!!

        repo.setDescription(txId = txId, description = "   ", learnMappings = true, now = now)

        assertThat(repo.getTransaction(txId)?.description).isNull()
        assertThat(
            dbRule.descriptionMappingDao.getMerchantBucket("MCDONALDS", TimeBucket.MIDDAY),
        ).isNull()
        assertThat(
            dbRule.descriptionMappingDao.getCategoryBucket(food.id, TimeBucket.MIDDAY),
        ).isNull()
    }

    @Test
    fun setDescription_skips_category_mapping_when_tx_has_no_category() = runTest {
        val repo = repo()
        val txId = repo.insert(txAt(now, categoryId = null, dedupeKey = "k"))!!

        repo.setDescription(txId = txId, description = "snack", learnMappings = true, now = now)

        // Merchant mapping still written — it's category-independent.
        assertThat(
            dbRule.descriptionMappingDao.getMerchantBucket("MCDONALDS", TimeBucket.MIDDAY),
        ).isNotNull()
        // Without a category on the transaction there's nothing to learn at the category level.
        dbRule.descriptionMappingDao.observeAllCategory().test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun computeDedupeKey_is_deterministic_and_5min_window_collides() {
        val a = computeDedupeKey(
            amountMinor = 1250,
            merchantNormalized = "MCDONALDS",
            occurredAt = Instant.parse("2026-05-09T12:30:00Z"),
            currency = "MYR",
        )
        val sameWindow = computeDedupeKey(
            amountMinor = 1250,
            merchantNormalized = "MCDONALDS",
            occurredAt = Instant.parse("2026-05-09T12:33:59Z"),
            currency = "MYR",
        )
        val nextWindow = computeDedupeKey(
            amountMinor = 1250,
            merchantNormalized = "MCDONALDS",
            occurredAt = Instant.parse("2026-05-09T12:36:00Z"),
            currency = "MYR",
        )
        assertThat(a).isEqualTo(sameWindow)
        assertThat(a).isNotEqualTo(nextWindow)
    }

    @Test
    fun setMerchant_renames_row_and_regenerates_dedupe_key() = runTest {
        val repo = repo()
        val txId = repo.insert(txAt(now, merchant = "CIMB (review)", dedupeKey = "old-key"))!!
        val before = repo.getTransaction(txId)!!

        val ok = repo.setMerchant(txId, "TAOBAO")

        assertThat(ok).isTrue()
        val after = repo.getTransaction(txId)!!
        assertThat(after.merchantRaw).isEqualTo("TAOBAO")
        assertThat(after.merchantNormalized).isEqualTo("TAOBAO")
        // Dedupe key was regenerated against the new normalized merchant so a future
        // re-capture of the same notification with the corrected merchant will dedupe
        // against this row instead of creating a duplicate.
        assertThat(after.notificationDedupeKey).isNotEqualTo(before.notificationDedupeKey)
        assertThat(after.notificationDedupeKey).isEqualTo(
            computeDedupeKey(
                amountMinor = after.amountMinor,
                merchantNormalized = "TAOBAO",
                occurredAt = after.occurredAt,
                currency = after.currency,
            ),
        )
    }

    @Test
    fun setMerchant_trims_and_normalizes() = runTest {
        val repo = repo()
        val txId = repo.insert(txAt(now, merchant = "CIMB (review)", dedupeKey = "k"))!!

        repo.setMerchant(txId, "  taobao  ")

        val after = repo.getTransaction(txId)!!
        assertThat(after.merchantRaw).isEqualTo("taobao")
        assertThat(after.merchantNormalized).isEqualTo("TAOBAO")
    }

    @Test
    fun setMerchant_rejects_blank() = runTest {
        val repo = repo()
        val txId = repo.insert(txAt(now, merchant = "ORIGINAL", dedupeKey = "k"))!!

        assertThat(repo.setMerchant(txId, "   ")).isFalse()
        assertThat(repo.getTransaction(txId)?.merchantRaw).isEqualTo("ORIGINAL")
    }

    @Test
    fun setMerchant_returns_false_on_dedupe_collision() = runTest {
        // Two rows already exist with the SAME amount + 5-min bucket. The first owns the
        // dedupe key for ("TAOBAO", amount, bucket). Renaming the second to "TAOBAO"
        // would regenerate the same key and collide; the repo must refuse the rename and
        // leave both rows untouched.
        val repo = repo()
        val taobaoKey = computeDedupeKey(
            amountMinor = 1250,
            merchantNormalized = "TAOBAO",
            occurredAt = now,
            currency = "MYR",
        )
        val existing = repo.insert(
            txAt(now, merchant = "TAOBAO", dedupeKey = taobaoKey),
        )!!
        val renaming = repo.insert(
            txAt(now, merchant = "CIMB (review)", dedupeKey = "renaming-key"),
        )!!

        val ok = repo.setMerchant(renaming, "TAOBAO")

        assertThat(ok).isFalse()
        assertThat(repo.getTransaction(renaming)?.merchantRaw).isEqualTo("CIMB (review)")
        assertThat(repo.getTransaction(existing)?.merchantRaw).isEqualTo("TAOBAO")
    }

    @Test
    fun computeDedupeKey_collapses_across_source_apps() {
        // Same payment seen by GWallet and by the bank app must produce the same dedupe key,
        // so the second insertion is dropped.
        val gwallet = computeDedupeKey(
            amountMinor = 53000,
            merchantNormalized = "CHONG TYRE AUTO",
            occurredAt = Instant.parse("2026-05-09T12:30:00Z"),
            currency = "MYR",
        )
        val bank = computeDedupeKey(
            amountMinor = 53000,
            merchantNormalized = "CHONG TYRE AUTO",
            occurredAt = Instant.parse("2026-05-09T12:31:30Z"),
            currency = "MYR",
        )
        assertThat(gwallet).isEqualTo(bank)
    }

    @Test
    fun openTrip_clears_needsCurrencyConfirmation_for_rows_in_range() = runTest {
        val repo = repo()
        val parked = repo.insert(
            txAt(now, merchant = "WISE", currency = "GBP", dedupeKey = "k1")
                .copy(needsCurrencyConfirmation = true),
        )!!

        repo.openTrip(
            currency = "GBP",
            startAt = now - 1.hours,
            endAt = now + 1.hours,
            now = now,
        )

        val after = repo.getTransaction(parked)!!
        assertThat(after.needsCurrencyConfirmation).isFalse()
    }

    @Test
    fun openTrip_leaves_rows_outside_window_parked() = runTest {
        val repo = repo()
        val outsideWindow = now - 24.hours
        val parked = repo.insert(
            txAt(outsideWindow, merchant = "WISE", currency = "GBP", dedupeKey = "k1")
                .copy(needsCurrencyConfirmation = true),
        )!!

        repo.openTrip(
            currency = "GBP",
            startAt = now - 1.hours,
            endAt = now + 1.hours,
            now = now,
        )

        val after = repo.getTransaction(parked)!!
        assertThat(after.needsCurrencyConfirmation).isTrue()
    }

    @Test
    fun openTrip_open_ended_clears_far_future_rows() = runTest {
        val repo = repo()
        val far = now + (365 * 24).hours
        val parked = repo.insert(
            txAt(far, merchant = "WISE", currency = "SGD", dedupeKey = "k1")
                .copy(needsCurrencyConfirmation = true),
        )!!

        repo.openTrip(currency = "SGD", startAt = now, endAt = null, now = now)

        assertThat(repo.getTransaction(parked)!!.needsCurrencyConfirmation).isFalse()
    }

    @Test
    fun setCurrency_to_currency_without_active_trip_parks_the_row() = runTest {
        val repo = repo()
        val txId = repo.insert(txAt(now, merchant = "MAYBE_GBP", currency = "MYR", dedupeKey = "k1"))!!

        val ok = repo.setCurrency(txId, "GBP")

        assertThat(ok).isTrue()
        val after = repo.getTransaction(txId)!!
        assertThat(after.currency).isEqualTo("GBP")
        assertThat(after.needsCurrencyConfirmation).isTrue()
    }

    @Test
    fun setCurrency_with_active_trip_promotes_the_row() = runTest {
        val repo = repo()
        repo.openTrip("GBP", startAt = now - 1.hours, endAt = now + 1.hours, now = now)
        val txId = repo.insert(txAt(now, merchant = "MAYBE_GBP", currency = "MYR", dedupeKey = "k1"))!!

        repo.setCurrency(txId, "GBP")

        val after = repo.getTransaction(txId)!!
        assertThat(after.currency).isEqualTo("GBP")
        assertThat(after.needsCurrencyConfirmation).isFalse()
    }

    @Test
    fun setCurrency_regenerates_dedupe_key() = runTest {
        val repo = repo()
        val txId = repo.insert(txAt(now, currency = "MYR", dedupeKey = "old"))!!
        val before = repo.getTransaction(txId)!!.notificationDedupeKey

        repo.setCurrency(txId, "GBP")

        val after = repo.getTransaction(txId)!!.notificationDedupeKey
        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun setCurrency_returns_false_on_dedupe_collision() = runTest {
        val repo = repo()
        // Pre-seed a row owning the dedupe key that "MAYBE_GBP" → GBP would generate.
        val gbpKey = computeDedupeKey(
            amountMinor = 1250,
            merchantNormalized = "MAYBE_GBP",
            occurredAt = now,
            currency = "GBP",
        )
        val existing = repo.insert(
            txAt(now, merchant = "MAYBE_GBP", currency = "GBP", dedupeKey = gbpKey),
        )!!
        val renaming = repo.insert(
            txAt(now, merchant = "MAYBE_GBP", currency = "MYR", dedupeKey = "renaming"),
        )!!

        val ok = repo.setCurrency(renaming, "GBP")

        assertThat(ok).isFalse()
        assertThat(repo.getTransaction(renaming)?.currency).isEqualTo("MYR")
        assertThat(repo.getTransaction(existing)?.currency).isEqualTo("GBP")
    }

    @Test
    fun closeTrip_sets_endAt_without_re_flagging_promoted_rows() = runTest {
        val repo = repo()
        val tripId = repo.openTrip("GBP", startAt = now - 1.hours, endAt = null, now = now)
        val txId = repo.insert(
            txAt(now, merchant = "WISE", currency = "GBP", dedupeKey = "k1")
                .copy(needsCurrencyConfirmation = false),
        )!!

        repo.closeTrip(tripId, endAt = now + 1.hours)

        // Trip endAt updated.
        val trip = dbRule.tripWindowDao.get(tripId)
        assertThat(trip?.endAt).isEqualTo(now + 1.hours)
        // Row stays promoted.
        assertThat(repo.getTransaction(txId)!!.needsCurrencyConfirmation).isFalse()
    }
}
