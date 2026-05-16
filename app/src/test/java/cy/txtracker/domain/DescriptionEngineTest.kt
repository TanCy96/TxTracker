package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.CategoryDescriptionMapping
import cy.txtracker.data.DescriptionMappingDao
import cy.txtracker.data.MerchantDescriptionMapping
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class DescriptionEngineTest {

    private val dao = mockk<DescriptionMappingDao>()
    private val engine = DescriptionEngine(dao)
    private val now = Instant.parse("2026-05-15T12:00:00Z")

    @Test
    fun returns_null_for_blank_merchant() = runTest {
        assertThat(engine.suggest("", categoryId = 1L, bucket = TimeBucket.MIDDAY)).isNull()
    }

    @Test
    fun exact_merchant_bucket_wins() = runTest {
        coEvery { dao.getMerchantBucket("STARBUCKS", TimeBucket.MIDDAY) } returns
            MerchantDescriptionMapping("STARBUCKS", TimeBucket.MIDDAY, "coffee", now)
        assertThat(engine.suggest("STARBUCKS", null, TimeBucket.MIDDAY)).isEqualTo("coffee")
    }

    @Test
    fun exact_merchant_any_bucket_used_when_bucket_miss() = runTest {
        coEvery { dao.getMerchantBucket("STARBUCKS", TimeBucket.MIDDAY) } returns null
        coEvery { dao.getMerchantAny("STARBUCKS") } returns
            MerchantDescriptionMapping("STARBUCKS", TimeBucket.MORNING, "coffee", now)
        assertThat(engine.suggest("STARBUCKS", null, TimeBucket.MIDDAY)).isEqualTo("coffee")
    }

    @Test
    fun prefix_merchant_used_when_exact_misses() = runTest {
        coEvery { dao.getMerchantBucket("STARBUCKS KLCC", TimeBucket.MIDDAY) } returns null
        coEvery { dao.getMerchantAny("STARBUCKS KLCC") } returns null
        coEvery { dao.getAllMerchantKeys() } returns listOf("STARBUCKS")
        coEvery { dao.getMerchantBucket("STARBUCKS", TimeBucket.MIDDAY) } returns
            MerchantDescriptionMapping("STARBUCKS", TimeBucket.MIDDAY, "coffee", now)
        assertThat(engine.suggest("STARBUCKS KLCC", null, TimeBucket.MIDDAY)).isEqualTo("coffee")
    }

    @Test
    fun category_bucket_used_when_merchant_paths_all_miss() = runTest {
        coEvery { dao.getMerchantBucket("STARBUCKS KLCC", TimeBucket.MIDDAY) } returns null
        coEvery { dao.getMerchantAny("STARBUCKS KLCC") } returns null
        coEvery { dao.getAllMerchantKeys() } returns emptyList()
        coEvery { dao.getCategoryBucket(7L, TimeBucket.MIDDAY) } returns
            CategoryDescriptionMapping(7L, TimeBucket.MIDDAY, "lunch", now)
        assertThat(engine.suggest("STARBUCKS KLCC", 7L, TimeBucket.MIDDAY)).isEqualTo("lunch")
    }

    @Test
    fun category_any_bucket_is_final_fallback() = runTest {
        coEvery { dao.getMerchantBucket(any(), any()) } returns null
        coEvery { dao.getMerchantAny(any()) } returns null
        coEvery { dao.getAllMerchantKeys() } returns emptyList()
        coEvery { dao.getCategoryBucket(7L, TimeBucket.MIDDAY) } returns null
        coEvery { dao.getCategoryAnyBucket(7L) } returns
            CategoryDescriptionMapping(7L, TimeBucket.MORNING, "lunch", now)
        assertThat(engine.suggest("X", 7L, TimeBucket.MIDDAY)).isEqualTo("lunch")
    }

    @Test
    fun returns_null_when_no_category_and_no_merchant_match() = runTest {
        coEvery { dao.getMerchantBucket(any(), any()) } returns null
        coEvery { dao.getMerchantAny(any()) } returns null
        coEvery { dao.getAllMerchantKeys() } returns emptyList()
        assertThat(engine.suggest("X", null, TimeBucket.MIDDAY)).isNull()
    }
}
