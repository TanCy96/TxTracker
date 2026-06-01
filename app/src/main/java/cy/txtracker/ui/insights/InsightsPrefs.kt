package cy.txtracker.ui.insights

import android.content.Context
import cy.txtracker.domain.InsightsPeriod
import cy.txtracker.export.ExportDateRange
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local-only Insights preferences: the chosen time range / grouping / chart type plus the optional
 * overall and per-category monthly budgets. Mirrors the [cy.txtracker.service.NotificationPrefs]
 * StateFlow-over-SharedPreferences pattern. Device-local (not round-tripped via backup), so a fresh
 * device starts from the defaults below.
 *
 * Per-category budgets are stored as a JSON `Map<categoryId-as-string, budgetMinor>` to avoid a Room
 * schema bump (which keeps the feature/share-debit merge trivial — see the Insights design spec).
 */
@Singleton
class InsightsPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _period = MutableStateFlow(readPeriod())
    val period: StateFlow<InsightsPeriod> = _period.asStateFlow()

    private val _customRange = MutableStateFlow(readCustomRange())
    val customRange: StateFlow<ExportDateRange?> = _customRange.asStateFlow()

    private val _groupBy = MutableStateFlow(readGroupBy())
    val groupBy: StateFlow<GroupBy> = _groupBy.asStateFlow()

    private val _chartType = MutableStateFlow(readChartType())
    val chartType: StateFlow<InsightsChartType> = _chartType.asStateFlow()

    private val _overallBudgetMinor = MutableStateFlow(readNullableLong(KEY_OVERALL_BUDGET))
    val overallBudgetMinor: StateFlow<Long?> = _overallBudgetMinor.asStateFlow()

    private val _categoryBudgetsMinor = MutableStateFlow(readCategoryBudgets())
    val categoryBudgetsMinor: StateFlow<Map<Long, Long>> = _categoryBudgetsMinor.asStateFlow()

    /** Category ids hidden from the Insights charts/total (the user filters out noisy ones). */
    private val _ignoredCategoryIds = MutableStateFlow(readIgnoredCategoryIds())
    val ignoredCategoryIds: StateFlow<Set<Long>> = _ignoredCategoryIds.asStateFlow()

    fun setPeriod(value: InsightsPeriod) {
        prefs.edit().putString(KEY_PERIOD, value.name).apply()
        _period.value = value
    }

    /** null clears the saved custom range. */
    fun setCustomRange(range: ExportDateRange?) {
        prefs.edit().apply {
            if (range == null) {
                remove(KEY_CUSTOM_START)
                remove(KEY_CUSTOM_END)
            } else {
                putString(KEY_CUSTOM_START, range.start.toString())
                putString(KEY_CUSTOM_END, range.end.toString())
            }
        }.apply()
        _customRange.value = range
    }

    fun setGroupBy(value: GroupBy) {
        prefs.edit().putString(KEY_GROUP_BY, value.name).apply()
        _groupBy.value = value
    }

    fun setChartType(value: InsightsChartType) {
        prefs.edit().putString(KEY_CHART_TYPE, value.name).apply()
        _chartType.value = value
    }

    /** null (or a non-positive value) clears the overall budget. */
    fun setOverallBudget(minor: Long?) {
        prefs.edit().apply {
            if (minor == null || minor <= 0) remove(KEY_OVERALL_BUDGET) else putLong(KEY_OVERALL_BUDGET, minor)
        }.apply()
        _overallBudgetMinor.value = minor?.takeIf { it > 0 }
    }

    /** null (or a non-positive value) removes that category's budget. */
    fun setCategoryBudget(categoryId: Long, minor: Long?) {
        val updated = _categoryBudgetsMinor.value.toMutableMap()
        if (minor == null || minor <= 0) updated.remove(categoryId) else updated[categoryId] = minor
        prefs.edit().putString(KEY_CATEGORY_BUDGETS, encodeBudgets(updated)).apply()
        _categoryBudgetsMinor.value = updated
    }

    fun setCategoryIgnored(categoryId: Long, ignored: Boolean) {
        val updated = _ignoredCategoryIds.value.toMutableSet()
        if (ignored) updated.add(categoryId) else updated.remove(categoryId)
        prefs.edit().putStringSet(KEY_IGNORED_CATEGORIES, updated.map { it.toString() }.toSet()).apply()
        _ignoredCategoryIds.value = updated
    }

    private fun readPeriod(): InsightsPeriod = runCatching {
        InsightsPeriod.valueOf(prefs.getString(KEY_PERIOD, null) ?: InsightsPeriod.THIS_MONTH.name)
    }.getOrDefault(InsightsPeriod.THIS_MONTH)

    private fun readGroupBy(): GroupBy = runCatching {
        GroupBy.valueOf(prefs.getString(KEY_GROUP_BY, null) ?: GroupBy.CATEGORY.name)
    }.getOrDefault(GroupBy.CATEGORY)

    private fun readChartType(): InsightsChartType = runCatching {
        InsightsChartType.valueOf(prefs.getString(KEY_CHART_TYPE, null) ?: InsightsChartType.CATEGORY_PIE.name)
    }.getOrDefault(InsightsChartType.CATEGORY_PIE)

    private fun readNullableLong(key: String): Long? =
        if (prefs.contains(key)) prefs.getLong(key, 0) else null

    private fun readCustomRange(): ExportDateRange? {
        val start = prefs.getString(KEY_CUSTOM_START, null) ?: return null
        val end = prefs.getString(KEY_CUSTOM_END, null) ?: return null
        return runCatching { ExportDateRange(LocalDate.parse(start), LocalDate.parse(end)) }.getOrNull()
    }

    private fun readCategoryBudgets(): Map<Long, Long> {
        val raw = prefs.getString(KEY_CATEGORY_BUDGETS, null) ?: return emptyMap()
        return runCatching {
            Json.decodeFromString<Map<String, Long>>(raw)
                .mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeBudgets(map: Map<Long, Long>): String =
        Json.encodeToString(map.mapKeys { it.key.toString() })

    private fun readIgnoredCategoryIds(): Set<Long> =
        prefs.getStringSet(KEY_IGNORED_CATEGORIES, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    private companion object {
        const val FILE = "insights"
        const val KEY_PERIOD = "period"
        const val KEY_CUSTOM_START = "custom_start"
        const val KEY_CUSTOM_END = "custom_end"
        const val KEY_GROUP_BY = "group_by"
        const val KEY_CHART_TYPE = "chart_type"
        const val KEY_OVERALL_BUDGET = "overall_budget_minor"
        const val KEY_CATEGORY_BUDGETS = "category_budgets"
        const val KEY_IGNORED_CATEGORIES = "ignored_category_ids"
    }
}
