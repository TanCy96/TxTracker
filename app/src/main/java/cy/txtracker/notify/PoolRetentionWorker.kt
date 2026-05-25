package cy.txtracker.notify

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cy.txtracker.data.TransactionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Clock

@HiltWorker
class PoolRetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: TransactionRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        repository.deleteRejectedPoolEntriesBefore(Clock.System.now() - 30.days)
        return Result.success()
    }
}
