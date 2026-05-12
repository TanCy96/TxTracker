package cy.txtracker.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

enum class SummaryCadence { OFF, DAILY, WEEKLY, MONTHLY }

/**
 * Local-only opt-in state for app-sent push notifications. Defaults: every
 * notification feature OFF; summary hour 20 (8pm MYT). Not round-tripped via
 * backup — a fresh device re-prompts the user.
 */
@Singleton
class NotificationPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _pendingEnabled = MutableStateFlow(prefs.getBoolean(KEY_PENDING_ENABLED, false))
    val pendingEnabled: StateFlow<Boolean> = _pendingEnabled.asStateFlow()

    private val _pendingDismissedUntil = MutableStateFlow(readDismissedUntil())
    val pendingDismissedUntil: StateFlow<Instant?> = _pendingDismissedUntil.asStateFlow()

    private val _summaryCadence = MutableStateFlow(readCadence())
    val summaryCadence: StateFlow<SummaryCadence> = _summaryCadence.asStateFlow()

    private val _summaryHour = MutableStateFlow(prefs.getInt(KEY_SUMMARY_HOUR, DEFAULT_SUMMARY_HOUR))
    val summaryHour: StateFlow<Int> = _summaryHour.asStateFlow()

    fun setPendingEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_PENDING_ENABLED, value).apply()
        _pendingEnabled.value = value
    }

    /** null clears the cooldown. */
    fun setPendingDismissedUntil(at: Instant?) {
        prefs.edit().apply {
            if (at == null) remove(KEY_PENDING_DISMISSED_UNTIL)
            else putLong(KEY_PENDING_DISMISSED_UNTIL, at.toEpochMilliseconds())
        }.apply()
        _pendingDismissedUntil.value = at
    }

    fun setSummaryCadence(cadence: SummaryCadence) {
        prefs.edit().putString(KEY_SUMMARY_CADENCE, cadence.name).apply()
        _summaryCadence.value = cadence
    }

    /** Validates [hour] in 0..23. */
    fun setSummaryHour(hour: Int) {
        require(hour in 0..23) { "summaryHour must be 0..23, got $hour" }
        prefs.edit().putInt(KEY_SUMMARY_HOUR, hour).apply()
        _summaryHour.value = hour
    }

    private fun readDismissedUntil(): Instant? =
        if (prefs.contains(KEY_PENDING_DISMISSED_UNTIL))
            Instant.fromEpochMilliseconds(prefs.getLong(KEY_PENDING_DISMISSED_UNTIL, 0))
        else null

    private fun readCadence(): SummaryCadence = runCatching {
        SummaryCadence.valueOf(prefs.getString(KEY_SUMMARY_CADENCE, SummaryCadence.OFF.name)!!)
    }.getOrDefault(SummaryCadence.OFF)

    private companion object {
        const val FILE = "notifications"
        const val KEY_PENDING_ENABLED = "pending_enabled"
        const val KEY_PENDING_DISMISSED_UNTIL = "pending_dismissed_until"
        const val KEY_SUMMARY_CADENCE = "summary_cadence"
        const val KEY_SUMMARY_HOUR = "summary_hour"
        const val DEFAULT_SUMMARY_HOUR = 20  // 8pm MYT
    }
}
