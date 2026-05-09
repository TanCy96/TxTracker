package cy.txtracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.export.CsvExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val csvExporter: CsvExporter,
) : ViewModel() {

    private val _exportStatus = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val exportStatus: StateFlow<ExportStatus> = _exportStatus.asStateFlow()

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

    sealed interface ExportStatus {
        data object Idle : ExportStatus
        data object Running : ExportStatus
        /** Stringified [Uri] of the exported file ready to be shared via ACTION_SEND. */
        data class Ready(val uri: String) : ExportStatus
        data class Error(val message: String) : ExportStatus
    }
}
