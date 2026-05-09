package cy.txtracker.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.MalaysiaTimeZone
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

data class AddManualUiState(
    val amountText: String = "",
    val merchantText: String = "",
    val categoryId: Long? = null,
    val descriptionText: String = "",
    val date: LocalDate = LocalDate(2000, 1, 1),
    val time: LocalTime = LocalTime(0, 0),
    val categories: List<Category> = emptyList(),
    val isSaving: Boolean = false,
) {
    val amountMinor: Long? get() = parseAmountMinor(amountText)
    val canSave: Boolean
        get() = !isSaving && (amountMinor ?: 0) > 0 && merchantText.trim().isNotEmpty()
}

@HiltViewModel
class AddManualViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddManualUiState())
    val state: StateFlow<AddManualUiState> = _state.asStateFlow()

    /** Loads the category list and initializes date/time to "now" in KL. */
    fun load() {
        viewModelScope.launch {
            val now = Clock.System.now().toLocalDateTime(MalaysiaTimeZone)
            _state.update {
                it.copy(
                    date = now.date,
                    time = LocalTime(now.hour, now.minute),
                    categories = repository.observeAllCategories().first(),
                )
            }
        }
    }

    fun setAmount(text: String) {
        // Accept only digits and at most one decimal point. Cap at 2 decimal places.
        val sanitized = text.filter { it.isDigit() || it == '.' }
        val parts = sanitized.split('.')
        val cleaned = when {
            parts.size <= 1 -> sanitized
            parts.size == 2 -> parts[0] + "." + parts[1].take(2)
            else -> parts[0] + "." + parts.drop(1).joinToString("").take(2)
        }
        _state.update { it.copy(amountText = cleaned) }
    }

    fun setMerchant(text: String) = _state.update { it.copy(merchantText = text) }
    fun setCategoryId(id: Long?) = _state.update { it.copy(categoryId = id) }
    fun setDescription(text: String) = _state.update { it.copy(descriptionText = text) }
    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }
    fun setTime(time: LocalTime) = _state.update { it.copy(time = time) }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        val amount = s.amountMinor ?: return
        val occurredAt: Instant = LocalDateTime(s.date, s.time).toInstant(MalaysiaTimeZone)

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            repository.addManualTransaction(
                amountMinor = amount,
                merchantRaw = s.merchantText.trim(),
                categoryId = s.categoryId,
                description = s.descriptionText.takeIf { it.isNotBlank() },
                occurredAt = occurredAt,
            )
            _state.update { it.copy(isSaving = false) }
            onSaved()
        }
    }
}

/** "12.50" → 1250. Returns null on invalid input. Assumes input has at most 2 decimal places. */
internal fun parseAmountMinor(text: String): Long? {
    if (text.isBlank()) return null
    val cleaned = text.trim()
    if (!Regex("""^\d+(\.\d{1,2})?$""").matches(cleaned)) return null
    val parts = cleaned.split('.')
    val ringgit = parts[0].toLongOrNull() ?: return null
    val cents = if (parts.size == 2) parts[1].padEnd(2, '0').toIntOrNull() ?: return null else 0
    return ringgit * 100 + cents
}
