package cy.txtracker.cloud

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cy.txtracker.data.TxDatabase
import cy.txtracker.service.CloudSyncPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes Room's [InvalidationTracker] on the backup-relevant tables and re-arms a
 * single unique WorkManager job 15 minutes after every change.
 *
 * The same `WORK_NAME` is used for the manual "Sync now" path with no initial delay;
 * tapping "Sync now" cancels any pending debounce and runs immediately.
 *
 * Started once from [cy.txtracker.TxApp.onCreate].
 */
@Singleton
class CloudSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TxDatabase,
    private val prefs: CloudSyncPrefs,
) {
    fun start() {
        val observer = object : InvalidationTracker.Observer(WATCHED_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                if (!prefs.enabled.value || prefs.paused.value) return
                enqueueDebouncedUpload()
            }
        }
        database.invalidationTracker.addObserver(observer)
    }

    /**
     * Enqueues a 15-minute-delayed upload with KEEP policy: if a worker is already
     * pending or running for the same unique name, this is a no-op. KEEP (leading-edge
     * debounce) replaces the previous REPLACE-based trailing-edge design because:
     *   1. Leading-edge has the same effect — the worker reads current DB state when it
     *      runs, not when it was scheduled, so changes that arrive during the wait window
     *      are uploaded on the same scheduled run.
     *   2. REPLACE was cancelling the user-initiated immediate Sync now job when any DB
     *      write happened mid-upload, leaving the status as if the upload never ran.
     */
    fun enqueueDebouncedUpload() {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(NETWORK_CONNECTED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.KEEP, request,
        )
    }

    /** Used by "Sync now": runs the worker immediately, cancelling any pending debounce. */
    fun enqueueImmediateUpload() {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(NETWORK_CONNECTED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.REPLACE, request,
        )
    }

    /** Cancels any pending or running upload. Used on sign-out. */
    fun cancelPending() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "cloud-sync-upload"
        private val WATCHED_TABLES = arrayOf(
            "transactions",
            "categories",
            "merchant_mappings",
            "merchant_description_mappings",
            "category_description_mappings",
            "merchant_notes",
            "user_facing_sources",
            "approved_sources",
            "funding_sources",
        )
        private val NETWORK_CONNECTED: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
