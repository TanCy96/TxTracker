package cy.txtracker.parsing

import android.service.notification.StatusBarNotification
import cy.txtracker.data.Direction
import javax.inject.Inject
import kotlinx.datetime.Instant

/**
 * Parses Google Wallet payment notifications. Observed format (Malaysia, MasterCard via Wallet):
 *
 *   `CHONG TYRE AUTO SVC RM530.00 with CIMB Cash Rebate Plat MasterCard **1868`
 *
 * Shape: `<MERCHANT> RM<AMOUNT> with <CARD_NAME> **<LAST4>`
 */
class GoogleWalletParser @Inject constructor() : NotificationParser {

    override val packageNames: Set<String> = setOf(GOOGLE_WALLET_PACKAGE)

    override fun parse(sbn: StatusBarNotification): ParsedTransaction? {
        val text = sbn.extractText() ?: return null
        return parseText(
            text = text,
            sourceApp = sbn.packageName,
            postedAt = Instant.fromEpochMilliseconds(sbn.postTime),
        )
    }

    /**
     * Pure-text variant: the testable path. Both the production parse() and unit tests funnel
     * through here so the regex stays the only place that knows the notification format.
     */
    internal fun parseText(text: String, sourceApp: String, postedAt: Instant): ParsedTransaction? {
        val match = PATTERN.matchEntire(text.trim()) ?: return null
        val merchant = match.groups["merchant"]!!.value.trim()
        val amountMinor = parseAmountMinor(match.groups["amount"]!!.value)
        return ParsedTransaction(
            amountMinor = amountMinor,
            currency = "MYR",
            merchantRaw = merchant,
            occurredAt = postedAt,
            sourceApp = sourceApp,
            rawText = text,
            direction = Direction.OUT,
        )
    }

    private fun parseAmountMinor(raw: String): Long {
        // "1,234.56" → "123456". Regex guarantees exactly two decimal places.
        val digitsOnly = raw.replace(",", "").replace(".", "")
        return digitsOnly.toLong()
    }

    companion object {
        const val GOOGLE_WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"

        // (?<merchant>.+?) — lazy: stops at the first " RM<digits>." it can find.
        // Currency is always "RM" for Malaysian Wallet notifications.
        // Card name is allowed to contain anything until the trailing " **<4 digits>".
        private val PATTERN = Regex(
            """^(?<merchant>.+?)\s+RM(?<amount>[\d,]+\.\d{2})\s+with\s+(?<card>.+?)\s+\*+(?<last4>\d{4})\s*$""",
        )
    }
}
