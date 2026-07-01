package cy.txtracker.ui.settings.merchants

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.MerchantMapping
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MerchantMappingsUiState(
    val rows: List<MerchantMappingRow> = emptyList(),
)

data class MerchantMappingRow(
    val mapping: MerchantMapping,
    val category: Category?,
)

@HiltViewModel
class MerchantMappingsViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val state: StateFlow<MerchantMappingsUiState> =
        combine(
            repository.observeMerchantMappings(),
            repository.observeAllCategories(), // all-scope: display-only id→name lookup (not a picker)
        ) { mappings, categories ->
            val byId = categories.associateBy { it.id }
            MerchantMappingsUiState(
                rows = mappings
                    .map { MerchantMappingRow(it, byId[it.categoryId]) }
                    .sortedBy { it.mapping.merchantNormalized },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = MerchantMappingsUiState(),
        )

    fun unlink(merchantNormalized: String) {
        viewModelScope.launch { repository.unlinkMerchantMapping(merchantNormalized) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
