package cy.txtracker.ui.foreign

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.TrackedCurrency
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.domain.YearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForeignViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    private val visibleMonth = MutableStateFlow(YearMonth.current(MalaysiaTimeZone))

    val state: StateFlow<ForeignUiState> = visibleMonth
        .flatMapLatest { ym ->
            val start = ym.start(MalaysiaTimeZone)
            val end = ym.endExclusive(MalaysiaTimeZone)
            combine(
                repository.observeForeignTransactionsBetween(start, end),
                repository.observeTrackedCurrencies(),
            ) { txs, currencies -> buildState(txs, currencies) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForeignUiState.Loading)

    fun setMonth(month: YearMonth) { visibleMonth.value = month }

    private fun buildState(
        txs: List<Transaction>,
        currencies: List<TrackedCurrency>,
    ): ForeignUiState.Loaded {
        val symbolByCode = currencies.associateBy({ it.code }, { it.displaySymbol })
        val grouped = txs.groupBy { it.currency }.mapValues { (code, rows) ->
            CurrencyGroup(
                code = code,
                displaySymbol = symbolByCode[code] ?: code,
                total = rows.sumOf { it.amountMinor },
                transactions = rows,
                categoryTotals = rows.groupBy { it.categoryId }
                    .mapValues { (_, r) -> r.sumOf { it.amountMinor } },
            )
        }
        return ForeignUiState.Loaded(byCurrency = grouped)
    }
}
