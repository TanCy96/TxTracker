package cy.txtracker.parsing

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Shared helpers used by the heuristic and permissive extractors.
 *
 * (Historically this file also defined a `NotificationParser` interface that strict
 * per-source parsers implemented. Those parsers were retired in favor of the
 * heuristic+permissive pipeline; only the text/amount helpers remain.)
 */

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

/**
 * "16.00" → 1600, "1,234.56" → 123456, "1" → 100 (no decimals → assume two-decimal
 * minor unit, common in foreign-currency-without-cents bank notifications).
 */
internal fun parseAmountMinor(raw: String): Long {
    val cleaned = raw.replace(",", "")
    val dotIdx = cleaned.indexOf('.')
    return if (dotIdx == -1) {
        // No decimals — multiply by 100 to land in minor units.
        cleaned.toLong() * 100
    } else {
        val whole = cleaned.substring(0, dotIdx)
        val fraction = cleaned.substring(dotIdx + 1).padEnd(2, '0').take(2)
        whole.toLong() * 100 + fraction.toLong()
    }
}
