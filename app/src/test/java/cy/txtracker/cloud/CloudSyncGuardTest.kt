package cy.txtracker.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudSyncGuardTest {

    @Test
    fun proceeds_when_no_baseline_and_local_has_data() {
        // First upload after sign-in with real data — nothing to compare against, allow.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 5, baselineRowCount = CloudSyncGuard.UNKNOWN_BASELINE)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun skips_when_local_empty_and_no_baseline() {
        // The fresh-install-after-wipe hole: prefs reset to UNKNOWN_BASELINE, local DB empty,
        // worker tries to upload an empty backup that would mask older real cloud backups.
        // Must refuse.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 0, baselineRowCount = CloudSyncGuard.UNKNOWN_BASELINE)
        assertThat(decision).isInstanceOf(CloudSyncGuard.Decision.Skip::class.java)
        val reason = (decision as CloudSyncGuard.Decision.Skip).reason
        assertThat(reason).contains("empty")
    }

    @Test
    fun proceeds_when_local_and_baseline_both_zero() {
        // Last successful upload was already empty — another empty is a no-op, not destructive.
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
        // 40% shrink — within the 50% allowance.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 60, baselineRowCount = 100)
        assertThat(decision).isEqualTo(CloudSyncGuard.Decision.Proceed)
    }

    @Test
    fun proceeds_at_exactly_50_percent_ratio_boundary() {
        // ratio == 0.5 exactly. Implementation uses `<` so this is Proceed; if it ever
        // becomes `<=`, this test fails and forces a deliberate decision.
        val decision = CloudSyncGuard.evaluate(currentRowCount = 50, baselineRowCount = 100)
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
