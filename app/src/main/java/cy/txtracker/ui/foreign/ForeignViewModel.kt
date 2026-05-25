package cy.txtracker.ui.foreign

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.TripWindow
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.parsing.Currencies
import cy.txtracker.ui.home.CategoryBreakdownEntry
import cy.txtracker.ui.home.DayGroup
import cy.txtracker.ui.home.TransactionWithCategory
import cy.txtracker.ui.home.snapStaleForeignCategoryToAll
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime

/**
 * Foreign tab is "Home, but scoped to one trip at a time". State + projection logic
 * intentionally mirrors [cy.txtracker.ui.home.HomeViewModel] so the UI can render with
 * the shared composables. Differences from Home:
 *   - The unit of navigation is a trip (currency + date window), not a calendar month.
 *   - Totals and breakdowns are in the trip's currency, not MYR.
 *   - The "Currency review" filter doesn't exist here — those rows are parked on Home.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForeignViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    /** Selected position in the sorted trips list (0 = newest). Coerced into range on
     *  every projection so deletion of the current trip doesn't leave a dangling index. */
    private val _selectedTripIndex = MutableStateFlow(0)
    private val _filter = MutableStateFlow<ForeignFilter>(ForeignFilter.All)

    val state: StateFlow<ForeignUiState> =
        combine(
            repository.observeAllTrips(),
            _selectedTripIndex,
            _filter,
            repository.observeAllCategories(),
        ) { trips, idx, filter, cats -> Quad(trips.sortedByDescending { it.startAt }, idx, filter, cats) }
            .flatMapLatest { (trips, requestedIdx, filter, cats) ->
                if (trips.isEmpty()) {
                    MutableStateFlow(ForeignUiState.NoTrips)
                } else {
                    val idx = requestedIdx.coerceIn(0, trips.lastIndex)
                    // Re-pin the requested index if the trips list shrank under us.
                    if (idx != requestedIdx) _selectedTripIndex.value = idx
                    val trip = trips[idx]
                    val end = trip.endAtExclusive
                    combine(
                        repository.observeTransactionsForTrip(trip.currency, trip.startAt, end),
                        repository.observeMerchantNotes(),
                    ) { txs, notes ->
                        buildLoaded(
                            trips = trips,
                            tripIndex = idx,
                            trip = trip,
                            categories = cats,
                            transactions = txs,
                            notes = notes,
                            filter = filter,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ForeignUiState.Loading)

    fun previousTrip() { _selectedTripIndex.update { (it + 1).coerceAtLeast(0) } }
    fun nextTrip() { _selectedTripIndex.update { (it - 1).coerceAtLeast(0) } }
    fun setFilter(filter: ForeignFilter) { _filter.value = filter }

    private fun buildLoaded(
        trips: List<TripWindow>,
        tripIndex: Int,
        trip: TripWindow,
        categories: List<Category>,
        transactions: List<Transaction>,
        notes: List<cy.txtracker.data.MerchantNote>,
        filter: ForeignFilter,
    ): ForeignUiState.Loaded {
        val byId = categories.associateBy { it.id }
        val joined = transactions.map { TransactionWithCategory(it, it.categoryId?.let(byId::get)) }

        // Breakdown FIRST so snap-back can consult it.
        val breakdown = transactions
            .filter { it.direction == cy.txtracker.data.Direction.OUT }
            .groupBy { it.categoryId }
            .map { (categoryId, rows) ->
                CategoryBreakdownEntry(category = categoryId?.let(byId::get), totalMinor = rows.sumOf { it.amountMinor })
            }
            .sortedWith(
                compareByDescending<CategoryBreakdownEntry> { it.category != null }
                    .thenBy { it.category?.sortOrder ?: Int.MAX_VALUE },
            )

        val effectiveFilter = snapStaleForeignCategoryToAll(filter, breakdown)
        if (effectiveFilter != filter) {
            _filter.value = effectiveFilter
        }

        val filtered = when (effectiveFilter) {
            ForeignFilter.All -> joined
            ForeignFilter.Unverified -> joined.filter { it.transaction.categoryId == null }
            ForeignFilter.Pending -> joined.filter { it.transaction.needsVerification }
            is ForeignFilter.Category -> joined.filter { it.transaction.categoryId == effectiveFilter.id }
        }

        val days = filtered
            .groupBy { it.transaction.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
            .toSortedMap(reverseOrder())
            .map { (date, list) -> DayGroup(date, list) }

        val total = transactions
            .filter { it.direction == cy.txtracker.data.Direction.OUT }
            .sumOf { it.amountMinor }

        val symbol = Currencies.CODE_TO_DISPLAY_SYMBOL[trip.currency] ?: trip.currency

        return ForeignUiState.Loaded(
            trip = TripDescriptor(
                tripId = trip.id,
                currency = trip.currency,
                displaySymbol = symbol,
                startAt = trip.startAt,
                endAt = trip.endAt,
            ),
            tripIndex = tripIndex,
            tripCount = trips.size,
            filter = effectiveFilter,
            totalMinor = total,
            transactionCount = transactions.size,
            breakdown = breakdown,
            categories = categories,
            days = days,
            notesByMerchant = notes.associate { it.merchantNormalized to it.note },
            pendingCount = transactions.count { it.needsVerification },
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        // Placeholder used by trips with `endAt = null` (open-ended). Picked very far in
        // the future so future captures keep landing inside the window.
        private val DISTANT_FUTURE: Instant = Instant.parse("9999-12-31T23:59:59Z")
    }

    /** Open-ended trips treat the upper bound as DISTANT_FUTURE so newly-captured rows
     *  for the trip currency keep showing up under the trip. */
    private val TripWindow.endAtExclusive: Instant
        get() = endAt ?: DISTANT_FUTURE

    /** Lightweight 4-tuple — Kotlin's stdlib stops at Triple. */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
