package cy.txtracker.domain

import cy.txtracker.data.CategoryDao
import cy.txtracker.data.MerchantMappingDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a category id for a normalized merchant string at ingestion time.
 *
 * Lookup order (most specific first):
 *   1. MerchantMapping table — explicit user-learned merchant→category. Survives reinstalls,
 *      survives keyword-rule changes. The user's voice is final.
 *   2. Built-in [KeywordRules] — regex over the merchant string mapping to a default category
 *      by name. Used only when no explicit mapping exists.
 *   3. Null — leave the transaction uncategorized; the user labels it via the edit sheet.
 *
 * Keyword-rule matches are NOT auto-saved as MerchantMappings: rules are deterministic so
 * caching adds no value, and any later user override should cleanly take precedence without
 * a stale mapping to clean up.
 */
@Singleton
class CategorizationEngine @Inject constructor(
    private val merchantMappingDao: MerchantMappingDao,
    private val categoryDao: CategoryDao,
) {
    suspend fun categorize(merchantNormalized: String): Long? {
        if (merchantNormalized.isBlank()) return null

        merchantMappingDao.get(merchantNormalized)?.let { return it.categoryId }

        val keywordCategoryName = KeywordRules.match(merchantNormalized) ?: return null
        return categoryDao.getAll().firstOrNull { it.name == keywordCategoryName }?.id
    }
}
