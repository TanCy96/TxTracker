package cy.txtracker.parsing

import android.service.notification.StatusBarNotification
import cy.txtracker.data.Direction
import javax.inject.Inject
import kotlinx.datetime.Instant

/**
 * Parses CIMB credit/debit-card spend notifications. Observed format:
 *
 *   `CIMB:MYR25.00 was charged on your card num 1868 @GRAB RIDES-EC on 08/05.`
 *   `Pls call the num at the back of your card for any queries.`
 *
 * Shape: `CIMB:MYR<AMOUNT> was charged on your card num <LAST4> @<MERCHANT> on <DD/MM>...`
 *
 * The trailing "Pls call..." sentence varies in wording, so the regex consumes anything from
 * the date marker to end-of-string.
 *
 * The CIMB app fires for every charge on the card, so it complements per-source apps like
 * GWallet, Grab, TnG. Cross-source deduping happens when the merchant string from CIMB
 * normalizes the same as the per-source app's merchant — e.g., "CHONG TYRE AUTO" from CIMB and
 * "CHONG TYRE AUTO SVC" from GWallet both reduce to "CHONG TYRE AUTO". Where they don't (Grab
 * vs CIMB's "GRAB RIDES-EC"), the user sees two rows.
 */
class CIMBParser @Inject constructor() : NotificationParser {

    override val packageNames: Set<String> = setOf(
        // CIMB has shipped multiple Android apps over the years. Listing the known variants so
        // the same parser claims any of them; if the user has an unlisted variant the logcat
        // line "Connected. Watching packages: [...]" will show that this one is missing it.
        CIMB_OCTO_PACKAGE,
        CIMB_OCTO_ALT_PACKAGE,
        CIMB_CLICKS_PACKAGE,
    )

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
        const val CIMB_OCTO_PACKAGE = "com.cimb.octo"
        const val CIMB_OCTO_ALT_PACKAGE = "com.cimbbank.octo"
        const val CIMB_CLICKS_PACKAGE = "com.cimbmalaysia"

        private val PATTERN = Regex(
            """^CIMB:MYR(?<amount>[\d,]+\.\d{2})\s+was\s+charged\s+on\s+your\s+card\s+num\s+(?<last4>\d{4})\s+@(?<merchant>.+?)\s+on\s+(?<date>\d{2}/\d{2})\b.*$""",
        )
    }
}
