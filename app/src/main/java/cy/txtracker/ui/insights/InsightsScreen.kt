package cy.txtracker.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cy.txtracker.data.Category
import cy.txtracker.domain.InsightsPeriod
import cy.txtracker.ui.format.formatAmount
import cy.txtracker.ui.insights.charts.BudgetProgressCard
import cy.txtracker.ui.insights.charts.CategoryPieChart
import cy.txtracker.ui.insights.charts.DailyStackedBarChart
import cy.txtracker.ui.insights.charts.EmptyChart
import cy.txtracker.ui.insights.charts.SpendTrendLineChart
import cy.txtracker.ui.insights.charts.amountAxisFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsScreen(
    state: InsightsUiState,
    onSelectPeriod: (InsightsPeriod) -> Unit,
    onSetGroupBy: (GroupBy) -> Unit,
    onSetChartType: (InsightsChartType) -> Unit,
    onSelectCategory: (Long) -> Unit,
    onEditOverallBudget: () -> Unit,
    onEditCategoryBudget: (Long) -> Unit,
    onAddCategoryBudget: () -> Unit,
    onDrill: (String) -> Unit,
    onSelectCurrency: (String) -> Unit,
    onSetCategoryIgnored: (Long, Boolean) -> Unit,
) {
    var showFilter by remember { mutableStateOf(false) }
    val loaded = state as? InsightsUiState.Loaded
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Insights") },
                actions = {
                    if (loaded != null) {
                        IconButton(onClick = { showFilter = true }) {
                            Icon(Icons.Outlined.FilterList, contentDescription = "Filter categories")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                InsightsUiState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is InsightsUiState.Loaded -> LoadedContent(
                    state = state,
                    onSelectPeriod = onSelectPeriod,
                    onSetGroupBy = onSetGroupBy,
                    onSetChartType = onSetChartType,
                    onSelectCategory = onSelectCategory,
                    onEditOverallBudget = onEditOverallBudget,
                    onEditCategoryBudget = onEditCategoryBudget,
                    onAddCategoryBudget = onAddCategoryBudget,
                    onDrill = onDrill,
                    onSelectCurrency = onSelectCurrency,
                )
            }
        }
    }
    if (showFilter && loaded != null) {
        CategoryFilterDialog(
            categories = loaded.categories,
            ignoredIds = loaded.ignoredCategoryIds,
            onToggle = onSetCategoryIgnored,
            onDismiss = { showFilter = false },
        )
    }
}

