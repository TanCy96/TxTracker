package cy.txtracker.ui.settings.descriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.CategoryDescriptionMapping
import cy.txtracker.data.MerchantDescriptionMapping
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.TimeBucket
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DescriptionMappingsUiState(
    val merchantRows: List<MerchantDescriptionMapping> = emptyList(),
    val categoryRows: List<CategoryDescriptionRow> = emptyList(),
)

data class CategoryDescriptionRow(
    val mapping: CategoryDescriptionMapping,
    val category: Category?,
)

@HiltViewModel
class DescriptionMappingsViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val state: StateFlow<DescriptionMappingsUiState> =
        combine(
            repository.observeMerchantDescriptionMappings(),
            repository.observeCategoryDescriptionMappings(),
            repository.observeAllCategories(), // all-scope: display-only id→name lookup (not a picker)
        ) { merchants, categoryMaps, categories ->
            val byId = categories.associateBy { it.id }
            DescriptionMappingsUiState(
                merchantRows = merchants.sortedBy { it.merchantNormalized },
                categoryRows = categoryMaps
                    .map { CategoryDescriptionRow(it, byId[it.categoryId]) }
                    .sortedBy { it.category?.sortOrder ?: Int.MAX_VALUE },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = DescriptionMappingsUiState(),
        )

    fun unlinkMerchant(merchantNormalized: String, bucket: TimeBucket) {
        viewModelScope.launch {
            repository.unlinkMerchantDescription(merchantNormalized, bucket)
        }
    }

    fun unlinkCategory(categoryId: Long, bucket: TimeBucket) {
        viewModelScope.launch {
            repository.unlinkCategoryDescription(categoryId, bucket)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
