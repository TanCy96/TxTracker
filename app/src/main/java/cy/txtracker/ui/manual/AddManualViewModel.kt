package cy.txtracker.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.FundingSource
import cy.txtracker.data.FundingSourceDao
import cy.txtracker.data.FundingSourceKind
import cy.txtracker.data.TrackedCurrency
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.service.FeatureFlags
import cy.txtracker.domain.isValidShareMinor
import cy.txtracker.domain.slDebitDefaultShareMinor
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

/** A reimbursement the user added before the transaction exists. Persisted after insert. */
data class DraftReimbursement(
    val amountMinor: Long,
    val destinationKind: FundingSourceKind,
    val personLabel: String?,
)

data class AddManualUiState(
    val amountText: String = "",
    val merchantText: String = "",
    val categoryId: Long? = null,
    val descriptionText: String = "",
    val date: LocalDate = LocalDate(2000, 1, 1),
    val time: LocalTime = LocalTime(0, 0),
    val categories: List<Category> = emptyList(),
    val currency: String = "MYR",
    val trackedCurrencies: List<TrackedCurrency> = emptyList(),
    val isSaving: Boolean = false,
    val availableFundingSources: List<FundingSource> = emptyList(),
    /** Pre-selected to the seeded Cash row on first load; user can change via the picker. */
    val fundingSource: FundingSource? = null,
    /** SL Debit default % (from the account); used to prefill the share when toggled on. */
    val slDefaultPercent: Int = 40,
    /** Non-null when the user has enabled "Share with SL Debit"; the share in minor units. */
    val slShareMinor: Long? = null,
    val slShareText: String = "",
    /** When false, the SL Debit share control is hidden (feature gated/locked). */
    val slDebitUnlocked: Boolean = false,
    val reimbursements: List<DraftReimbursement> = emptyList(),
) {
    val amountMinor: Long? get() = parseAmountMinor(amountText)
    /** Cached reimbursed total for the row, or null when none. Validated at add-time. */
    val reimbursedMinor: Long?
        get() = reimbursements.sumOf { it.amountMinor }.takeIf { it > 0 }
    val canSave: Boolean
        get() = !isSaving && (amountMinor ?: 0) > 0 && merchantText.trim().isNotEmpty()
}

