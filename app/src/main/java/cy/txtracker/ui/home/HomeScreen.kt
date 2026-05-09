package cy.txtracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.Category
import cy.txtracker.data.Transaction
import cy.txtracker.ui.edit.EditTransactionSheet
import cy.txtracker.ui.format.formatDayHeader
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.format.formatTimeOfDay
import cy.txtracker.ui.format.formatYearMonth
import cy.txtracker.ui.manual.AddManualSheet

@Composable
fun HomeRoute(
    onSettingsClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var editingTxId by remember { mutableStateOf<Long?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }

    HomeScreen(
        state = state,
        onPrevMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
        onFilterChange = viewModel::setFilter,
        onTransactionClick = { tx -> editingTxId = tx.id },
        onAddClick = { showAddSheet = true },
        onSettingsClick = onSettingsClick,
    )

    editingTxId?.let { id ->
        EditTransactionSheet(
            transactionId = id,
            onDismiss = { editingTxId = null },
        )
    }
    if (showAddSheet) {
        AddManualSheet(onDismiss = { showAddSheet = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onFilterChange: (HomeFilter) -> Unit,
    onTransactionClick: (Transaction) -> Unit,
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Filled.Add, contentDescription = "Add manual transaction")
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPrevMonth) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                        }
                        Text(
                            text = formatYearMonth(state.yearMonth),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = onNextMonth) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            MonthTotalHeader(totalMinor = state.totalMinor)
            CategoryBreakdownRow(breakdown = state.breakdown)
            HorizontalDivider()
            FilterRow(
                filter = state.filter,
                categories = state.categories,
                hasUnverified = state.breakdown.any { it.category == null && it.totalMinor > 0 },
                pendingCount = state.pendingCount,
                onFilterChange = onFilterChange,
            )
            HorizontalDivider()
            if (state.days.isEmpty()) {
                EmptyState(state)
            } else {
                TransactionList(
                    days = state.days,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    onTransactionClick = onTransactionClick,
                )
            }
        }
    }
}

@Composable
private fun MonthTotalHeader(totalMinor: Long) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = "Spent this month", style = MaterialTheme.typography.labelMedium)
        Text(
            text = formatMyr(totalMinor),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CategoryBreakdownRow(breakdown: List<CategoryBreakdownEntry>) {
    if (breakdown.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(breakdown) { entry ->
            AssistChip(
                onClick = { /* TODO: tap to filter to this category — wire when filter is in scope */ },
                leadingIcon = {
                    val color = entry.category?.color?.let(::Color) ?: MaterialTheme.colorScheme.outline
                    Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
                },
                label = {
                    val name = entry.category?.name ?: "Unverified"
                    Text("$name  ${formatMyr(entry.totalMinor)}")
                },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    filter: HomeFilter,
    categories: List<Category>,
    hasUnverified: Boolean,
    pendingCount: Int,
    onFilterChange: (HomeFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = filter is HomeFilter.All,
                onClick = { onFilterChange(HomeFilter.All) },
                label = { Text("All") },
            )
        }
        if (pendingCount > 0) {
            item {
                FilterChip(
                    selected = filter is HomeFilter.Pending,
                    onClick = { onFilterChange(HomeFilter.Pending) },
                    label = { Text("Pending ($pendingCount)") },
                )
            }
        }
        if (hasUnverified) {
            item {
                FilterChip(
                    selected = filter is HomeFilter.Unverified,
                    onClick = { onFilterChange(HomeFilter.Unverified) },
                    label = { Text("Unverified") },
                )
            }
        }
        items(categories, key = { it.id }) { c ->
            FilterChip(
                selected = filter is HomeFilter.Category && filter.id == c.id,
                onClick = { onFilterChange(HomeFilter.Category(c.id)) },
                label = { Text(c.name) },
            )
        }
    }
}

@Composable
private fun TransactionList(
    days: List<DayGroup>,
    contentPadding: PaddingValues,
    onTransactionClick: (Transaction) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding) {
        days.forEach { group ->
            item(key = "header-${group.date}") {
                DayHeader(group)
            }
            items(group.transactions, key = { it.transaction.id }) { row ->
                TransactionRow(row = row, onClick = { onTransactionClick(row.transaction) })
            }
        }
    }
}

@Composable
private fun DayHeader(group: DayGroup) {
    val total = group.transactions.sumOf { it.transaction.amountMinor }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = formatDayHeader(group.date),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatMyr(total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransactionRow(row: TransactionWithCategory, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.transaction.merchantRaw,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatTimeOfDay(row.transaction.occurredAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatMyr(row.transaction.amountMinor),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(category = row.category)
                if (row.transaction.needsVerification) {
                    Spacer(Modifier.size(8.dp))
                    PendingPill()
                }
            }
            row.transaction.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingPill() {
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    val fg = MaterialTheme.colorScheme.onTertiaryContainer
    Box(
        modifier = Modifier
            .background(bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Pending",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun CategoryChip(category: Category?) {
    val (label, color) = when (category) {
        null -> "Unverified" to MaterialTheme.colorScheme.outline
        else -> category.name to Color(category.color)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(state: HomeUiState) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (state.isLoading) "Loading…"
                else "No spending captured for ${formatYearMonth(state.yearMonth)} yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.isLoading) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Make a payment in Google Wallet, or add a manual entry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

