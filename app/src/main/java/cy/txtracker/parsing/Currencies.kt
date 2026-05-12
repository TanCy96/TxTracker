package cy.txtracker.parsing

/**
 * Lookup tables + disambiguation logic for currency tokens parsed out of
 * notifications. The parsers extract a raw prefix (`RM`, `£`, `$`) or suffix
 * (`MYR`, `GBP`, `USD`); [resolve] converts that to a stable currency code
 * using the hierarchy documented on the function.
 */
object Currencies {

    /**
     * ISO-4217 codes the app knows about. Used as the set of acceptable suffix
     * tokens — anything matching `[A-Z]{3}` not in this set is treated as
     * "UNKNOWN" so a random TLA in a notification doesn't pose as a currency.
     */
    val KNOWN_CODES: Set<String> = setOf(
        "MYR", "USD", "GBP", "EUR", "SGD", "AUD", "JPY", "CNY",
        "HKD", "NZD", "CAD", "THB", "IDR", "PHP", "VND", "INR",
        "KRW", "TWD", "CHF", "DKK", "SEK", "NOK", "RUB", "BRL",
    )

    /**
     * Unambiguous symbol → code. `null` value = ambiguous: caller looks the
     * symbol up in [TrackedCurrency] rows with `isDefaultForSymbol = true`.
     */
    val SYMBOL_TO_CODE: Map<String, String?> = mapOf(
        "RM" to "MYR",
        "£" to "GBP",
        "€" to "EUR",
        "₹" to "INR",
        "₩" to "KRW",
        "₽" to "RUB",
        "฿" to "THB",
        "$" to null,  // USD / SGD / AUD / HKD / NZD / CAD / …
        "¥" to null,  // JPY / CNY
    )

    /**
     * Display symbol to use when auto-creating a [TrackedCurrency] from an
     * explicit code in a parsed notification. Keeps the auto-add behavior
     * predictable for the user.
     */
    val CODE_TO_DISPLAY_SYMBOL: Map<String, String> = mapOf(
        "MYR" to "RM", "USD" to "$",  "GBP" to "£",  "EUR" to "€",
        "SGD" to "S$", "AUD" to "A$", "JPY" to "¥",  "CNY" to "¥",
        "HKD" to "HK$","NZD" to "NZ$","CAD" to "C$", "THB" to "฿",
        "IDR" to "Rp", "PHP" to "₱",  "VND" to "₫",  "INR" to "₹",
        "KRW" to "₩",  "TWD" to "NT$","CHF" to "Fr", "DKK" to "kr",
        "SEK" to "kr", "NOK" to "kr", "RUB" to "₽",  "BRL" to "R$",
    )

    /**
     * Resolves the currency for a parsed amount match.
     *
     * Hierarchy:
     *   1. Explicit 3-letter code (prefix or suffix) in [KNOWN_CODES] — used as-is.
     *   2. Unambiguous symbol from [SYMBOL_TO_CODE] — its mapped code.
     *   3. Ambiguous symbol — looked up in [symbolDefaults] (the user's
     *      `isDefaultForSymbol = true` rows, keyed by symbol).
     *   4. Anything else — "UNKNOWN".
     *
     * @param prefixToken the matched prefix group (`RM`, `£`, `$`, …) or null.
     * @param suffixToken the matched suffix group (`MYR`, `GBP`, …) or null.
     * @param symbolDefaults map of symbol → code from
     *   [cy.txtracker.data.TrackedCurrencyDao.getDefaultsForSymbol].
     */
    fun resolve(
        prefixToken: String?,
        suffixToken: String?,
        symbolDefaults: Map<String, String>,
    ): String {
        // 1. Explicit code wins.
        if (suffixToken != null && suffixToken.uppercase() in KNOWN_CODES) {
            return suffixToken.uppercase()
        }
        if (prefixToken != null && prefixToken.uppercase() in KNOWN_CODES) {
            return prefixToken.uppercase()
        }
        // 2 + 3. Symbol resolution.
        val symbol = prefixToken ?: return "UNKNOWN"
        val mapped = SYMBOL_TO_CODE[symbol]
        if (mapped != null) return mapped
        if (symbol in SYMBOL_TO_CODE) {
            // Symbol is known-ambiguous. Use user's default if any.
            return symbolDefaults[symbol] ?: "UNKNOWN"
        }
        return "UNKNOWN"
    }
}
