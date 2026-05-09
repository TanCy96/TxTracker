package cy.txtracker.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

sealed interface EditUiState {
    data object Loading : EditUiState
    data object Missing : EditUiState
    data class Editing(
        val transaction: Transaction,
        val categories: List<Category>,
    ) : EditUiState
}

@HiltViewModel
class EditTransactionViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<EditUiState>(EditUiState.Loading)
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    fun load(transactionId: Long) {
        viewModelScope.launch {
            val tx = repository.getTransaction(transactionId)
            _state.value = if (tx == null) {
                EditUiState.Missing
            } else {
                EditUiState.Editing(
                    transaction = tx,
                    categories = repository.observeAllCategories().first(),
                )
            }
        }
    }

    /**
     * Sets the transaction's category and (when [learn] is true) upserts the
     * merchant→category mapping so future txs from the same merchant auto-categorize.
     */
    fun setCategory(transactionId: Long, categoryId: Long?, learn: Boolean = true) {
        viewModelScope.launch {
            repository.setCategory(
                txId = transactionId,
                categoryId = categoryId,
                learnMapping = learn,
                now = Clock.System.now(),
            )
            // Refresh local state so the sheet's selection ticks over to the new category.
            val refreshed = repository.getTransaction(transactionId) ?: return@launch
            val current = _state.value
            if (current is EditUiState.Editing) {
                _state.value = current.copy(transaction = refreshed)
            }
        }
    }

    /**
     * Sets the description and (when [learn] is true) upserts both
     * `MerchantDescriptionMapping(merchant, bucket)` and, if the transaction has a category,
     * `CategoryDescriptionMapping(category, bucket)` so future similar transactions get a
     * suggestion. Blank input clears the description without writing any mapping.
     */
    fun setDescription(transactionId: Long, description: String?, learn: Boolean = true) {
        viewModelScope.launch {
            repository.setDescription(
                txId = transactionId,
                description = description,
                learnMappings = learn,
                now = Clock.System.now(),
            )
            val refreshed = repository.getTransaction(transactionId) ?: return@launch
            val current = _state.value
            if (current is EditUiState.Editing) {
                _state.value = current.copy(transaction = refreshed)
            }
        }
    }

    /** Clears the `needsVerification` flag — user confirmed the heuristic-captured row is real. */
    fun confirmVerification(transactionId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.setNeedsVerification(transactionId, false)
            onDone()
        }
    }

    fun delete(transactionId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.delete(transactionId)
            onDone()
        }
    }
}
