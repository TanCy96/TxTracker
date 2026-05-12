package cy.txtracker.ui.settings.notifications

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cy.txtracker.notify.NotificationPermissionBridge
import cy.txtracker.service.NotificationPrefs
import cy.txtracker.service.SummaryCadence
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val pendingEnabled: Boolean,
    val foreignEnabled: Boolean,
    val summaryCadence: SummaryCadence,
    val summaryHour: Int,
    val osNotificationsDisabled: Boolean,
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: NotificationPrefs,
    private val permissionBridge: NotificationPermissionBridge,
) : ViewModel() {

    /** Polled by the screen on ON_RESUME so toggling OS settings updates the UI. */
    private val osDisabledRefresh = MutableStateFlow(checkOsDisabled())

    val state: StateFlow<NotificationsUiState> = combine(
        prefs.pendingEnabled,
        prefs.foreignEnabled,
        prefs.summaryCadence,
        prefs.summaryHour,
        osDisabledRefresh,
    ) { p, f, c, h, osDisabled ->
        NotificationsUiState(
            pendingEnabled = p,
            foreignEnabled = f,
            summaryCadence = c,
            summaryHour = h,
            osNotificationsDisabled = osDisabled,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        NotificationsUiState(
            pendingEnabled = false,
            foreignEnabled = false,
            summaryCadence = SummaryCadence.OFF,
            summaryHour = 20,
            osNotificationsDisabled = false,
        ),
    )

    fun refreshOsState() {
        osDisabledRefresh.value = checkOsDisabled()
    }

    fun setPendingEnabled(value: Boolean) {
        if (value) {
            requestPermissionThen { prefs.setPendingEnabled(true) }
        } else {
            prefs.setPendingEnabled(false)
        }
    }

    fun setForeignEnabled(value: Boolean) {
        if (value) {
            requestPermissionThen { prefs.setForeignEnabled(true) }
        } else {
            prefs.setForeignEnabled(false)
        }
    }

    fun setSummaryCadence(cadence: SummaryCadence) {
        if (cadence != SummaryCadence.OFF && prefs.summaryCadence.value == SummaryCadence.OFF) {
            requestPermissionThen { prefs.setSummaryCadence(cadence) }
        } else {
            prefs.setSummaryCadence(cadence)
        }
    }

    fun setSummaryHour(hour: Int) {
        prefs.setSummaryHour(hour)
    }

    fun openSystemSettings(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    private fun requestPermissionThen(action: () -> Unit) {
        viewModelScope.launch {
            permissionBridge.request()
            action()
            refreshOsState()
        }
    }

    private fun checkOsDisabled(): Boolean =
        !NotificationManagerCompat.from(context).areNotificationsEnabled()
}
