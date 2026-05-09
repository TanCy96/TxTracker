package cy.txtracker.parsing

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Parses payment notifications from a specific source app (Google Wallet, a bank app, etc.).
 *
 * Each implementation owns the regex/format for its source and is registered in the parsing
 * Hilt module via `@IntoSet`. The listener iterates the set and dispatches by package name.
 */
interface NotificationParser {
    /** Package names this parser claims responsibility for. */
    val packageNames: Set<String>

    /** Returns a parsed transaction, or null if the notification doesn't match a known shape. */
    fun parse(sbn: StatusBarNotification): ParsedTransaction?
}

/**
 * Extracts the most informative text payload from a notification. Tries `EXTRA_BIG_TEXT` first
 * (the expanded body), then falls back to `EXTRA_TEXT`. Returns null if neither is present.
 */
internal fun StatusBarNotification.extractText(): String? {
    val extras = notification?.extras ?: return null
    val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
    val plain = extras.getCharSequence(Notification.EXTRA_TEXT)
    return (big ?: plain)?.toString()?.takeIf { it.isNotBlank() }
}
