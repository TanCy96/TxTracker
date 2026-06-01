package cy.txtracker.notify

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cy.txtracker.service.NotificationPrefs
import cy.txtracker.service.SummaryCadence
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock

/**
 * Reconciles WorkManager unique-work state against [NotificationPrefs] on every
 * pref change. Started once from [cy.txtracker.TxApp.onCreate]; uses
 * `ProcessLifecycleOwner.lifecycleScope` so the observer lives as long as the
 * process.
 */
@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: NotificationPrefs,
) {
    fun start(scope: CoroutineScope) {
        combine(
            prefs.pendingEnabled,
            prefs.foreignEnabled,
            prefs.summaryCadence,
            prefs.summaryHour,
            prefs.budgetAlertsEnabled,
        ) { pending, foreign, cadence, hour, budget ->
            SchedulerSnapshot(pending, foreign, cadence, hour, budget)
        }
            .onEach { snapshot ->
                reconcilePending(snapshot.pendingEnabled)
                reconcileForeign(snapshot.foreignEnabled)
                reconcileSummary(snapshot.cadence, snapshot.hour)
                reconcileBudget(snapshot.budgetEnabled)
            }
            .launchIn(scope)
    }

    private fun reconcilePending(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            // Initial delay targets the next 8pm MYT so the first reminder lands
            // in the evening rather than at whichever hour WorkManager happens to
            // pick within the 1h flex window of the periodic schedule.
            val initialDelayMs = millisUntilNextFiring(
                cadence = SummaryCadence.DAILY,
                hour = EVENING_HOUR,
                now = Clock.System.now(),
            )
            val request = PeriodicWorkRequestBuilder<PendingReminderWorker>(
                /* repeatInterval = */ 24, TimeUnit.HOURS,
                /* flexInterval = */ 1, TimeUnit.HOURS,
            )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(
                PENDING_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } else {
            wm.cancelUniqueWork(PENDING_WORK_NAME)
            NotificationManagerCompat.from(context).cancel(NotificationIds.PENDING)
        }
    }

    private fun reconcileForeign(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            // Same evening-anchor as the pending reminder.
            val initialDelayMs = millisUntilNextFiring(
                cadence = SummaryCadence.DAILY,
                hour = EVENING_HOUR,
                now = Clock.System.now(),
            )
            val request = PeriodicWorkRequestBuilder<ForeignCurrencyWorker>(
                /* repeatInterval = */ 24, TimeUnit.HOURS,
                /* flexInterval = */ 1, TimeUnit.HOURS,
            )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(
                FOREIGN_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } else {
            wm.cancelUniqueWork(FOREIGN_WORK_NAME)
            NotificationManagerCompat.from(context).cancel(NotificationIds.FOREIGN)
        }
    }

    private fun reconcileBudget(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            // Same evening anchor as the pending/foreign reminders.
            val initialDelayMs = millisUntilNextFiring(SummaryCadence.DAILY, EVENING_HOUR, Clock.System.now())
            val request = PeriodicWorkRequestBuilder<BudgetAlertWorker>(
                /* repeatInterval = */ 24, TimeUnit.HOURS,
                /* flexInterval = */ 1, TimeUnit.HOURS,
            )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(BUDGET_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            wm.cancelUniqueWork(BUDGET_WORK_NAME)
            NotificationManagerCompat.from(context).cancel(NotificationIds.BUDGET)
        }
    }

    private fun reconcileSummary(cadence: SummaryCadence, hour: Int) {
        val wm = WorkManager.getInstance(context)
        if (cadence == SummaryCadence.OFF) {
            wm.cancelUniqueWork(SUMMARY_WORK_NAME)
            lastSummarySignature = null
            return
        }
        val (intervalHours, flexHours) = when (cadence) {
            SummaryCadence.DAILY   -> 24L to 1L
            SummaryCadence.WEEKLY  -> (7L * 24) to 6L
            SummaryCadence.MONTHLY -> (30L * 24) to 24L
            SummaryCadence.OFF -> error("filtered above")
        }
        val initialDelayMs = millisUntilNextFiring(cadence, hour, Clock.System.now())
        val request = PeriodicWorkRequestBuilder<SummaryWorker>(
            intervalHours, TimeUnit.HOURS,
            flexHours, TimeUnit.HOURS,
        )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .build()
        val current = SummarySignature(cadence, hour)
        wm.enqueueUniquePeriodicWork(
            SUMMARY_WORK_NAME,
            decideSummaryPolicy(lastSummarySignature, current),
            request,
        )
        lastSummarySignature = current
    }

    private var lastSummarySignature: SummarySignature? = null

    internal data class SummarySignature(val cadence: SummaryCadence, val hour: Int)

    private data class SchedulerSnapshot(
        val pendingEnabled: Boolean,
        val foreignEnabled: Boolean,
        val cadence: SummaryCadence,
        val hour: Int,
        val budgetEnabled: Boolean,
    )

    internal companion object {
        const val PENDING_WORK_NAME = "pending-reminder"
        const val FOREIGN_WORK_NAME = "foreign-currency"
        const val SUMMARY_WORK_NAME = "summary"
        const val BUDGET_WORK_NAME = "budget-alert"
        /** 8pm MYT — evening anchor for pending and foreign reminders. */
        const val EVENING_HOUR = 20

        /**
         * `UPDATE` keeps the existing periodic anchor; `CANCEL_AND_REENQUEUE` resets it.
         * When the user changes the summary cadence or hour, we must re-anchor so the new
         * `setInitialDelay` actually takes effect — otherwise WorkManager silently keeps
         * the old schedule (root cause of ISSUE.md #2). On first emission or unchanged
         * repeats, UPDATE is correct (no-op for the schedule).
         */
        internal fun decideSummaryPolicy(
            last: SummarySignature?,
            current: SummarySignature,
        ): ExistingPeriodicWorkPolicy =
            if (last != null && last != current) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            else ExistingPeriodicWorkPolicy.UPDATE
    }
}
