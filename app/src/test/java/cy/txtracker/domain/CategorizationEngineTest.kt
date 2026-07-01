package cy.txtracker.domain

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import cy.txtracker.data.CategoryDao
import cy.txtracker.data.MerchantMapping
import cy.txtracker.data.MerchantMappingDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class CategorizationEngineTest {

    private val merchantDao = mockk<MerchantMappingDao>()
    private val categoryDao = mockk<CategoryDao>()
    private val engine = CategorizationEngine(merchantDao, categoryDao)
    private val now = Instant.parse("2026-05-15T12:00:00Z")

    private fun cat(id: Long, name: String, sortOrder: Int, pattern: String? = null) =
        Category(id = id, name = name, color = 0, isCustom = false, sortOrder = sortOrder, keywordPattern = pattern)

    @Test
    fun returns_null_for_blank_merchant() = runTest {
        coEvery { merchantDao.get(any()) } returns null
        coEvery { merchantDao.getAllOrderedByRecency() } returns emptyList()
        coEvery { categoryDao.getAllGlobal() } returns emptyList()
        assertThat(engine.categorize("")).isNull()
    }

    @Test
    fun exact_merchant_mapping_wins() = runTest {
        coEvery { merchantDao.get("STARBUCKS") } returns
            MerchantMapping("STARBUCKS", categoryId = 42L, learnedAt = now)
        assertThat(engine.categorize("STARBUCKS")).isEqualTo(42L)
    }

    @Test
    fun longest_prefix_mapping_used_when_exact_misses() = runTest {
        coEvery { merchantDao.get("STARBUCKS RESERVE BANGSAR") } returns null
        coEvery { merchantDao.getAllOrderedByRecency() } returns listOf(
            MerchantMapping("STARBUCKS RESERVE", categoryId = 7L, learnedAt = now),
            MerchantMapping("STARBUCKS", categoryId = 3L, learnedAt = now),
        )
        // Longer stored wins.
        assertThat(engine.categorize("STARBUCKS RESERVE BANGSAR")).isEqualTo(7L)
    }

    @Test
    fun user_keyword_pattern_used_when_no_mapping_matches() = runTest {
        coEvery { merchantDao.get("BURGER KING JALAN AMPANG") } returns null
        coEvery { merchantDao.getAllOrderedByRecency() } returns emptyList()
        coEvery { categoryDao.getAllGlobal() } returns listOf(
            cat(id = 1L, name = "Coffee", sortOrder = 0, pattern = "STARBUCKS|TEALIVE"),
            cat(id = 2L, name = "Dining", sortOrder = 1, pattern = "MCDONALD|\\bKFC\\b|BURGER\\s?KING"),
        )
        assertThat(engine.categorize("BURGER KING JALAN AMPANG")).isEqualTo(2L)
    }

    @Test
    fun sortOrder_decides_pattern_priority_on_overlap() = runTest {
        // Both categories include STARBUCKS in their pattern. Lower sortOrder wins.
        coEvery { merchantDao.get("STARBUCKS KLCC") } returns null
        coEvery { merchantDao.getAllOrderedByRecency() } returns emptyList()
        coEvery { categoryDao.getAllGlobal() } returns listOf(
            cat(id = 1L, name = "Coffee", sortOrder = 0, pattern = "STARBUCKS|TEALIVE"),
            cat(id = 2L, name = "Dining", sortOrder = 1, pattern = "MCDONALD|STARBUCKS"),
        )
        assertThat(engine.categorize("STARBUCKS KLCC")).isEqualTo(1L)
    }

    @Test
    fun pattern_match_is_case_insensitive() = runTest {
        coEvery { merchantDao.get("starbucks") } returns null
        coEvery { merchantDao.getAllOrderedByRecency() } returns emptyList()
        coEvery { categoryDao.getAllGlobal() } returns listOf(
            cat(id = 1L, name = "Coffee", sortOrder = 0, pattern = "STARBUCKS"),
        )
        assertThat(engine.categorize("starbucks")).isEqualTo(1L)
    }

    @Test
    fun returns_null_when_nothing_matches() = runTest {
        coEvery { merchantDao.get(any()) } returns null
        coEvery { merchantDao.getAllOrderedByRecency() } returns emptyList()
        coEvery { categoryDao.getAllGlobal() } returns listOf(
            cat(id = 1L, name = "Coffee", sortOrder = 0, pattern = "STARBUCKS"),
        )
        assertThat(engine.categorize("RANDOM SHOP")).isNull()
    }

    @Test
    fun categories_with_null_pattern_are_skipped() = runTest {
        coEvery { merchantDao.get(any()) } returns null
        coEvery { merchantDao.getAllOrderedByRecency() } returns emptyList()
        coEvery { categoryDao.getAllGlobal() } returns listOf(
            cat(id = 1L, name = "Coffee", sortOrder = 0, pattern = null),
            cat(id = 2L, name = "Dining", sortOrder = 1, pattern = "STARBUCKS"),
        )
        assertThat(engine.categorize("STARBUCKS")).isEqualTo(2L)
    }

    @Test
    fun keyword_step_reads_global_categories_only() = runTest {
        coEvery { merchantDao.get(any()) } returns null
        coEvery { merchantDao.getAllOrderedByRecency() } returns emptyList()
        coEvery { categoryDao.getAllGlobal() } returns listOf(
            cat(id = 1L, name = "Coffee", sortOrder = 0, pattern = "STARBUCKS"),
        )
        assertThat(engine.categorize("STARBUCKS KLCC")).isEqualTo(1L)
        io.mockk.coVerify(exactly = 0) { categoryDao.getAll() }
    }
}
