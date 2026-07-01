package cy.txtracker.domain

import cy.txtracker.data.CategoryDao
import cy.txtracker.data.MerchantMappingDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a category id for a normalized merchant string at ingestion time.
 *
 * Lookup order (most specific first):
 *   1. Exact `MerchantMapping` — the user's explicit voice for this merchant string.
 *   2. Longest token-aligned prefix `MerchantMapping` — captured "STARBUCKS KLCC" matches
 *      stored "STARBUCKS". Stored must be a prefix of captured (boundary on whitespace);
 *      captured shorter than stored never matches. Ties on length resolve to most recently
 *      learned (mapping list is ordered by `learnedAt DESC`).
 *   3. User-defined per-category `keywordPattern` (regex, IGNORE_CASE), iterated by
 *      category `sortOrder` ascending. First match wins.
 *   4. Null — uncategorized; user labels via the edit sheet.
 *
 * Hardcoded built-in rules were retired; built-in seed patterns now live on the category
 * rows themselves (set by `TxDatabase.seedCategories` on fresh install).
 */
@Singleton
class CategorizationEngine @Inject constructor(
    private val merchantMappingDao: MerchantMappingDao,
    private val categoryDao: CategoryDao,
) {
    suspend fun categorize(merchantNormalized: String): Long? {
        if (merchantNormalized.isBlank()) return null

        // 1. Exact.
        merchantMappingDao.get(merchantNormalized)?.let { return it.categoryId }

        // 2. Longest token-aligned prefix.
        val mappings = merchantMappingDao.getAllOrderedByRecency()
        val storedKeys = mappings.map { it.merchantNormalized }
        val prefixMatch = MerchantPrefixMatcher.longestPrefix(merchantNormalized, storedKeys)
        if (prefixMatch != null) {
            mappings.firstOrNull { it.merchantNormalized == prefixMatch }
                ?.let { return it.categoryId }
        }

        // 3. User category keywordPattern (global only; trip categories are manual).
        val categories = categoryDao.getAllGlobal() // already ORDER BY sortOrder ASC, name ASC
        for (c in categories) {
            val pattern = c.keywordPattern?.takeIf { it.isNotBlank() } ?: continue
            val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                ?: continue // Malformed user pattern — skip rather than crash ingest.
            if (regex.containsMatchIn(merchantNormalized)) return c.id
        }

        return null
    }
}