@HiltViewModel
class AddManualViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val fundingSourceDao: FundingSourceDao,
    private val featureFlags: FeatureFlags,
) : ViewModel() {

    private val _state = MutableStateFlow(AddManualUiState())
    val state: StateFlow<AddManualUiState> = _state.asStateFlow()

    /**
     * Resets all input fields to defaults and initializes date/time to "now" in KL.
     * Called every time the sheet opens — without the full reset, the previous entry's
     * text fields and category selection would persist (Hilt scopes the ViewModel to the
     * parent NavGraph, so the same instance is reused across sheet openings).
     *
     * Also refreshes the funding-source list and pre-selects the seeded Cash row so
     * manual entries are linked to a source out of the box.
     *
     * @param initialCurrency optional pre-selected currency code (the Foreign tab uses
     *   this to anchor manual entries to the currently-viewed trip). Falls back to MYR.
     * @param initialOccurredAt optional pre-selected timestamp. Foreign uses this to
     *   default into a past trip's window so the new row auto-promotes into that trip.
     *   Falls back to "now" in KL.
     */
    fun load(initialCurrency: String? = null, initialOccurredAt: Instant? = null) {
        viewModelScope.launch {
            val zone = MalaysiaTimeZone
            val anchor = (initialOccurredAt ?: Clock.System.now()).toLocalDateTime(zone)
            val currency = initialCurrency ?: "MYR"
            val occurredAt = LocalDateTime(anchor.date, LocalTime(anchor.hour, anchor.minute))
                .toInstant(MalaysiaTimeZone)
            val categories = resolveCategoriesForCurrency(currency, occurredAt)
            val trackedCurrencies = repository.observeTrackedCurrencies().first()
            val sources = fundingSourceDao.getAll()
            val defaultCash = fundingSourceDao.getDefaultCash()
            val slAccount = repository.getSlDebitAccount()
            _state.value = AddManualUiState(
                date = anchor.date,
                time = LocalTime(anchor.hour, anchor.minute),
                categories = categories,
                currency = currency,
                trackedCurrencies = trackedCurrencies,
                availableFundingSources = sources,
                fundingSource = defaultCash,
                slDefaultPercent = slAccount?.defaultSharePercent ?: 40,
                slDebitUnlocked = featureFlags.slDebitUnlocked.value,
            )
        }
    }

    /**
     * Returns the category list appropriate for [currency] at [occurredAt]:
     * - MYR → global categories only
     * - non-MYR with a covering trip → that trip's categories
     * - non-MYR without a covering trip → empty list
     *
     * This is the source of truth for the picker; [observeAllCategories] is never used here
     * so a trip category can never appear in an MYR picker or vice-versa.
     */
    private suspend fun resolveCategoriesForCurrency(
        currency: String,
        occurredAt: Instant,
    ): List<Category> = if (currency == "MYR") {
        repository.observeGlobalCategories().first()
    } else {
        val trip = repository.findActiveTrip(currency, occurredAt)
        if (trip != null) repository.observeCategoriesForTrip(trip.id).first() else emptyList()
    }

    /**
     * Updates the selected funding source. Pass null to clear the selection.
     * Resolves the new selection from [AddManualUiState.availableFundingSources] by id
     * so the state always holds a fully-populated object (or null).
     */
    fun setFundingSource(id: Long?) {
        _state.update { s ->
            s.copy(fundingSource = id?.let { s.availableFundingSources.find { src -> src.id == it } })
        }
    }

    /**
     * Updates the selected currency and re-scopes the category picker to match.
     * For MYR → global categories; for non-MYR → covering trip's categories (or empty).
     * If the currently-selected category is not present in the new scope it is cleared,
     * so a trip category can never be silently attached to an MYR transaction.
     */
    fun setCurrency(currency: String) {
        viewModelScope.launch {
            val s = _state.value
            val occurredAt = LocalDateTime(s.date, s.time).toInstant(MalaysiaTimeZone)
            val newCategories = resolveCategoriesForCurrency(currency, occurredAt)
            val newCategoryId = s.categoryId?.takeIf { id -> newCategories.any { it.id == id } }
            _state.update { it.copy(currency = currency, categories = newCategories, categoryId = newCategoryId) }
        }
    }

    fun addCurrency(code: String) {
        viewModelScope.launch {
            repository.ensureTrackedCurrency(code)
            val refreshed = repository.observeTrackedCurrencies().first()
            _state.update { it.copy(trackedCurrencies = refreshed) }
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
        _state.update { s ->
            val newAmount = parseAmountMinor(cleaned) ?: 0L
            s.copy(
                amountText = cleaned,
                slShareMinor = s.slShareMinor?.takeIf { isValidShareMinor(it, newAmount) },
            )
        }
    }

    /** Toggles the SL Debit share on/off. On enable, prefills the default % of the current amount. */
    fun setShareEnabled(enabled: Boolean) {
        _state.update { s ->
            if (!enabled) {
                s.copy(slShareMinor = null, slShareText = "")
            } else {
                val amount = s.amountMinor ?: 0
                val def = slDebitDefaultShareMinor(amount, s.slDefaultPercent)
                s.copy(slShareMinor = def.takeIf { it > 0 }, slShareText = if (def > 0) formatShare(def) else "")
            }
        }
    }

    fun setShareText(text: String) {
        val sanitized = text.filter { it.isDigit() || it == '.' }
        _state.update { s ->
            val parsed = parseAmountMinor(sanitized)
            val amount = s.amountMinor ?: 0
            s.copy(
                slShareText = sanitized,
                slShareMinor = parsed?.takeIf { isValidShareMinor(it, amount) },
            )
        }
    }

    fun setMerchant(text: String) = _state.update { it.copy(merchantText = text) }
    fun setCategoryId(id: Long?) = _state.update { it.copy(categoryId = id) }
    fun setDescription(text: String) = _state.update { it.copy(descriptionText = text) }

    fun addReimbursement(amountMinor: Long, destinationKind: FundingSourceKind, personLabel: String?) {
        _state.update {
            it.copy(reimbursements = it.reimbursements + DraftReimbursement(amountMinor, destinationKind, personLabel?.trim()?.takeIf { p -> p.isNotEmpty() }))
        }
    }

    fun removeReimbursement(index: Int) {
        _state.update { it.copy(reimbursements = it.reimbursements.filterIndexed { i, _ -> i != index }) }
    }

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }
    fun setTime(time: LocalTime) = _state.update { it.copy(time = time) }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        val amount = s.amountMinor ?: return
        val occurredAt: Instant = LocalDateTime(s.date, s.time).toInstant(MalaysiaTimeZone)

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val newId = repository.addManualTransaction(
                amountMinor = amount,
                merchantRaw = s.merchantText.trim(),
                categoryId = s.categoryId,
                description = s.descriptionText.takeIf { it.isNotBlank() },
                occurredAt = occurredAt,
                currency = s.currency,
                fundingSourceId = s.fundingSource?.id,
                reimbursedMinor = s.reimbursedMinor,
            )
            val share = s.slShareMinor
            if (newId != null && s.currency == "MYR" && share != null && isValidShareMinor(share, amount)) {
                repository.setTransactionShare(newId, share)
            }
            if (newId != null) {
                s.reimbursements.forEach { d ->
                    repository.addReimbursementEntry(newId, d.amountMinor, d.destinationKind, d.personLabel)
                }
            }
            _state.update { it.copy(isSaving = false) }
            onSaved()
        }
    }
}

/** "1250" minor -> "12.50" plain string (no currency prefix), for the share text field. */
private fun formatShare(amountMinor: Long): String =
    "${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"

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
