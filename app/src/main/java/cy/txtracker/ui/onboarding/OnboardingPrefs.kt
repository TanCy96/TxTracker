package cy.txtracker.ui.onboarding

import android.content.Context

/**
 * Tiny SharedPreferences wrapper for the one bit of state we need: whether the user has
 * already dismissed the onboarding screen and chosen to use the app without notification
 * access. Once dismissed, the onboarding screen is skipped on subsequent launches even if
 * notification access is never granted.
 */
internal object OnboardingPrefs {
    private const val FILE = "onboarding"
    private const val KEY_DISMISSED = "dismissed"

    fun isDismissed(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DISMISSED, false)

    fun setDismissed(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    /** Clears the dismissed flag so the onboarding screen reappears on next launch. */
    fun clearDismissed(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY_DISMISSED).apply()
    }
}
