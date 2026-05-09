package cy.txtracker.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import cy.txtracker.parsing.NotificationParser
import cy.txtracker.parsing.extractText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Captures notifications from registered payment apps, dispatches each to the
 * [NotificationParser] that claims its package, and hands the parsed result to
 * [TxIngestor] for normalization, deduping, and persistence.
 *
 * The system invokes [onNotificationPosted] on the listener thread; DB writes are
 * offloaded to an IO scope owned by this service so the system thread doesn't
 * block on disk.
 *
 * Group-summary notifications and notifications from unregistered packages are
 * ignored cheaply with no parser invocation. Parser exceptions are logged but
 * never propagate — a malformed bank notification must not take down the listener
 * for everything else.
 */
@AndroidEntryPoint
class TxNotificationListener : NotificationListenerService() {

    @Inject lateinit var parsers: Set<@JvmSuppressWildcards NotificationParser>
    @Inject lateinit var ingestor: TxIngestor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val parserByPackage: Map<String, NotificationParser> by lazy {
        parsers.flatMap { p -> p.packageNames.map { it to p } }.toMap()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Connected. Watching packages: ${parserByPackage.keys}")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Disconnected. Requesting rebind.")
        // Some OEM ROMs aggressively kill notification listeners; a rebind request
        // gets us reconnected as soon as the system is willing.
        requestRebind(ComponentName(this, TxNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Cheap filter: ignore packages we don't have a parser for. Most notifications on
        // the device fall here, so we exit before doing any extra work.
        val parser = parserByPackage[sbn.packageName] ?: return

        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "Skipping group summary from ${sbn.packageName}")
            return
        }

        scope.launch {
            try {
                val parsed = parser.parse(sbn)
                if (parsed == null) {
                    val preview = sbn.extractText()?.take(220)?.replace('\n', ' ')
                    Log.i(
                        TAG,
                        "Parser returned null for ${sbn.packageName}. text='${preview ?: "<empty>"}'",
                    )
                    return@launch
                }
                val rowId = ingestor.ingest(parsed)
                if (rowId != null) {
                    Log.i(
                        TAG,
                        "Inserted row $rowId from ${sbn.packageName}: " +
                            "merchant='${parsed.merchantRaw}' amountMinor=${parsed.amountMinor}",
                    )
                } else {
                    Log.i(
                        TAG,
                        "Dropped on dedupe from ${sbn.packageName}: " +
                            "merchant='${parsed.merchantRaw}' amountMinor=${parsed.amountMinor}",
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to handle notification from ${sbn.packageName}", t)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TxNotificationListener"
    }
}
