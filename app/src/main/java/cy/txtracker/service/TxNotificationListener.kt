package cy.txtracker.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import cy.txtracker.data.TrackedCurrencyDao
import cy.txtracker.data.TransactionRepository
import cy.txtracker.parsing.HeuristicExtractor
import cy.txtracker.parsing.PermissiveExtractor
import cy.txtracker.parsing.SourcePackages
import cy.txtracker.parsing.extractText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Captures notifications from registered payment apps, runs them through the heuristic
 * and permissive extractors, and hands the parsed result to [TxIngestor] for normalization,
 * deduping, and persistence.
 *
 * The system invokes [onNotificationPosted] on the listener thread; DB writes are
 * offloaded to an IO scope owned by this service so the system thread doesn't
 * block on disk.
 *
 * Group-summary notifications and notifications from non-allowlisted packages are
 * ignored cheaply. Extractor exceptions are logged but never propagate — a malformed
 * bank notification must not take down the listener for everything else.
 *
 * The user-controllable [CapturePrefs.captureAllPackages] toggle bypasses the allowlist
 * for discovery; on by default for new installs.
 */
@AndroidEntryPoint
class TxNotificationListener : NotificationListenerService() {

    @Inject lateinit var heuristicExtractor: HeuristicExtractor
    @Inject lateinit var permissiveExtractor: PermissiveExtractor
    @Inject lateinit var ingestor: TxIngestor
    @Inject lateinit var capturePrefs: CapturePrefs
    @Inject lateinit var repository: TransactionRepository
    @Inject lateinit var trackedCurrencyDao: TrackedCurrencyDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Packages the listener processes when capture-all is off. Notifications outside this
     * set bail at the top of [onNotificationPosted] without doing any work.
     * Refreshed reactively via [watchedPackagesJob] whenever [ApprovedSource] rows change.
     */
    @Volatile
    private var watchedPackages: Set<String> = SourcePackages.PERMISSIVE_PACKAGES

    private var watchedPackagesJob: Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        watchedPackagesJob?.cancel()
        watchedPackagesJob = scope.launch {
            repository.observeApprovedSourcePackages().collect { approved ->
                watchedPackages = SourcePackages.PERMISSIVE_PACKAGES + approved
                Log.i(TAG, "Watching ${watchedPackages.size} packages " +
                    "(${SourcePackages.PERMISSIVE_PACKAGES.size} default + ${approved.size} approved).")
            }
        }
        if (capturePrefs.captureAllPackages.value) {
            Log.w(TAG, "Capture-all-packages is ON — every package's notifications will be processed.")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Disconnected. Requesting rebind.")
        // Some OEM ROMs aggressively kill notification listeners; a rebind request
        // gets us reconnected as soon as the system is willing.
        requestRebind(ComponentName(this, TxNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val bypassAllowlist = capturePrefs.captureAllPackages.value

        // Cheap filter: bail unless this package is on the finance-app allowlist (or the
        // user has turned on capture-all-packages for discovery).
        if (!bypassAllowlist && sbn.packageName !in watchedPackages) return

        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "Skipping group summary from ${sbn.packageName}")
            return
        }

        val text = sbn.extractText()
        val postedAt = Instant.fromEpochMilliseconds(sbn.postTime)

        scope.launch {
            try {
                val symbolDefaults = trackedCurrencyDao.getDefaultsForSymbol()
                    .associate { it.displaySymbol to it.code }

                // 1. Heuristic extractor for any watched package. Verb+recipient or
                //    card-spend shape. Lands as Pending so the user can verify.
                val heuristic = text?.let {
                    heuristicExtractor.extract(it, sbn.packageName, postedAt, symbolDefaults)
                }
                if (heuristic != null) {
                    insert(heuristic, sbn.packageName, needsVerification = true)
                    return@launch
                }

                // 2. Permissive last-resort capture: anything with an RM/MYR amount lands
                //    as Pending so the user reviews and confirms or deletes.
                val permissive = text?.let {
                    permissiveExtractor.extract(
                        text = it,
                        sourceApp = sbn.packageName,
                        postedAt = postedAt,
                        bypassAllowlist = bypassAllowlist,
                        symbolDefaults = symbolDefaults,
                    )
                }
                if (permissive != null) {
                    insert(permissive, sbn.packageName, needsVerification = true)
                    return@launch
                }

                // 3. Nothing matched even at the permissive layer (no amount in the text).
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
