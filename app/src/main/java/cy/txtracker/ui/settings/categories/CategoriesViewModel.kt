package cy.txtracker.ui.settings.categories

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
class CategoriesViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val categories: StateFlow<List<Category>> = repository.observeAllCategories()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    fun add(name: String, color: Int) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        viewModelScope.launch {
            val existing = categories.value
            val nextSortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            // Unique-name violation just throws and is ignored. The dialog disables Save when
            // the name collides with an existing one, so we don't expect to hit that here.
            runCatching {
                repository.addCategory(
                    Category(
                        name = cleanName,
                        color = color,
                        isCustom = true,
                        sortOrder = nextSortOrder,
                    ),
                )
            }
        }
    }

    fun rename(category: Category, newName: String) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty() || cleanName == category.name) return
        viewModelScope.launch {
            runCatching { repository.updateCategory(category.copy(name = cleanName)) }
        }
    }

    fun delete(category: Category) {
        viewModelScope.launch { repository.deleteCategory(category) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
