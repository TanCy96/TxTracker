package cy.txtracker.ui.settings.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.PackageStatus
import cy.txtracker.data.TrackedPackageRow
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TrackedAppsUiState(
    val tracked: List<TrackedPackageRow> = emptyList(),
    val rejected: List<TrackedPackageRow> = emptyList(),
    val untracked: List<TrackedPackageRow> = emptyList(),
)

@HiltViewModel
class TrackedAppsViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val state: StateFlow<TrackedAppsUiState> =
        repository.observeTrackedPackages()
            .map { rows ->
                TrackedAppsUiState(
                    tracked = rows.filter { it.status == PackageStatus.TRACKED },
                    rejected = rows.filter { it.status == PackageStatus.REJECTED },
                    untracked = rows.filter { it.status == PackageStatus.UNTRACKED },
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000L),
                TrackedAppsUiState(),
            )

    fun track(packageName: String) {
        viewModelScope.launch { repository.trackPackage(packageName) }
    }

    fun reject(packageName: String) {
        viewModelScope.launch { repository.rejectPackage(packageName) }
    }

    fun rename(packageName: String, label: String) {
        viewModelScope.launch { repository.renameTrackedApp(packageName, label) }
    }
}
