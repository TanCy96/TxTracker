package cy.txtracker.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudSyncGuardTest {

    @Test
    fun proceeds_when_no_baseline() {
        // First-ever upload: baseline = UNKNOWN. We proceed because we have nothing to compare to.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 0, baselineRowCount = CloudSyncGuard.UNKNOWN_BASELINE)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun proceeds_when_local_and_baseline_both_zero() {
        // Long-time empty installs (e.g., user signed in but never captured anything).
        val decision = CloudSyncGuard.evaluate(currentRowCount = 0, baselineRowCount = 0)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun proceeds_when_local_grew() {
        val decision = CloudSyncGuard.evaluate(currentRowCount = 100, baselineRowCount = 50)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun proceeds_when_local_shrank_within_threshold() {
        // User legitimately deleted some rows. Within 50% shrink threshold — allow.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 60, baselineRowCount = 100)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun skips_when_local_emptied_but_baseline_had_rows() {
        // The smoking gun for ISSUE.md #1 — local DB got wiped, baseline has data.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 0, baselineRowCount = 100)
        assertThat(decision).isInstanceOf(CloudSyncGuard.Decision.Skip::class.java)
        val reason = (decision as CloudSyncGuard.Decision.Skip).reason
        assertThat(reason).contains("empty")
    }

    @Test
    fun skips_when_local_shrank_beyond_threshold() {
        // 80% shrink — beyond the 50% threshold. Skip and let user inspect.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 20, baselineRowCount = 100)
        assertThat(decision).isInstanceOf(CloudSyncGuard.Decision.Skip::class.java)
    }
}
