package cy.txtracker.parsing

import cy.txtracker.data.Direction
import cy.txtracker.data.UNDEFINED_MERCHANT
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

    fun extract(
        text: String,
        sourceApp: String,
        postedAt: Instant,
        symbolDefaults: Map<String, String> = emptyMap(),
    ): ParsedTransaction? {
        if (text.isBlank()) return null
        val trimmed = stripTrailingTapCta(text.trim())
        val amount = NotificationAmountParser.findFirst(trimmed, symbolDefaults) ?: return null

        val merchant = resolveMerchant(trimmed)?.takeIf { it.isNotBlank() } ?: return null

        return ParsedTransaction(
            amountMinor = amount.amountMinor,
            currency = amount.currency,
            merchantRaw = merchant,
            occurredAt = postedAt,
            sourceApp = sourceApp,
            rawText = text,
            direction = Direction.OUT,
        )
    }

    /**
     * Strips trailing notification call-to-action like "Tap to see this transaction" or
     * "Tap here to view details". Wise and other apps append these as a body suffix; the
     * literal "to <verb>…" otherwise hijacks the `to MERCHANT` recipient pattern and
     * captures the CTA tail as the merchant.
     */
    private fun stripTrailingTapCta(text: String): String =
        TAP_CTA_SUFFIX.replace(text, "").trimEnd().trimEnd('.', ',', ';')

    /**
     * Tries the card-spend shape first (no verb required), then the verb+recipient shape.
     * If an out-verb is present but no recipient anchor matched (HSBC SMS shape, where the
     * bank doesn't include who you paid), returns `UNDEFINED_MERCHANT` so the transaction
     * is still captured for manual labeling. Returns null only when there's no out-verb at
     * all (i.e. the text is not an outgoing-payment notification).
     */
    private fun resolveMerchant(text: String): String? {
        CARD_SPEND_PATTERN.matchEntire(text)?.groups?.get("merchant")?.value?.trim()?.let {
            if (it.isNotEmpty()) return it
        }

        TRANSFER_SUCCESS_PATTERN.find(text)?.groups?.get("merchant")?.value?.trim()?.let {
            if (it.isNotEmpty()) return it
        }

        if (!OUT_VERB.containsMatchIn(text)) return null
        for (pattern in RECIPIENT_PATTERNS) {
            val raw = pattern.find(text)?.groups?.get("merchant")?.value
                ?.trim()
                ?.trimEnd('.', ',', ';')
            if (!raw.isNullOrEmpty()) return raw
        }
        return UNDEFINED_MERCHANT
    }

    companion object {
        // Three shapes in one alternation; decimals optional. The verb-gate (OUT_VERB)
        // continues to fence off promo / noise text from triggering on amount alone.
        //   Prefix form:  "RM 12.50", "MYR1.00", "£20", "$5", "€1,000.50"
        //   Suffix form:  "1 MYR", "100 GBP", "25.50 USD"
        // Leading-digit group accepts either properly comma-grouped thousands or a bare
        // run of digits — some banks (CIMB observed) emit "1163.27" without separators.
        // Both alternatives are fenced off from running letters/digits to keep date-like
        // fragments out: e.g. "Transaction Date: 18MAY2026" must NOT match as amtB=18 /
        // suffix=MAY (regression observed on HSBC capture-all email body). Lookbehind /
        // lookahead enforce that the amount sits at a real token boundary.
        private val AMOUNT = Regex(
            """(?:""" +
            """(?<![A-Za-z])(?<prefix>RM|MYR|[£€¥₹₩₽฿$])\s*(?<amtA>(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)""" +
            """|""" +
            """(?<![A-Za-z0-9])(?<amtB>(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)\s*(?<suffix>(?-i:[A-Z]{3}))(?![A-Za-z0-9])""" +
            """)""",
            RegexOption.IGNORE_CASE,
        )

        // Verbs that imply outgoing money. Refunds / credits / cashback all use different
        // verbs (received, refunded, earned, credited) and are intentionally NOT in this
        // list. Includes both past-tense and bare forms — bank apps differ in phrasing
        // ("transferred" vs "transfer", "withdrawn" vs "withdrew"). `payment` is the
        // noun-form gate for bank FPX confirmations ("FPX Payment RM... to MERCHANT").
        private val OUT_VERB = Regex(
            """\b(?:paid|payment|transferred|transfer|charged|debited|debit|spent|withdrawn|withdrew|withdraw|sent|deducted|purchased|purchase|billed)\b""",
            RegexOption.IGNORE_CASE,
        )

        // Notification call-to-action suffix: "Tap to <verb> …", "Tap here to <verb> …".
        // Anchored to end-of-string so we only strip the trailing CTA, not occurrences in
        // the middle. Optional preceding period/comma is consumed so we don't leave a
        // dangling separator.
        private val TAP_CTA_SUFFIX = Regex(
            """[\s\.,;]*\bTap\s+(?:here\s+)?to\s+\S+.*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // Card-spend shape (full-string match): `<MERCHANT> RM<amt> with <CARD> <bullets><last4>`.
        // Accepts both U+2022 bullets (••1868 — current Google Wallet) and asterisks (**1868
        // — older Google Wallet style and some other wallets). Bullet/asterisk run of any
        // length to handle ••••1868 captures observed in the wild.
        private val CARD_SPEND_PATTERN = Regex(
            """^(?<merchant>.+?)\s+RM\s*[\d,]+\.\d{2}\s+with\s+.+?\s+[•*]+\s*(?<last4>\d{4})\s*$""",
        )

        // Transfer-success shape (head match): "RM<amt> to <RECIPIENT> is success(ful)".
        // GX Bank and similar confirmation-style pushes have a `to <recipient>` anchor but no
        // out-verb, so the OUT_VERB gate rejects them. The amount-led head + "is successful" tail
        // is specific enough to exclude promo noise, so we accept it without requiring a verb.
        // The trailing \b is load-bearing: it makes "is successful" match but "is successfully"
        // NOT match, so confirmation phrasing like "successfully transferred to X" still flows
        // through the verb+recipient path instead of being captured here.
        private val TRANSFER_SUCCESS_PATTERN = Regex(
            """^RM\s*[\d,]+(?:\.\d{2})?\s+to\s+(?<merchant>.+?)\s+is\s+success(?:ful)?\b""",
            RegexOption.IGNORE_CASE,
        )

        // Each recipient pattern stops before common follow-on tokens ("on <date>", "for <X>",
        // "via <X>", etc.) and at sentence-ending punctuation. Order matters — we try the most
        // specific shapes first.
        private val RECIPIENT_PATTERNS = listOf(
            // Wise-style P2P: "is now in <NAME>'s account" — possessive form.
            Regex(
                """\bin\s+(?<merchant>[^\.\n,]+?)'s\s+account\b""",
                RegexOption.IGNORE_CASE,
            ),
            // "@MERCHANT on <date>" (bank-style)
            Regex(
                """@(?<merchant>[^\.\n,]+?)(?=\s+on\s+\d{2}/\d{2}|[\.\n,]|\s*$)""",
            ),
            // "to MERCHANT for|on|via|using|by ..." or "to MERCHANT" at end of sentence.
            // Bank confirmation suffixes (`accepted`, `successfully`, `completed`,
            // `processed`) also terminate the merchant — they sit between the merchant
            // and a date/time and would otherwise be slurped into the merchant name.
            Regex(
                """\bto\s+(?<merchant>[^\.\n,]+?)(?=\s+(?:for|on|at|via|using|by|accepted|successfully|completed|processed)\b|[\.\n,]|\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
            // "at MERCHANT" — store-style ("paid at COFFEE BEAN")
            Regex(
                """\bat\s+(?<merchant>[^\.\n,]+?)(?=\s+(?:for|on|via|using|by|accepted|successfully|completed|processed)\b|[\.\n,]|\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
            // Wallet head-colon shape: "MERCHANT: RM<amt> ..." — TnG and other wallets put the
            // merchant as the SUBJECT at the start of the body, not as the object of a preposition.
            // Anchored to start-of-text and requires a currency-prefix amount immediately after
            // the colon, so non-wallet "X:" prefixes (dates, labels) don't match. Placed last so
            // existing `to MERCHANT` / `at MERCHANT` patterns still win when both shapes apply.
            Regex(
                """^(?<merchant>[^:\n]+?):\s+(?:RM|MYR|[£€¥₹₩₽฿$])""",
                RegexOption.IGNORE_CASE,
            ),
        )
    }
}
