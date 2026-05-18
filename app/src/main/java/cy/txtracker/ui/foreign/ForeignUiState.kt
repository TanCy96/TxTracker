package cy.txtracker.ui.foreign

import cy.txtracker.data.Category
import cy.txtracker.ui.home.CategoryBreakdownEntry
import cy.txtracker.ui.home.DayGroup
import kotlinx.datetime.Instant

/**
 * Filter chips on the Foreign tab. Same shape as Home minus `CurrencyReview` — rows
 * with `needsCurrencyConfirmation = 1` live on the Home tab's currency-review filter
 * and never surface inside a trip view.
 */
sealed interface ForeignFilter {
    data object All : ForeignFilter
    data object Unverified : ForeignFilter
    data object Pending : ForeignFilter
    data class Category(val id: Long) : ForeignFilter
}

/** Identity of the trip currently in view: lets the UI render the header + chevron state. */
data class TripDescriptor(
    val tripId: Long,
    val currency: String,
    val displaySymbol: String,
    val startAt: Instant,
    /** `null` = open-ended (header renders the end side as "Today"). */
    val endAt: Instant?,
)

sealed interface ForeignUiState {
    data object Loading : ForeignUiState

    /** No trips at all — the user hasn't opened one yet. */
    data object NoTrips : ForeignUiState

    data class Loaded(
        val trip: TripDescriptor,
        val tripIndex: Int,   // 0-based, into the sorted trip list
        val tripCount: Int,   // total trips known
        val filter: ForeignFilter,
        val totalMinor: Long,
        val transactionCount: Int,
        val breakdown: List<CategoryBreakdownEntry>,
        val categories: List<Category>,
        val days: List<DayGroup>,
        /** merchantNormalized -> user note. Used by the row to show context inline. */
        val notesByMerchant: Map<String, String>,
        val pendingCount: Int,
        val isLoading: Boolean = false,
    ) : ForeignUiState
}
