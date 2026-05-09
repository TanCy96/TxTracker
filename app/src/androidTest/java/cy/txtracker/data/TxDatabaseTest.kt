package cy.txtracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TxDatabaseTest {

    @get:Rule val dbRule = DbRule()

    @Test
    fun seed_callback_inserts_default_categories_in_sortOrder() = runTest {
        val categories = dbRule.categoryDao.getAll()
        assertThat(categories).hasSize(10)
        assertThat(categories.map { it.name })
            .containsExactly(
                "Food", "Groceries", "Transport", "Fuel", "Parking",
                "Apparel", "Entertainment", "Utilities", "Health", "Other",
            )
            .inOrder()
        assertThat(categories.all { !it.isCustom }).isTrue()
        assertThat(categories.map { it.sortOrder }).isEqualTo((0..9).toList())
    }
}
