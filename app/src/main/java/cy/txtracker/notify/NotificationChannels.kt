package cy.txtracker.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Registers the three notification channels on every cold start. Idempotent —
 * Android dedupes by channel id. Called from [cy.txtracker.TxApp.onCreate].
 *
 * All three channels are registered eagerly. FOREIGN has no producer on this
 * branch (the FX-branch wires its worker into this channel later); declaring
 * it here means the merge needs no channel-registration migration and lets
 * the user pre-mute it via system settings.
 */
object NotificationChannels {
    const val PENDING = "txtracker.pending"
    const val FOREIGN = "txtracker.foreign"
    const val SUMMARY = "txtracker.summary"
    const val BUDGET = "txtracker.budget"

    fun registerAll(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                PENDING, "Pending verification", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Reminders that captured transactions are waiting for review."
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                FOREIGN, "Foreign currency setup", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Prompts to start a trip when foreign-currency activity is detected."
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                SUMMARY, "Spending summaries", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Periodic recap of how much you've spent."
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                BUDGET, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts when you approach or exceed a monthly budget."
            },
        )
    }
}
