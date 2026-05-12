package cy.txtracker.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cy.txtracker.service.NotificationPrefs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Clock

/**
 * Fired when the user swipes the pending notification away. Suppresses
 * re-notify for 12h so the worker doesn't immediately repost on its next run.
 * Registered in the manifest as android:exported="false" in Task 7.1.
 */
@AndroidEntryPoint
class PendingDismissReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: NotificationPrefs

    override fun onReceive(context: Context, intent: Intent) {
        prefs.setPendingDismissedUntil(Clock.System.now() + 12.hours)
    }
}
