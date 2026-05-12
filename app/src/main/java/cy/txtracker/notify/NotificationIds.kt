package cy.txtracker.notify

/**
 * Stable ids posted to NotificationManager. Centralised so cancel() and
 * notify() in different files can't drift. FOREIGN is reserved for the
 * FX-branch producer — declared here so the FX merge needs no migration.
 */
object NotificationIds {
    const val PENDING = 1001
    const val FOREIGN = 1002
    const val SUMMARY = 1003
}
