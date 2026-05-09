package cy.txtracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.CategoryTotal
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.MalaysiaTimeZone
import cy.txtracker.domain.YearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.toLocalDateTime

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    private val _yearMonth = MutableStateFlow(YearMonth.current())
    private val _filter = MutableStateFlow<HomeFilter>(HomeFilter.All)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HomeUiState> =
        combine(_yearMonth, _filter, repository.observeAllCategories()) { ym, filter, cats ->
            Triple(ym, filter, cats)
        }.flatMapLatest { (ym, filter, categories) ->
            val start = ym.start()
            val end = ym.endExclusive()
            combine(
                repository.observeTransactionsBetween(start, end),
                repository.observeCategoryTotalsBetween(start, end),
                repository.observeTotalBetween(start, end),
            ) { txs, totals, total ->
                buildState(
                    yearMonth = ym,
                    filter = filter,
                    categories = categories,
                    transactions = txs,
                    totals = totals,
                    monthTotal = total,
                )
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

    private fun empty(): HomeUiState = HomeUiState(
        yearMonth = _yearMonth.value,
        filter = _filter.value,
        totalMinor = 0L,
        breakdown = emptyList(),
        categories = emptyList(),
        days = emptyList(),
        pendingCount = 0,
        isLoading = true,
    )

    private fun buildState(
        yearMonth: YearMonth,
        filter: HomeFilter,
        categories: List<Category>,
        transactions: List<Transaction>,
        totals: List<CategoryTotal>,
        monthTotal: Long,
    ): HomeUiState {
        val byId = categories.associateBy { it.id }
        val joined = transactions.map { TransactionWithCategory(it, it.categoryId?.let(byId::get)) }
        val filtered = when (filter) {
            HomeFilter.All -> joined
            HomeFilter.Unverified -> joined.filter { it.transaction.categoryId == null }
            HomeFilter.Pending -> joined.filter { it.transaction.needsVerification }
            is HomeFilter.Category -> joined.filter { it.transaction.categoryId == filter.id }
        }
        val days = filtered
            .groupBy { it.transaction.occurredAt.toLocalDateTime(MalaysiaTimeZone).date }
            .toSortedMap(reverseOrder())
            .map { (date, list) -> DayGroup(date, list) }

        val breakdown = totals
            .map { CategoryBreakdownEntry(category = it.categoryId?.let(byId::get), totalMinor = it.totalMinor) }
            .sortedWith(
                compareByDescending<CategoryBreakdownEntry> { it.category != null } // categorized first
                    .thenBy { it.category?.sortOrder ?: Int.MAX_VALUE },
            )

        return HomeUiState(
            yearMonth = yearMonth,
            filter = filter,
            totalMinor = monthTotal,
            breakdown = breakdown,
            categories = categories,
            days = days,
            pendingCount = transactions.count { it.needsVerification },
            isLoading = false,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
