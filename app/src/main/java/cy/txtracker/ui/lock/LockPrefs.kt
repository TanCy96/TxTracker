package cy.txtracker.ui.lock

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted "lock enabled" toggle. Mirrors the StateFlow-backed pattern used by
 * [cy.txtracker.ui.onboarding.OnboardingPrefs] so the Settings toggle and the
 * runtime lock state stay in sync without a restart.
 */
@Singleton
class LockPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private companion object {
        const val FILE = "lock"
        const val KEY_ENABLED = "enabled"
    }
}
