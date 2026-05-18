package cy.txtracker.ui.foreign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.Category
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.ui.edit.EditTransactionSheet
import cy.txtracker.ui.format.formatAmount
import cy.txtracker.ui.home.CategoryBreakdownRow
import cy.txtracker.ui.home.TransactionList
import cy.txtracker.ui.manual.AddManualSheet
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

@Composable
fun ForeignRoute(
    onSettingsClick: () -> Unit = {},
    viewModel: ForeignViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var editingTxId by remember { mutableStateOf<Long?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }

    ForeignScreen(
        state = state,
        onPrevTrip = viewModel::previousTrip,
        onNextTrip = viewModel::nextTrip,
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
        // Pre-fill currency + a date inside the trip's window so the new row auto-promotes
        // into the trip the user is currently viewing. When the trip is open-ended or the
        // current time falls inside the window, "now" is used directly; otherwise the
        // trip's start anchors the entry (user can still adjust both in the sheet).
        val loaded = state as? ForeignUiState.Loaded
        val anchor = loaded?.let { defaultOccurredAtForTrip(it.trip) }
        AddManualSheet(
            onDismiss = { showAddSheet = false },
            initialCurrency = loaded?.trip?.currency,
            initialOccurredAt = anchor,
        )
    }
}

/**
 * Defaults the manual entry to land inside the selected trip's window:
 *   - if "now" sits inside [startAt, endAt or open]: use "now"
 *   - if "now" is BEFORE startAt: use startAt
 *   - if "now" is AFTER endAt:    use endAt (one second before, since endAt is exclusive)
 */
private fun defaultOccurredAtForTrip(trip: TripDescriptor): Instant {
    val now = Clock.System.now()
    val end = trip.endAt
    return when {
        now < trip.startAt -> trip.startAt
        end != null && now >= end -> end - 1.seconds
        else -> now
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForeignScreen(
    state: ForeignUiState,
    onPrevTrip: () -> Unit,
    onNextTrip: () -> Unit,
    onFilterChange: (ForeignFilter) -> Unit,
    onTransactionClick: (cy.txtracker.data.Transaction) -> Unit,
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    // surfaceVariant differentiates the Foreign tab from Home at a glance while still
    // adapting to dark/light theme.
    val bg = MaterialTheme.colorScheme.surfaceVariant

    Scaffold(
        containerColor = bg,
        floatingActionButton = {
            // Hide the FAB until at least one trip exists — manual entry without a trip
            // would land outside any window and immediately park into "Currency review",
            // which is the wrong affordance from this surface.
            if (state is ForeignUiState.Loaded) {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(Icons.Filled.Add, contentDescription = "Add manual transaction")
                }
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = bg),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onPrevTrip,
                            enabled = state is ForeignUiState.Loaded && state.tripIndex < state.tripCount - 1,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous trip")
                        }
                        Text(
                            text = tripChipLabel(state),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(
                            onClick = onNextTrip,
                            enabled = state is ForeignUiState.Loaded && state.tripIndex > 0,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next trip")
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                ForeignUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                ForeignUiState.NoTrips -> NoTripsEmpty()
                is ForeignUiState.Loaded -> LoadedContent(
                    state = state,
                    onFilterChange = onFilterChange,
                    onTransactionClick = onTransactionClick,
                )
            }
        }
    }
}

@Composable
private fun LoadedContent(
    state: ForeignUiState.Loaded,
    onFilterChange: (ForeignFilter) -> Unit,
    onTransactionClick: (cy.txtracker.data.Transaction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TripTotalHeader(
            trip = state.trip,
            totalMinor = state.totalMinor,
            transactionCount = state.transactionCount,
        )
        CategoryBreakdownRow(
            breakdown = state.breakdown,
            amountFormatter = { amt -> formatAmount(amt, state.trip.displaySymbol) },
        )
        HorizontalDivider()
        ForeignFilterRow(
            filter = state.filter,
            categories = state.categories,
            hasUnverified = state.breakdown.any { it.category == null && it.totalMinor > 0 },
            pendingCount = state.pendingCount,
            onFilterChange = onFilterChange,
        )
        HorizontalDivider()
        if (state.days.isEmpty()) {
            EmptyTripContent(state)
        } else {
            TransactionList(
                days = state.days,
                notesByMerchant = state.notesByMerchant,
                contentPadding = PaddingValues(vertical = 8.dp),
                amountFormatter = { amt -> formatAmount(amt, state.trip.displaySymbol) },
                onTransactionClick = onTransactionClick,
            )
        }
    }
}

@Composable
private fun TripTotalHeader(trip: TripDescriptor, totalMinor: Long, transactionCount: Int) {
    val rangeText = formatTripRange(trip.startAt, trip.endAt)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Spent during $rangeText",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = "${trip.currency} ${formatAmount(totalMinor, trip.displaySymbol)}",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (transactionCount > 0) {
            Text(
                text = if (transactionCount == 1) "1 transaction" else "$transactionCount transactions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForeignFilterRow(
    filter: ForeignFilter,
    categories: List<Category>,
    hasUnverified: Boolean,
    pendingCount: Int,
    onFilterChange: (ForeignFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = filter is ForeignFilter.All,
                onClick = { onFilterChange(ForeignFilter.All) },
                label = { Text("All") },
            )
        }
        if (pendingCount > 0) {
            item {
                FilterChip(
                    selected = filter is ForeignFilter.Pending,
                    onClick = { onFilterChange(ForeignFilter.Pending) },
                    label = { Text("Pending ($pendingCount)") },
                )
            }
        }
        if (hasUnverified) {
            item {
                FilterChip(
                    selected = filter is ForeignFilter.Unverified,
                    onClick = { onFilterChange(ForeignFilter.Unverified) },
                    label = { Text("Unverified") },
                )
            }
        }
        items(categories, key = { it.id }) { c ->
            FilterChip(
                selected = filter is ForeignFilter.Category && filter.id == c.id,
                onClick = { onFilterChange(ForeignFilter.Category(c.id)) },
                label = { Text(c.name) },
            )
        }
    }
}

