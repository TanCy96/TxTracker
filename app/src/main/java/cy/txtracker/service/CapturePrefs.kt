package cy.txtracker.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted "capture all packages" toggle. When ON, the notification listener bypasses
 * its finance-app allowlist and runs the heuristic + permissive extractors on every
 * package's notifications. Useful for discovering new finance apps; noisy day-to-day
 * because chat / news / shopping apps with RM amounts will create review-needed rows.
 *
 * Default OFF. Mirrors the StateFlow-backed pattern used by
 * [cy.txtracker.ui.lock.LockPrefs] so the Settings toggle and the runtime state stay
 * in sync without a restart.
 */
@Singleton
class CapturePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _captureAllPackages = MutableStateFlow(prefs.getBoolean(KEY_ALL_PACKAGES, false))
    val captureAllPackages: StateFlow<Boolean> = _captureAllPackages.asStateFlow()

    fun setCaptureAllPackages(value: Boolean) {
        prefs.edit().putBoolean(KEY_ALL_PACKAGES, value).apply()
        _captureAllPackages.value = value
    }

    private companion object {
        const val FILE = "capture"
        const val KEY_ALL_PACKAGES = "all_packages"
    }
}
