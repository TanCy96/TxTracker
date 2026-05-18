package cy.txtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cy.txtracker.domain.TimeBucket
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

@Dao
interface TransactionDao {

    /** Inserts; returns the new row ID, or -1 if the dedupe key collided. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tx: Transaction): Long

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    @Query(
        "SELECT COUNT(*) FROM transactions WHERE needsVerification = 1 AND createdAt < :cutoff"
    )
    suspend fun countPendingOlderThan(cutoff: Instant): Int

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<Transaction>>

    @Query("SELECT DISTINCT sourceApp FROM transactions WHERE sourceApp != 'manual'")
    fun observeDistinctSourceApps(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun countAll(): Long

    @Query("SELECT * FROM transactions ORDER BY occurredAt ASC")
    suspend fun getAllOnce(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE currency = :currency ORDER BY occurredAt ASC")
    suspend fun getAllForCurrency(currency: String): List<Transaction>

    @Query("SELECT * FROM transactions WHERE currency = :currency ORDER BY occurredAt DESC")
    fun observeAllForCurrency(currency: String): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE occurredAt >= :cutoffStart ORDER BY occurredAt ASC")
    suspend fun getAllFrom(cutoffStart: Instant): List<Transaction>

    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAt >= :startInclusive AND occurredAt < :endExclusive
        ORDER BY occurredAt DESC
        """
    )
    fun observeBetween(startInclusive: Instant, endExclusive: Instant): Flow<List<Transaction>>

    /**
     * Home-tab feed: MYR-only rows in window. Foreign rows live in the Foreign tab
     * (parked or trip-promoted) and must not bleed into Home totals or its list.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAt >= :startInclusive AND occurredAt < :endExclusive
          AND currency = 'MYR'
        ORDER BY occurredAt DESC
        """
    )
    fun observeMyrBetween(startInclusive: Instant, endExclusive: Instant): Flow<List<Transaction>>

    @Query(
        "SELECT * FROM transactions WHERE occurredAt >= :startInclusive AND occurredAt < :endExclusive ORDER BY occurredAt ASC"
    )
    suspend fun getAllBetween(startInclusive: Instant, endExclusive: Instant): List<Transaction>

    // Totals power the Home tab and intentionally exclude non-MYR rows: foreign
    // transactions (parked or trip-promoted) live under the Foreign tab and must not
    // bleed into the Home month total or its per-category breakdown.
    @Query(
        """
        SELECT categoryId, SUM(amountMinor) AS totalMinor
        FROM transactions
        WHERE occurredAt >= :startInclusive AND occurredAt < :endExclusive
          AND direction = 'OUT'
          AND currency = 'MYR'
        GROUP BY categoryId
        """
    )
    fun observeCategoryTotalsBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<CategoryTotal>>

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0)
        FROM transactions
        WHERE occurredAt >= :startInclusive AND occurredAt < :endExclusive
          AND direction = 'OUT'
          AND currency = 'MYR'
        """
    )
    fun observeTotalBetween(startInclusive: Instant, endExclusive: Instant): Flow<Long>

    @Query("SELECT * FROM transactions WHERE categoryId IS NULL")
    suspend fun getNullCategoryRows(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE description IS NULL")
    suspend fun getNullDescriptionRows(): List<Transaction>

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE id = :id")
    suspend fun updateCategory(id: Long, categoryId: Long?)

    @Query("UPDATE transactions SET description = :description WHERE id = :id")
    suspend fun updateDescription(id: Long, description: String?)

    @Query("UPDATE transactions SET needsVerification = :needsVerification WHERE id = :id")
    suspend fun updateNeedsVerification(id: Long, needsVerification: Boolean)

    /**
     * Cross-source dedupe lookup. Returns the most recently captured row matching the
     * amount + currency + 5-min bucket window with a DIFFERENT source app. Limit 1 because
     * v1 collapses multiple matches into "first known" — same-tier multi-match is rare in
     * practice and v1 chooses one deterministic outcome.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountMinor = :amountMinor
          AND currency = :currency
          AND occurredAt >= :bucketStart
          AND occurredAt < :bucketEndExclusive
          AND sourceApp != :excludeSourceApp
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun findCrossMerchantDupe(
        amountMinor: Long,
        currency: String,
        bucketStart: Instant,
        bucketEndExclusive: Instant,
        excludeSourceApp: String,
    ): Transaction?

    /**
     * Promotes a row's source fields in place. Used when an incoming Tier 1 notification
     * arrives for a row previously inserted from a Tier 2 source. Preserves id, createdAt,
     * categoryId, description; rewrites merchantRaw/Normalized/sourceApp/rawText and the
     * recomputed notificationDedupeKey.
     */
    @Query(
        """
        UPDATE transactions
        SET merchantRaw = :merchantRaw,
            merchantNormalized = :merchantNormalized,
            sourceApp = :sourceApp,
            rawText = :rawText,
            notificationDedupeKey = :notificationDedupeKey,
            needsVerification = :needsVerification
        WHERE id = :id
        """
    )
    suspend fun promoteSourceFields(
        id: Long,
        merchantRaw: String,
        merchantNormalized: String,
        sourceApp: String,
        rawText: String?,
        notificationDedupeKey: String,
        needsVerification: Boolean,
    )

