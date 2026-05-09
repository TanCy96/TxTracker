package cy.txtracker.parsing

import cy.txtracker.data.Direction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

/**
 * Generic, keyword-based fallback for when no strict per-source parser matches.
 *
 * Triggers only when **all three** signals are present in the notification text:
 *   1. An amount: `RM<n>.<2d>` or `MYR<n>.<2d>`, with or without space, with optional thousands.
 *   2. An out-direction verb: paid / transferred / charged / debited / spent / withdrawn /
 *      sent / deducted (case-insensitive).
 *   3. A recipient phrase: `to <X>` / `@<X>` / `at <X>`.
 *
 * Requiring all three keeps false positives out of the captured stream — promo text like
 * "RM 5.00 cashback expires soon" has no out-verb; balance updates have neither verb nor
 * recipient. Anything that does match all three is real enough to be worth surfacing, but
 * gets `needsVerification = true` so the user can confirm before it counts as real spend.
 *
 * The extractor doesn't try to be the strict parser's equal — it accepts noisier merchant
 * strings than a hand-rolled regex would. Cleanup happens later when the user confirms or
 * edits the row.
 */
@Singleton
class HeuristicExtractor @Inject constructor() {

    fun extract(text: String, sourceApp: String, postedAt: Instant): ParsedTransaction? {
        if (text.isBlank()) return null

        val amountMatch = AMOUNT.find(text) ?: return null
        if (!OUT_VERB.containsMatchIn(text)) return null

        val merchant = extractMerchant(text)?.takeIf { it.isNotBlank() } ?: return null

        return ParsedTransaction(
            amountMinor = parseRinggitAmountMinor(amountMatch.groups["amount"]!!.value),
            currency = "MYR",
            merchantRaw = merchant,
            occurredAt = postedAt,
            sourceApp = sourceApp,
            rawText = text,
            direction = Direction.OUT,
        )
    }

    /**
     * Walk the recipient patterns in priority order, returning the first non-blank merchant.
     * Trailing punctuation is stripped because notifications often end the merchant phrase
     * with a period.
     */
    private fun extractMerchant(text: String): String? {
        for (pattern in MERCHANT_PATTERNS) {
            val m = pattern.find(text) ?: continue
            val raw = m.groups["merchant"]?.value ?: continue
            return raw.trim().trimEnd('.', ',', ';')
        }
        return null
    }

    companion object {
        // Amount: RM 1.00, RM1.00, MYR 1.00, MYR1.00, RM 1,234.56
        private val AMOUNT = Regex(
            """\b(?:RM|MYR)\s*(?<amount>[\d,]+\.\d{2})\b""",
        )

        // Verbs that imply outgoing money. Refunds/credits/cashback all use different verbs
        // and are intentionally NOT in this list.
        private val OUT_VERB = Regex(
            """\b(?:paid|transferred|charged|debited|spent|withdrawn|sent|deducted)\b""",
            RegexOption.IGNORE_CASE,
        )

        // Each merchant pattern stops before common follow-on tokens ("on <date>", "for <X>",
        // "via <X>", etc.) and at sentence-ending punctuation. Order matters — we try the most
        // specific shapes first.
        private val MERCHANT_PATTERNS = listOf(
            // "@MERCHANT on <date>" (bank-style)
            Regex(
                """@(?<merchant>[^\.\n,]+?)(?=\s+on\s+\d{2}/\d{2}|[\.\n,]|\s*$)""",
            ),
            // "to MERCHANT for|on|via|using|by ..." or "to MERCHANT" at end of sentence
            Regex(
                """\bto\s+(?<merchant>[^\.\n,]+?)(?=\s+(?:for|on|at|via|using|by)\b|[\.\n,]|\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
            // "at MERCHANT" — store-style ("paid at COFFEE BEAN")
            Regex(
                """\bat\s+(?<merchant>[^\.\n,]+?)(?=\s+(?:for|on|via|using|by)\b|[\.\n,]|\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
        )
    }
}
