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

    private val _pendingDismissedUntil = MutableStateFlow(readInstant(KEY_PENDING_DISMISSED_UNTIL))
    val pendingDismissedUntil: StateFlow<Instant?> = _pendingDismissedUntil.asStateFlow()

    private val _foreignEnabled = MutableStateFlow(prefs.getBoolean(KEY_FOREIGN_ENABLED, false))
    val foreignEnabled: StateFlow<Boolean> = _foreignEnabled.asStateFlow()

    private val _foreignDismissedUntil = MutableStateFlow(readInstant(KEY_FOREIGN_DISMISSED_UNTIL))
    val foreignDismissedUntil: StateFlow<Instant?> = _foreignDismissedUntil.asStateFlow()

    private val _summaryCadence = MutableStateFlow(readCadence())
    val summaryCadence: StateFlow<SummaryCadence> = _summaryCadence.asStateFlow()

    private val _summaryHour = MutableStateFlow(prefs.getInt(KEY_SUMMARY_HOUR, DEFAULT_SUMMARY_HOUR))
    val summaryHour: StateFlow<Int> = _summaryHour.asStateFlow()

    private val _budgetAlertsEnabled = MutableStateFlow(prefs.getBoolean(KEY_BUDGET_ALERTS_ENABLED, false))
    val budgetAlertsEnabled: StateFlow<Boolean> = _budgetAlertsEnabled.asStateFlow()

    /** Per-(month, scope, threshold) keys already alerted on, so each fires once. Reset monthly by the worker. */
    private val _firedBudgetAlertKeys =
        MutableStateFlow(prefs.getStringSet(KEY_FIRED_BUDGET_KEYS, emptySet())!!.toSet())
    val firedBudgetAlertKeys: StateFlow<Set<String>> = _firedBudgetAlertKeys.asStateFlow()

    fun setPendingEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_PENDING_ENABLED, value).apply()
        _pendingEnabled.value = value
    }

    /** null clears the cooldown. */
    fun setPendingDismissedUntil(at: Instant?) {
        writeInstant(KEY_PENDING_DISMISSED_UNTIL, at)
        _pendingDismissedUntil.value = at
    }

    fun setForeignEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_FOREIGN_ENABLED, value).apply()
        _foreignEnabled.value = value
    }

    /** null clears the cooldown. */
    fun setForeignDismissedUntil(at: Instant?) {
        writeInstant(KEY_FOREIGN_DISMISSED_UNTIL, at)
        _foreignDismissedUntil.value = at
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

    fun setBudgetAlertsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BUDGET_ALERTS_ENABLED, value).apply()
        _budgetAlertsEnabled.value = value
    }

    fun setFiredBudgetAlertKeys(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_FIRED_BUDGET_KEYS, keys).apply()
        _firedBudgetAlertKeys.value = keys
    }

    private fun readInstant(key: String): Instant? =
        if (prefs.contains(key)) Instant.fromEpochMilliseconds(prefs.getLong(key, 0)) else null

    private fun writeInstant(key: String, at: Instant?) {
        prefs.edit().apply {
            if (at == null) remove(key)
            else putLong(key, at.toEpochMilliseconds())
        }.apply()
    }

    private fun readCadence(): SummaryCadence = runCatching {
        SummaryCadence.valueOf(prefs.getString(KEY_SUMMARY_CADENCE, SummaryCadence.OFF.name)!!)
    }.getOrDefault(SummaryCadence.OFF)

    private companion object {
        const val FILE = "notifications"
        const val KEY_PENDING_ENABLED = "pending_enabled"
        const val KEY_PENDING_DISMISSED_UNTIL = "pending_dismissed_until"
        const val KEY_FOREIGN_ENABLED = "foreign_enabled"
        const val KEY_FOREIGN_DISMISSED_UNTIL = "foreign_dismissed_until"
        const val KEY_SUMMARY_CADENCE = "summary_cadence"
        const val KEY_SUMMARY_HOUR = "summary_hour"
        const val DEFAULT_SUMMARY_HOUR = 20  // 8pm MYT
        const val KEY_BUDGET_ALERTS_ENABLED = "budget_alerts_enabled"
        const val KEY_FIRED_BUDGET_KEYS = "fired_budget_alert_keys"
    }
}
