package cy.txtracker.ui.settings.currencies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.TripWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** A trip + the count of transactions in its window for the current currency. */
data class TripWithCount(
    val trip: TripWindow,
    val transactionCount: Int,
)

data class TripHistoryUiState(
    val currency: String,
    val trips: List<TripWithCount>,
)

@HiltViewModel
class TripHistoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: TransactionRepository,
) : ViewModel() {

    private val currency: String = savedState["code"] ?: "MYR"

    val state: StateFlow<TripHistoryUiState> = combine(
        repository.observeTripsForCurrency(currency),
        repository.observeTransactionsForCurrency(currency),
    ) { trips, txs ->
        TripHistoryUiState(
            currency = currency,
            trips = trips.map { TripWithCount(it, countInWindow(txs, it)) },
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        TripHistoryUiState(currency = currency, trips = emptyList()),
    )

    fun endTrip(tripId: Long) {
        viewModelScope.launch { repository.closeTrip(tripId, Clock.System.now()) }
    }

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch { repository.deleteTrip(tripId) }
    }

    fun editTrip(tripId: Long, startAt: Instant, endAt: Instant?) {
        viewModelScope.launch { repository.editTrip(tripId, startAt, endAt) }
    }

    /**
     * Counts transactions whose `occurredAt` falls in the trip's half-open
     * window `[startAt, endAt)`. Open-ended trips count everything from
     * `startAt` onward. Filters by currency happen at the query level — this
     * function only does the temporal slice.
     */
    private fun countInWindow(txs: List<Transaction>, trip: TripWindow): Int =
        txs.count { tx ->
            tx.occurredAt >= trip.startAt &&
                (trip.endAt == null || tx.occurredAt < trip.endAt)
        }
}
