package cy.txtracker.parsing

import android.service.notification.StatusBarNotification
import cy.txtracker.data.Direction
import javax.inject.Inject
import kotlinx.datetime.Instant

/**
 * Parses Grab passenger payment notifications. Observed format:
 *
 *   `Your Mastercard 1868 has been charged RM 25.00 for booking A-9AK6JSBWXF8SAV`
 *
 * Shape: `Your <CARD> <LAST4> has been charged RM <AMOUNT> for booking <BOOKING_ID>`
 *
 * Notes:
 *   - The amount may have a space after RM ("RM 25.00") unlike GWallet ("RM530.00").
 *   - The notification doesn't carry a real merchant name; we report `merchantRaw = "GRAB"` so
 *     the keyword rule classifies it as Transport and rows display tidily as "GRAB". The booking
 *     id is preserved in `rawText` for debugging.
 *   - When CIMB also fires for the same ride, it produces a different merchant string
 *     ("GRAB RIDES-EC") that does NOT collapse with this one through the dedupe key. v1
 *     accepts the duplicate; the user can disable one source if it bothers them.
 */
class GrabParser @Inject constructor() : NotificationParser {

    override val packageNames: Set<String> = setOf(GRAB_PACKAGE)

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
            merchantRaw = "GRAB",
            occurredAt = postedAt,
            sourceApp = sourceApp,
            rawText = text,
            direction = Direction.OUT,
        )
    }

    companion object {
        const val GRAB_PACKAGE = "com.grabtaxi.passenger"

        // Card label is a lazy `.+?` to allow multi-word names like "Cash Back Mastercard".
        // Amount may have an optional space after RM. Booking id is whatever follows
        // " for booking " up to end-of-string.
        private val PATTERN = Regex(
            """^Your\s+(?<card>.+?)\s+(?<last4>\d{4})\s+has\s+been\s+charged\s+RM\s*(?<amount>[\d,]+\.\d{2})\s+for\s+booking\s+(?<booking>\S+)\s*$""",
        )
    }
}
