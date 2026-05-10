package cy.txtracker.data

import androidx.room.withTransaction
import cy.txtracker.domain.TimeBucket
import cy.txtracker.domain.bucketOf
import cy.txtracker.export.Backup
import cy.txtracker.export.ImportResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Single entry point for the UI and the notification listener to read and write
 * transaction data. Wraps DAOs, owns dedupe-key generation, and applies the
 * dual-table learning policy on user edits.
 */
@Singleton
class TransactionRepository @Inject constructor(
    private val database: TxDatabase,
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

    /** All transactions sorted by occurredAt ASC. Used by CSV export. */
    suspend fun getAllTransactionsOnce(): List<Transaction> = transactionDao.getAllOnce()

    suspend fun getAllCategoriesOnce(): List<Category> = categoryDao.getAll()

    // Writes --------------------------------------------------------------

    /** Insert a transaction. Returns the new row ID, or null if dedupe collision dropped it. */
    suspend fun insert(tx: Transaction): Long? {
        val id = transactionDao.insert(tx)
        return id.takeIf { it >= 0 }
    }

    /**
     * Convenience for the manual-entry sheet. Builds a [Transaction] with `sourceApp = "manual"`,
     * a UUID-based dedupe key (so two manual entries never collide with each other or with a
     * captured notification), `direction = OUT`, and the user-supplied fields. The merchant
     * string is normalized via [normalizeMerchant], and the time bucket is computed from
     * [occurredAt] in the supplied [Instant]. Returns the new row id.
     */
    suspend fun addManualTransaction(
        amountMinor: Long,
        merchantRaw: String,
        categoryId: Long?,
        description: String?,
        occurredAt: Instant,
        now: Instant = Clock.System.now(),
    ): Long? {
        val tx = Transaction(
            amountMinor = amountMinor,
            currency = "MYR",
            merchantRaw = merchantRaw,
            merchantNormalized = normalizeMerchant(merchantRaw),
            categoryId = categoryId,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            occurredAt = occurredAt,
            timeBucket = bucketOf(occurredAt),
            sourceApp = MANUAL_SOURCE_APP,
            rawText = null,
            direction = Direction.OUT,
            createdAt = now,
            notificationDedupeKey = "manual:${UUID.randomUUID()}",
            needsVerification = false,
        )
        return insert(tx)
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

    suspend fun setNeedsVerification(txId: Long, needsVerification: Boolean) =
        transactionDao.updateNeedsVerification(txId, needsVerification)

    suspend fun delete(txId: Long) = transactionDao.delete(txId)

    // Category management ------------------------------------------------

    suspend fun addCategory(category: Category): Long = categoryDao.insert(category)

    suspend fun updateCategory(category: Category) = categoryDao.update(category)

    /**
     * Persists a new category ordering. Caller is expected to pass the categories in the
     * desired order — the repository assigns dense `sortOrder` values 0..N-1, so the values
     * never accumulate gaps and the next reorder is a clean renumber regardless of how many
     * times the user has shuffled.
     */
    suspend fun reorderCategories(orderedCategories: List<Category>) {
        val renumbered = orderedCategories.mapIndexed { index, c -> c.copy(sortOrder = index) }
        categoryDao.updateAll(renumbered)
    }

    suspend fun deleteCategory(category: Category) = categoryDao.delete(category)

    // Mapping management -------------------------------------------------

    suspend fun unlinkMerchantMapping(merchantNormalized: String) =
        merchantMappingDao.delete(merchantNormalized)

    suspend fun unlinkMerchantDescription(merchantNormalized: String, bucket: TimeBucket) =
        descriptionMappingDao.deleteMerchantBucket(merchantNormalized, bucket)

    suspend fun unlinkCategoryDescription(categoryId: Long, bucket: TimeBucket) =
        descriptionMappingDao.deleteCategoryBucket(categoryId, bucket)

    /**
     * Merges a [Backup] into the local database in a single Room transaction.
     *
     * Merge rules:
     *   - **Categories**: any backup category whose name doesn't already exist locally is
     *     created with the saved color and `isCustom`, appended to the end of the local
     *     `sortOrder` sequence. Existing categories are left untouched — the user's
     *     current color/order choices win.
     *   - **All mappings**: keyed by their natural key (merchant for merchant→category,
     *     (merchant, bucket) and (categoryName, bucket) for descriptions). When the same
     *     key exists locally, whichever side has the later `learnedAt` wins — so importing
     *     an older backup never clobbers more recent local learning, and importing a
     *     newer backup does refresh stale mappings.
     *   - **Mappings whose category name doesn't exist after the category-create pass**
     *     are skipped (counted in `skippedDueToMissingCategory`). Should be empty in
     *     normal use because the category-create pass creates everything referenced.
     */
    suspend fun applyBackup(backup: Backup): ImportResult = database.withTransaction {
        // 1. Insert any backup categories whose names aren't already present locally.
        val existingCategories = categoryDao.getAll()
        val existingByName = existingCategories.associateBy { it.name }
        var nextSortOrder = (existingCategories.maxOfOrNull { it.sortOrder } ?: -1) + 1

        var categoriesCreated = 0
        for (bc in backup.categories) {
            if (bc.name in existingByName) continue
            categoryDao.insert(
                Category(
                    name = bc.name,
                    color = bc.color,
                    isCustom = bc.isCustom,
                    sortOrder = nextSortOrder++,
                ),
            )
            categoriesCreated++
        }

        // 2. Refresh the name → category map so newly inserted ones are referenceable.
        val categoriesByName = categoryDao.getAll().associateBy { it.name }

        // 3. Merchant mappings.
        var mAdded = 0; var mUpdated = 0; var skipped = 0
        for (bm in backup.merchantMappings) {
            val target = categoriesByName[bm.categoryName]
            if (target == null) { skipped++; continue }
            val existing = merchantMappingDao.get(bm.merchant)
            if (existing != null && existing.learnedAt >= bm.learnedAt) continue
            merchantMappingDao.upsert(
                MerchantMapping(
                    merchantNormalized = bm.merchant,
                    categoryId = target.id,
                    learnedAt = bm.learnedAt,
                ),
            )
            if (existing == null) mAdded++ else mUpdated++
        }

        // 4. Merchant + bucket description mappings.
        var mdAdded = 0; var mdUpdated = 0
        for (bmd in backup.merchantDescriptionMappings) {
            val existing = descriptionMappingDao.getMerchantBucket(bmd.merchant, bmd.bucket)
            if (existing != null && existing.learnedAt >= bmd.learnedAt) continue
            descriptionMappingDao.upsertMerchant(
                MerchantDescriptionMapping(
                    merchantNormalized = bmd.merchant,
                    timeBucket = bmd.bucket,
                    description = bmd.description,
                    learnedAt = bmd.learnedAt,
                ),
            )
            if (existing == null) mdAdded++ else mdUpdated++
        }

        // 5. Category + bucket description mappings.
        var cdAdded = 0; var cdUpdated = 0
        for (bcd in backup.categoryDescriptionMappings) {
            val target = categoriesByName[bcd.categoryName]
            if (target == null) { skipped++; continue }
            val existing = descriptionMappingDao.getCategoryBucket(target.id, bcd.bucket)
            if (existing != null && existing.learnedAt >= bcd.learnedAt) continue
            descriptionMappingDao.upsertCategory(
                CategoryDescriptionMapping(
                    categoryId = target.id,
                    timeBucket = bcd.bucket,
                    description = bcd.description,
                    learnedAt = bcd.learnedAt,
                ),
            )
            if (existing == null) cdAdded++ else cdUpdated++
        }

        ImportResult(
            categoriesCreated = categoriesCreated,
            merchantMappingsAdded = mAdded,
            merchantMappingsUpdated = mUpdated,
            merchantDescriptionsAdded = mdAdded,
            merchantDescriptionsUpdated = mdUpdated,
            categoryDescriptionsAdded = cdAdded,
            categoryDescriptionsUpdated = cdUpdated,
            skippedDueToMissingCategory = skipped,
        )
    }
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
