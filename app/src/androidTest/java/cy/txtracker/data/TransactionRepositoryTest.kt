package cy.txtracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.TimeBucket
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
        transactionDao = dbRule.transactionDao,
        categoryDao = dbRule.categoryDao,
        merchantMappingDao = dbRule.merchantMappingDao,
        descriptionMappingDao = dbRule.descriptionMappingDao,
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
            sourceApp = "com.gpay",
            amountMinor = 1250,
            merchantNormalized = "MCDONALDS",
            occurredAt = Instant.parse("2026-05-09T12:30:00Z"),
        )
        val sameWindow = computeDedupeKey(
            sourceApp = "com.gpay",
            amountMinor = 1250,
            merchantNormalized = "MCDONALDS",
            occurredAt = Instant.parse("2026-05-09T12:33:59Z"),
        )
        val nextWindow = computeDedupeKey(
            sourceApp = "com.gpay",
            amountMinor = 1250,
            merchantNormalized = "MCDONALDS",
            occurredAt = Instant.parse("2026-05-09T12:36:00Z"),
        )
        assertThat(a).isEqualTo(sameWindow)
        assertThat(a).isNotEqualTo(nextWindow)
    }
}
