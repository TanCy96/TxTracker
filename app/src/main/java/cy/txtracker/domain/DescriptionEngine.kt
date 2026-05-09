package cy.txtracker.domain

import cy.txtracker.data.DescriptionMappingDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suggests a free-text description ("lunch", "petrol", "coffee") for a transaction at
 * ingestion time, based on what the user has labeled in the past.
 *
 * Lookup order (most specific first):
 *   1. `MerchantDescriptionMapping(merchant, bucket)` — same merchant, same time-of-day bucket.
 *   2. `MerchantDescriptionMapping(merchant, *)` — same merchant, any bucket; the most recently
 *      learned entry wins.
 *   3. `CategoryDescriptionMapping(category, bucket)` — same category, same bucket. This is the
 *      cross-merchant generalization that lets one labeling of `Food + MIDDAY = "lunch"` apply to
 *      any midday Food purchase from any merchant.
 *   4. Null — no suggestion; the user fills it in via the edit sheet, and saving dual-writes
 *      both mapping tables so future lookups have something to find.
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

        if (categoryId != null) {
            descriptionMappingDao.getCategoryBucket(categoryId, bucket)
                ?.let { return it.description }
        }

        return null
    }
}
