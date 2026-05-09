package cy.txtracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cy.txtracker.ui.home.HomeRoute
import cy.txtracker.ui.onboarding.OnboardingPrefs
import cy.txtracker.ui.onboarding.OnboardingScreen
import cy.txtracker.ui.onboarding.openListenerSettings
import cy.txtracker.ui.onboarding.rememberListenerGrantState

/**
 * Top-level routing. Shows the onboarding screen on first launch when notification access
 * hasn't been granted and the user hasn't explicitly skipped onboarding; otherwise renders
 * the home screen. Returning from the system Settings page recomputes grant state on
 * ON_RESUME, so the screen swaps automatically once access is enabled.
 */
@Composable
fun AppRoute() {
    val context = LocalContext.current
    val granted by rememberListenerGrantState()
    var dismissed by remember { mutableStateOf(OnboardingPrefs.isDismissed(context)) }

    if (granted || dismissed) {
        HomeRoute()
    } else {
        OnboardingScreen(
            onGrantAccess = { context.openListenerSettings() },
            onSkip = {
                OnboardingPrefs.setDismissed(context)
                dismissed = true
            },
        )
    }
}
