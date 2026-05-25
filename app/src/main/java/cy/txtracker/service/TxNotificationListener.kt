package cy.txtracker.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import cy.txtracker.data.TrackedCurrencyDao
import cy.txtracker.data.TransactionRepository
import cy.txtracker.parsing.extractText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Captures notifications, runs them through the capture pipeline, and writes either a
 * transaction or a reviewable pool entry.
 *
 * The system invokes [onNotificationPosted] on the listener thread; DB writes are
 * offloaded to an IO scope owned by this service so the system thread does not block on disk.
 *
 * Group-summary notifications are ignored cheaply. Extractor exceptions are logged but never
 * propagate; a malformed notification must not take down the listener for everything else.
 */
@AndroidEntryPoint
class TxNotificationListener : NotificationListenerService() {

    @Inject lateinit var ingestor: TxIngestor
    @Inject lateinit var repository: TransactionRepository
    @Inject lateinit var trackedCurrencyDao: TrackedCurrencyDao
    @Inject lateinit var rewriteEngine: NotificationRewriteEngine
    @Inject lateinit var capturePipeline: CapturePipeline

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Disconnected. Requesting rebind.")
        requestRebind(ComponentName(this, TxNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "Skipping group summary from ${sbn.packageName}")
            return
        }

        val rawText = sbn.extractText() ?: return
        val postedAt = Instant.fromEpochMilliseconds(sbn.postTime)

        scope.launch {
            try {
                val rewritten = rewriteEngine.apply(sbn.packageName, rawText)
                val symbolDefaults = trackedCurrencyDao.getDefaultsForSymbol()
                    .associate { it.displaySymbol to it.code }

                when (val decision = capturePipeline.decide(
                    packageName = sbn.packageName,
                    rawText = rawText,
                    rewrittenText = rewritten,
                    postedAt = postedAt,
                    symbolDefaults = symbolDefaults,
                    capturedAt = Clock.System.now(),
                )) {
                    is CaptureDecision.Parsed -> {
                        val rowId = insert(
                            parsed = decision.parsed,
                            packageName = sbn.packageName,
                            needsVerification = true,
                        )
                        if (rowId != null) {
                            repository.trackPackage(sbn.packageName)
                        }
                    }
                    is CaptureDecision.Pooled -> {
                        val rowId = repository.insertCapturedNotification(
                            packageName = decision.packageName,
                            postedAt = decision.postedAt,
                            amountMinor = decision.amountMinor,
                            currency = decision.currency,
                            rawText = decision.rawText,
                            rewrittenText = decision.rewrittenText,
                            now = decision.capturedAt,
                        )
                        Log.i(
                            TAG,
                            "Pooled row ${rowId ?: "<deduped>"} from ${decision.packageName}: " +
                                "amountMinor=${decision.amountMinor}",
                        )
                    }
                    CaptureDecision.Dropped -> {
                        val preview = rewritten.take(220).replace('\n', ' ')
                        Log.i(TAG, "No amount detected for ${sbn.packageName}. text='$preview'")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to handle notification from ${sbn.packageName}", t)
            }
        }
    }

    private suspend fun insert(
        parsed: cy.txtracker.parsing.ParsedTransaction,
        packageName: String,
        needsVerification: Boolean,
    ): Long? {
        val rowId = ingestor.ingest(parsed, needsVerification = needsVerification)
        val tag = if (needsVerification) "Inserted (PENDING)" else "Inserted"
        if (rowId != null) {
            Log.i(
                TAG,
                "$tag row $rowId from $packageName: " +
                    "merchant='${parsed.merchantRaw}' amountMinor=${parsed.amountMinor}",
            )
        } else {
            Log.i(
                TAG,
                "Dropped on dedupe from $packageName: " +
                    "merchant='${parsed.merchantRaw}' amountMinor=${parsed.amountMinor}",
            )
        }
        return rowId
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TxNotificationListener"
    }
}
