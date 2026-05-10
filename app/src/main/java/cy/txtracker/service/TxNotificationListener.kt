package cy.txtracker.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import cy.txtracker.parsing.HeuristicExtractor
import cy.txtracker.parsing.NotificationParser
import cy.txtracker.parsing.PermissiveExtractor
import cy.txtracker.parsing.extractText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

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
    @Inject lateinit var heuristicExtractor: HeuristicExtractor
    @Inject lateinit var permissiveExtractor: PermissiveExtractor
    @Inject lateinit var ingestor: TxIngestor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val parserByPackage: Map<String, NotificationParser> by lazy {
        parsers.flatMap { p -> p.packageNames.map { it to p } }.toMap()
    }

    /**
     * Union of every package the listener cares about: those with a strict parser plus
     * the permissive allowlist. Notifications outside this set bail at the top of
     * [onNotificationPosted] without doing any work.
     */
    private val watchedPackages: Set<String> by lazy {
        parserByPackage.keys + PermissiveExtractor.PERMISSIVE_PACKAGES
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Connected. Strict parsers cover: ${parserByPackage.keys}")
        Log.i(TAG, "Permissive coverage extends to ${watchedPackages.size} total packages.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Disconnected. Requesting rebind.")
        // Some OEM ROMs aggressively kill notification listeners; a rebind request
        // gets us reconnected as soon as the system is willing.
        requestRebind(ComponentName(this, TxNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Cheap filter: bail unless we either have a strict parser for this package OR it's
        // on the permissive finance-app allowlist. Random app notifications never get here.
        if (sbn.packageName !in watchedPackages) return

        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "Skipping group summary from ${sbn.packageName}")
            return
        }

        val text = sbn.extractText()
        val postedAt = Instant.fromEpochMilliseconds(sbn.postTime)

        scope.launch {
            try {
                // 1. Strict per-source parser, when one claims this package. High-confidence.
                parserByPackage[sbn.packageName]?.parse(sbn)?.let { strict ->
                    insert(strict, sbn.packageName, needsVerification = false)
                    return@launch
                }

                // 2. Heuristic fallback for any watched package. Lower confidence.
                val heuristic = text?.let {
                    heuristicExtractor.extract(it, sbn.packageName, postedAt)
                }
                if (heuristic != null) {
                    insert(heuristic, sbn.packageName, needsVerification = true)
                    return@launch
                }

                // 3. Permissive last-resort capture for finance-app packages: anything with
                //    an RM/MYR amount, even without a verb or recipient. Lands as Pending so
                //    the user reviews and confirms or deletes.
                val permissive = text?.let {
                    permissiveExtractor.extract(it, sbn.packageName, postedAt)
                }
                if (permissive != null) {
                    insert(permissive, sbn.packageName, needsVerification = true)
                    return@launch
                }

                // 4. Nothing matched even at the permissive layer (no amount in the text).
                //    Log a preview for diagnostics if anyone hooks up logcat later.
                val preview = text?.take(220)?.replace('\n', ' ')
                Log.i(
                    TAG,
                    "No amount detected for ${sbn.packageName}. text='${preview ?: "<empty>"}'",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to handle notification from ${sbn.packageName}", t)
            }
        }
    }

    private suspend fun insert(
        parsed: cy.txtracker.parsing.ParsedTransaction,
        packageName: String,
        needsVerification: Boolean,
    ) {
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
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TxNotificationListener"
    }
}
