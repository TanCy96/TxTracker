package cy.txtracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import cy.txtracker.notify.NotificationChannels
import cy.txtracker.notify.NotificationScheduler
import cy.txtracker.ui.lock.LockState
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TxApp : Application(), Configuration.Provider {

    @Inject lateinit var lockState: LockState
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var cloudSyncScheduler: cy.txtracker.cloud.CloudSyncScheduler
    @Inject lateinit var notificationScheduler: NotificationScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        NotificationChannels.registerAll(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = lockState.onForegrounded()
                override fun onStop(owner: LifecycleOwner) = lockState.onBackgrounded()
            },
        )
        cloudSyncScheduler.start()
        notificationScheduler.start(ProcessLifecycleOwner.get().lifecycleScope)
    }
}
