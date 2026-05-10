package cy.txtracker.ui.lock

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime locked / unlocked state, separate from the persisted [LockPrefs] flag.
 *
 * - Cold start: locked iff the toggle is on, so the user authenticates before seeing data.
 * - Background → foreground: locked iff the app was backgrounded longer than [GRACE_MS].
 *   Brief tab-outs (pulling down notifications, replying to a quick message) don't re-lock.
 * - Toggle off: callers should call [unlock] to clear the runtime locked state immediately.
 *
 * `nowMs` is overridable for tests that want to control the clock without booting Android.
 */
@Singleton
class LockState @Inject constructor(
    private val lockPrefs: LockPrefs,
) {
    private val _locked = MutableStateFlow(lockPrefs.enabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAtMs: Long? = null

    /** Test seam: production uses System#currentTimeMillis. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** Mark the user authenticated. Called when biometric succeeds OR toggle is turned off. */
    fun unlock() {
        _locked.value = false
        backgroundedAtMs = null
    }

    /** Process-level ON_STOP. Records when the app went into the background. */
    fun onBackgrounded() {
        if (lockPrefs.enabled.value) {
            backgroundedAtMs = nowMs()
        }
    }

    /**
     * Process-level ON_START. Locks if the lock toggle is on AND the app was backgrounded
     * longer than [GRACE_MS] ago. Doesn't touch state when the toggle is off so toggling
     * the feature on/off doesn't surprise the user with an unexpected lock.
     */
    fun onForegrounded() {
        if (!lockPrefs.enabled.value) {
            backgroundedAtMs = null
            return
        }
        val backgroundedAt = backgroundedAtMs ?: return
        if (nowMs() - backgroundedAt > GRACE_MS) {
            _locked.value = true
        }
        backgroundedAtMs = null
    }

    companion object {
        const val GRACE_MS: Long = 30_000L
    }
}
