package cy.txtracker.notify

import androidx.work.ExistingPeriodicWorkPolicy
import com.google.common.truth.Truth.assertThat
import cy.txtracker.service.SummaryCadence
import org.junit.Test

class NotificationSchedulerPolicyTest {

    @Test
    fun first_emission_uses_update() {
        // No prior signature — UPDATE is correct because there's nothing to re-anchor
        // (either no existing work, or work with the right anchor from the previous
        // process). UPDATE is also a safe no-op when the work already exists.
        val policy = NotificationScheduler.decideSummaryPolicy(
            last = null,
            current = NotificationScheduler.SummarySignature(SummaryCadence.DAILY, 20),
        )
        assertThat(policy).isEqualTo(ExistingPeriodicWorkPolicy.UPDATE)
    }

    @Test
    fun unchanged_signature_uses_update() {
        // Same cadence + hour as the previous reconcile — keep the existing anchor.
        val signature = NotificationScheduler.SummarySignature(SummaryCadence.DAILY, 20)
        val policy = NotificationScheduler.decideSummaryPolicy(
            last = signature,
            current = signature,
        )
        assertThat(policy).isEqualTo(ExistingPeriodicWorkPolicy.UPDATE)
    }

    @Test
    fun changed_hour_uses_cancel_and_reenqueue() {
        // ISSUE.md #2 reproduction: user changed daily summary hour 8pm -> 9pm. Without
        // CANCEL_AND_REENQUEUE, WorkManager silently kept the original 8pm anchor.
        val policy = NotificationScheduler.decideSummaryPolicy(
            last = NotificationScheduler.SummarySignature(SummaryCadence.DAILY, 20),
            current = NotificationScheduler.SummarySignature(SummaryCadence.DAILY, 21),
        )
        assertThat(policy).isEqualTo(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
    }

    @Test
    fun changed_cadence_uses_cancel_and_reenqueue() {
        // DAILY -> WEEKLY at the same hour must re-anchor the periodic interval.
        val policy = NotificationScheduler.decideSummaryPolicy(
            last = NotificationScheduler.SummarySignature(SummaryCadence.DAILY, 20),
            current = NotificationScheduler.SummarySignature(SummaryCadence.WEEKLY, 20),
        )
        assertThat(policy).isEqualTo(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
    }
}
