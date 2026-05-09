package cy.txtracker.data

import cy.txtracker.domain.TimeBucket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Single entry point for the UI and the notification listener to read and write
 * transaction data. Wraps DAOs, owns dedupe-key generation, and applies the
 * dual-table learning policy on user edits.
 */
@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val merchantMappingDao: MerchantMappingDao,
    private val descriptionMappingDao: DescriptionMappingDao,
) {
    // Reads ---------------------------------------------------------------

    fun observeTransactionsBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> =
        transactionDao.observeBetween(startInclusive, endExclusive)

    fun observeTotalBetween(startInclusive: Instant, endExclusive: Instant): Flow<Long> =
        transactionDao.observeTotalBetween(startInclusive, endExclusive)

    fun observeCategoryTotalsBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<CategoryTotal>> =
        transactionDao.observeCategoryTotalsBetween(startInclusive, endExclusive)

    fun observeAllCategories(): Flow<List<Category>> = categoryDao.observeAll()

    fun observeMerchantMappings(): Flow<List<MerchantMapping>> =
        merchantMappingDao.observeAll()

    fun observeMerchantDescriptionMappings(): Flow<List<MerchantDescriptionMapping>> =
        descriptionMappingDao.observeAllMerchant()

    fun observeCategoryDescriptionMappings(): Flow<List<CategoryDescriptionMapping>> =
        descriptionMappingDao.observeAllCategory()

    suspend fun getTransaction(id: Long): Transaction? = transactionDao.getById(id)

    suspend fun getCategory(id: Long): Category? = categoryDao.getById(id)

    // Writes --------------------------------------------------------------

    /** Insert a transaction. Returns the new row ID, or null if dedupe collision dropped it. */
    suspend fun insert(tx: Transaction): Long? {
        val id = transactionDao.insert(tx)
        return id.takeIf { it >= 0 }
    }

    /**
     * Updates the transaction's category and (when [learnMapping] is true) writes/refreshes
     * the merchant→category mapping so future transactions from the same merchant
     * auto-categorize. Pass false when the user is correcting a one-off miscategorization
     * and doesn't want it learned.
     */
    suspend fun setCategory(
        txId: Long,
        categoryId: Long?,
        learnMapping: Boolean = true,
        now: Instant,
    ) {
        transactionDao.updateCategory(txId, categoryId)
        if (learnMapping && categoryId != null) {
            val tx = transactionDao.getById(txId) ?: return
            merchantMappingDao.upsert(
                MerchantMapping(
                    merchantNormalized = tx.merchantNormalized,
                    categoryId = categoryId,
                    learnedAt = now,
                ),
            )
            // If the transaction already has a description, propagate it to the
            // category-level mapping so future txs in the same (category, bucket)
            // get the suggestion. Without this, the order in which the user labels
            // category and description would change what gets learned.
            tx.description?.takeIf { it.isNotBlank() }?.let { description ->
                descriptionMappingDao.upsertCategory(
                    CategoryDescriptionMapping(
                        categoryId = categoryId,
                        timeBucket = tx.timeBucket,
                        description = description,
                        learnedAt = now,
                    ),
                )
            }
        }
    }

    /**
     * Updates the description and, when [learnMappings] is true and [description] is non-blank,
     * upserts BOTH `MerchantDescriptionMapping` and `CategoryDescriptionMapping` so future
     * transactions can be auto-described. The merchant-specific entry naturally takes
     * precedence in lookups.
     */
    suspend fun setDescription(
        txId: Long,
        description: String?,
        learnMappings: Boolean = true,
        now: Instant,
    ) {
        val cleaned = description?.trim()?.takeIf { it.isNotEmpty() }
        transactionDao.updateDescription(txId, cleaned)
        if (learnMappings && cleaned != null) {
            val tx = transactionDao.getById(txId) ?: return
            descriptionMappingDao.upsertMerchant(
                MerchantDescriptionMapping(
                    merchantNormalized = tx.merchantNormalized,
                    timeBucket = tx.timeBucket,
                    description = cleaned,
                    learnedAt = now,
                ),
            )
            tx.categoryId?.let { categoryId ->
                descriptionMappingDao.upsertCategory(
                    CategoryDescriptionMapping(
                        categoryId = categoryId,
                        timeBucket = tx.timeBucket,
                        description = cleaned,
                        learnedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun updateCoreFields(
        txId: Long,
        amountMinor: Long,
        merchantRaw: String,
        merchantNormalized: String,
        occurredAt: Instant,
        timeBucket: TimeBucket,
    ) {
        transactionDao.updateCoreFields(
            id = txId,
            amountMinor = amountMinor,
            merchantRaw = merchantRaw,
            merchantNormalized = merchantNormalized,
            occurredAt = occurredAt,
            timeBucket = timeBucket,
        )
    }

    suspend fun delete(txId: Long) = transactionDao.delete(txId)

    // Category management ------------------------------------------------

    suspend fun addCategory(category: Category): Long = categoryDao.insert(category)

    suspend fun updateCategory(category: Category) = categoryDao.update(category)

    suspend fun deleteCategory(category: Category) = categoryDao.delete(category)

    // Mapping management -------------------------------------------------

    suspend fun unlinkMerchantMapping(merchantNormalized: String) =
        merchantMappingDao.delete(merchantNormalized)

    suspend fun unlinkMerchantDescription(merchantNormalized: String, bucket: TimeBucket) =
        descriptionMappingDao.deleteMerchantBucket(merchantNormalized, bucket)

    suspend fun unlinkCategoryDescription(categoryId: Long, bucket: TimeBucket) =
        descriptionMappingDao.deleteCategoryBucket(categoryId, bucket)
}

/**
 * Stable hash used as `notificationDedupeKey`. The listener computes this for every parsed
 * notification; identical hashes drop on insert via `OnConflictStrategy.IGNORE`.
 *
 * `sourceApp` is intentionally NOT part of the key: the user's bank app and Google Wallet
 * frequently emit separate notifications for the same payment, and they should collapse to
 * one row. The 5-minute window also handles the typical pending → posted notification pair
 * a single source emits for one transaction.
 */
fun computeDedupeKey(
    amountMinor: Long,
    merchantNormalized: String,
    occurredAt: Instant,
): String {
    val bucketMs = occurredAt.toEpochMilliseconds() / FIVE_MINUTES_MS
    val payload = "$amountMinor|$merchantNormalized|$bucketMs"
    return sha1Hex(payload)
}

private const val FIVE_MINUTES_MS: Long = 5 * 60 * 1000

private fun sha1Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1")
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { "%02x".format(it) }
}
