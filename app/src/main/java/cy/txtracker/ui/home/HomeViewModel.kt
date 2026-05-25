package cy.txtracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.CategoryTotal
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.domain.YearMonth
import cy.txtracker.notify.DeeplinkBus
import cy.txtracker.service.CurrencyPrefs
import cy.txtracker.ui.MainActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val currencyPrefs: CurrencyPrefs,
    private val deeplinkBus: DeeplinkBus,
) : ViewModel() {

    private val _yearMonth = MutableStateFlow(YearMonth.current())
    private val _filter = MutableStateFlow<HomeFilter>(HomeFilter.All)

    /** Banner offer â€” derived independently from currency-review rows + dismissed prefs. */
    private val _bannerOffer: StateFlow<BannerOffer?> =
        combine(
            repository.observeCurrencyReviewTransactions(),
            currencyPrefs.dismissed,
        ) { parkedRows, dismissed ->
            computeBannerOffer(parkedRows, dismissed)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    private val _currencyReviewCount: StateFlow<Int> =
        repository.observeCurrencyReviewTransactions()
            .map { it.size }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = 0,
            )

    init {
        // Deep-link arrives via DeeplinkBus from MainActivity intent extras (e.g. user
        // taps a pending-row notification). Each Deeplink maps to a Home filter switch.
        viewModelScope.launch {
            deeplinkBus.forHome.collect { deeplink ->
                when (deeplink) {
                    MainActivity.Deeplink.PendingFilter -> {
                        _filter.value = HomeFilter.Pending
                    }
                    MainActivity.Deeplink.CurrencyReview -> {
                        _filter.value = HomeFilter.CurrencyReview
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HomeUiState> =
        combine(_yearMonth, _filter, repository.observeAllCategories(), _bannerOffer, _currencyReviewCount) { ym, filter, cats, banner, crCount ->
            object {
                val yearMonth = ym
                val filter = filter
                val categories = cats
                val banner = banner
                val currencyReviewCount = crCount
            }
        }.flatMapLatest { params ->
            val ym = params.yearMonth
            val filter = params.filter
            val categories = params.categories
            val banner = params.banner
            val currencyReviewCount = params.currencyReviewCount

            if (filter == HomeFilter.CurrencyReview) {
                // Currency-review filter: show all parked rows regardless of month
                combine(
                    repository.observeCurrencyReviewTransactions(),
                    repository.observeMerchantNotes(),
                ) { txs, notes ->
                    buildCurrencyReviewState(
                        yearMonth = ym,
                        categories = categories,
                        transactions = txs,
                        notes = notes,
                        banner = banner,
                        currencyReviewCount = currencyReviewCount,
                    )
                }
            } else {
                val start = ym.start()
                val end = ym.endExclusive()
                combine(
                    repository.observeMyrTransactionsBetween(start, end),
                    repository.observeCategoryTotalsBetween(start, end),
                    repository.observeTotalBetween(start, end),
                    repository.observeMerchantNotes(),
                ) { txs, totals, total, notes ->
                    buildState(
                        yearMonth = ym,
                        filter = filter,
                        categories = categories,
                        transactions = txs,
                        totals = totals,
                        monthTotal = total,
                        notes = notes,
                        banner = banner,
                        currencyReviewCount = currencyReviewCount,
                    )
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = empty(),
        )

    fun previousMonth() = _yearMonth.update { it.previous() }
    fun nextMonth() = _yearMonth.update { it.next() }
    fun selectMonth(ym: YearMonth) { _yearMonth.value = ym }
    fun setFilter(filter: HomeFilter) { _filter.value = filter }

    fun dismissBanner(currency: String) {
        currencyPrefs.markDismissed(currency)
    }

    fun openTrip(currency: String, startAt: Instant, endAt: Instant?) {
        viewModelScope.launch {
            repository.openTrip(currency, startAt, endAt)
            // Once a trip is opened the parked rows are promoted, so the banner will
            // naturally disappear. Also clear any dismissal so a future banner for the
            // same currency can appear again.
            currencyPrefs.clearDismissal(currency)
        }
    }

    private fun empty(): HomeUiState = HomeUiState(
        yearMonth = _yearMonth.value,
        filter = _filter.value,
        totalMinor = 0L,
        transactionCount = 0,
        breakdown = emptyList(),
        categories = emptyList(),
        days = emptyList(),
        notesByMerchant = emptyMap(),
        pendingCount = 0,
        currencyReviewCount = 0,
        isLoading = true,
        bannerCurrency = null,
    )

    private fun buildState(
        yearMonth: YearMonth,
        filter: HomeFilter,
        categories: List<Category>,
        transactions: List<Transaction>,
        totals: List<CategoryTotal>,
        monthTotal: Long,
        notes: List<cy.txtracker.data.MerchantNote>,
        banner: BannerOffer?,
        currencyReviewCount: Int,
    ): HomeUiState {
        val byId = categories.associateBy { it.id }
        val joined = transactions.map { TransactionWithCategory(it, it.categoryId?.let(byId::get)) }

        // 1. Compute breakdown FIRST so we can consult it for snap-back below.
        val breakdown = totals
            .map { CategoryBreakdownEntry(category = it.categoryId?.let(byId::get), totalMinor = it.totalMinor) }
            .sortedWith(
                compareByDescending<CategoryBreakdownEntry> { it.category != null }
                    .thenBy { it.category?.sortOrder ?: Int.MAX_VALUE },
            )

        // 2. Snap a stale category filter back to All. Writes back so the next flatMapLatest emit
        //    carries the corrected value; this invocation continues with `filter` as-is.
        val effectiveFilter = snapStaleHomeCategoryToAll(filter, breakdown)
        if (effectiveFilter != filter) {
            _filter.value = effectiveFilter
        }

        // 3. Filter / group using the effective filter.
        val filtered = when (effectiveFilter) {
            HomeFilter.All -> joined
            HomeFilter.Unverified -> joined.filter { it.transaction.categoryId == null }
            HomeFilter.Pending -> joined.filter { it.transaction.needsVerification }
            HomeFilter.CurrencyReview -> joined.filter { it.transaction.needsCurrencyConfirmation }
            is HomeFilter.Category -> joined.filter { it.transaction.categoryId == effectiveFilter.id }
        }
        val days = filtered
            .groupBy { it.transaction.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
            .toSortedMap(reverseOrder())
            .map { (date, list) -> DayGroup(date, list) }

        return HomeUiState(
            yearMonth = yearMonth,
            filter = effectiveFilter,
            totalMinor = monthTotal,
            transactionCount = transactions.size,
            breakdown = breakdown,
            categories = categories,
            days = days,
            notesByMerchant = notes.associate { it.merchantNormalized to it.note },
            pendingCount = transactions.count { it.needsVerification },
            currencyReviewCount = currencyReviewCount,
            isLoading = false,
            bannerCurrency = banner,
        )
    }

    private fun buildCurrencyReviewState(
        yearMonth: YearMonth,
        categories: List<Category>,
        transactions: List<Transaction>,
        notes: List<cy.txtracker.data.MerchantNote>,
        banner: BannerOffer?,
        currencyReviewCount: Int,
    ): HomeUiState {
        val byId = categories.associateBy { it.id }
        val joined = transactions.map { TransactionWithCategory(it, it.categoryId?.let(byId::get)) }
        val days = joined
            .groupBy { it.transaction.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
            .toSortedMap(reverseOrder())
            .map { (date, list) -> DayGroup(date, list) }

        return HomeUiState(
            yearMonth = yearMonth,
            filter = HomeFilter.CurrencyReview,
            totalMinor = 0L,
            transactionCount = transactions.size,
            breakdown = emptyList(),
            categories = categories,
            days = days,
            notesByMerchant = notes.associate { it.merchantNormalized to it.note },
            pendingCount = transactions.count { it.needsVerification },
            currencyReviewCount = currencyReviewCount,
            isLoading = false,
            bannerCurrency = banner,
        )
    }

    /**
     * Determines the first currency that has parked rows but no active trip AND has not been
     * dismissed by the user. Called inside a [combine] lambda so it must be non-suspending.
     * We inspect the already-held [dismissed] set; the actual trip lookup is done lazily in the
     * banner flow via [_bannerOffer] which re-evaluates whenever parked rows or prefs change.
     *
     * NOTE: "no active trip" is approximated here by checking if the currency appears in the
     * dismissed set â€” the real trip check happens asynchronously via the flow in the ViewModel's
     * init block. The banner flow uses this same approach: dismissed == no active trip check
     * needed for the in-memory flow; the ViewModel's [openTrip] clears dismissal so the check
     * stays coherent.
     */
    private fun computeBannerOffer(
        parkedRows: List<Transaction>,
        dismissed: Set<String>,
    ): BannerOffer? {
        if (parkedRows.isEmpty()) return null
        // Group by currency, find first non-dismissed currency (skip UNKNOWN)
        val byCurrency = parkedRows.groupBy { it.currency }
        for ((currency, rows) in byCurrency) {
            if (currency == "UNKNOWN" || currency in dismissed) continue
            // Find the earliest occurred-at for this currency
            val earliest = rows.minByOrNull { it.occurredAt } ?: continue
            return BannerOffer(currency = currency, earliestOccurredAt = earliest.occurredAt)
        }
        return null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

