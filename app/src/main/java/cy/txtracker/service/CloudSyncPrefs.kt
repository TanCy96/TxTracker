package cy.txtracker.service

import android.content.Context
import cy.txtracker.export.YearMonth
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/**
 * Persisted cloud-sync state and settings. StateFlow-backed so the Settings UI and the
 * upload worker stay in sync without a restart. Mirrors the pattern used by
 * [cy.txtracker.ui.lock.LockPrefs] and [CapturePrefs].
 *
 * Six fields:
 *   - [enabled]: signed-in to Google Drive (false default; opt-in).
 *   - [paused]: temporarily halt uploads/downloads while staying signed in.
 *   - [accountEmail]: display only.
 *   - [lastSyncAt] / [lastSyncError]: surfaced in Settings status line.
 *   - [transactionCutoff]: optional year-month floor; transactions older than this are
 *     excluded from cloud upload.
 */
@Singleton
class CloudSyncPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _paused = MutableStateFlow(prefs.getBoolean(KEY_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _accountEmail = MutableStateFlow(prefs.getString(KEY_ACCOUNT_EMAIL, null))
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    private val _lastSyncAt = MutableStateFlow(
        prefs.getLong(KEY_LAST_SYNC_AT, 0L).takeIf { it > 0L }
            ?.let { Instant.fromEpochMilliseconds(it) },
    )
    val lastSyncAt: StateFlow<Instant?> = _lastSyncAt.asStateFlow()

    private val _lastSyncError = MutableStateFlow(prefs.getString(KEY_LAST_SYNC_ERROR, null))
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    private val _transactionCutoff = MutableStateFlow(readCutoff())
    val transactionCutoff: StateFlow<YearMonth?> = _transactionCutoff.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setPaused(value: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, value).apply()
        _paused.value = value
    }

    fun setAccountEmail(value: String?) {
        prefs.edit().putString(KEY_ACCOUNT_EMAIL, value).apply()
        _accountEmail.value = value
    }

    fun setTransactionCutoff(value: YearMonth?) {
        prefs.edit().putString(KEY_TRANSACTION_CUTOFF, value?.format()).apply()
        _transactionCutoff.value = value
    }

    /** Updates last-sync state. Pass a non-null [error] to record a failure; null clears
     *  it. When [success] is true, also stamps the lastSyncAt timestamp; when false, the
     *  existing timestamp is preserved (we keep "last successful sync" visible). */
    fun setLastSync(success: Boolean, error: String?) {
        val editor = prefs.edit()
        if (success) {
            val nowMs = System.currentTimeMillis()
            editor.putLong(KEY_LAST_SYNC_AT, nowMs)
            _lastSyncAt.value = Instant.fromEpochMilliseconds(nowMs)
        }
        editor.putString(KEY_LAST_SYNC_ERROR, error)
        editor.apply()
        _lastSyncError.value = error
    }

    /** Clears all signed-in state. Called from sign-out. Doesn't clear [transactionCutoff]
     *  — that's a user preference that should persist across sign-out/in cycles. */
    fun clearSession() {
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_PAUSED, false)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_LAST_SYNC_AT)
            .remove(KEY_LAST_SYNC_ERROR)
            .apply()
        _enabled.value = false
        _paused.value = false
        _accountEmail.value = null
        _lastSyncAt.value = null
        _lastSyncError.value = null
    }

    private fun readCutoff(): YearMonth? =
        prefs.getString(KEY_TRANSACTION_CUTOFF, null)
            ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }

    private companion object {
        const val FILE = "cloud_sync"
        const val KEY_ENABLED = "enabled"
        const val KEY_PAUSED = "paused"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_SYNC_ERROR = "last_sync_error"
        const val KEY_TRANSACTION_CUTOFF = "transaction_cutoff"
    }
}
