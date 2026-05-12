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
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.Instant.Companion.DISTANT_FUTURE

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
    private val merchantNoteDao: MerchantNoteDao,
    private val userFacingSourceDao: UserFacingSourceDao,
    private val approvedSourceDao: ApprovedSourceDao,
    private val trackedCurrencyDao: TrackedCurrencyDao,
    private val tripWindowDao: TripWindowDao,
) {
    // Reads ---------------------------------------------------------------

    fun observeTransactionsBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> =
        transactionDao.observeBetween(startInclusive, endExclusive)

    fun observeForeignTransactionsBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> =
        transactionDao.observeForeignBetween(startInclusive, endExclusive)

    fun observeCurrencyReviewTransactions(): Flow<List<Transaction>> =
        transactionDao.observeCurrencyReview()

    fun observeTrackedCurrencies(): Flow<List<TrackedCurrency>> =
        trackedCurrencyDao.observeAll()

    fun observeAllTrips(): Flow<List<TripWindow>> = tripWindowDao.observeAll()

    fun observeTripsForCurrency(currency: String): Flow<List<TripWindow>> =
        tripWindowDao.observeForCurrency(currency)

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

    fun observeMerchantNotes(): Flow<List<MerchantNote>> = merchantNoteDao.observeAll()

    fun observeUserFacingSources(): Flow<List<UserFacingSource>> =
        userFacingSourceDao.observeAll()

    fun observeApprovedSourcePackages(): Flow<List<String>> =
        approvedSourceDao.observeAllPackageNames()

    fun observeApprovedSources(): Flow<List<ApprovedSource>> =
        approvedSourceDao.observeAll()

    fun observeAllSourcePackages(): Flow<List<String>> =
        transactionDao.observeDistinctSourceApps()

    suspend fun addUserFacingSource(packageName: String, now: Instant = Clock.System.now()) {
        userFacingSourceDao.insert(UserFacingSource(packageName, now))
    }

    suspend fun removeUserFacingSource(packageName: String) {
        userFacingSourceDao.delete(packageName)
    }

    suspend fun getMerchantNote(merchantNormalized: String): MerchantNote? =
        merchantNoteDao.get(merchantNormalized)

    /**
     * Sets a free-text note for a merchant. Blank input clears the note (deletes the row)
     * so an empty note doesn't accumulate as a phantom mapping.
     */
    suspend fun setMerchantNote(
        merchantNormalized: String,
        note: String?,
        now: Instant = Clock.System.now(),
    ) {
        val cleaned = note?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned == null) {
            merchantNoteDao.delete(merchantNormalized)
        } else {
            merchantNoteDao.upsert(MerchantNote(merchantNormalized, cleaned, now))
        }
    }

    suspend fun getTransaction(id: Long): Transaction? = transactionDao.getById(id)

    suspend fun getCategory(id: Long): Category? = categoryDao.getById(id)

    /** All transactions sorted by occurredAt ASC. Used by CSV export. */
    suspend fun getAllTransactionsOnce(): List<Transaction> = transactionDao.getAllOnce()

    /** All transactions with `occurredAt >= cutoffStart`, sorted ASC. Used by cloud-sync
     *  upload when a year-month floor is configured. */
    suspend fun getAllTransactionsOnceFrom(cutoffStart: Instant): List<Transaction> =
        transactionDao.getAllFrom(cutoffStart)

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
        currency: String = "MYR",
        now: Instant = Clock.System.now(),
    ): Long? {
        val needsCurrencyConfirmation = if (currency == "MYR") {
            false
        } else {
            ensureTrackedCurrency(currency)
            findActiveTrip(currency, occurredAt) == null
        }
        val tx = Transaction(
            amountMinor = amountMinor,
            currency = currency,
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
            needsCurrencyConfirmation = needsCurrencyConfirmation,
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

    /**
     * Renames a single transaction's merchant. Re-normalizes and regenerates the
     * `notificationDedupeKey` so future captures of the same payment (amount + 5-min
     * bucket + new merchant) will dedupe against this row rather than re-inserting.
     *
     * Returns true on success, false when the regenerated dedupe key collides with
     * another existing row (rare — happens only if the user renames into a key that
     * an existing row already owns). Caller leaves the row unchanged on false.
     *
     * Does NOT touch merchant-level mappings (category, description, note) — those
     * stay attached to the OLD `merchantNormalized` until the user re-saves them on
     * the renamed row.
     */
    suspend fun setMerchant(txId: Long, merchantRaw: String): Boolean {
        val cleaned = merchantRaw.trim()
        if (cleaned.isEmpty()) return false
        val tx = transactionDao.getById(txId) ?: return false
        val newNormalized = normalizeMerchant(cleaned)
        if (newNormalized == tx.merchantNormalized && cleaned == tx.merchantRaw) return true
        val newDedupeKey = computeDedupeKey(tx.amountMinor, newNormalized, tx.occurredAt, tx.currency)
        return try {
            transactionDao.updateMerchant(
                id = txId,
                merchantRaw = cleaned,
                merchantNormalized = newNormalized,
                notificationDedupeKey = newDedupeKey,
            )
            true
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            false
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

    /**
     * Toggles the verification flag. When the user CONFIRMS a Pending row (verify=false), we
     * also implicitly approve its source package — adding it to [ApprovedSource] so the
     * listener will continue processing notifications from this app even after the user
     * turns off capture-all-packages. Manual entries are skipped (their sourceApp is
     * `manual`, not a real package).
     */
    suspend fun setNeedsVerification(
        txId: Long,
        needsVerification: Boolean,
        now: Instant = Clock.System.now(),
    ) {
        if (!needsVerification) {
            val tx = transactionDao.getById(txId)
            val sourceApp = tx?.sourceApp
            if (sourceApp != null && sourceApp != MANUAL_SOURCE_APP) {
                approvedSourceDao.insert(ApprovedSource(packageName = sourceApp, firstApprovedAt = now))
            }
        }
        transactionDao.updateNeedsVerification(txId, needsVerification)
    }

    /**
     * Looks up an existing row that matches the incoming notification on amount + currency
     * + 5-min bucket but came from a different source app. Returns null if no such row.
     * Caller (TxIngestor) decides whether to promote, drop, or insert normally.
     */
    suspend fun findCrossMerchantDupe(
        amountMinor: Long,
        currency: String,
        occurredAt: Instant,
        excludeSourceApp: String,
    ): Transaction? {
        val (bucketStart, bucketEndExclusive) = bucketBoundsFor(occurredAt)
        return transactionDao.findCrossMerchantDupe(
            amountMinor = amountMinor,
            currency = currency,
            bucketStart = bucketStart,
            bucketEndExclusive = bucketEndExclusive,
            excludeSourceApp = excludeSourceApp,
        )
    }

    /**
     * Idempotent. Ensures a [TrackedCurrency] row exists for [code]. Picks the
     * display symbol from [cy.txtracker.parsing.Currencies.CODE_TO_DISPLAY_SYMBOL]; falls back to
     * the code itself when no symbol is registered (uncommon — most ISO codes have a symbol).
     *
     * Auto-creation here just makes the currency visible in Settings → Foreign currencies
     * so the user can act on it. It does NOT make the currency "active" anywhere — the user
     * still has to open a trip.
     */
    suspend fun ensureTrackedCurrency(code: String, now: Instant = Clock.System.now()) {
        if (code == "MYR" || code == "UNKNOWN") return
        val symbol = cy.txtracker.parsing.Currencies.CODE_TO_DISPLAY_SYMBOL[code] ?: code
        trackedCurrencyDao.insertIfAbsent(
            TrackedCurrency(
                code = code,
                displaySymbol = symbol,
                isDefaultForSymbol = false,
                addedAt = now,
            ),
        )
    }

    suspend fun findActiveTrip(currency: String, at: Instant): TripWindow? =
        tripWindowDao.findActiveAt(currency, at)

    /**
     * Creates a [TripWindow] and retroactively promotes all parked transactions in
     * the window's currency that fall inside `[startAt, endAt)`. Atomic — uses a
     * Room transaction.
     *
     * `endAt = null` means "open-ended"; the in-range clear uses
     * [DISTANT_FUTURE] as the upper bound so future captures in that
     * currency keep promoting.
     *
     * Returns the new trip's id.
     */
    suspend fun openTrip(
        currency: String,
        startAt: Instant,
        endAt: Instant?,
        now: Instant = Clock.System.now(),
    ): Long = database.withTransaction {
        val tripId = tripWindowDao.insert(
            TripWindow(
                currency = currency,
                startAt = startAt,
                endAt = endAt,
                createdAt = now,
            ),
        )
        transactionDao.clearCurrencyConfirmationForRange(
            currency = currency,
            startAt = startAt,
            endAtExclusive = endAt ?: DISTANT_FUTURE,
        )
        tripId
    }

    /**
     * Sets a trip's `endAt`. Used by the "End trip" action in Currencies
     * settings. Does NOT re-flag any transactions — once promoted, rows stay
     * promoted. The trip is the artifact, not a live gate.
     */
    suspend fun closeTrip(tripId: Long, endAt: Instant) {
        tripWindowDao.setEnd(tripId, endAt)
    }

    /**
     * Renames a single transaction's currency. Mirrors [setMerchant]: regenerates
     * the dedupe key, returns `false` on uniqueness collision, leaves the row
     * untouched on collision.
     *
     * `needsCurrencyConfirmation` is re-evaluated atomically: if any active trip
     * for the new currency covers `occurredAt`, the row promotes (false);
     * otherwise it parks (true). Picking MYR always promotes (false).
     *
     * Callers in the edit-sheet flow follow up with [openTrip] when the user
     * accepts a trip-creation prompt for a parked row.
     */
    suspend fun setCurrency(txId: Long, currency: String): Boolean {
        val tx = transactionDao.getById(txId) ?: return false
        if (tx.currency == currency) return true

        val parked = if (currency == "MYR") {
            false
        } else {
            tripWindowDao.findActiveAt(currency, tx.occurredAt) == null
        }
        val newDedupeKey = computeDedupeKey(
            amountMinor = tx.amountMinor,
            merchantNormalized = tx.merchantNormalized,
            occurredAt = tx.occurredAt,
            currency = currency,
        )
        return try {
            transactionDao.updateCurrency(
                id = txId,
                currency = currency,
                notificationDedupeKey = newDedupeKey,
                needsCurrencyConfirmation = parked,
            )
            true
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            false
        }
    }

    suspend fun promoteSourceFields(
        id: Long,
        merchantRaw: String,
        merchantNormalized: String,
        sourceApp: String,
        rawText: String?,
        notificationDedupeKey: String,
        needsVerification: Boolean,
    ) = transactionDao.promoteSourceFields(
        id = id,
        merchantRaw = merchantRaw,
        merchantNormalized = merchantNormalized,
        sourceApp = sourceApp,
        rawText = rawText,
        notificationDedupeKey = notificationDedupeKey,
        needsVerification = needsVerification,
    )

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

        // 6. User-facing sources. Idempotent: insert-or-ignore. Existing rows untouched so
        //    we never bump a local `addedAt` backwards from an older backup.
        for (bs in backup.userFacingSources) {
            userFacingSourceDao.insert(
                UserFacingSource(packageName = bs.packageName, addedAt = bs.addedAt),
            )
        }

        // 7. Approved sources. Idempotent insert-or-ignore — existing local rows keep their
        //    `firstApprovedAt` (the earliest local approval wins; we never push it forward
        //    based on a newer backup file).
        for (bs in backup.approvedSources) {
            approvedSourceDao.insert(
                ApprovedSource(packageName = bs.packageName, firstApprovedAt = bs.firstApprovedAt),
            )
        }

        // 8. Merchant notes. Later-updatedAt wins on conflict — symmetrical to the
        //    merchant-mapping / description-mapping merges. Skip notes that aren't newer
        //    than the local copy.
        for (bn in backup.merchantNotes) {
            val existing = merchantNoteDao.get(bn.merchant)
            if (existing != null && existing.updatedAt >= bn.updatedAt) continue
            merchantNoteDao.upsert(
                MerchantNote(
                    merchantNormalized = bn.merchant,
                    note = bn.note,
                    updatedAt = bn.updatedAt,
                ),
            )
        }

        // 9. Tracked currencies. Insert-or-ignore — local rows win on conflict.
        for (bc in backup.trackedCurrencies) {
            trackedCurrencyDao.insertIfAbsent(
                TrackedCurrency(
                    code = bc.code,
                    displaySymbol = bc.displaySymbol,
                    isDefaultForSymbol = bc.isDefaultForSymbol,
                    addedAt = bc.addedAt,
                ),
            )
        }

        // 10. Trip windows. Dedupe by (currency, startAt) at the application level
        //     since the primary key is an autoincrement id, not the natural key.
        val existingTrips = tripWindowDao.observeAll().first()
            .map { it.currency to it.startAt }
            .toSet()
        for (bt in backup.tripWindows) {
            if (bt.currency to bt.startAt in existingTrips) continue
            tripWindowDao.insert(
                TripWindow(
                    currency = bt.currency,
                    startAt = bt.startAt,
                    endAt = bt.endAt,
                    createdAt = bt.createdAt,
                ),
            )
        }

        // 11. Transactions. Insert with IGNORE on the unique notificationDedupeKey index —
        //     local transactions always win on conflict. Backup transactions whose
        //     categoryName doesn't resolve to a local category get inserted with
        //     categoryId = null (Unverified, same as a fresh capture).
        var transactionsAdded = 0
        for (bt in backup.transactions) {
            val categoryId = bt.categoryName?.let { categoriesByName[it]?.id }
            val rowId = transactionDao.insert(
                Transaction(
                    amountMinor = bt.amountMinor,
                    currency = bt.currency,
                    merchantRaw = bt.merchantRaw,
                    merchantNormalized = bt.merchantNormalized,
                    categoryId = categoryId,
                    description = bt.description,
                    occurredAt = bt.occurredAt,
                    timeBucket = bt.timeBucket,
                    sourceApp = bt.sourceApp,
                    rawText = bt.rawText,
                    direction = bt.direction,
                    createdAt = bt.createdAt,
                    notificationDedupeKey = bt.notificationDedupeKey,
                    needsVerification = bt.needsVerification,
                    needsCurrencyConfirmation = bt.needsCurrencyConfirmation,
                ),
            )
            if (rowId >= 0) transactionsAdded++
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
            transactionsAdded = transactionsAdded,
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
    currency: String,
): String {
    val bucketMs = occurredAt.toEpochMilliseconds() / FIVE_MINUTES_MS
    val payload = "$amountMinor|$merchantNormalized|$bucketMs|$currency"
    return sha1Hex(payload)
}

/**
 * The half-open `[start, endExclusive)` instant range of the 5-minute bucket containing
 * [occurredAt], using the same bucketing arithmetic as [computeDedupeKey]. The
 * cross-source dedup lookup uses this so its bucket boundaries match the hash dedupe
 * key's bucket boundaries.
 */
fun bucketBoundsFor(occurredAt: Instant): Pair<Instant, Instant> {
    val bucketStartMs =
        (occurredAt.toEpochMilliseconds() / FIVE_MINUTES_MS) * FIVE_MINUTES_MS
    val start = Instant.fromEpochMilliseconds(bucketStartMs)
    val endExclusive = Instant.fromEpochMilliseconds(bucketStartMs + FIVE_MINUTES_MS)
    return start to endExclusive
}

internal const val FIVE_MINUTES_MS: Long = 5 * 60 * 1000

private fun sha1Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1")
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { "%02x".format(it) }
}
