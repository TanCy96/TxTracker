package cy.txtracker.ui.settings.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.CapturedNotification
import cy.txtracker.data.Category
import cy.txtracker.data.PoolFilter
import cy.txtracker.data.PromoteEdit
import cy.txtracker.data.TransactionRepository
import cy.txtracker.domain.MalaysiaTimeZone
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
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

data class PoolUiState(
    val filter: PoolFilter = PoolFilter.PENDING,
    val packageName: String? = null,
    val rows: List<PoolDayGroup> = emptyList(),
    val customLabels: Map<String, String> = emptyMap(),
) {
    fun labelFor(packageName: String): String =
        customLabels[packageName] ?: cy.txtracker.parsing.SourceLabels.label(packageName)
}

data class PoolDayGroup(
    val date: LocalDate,
    val rows: List<CapturedNotification>,
)

@HiltViewModel
class PoolViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(PoolFilter.PENDING)
    private val packageName = MutableStateFlow<String?>(null)

    val categories: StateFlow<List<Category>> =
        repository.observeGlobalCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<PoolUiState> =
        combine(filter, packageName) { f, pkg -> f to pkg }
            .flatMapLatest { (f, pkg) ->
                combine(
                    repository.observePool(f, pkg),
                    repository.observeCustomLabels(),
                ) { rows, labels ->
                    PoolUiState(
                        filter = f,
                        packageName = pkg,
                        rows = rows
                            .groupBy { it.postedAt.toLocalDateTime(MalaysiaTimeZone).date }
                            .toSortedMap(reverseOrder())
                            .map { (date, list) -> PoolDayGroup(date, list) },
                        customLabels = labels,
                    )
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000L),
                PoolUiState(),
            )

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds

    fun enterSelection(id: Long) {
        _selectionMode.value = true
        _selectedIds.value = setOf(id)
    }

    fun toggleSelect(id: Long) {
        val updated = if (id in _selectedIds.value) _selectedIds.value - id else _selectedIds.value + id
        _selectedIds.value = updated
        if (updated.isEmpty()) _selectionMode.value = false
    }

    fun clearSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun approveSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) { clearSelection(); return }
        viewModelScope.launch { repository.promotePoolEntries(ids) }
        clearSelection()
    }

    fun rejectSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) { clearSelection(); return }
        viewModelScope.launch { repository.markPoolEntriesNoise(ids) }
        clearSelection()
    }

    fun setFilter(value: PoolFilter) {
        filter.value = value
    }

    fun setPackageName(value: String?) {
        packageName.update { value }
    }

    fun markNoise(id: Long) {
        viewModelScope.launch { repository.markPoolEntryNoise(id) }
    }

    fun rejectPackage(packageName: String) {
        viewModelScope.launch { repository.rejectPackage(packageName) }
    }

    fun promote(id: Long, edit: PromoteEdit, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            onDone(repository.promotePoolEntry(id, edit) != null)
        }
    }
}
