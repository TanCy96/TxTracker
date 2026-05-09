package cy.txtracker.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.CategoryDescriptionMapping
import cy.txtracker.data.DbRule
import cy.txtracker.data.MerchantDescriptionMapping
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DescriptionEngineTest {

    @get:Rule val dbRule = DbRule()

    private fun engine() = DescriptionEngine(
        descriptionMappingDao = dbRule.descriptionMappingDao,
    )

    private suspend fun foodId(): Long =
        dbRule.categoryDao.getAll().first { it.name == "Food" }.id

    private val baseTime = Instant.parse("2026-05-09T04:30:00Z")  // 12:30 KL → MIDDAY

    @Test
    fun merchant_plus_bucket_match_wins_over_everything_else() = runTest {
        val food = foodId()
        // Most-specific entry: STARBUCKS at MIDDAY → "lunch coffee"
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping("STARBUCKS", TimeBucket.MIDDAY, "lunch coffee", baseTime),
        )
        // A weaker merchant-only entry that should be ignored due to (1).
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping("STARBUCKS", TimeBucket.MORNING, "morning coffee", baseTime),
        )
        // A weaker category-only entry that should be ignored due to (1).
        dbRule.descriptionMappingDao.upsertCategory(
            CategoryDescriptionMapping(food, TimeBucket.MIDDAY, "lunch", baseTime),
        )

        val result = engine().suggest("STARBUCKS", food, TimeBucket.MIDDAY)
        assertThat(result).isEqualTo("lunch coffee")
    }

    @Test
    fun merchant_only_match_used_when_bucket_specific_missing() = runTest {
        val food = foodId()
        // No MIDDAY entry for STARBUCKS, but an EVENING one exists. Most-recent wins.
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping(
                "STARBUCKS", TimeBucket.EVENING, "espresso",
                Instant.parse("2026-05-08T13:00:00Z"),
            ),
        )
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping(
                "STARBUCKS", TimeBucket.AFTERNOON, "afternoon latte",
                Instant.parse("2026-05-09T08:30:00Z"),  // most recent
            ),
        )

        val result = engine().suggest("STARBUCKS", food, TimeBucket.MIDDAY)
        assertThat(result).isEqualTo("afternoon latte")
    }

    @Test
    fun category_plus_bucket_used_when_no_merchant_entry() = runTest {
        val food = foodId()
        // The user-flagged real case: label one Food+MIDDAY tx as "lunch"; the next midday
        // Food purchase from a different merchant should pick up the same suggestion.
        dbRule.descriptionMappingDao.upsertCategory(
            CategoryDescriptionMapping(food, TimeBucket.MIDDAY, "lunch", baseTime),
        )

        // Different merchant — no merchant-level entry — falls through to category mapping.
        val result = engine().suggest("BURGER KING", food, TimeBucket.MIDDAY)
        assertThat(result).isEqualTo("lunch")
    }

    @Test
    fun category_mapping_skipped_for_uncategorized_transactions() = runTest {
        val food = foodId()
        dbRule.descriptionMappingDao.upsertCategory(
            CategoryDescriptionMapping(food, TimeBucket.MIDDAY, "lunch", baseTime),
        )
        // Tx has categoryId = null, so the engine has no category to look up.
        val result = engine().suggest("UNKNOWN MERCHANT", null, TimeBucket.MIDDAY)
        assertThat(result).isNull()
    }

    @Test
    fun returns_null_when_nothing_matches() = runTest {
        val result = engine().suggest("NEW MERCHANT", foodId(), TimeBucket.MIDDAY)
        assertThat(result).isNull()
    }

    @Test
    fun blank_merchant_short_circuits_with_null() = runTest {
        assertThat(engine().suggest("", foodId(), TimeBucket.MIDDAY)).isNull()
        assertThat(engine().suggest("   ", foodId(), TimeBucket.MIDDAY)).isNull()
    }
}
