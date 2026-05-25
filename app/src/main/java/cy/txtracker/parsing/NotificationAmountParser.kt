package cy.txtracker.parsing

data class ParsedAmount(
    val amountMinor: Long,
    val currency: String,
)

object NotificationAmountParser {
    private val AMOUNT = Regex(
        """(?:""" +
            """(?<![A-Za-z])(?<prefix>RM|MYR|[\u00A3\u20AC\u00A5\u20B9\u20A9\u20BD\u0E3F$])\s*(?<amtA>(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)""" +
            """|""" +
            """(?<![A-Za-z0-9])(?<amtB>(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)\s*(?<suffix>(?-i:[A-Z]{3}))(?![A-Za-z0-9])""" +
            """)""",
        RegexOption.IGNORE_CASE,
    )

    fun findFirst(
        text: String,
        symbolDefaults: Map<String, String> = emptyMap(),
    ): ParsedAmount? {
        if (text.isBlank()) return null
        val match = AMOUNT.find(text) ?: return null
        val amountStr = match.groups["amtA"]?.value
            ?: match.groups["amtB"]?.value
            ?: return null
        val prefixToken = match.groups["prefix"]?.value
        val suffixToken = match.groups["suffix"]?.value
        return ParsedAmount(
            amountMinor = parseAmountMinor(amountStr),
            currency = Currencies.resolve(prefixToken, suffixToken, symbolDefaults),
        )
    }
}
