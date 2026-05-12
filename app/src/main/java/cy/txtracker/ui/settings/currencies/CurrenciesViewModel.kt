package cy.txtracker.ui.settings.currencies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.TrackedCurrency
import cy.txtracker.data.TripWindow
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class CurrencyRow(
    val currency: TrackedCurrency,
    val activeTrip: TripWindow?,
)

data class CurrenciesUiState(
    val rows: List<CurrencyRow>,
)

@HiltViewModel
class CurrenciesViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val state: StateFlow<CurrenciesUiState> = combine(
        repository.observeTrackedCurrencies(),
        repository.observeAllTrips(),
    ) { currencies, trips ->
        val now = Clock.System.now()
        val byCurrency = trips.groupBy { it.currency }
        CurrenciesUiState(
            rows = currencies.map { c ->
                CurrencyRow(
                    currency = c,
                    activeTrip = byCurrency[c.code]?.firstOrNull {
                        it.startAt <= now && (it.endAt == null || it.endAt > now)
                    },
                )
            },
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        CurrenciesUiState(emptyList()),
    )

    fun openTrip(currency: String, startAt: Instant, endAt: Instant?) {
        viewModelScope.launch { repository.openTrip(currency, startAt, endAt) }
    }

    fun addCurrency(code: String) {
        viewModelScope.launch { repository.ensureTrackedCurrency(code) }
    }
}
