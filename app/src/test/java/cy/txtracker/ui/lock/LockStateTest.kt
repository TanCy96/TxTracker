package cy.txtracker.ui.lock

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class LockStateTest {

    private fun stateWith(initialEnabled: Boolean): Pair<LockState, MutableStateFlow<Boolean>> {
        val flag = MutableStateFlow(initialEnabled)
        val prefs = mockk<LockPrefs> { every { enabled } returns flag }
        return LockState(prefs) to flag
    }

    @Test
    fun cold_start_locks_when_enabled() {
        val (state, _) = stateWith(initialEnabled = true)
        assertThat(state.locked.value).isTrue()
    }

    @Test
    fun cold_start_does_not_lock_when_disabled() {
        val (state, _) = stateWith(initialEnabled = false)
        assertThat(state.locked.value).isFalse()
    }

    @Test
    fun unlock_clears_locked_state() {
        val (state, _) = stateWith(initialEnabled = true)
        state.unlock()
        assertThat(state.locked.value).isFalse()
    }

    @Test
    fun foregrounded_after_grace_window_locks() {
        val (state, _) = stateWith(initialEnabled = true)
        state.unlock()
        var now = 0L
        state.nowMs = { now }

        state.onBackgrounded()
        now += LockState.GRACE_MS + 1
        state.onForegrounded()

        assertThat(state.locked.value).isTrue()
    }

    @Test
    fun foregrounded_within_grace_window_does_not_lock() {
        val (state, _) = stateWith(initialEnabled = true)
        state.unlock()
        var now = 0L
        state.nowMs = { now }

        state.onBackgrounded()
        now += LockState.GRACE_MS - 1
        state.onForegrounded()

        assertThat(state.locked.value).isFalse()
    }

    @Test
    fun foregrounded_when_lock_is_disabled_never_locks() {
        val (state, _) = stateWith(initialEnabled = false)
        var now = 0L
        state.nowMs = { now }

        state.onBackgrounded()
        now += 1_000_000L
        state.onForegrounded()

        assertThat(state.locked.value).isFalse()
    }

    @Test
    fun foregrounded_without_a_prior_backgrounded_event_is_a_noop() {
        // Defends against odd lifecycle orderings where ON_START fires without a paired
        // ON_STOP. Should NOT lock in that case.
        val (state, _) = stateWith(initialEnabled = true)
        state.unlock()
        state.nowMs = { LockState.GRACE_MS * 100 }

        state.onForegrounded()

        assertThat(state.locked.value).isFalse()
    }

    @Test
    fun toggling_lock_off_via_prefs_makes_subsequent_foreground_a_noop() {
        // User enables lock, unlocks, backgrounds for a long time, but disables the toggle
        // before returning. Should NOT lock when they come back.
        val (state, flag) = stateWith(initialEnabled = true)
        state.unlock()
        var now = 0L
        state.nowMs = { now }

        state.onBackgrounded()
        flag.value = false  // user toggles off while away
        now += LockState.GRACE_MS * 10
        state.onForegrounded()

        assertThat(state.locked.value).isFalse()
    }
}
