package cy.txtracker.ui

import androidx.lifecycle.ViewModel
import cy.txtracker.ui.lock.LockState
import cy.txtracker.ui.onboarding.OnboardingPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level state for [AppRoute]. Wraps [OnboardingPrefs] so the route can observe the
 * dismissed flag reactively, and exposes [LockState] so the route can render the lock
 * screen above all other content when the app is locked.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val onboardingPrefs: OnboardingPrefs,
    private val lockState: LockState,
) : ViewModel() {

    val onboardingDismissed: StateFlow<Boolean> = onboardingPrefs.dismissed

    val locked: StateFlow<Boolean> = lockState.locked

    fun markOnboardingDismissed() = onboardingPrefs.setDismissed()

    fun unlock() = lockState.unlock()
}
