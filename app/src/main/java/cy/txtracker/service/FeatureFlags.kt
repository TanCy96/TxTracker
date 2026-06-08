package cy.txtracker.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted runtime feature flags. Flags gate UI/behavior ONLY — never the Room schema —
 * so the SL Debit tables stay unconditional and the database has a single version line.
 *
 * [slDebitUnlocked] defaults to false: SL Debit is hidden and unreachable until the developer
 * unlocks it via the hidden version-tap gesture in Settings. Mirrors the [CurrencyPrefs]
 * SharedPreferences pattern.
 */
@Singleton
class FeatureFlags @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _slDebitUnlocked = MutableStateFlow(prefs.getBoolean(KEY_SL_DEBIT, false))
    val slDebitUnlocked: StateFlow<Boolean> = _slDebitUnlocked.asStateFlow()

    fun setSlDebitUnlocked(value: Boolean) {
        prefs.edit().putBoolean(KEY_SL_DEBIT, value).apply()
        _slDebitUnlocked.value = value
    }

    private companion object {
        const val FILE = "feature_flags"
        const val KEY_SL_DEBIT = "sl_debit_unlocked"
    }
}
