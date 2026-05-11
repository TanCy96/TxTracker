package cy.txtracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import cy.txtracker.ui.lock.LockState
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TxApp : Application(), Configuration.Provider {

    @Inject lateinit var lockState: LockState
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Process-level lifecycle: ON_START fires when the first activity comes to the
        // foreground, ON_STOP when the last one goes to the background. Tracks whole-app
        // foreground/background transitions, not per-activity rotations.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = lockState.onForegrounded()
                override fun onStop(owner: LifecycleOwner) = lockState.onBackgrounded()
            },
        )
    }
}
