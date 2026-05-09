package cy.txtracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MerchantMappingDaoTest {

    @get:Rule val dbRule = DbRule()

    @Test
    fun upsert_inserts_then_replaces_for_same_merchant() = runTest {
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val transport = dbRule.categoryDao.getAll().first { it.name == "Transport" }
        val now = Instant.parse("2026-05-09T12:30:00Z")

        dbRule.merchantMappingDao.upsert(MerchantMapping("MCDONALDS", food.id, now))
        assertThat(dbRule.merchantMappingDao.get("MCDONALDS")?.categoryId).isEqualTo(food.id)

        // Re-upsert with a different category — the merchant has only one mapping at a time.
        dbRule.merchantMappingDao.upsert(MerchantMapping("MCDONALDS", transport.id, now))
        assertThat(dbRule.merchantMappingDao.get("MCDONALDS")?.categoryId).isEqualTo(transport.id)
    }

    @Test
    fun deleting_category_cascades_to_merchant_mappings() = runTest {
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        val now = Instant.parse("2026-05-09T12:30:00Z")
        dbRule.merchantMappingDao.upsert(MerchantMapping("MCDONALDS", food.id, now))

        dbRule.categoryDao.delete(food)

        assertThat(dbRule.merchantMappingDao.get("MCDONALDS")).isNull()
    }
}
