package cy.txtracker.service

import android.service.notification.NotificationListenerService

/**
 * Stub for the AndroidManifest reference. The actual notification-routing logic is
 * implemented in a later task; the parent class's default no-op behavior is what runs
 * for now. Without this stub the manifest's `<service>` entry would resolve to a
 * missing class and crash the process whenever the system tries to bind it (which
 * happens immediately if notification-listener access was granted to the package
 * on a previous install).
 */
class TxNotificationListener : NotificationListenerService()
