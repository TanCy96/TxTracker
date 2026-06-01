package cy.txtracker.notify

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.YearMonth
import org.junit.Test

class BudgetAlertsTest {

    private val may = YearMonth(2026, 5)

    private fun fire(
        monthSpend: Long = 0,
        overallBudget: Long? = null,
        categorySpend: Map<Long, Long> = emptyMap(),
        categoryBudgets: Map<Long, Long> = emptyMap(),
        categoryNames: Map<Long, String> = emptyMap(),
        alreadyFired: Set<String> = emptySet(),
    ) = budgetAlertsToFire(
        yearMonth = may,
        monthSpendMinor = monthSpend,
        overallBudgetMinor = overallBudget,
        categorySpendMinor = categorySpend,
        categoryBudgetsMinor = categoryBudgets,
        categoryNames = categoryNames,
        alreadyFired = alreadyFired,
    )

    @Test
    fun no_alert_below_80_percent() {
        assertThat(fire(monthSpend = 5000, overallBudget = 10000)).isEmpty()
    }

    @Test
    fun fires_80_at_threshold() {
        val alerts = fire(monthSpend = 8000, overallBudget = 10000)
        assertThat(alerts.map { it.key }).containsExactly("2026-5:overall:80")
        assertThat(alerts.single().threshold).isEqualTo(80)
    }

    @Test
    fun fires_only_100_when_over_budget() {
        val alerts = fire(monthSpend = 12000, overallBudget = 10000)
        assertThat(alerts.map { it.key }).containsExactly("2026-5:overall:100")
    }

    @Test
    fun respects_already_fired() {
        assertThat(fire(monthSpend = 8500, overallBudget = 10000, alreadyFired = setOf("2026-5:overall:80"))).isEmpty()
    }

    @Test
    fun fires_100_even_if_80_already_fired() {
        val alerts = fire(monthSpend = 10500, overallBudget = 10000, alreadyFired = setOf("2026-5:overall:80"))
        assertThat(alerts.map { it.key }).containsExactly("2026-5:overall:100")
    }

    @Test
    fun category_alerts_use_name_and_skip_deleted() {
        val alerts = fire(
            categorySpend = mapOf(1L to 12000L, 2L to 9000L),
            categoryBudgets = mapOf(1L to 10000L, 2L to 10000L, 99L to 5000L),
            categoryNames = mapOf(1L to "Food", 2L to "Transport"), // 99 has no name → deleted
        )
        // Food 120% → 100; Transport 90% → 80; category 99 skipped.
        assertThat(alerts.map { it.label to it.threshold })
            .containsExactly("Food" to 100, "Transport" to 80)
    }

    @Test
    fun no_budgets_no_alerts() {
        assertThat(fire(monthSpend = 9999)).isEmpty()
    }
}
