package cy.txtracker.ui.settings.sldebit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.SlDebitAccount
import cy.txtracker.data.SlDebitDeposit
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

data class SlDebitUiState(
    val account: SlDebitAccount? = null,
    val balanceMinor: Long = 0L,
    val deposits: List<SlDebitDeposit> = emptyList(),
)

@HiltViewModel
class SlDebitViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val state: StateFlow<SlDebitUiState> =
        combine(
            repository.observeSlDebitAccount(),
            repository.observeSlDebitBalance(),
            repository.observeSlDebitDeposits(),
        ) { account, balance, deposits ->
            SlDebitUiState(account = account, balanceMinor = balance, deposits = deposits)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = SlDebitUiState(),
        )

    fun rename(name: String) = viewModelScope.launch { repository.renameSlDebitAccount(name) }
    fun setDefaultPercent(percent: Int) = viewModelScope.launch { repository.setSlDebitDefaultPercent(percent) }
    fun addDeposit(amountMinor: Long, occurredAt: Instant, note: String?) =
        viewModelScope.launch { repository.addSlDebitDeposit(amountMinor, occurredAt, note) }
    fun updateDeposit(deposit: SlDebitDeposit) = viewModelScope.launch { repository.updateSlDebitDeposit(deposit) }
    fun deleteDeposit(id: Long) = viewModelScope.launch { repository.deleteSlDebitDeposit(id) }
}
