package cy.txtracker.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnlockTapTest {

    @Test
    fun early_taps_do_not_unlock_and_show_no_hint() {
        val r = registerUnlockTap(currentCount = 0, alreadyUnlocked = false)
        assertThat(r.unlocked).isFalse()
        assertThat(r.newCount).isEqualTo(1)
        assertThat(r.hintRemaining).isNull()
    }

    @Test
    fun last_two_taps_before_threshold_emit_a_hint() {
        val fifth = registerUnlockTap(currentCount = 4, alreadyUnlocked = false)
        assertThat(fifth.unlocked).isFalse()
        assertThat(fifth.hintRemaining).isEqualTo(2)

        val sixth = registerUnlockTap(currentCount = 5, alreadyUnlocked = false)
        assertThat(sixth.unlocked).isFalse()
        assertThat(sixth.hintRemaining).isEqualTo(1)
    }

    @Test
    fun seventh_tap_unlocks_and_resets_count() {
        val r = registerUnlockTap(currentCount = 6, alreadyUnlocked = false)
        assertThat(r.unlocked).isTrue()
        assertThat(r.newCount).isEqualTo(0)
        assertThat(r.hintRemaining).isNull()
    }

    @Test
    fun taps_when_already_unlocked_are_noops() {
        val r = registerUnlockTap(currentCount = 3, alreadyUnlocked = true)
        assertThat(r.unlocked).isFalse()
        assertThat(r.newCount).isEqualTo(0)
        assertThat(r.hintRemaining).isNull()
    }
}
