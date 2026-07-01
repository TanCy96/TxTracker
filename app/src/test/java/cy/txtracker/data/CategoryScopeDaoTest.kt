package cy.txtracker.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CategoryScopeDaoTest {
    @Test
    fun category_defaults_to_global_scope() {
        val c = Category(name = "Food", color = 1, isCustom = false, sortOrder = 0)
        assertThat(c.tripId).isNull()
    }
}
