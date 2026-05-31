package cy.txtracker.ui.insights

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.Category
import org.junit.Test

class BudgetProgressTest {

    private fun category(id: Long, name: String, sortOrder: Int) =
        Category(id = id, name = name, color = 0, isCustom = false, sortOrder = sortOrder)

    @Test
    fun budget_progress_computes_fraction_under_budget() {
        val p = budgetProgress(spentMinor = 5000, budgetMinor = 10000)!!
        assertThat(p.fraction).isWithin(0.0001f).of(0.5f)
        assertThat(p.overBudget).isFalse()
    }

    @Test
    fun budget_progress_flags_over_budget() {
        val p = budgetProgress(spentMinor = 12000, budgetMinor = 10000)!!
        assertThat(p.fraction).isWithin(0.0001f).of(1.2f)
        assertThat(p.overBudget).isTrue()
    }

    @Test
    fun budget_progress_null_when_no_or_zero_budget() {
        assertThat(budgetProgress(5000, null)).isNull()
        assertThat(budgetProgress(5000, 0)).isNull()
    }

    @Test
    fun category_budget_progress_joins_spend_drops_unbudgeted_and_deleted_over_first() {
        val food = category(1, "Food", 0)
        val transport = category(2, "Transport", 1)
        val categoriesById = mapOf(1L to food, 2L to transport)
        val monthSpend = mapOf(1L to 12000L, 2L to 3000L)
        val budgets = mapOf(
            1L to 10000L, // Food: over budget
            2L to 10000L, // Transport: under budget
            99L to 5000L, // deleted category -> dropped
        )
        val result = categoryBudgetProgress(monthSpend, budgets, categoriesById)
        assertThat(result.map { it.category.name }).containsExactly("Food", "Transport").inOrder()
        assertThat(result[0].progress.overBudget).isTrue()
        assertThat(result[1].progress.spentMinor).isEqualTo(3000L)
    }

    @Test
    fun category_budget_progress_uses_zero_spend_when_no_transactions() {
        val food = category(1, "Food", 0)
        val result = categoryBudgetProgress(emptyMap(), mapOf(1L to 10000L), mapOf(1L to food))
        assertThat(result.single().progress.spentMinor).isEqualTo(0L)
        assertThat(result.single().progress.overBudget).isFalse()
    }
}
