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
        ) { pending, foreign, cadence, hour ->
            SchedulerSnapshot(pending, foreign, cadence, hour)
        }
            .onEach { snapshot ->
                reconcilePending(snapshot.pendingEnabled)
                reconcileForeign(snapshot.foreignEnabled)
                reconcileSummary(snapshot.cadence, snapshot.hour)
            }
            .launchIn(scope)
    }

    private fun reconcilePending(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<PendingReminderWorker>(
                /* repeatInterval = */ 24, TimeUnit.HOURS,
                /* flexInterval = */ 1, TimeUnit.HOURS,
            ).build()
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
            val request = PeriodicWorkRequestBuilder<ForeignCurrencyWorker>(
                /* repeatInterval = */ 24, TimeUnit.HOURS,
                /* flexInterval = */ 1, TimeUnit.HOURS,
            ).build()
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

    private fun reconcileSummary(cadence: SummaryCadence, hour: Int) {
        val wm = WorkManager.getInstance(context)
        if (cadence == SummaryCadence.OFF) {
            wm.cancelUniqueWork(SUMMARY_WORK_NAME)
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
        wm.enqueueUniquePeriodicWork(
            SUMMARY_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private data class SchedulerSnapshot(
        val pendingEnabled: Boolean,
        val foreignEnabled: Boolean,
        val cadence: SummaryCadence,
        val hour: Int,
    )

    private companion object {
        const val PENDING_WORK_NAME = "pending-reminder"
        const val FOREIGN_WORK_NAME = "foreign-currency"
        const val SUMMARY_WORK_NAME = "summary"
    }
}
