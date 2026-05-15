package cy.txtracker.domain

/**
 * Pure lookup helper: given a captured merchant string and a list of stored merchant strings,
 * return the longest stored entry that is a token-aligned prefix of the captured string,
 * or null if none match.
 *
 * Token alignment: `stored` is a prefix of `captured` iff `captured == stored` OR
 * `captured.startsWith(stored + " ")`. Byte-level prefixes that cross a token boundary
 * (e.g., "STARB" vs "STARBUCKS KLCC") do NOT match.
 *
 * Ties on length resolve by the input order of [stored] — caller-controlled. Production
 * callers pass mappings sorted by `learnedAt DESC` so the most recently learned wins ties.
 */
object MerchantPrefixMatcher {

    fun longestPrefix(captured: String, stored: List<String>): String? {
        val capturedTrim = captured.trim()
        if (capturedTrim.isEmpty() || stored.isEmpty()) return null
        var best: String? = null
        for (s in stored) {
            if (s.isEmpty()) continue
            val isPrefix = capturedTrim == s || capturedTrim.startsWith("$s ")
            if (isPrefix && (best == null || s.length > best.length)) {
                best = s
            }
        }
        return best
    }
}
