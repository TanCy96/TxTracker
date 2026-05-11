package cy.txtracker.parsing

import cy.txtracker.data.Direction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant

/**
 * Generic, keyword-based fallback for when no strict per-source parser matches.
 *
 * Two trigger shapes:
 *
 *   A. **Verb + recipient.** All three of:
 *      1. An amount: `RM<n>.<2d>` or `MYR<n>.<2d>`, with or without space, with optional thousands.
 *      2. An out-direction verb: paid / transferred / charged / debited / spent / withdrawn /
 *         sent / deducted (case-insensitive).
 *      3. A recipient phrase: `to <X>` / `@<X>` / `at <X>`.
 *
 *   B. **Card-spend (no verb required).** The full text matches
 *      `<MERCHANT> RM<amount> with <CARD> <bullets-or-asterisks><LAST4>`. Wallets like
 *      Google Wallet use this verb-less form. The head before the amount is the merchant.
 *
 * Either shape is sufficient. Shape B is tried first so the card-spend head extraction
 * doesn't get accidentally caught by the more permissive shape-A merchant patterns.
 *
 * Requiring at least one of these keeps false positives out of the captured stream — promo
 * text like "RM 5.00 cashback expires soon" matches neither. Anything that does match gets
 * `needsVerification = true` so the user can confirm before it counts as real spend.
 */
@Singleton
class HeuristicExtractor @Inject constructor() {

    fun extract(text: String, sourceApp: String, postedAt: Instant): ParsedTransaction? {
        if (text.isBlank()) return null
        val trimmed = text.trim()
        val amountMatch = AMOUNT.find(trimmed) ?: return null

        val merchant = resolveMerchant(trimmed)?.takeIf { it.isNotBlank() } ?: return null

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
     * Tries the card-spend shape first (no verb required), then falls back to the
     * verb+recipient shape. Returns null if neither shape matches.
     */
    private fun resolveMerchant(text: String): String? {
        CARD_SPEND_PATTERN.matchEntire(text)?.groups?.get("merchant")?.value?.trim()?.let {
            if (it.isNotEmpty()) return it
        }

        if (!OUT_VERB.containsMatchIn(text)) return null
        for (pattern in RECIPIENT_PATTERNS) {
            val raw = pattern.find(text)?.groups?.get("merchant")?.value
                ?.trim()
                ?.trimEnd('.', ',', ';')
            if (!raw.isNullOrEmpty()) return raw
        }
        return null
    }

    companion object {
        // Amount: RM 1.00, RM1.00, MYR 1.00, MYR1.00, RM 1,234.56
        private val AMOUNT = Regex(
            """\b(?:RM|MYR)\s*(?<amount>[\d,]+\.\d{2})\b""",
        )

        // Verbs that imply outgoing money. Refunds / credits / cashback all use different
        // verbs (received, refunded, earned, credited) and are intentionally NOT in this
        // list. Includes both past-tense and bare forms — bank apps differ in phrasing
        // ("transferred" vs "transfer", "withdrawn" vs "withdrew").
        private val OUT_VERB = Regex(
            """\b(?:paid|transferred|transfer|charged|debited|debit|spent|withdrawn|withdrew|withdraw|sent|deducted|purchased|purchase|billed)\b""",
            RegexOption.IGNORE_CASE,
        )

        // Card-spend shape (full-string match): `<MERCHANT> RM<amt> with <CARD> <bullets><last4>`.
        // Accepts both U+2022 bullets (••1868 — current Google Wallet) and asterisks (**1868
        // — older Google Wallet style and some other wallets). Bullet/asterisk run of any
        // length to handle ••••1868 captures observed in the wild.
        private val CARD_SPEND_PATTERN = Regex(
            """^(?<merchant>.+?)\s+RM\s*[\d,]+\.\d{2}\s+with\s+.+?\s+[•*]+\s*(?<last4>\d{4})\s*$""",
        )

        // Each recipient pattern stops before common follow-on tokens ("on <date>", "for <X>",
        // "via <X>", etc.) and at sentence-ending punctuation. Order matters — we try the most
        // specific shapes first.
        private val RECIPIENT_PATTERNS = listOf(
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
