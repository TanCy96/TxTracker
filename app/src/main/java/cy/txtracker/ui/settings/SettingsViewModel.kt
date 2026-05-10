package cy.txtracker.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.export.BackupExporter
import cy.txtracker.export.BackupImporter
import cy.txtracker.export.CsvExporter
import cy.txtracker.export.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val csvExporter: CsvExporter,
    private val backupExporter: BackupExporter,
    private val backupImporter: BackupImporter,
) : ViewModel() {

    private val _exportStatus = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val exportStatus: StateFlow<ExportStatus> = _exportStatus.asStateFlow()

    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val backupStatus: StateFlow<BackupStatus> = _backupStatus.asStateFlow()

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

    sealed interface ExportStatus {
        data object Idle : ExportStatus
        data object Running : ExportStatus
        /** Stringified [Uri] of the exported file ready to be shared via ACTION_SEND. */
        data class Ready(val uri: String) : ExportStatus
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