@Composable
private fun LoadedContent(
    state: InsightsUiState.Loaded,
    onSelectPeriod: (InsightsPeriod) -> Unit,
    onSetGroupBy: (GroupBy) -> Unit,
    onSetChartType: (InsightsChartType) -> Unit,
    onSelectCategory: (Long) -> Unit,
    onEditOverallBudget: () -> Unit,
    onEditCategoryBudget: (Long) -> Unit,
    onAddCategoryBudget: () -> Unit,
    onDrill: (String) -> Unit,
    onSelectCurrency: (String) -> Unit,
) {
    // Range + grouping apply only to the snapshot charts (pie / daily bar); trends use a fixed
    // rolling 6-month window and budget is always current-month, so their controls are hidden.
    val usesRangeAndGrouping = state.chartType == InsightsChartType.CATEGORY_PIE ||
        state.chartType == InsightsChartType.DAILY_BAR
    val amountFormatter: (Long) -> String = { formatAmount(it, state.currencySymbol) }
    val axisFormatter = amountAxisFormatter(state.currencySymbol)
    // Trends + budget are MYR-only; foreign currencies offer just the range-based charts.
    val chartTypes = if (state.currency == "MYR") {
        InsightsChartType.entries.toList()
    } else {
        listOf(InsightsChartType.CATEGORY_PIE, InsightsChartType.DAILY_BAR)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        if (state.currencies.size > 1) {
            ChipRow {
                state.currencies.forEach { option ->
                    FilterChip(
                        selected = state.currency == option.code,
                        onClick = { onSelectCurrency(option.code) },
                        label = { Text(option.code) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        ChipRow {
            chartTypes.forEach { type ->
                FilterChip(
                    selected = state.chartType == type,
                    onClick = { onSetChartType(type) },
                    label = { Text(chartTypeLabel(type)) },
                )
            }
        }

        if (usesRangeAndGrouping) {
            Spacer(Modifier.height(8.dp))
            ChipRow {
                InsightsPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = state.period == period,
                        onClick = { onSelectPeriod(period) },
                        label = { Text(periodLabel(period)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ChipRow {
                GroupBy.entries.forEach { group ->
                    FilterChip(
                        selected = state.groupBy == group,
                        onClick = { onSetGroupBy(group) },
                        label = { Text(groupByLabel(group)) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Spent · ${state.rangeLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatAmount(state.totalMinor, state.currencySymbol),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(16.dp))

        when (state.chartType) {
            InsightsChartType.CATEGORY_PIE ->
                CategoryPieChart(state.breakdown, onSliceTap = onDrill, amountFormatter = amountFormatter)
            InsightsChartType.DAILY_BAR ->
                DailyStackedBarChart(
                    days = state.daily,
                    series = state.breakdown,
                    onKeyTap = onDrill,
                    amountFormatter = amountFormatter,
                    axisFormatter = axisFormatter,
                )
            InsightsChartType.MONTHLY_TREND -> {
                Text(
                    text = "Monthly spend · last 6 months",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                SpendTrendLineChart(state.monthly, lineColor = MaterialTheme.colorScheme.primary, axisFormatter = axisFormatter)
            }
            InsightsChartType.CATEGORY_TREND -> CategoryTrendSection(state, onSelectCategory)
            InsightsChartType.BUDGET -> BudgetProgressCard(
                overall = state.budget,
                categoryBudgets = state.categoryBudgets,
                onEditOverall = onEditOverallBudget,
                onEditCategory = onEditCategoryBudget,
                onAddCategoryBudget = onAddCategoryBudget,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CategoryTrendSection(state: InsightsUiState.Loaded, onSelectCategory: (Long) -> Unit) {
    if (state.categories.isEmpty()) {
        EmptyChart("No categories yet")
        return
    }
    val selected = state.categories.firstOrNull { it.id == state.selectedCategoryId }
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Category", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(12.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected?.name ?: "Select")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name) },
                        onClick = {
                            onSelectCategory(category.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SpendTrendLineChart(
        points = state.categoryTrend,
        lineColor = selected?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private fun chartTypeLabel(type: InsightsChartType): String = when (type) {
    InsightsChartType.CATEGORY_PIE -> "Breakdown"
    InsightsChartType.DAILY_BAR -> "Daily"
    InsightsChartType.MONTHLY_TREND -> "Trend"
    InsightsChartType.CATEGORY_TREND -> "Category trend"
    InsightsChartType.BUDGET -> "Budget"
}

private fun periodLabel(period: InsightsPeriod): String = when (period) {
    InsightsPeriod.THIS_MONTH -> "This month"
    InsightsPeriod.LAST_MONTH -> "Last month"
    InsightsPeriod.LAST_3_MONTHS -> "Last 3 mo"
    InsightsPeriod.LAST_6_MONTHS -> "Last 6 mo"
    InsightsPeriod.THIS_YEAR -> "This year"
    InsightsPeriod.ALL_TIME -> "All time"
    InsightsPeriod.CUSTOM -> "Custom…"
}

private fun groupByLabel(group: GroupBy): String = when (group) {
    GroupBy.CATEGORY -> "By category"
    GroupBy.FUNDING_SOURCE -> "By source"
}

/** Checklist of categories; unchecking one hides it from the charts (excluded from spend + total). */
@Composable
private fun CategoryFilterDialog(
    categories: List<Category>,
    ignoredIds: Set<Long>,
    onToggle: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show categories") },
        text = {
            if (categories.isEmpty()) {
                Text("No categories yet.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    categories.forEach { category ->
                        val visible = category.id !in ignoredIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(category.id, visible) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = visible, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(category.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
