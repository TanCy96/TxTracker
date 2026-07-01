package cy.txtracker.ui.settings.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TripCategoriesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TransactionRepository,
) : ViewModel() {

    private val tripId: Long = checkNotNull(savedStateHandle["tripId"])

    val categories: StateFlow<List<Category>> = repository.observeCategoriesForTrip(tripId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    fun add(name: String, color: Int) {
        viewModelScope.launch {
            repository.addCategoryInScope(name, color, keywordPattern = null, tripId = tripId)
        }
    }

    fun rename(original: Category, name: String, color: Int) {
        viewModelScope.launch {
            repository.renameCategoryInScope(original, name, color, newKeywordPattern = null)
        }
    }

    fun delete(category: Category) {
        viewModelScope.launch { repository.deleteCategory(category) }
    }

    fun reorder(ordered: List<Category>) {
        viewModelScope.launch { repository.reorderCategories(ordered) }
    }
}
