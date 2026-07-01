package cy.txtracker.ui.settings.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.Category
import cy.txtracker.data.MerchantMapping
import cy.txtracker.data.Transaction
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.Instant.Companion.DISTANT_FUTURE

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val categories: StateFlow<List<Category>> = repository.observeGlobalCategories()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    // 30-day window captured once at VM construction; refreshing on screen reopen is
    // sufficient for the "auto: N" display chip.
    private val recentSince: Instant = Clock.System.now() - 30.days

    private val merchantMappings: Flow<List<MerchantMapping>> =
        repository.observeMerchantMappings()

    private val recentTransactions: Flow<List<Transaction>> =
        repository.observeTransactionsBetween(recentSince, DISTANT_FUTURE)

    /**
     * Per-category display counts for the categories list chip:
     * - `learned` = merchant→category mappings pointing at this category.
     * - `auto` = distinct merchant strings in the last 30 days whose name matches the
     *   category's `keywordPattern`. 0 when no pattern.
     */
    val categoryCounts: StateFlow<Map<Long, CategoryCounts>> = combine(
        categories,
        merchantMappings,
        recentTransactions,
    ) { cats, mappings, recentTxs ->
        val distinctRecentMerchants by lazy {
            recentTxs.asSequence().map { it.merchantNormalized }.distinct().toList()
        }
        cats.associate { c ->
            val learned = mappings.count { it.categoryId == c.id }
            val pattern = c.keywordPattern
            val auto = if (pattern.isNullOrBlank()) {
                0
            } else {
                val regex =
                    runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                if (regex == null) 0
                else distinctRecentMerchants.count { regex.containsMatchIn(it) }
            }
            c.id to CategoryCounts(learned = learned, auto = auto)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyMap(),
    )

    fun add(name: String, color: Int, keywordPattern: String? = null) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val cleanPattern = keywordPattern?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            runCatching {
                repository.addCategoryInScope(
                    name = cleanName,
                    color = color,
                    keywordPattern = cleanPattern,
                    tripId = null,
                )
            }
        }
    }

    /** Updates name, color, and keyword pattern in one go. Empty pattern persists as null. */
    fun editCategory(
        original: Category,
        newName: String,
        newColor: Int,
        newKeywordPattern: String?,
    ) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return
        val cleanPattern = newKeywordPattern?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            runCatching {
                repository.renameCategoryInScope(
                    original = original,
                    newName = cleanName,
                    newColor = newColor,
                    newKeywordPattern = cleanPattern,
                )
            }
        }
    }

    fun delete(category: Category) {
        viewModelScope.launch { repository.deleteCategory(category) }
    }

    /**
     * Persists a user-specified ordering. Called from the categories screen on drag-end with
     * the visible list already in the new order; the repository assigns dense `sortOrder`
     * values so subsequent reorders stay clean.
     */
    fun reorder(orderedCategories: List<Category>) {
        viewModelScope.launch { repository.reorderCategories(orderedCategories) }
    }

    data class CategoryCounts(val learned: Int, val auto: Int)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
