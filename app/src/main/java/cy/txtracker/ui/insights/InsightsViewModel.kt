package cy.txtracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.Direction
import cy.txtracker.data.FundingSource
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.InsightsPeriod
import cy.txtracker.domain.InsightsRange
import cy.txtracker.domain.resolveInsightsPeriod
import cy.txtracker.export.ExportDateRange
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val prefs: InsightsPrefs,
) : ViewModel() {

    /** Per-category trend selection — session only (depends on which categories currently exist). */
    private val _selectedCategoryId = MutableStateFlow<Long?>(null)

    // Trend charts always show a fixed trailing 6-month window, independent of the range selector;
    // budgets are evaluated against the current calendar month (a subset of that window). "Now" is
    // captured once at construction, mirroring HomeViewModel's YearMonth.current().
    private val sixMonthRange: InsightsRange = resolveInsightsPeriod(InsightsPeriod.LAST_6_MONTHS)
    private val thisMonthRange: InsightsRange = resolveInsightsPeriod(InsightsPeriod.THIS_MONTH)
    private val sixMonthTxs: Flow<List<Transaction>> =
        repository.observeMyrTransactionsBetween(sixMonthRange.startInclusive, sixMonthRange.endExclusive)

    /** Selected-range rows. Re-subscribes only when the period / custom range changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val rangeData: Flow<RangeData> =
        combine(prefs.period, prefs.customRange) { period, custom -> period to custom }
            .flatMapLatest { (period, custom) ->
                val range = resolveInsightsPeriod(period, custom?.start, custom?.end)
                repository.observeMyrTransactionsBetween(range.startInclusive, range.endExclusive)
                    .map { txs -> RangeData(period, custom, range, txs) }
            }

    val state: StateFlow<InsightsUiState> =
        combine(
            rangeData,
            sixMonthTxs,
            combine(prefs.groupBy, prefs.chartType, _selectedCategoryId) { g, c, s -> Triple(g, c, s) },
            combine(prefs.overallBudgetMinor, prefs.categoryBudgetsMinor) { o, c -> o to c },
            combine(repository.observeAllCategories(), repository.observeFundingSources()) { cats, f -> cats to f },
        ) { rd, sixMo, presentation, budgets, catsFunding ->
            val (groupBy, chartType, selectedCategoryId) = presentation
            val (overallBudget, categoryBudgets) = budgets
            val (categories, fundingSources) = catsFunding
            buildLoaded(
                rangeData = rd,
                sixMoTxs = sixMo,
                groupBy = groupBy,
                chartType = chartType,
                selectedCategoryId = selectedCategoryId,
                overallBudget = overallBudget,
                categoryBudgets = categoryBudgets,
                categories = categories,
                fundingSources = fundingSources,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = InsightsUiState.Loading,
        )

    fun setPeriod(period: InsightsPeriod) = prefs.setPeriod(period)

    /** Confirming a custom range also switches the selector to CUSTOM. */
    fun setCustomRange(range: ExportDateRange) {
        prefs.setCustomRange(range)
        prefs.setPeriod(InsightsPeriod.CUSTOM)
    }

    fun setGroupBy(groupBy: GroupBy) = prefs.setGroupBy(groupBy)
    fun setChartType(chartType: InsightsChartType) = prefs.setChartType(chartType)
    fun selectCategory(categoryId: Long?) { _selectedCategoryId.value = categoryId }
    fun setOverallBudget(minor: Long?) = prefs.setOverallBudget(minor)
    fun setCategoryBudget(categoryId: Long, minor: Long?) = prefs.setCategoryBudget(categoryId, minor)

    private fun buildLoaded(
        rangeData: RangeData,
        sixMoTxs: List<Transaction>,
        groupBy: GroupBy,
        chartType: InsightsChartType,
        selectedCategoryId: Long?,
        overallBudget: Long?,
        categoryBudgets: Map<Long, Long>,
        categories: List<Category>,
        fundingSources: List<FundingSource>,
    ): InsightsUiState {
        val categoriesById = categories.associateBy { it.id }
        val fundingById = fundingSources.associateBy { it.id }
        val rangeTxs = rangeData.txs

        // Snap a stale selection back, or default the per-category trend to the first category.
        val effectiveSelectedCategoryId =
            selectedCategoryId?.takeIf { id -> categories.any { it.id == id } }
                ?: categories.firstOrNull()?.id
        if (effectiveSelectedCategoryId != selectedCategoryId) {
            _selectedCategoryId.value = effectiveSelectedCategoryId
        }

        // Current-month slice of the 6-month window drives both budget figures.
        val monthTxs = sixMoTxs.filter {
            it.direction == Direction.OUT &&
                it.occurredAt >= thisMonthRange.startInclusive &&
                it.occurredAt < thisMonthRange.endExclusive
        }
        val monthSpend = monthTxs.sumOf { it.chartAmountMinor() }
        val monthCategorySpend = monthTxs
            .mapNotNull { tx -> tx.categoryId?.let { it to tx } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, rows) -> rows.sumOf { it.chartAmountMinor() } }

        return InsightsUiState.Loaded(
            chartType = chartType,
            period = rangeData.period,
            rangeLabel = rangeLabel(rangeData.period, rangeData.customRange),
            groupBy = groupBy,
            categories = categories,
            selectedCategoryId = effectiveSelectedCategoryId,
            breakdown = groupedBreakdown(rangeTxs, groupBy, categoriesById, fundingById),
            daily = dailyStacked(rangeTxs, groupBy, categoriesById, fundingById),
            monthly = monthlyTotals(sixMoTxs),
            categoryTrend = effectiveSelectedCategoryId?.let { monthlyTotalsForCategory(sixMoTxs, it) } ?: emptyList(),
            totalMinor = rangeTxs.filter { it.direction == Direction.OUT }.sumOf { it.chartAmountMinor() },
            budget = budgetProgress(monthSpend, overallBudget),
            categoryBudgets = categoryBudgetProgress(monthCategorySpend, categoryBudgets, categoriesById),
            isEmpty = rangeTxs.none { it.direction == Direction.OUT },
        )
    }

    private fun rangeLabel(period: InsightsPeriod, customRange: ExportDateRange?): String = when (period) {
        InsightsPeriod.THIS_MONTH -> "This month"
        InsightsPeriod.LAST_MONTH -> "Last month"
        InsightsPeriod.LAST_3_MONTHS -> "Last 3 months"
        InsightsPeriod.LAST_6_MONTHS -> "Last 6 months"
        InsightsPeriod.THIS_YEAR -> "This year"
        InsightsPeriod.ALL_TIME -> "All time"
        InsightsPeriod.CUSTOM -> customRange?.let { "${it.start} – ${it.end}" } ?: "Custom"
    }

    private data class RangeData(
        val period: InsightsPeriod,
        val customRange: ExportDateRange?,
        val range: InsightsRange,
        val txs: List<Transaction>,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
