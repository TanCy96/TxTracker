package cy.txtracker.cloud

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cy.txtracker.data.TransactionRepository
import cy.txtracker.export.BackupExporter
import cy.txtracker.service.CloudSyncPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Performs the upload. Triggered by [CloudSyncScheduler] via WorkManager unique work with a
 * 15-min initial delay and KEEP policy.
 *
 * Pipeline:
 *   1. Pre-upload guard. Compare local row count to [CloudSyncPrefs.lastUploadedRowCount].
 *      If [CloudSyncGuard] returns Skip, stash the reason in prefs and return success.
 *   2. Export the backup to JSON.
 *   3. Upload to a new dated filename.
 *   4. List all backup files; apply [BackupRetentionPolicy]; delete those it returns.
 *      Failures in this step are logged but not propagated — the new upload already succeeded.
 *   5. Update prefs: baseline = currentRowCount, blocked reason = null, lastSync = success.
 */
@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val prefs: CloudSyncPrefs,
    private val backupExporter: BackupExporter,
    private val driveClient: DriveClient,
    private val repository: TransactionRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        execute(
            prefs = prefs,
            backupExporter = backupExporter,
            driveClient = driveClient,
            currentRowCount = repository.countAllTransactions(),
            now = Clock.System.now(),
        )

    companion object {
        private const val TAG = "CloudSyncWorker"

        suspend fun execute(
            prefs: CloudSyncPrefs,
            backupExporter: BackupExporter,
            driveClient: DriveClient,
            currentRowCount: Long,
            now: Instant = Clock.System.now(),
        ): Result {
            if (!prefs.enabled.value || prefs.paused.value) {
                Log.i(TAG, "Bailing: enabled=${prefs.enabled.value} paused=${prefs.paused.value}")
                return Result.success()
            }

            val decision = CloudSyncGuard.evaluate(
                currentRowCount = currentRowCount,
                baselineRowCount = prefs.lastUploadedRowCount.value,
            )
            if (decision is CloudSyncGuard.Decision.Skip) {
                Log.w(TAG, "Guard blocked upload: ${decision.reason}")
                prefs.setSyncBlockedReason(decision.reason)
                prefs.setLastSync(success = false, error = decision.reason)
                // Returning success — the worker did its job; user must intervene.
                return Result.success()
            }

            val json = try {
                backupExporter.exportToJsonString(prefs.transactionCutoff.value)
            } catch (t: Throwable) {
                Log.w(TAG, "Export failed: ${t.message}", t)
                prefs.setLastSync(success = false, error = t.message ?: "Export failed")
                return Result.failure()
            }

            Log.i(TAG, "Uploading ${json.length} bytes (cutoff=${prefs.transactionCutoff.value})")
            val uploadResult = driveClient.uploadDated(json, now)
            if (uploadResult.isFailure) {
                val e = uploadResult.exceptionOrNull()
                val message = e?.message ?: "Upload failed"
                Log.w(TAG, "Upload failed: $message", e)
                prefs.setLastSync(success = false, error = message)
                return when (e) {
                    is TransientNetworkException -> Result.retry()
                    else -> Result.failure()
                }
            }

            // Prune older files. Failures here are non-fatal: the new upload is already safe.
            try {
                val all = driveClient.listAll().getOrThrow()
                val toDelete = BackupRetentionPolicy.selectToDelete(all, now)
                for (id in toDelete) {
                    driveClient.delete(id).onFailure { e ->
                        Log.w(TAG, "Failed to prune $id: ${e.message}", e)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Prune step failed (upload still successful): ${t.message}", t)
            }

            prefs.setLastUploadedRowCount(currentRowCount)
            prefs.setSyncBlockedReason(null)
            prefs.setLastSync(success = true, error = null)
            return Result.success()
        }
    }
}
