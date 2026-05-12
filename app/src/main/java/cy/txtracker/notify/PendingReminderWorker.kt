package cy.txtracker.notify

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cy.txtracker.data.TransactionRepository
import cy.txtracker.service.NotificationPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Clock

/**
 * Daily-ish periodic worker. Posts a single aggregated notification when the
 * count of `needsVerification = 1 AND createdAt < (now - 24h)` is > 0, unless
 * the user dismissed within the past 12h. Cancels the notification when the
 * count drops back to 0 and clears any cooldown.
 */
@HiltWorker
class PendingReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: TransactionRepository,
    private val prefs: NotificationPrefs,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mgr = NotificationManagerCompat.from(applicationContext)

        if (!prefs.pendingEnabled.value) {
            mgr.cancel(NotificationIds.PENDING)
            return Result.success()
        }

        val now = Clock.System.now()
        val staleCount = repository.countPendingOlderThan(now - 24.hours)

        if (staleCount == 0) {
            mgr.cancel(NotificationIds.PENDING)
            prefs.setPendingDismissedUntil(null)
            return Result.success()
        }

        val dismissedUntil = prefs.pendingDismissedUntil.value
        val cooledDown = dismissedUntil != null && now < dismissedUntil
        if (cooledDown) {
            Log.d(TAG, "Skipping pending notification — cooldown active until $dismissedUntil")
            return Result.success()
        }

        if (!mgr.areNotificationsEnabled()) {
            return Result.success()
        }

        mgr.notify(NotificationIds.PENDING, buildPendingNotification(applicationContext, staleCount))
        return Result.success()
    }

    private companion object {
        const val TAG = "PendingReminderWorker"
    }
}
