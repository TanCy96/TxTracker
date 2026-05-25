package cy.txtracker.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.TrackedCurrency
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
import kotlinx.datetime.Instant

sealed interface EditUiState {
    data object Loading : EditUiState
    data object Missing : EditUiState
    data class Editing(
        val transaction: Transaction,
        val categories: List<Category>,
        /** Existing note for this transaction's merchant, or null when none. */
        val merchantNote: String?,
        val trackedCurrencies: List<TrackedCurrency>,
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
                    merchantNote = repository.getMerchantNote(tx.merchantNormalized)?.note,
                    trackedCurrencies = repository.observeTrackedCurrencies().first(),
                )
            }
        }
    }

    /**
     * Sets (or clears, when [note] is blank) the free-text note for the current
     * transaction's merchant. Note is keyed by merchantNormalized so it applies to
     * every transaction from that same merchant going forward.
     */
    fun setMerchantNote(transactionId: Long, note: String?) {
        viewModelScope.launch {
            val tx = repository.getTransaction(transactionId) ?: return@launch
            repository.setMerchantNote(tx.merchantNormalized, note)
            // Refresh local state so the sheet's note field reflects the saved value.
            val refreshed = repository.getMerchantNote(tx.merchantNormalized)?.note
            val current = _state.value
            if (current is EditUiState.Editing) {
                _state.value = current.copy(merchantNote = refreshed)
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

    /**
     * Renames the transaction's merchant. Useful when the parser captured the wrong
     * merchant (e.g. a `(review)` placeholder from the permissive extractor). On success,
     * refreshes the sheet's local state so the new name (and any newly-applicable
     * merchant note) is visible. On collision the repository returns false and we leave
     * state untouched — the field reverts on the next recomposition.
     */
    fun setMerchant(transactionId: Long, merchantRaw: String) {
        viewModelScope.launch {
            val ok = repository.setMerchant(transactionId, merchantRaw)
            if (!ok) return@launch
            val refreshed = repository.getTransaction(transactionId) ?: return@launch
            val refreshedNote = repository.getMerchantNote(refreshed.merchantNormalized)?.note
            val current = _state.value
            if (current is EditUiState.Editing) {
                _state.value = current.copy(
                    transaction = refreshed,
                    merchantNote = refreshedNote,
                )
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

    fun setCurrency(transactionId: Long, currency: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repository.setCurrency(transactionId, currency)
            if (ok) {
                val refreshed = repository.getTransaction(transactionId) ?: return@launch
                val current = _state.value
                if (current is EditUiState.Editing) {
                    _state.value = current.copy(transaction = refreshed)
                }
            }
            onResult(ok)
        }
    }

    fun openTrip(currency: String, startAt: Instant, endAt: Instant?, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.openTrip(currency, startAt, endAt)
            val current = _state.value
            if (current is EditUiState.Editing) {
                val refreshed = repository.getTransaction(current.transaction.id) ?: return@launch
                _state.value = current.copy(transaction = refreshed)
            }
            onDone()
        }
    }

    suspend fun findActiveTrip(currency: String, at: Instant) =
        repository.findActiveTrip(currency, at)

    fun addCurrency(code: String) {
        viewModelScope.launch { repository.ensureTrackedCurrency(code) }
    }

    /**
     * Saves a per-package raw-text rewrite rule. Applied to incoming notifications from
     * [packageName] BEFORE the parser runs, so app-specific noise can be stripped without
     * code changes. Called from the row's "Improve parsing for this app" dialog.
     */
    fun upsertRewrite(packageName: String, pattern: String, replacement: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.upsertRewrite(
                packageName = packageName,
                pattern = pattern,
                replacement = replacement,
            )
            onDone()
        }
    }
}
