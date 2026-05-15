package cy.txtracker.domain

/**
 * Default `keywordPattern` values written into the built-in categories on fresh-install
 * seeding (see [cy.txtracker.data.TxDatabase.seedCategories]). One-to-one port of the
 * regex strings that the deleted `KeywordRules` consulted at runtime — now stored as data
 * on category rows so users can edit / delete / refine them in Settings.
 *
 * Keys must match the names in `DefaultCategories.seed` in `TxDatabase.kt`. Existing
 * installs are not retroactively reseeded; their categories keep `keywordPattern = NULL`
 * until the user opts in via the editor.
 */
object DefaultKeywordPatterns {

    val byCategoryName: Map<String, String> = mapOf(
        "Food" to "MCDONALD|\\bKFC\\b|BURGER\\s?KING|PIZZA\\s?HUT|SUBWAY|STARBUCKS|" +
            "RESTAURANT|RESTORAN|CAFE|MAMAK|MAKAN|FOODPANDA|GRABFOOD|" +
            "SHOPEEFOOD|TEALIVE|BOOST\\s?JUICE|MIXUE|CHATIME|SECRET\\s?RECIPE|" +
            "OLDTOWN|TEXAS\\s?CHICKEN",
        "Groceries" to "VILLAGE\\s?GROCER|JAYA\\s?GROCER|TESCO|LOTUSS?|\\bAEON\\b|GIANT|MYDIN|" +
            "99\\s?SPEEDMART|PASARAYA|HERO\\s?MARKET|SAM'?S\\s?GROCERIA|COLD\\s?STORAGE|" +
            "ECONSAVE|BILLION",
        "Transport" to "\\bGRAB\\b|MAXIM|GOJEK|RAPID\\s?KL|RAPID\\s?BUS|\\bMRT\\b|\\bLRT\\b|MONORAIL|" +
            "PRASARANA|TOUCH\\s?N\\s?GO|\\bTNG\\b|TOLL|\\bPLUS\\b",
        "Fuel" to "PETRONAS|SHELL|CALTEX|\\bPETRON\\b|BHPETROL|\\bBHP\\b",
        "Parking" to "PARKING|PARKIR|PARK\\s?SOLUTION|JOMPARKING|FLEXIPARK|WILSON\\s?PARKING|" +
            "\\bMBPJ\\b|\\bDBKL\\b|\\bMPSJ\\b|\\bMBSA\\b",
        "Apparel" to "UNIQLO|\\bZARA\\b|H&M|COTTON\\s?ON|PADINI|BRANDS\\s?OUTLET|\\bNIKE\\b|" +
            "ADIDAS|\\bPUMA\\b|UNDER\\s?ARMOUR|REEBOK|SKECHERS",
        "Entertainment" to "CINEMA|\\bGSC\\b|\\bTGV\\b|\\bMBO\\b|NETFLIX|SPOTIFY|YOUTUBE|" +
            "\\bSTEAM\\b|\\bGAME\\b|\\bVIU\\b|DISNEY",
        "Utilities" to "TENAGA\\s?NASIONAL|\\bTNB\\b|\\bUNIFI\\b|\\bMAXIS\\b|CELCOM|\\bDIGI\\b|" +
            "U\\s?MOBILE|YES\\s?4G|TIME\\s?DOTCOM|\\bASTRO\\b|SYABAS|AIR\\s?SELANGOR",
        "Health" to "HOSPITAL|CLINIC|KLINIK|PHARMACY|FARMASI|GUARDIAN|WATSONS|" +
            "CARING|ALPRO|BIG\\s?PHARMACY",
    )
}
