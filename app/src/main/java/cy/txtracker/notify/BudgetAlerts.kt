package cy.txtracker.notify

import cy.txtracker.domain.YearMonth

/** A budget that has crossed a threshold this month and hasn't been alerted yet. */
data class BudgetAlert(
    val key: String,
    val label: String,
    val threshold: Int,
    val spentMinor: Long,
    val budgetMinor: Long,
)

/**
 * Returns the budget alerts that should fire now: the overall budget and each per-category budget
 * that has crossed a threshold this month and isn't in [alreadyFired]. Thresholds are
 * mutually exclusive — 80% fires in `[80%, 100%)`, 100% fires at `>= 100%` — so a budget that is
 * already over on the first check fires only "over budget", never both. Keys are scoped by month
 * (`"<year>-<month>:<scope>:<threshold>"`) so they auto-reset when the month rolls over. Pure.
 */
fun budgetAlertsToFire(
    yearMonth: YearMonth,
    monthSpendMinor: Long,
    overallBudgetMinor: Long?,
    categorySpendMinor: Map<Long, Long>,
    categoryBudgetsMinor: Map<Long, Long>,
    categoryNames: Map<Long, String>,
    alreadyFired: Set<String>,
): List<BudgetAlert> {
    val monthKey = "${yearMonth.year}-${yearMonth.month}"
    val out = mutableListOf<BudgetAlert>()

    fun consider(scope: String, label: String, spent: Long, budget: Long?) {
        if (budget == null || budget <= 0) return
        val pct = 100.0 * spent / budget
        val threshold = when {
            pct >= 100 -> 100
            pct >= 80 -> 80
            else -> return
        }
        val key = "$monthKey:$scope:$threshold"
        if (key !in alreadyFired) {
            out += BudgetAlert(key, label, threshold, spent, budget)
        }
    }

    consider("overall", "your monthly budget", monthSpendMinor, overallBudgetMinor)
    categoryBudgetsMinor.forEach { (id, budget) ->
        val name = categoryNames[id] ?: return@forEach // category deleted — skip
        consider("cat:$id", name, categorySpendMinor[id] ?: 0L, budget)
    }
    return out
}
