package cy.txtracker.ui.home

import cy.txtracker.data.Category
import cy.txtracker.data.Transaction
import cy.txtracker.domain.YearMonth
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

sealed interface HomeFilter {
    data object All : HomeFilter
    data object Unverified : HomeFilter
    /** Heuristic-captured transactions awaiting user confirm/delete. */
    data object Pending : HomeFilter
    /** Transactions parked for currency confirmation (needsCurrencyConfirmation = 1). */
    data object CurrencyReview : HomeFilter
    data class Category(val id: Long) : HomeFilter
}

/**
 * Describes a banner offer to start a trip for [currency].
 * [earliestOccurredAt] is the oldest parked transaction's timestamp, used as
 * the default trip start when the user taps "Start".
 */
data class BannerOffer(
    val currency: String,
    val earliestOccurredAt: Instant,
)

/** Display projection of one transaction joined with its category (null if uncategorized). */
data class TransactionWithCategory(
    val transaction: Transaction,
    val category: Category?,
)

/** A day's worth of transactions (already sorted DESC by occurredAt within the day). */
data class DayGroup(
    val date: LocalDate,
    val transactions: List<TransactionWithCategory>,
)

/** Entry in the per-category breakdown row at the top of the home screen. */
data class CategoryBreakdownEntry(
    val category: Category?,  // null = "Unverified" bucket
    val totalMinor: Long,
)

data class HomeUiState(
    val yearMonth: YearMonth,
    val filter: HomeFilter,
    val totalMinor: Long,
    val transactionCount: Int,
    val breakdown: List<CategoryBreakdownEntry>,
    val categories: List<Category>,
    val days: List<DayGroup>,
    /** merchantNormalized -> user note. Used by the home row to show context inline. */
    val notesByMerchant: Map<String, String>,
    val pendingCount: Int,
    /** Count of MYR transactions awaiting currency confirmation. Drives the visibility of the
     *  Currency-review filter chip in the breakdown header — chip hides when count is 0. */
    val currencyReviewCount: Int,
    val isLoading: Boolean,
    /** Non-null when there are parked currency rows for a currency with no active trip and the
     *  banner has not been dismissed by the user. */
    val bannerCurrency: BannerOffer? = null,
)
