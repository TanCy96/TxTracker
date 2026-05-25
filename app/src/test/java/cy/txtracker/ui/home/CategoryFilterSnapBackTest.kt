package cy.txtracker.ui.home

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import org.junit.Test

class CategoryFilterSnapBackTest {

    private val foodCat = Category(id = 5L, name = "Food", color = 0, isCustom = false, sortOrder = 0)
    private val foodEntry = CategoryBreakdownEntry(category = foodCat, totalMinor = 19950L)
    private val unverifiedEntry = CategoryBreakdownEntry(category = null, totalMinor = 5000L)

    @Test
    fun `category filter survives when its breakdown chip is present`() {
        val result = snapStaleHomeCategoryToAll(
            filter = HomeFilter.Category(5L),
            breakdown = listOf(foodEntry, unverifiedEntry),
        )
        assertThat(result).isEqualTo(HomeFilter.Category(5L))
    }

    @Test
    fun `category filter snaps to All when its breakdown chip is gone`() {
        val result = snapStaleHomeCategoryToAll(
            filter = HomeFilter.Category(99L),
            breakdown = listOf(foodEntry),
        )
        assertThat(result).isEqualTo(HomeFilter.All)
    }

    @Test
    fun `category filter snaps to All when breakdown is empty`() {
        val result = snapStaleHomeCategoryToAll(
            filter = HomeFilter.Category(5L),
            breakdown = emptyList(),
        )
        assertThat(result).isEqualTo(HomeFilter.All)
    }

    @Test
    fun `non-category filters are never modified`() {
        listOf(HomeFilter.All, HomeFilter.Pending, HomeFilter.Unverified, HomeFilter.CurrencyReview).forEach { f ->
            val result = snapStaleHomeCategoryToAll(filter = f, breakdown = emptyList())
            assertThat(result).isEqualTo(f)
        }
    }
}
