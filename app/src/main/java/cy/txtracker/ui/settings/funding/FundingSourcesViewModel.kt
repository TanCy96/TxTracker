package cy.txtracker.ui.settings.funding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FundingSourcesUiState(
    val sources: List<FundingSource> = emptyList(),
    val txCounts: Map<Long, Int> = emptyMap(),
    val defaultCashId: Long? = null,
)

@HiltViewModel
class FundingSourcesViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)

    val state: StateFlow<FundingSourcesUiState> =
        combine(repository.observeFundingSources(), refreshTrigger) { sources, _ ->
            val counts = sources.associate { it.id to repository.fundingSourceTxCount(it.id) }
            val defaultCashId = sources
                .filter { it.kind == FundingSourceKind.CASH }
                .minByOrNull { it.id }?.id
            FundingSourcesUiState(sources = sources, txCounts = counts, defaultCashId = defaultCashId)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = FundingSourcesUiState(),
        )

    fun rename(id: Long, name: String) = viewModelScope.launch {
        repository.renameFundingSource(id, name)
        refreshTrigger.value++
    }

    fun setKind(id: Long, kind: FundingSourceKind) = viewModelScope.launch {
        repository.setFundingSourceKind(id, kind)
        refreshTrigger.value++
    }

    fun merge(sourceId: Long, targetId: Long) = viewModelScope.launch {
        repository.mergeFundingSources(sourceId, targetId)
        refreshTrigger.value++
    }

    fun delete(id: Long) = viewModelScope.launch {
        repository.deleteFundingSource(id)
        refreshTrigger.value++
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
