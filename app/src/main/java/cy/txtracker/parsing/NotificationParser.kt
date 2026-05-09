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
 * Returns the text payload most likely to contain the full payment shape, in priority order:
 *
 *   1. `EXTRA_BIG_TEXT` — the expanded body. Most payment apps put the full sentence here.
 *   2. `EXTRA_TEXT` joined with `EXTRA_TITLE` — used when an app splits the payment string
 *      across title and body (some bank apps do this).
 *   3. `EXTRA_TEXT` alone, or `EXTRA_TITLE` alone, as last resorts.
 *
 * Returns null if every field is empty.
 */
internal fun StatusBarNotification.extractText(): String? {
    val extras = notification?.extras ?: return null
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

    return when {
        big.isNotEmpty() -> big
        title.isNotEmpty() && text.isNotEmpty() -> "$title $text"
        text.isNotEmpty() -> text
        title.isNotEmpty() -> title
        else -> null
    }
}
