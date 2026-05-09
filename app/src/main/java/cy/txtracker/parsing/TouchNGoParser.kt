package cy.txtracker.parsing

import android.service.notification.StatusBarNotification
import cy.txtracker.data.Direction
import javax.inject.Inject
import kotlinx.datetime.Instant

/**
 * Parses Touch 'n Go eWallet payment notifications. Observed format:
 *
 *   `You have paid RM16.00 to V.SHANTHI A/P AVELLAUTHAM`
 *
 * Shape: `You have paid RM<AMOUNT> to <MERCHANT>`
 *
 * Other TnG notifications (reload, received, promos, balance updates) are returned as null
 * — only confirmed outgoing payments get parsed.
 */
class TouchNGoParser @Inject constructor() : NotificationParser {

    override val packageNames: Set<String> = setOf(TNG_PACKAGE)

    override fun parse(sbn: StatusBarNotification): ParsedTransaction? {
        val text = sbn.extractText() ?: return null
        return parseText(
            text = text,
            sourceApp = sbn.packageName,
            postedAt = Instant.fromEpochMilliseconds(sbn.postTime),
        )
    }

    internal fun parseText(text: String, sourceApp: String, postedAt: Instant): ParsedTransaction? {
        val match = PATTERN.matchEntire(text.trim()) ?: return null
        return ParsedTransaction(
            amountMinor = parseRinggitAmountMinor(match.groups["amount"]!!.value),
            currency = "MYR",
            merchantRaw = match.groups["merchant"]!!.value.trim(),
            occurredAt = postedAt,
            sourceApp = sourceApp,
            rawText = text,
            direction = Direction.OUT,
        )
    }

    companion object {
        const val TNG_PACKAGE = "my.com.tngdigital.ewallet"

        private val PATTERN = Regex(
            """^You have paid RM(?<amount>[\d,]+\.\d{2}) to (?<merchant>.+?)\s*$""",
        )
    }
}
