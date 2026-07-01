package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultTripCategoriesTest {
    @Test
    fun template_has_expected_travel_categories_in_order() {
        val names = DefaultTripCategories.template.map { it.name }
        assertThat(names).containsExactly(
            "Accommodation", "Food & Drink", "Transport", "Attractions",
            "Shopping", "Groceries", "Fees & Cash", "Other",
        ).inOrder()
    }

    @Test
    fun template_colors_are_distinct() {
        val colors = DefaultTripCategories.template.map { it.color }
        assertThat(colors).containsNoDuplicates()
    }
}
