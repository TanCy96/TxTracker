package cy.txtracker.ui.insights

import cy.txtracker.data.Category
import cy.txtracker.domain.InsightsPeriod
import cy.txtracker.domain.YearMonth
import cy.txtracker.ui.home.DayGroup
import kotlinx.datetime.LocalDate

/** Dimension the pie/bar charts group spend by. */
enum class GroupBy { CATEGORY, FUNDING_SOURCE }

/** Which chart the Insights screen is currently showing. */
enum class InsightsChartType { CATEGORY_PIE, DAILY_BAR, MONTHLY_TREND, CATEGORY_TREND, BUDGET }

/**
 * One slice of the pie / one series of the stacked bar / one legend row. [key] is a stable
 * series identity shared with [DayBucket.totalsByKey]; [colorArgb] is a resolved ARGB int so the
 * composables stay dumb (category colors come from the DB, funding/unverified from a fixed palette).
 */
data class BreakdownSlice(
    val key: String,
    val label: String,
    val colorArgb: Int,
    val totalMinor: Long,
)

/** Spend for one calendar day, split by series key (keys match [BreakdownSlice.key]). */
data class DayBucket(
    val date: LocalDate,
    val totalsByKey: Map<String, Long>,
) {
    val totalMinor: Long get() = totalsByKey.values.sum()
}

/** Total spend for one calendar month (trend-line point). */
data class MonthBucket(
    val yearMonth: YearMonth,
    val totalMinor: Long,
)

/** Progress of spend against a budget. [fraction] is uncapped (spent/budget); the UI caps the bar. */
data class BudgetProgress(
    val spentMinor: Long,
    val budgetMinor: Long,
    val fraction: Float,
    val overBudget: Boolean,
)

/** A category paired with its current-month budget progress. */
data class CategoryBudgetProgress(
    val category: Category,
    val progress: BudgetProgress,
)

/** Drill-down target: the transactions behind a tapped chart series, grouped by day. */
data class InsightsDrill(
    val key: String,
    val label: String,
    val days: List<DayGroup>,
)

sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data class Loaded(
        val chartType: InsightsChartType,
        val period: InsightsPeriod,
        /** Human-readable range label, e.g. "Last 6 months". */
        val rangeLabel: String,
        val groupBy: GroupBy,
        val categories: List<Category>,
        val selectedCategoryId: Long?,
        val breakdown: List<BreakdownSlice>,
        val daily: List<DayBucket>,
        val monthly: List<MonthBucket>,
        val categoryTrend: List<MonthBucket>,
        val totalMinor: Long,
        /** Overall monthly budget progress; null when no overall budget is set. */
        val budget: BudgetProgress?,
        /** Per-category monthly budget progress; empty when none set. */
        val categoryBudgets: List<CategoryBudgetProgress>,
        /** Active drill-down (transactions behind a tapped series); null when none. */
        val drill: InsightsDrill?,
        val isEmpty: Boolean,
    ) : InsightsUiState
}
