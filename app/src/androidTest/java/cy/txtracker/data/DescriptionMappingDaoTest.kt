package cy.txtracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.TimeBucket
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DescriptionMappingDaoTest {

    @get:Rule val dbRule = DbRule()

    private val now = Instant.parse("2026-05-09T12:30:00Z")

    @Test
    fun merchant_bucket_upsert_is_unique_per_pair() = runTest {
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping("MCDONALDS", TimeBucket.MIDDAY, "lunch", now),
        )
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping("MCDONALDS", TimeBucket.EVENING, "dinner", now),
        )

        // Replace the MIDDAY entry; EVENING is untouched.
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping("MCDONALDS", TimeBucket.MIDDAY, "quick lunch", now),
        )

        val midday = dbRule.descriptionMappingDao.getMerchantBucket("MCDONALDS", TimeBucket.MIDDAY)
        val evening = dbRule.descriptionMappingDao.getMerchantBucket("MCDONALDS", TimeBucket.EVENING)
        assertThat(midday?.description).isEqualTo("quick lunch")
        assertThat(evening?.description).isEqualTo("dinner")
    }

    @Test
    fun getMerchantAny_returns_most_recent_when_no_bucket_match_used() = runTest {
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping(
                "STARBUCKS", TimeBucket.MORNING, "coffee",
                Instant.parse("2026-04-01T09:00:00Z"),
            ),
        )
        dbRule.descriptionMappingDao.upsertMerchant(
            MerchantDescriptionMapping(
                "STARBUCKS", TimeBucket.AFTERNOON, "afternoon coffee",
                Instant.parse("2026-05-01T16:00:00Z"),
            ),
        )

        val any = dbRule.descriptionMappingDao.getMerchantAny("STARBUCKS")
        assertThat(any?.description).isEqualTo("afternoon coffee")
    }

    @Test
    fun category_bucket_upsert_is_unique_per_pair_and_cascades_on_category_delete() = runTest {
        val food = dbRule.categoryDao.getAll().first { it.name == "Food" }

        dbRule.descriptionMappingDao.upsertCategory(
            CategoryDescriptionMapping(food.id, TimeBucket.MIDDAY, "lunch", now),
        )
        dbRule.descriptionMappingDao.upsertCategory(
            CategoryDescriptionMapping(food.id, TimeBucket.EVENING, "dinner", now),
        )

        assertThat(dbRule.descriptionMappingDao.getCategoryBucket(food.id, TimeBucket.MIDDAY)?.description)
            .isEqualTo("lunch")

        dbRule.categoryDao.delete(food)

        assertThat(dbRule.descriptionMappingDao.getCategoryBucket(food.id, TimeBucket.MIDDAY)).isNull()
        assertThat(dbRule.descriptionMappingDao.getCategoryBucket(food.id, TimeBucket.EVENING)).isNull()
    }
}
