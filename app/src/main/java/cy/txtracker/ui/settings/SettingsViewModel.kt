package cy.txtracker.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import cy.txtracker.cloud.CloudSyncGuard
import cy.txtracker.cloud.CloudSyncScheduler
import cy.txtracker.cloud.DriveClient
import cy.txtracker.cloud.GoogleSignInStateProvider
import cy.txtracker.data.TrackedCurrency
import cy.txtracker.data.TransactionRepository
import cy.txtracker.export.BackupExporter
import cy.txtracker.export.BackupImporter
import cy.txtracker.export.CsvExporter
import cy.txtracker.export.ImportResult
import cy.txtracker.export.YearMonth
import cy.txtracker.service.CloudSyncPrefs
import kotlinx.datetime.Instant
import cy.txtracker.ui.lock.LockPrefs
import cy.txtracker.ui.lock.LockState
import cy.txtracker.ui.onboarding.OnboardingPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val csvExporter: CsvExporter,
    private val backupExporter: BackupExporter,
    private val backupImporter: BackupImporter,
    private val onboardingPrefs: OnboardingPrefs,
    private val lockPrefs: LockPrefs,
    private val lockState: LockState,
    private val cloudSyncPrefs: CloudSyncPrefs,
    private val cloudSyncScheduler: CloudSyncScheduler,
    private val driveClient: DriveClient,
    private val signInState: GoogleSignInStateProvider,
    private val repository: TransactionRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** Tracked currencies for the CSV export chooser sheet. */
    val trackedCurrencies: StateFlow<List<TrackedCurrency>> =
        repository.observeTrackedCurrencies()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = emptyList(),
            )

    val lockEnabled: StateFlow<Boolean> = lockPrefs.enabled

    fun setLockEnabled(value: Boolean) {
        lockPrefs.setEnabled(value)
        // Turning the toggle off should also clear any pending runtime lock state so the
        // user doesn't stay locked-in-memory unexpectedly. Toggling on doesn't lock the
        // current session — the lock kicks in next time the app cold-starts or comes back
        // from a long background.
        if (!value) lockState.unlock()
    }

    val poolPendingCount: StateFlow<Int> =
        repository.observePoolPendingCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = 0,
            )

    fun resetOnboarding() = onboardingPrefs.clearDismissed()

    private val _exportStatus = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val exportStatus: StateFlow<ExportStatus> = _exportStatus.asStateFlow()

    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val backupStatus: StateFlow<BackupStatus> = _backupStatus.asStateFlow()

    private val _backfillResult = MutableStateFlow<BackfillResult?>(null)
    val backfillResult: StateFlow<BackfillResult?> = _backfillResult.asStateFlow()

    /**
     * Runs the categorizer + describer over every transaction whose category or
     * description is null, applying any non-null engine result. Surfaces a summary
     * count via [backfillResult] for the UI to present in a dialog.
     */
    fun runBackfill() {
        viewModelScope.launch {
            val categoryRows = repository.recategorizeNullRows()
            val descriptionRows = repository.redescribeNullRows()
            _backfillResult.value = BackfillResult(categoryRows, descriptionRows)
        }
    }

    fun consumeBackfillResult() { _backfillResult.value = null }

    data class BackfillResult(val categoryRows: Int, val descriptionRows: Int)

    private val _classifyResult = MutableStateFlow<Int?>(null)
    val classifyResult: StateFlow<Int?> = _classifyResult.asStateFlow()

    /**
     * Runs the funding-source classifier over every transaction whose [fundingSourceId] is
     * null. Surfaces the count of rows linked via [classifyResult] for a snackbar.
     */
    fun classifyExistingTransactions() {
        viewModelScope.launch {
            _classifyResult.value = repository.classifyAllUnlinkedTransactions()
        }
    }

    fun consumeClassifyResult() { _classifyResult.value = null }

    private val _reparseResult = MutableStateFlow<cy.txtracker.data.ReparseResult?>(null)
    val reparseResult: StateFlow<cy.txtracker.data.ReparseResult?> = _reparseResult.asStateFlow()

    /**
     * Runs the parser over every captured row's stored `rawText`, applying any
     * per-package rewrite rules first. Updates merchant fields where the new parse
     * differs and the row hasn't been user-edited. Surfaces counts via [reparseResult].
     */
    fun runReparseMerchants() {
        viewModelScope.launch {
            _reparseResult.value = repository.reparseMerchantsFromRawText()
        }
    }

    fun consumeReparseResult() { _reparseResult.value = null }

    fun export() {
        if (_exportStatus.value is ExportStatus.Running) return
        _exportStatus.value = ExportStatus.Running
        viewModelScope.launch {
            try {
                val uri = csvExporter.export()
                _exportStatus.value = ExportStatus.Ready(uri.toString())
            } catch (t: Throwable) {
                _exportStatus.value = ExportStatus.Error(t.message ?: "Export failed")
            }
        }
    }

    /** Export transactions for [currency] as a CSV file and share via ACTION_SEND. */
    fun exportCsv(currency: String) {
        if (_exportStatus.value is ExportStatus.Running) return
        _exportStatus.value = ExportStatus.Running
        viewModelScope.launch {
            try {
                val uri = csvExporter.exportCsv(currency)
                _exportStatus.value = ExportStatus.Ready(uri.toString())
            } catch (t: Throwable) {
                _exportStatus.value = ExportStatus.Error(t.message ?: "Export failed")
            }
        }
    }

    /** Export all currencies as a zip of per-currency CSVs and share via ACTION_SEND. */
    fun exportAllZip() {
        if (_exportStatus.value is ExportStatus.Running) return
        _exportStatus.value = ExportStatus.Running
        viewModelScope.launch {
            try {
                val uri = csvExporter.exportAllCurrenciesZip()
                _exportStatus.value = ExportStatus.ZipReady(uri.toString())
            } catch (t: Throwable) {
                _exportStatus.value = ExportStatus.Error(t.message ?: "Export failed")
            }
        }
    }

    fun consumeStatus() {
        _exportStatus.value = ExportStatus.Idle
    }

    fun exportBackup() {
        if (_backupStatus.value is BackupStatus.Running) return
        _backupStatus.value = BackupStatus.Running("Building backup…")
        viewModelScope.launch {
            try {
                val uri = backupExporter.export()
                _backupStatus.value = BackupStatus.ExportReady(uri.toString())
            } catch (t: Throwable) {
                _backupStatus.value = BackupStatus.Error(t.message ?: "Backup failed")
            }
        }
    }

    fun importBackup(uri: Uri) {
        if (_backupStatus.value is BackupStatus.Running) return
        _backupStatus.value = BackupStatus.Running("Restoring…")
        viewModelScope.launch {
            try {
                val result = backupImporter.import(uri)
                _backupStatus.value = BackupStatus.Imported(result)
            } catch (t: Throwable) {
                _backupStatus.value = BackupStatus.Error(t.message ?: "Restore failed")
            }
        }
    }

    fun consumeBackupStatus() {
        _backupStatus.value = BackupStatus.Idle
    }

    // ─── Cloud sync state ──────────────────────────────────────────────────────────

    val cloudSyncEnabled: StateFlow<Boolean> = cloudSyncPrefs.enabled
    val cloudSyncPaused: StateFlow<Boolean> = cloudSyncPrefs.paused
    val cloudAccountEmail: StateFlow<String?> = cloudSyncPrefs.accountEmail
    val cloudLastSyncAt: StateFlow<Instant?> = cloudSyncPrefs.lastSyncAt
    val cloudLastSyncError: StateFlow<String?> = cloudSyncPrefs.lastSyncError
    val cloudTransactionCutoff: StateFlow<YearMonth?> = cloudSyncPrefs.transactionCutoff
    val cloudSyncBlockedReason: StateFlow<String?> = cloudSyncPrefs.syncBlockedReason

    /** Clears the block and wipes the baseline (sets UNKNOWN_BASELINE) so the guard skips
     *  the row-count check on the next run rather than blocking again. The worker writes a
     *  new baseline after its next successful upload. */
    fun resumeBlockedSync() {
        cloudSyncPrefs.setSyncBlockedReason(null)
        cloudSyncPrefs.setLastUploadedRowCount(CloudSyncGuard.UNKNOWN_BASELINE)
        cloudSyncScheduler.enqueueImmediateUpload()
    }

    /** True when a cloud-sync WorkManager job is enqueued OR running. Drives the
     *  "Sync now" row's spinner so the user gets visible feedback when uploads are
     *  pending (e.g., waiting on a network constraint) or in flight. */
    val cloudSyncInFlight: StateFlow<Boolean> =
        WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(CloudSyncScheduler.WORK_NAME)
            .map { infos -> infos.any { !it.state.isFinished } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = false,
            )

    private val _cloudSyncStatus = MutableStateFlow<CloudSyncStatus>(CloudSyncStatus.Idle)
    val cloudSyncStatus: StateFlow<CloudSyncStatus> = _cloudSyncStatus.asStateFlow()

    private val _cloudRestorePickerState =
        MutableStateFlow<CloudRestorePickerState>(CloudRestorePickerState.Hidden)
    val cloudRestorePickerState: StateFlow<CloudRestorePickerState> =
        _cloudRestorePickerState.asStateFlow()

    /** Called after a successful Google Sign-In activity result. */
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
                CloudSyncStatus.Idle
            }
        }
    }

    /** Called after sign-in fails or is cancelled. */
    fun signInFailed(message: String) {
        _cloudSyncStatus.value = CloudSyncStatus.Error(message)
    }

    fun setCloudPaused(value: Boolean) = cloudSyncPrefs.setPaused(value)

    fun cloudSyncNow() {
        cloudSyncScheduler.enqueueImmediateUpload()
    }

    fun setTransactionCutoff(value: YearMonth?) {
        viewModelScope.launch {
            backupExporter.saveLocalRollbackSnapshot("pre-cutoff-change")
            cloudSyncPrefs.setTransactionCutoff(value)
        }
    }

    fun cloudSignOut(deleteCloudBackup: Boolean) {
        viewModelScope.launch {
            if (deleteCloudBackup) driveClient.delete().getOrNull()
            cloudSyncScheduler.cancelPending()
            cloudSyncPrefs.clearSession()
            signInState.signInClient.signOut()
        }
    }

    /** Opens the cloud-restore picker and starts fetching the list of backups. */
    fun openCloudRestorePicker() {
        _cloudRestorePickerState.value = CloudRestorePickerState.Loading
        viewModelScope.launch {
            val result = driveClient.listAll()
            _cloudRestorePickerState.value = result.fold(
                onSuccess = { files ->
                    CloudRestorePickerState.Loaded(files.sortedByDescending { it.modifiedAt })
                },
                onFailure = { e ->
                    CloudRestorePickerState.Error(e.message ?: "Failed to list backups")
                },
            )
        }
    }

    fun dismissCloudRestorePicker() {
        _cloudRestorePickerState.value = CloudRestorePickerState.Hidden
    }

    /** Restore a specific backup file by Drive id. Snapshots local state first. */
    fun restoreFromCloudById(fileId: String) {
        _cloudSyncStatus.value = CloudSyncStatus.Restoring
        _cloudRestorePickerState.value = CloudRestorePickerState.Hidden
        viewModelScope.launch {
            try {
                backupExporter.saveLocalRollbackSnapshot("pre-cloud-restore")
                val json = driveClient.download(fileId).getOrThrow()
                val result = backupImporter.importFromJsonString(json)
                _cloudSyncStatus.value = CloudSyncStatus.RestoreSuccess(result)
            } catch (t: Throwable) {
                _cloudSyncStatus.value =
                    CloudSyncStatus.Error(t.message ?: "Restore failed")
            }
        }
    }

    /** Restore from cloud — explicit user action. Snapshots local state first. */
    fun restoreFromCloud() {
        _cloudSyncStatus.value = CloudSyncStatus.Restoring
        viewModelScope.launch {
            try {
                backupExporter.saveLocalRollbackSnapshot("pre-cloud-restore")
                val json = driveClient.download().getOrThrow()
                if (json == null) {
                    _cloudSyncStatus.value =
                        CloudSyncStatus.Error("No backup found in cloud")
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
        _cloudSyncStatus.value = CloudSyncStatus.Idle
    }

    fun consumeCloudStatus() {
        _cloudSyncStatus.value = CloudSyncStatus.Idle
    }

    /** Intent used by the Settings screen's sign-in launcher. */
    fun signInIntent() = signInState.signInClient.signInIntent

    sealed interface CloudRestorePickerState {
        data object Hidden : CloudRestorePickerState
        data object Loading : CloudRestorePickerState
        data class Loaded(val files: List<cy.txtracker.cloud.BackupFile>) : CloudRestorePickerState
        data class Error(val message: String) : CloudRestorePickerState
    }

    sealed interface CloudSyncStatus {
        data object Idle : CloudSyncStatus
        data object CheckingForBackup : CloudSyncStatus
        data object RestorePrompt : CloudSyncStatus
        data object Restoring : CloudSyncStatus
        data class RestoreSuccess(val result: ImportResult) : CloudSyncStatus
        data class Error(val message: String) : CloudSyncStatus
    }

    sealed interface ExportStatus {
        data object Idle : ExportStatus
        data object Running : ExportStatus
        /** Stringified [Uri] of the exported CSV ready to be shared via ACTION_SEND. */
        data class Ready(val uri: String) : ExportStatus
        /** Stringified [Uri] of the exported zip file ready to be shared via ACTION_SEND. */
        data class ZipReady(val uri: String) : ExportStatus
        data class Error(val message: String) : ExportStatus
    }

    sealed interface BackupStatus {
        data object Idle : BackupStatus
        data class Running(val message: String) : BackupStatus
        /** Backup written and ready to share — UI fires the ACTION_SEND chooser. */
        data class ExportReady(val uri: String) : BackupStatus
        /** Restore completed; the UI shows a snackbar summarizing what changed. */
        data class Imported(val result: ImportResult) : BackupStatus
        data class Error(val message: String) : BackupStatus
    }
}
