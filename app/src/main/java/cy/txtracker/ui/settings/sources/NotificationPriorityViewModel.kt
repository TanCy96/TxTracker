package cy.txtracker.ui.settings.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.data.MANUAL_SOURCE_APP
import cy.txtracker.data.TransactionRepository
import cy.txtracker.parsing.GrabParser
import cy.txtracker.parsing.SourceTierResolver
import cy.txtracker.parsing.TouchNGoParser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Models the Notification Priority screen: the read-only built-in row list, the
 * user-editable row list, and the list of candidate packages the user could promote
 * (any package that has produced a transaction and isn't already Tier 1).
 */
data class NotificationPriorityUiState(
    val builtIn: List<PriorityRow> = emptyList(),
    val userAdded: List<PriorityRow> = emptyList(),
    val candidates: List<PriorityRow> = emptyList(),
)

data class PriorityRow(
    val packageName: String,
    val displayLabel: String,
)

@HiltViewModel
class NotificationPriorityViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val state: StateFlow<NotificationPriorityUiState> =
        combine(
            repository.observeUserFacingSources(),
            repository.observeAllSourcePackages(),
        ) { userAdded, allSeenPackages ->
            val userAddedSet = userAdded.map { it.packageName }.toSet()
            val builtInRows = SourceTierResolver.BUILTIN_USER_FACING_PACKAGES
                .map { PriorityRow(it, displayLabelFor(it)) }
                .sortedBy { it.displayLabel.lowercase() }
            val userRows = userAdded
                .map { PriorityRow(it.packageName, displayLabelFor(it.packageName)) }
                .sortedBy { it.displayLabel.lowercase() }
            val candidates = allSeenPackages
                .filter {
                    it != MANUAL_SOURCE_APP &&
                        it !in SourceTierResolver.BUILTIN_USER_FACING_PACKAGES &&
                        it !in userAddedSet
                }
                .map { PriorityRow(it, displayLabelFor(it)) }
                .sortedBy { it.displayLabel.lowercase() }
            NotificationPriorityUiState(
                builtIn = builtInRows,
                userAdded = userRows,
                candidates = candidates,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = NotificationPriorityUiState(),
        )

    fun add(packageName: String) {
        viewModelScope.launch { repository.addUserFacingSource(packageName) }
    }

    fun remove(packageName: String) {
        viewModelScope.launch { repository.removeUserFacingSource(packageName) }
    }

    private fun displayLabelFor(pkg: String): String = when (pkg) {
        GrabParser.GRAB_PACKAGE -> "Grab"
        TouchNGoParser.TNG_PACKAGE -> "Touch 'n Go eWallet"
        else -> pkg
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
