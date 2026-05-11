package cy.txtracker.ui.onboarding

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.cloud.DriveClient
import cy.txtracker.cloud.GoogleSignInStateProvider
import cy.txtracker.export.BackupExporter
import cy.txtracker.export.BackupImporter
import cy.txtracker.export.ImportResult
import cy.txtracker.service.CloudSyncPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the onboarding flow's cloud-restore step. Mirrors the cloud-sync portions of
 * [cy.txtracker.ui.settings.SettingsViewModel] so the user can sign in and trigger the
 * fresh-install restore prompt without leaving onboarding.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val cloudSyncPrefs: CloudSyncPrefs,
    private val driveClient: DriveClient,
    private val backupExporter: BackupExporter,
    private val backupImporter: BackupImporter,
    private val signInState: GoogleSignInStateProvider,
) : ViewModel() {

    private val _cloudSyncStatus = MutableStateFlow<CloudSyncStatus>(CloudSyncStatus.Idle)
    val cloudSyncStatus: StateFlow<CloudSyncStatus> = _cloudSyncStatus.asStateFlow()

    fun signInIntent(): Intent = signInState.signInClient.signInIntent

    fun completeSignIn(email: String?) {
        cloudSyncPrefs.setEnabled(true)
        cloudSyncPrefs.setAccountEmail(email)
        _cloudSyncStatus.value = CloudSyncStatus.CheckingForBackup
        viewModelScope.launch {
            val exists = driveClient.exists().getOrNull() ?: false
            val isFreshInstall = backupImporter.isLocalDataEmpty()
            _cloudSyncStatus.value = if (exists && isFreshInstall) {
                CloudSyncStatus.RestorePrompt
            } else {
                // Already have local data, or no cloud backup — nothing to prompt about.
                CloudSyncStatus.Done
            }
        }
    }

    fun signInFailed(message: String) {
        _cloudSyncStatus.value = CloudSyncStatus.Error(message)
    }

    fun restoreFromCloud() {
        _cloudSyncStatus.value = CloudSyncStatus.Restoring
        viewModelScope.launch {
            try {
                backupExporter.saveLocalRollbackSnapshot("pre-cloud-restore")
                val json = driveClient.download().getOrThrow()
                if (json == null) {
                    _cloudSyncStatus.value = CloudSyncStatus.Error("No backup found in cloud")
                    return@launch
                }
                val result = backupImporter.importFromJsonString(json)
                _cloudSyncStatus.value = CloudSyncStatus.RestoreSuccess(result)
            } catch (t: Throwable) {
                _cloudSyncStatus.value =
                    CloudSyncStatus.Error(t.message ?: "Restore failed")
            }
        }
    }

    fun dismissRestorePrompt() {
        _cloudSyncStatus.value = CloudSyncStatus.Done
    }

    fun consumeStatus() {
        _cloudSyncStatus.value = CloudSyncStatus.Done
    }

    sealed interface CloudSyncStatus {
        data object Idle : CloudSyncStatus
        data object CheckingForBackup : CloudSyncStatus
        data object RestorePrompt : CloudSyncStatus
        data object Restoring : CloudSyncStatus
        data class RestoreSuccess(val result: ImportResult) : CloudSyncStatus
        data class Error(val message: String) : CloudSyncStatus
        /** Terminal: cloud step is done (signed in successfully with nothing to restore, or
         *  user skipped, or restore completed). The screen can call onDismiss. */
        data object Done : CloudSyncStatus
    }
}
