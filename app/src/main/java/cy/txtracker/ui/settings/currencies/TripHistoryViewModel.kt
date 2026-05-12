package cy.txtracker.ui.settings.currencies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.TripWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class TripHistoryUiState(
    val currency: String,
    val trips: List<TripWindow>,
)

@HiltViewModel
class TripHistoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: TransactionRepository,
) : ViewModel() {

    private val currency: String = savedState["code"] ?: "MYR"

    val state: StateFlow<TripHistoryUiState> =
        repository.observeTripsForCurrency(currency)
            .map { TripHistoryUiState(currency = currency, trips = it) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                TripHistoryUiState(currency = currency, trips = emptyList()),
            )

    fun endTrip(tripId: Long) {
        viewModelScope.launch { repository.closeTrip(tripId, Clock.System.now()) }
    }
}
