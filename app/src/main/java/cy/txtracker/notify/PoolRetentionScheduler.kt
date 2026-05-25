package cy.txtracker.notify

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoolRetentionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        val request = PeriodicWorkRequestBuilder<PoolRetentionWorker>(
            24, TimeUnit.HOURS,
            1, TimeUnit.HOURS,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "pool-retention"
    }
}