    @Query(
        """
        UPDATE transactions
        SET amountMinor = :amountMinor,
            merchantRaw = :merchantRaw,
            merchantNormalized = :merchantNormalized,
            occurredAt = :occurredAt,
            timeBucket = :timeBucket
        WHERE id = :id
        """
    )
    suspend fun updateCoreFields(
        id: Long,
        amountMinor: Long,
        merchantRaw: String,
        merchantNormalized: String,
        occurredAt: Instant,
        timeBucket: TimeBucket,
    )

    @Query(
        """
        UPDATE transactions
        SET merchantRaw = :merchantRaw,
            merchantNormalized = :merchantNormalized,
            notificationDedupeKey = :notificationDedupeKey
        WHERE id = :id
        """
    )
    suspend fun updateMerchant(
        id: Long,
        merchantRaw: String,
        merchantNormalized: String,
        notificationDedupeKey: String,
    )

    @Query(
        """
        UPDATE transactions
        SET needsCurrencyConfirmation = 0
        WHERE currency = :currency
          AND needsCurrencyConfirmation = 1
          AND occurredAt >= :startAt
          AND occurredAt < :endAtExclusive
        """
    )
    suspend fun clearCurrencyConfirmationForRange(
        currency: String,
        startAt: Instant,
        endAtExclusive: Instant,
    )

    @Query(
        """
        UPDATE transactions
        SET currency = :currency,
            notificationDedupeKey = :notificationDedupeKey,
            needsCurrencyConfirmation = :needsCurrencyConfirmation
        WHERE id = :id
        """
    )
    suspend fun updateCurrency(
        id: Long,
        currency: String,
        notificationDedupeKey: String,
        needsCurrencyConfirmation: Boolean,
    )

    @Query(
        """
        SELECT * FROM transactions
        WHERE currency != 'MYR'
          AND needsCurrencyConfirmation = 0
          AND occurredAt >= :startInclusive
          AND occurredAt < :endExclusive
        ORDER BY occurredAt DESC
        """
    )
    fun observeForeignBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>>

    /**
     * Rows that belong to a specific trip window: same currency, occurredAt inside
     * [startInclusive, endExclusive). Foreign tab uses this to scope its view to one
     * trip at a time.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE currency = :currency
          AND occurredAt >= :startInclusive
          AND occurredAt < :endExclusive
        ORDER BY occurredAt DESC
        """
    )
    fun observeBetweenForCurrency(
        currency: String,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE needsCurrencyConfirmation = 1
        ORDER BY occurredAt DESC
        """
    )
    fun observeCurrencyReview(): Flow<List<Transaction>>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)
}
