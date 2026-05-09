package cy.txtracker.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.DbRule
import cy.txtracker.data.MerchantMapping
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategorizationEngineTest {

    @get:Rule val dbRule = DbRule()

    private fun engine() = CategorizationEngine(
        merchantMappingDao = dbRule.merchantMappingDao,
        categoryDao = dbRule.categoryDao,
    )

    private suspend fun categoryId(name: String): Long =
        dbRule.categoryDao.getAll().first { it.name == name }.id

    @Test
    fun mapping_takes_precedence_over_keyword_rule() = runTest {
        val transport = categoryId("Transport")
        val food = categoryId("Food")
        // STARBUCKS would normally match the Food keyword rule.
        // An explicit MerchantMapping must win.
        dbRule.merchantMappingDao.upsert(
            MerchantMapping("STARBUCKS", transport, Instant.parse("2026-05-09T12:00:00Z")),
        )

        val result = engine().categorize("STARBUCKS")
        assertThat(result).isEqualTo(transport)
        assertThat(result).isNotEqualTo(food)
    }

    @Test
    fun keyword_rule_used_when_no_mapping_exists() = runTest {
        val food = categoryId("Food")
        val result = engine().categorize("MCDONALDS PJ")
        assertThat(result).isEqualTo(food)
    }

    @Test
    fun returns_null_when_no_mapping_and_no_keyword_match() = runTest {
        val result = engine().categorize("CHONG TYRE AUTO")
        assertThat(result).isNull()
    }

    @Test
    fun returns_null_when_keyword_rule_points_to_deleted_category() = runTest {
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }
        dbRule.categoryDao.delete(food)
        // MCDONALDS still matches the Food keyword rule, but Food is gone — engine yields null.
        assertThat(engine().categorize("MCDONALDS PJ")).isNull()
    }

    @Test
    fun blank_merchant_returns_null_without_db_lookup() = runTest {
        assertThat(engine().categorize("")).isNull()
        assertThat(engine().categorize("   ")).isNull()
    }
}
