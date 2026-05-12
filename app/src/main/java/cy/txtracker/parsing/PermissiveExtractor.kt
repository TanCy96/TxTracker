package cy.txtracker.parsing

import cy.txtracker.data.Direction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

/**
 * Last-resort capture for notifications from known finance-app packages where the
 * heuristic has failed.
 *
 * Triggers when:
 *   - The source app is in [SourcePackages.PERMISSIVE_PACKAGES] (or [bypassAllowlist]
 *     is true), AND
 *   - The notification text contains *any* `RM` or `MYR` amount.
 *
 * Doesn't try to extract a generic merchant. The captured row uses a source-app label
 * as the merchant ("GWallet (review)", "CIMB (review)", …) and stores the full notification
 * text in `rawText`. The user reviews the row from the home Pending filter.
 *
 * Per-source exceptions live in [merchantFor]. Grab is the one currently — every Grab
 * notification refers to "Grab" as the counterparty, so we commit to `"GRAB"` directly.
 *
 * Keeping the amount requirement matters — without it, a "Welcome to Wallet" notification
 * would create a phantom RM 0.00 row.
 */
@Singleton
class PermissiveExtractor @Inject constructor() {

    /**
     * Builds a `ParsedTransaction` from any text with an RM/MYR amount.
     *
     * @param bypassAllowlist when true, skips the [SourcePackages.PERMISSIVE_PACKAGES]
     *   check so unknown packages can be processed. The listener passes the user's
     *   "capture all packages" Settings toggle here.
     */
    fun extract(
        text: String,
        sourceApp: String,
        postedAt: Instant,
        bypassAllowlist: Boolean = false,
    ): ParsedTransaction? {
        if (!bypassAllowlist && sourceApp !in SourcePackages.PERMISSIVE_PACKAGES) return null
        if (text.isBlank()) return null
        val match = AMOUNT.find(text) ?: return null

        return ParsedTransaction(
            amountMinor = parseAmountMinor(match.groups["amount"]!!.value),
            currency = "MYR",
            merchantRaw = merchantFor(sourceApp, text),
            occurredAt = postedAt,
            sourceApp = sourceApp,
            rawText = text,
            direction = Direction.OUT,
        )
    }

    /**
     * Resolves the merchant string for a permissive capture.
     *
     * Most apps don't expose a real merchant in notification text we can recover here, so
     * we use a `"<Source> (review)"` placeholder and let the user fix it from the Pending
     * list.
     *
     * Grab is a deliberate exception: every Grab booking notification refers to "Grab" as
     * the counterparty (the actual driver / restaurant / merchant isn't named in the push),
     * so we commit to `"GRAB"` directly and skip the review suffix.
     */
    private fun merchantFor(sourceApp: String, text: String): String {
        if (sourceApp == SourcePackages.GRAB) return grabMerchantFrom(text)
        return "${sourceLabel(sourceApp)} (review)"
    }

    /**
     * Resolves the Grab sub-product label from a booking ID inside the notification text.
     * Two observed formats today:
     *   - `A-<alphanumeric>`  → Grab Car
     *   - `<digits>-<alphanumeric>` → Grab Food
     * Anything else falls back to the bare "GRAB" label so a future product (Grab Mart,
     * Grab Express, …) isn't mislabeled — the user can re-label from the Pending list.
     *
     * If the notification text doesn't carry a "for booking <ID>" phrase at all (e.g.,
     * Grab changes wording), also falls back to "GRAB".
     */
    private fun grabMerchantFrom(text: String): String {
        val rawId = GRAB_BOOKING_ID.find(text)?.groups?.get("id")?.value ?: return "GRAB"
        val id = rawId.trimEnd('.', ',', ';')
        return when {
            id.startsWith("A-") -> "Grab Car"
            GRAB_FOOD_BOOKING.matches(id) -> "Grab Food"
            else -> "GRAB"
        }
    }

    /** Friendlier short name for the row's merchant placeholder, derived from the package. */
    private fun sourceLabel(sourceApp: String): String = when {
        sourceApp == SourcePackages.GOOGLE_WALLET -> "GWallet"
        sourceApp == SourcePackages.GOOGLE_PAY -> "GPay"
        sourceApp.contains("cimb") -> "CIMB"
        sourceApp.contains("maybank") -> "Maybank"
        sourceApp.contains("publicbank") -> "Public Bank"
        sourceApp.contains("rhb") -> "RHB"
        sourceApp.contains("hsbc") -> "HSBC"
        sourceApp.contains("hlb") || sourceApp.contains("hongleong") -> "Hong Leong"
        sourceApp.contains("ambank") -> "AmBank"
        sourceApp.contains("bsn") -> "BSN"
        sourceApp.contains("gxs") || sourceApp.contains("gxbank") -> "GX Bank"
        sourceApp.contains("wise") || sourceApp.contains("transferwise") -> "Wise"
        sourceApp == SourcePackages.TOUCH_N_GO -> "TnG"
        sourceApp == SourcePackages.GRAB -> "Grab"
        else -> sourceApp.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    companion object {
        // Same shape as in HeuristicExtractor; duplicated rather than shared because the
        // permissive layer's contract is "amount alone is enough" and we don't want a
        // future change to the heuristic to silently widen this layer too.
        private val AMOUNT = Regex(
            """\b(?:RM|MYR)\s*(?<amount>[\d,]+\.\d{2})\b""",
        )

        // "for booking <id>" anchored to a whitespace-delimited token. Greedy `\S+` so the
        // booking ID's trailing period (some captures end with it) is included; the caller
        // trims trailing punctuation before classifying.
        private val GRAB_BOOKING_ID = Regex(
            """for booking (?<id>\S+)""",
        )

        // Grab Food bookings: `<one or more digits>-<one or more alphanumeric chars>`.
        // Grab Car bookings start with the literal `A-` and are handled by `startsWith`,
        // not this regex.
        private val GRAB_FOOD_BOOKING = Regex(
            """^\d+-[A-Z0-9]+$""",
        )
    }
}
