package cy.txtracker.cloud

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cy.txtracker.export.BackupExporter
import cy.txtracker.service.CloudSyncPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Performs the actual upload. Triggered by [CloudSyncScheduler] via WorkManager unique
 * work with a 15-minute initial delay and REPLACE policy (trailing-edge debounce).
 *
 * Maps [CloudSyncException] flavors:
 *   - [TransientNetworkException] → Result.retry (WorkManager exponential backoff)
 *   - [AuthExpiredException] / [NotSignedInException] → Result.failure (user action req.)
 *   - Other → Result.failure with the message surfaced via [CloudSyncPrefs.setLastSync]
 */
@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val prefs: CloudSyncPrefs,
    private val backupExporter: BackupExporter,
    private val driveClient: DriveClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        execute(prefs, backupExporter, driveClient)

    companion object {
        /** Pure function used by both production [doWork] and unit tests. Decoupled from
         *  Context/WorkerParameters so it's trivially testable. */
        suspend fun execute(
            prefs: CloudSyncPrefs,
            backupExporter: BackupExporter,
            driveClient: DriveClient,
        ): Result {
            if (!prefs.enabled.value || prefs.paused.value) return Result.success()

            val json = try {
                backupExporter.exportToJsonString(prefs.transactionCutoff.value)
            } catch (t: Throwable) {
                prefs.setLastSync(success = false, error = t.message ?: "Export failed")
                return Result.failure()
            }

            val uploadResult = driveClient.upload(json)
            return uploadResult.fold(
                onSuccess = {
                    prefs.setLastSync(success = true, error = null)
                    Result.success()
                },
                onFailure = { e ->
                    val message = e.message ?: "Upload failed"
                    prefs.setLastSync(success = false, error = message)
                    when (e) {
                        is TransientNetworkException -> Result.retry()
                        is AuthExpiredException, is NotSignedInException -> Result.failure()
                        else -> Result.failure()
                    }
                },
            )
        }
    }
}
