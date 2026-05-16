package cy.txtracker.domain

import cy.txtracker.data.DescriptionMappingDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suggests a free-text description for a transaction at ingestion time, based on what the
 * user has labeled in the past.
 *
 * Lookup order (most specific first):
 *   1. (merchant, bucket) exact.
 *   2. (merchant, any-bucket) — most recent wins.
 *   3. Longest-prefix merchant — captured "STARBUCKS KLCC" falls back to stored
 *      "STARBUCKS" mappings, re-running 1 and 2 against the matched stored merchant.
 *   4. (category, bucket).
 *   5. (category, any-bucket) — most recent wins.
 *   6. Null.
 */
@Singleton
class DescriptionEngine @Inject constructor(
    private val descriptionMappingDao: DescriptionMappingDao,
) {
    suspend fun suggest(
        merchantNormalized: String,
        categoryId: Long?,
        bucket: TimeBucket,
    ): String? {
        if (merchantNormalized.isBlank()) return null

        descriptionMappingDao.getMerchantBucket(merchantNormalized, bucket)
            ?.let { return it.description }

        descriptionMappingDao.getMerchantAny(merchantNormalized)
            ?.let { return it.description }

        val storedKeys = descriptionMappingDao.getAllMerchantKeys()
        val prefixMatch = MerchantPrefixMatcher.longestPrefix(merchantNormalized, storedKeys)
        if (prefixMatch != null) {
            descriptionMappingDao.getMerchantBucket(prefixMatch, bucket)
                ?.let { return it.description }
            descriptionMappingDao.getMerchantAny(prefixMatch)
                ?.let { return it.description }
        }

        if (categoryId != null) {
            descriptionMappingDao.getCategoryBucket(categoryId, bucket)
                ?.let { return it.description }
            descriptionMappingDao.getCategoryAnyBucket(categoryId)
                ?.let { return it.description }
        }

        return null
    }
}
