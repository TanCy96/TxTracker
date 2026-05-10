package cy.txtracker.ui.onboarding

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted "onboarding dismissed" flag, exposed as a [StateFlow] so the rest of the UI
 * recomposes immediately when the value changes (e.g. when the user taps "Reset onboarding"
 * in Settings, the home AppRoute swaps back to the onboarding screen the same frame).
 *
 * The flag means "the user has completed or dismissed onboarding". It's set when:
 *   - The user taps "Continue without notifications" on the onboarding screen.
 *   - The user grants notification access (auto-dismissed in AppRoute via a LaunchedEffect),
 *     so they don't see the onboarding screen again on every launch.
 *
 * Cleared by the "Reset onboarding" action in Settings, which should immediately bring the
 * onboarding screen back regardless of whether access is currently granted.
 */
@Singleton
class OnboardingPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _dismissed = MutableStateFlow(prefs.getBoolean(KEY_DISMISSED, false))
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    fun setDismissed() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
        _dismissed.value = true
    }

    fun clearDismissed() {
        prefs.edit().remove(KEY_DISMISSED).apply()
        _dismissed.value = false
    }

    private companion object {
        const val FILE = "onboarding"
        const val KEY_DISMISSED = "dismissed"
    }
}