@Composable
private fun NoTripsEmpty() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "No trips yet. Open one from the currency-review banner on Home, or " +
                "from Settings → Foreign currencies.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EmptyTripContent(state: ForeignUiState.Loaded) {
    val msg = when (state.filter) {
        ForeignFilter.All ->
            "No ${state.trip.currency} transactions captured for this trip yet."
        ForeignFilter.Pending -> "Nothing in \"Pending\" for this trip."
        ForeignFilter.Unverified -> "Nothing in \"Unverified\" for this trip."
        is ForeignFilter.Category -> {
            val name = state.categories.firstOrNull { it.id == state.filter.id }?.name ?: "this category"
            "Nothing in \"$name\" for this trip."
        }
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            msg,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun tripChipLabel(state: ForeignUiState): String = when (state) {
    ForeignUiState.Loading -> "Loading…"
    ForeignUiState.NoTrips -> "No trips"
    is ForeignUiState.Loaded -> {
        val range = formatTripChipRange(state.trip.startAt, state.trip.endAt)
        "${state.trip.currency} · $range"
    }
}

/** Header copy: "9 May 2026 to Today" or "9 May to 18 May 2026". */
private fun formatTripRange(start: Instant, end: Instant?): String {
    val today = Clock.System.now().toLocalDateTime(MalaysiaTimeZone).date
    val startDate = start.toLocalDateTime(MalaysiaTimeZone).date
    val startStr = formatLongDate(startDate, today)
    val endStr = end?.let { formatLongDate(it.toLocalDateTime(MalaysiaTimeZone).date, today) } ?: "Today"
    return "$startStr to $endStr"
}

/** Top-bar chip: compact "9 May – 18 May" or "9 May – Today". */
private fun formatTripChipRange(start: Instant, end: Instant?): String {
    val today = Clock.System.now().toLocalDateTime(MalaysiaTimeZone).date
    val startDate = start.toLocalDateTime(MalaysiaTimeZone).date
    val startStr = formatShortDate(startDate, today)
    val endStr = end?.let { formatShortDate(it.toLocalDateTime(MalaysiaTimeZone).date, today) } ?: "Today"
    return "$startStr – $endStr"
}

private val MONTH_SHORT = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** "9 May" same year, "9 May 2025" different year. */
private fun formatLongDate(date: LocalDate, today: LocalDate): String {
    val m = MONTH_SHORT[date.monthNumber - 1]
    return if (date.year == today.year) "${date.dayOfMonth} $m"
    else "${date.dayOfMonth} $m ${date.year}"
}

/** "9 May" same year, "9 May '25" different year — used in the compact chip. */
private fun formatShortDate(date: LocalDate, today: LocalDate): String {
    val m = MONTH_SHORT[date.monthNumber - 1]
    return if (date.year == today.year) "${date.dayOfMonth} $m"
    else "${date.dayOfMonth} $m '${(date.year % 100).toString().padStart(2, '0')}"
}

