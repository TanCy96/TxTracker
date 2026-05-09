package cy.txtracker.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import cy.txtracker.parsing.NotificationParser
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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val parser = parserByPackage[sbn.packageName] ?: return

        scope.launch {
            try {
                parser.parse(sbn)?.let { ingestor.ingest(it) }
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
