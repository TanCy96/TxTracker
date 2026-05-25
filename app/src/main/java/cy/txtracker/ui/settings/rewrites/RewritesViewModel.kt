package cy.txtracker.ui.settings.rewrites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.PackageTextRewrite
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RewritesUiState(
    /** Grouped by packageName, preserving the DAO's per-package learnedAt-ASC order. */
    val byPackage: Map<String, List<PackageTextRewrite>> = emptyMap(),
)

@HiltViewModel
class RewritesViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val state: StateFlow<RewritesUiState> = repository.observeRewrites()
        .map { rows -> RewritesUiState(byPackage = rows.groupBy { it.packageName }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RewritesUiState())

    fun delete(packageName: String, pattern: String) {
        viewModelScope.launch { repository.deleteRewrite(packageName, pattern) }
    }
}
