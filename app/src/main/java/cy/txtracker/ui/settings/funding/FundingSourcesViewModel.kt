package cy.txtracker.ui.settings.funding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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

    val state: StateFlow<FundingSourcesUiState> =
        repository.observeFundingSources()
            .map { sources ->
                val counts = sources.associate { it.id to repository.fundingSourceTxCount(it.id) }
                val defaultCashId = sources
                    .filter { it.kind == FundingSourceKind.CASH }
                    .minByOrNull { it.id }?.id
                FundingSourcesUiState(sources = sources, txCounts = counts, defaultCashId = defaultCashId)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = FundingSourcesUiState(),
            )

    fun rename(id: Long, name: String) = viewModelScope.launch {
        repository.renameFundingSource(id, name)
    }

    fun setKind(id: Long, kind: FundingSourceKind) = viewModelScope.launch {
        repository.setFundingSourceKind(id, kind)
    }

    fun merge(sourceId: Long, targetId: Long) = viewModelScope.launch {
        repository.mergeFundingSources(sourceId, targetId)
    }

    fun delete(id: Long) = viewModelScope.launch {
        repository.deleteFundingSource(id)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
