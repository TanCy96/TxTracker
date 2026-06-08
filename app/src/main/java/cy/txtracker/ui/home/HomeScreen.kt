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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.data.Category
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.Transaction
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.ui.common.KIND_ORDER
import cy.txtracker.ui.common.fundingBucketLabel
import cy.txtracker.ui.currency.TripCreationDialog
import cy.txtracker.ui.edit.DeletedTransaction
import cy.txtracker.ui.edit.EditTransactionSheet
import cy.txtracker.ui.format.formatDayHeader
import cy.txtracker.ui.format.formatMyr
import cy.txtracker.ui.format.formatTimeOfDay
import cy.txtracker.ui.format.formatYearMonth
import cy.txtracker.ui.manual.AddManualSheet
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.plus

@Composable
fun HomeRoute(
    onSettingsClick: () -> Unit = {},
    onSlDebitClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val slDebitUnlocked by viewModel.slDebitUnlocked.collectAsState()
    var editingTxId by remember { mutableStateOf<Long?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var tripDialogOffer by remember { mutableStateOf<BannerOffer?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    HomeScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onPrevMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
        onFilterChange = viewModel::setFilter,
        onFundingBucketToggle = viewModel::toggleFundingBucket,
        onTransactionClick = { tx -> editingTxId = tx.id },
        onAddClick = { showAddSheet = true },
        onSettingsClick = onSettingsClick,
        onSlDebitClick = onSlDebitClick,
        slDebitUnlocked = slDebitUnlocked,
        onDismissBanner = { currency -> viewModel.dismissBanner(currency) },
        onStartTrip = { offer -> tripDialogOffer = offer },
    )

    editingTxId?.let { id ->
        EditTransactionSheet(
            transactionId = id,
            onDismiss = { editingTxId = null },
            onDeleted = { snapshot ->
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Transaction deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreTransaction(snapshot)
                    }
                }
            },
        )
    }
    if (showAddSheet) {
        AddManualSheet(onDismiss = { showAddSheet = false })
    }
    tripDialogOffer?.let { offer ->
        TripCreationDialog(
            currency = offer.currency,
            defaultStartAt = offer.earliestOccurredAt,
            defaultEndAt = offer.earliestOccurredAt.plus(14, DateTimeUnit.DAY, MalaysiaTimeZone),
            onConfirm = { startAt, endAt ->
                viewModel.openTrip(offer.currency, startAt, endAt)
                tripDialogOffer = null
            },
            onDismiss = { tripDialogOffer = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onFilterChange: (HomeFilter) -> Unit,
    onFundingBucketToggle: (FundingSourceKind) -> Unit = {},
    onTransactionClick: (Transaction) -> Unit,
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSlDebitClick: () -> Unit = {},
    slDebitUnlocked: Boolean = false,
    onDismissBanner: (String) -> Unit = {},
    onStartTrip: (BannerOffer) -> Unit = {},
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            MonthTotalHeader(totalMinor = state.totalMinor, transactionCount = state.transactionCount)

            if (slDebitUnlocked) {
                SlDebitBalanceCard(
                    name = state.slDebitName,
                    balanceMinor = state.slDebitBalanceMinor,
                    onClick = onSlDebitClick,
                )
            }

            val onChipTap: (HomeFilter) -> Unit = { target ->
                onFilterChange(if (state.filter == target) HomeFilter.All else target)
            }

            val statusChips = buildList {
                if (state.pendingCount > 0) add(
                    StatusChipSpec(
                        label = "Pending (${state.pendingCount})",
                        selected = state.filter is HomeFilter.Pending,
                        onTap = { onChipTap(HomeFilter.Pending) },
                    )
                )
                if (state.currencyReviewCount > 0) add(
                    StatusChipSpec(
                        label = "Currency review (${state.currencyReviewCount})",
                        selected = state.filter is HomeFilter.CurrencyReview,
                        onTap = { onChipTap(HomeFilter.CurrencyReview) },
                    )
                )
            }
            StatusFilterRow(specs = statusChips)

            CategoryBreakdownRow(
                breakdown = state.breakdown,
                amountFormatter = ::formatMyr,
                isSelected = { entry ->
                    val f = state.filter
                    when {
                        entry.category == null -> f is HomeFilter.Unverified
                        else -> f is HomeFilter.Category && f.id == entry.category.id
                    }
                },
                onChipTap = { entry ->
                    val target = if (entry.category == null) {
                        HomeFilter.Unverified
                    } else {
                        HomeFilter.Category(entry.category.id)
                    }
                    onChipTap(target)
                },
            )
            FundingBucketFilterRow(
                counts = state.fundingBucketCounts,
                selected = state.fundingBucketFilter,
                onToggle = onFundingBucketToggle,
            )
            HorizontalDivider()
            state.bannerCurrency?.let { offer ->
                CurrencyReviewBanner(
                    offer = offer,
                    onStart = { onStartTrip(offer) },
                    onDismiss = { onDismissBanner(offer.currency) },
                )
            }
            if (state.days.isEmpty()) {
                EmptyState(state)
            } else {
                TransactionList(
                    days = state.days,
                    notesByMerchant = state.notesByMerchant,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    amountFormatter = ::formatMyr,
                    onTransactionClick = onTransactionClick,
                )
            }
        }
    }
}

