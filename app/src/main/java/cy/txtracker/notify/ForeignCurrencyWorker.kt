package cy.txtracker.notify

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cy.txtracker.data.TransactionRepository
import cy.txtracker.service.CurrencyPrefs
import cy.txtracker.service.NotificationPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock

/**
 * Daily-ish periodic worker. Posts a single foreign-currency notification when
 * at least one parked currency-review row exists for a non-MYR, non-UNKNOWN
 * currency that the user hasn't muted (either via the per-currency Home banner
 * dismissal in [CurrencyPrefs] or via the 12h notification cooldown in
 * [NotificationPrefs]). Cancels the notification when the queue clears.
 *
 * When multiple currencies have parked rows, the worker surfaces only the
 * first one. Once the user starts a trip for that currency, the rows promote
 * out of the parked stream and the next worker run surfaces the next currency.
 */
@HiltWorker
class ForeignCurrencyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: TransactionRepository,
    private val prefs: NotificationPrefs,
    private val currencyPrefs: CurrencyPrefs,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mgr = NotificationManagerCompat.from(applicationContext)

        if (!prefs.foreignEnabled.value) {
            mgr.cancel(NotificationIds.FOREIGN)
            return Result.success()
        }

        val now = Clock.System.now()
        val cooldownUntil = prefs.foreignDismissedUntil.value
        if (cooldownUntil != null && now < cooldownUntil) {
            Log.d(TAG, "Skipping foreign notification — cooldown active until $cooldownUntil")
            return Result.success()
        }

        // First eligible currency: not MYR, not UNKNOWN, not in the in-app
        // banner-dismissal set. Picking the first matches the in-app banner's
        // policy (one currency surfaced at a time).
        val parkedRows = repository.observeCurrencyReviewTransactions().first()
        val dismissedCurrencies = currencyPrefs.dismissed.value
        val target = parkedRows
            .filter {
                it.currency != "MYR" &&
                    it.currency != "UNKNOWN" &&
                    it.currency !in dismissedCurrencies
            }
            .groupBy { it.currency }
            .entries
            .firstOrNull()

        if (target == null) {
            mgr.cancel(NotificationIds.FOREIGN)
            prefs.setForeignDismissedUntil(null)
            return Result.success()
        }

        if (!mgr.areNotificationsEnabled()) {
            return Result.success()
        }

        mgr.notify(
            NotificationIds.FOREIGN,
            buildForeignNotification(
                context = applicationContext,
                currency = target.key,
                count = target.value.size,
            ),
        )
        return Result.success()
    }

    private companion object {
        const val TAG = "ForeignCurrencyWorker"
    }
}
