package cy.txtracker.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cy.txtracker.data.Category
import cy.txtracker.data.Transaction
import cy.txtracker.domain.InsightsPeriod
import cy.txtracker.export.ExportDateRange
import cy.txtracker.ui.edit.EditTransactionSheet
import cy.txtracker.ui.format.formatAmount
import cy.txtracker.ui.home.TransactionList
import cy.txtracker.ui.insights.charts.ColorDot
import cy.txtracker.ui.manual.parseAmountMinor
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun InsightsRoute(viewModel: InsightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loaded = state as? InsightsUiState.Loaded

    var showCustomRange by remember { mutableStateOf(false) }
    var budgetTarget by remember { mutableStateOf<BudgetTarget?>(null) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var editingTxId by remember { mutableStateOf<Long?>(null) }

    InsightsScreen(
        state = state,
        onSelectPeriod = { period ->
            if (period == InsightsPeriod.CUSTOM) showCustomRange = true else viewModel.setPeriod(period)
        },
        onSetGroupBy = viewModel::setGroupBy,
        onSetChartType = viewModel::setChartType,
        onSelectCategory = viewModel::selectCategory,
        onEditOverallBudget = { budgetTarget = BudgetTarget.Overall(loaded?.budget?.budgetMinor) },
        onEditCategoryBudget = { id ->
            val name = loaded?.categories?.firstOrNull { it.id == id }?.name ?: "Category"
            val current = loaded?.categoryBudgets?.firstOrNull { it.category.id == id }?.progress?.budgetMinor
            budgetTarget = BudgetTarget.Category(id, name, current)
        },
        onAddCategoryBudget = { showCategoryPicker = true },
        onDrill = viewModel::openDrill,
        onSelectCurrency = viewModel::selectCurrency,
    )

    if (showCustomRange) {
        InsightsRangePickerDialog(
            onConfirm = { range ->
                if (range != null) viewModel.setCustomRange(range)
                showCustomRange = false
            },
            onDismiss = { showCustomRange = false },
        )
    }

    budgetTarget?.let { target ->
        BudgetAmountDialog(
            title = when (target) {
                is BudgetTarget.Overall -> "Overall monthly budget"
                is BudgetTarget.Category -> "${target.name} budget"
            },
            initialMinor = target.currentMinor,
            onConfirm = { minor ->
                when (target) {
                    is BudgetTarget.Overall -> viewModel.setOverallBudget(minor)
                    is BudgetTarget.Category -> viewModel.setCategoryBudget(target.id, minor)
                }
                budgetTarget = null
            },
            onDismiss = { budgetTarget = null },
        )
    }

    if (showCategoryPicker) {
        val budgetedIds = loaded?.categoryBudgets?.map { it.category.id }?.toSet() ?: emptySet()
        val available = loaded?.categories?.filter { it.id !in budgetedIds } ?: emptyList()
        CategoryBudgetPickerDialog(
            categories = available,
            onPick = { id ->
                showCategoryPicker = false
                val name = available.firstOrNull { it.id == id }?.name ?: "Category"
                budgetTarget = BudgetTarget.Category(id, name, null)
            },
            onDismiss = { showCategoryPicker = false },
        )
    }

    loaded?.drill?.let { drill ->
        DrillSheet(
            drill = drill,
            onTransactionClick = { editingTxId = it.id },
            onDismiss = viewModel::closeDrill,
        )
    }

    editingTxId?.let { id ->
        EditTransactionSheet(transactionId = id, onDismiss = { editingTxId = null })
    }
}

private sealed interface BudgetTarget {
    val currentMinor: Long?

    data class Overall(override val currentMinor: Long?) : BudgetTarget
    data class Category(val id: Long, val name: String, override val currentMinor: Long?) : BudgetTarget
}

@Composable
private fun BudgetAmountDialog(
    title: String,
    initialMinor: Long?,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialMinor?.let(::formatPlainAmount) ?: "") }
    val parsed = parseAmountMinor(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Tracked against this month's spend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Amount (RM)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parsed) }, enabled = parsed != null) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initialMinor != null) {
                    TextButton(onClick = { onConfirm(null) }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CategoryBudgetPickerDialog(
    categories: List<Category>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add category budget") },
        text = {
            if (categories.isEmpty()) {
                Text("Every category already has a budget.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    categories.forEach { category ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(category.id) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ColorDot(category.color)
                            Spacer(Modifier.width(12.dp))
                            Text(category.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Material3 date-range picker for the Custom period. Mirrors the CSV-export picker: Material
 * reports UTC-midnight epoch millis, so the tapped calendar date is read via [TimeZone.UTC] and
 * the resulting [ExportDateRange] is re-interpreted in Malaysia time by [resolveInsightsPeriod].
 * Duplicated rather than shared to keep the merge-sensitive SettingsScreen untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsightsRangePickerDialog(
    onConfirm: (ExportDateRange?) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val start = state.selectedStartDateMillis?.toUtcLocalDate()
                if (start == null) {
                    onConfirm(null)
                } else {
                    val end = state.selectedEndDateMillis?.toUtcLocalDate() ?: start
                    onConfirm(ExportDateRange(start, end))
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state)
    }
}

private fun formatPlainAmount(minor: Long): String =
    "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

private fun Long.toUtcLocalDate(): LocalDate =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date

/** Bottom sheet listing the transactions behind a tapped chart series (for the selected range). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrillSheet(
    drill: InsightsDrill,
    onTransactionClick: (Transaction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = drill.label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        TransactionList(
            days = drill.days,
            notesByMerchant = emptyMap(),
            contentPadding = PaddingValues(bottom = 24.dp),
            amountFormatter = { formatAmount(it, drill.symbol) },
            onTransactionClick = onTransactionClick,
        )
    }
}
