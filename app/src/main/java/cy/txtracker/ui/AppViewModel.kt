package cy.txtracker.ui

import androidx.lifecycle.ViewModel
import cy.txtracker.ui.onboarding.OnboardingPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level state for [AppRoute]. Wraps [OnboardingPrefs] so the route can observe the
 * dismissed flag reactively and react to changes triggered from anywhere in the app
 * (e.g., the "Reset onboarding" action in Settings re-shows the onboarding screen the
 * same frame, without needing an app restart).
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val onboardingPrefs: OnboardingPrefs,
) : ViewModel() {

    val onboardingDismissed: StateFlow<Boolean> = onboardingPrefs.dismissed

    fun markOnboardingDismissed() = onboardingPrefs.setDismissed()
}
