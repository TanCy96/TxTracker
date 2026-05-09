package cy.txtracker.domain

/**
 * Built-in keyword rules: regex over normalized merchant strings → default category name.
 *
 * Rules are checked in declaration order; the first match wins. Order matters for ambiguous
 * keywords — GRABFOOD matches Food before \bGRAB\b would match Transport, by design.
 *
 * Patterns are applied to the output of `normalizeMerchant`, which is uppercase and has trailing
 * entity suffixes (SDN BHD, SVC, etc.) stripped. So all patterns are uppercase, and the alphabet
 * is `[A-Z0-9 ]` plus a few common punctuation characters that survive normalization.
 *
 * Adding a rule: pick the most specific brand keywords for that category, prefer word
 * boundaries (`\\b`) over substring matches when the keyword can collide with another category.
 */
object KeywordRules {

    data class Rule(val pattern: Regex, val categoryName: String)

    val rules: List<Rule> = listOf(
        // Food — chains, common Malaysian F&B keywords. Listed before Transport so GRABFOOD wins.
        Rule(
            Regex(
                "MCDONALD|\\bKFC\\b|BURGER\\s?KING|PIZZA\\s?HUT|SUBWAY|STARBUCKS|" +
                    "RESTAURANT|RESTORAN|CAFE|MAMAK|MAKAN|FOODPANDA|GRABFOOD|" +
                    "SHOPEEFOOD|TEALIVE|BOOST\\s?JUICE|MIXUE|CHATIME|SECRET\\s?RECIPE|" +
                    "OLDTOWN|TEXAS\\s?CHICKEN",
            ),
            "Food",
        ),
        // Groceries — supermarkets and minimarts.
        Rule(
            Regex(
                "VILLAGE\\s?GROCER|JAYA\\s?GROCER|TESCO|LOTUSS?|\\bAEON\\b|GIANT|MYDIN|" +
                    "99\\s?SPEEDMART|PASARAYA|HERO\\s?MARKET|SAM'?S\\s?GROCERIA|COLD\\s?STORAGE|" +
                    "ECONSAVE|BILLION",
            ),
            "Groceries",
        ),
        // Transport — rideshare, public transport, tolls, e-wallet TNG.
        Rule(
            Regex(
                "\\bGRAB\\b|MAXIM|GOJEK|RAPID\\s?KL|RAPID\\s?BUS|\\bMRT\\b|\\bLRT\\b|MONORAIL|" +
                    "PRASARANA|TOUCH\\s?N\\s?GO|\\bTNG\\b|TOLL|\\bPLUS\\b",
            ),
            "Transport",
        ),
        // Fuel — petrol stations.
        Rule(
            Regex("PETRONAS|SHELL|CALTEX|\\bPETRON\\b|BHPETROL|\\bBHP\\b"),
            "Fuel",
        ),
        // Parking — operators and councils that bill parking.
        Rule(
            Regex(
                "PARKING|PARKIR|PARK\\s?SOLUTION|JOMPARKING|FLEXIPARK|WILSON\\s?PARKING|" +
                    "\\bMBPJ\\b|\\bDBKL\\b|\\bMPSJ\\b|\\bMBSA\\b",
            ),
            "Parking",
        ),
        // Apparel — fashion retailers common in Malaysian malls.
        Rule(
            Regex(
                "UNIQLO|\\bZARA\\b|H&M|COTTON\\s?ON|PADINI|BRANDS\\s?OUTLET|\\bNIKE\\b|" +
                    "ADIDAS|\\bPUMA\\b|UNDER\\s?ARMOUR|REEBOK|SKECHERS",
            ),
            "Apparel",
        ),
        // Entertainment — cinemas, streaming, gaming.
        Rule(
            Regex(
                "CINEMA|\\bGSC\\b|\\bTGV\\b|\\bMBO\\b|NETFLIX|SPOTIFY|YOUTUBE|" +
                    "\\bSTEAM\\b|\\bGAME\\b|\\bVIU\\b|DISNEY",
            ),
            "Entertainment",
        ),
        // Utilities — telcos, internet, electricity, water.
        Rule(
            Regex(
                "TENAGA\\s?NASIONAL|\\bTNB\\b|\\bUNIFI\\b|\\bMAXIS\\b|CELCOM|\\bDIGI\\b|" +
                    "U\\s?MOBILE|YES\\s?4G|TIME\\s?DOTCOM|\\bASTRO\\b|SYABAS|AIR\\s?SELANGOR",
            ),
            "Utilities",
        ),
        // Health — clinics, hospitals, pharmacies.
        Rule(
            Regex(
                "HOSPITAL|CLINIC|KLINIK|PHARMACY|FARMASI|GUARDIAN|WATSONS|" +
                    "CARING|ALPRO|BIG\\s?PHARMACY",
            ),
            "Health",
        ),
    )

    /** Returns the category name for the first matching rule, or null if none match. */
    fun match(merchantNormalized: String): String? =
        rules.firstOrNull { it.pattern.containsMatchIn(merchantNormalized) }?.categoryName
}
