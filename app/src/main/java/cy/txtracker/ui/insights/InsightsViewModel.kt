package cy.txtracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.Direction
import cy.txtracker.data.FundingSource
import cy.txtracker.data.TrackedCurrency
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.InsightsPeriod
import cy.txtracker.domain.InsightsRange
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.domain.resolveInsightsPeriod
import cy.txtracker.export.ExportDateRange
import cy.txtracker.ui.home.DayGroup
import cy.txtracker.ui.home.TransactionWithCategory
import kotlinx.datetime.toLocalDateTime
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

    /** Active chart drill-down (a BreakdownSlice.key), or null. Session only. */
    private val _drillKey = MutableStateFlow<String?>(null)

    /** Selected currency — MYR or a tracked foreign code. Session only. */
    private val _selectedCurrency = MutableStateFlow("MYR")

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
        combine(prefs.period, prefs.customRange, _selectedCurrency) { period, custom, currency ->
            Triple(period, custom, currency)
        }
            .flatMapLatest { (period, custom, currency) ->
                val range = resolveInsightsPeriod(period, custom?.start, custom?.end)
                val txFlow = if (currency == "MYR") {
                    repository.observeMyrTransactionsBetween(range.startInclusive, range.endExclusive)
                } else {
                    // Per-currency range query (the repo method is named for its trip use).
                    repository.observeTransactionsForTrip(currency, range.startInclusive, range.endExclusive)
                }
                txFlow.map { txs -> RangeData(period, custom, range, currency, txs) }
            }

    val state: StateFlow<InsightsUiState> =
        combine(
            rangeData,
            sixMonthTxs,
            combine(prefs.groupBy, prefs.chartType, _selectedCategoryId, _drillKey, prefs.ignoredCategoryIds) { g, c, s, d, ignored -> Presentation(g, c, s, d, ignored) },
            combine(prefs.overallBudgetMinor, prefs.categoryBudgetsMinor) { o, c -> o to c },
            combine(
                repository.observeAllCategories(),
                repository.observeFundingSources(),
                repository.observeTrackedCurrencies(),
            ) { cats, f, tc -> Triple(cats, f, tc) },
        ) { rd, sixMo, presentation, budgets, catsFunding ->
            val (groupBy, chartType, selectedCategoryId, drillKey, ignoredCategoryIds) = presentation
            val (overallBudget, categoryBudgets) = budgets
            val (categories, fundingSources, trackedCurrencies) = catsFunding
            buildLoaded(
                rangeData = rd,
                sixMoTxs = sixMo,
                groupBy = groupBy,
                chartType = chartType,
                selectedCategoryId = selectedCategoryId,
                drillKey = drillKey,
                ignoredCategoryIds = ignoredCategoryIds,
                overallBudget = overallBudget,
                categoryBudgets = categoryBudgets,
                categories = categories,
                fundingSources = fundingSources,
                trackedCurrencies = trackedCurrencies,
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
    fun openDrill(key: String) { _drillKey.value = key }
    fun closeDrill() { _drillKey.value = null }
    fun selectCurrency(code: String) {
        _drillKey.value = null
        _selectedCurrency.value = code
    }
    fun setCategoryIgnored(categoryId: Long, ignored: Boolean) = prefs.setCategoryIgnored(categoryId, ignored)
    fun setOverallBudget(minor: Long?) = prefs.setOverallBudget(minor)
    fun setCategoryBudget(categoryId: Long, minor: Long?) = prefs.setCategoryBudget(categoryId, minor)

    private fun buildLoaded(
        rangeData: RangeData,
        sixMoTxs: List<Transaction>,
        groupBy: GroupBy,
        chartType: InsightsChartType,
        selectedCategoryId: Long?,
        drillKey: String?,
        ignoredCategoryIds: Set<Long>,
        overallBudget: Long?,
        categoryBudgets: Map<Long, Long>,
        categories: List<Category>,
        fundingSources: List<FundingSource>,
        trackedCurrencies: List<TrackedCurrency>,
    ): InsightsUiState {
        val categoriesById = categories.associateBy { it.id }
        val fundingById = fundingSources.associateBy { it.id }
        val rangeTxs = rangeData.txs
        // Hidden categories are excluded from the charts + total (but not budgets, which the user
        // explicitly set per category). Unverified (null category) is never hidden.
        val visibleRangeTxs = rangeTxs.filter { tx -> tx.categoryId?.let { it !in ignoredCategoryIds } ?: true }
        val visibleSixMoTxs = sixMoTxs.filter { tx -> tx.categoryId?.let { it !in ignoredCategoryIds } ?: true }
        val currency = rangeData.currency
        val currencies = listOf(CurrencyOption("MYR", "RM")) +
            trackedCurrencies.map { CurrencyOption(it.code, it.displaySymbol) }
        val currencySymbol = currencies.firstOrNull { it.code == currency }?.symbol ?: "RM"
        // Trends + budget are MYR-only (fixed MYR windows); for a foreign currency restrict to the
        // range-based charts so a persisted MYR chart-type doesn't render MYR data under a foreign symbol.
        val effectiveChartType =
            if (currency != "MYR" && chartType != InsightsChartType.CATEGORY_PIE && chartType != InsightsChartType.DAILY_BAR) {
                InsightsChartType.CATEGORY_PIE
            } else {
                chartType
            }

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

        val breakdown = groupedBreakdown(visibleRangeTxs, groupBy, categoriesById, fundingById)
        val drill = buildDrill(drillKey, visibleRangeTxs, groupBy, categoriesById, fundingById, breakdown, currencySymbol)

        return InsightsUiState.Loaded(
            chartType = effectiveChartType,
            period = rangeData.period,
            rangeLabel = rangeLabel(rangeData.period, rangeData.customRange),
            groupBy = groupBy,
            categories = categories,
            selectedCategoryId = effectiveSelectedCategoryId,
            ignoredCategoryIds = ignoredCategoryIds,
            currency = currency,
            currencySymbol = currencySymbol,
            currencies = currencies,
            breakdown = breakdown,
            daily = dailyStacked(visibleRangeTxs, groupBy, categoriesById, fundingById),
            monthly = monthlyTotals(visibleSixMoTxs),
            categoryTrend = effectiveSelectedCategoryId?.let { monthlyTotalsForCategory(visibleSixMoTxs, it) } ?: emptyList(),
            totalMinor = visibleRangeTxs.filter { it.direction == Direction.OUT }.sumOf { it.chartAmountMinor() },
            budget = budgetProgress(monthSpend, overallBudget),
            categoryBudgets = categoryBudgetProgress(monthCategorySpend, categoryBudgets, categoriesById),
            drill = drill,
            isEmpty = rangeTxs.none { it.direction == Direction.OUT },
        )
    }

    private fun buildDrill(
        drillKey: String?,
        rangeTxs: List<Transaction>,
        groupBy: GroupBy,
        categoriesById: Map<Long, Category>,
        fundingById: Map<Long, FundingSource>,
        breakdown: List<BreakdownSlice>,
        symbol: String,
    ): InsightsDrill? {
        val key = drillKey ?: return null
        val rows = transactionsForKey(rangeTxs, key, groupBy, categoriesById, fundingById)
        if (rows.isEmpty()) {
            // Stale key (e.g. the grouping changed) — clear it so the sheet dismisses.
            _drillKey.value = null
            return null
        }
        val days = rows
            .map { TransactionWithCategory(it, it.categoryId?.let(categoriesById::get)) }
            .groupBy { it.transaction.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
            .toSortedMap(reverseOrder())
            .map { (date, list) -> DayGroup(date, list) }
        val label = breakdown.firstOrNull { it.key == key }?.label ?: key
        return InsightsDrill(key = key, label = label, symbol = symbol, days = days)
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
        val currency: String,
        val txs: List<Transaction>,
    )

    private data class Presentation(
        val groupBy: GroupBy,
        val chartType: InsightsChartType,
        val selectedCategoryId: Long?,
        val drillKey: String?,
        val ignoredCategoryIds: Set<Long>,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