@Composable
private fun SlDebitBalanceCard(name: String, balanceMinor: Long, onClick: () -> Unit) {
    androidx.compose.material3.ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                cy.txtracker.ui.format.formatMyr(balanceMinor),
                style = MaterialTheme.typography.titleMedium,
                color = if (balanceMinor < 0) MaterialTheme.colorScheme.error else cy.txtracker.ui.theme.SlShareGreen,
            )
        }
    }
}

@Composable
private fun CurrencyReviewBanner(
    offer: BannerOffer,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Detected ${offer.currency} transactions outside a trip. Start tracking?",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onStart) { Text("Start") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun MonthTotalHeader(totalMinor: Long, transactionCount: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = "Spent this month", style = MaterialTheme.typography.labelMedium)
        Text(
            text = formatMyr(totalMinor),
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
internal fun CategoryBreakdownRow(
    breakdown: List<CategoryBreakdownEntry>,
    amountFormatter: (Long) -> String,
    isSelected: (CategoryBreakdownEntry) -> Boolean,
    onChipTap: (CategoryBreakdownEntry) -> Unit,
) {
    if (breakdown.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(breakdown, key = { entry -> entry.category?.id ?: -1L }) { entry ->
            FilterChip(
                selected = isSelected(entry),
                onClick = { onChipTap(entry) },
                leadingIcon = {
                    val color = entry.category?.color?.let(::Color) ?: MaterialTheme.colorScheme.outline
                    Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
                },
                label = {
                    val name = entry.category?.name ?: "Unverified"
                    Text("$name  ${amountFormatter(entry.totalMinor)}")
                },
            )
        }
    }
}

/**
 * Shared spec for the thin status-filter row above [CategoryBreakdownRow]. Each spec is
 * one chip; the row only renders when at least one spec is present (callers omit chips
 * whose underlying count is zero, so an empty list means "row hides itself").
 */
internal data class StatusChipSpec(
    val label: String,
    val selected: Boolean,
    val onTap: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusFilterRow(specs: List<StatusChipSpec>) {
    if (specs.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(specs, key = { spec -> spec.label }) { spec ->
            FilterChip(
                selected = spec.selected,
                onClick = spec.onTap,
                label = { Text(spec.label) },
            )
        }
    }
}

/**
 * Horizontal chip row that lets the user filter transactions by funding bucket (kind).
 * Chips only appear for buckets that have at least one transaction in the current month.
 * Multi-select: tapping a selected chip deselects it; tapping an unselected chip adds it.
 * The row hides itself entirely when no bucket has any transactions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FundingBucketFilterRow(
    counts: Map<FundingSourceKind, Int>,
    selected: Set<FundingSourceKind>,
    onToggle: (FundingSourceKind) -> Unit,
) {
    val visibleKinds = KIND_ORDER.filter { kind -> (counts[kind] ?: 0) > 0 }
    if (visibleKinds.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(visibleKinds, key = { kind -> kind.name }) { kind ->
            val count = counts[kind] ?: 0
            FilterChip(
                selected = kind in selected,
                onClick = { onToggle(kind) },
                label = { Text("${fundingBucketLabel(kind)} ($count)") },
            )
        }
    }
}

@Composable
internal fun TransactionList(
    days: List<DayGroup>,
    notesByMerchant: Map<String, String>,
    contentPadding: PaddingValues,
    amountFormatter: (Long) -> String,
    onTransactionClick: (Transaction) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding) {
        days.forEachIndexed { index, group ->
            item(key = "header-${group.date}") {
                DayHeader(group, isFirst = index == 0, amountFormatter = amountFormatter)
            }
            items(group.transactions, key = { it.transaction.id }) { row ->
                TransactionRow(
                    row = row,
                    note = notesByMerchant[row.transaction.merchantNormalized],
                    amountFormatter = amountFormatter,
                    onClick = { onTransactionClick(row.transaction) },
                )
            }
        }
    }
}

@Composable
internal fun DayHeader(
    group: DayGroup,
    isFirst: Boolean,
    amountFormatter: (Long) -> String,
) {
    val total = group.transactions.sumOf { it.transaction.amountMinor - (it.transaction.reimbursedMinor ?: 0L) }
    Column {
        if (!isFirst) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
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
                    text = amountFormatter(total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TransactionRow(
    row: TransactionWithCategory,
    note: String?,
    amountFormatter: (Long) -> String,
    onClick: () -> Unit,
) {
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
                    if (!note.isNullOrBlank()) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                RowAmount(transaction = row.transaction, amountFormatter = amountFormatter)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(category = row.category)
                if (row.transaction.needsVerification) {
                    Spacer(Modifier.size(8.dp))
                    PendingPill()
                }
            }
            // Prefer the user's description when set. Otherwise, for Pending rows that came
            // through the permissive layer (merchant is just "(review)"), show a snippet of
            // the raw notification so the user can identify the payment without opening the
            // edit sheet.
            val hint = row.transaction.description?.takeIf { it.isNotBlank() }
                ?: if (row.transaction.needsVerification) {
                    row.transaction.rawText
                        ?.take(100)
                        ?.replace('\n', ' ')
                        ?.takeIf { it.isNotBlank() }
                } else null
            hint?.let { text ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowAmount(transaction: Transaction, amountFormatter: (Long) -> String) {
    val share = transaction.slShareMinor
    val reimbursed = transaction.reimbursedMinor
    if (share == null && reimbursed == null) {
        Text(
            text = amountFormatter(transaction.amountMinor),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    } else {
        val net = transaction.amountMinor - (share ?: 0L) - (reimbursed ?: 0L)
        Column(horizontalAlignment = Alignment.End) {
            // Net "what you actually paid" — emphasized.
            Text(
                text = amountFormatter(net),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            // Original amount — de-emphasized, struck through.
            Text(
                text = amountFormatter(transaction.amountMinor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
            )
            // SL Debit share — "money coming back" accent.
            if (share != null) {
                Text(
                    text = "−${amountFormatter(share)} SL",
                    style = MaterialTheme.typography.bodySmall,
                    color = cy.txtracker.ui.theme.SlShareGreen,
                )
            }
            // Reimbursed portion — "money coming back" accent.
            if (reimbursed != null) {
                Text(
                    text = "−${amountFormatter(reimbursed)} Reimbursed",
                    style = MaterialTheme.typography.bodySmall,
                    color = cy.txtracker.ui.theme.ReimbursedAccent,
                )
            }
        }
    }
}

@Composable
internal fun PendingPill() {
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
internal fun CategoryChip(category: Category?) {
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
            val (title, subtitle) = emptyStateCopy(state)
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The copy adapts to which dimension is "empty":
 *   - Loading: a placeholder.
 *   - True empty month (totalMinor == 0 with All filter): the onboarding-style hint.
 *   - Filter excludes everything in a non-empty month: a filter-specific message so the user
 *     understands the rows aren't gone, just filtered out.
 */
private fun emptyStateCopy(state: HomeUiState): Pair<String, String?> {
    if (state.isLoading) return "Loading…" to null

    val monthLabel = formatYearMonth(state.yearMonth)
    if (state.totalMinor == 0L && state.filter is HomeFilter.All) {
        return "No spending captured for $monthLabel yet." to
            "Make a payment in a connected app, or tap + to add a manual entry."
    }

    val filterLabel = when (val f = state.filter) {
        HomeFilter.All -> null
        HomeFilter.Unverified -> "Unverified"
        HomeFilter.Pending -> "Pending"
        HomeFilter.CurrencyReview -> "Currency review"
        is HomeFilter.Category -> state.categories.firstOrNull { it.id == f.id }?.name
    }
    return if (filterLabel != null) {
        "Nothing in \"$filterLabel\" for $monthLabel." to "Switch the filter or pick a different month."
    } else {
        "No transactions to show." to null
    }
}
