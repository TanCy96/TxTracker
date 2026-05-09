package cy.txtracker.data

/**
 * Normalizes a raw merchant string into a stable form used for:
 *   1. Dedupe-key computation (so the same payment from GWallet and from the bank app collapse)
 *   2. `MerchantMapping` / `MerchantDescriptionMapping` lookups (so cosmetic variants of the same
 *      merchant name share their learned category and descriptions)
 *
 * Normalization steps:
 *   - Uppercase and trim, collapse whitespace.
 *   - Strip trailing card-number tails like " **1868" that some bank notifications append.
 *   - Strip trailing Malaysian business-entity suffixes (SDN BHD, BHD, ENT, SVC, etc.) repeatedly
 *     so chains like "ABC TRADING SDN BHD" reduce to "ABC".
 *
 * Conservative on purpose: only well-known suffixes are stripped, never internal words.
 */
fun normalizeMerchant(raw: String): String {
    if (raw.isBlank()) return ""

    var s = raw.uppercase().trim()
    s = s.replace(Regex("\\s+"), " ")

    // " **1868", " *1234" — card-number tails some bank apps append.
    s = s.replace(Regex("\\s*\\*+\\d{2,}\\s*$"), "").trim()

    // Iteratively peel off entity suffixes. Each iteration removes one trailing suffix; the loop
    // continues until nothing else matches, so "ABC TRADING SDN BHD" → "ABC" in two passes.
    val suffix = Regex(
        """\s+(SDN\.?\s*BHD\.?|BHD\.?|\(M\)|\(MALAYSIA\)|MALAYSIA|MSIA|ENTERPRISES?|ENT|TRADING|SERVICES?|SVCS?|GROUP|HOLDINGS)\.?\s*$""",
    )
    while (true) {
        val next = s.replace(suffix, "").trim()
        if (next == s) break
        s = next
    }
    return s
}
