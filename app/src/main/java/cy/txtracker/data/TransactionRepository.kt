package cy.txtracker.data

import androidx.room.withTransaction
import cy.txtracker.domain.CategorizationEngine
import cy.txtracker.domain.DescriptionEngine
import cy.txtracker.domain.TimeBucket
import cy.txtracker.domain.bucketOf
import cy.txtracker.export.Backup
import cy.txtracker.export.ImportResult
import cy.txtracker.parsing.FundingSourceClassifier
import cy.txtracker.parsing.HeuristicExtractor
import cy.txtracker.parsing.SourceLabels
import cy.txtracker.parsing.SourcePackages
import cy.txtracker.service.NotificationRewriteEngine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.Instant.Companion.DISTANT_FUTURE

/**
 * Single entry point for the UI and the notification listener to read and write
 * transaction data. Wraps DAOs, owns dedupe-key generation, and applies the
 * dual-table learning policy on user edits.
 */
/**
 * Outcome of [TransactionRepository.reparseMerchantsFromRawText]. Surfaced verbatim
 * in the Settings snackbar so the user can confirm the sweep did something.
 */
data class ReparseResult(
    val scanned: Int,
    val updated: Int,
    val unchanged: Int,
    val parserMiss: Int,
    val skippedCollision: Int,
)

enum class PoolFilter { PENDING, NOISE, PROMOTED, ALL }

data class PromoteEdit(
    val merchantRaw: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAt: Instant,
    val categoryId: Long?,
    val description: String?,
)

data class TrackedPackageRow(
    val packageName: String,
    val label: String,
    val status: PackageStatus,
    val isBuiltIn: Boolean,
    val poolEntryCountLast30Days: Int,
    val lastCapturedAt: Instant?,
)

enum class PackageStatus { TRACKED, REJECTED, UNTRACKED }

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
    private val capturedNotificationDao: CapturedNotificationDao,
    private val rejectedSourceDao: RejectedSourceDao,
    private val trackedCurrencyDao: TrackedCurrencyDao,
    private val tripWindowDao: TripWindowDao,
    private val packageTextRewriteDao: PackageTextRewriteDao,
    private val fundingSourceDao: FundingSourceDao,
    private val reimbursementEntryDao: ReimbursementEntryDao,
    private val categorizationEngine: CategorizationEngine,
    private val descriptionEngine: DescriptionEngine,
    private val heuristicExtractor: HeuristicExtractor,
    private val rewriteEngine: NotificationRewriteEngine,
    private val fundingSourceClassifier: FundingSourceClassifier,
) {
    constructor(
        database: TxDatabase,
        transactionDao: TransactionDao,
        categoryDao: CategoryDao,
        merchantMappingDao: MerchantMappingDao,
        descriptionMappingDao: DescriptionMappingDao,
        merchantNoteDao: MerchantNoteDao,
        userFacingSourceDao: UserFacingSourceDao,
        approvedSourceDao: ApprovedSourceDao,
        trackedCurrencyDao: TrackedCurrencyDao,
        tripWindowDao: TripWindowDao,
    ) : this(
        database = database,
        transactionDao = transactionDao,
        categoryDao = categoryDao,
        merchantMappingDao = merchantMappingDao,
        descriptionMappingDao = descriptionMappingDao,
        merchantNoteDao = merchantNoteDao,
        userFacingSourceDao = userFacingSourceDao,
        approvedSourceDao = approvedSourceDao,
        capturedNotificationDao = database.capturedNotificationDao(),
        rejectedSourceDao = database.rejectedSourceDao(),
        trackedCurrencyDao = trackedCurrencyDao,
        tripWindowDao = tripWindowDao,
        packageTextRewriteDao = database.packageTextRewriteDao(),
        fundingSourceDao = database.fundingSourceDao(),
        reimbursementEntryDao = database.reimbursementEntryDao(),
        categorizationEngine = CategorizationEngine(merchantMappingDao, categoryDao),
        descriptionEngine = DescriptionEngine(descriptionMappingDao),
        heuristicExtractor = HeuristicExtractor(),
        rewriteEngine = NotificationRewriteEngine(database.packageTextRewriteDao()),
        fundingSourceClassifier = FundingSourceClassifier(database.fundingSourceDao()),
    )

    // Reads ---------------------------------------------------------------

    fun observeTransactionsBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> =
        transactionDao.observeBetween(startInclusive, endExclusive)

    /** Home-tab feed: MYR-only rows in window. Foreign rows belong to the Foreign tab. */
    fun observeMyrTransactionsBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> =
        transactionDao.observeMyrBetween(startInclusive, endExclusive)

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

    /** All transactions for [currency], ordered occurredAt DESC. Used by the
     *  Trip-history screen to count transactions per trip window. */
    fun observeTransactionsForCurrency(currency: String): Flow<List<Transaction>> =
        transactionDao.observeAllForCurrency(currency)

    /**
     * Trip-scoped feed: rows for [currency] in [startInclusive, endExclusive). The
     * Foreign tab uses this to render a single trip the same way Home renders a month.
     * Open-ended trips pass [DISTANT_FUTURE] for [endExclusive].
     */
    fun observeTransactionsForTrip(
        currency: String,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> =
        transactionDao.observeBetweenForCurrency(currency, startInclusive, endExclusive)

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

    // Per-package raw-text rewrite rules. Applied at ingest-time BEFORE the heuristic /
    // permissive extractors by NotificationRewriteEngine, which observes [observeRewrites].
    fun observeRewrites(): Flow<List<PackageTextRewrite>> =
        packageTextRewriteDao.observeAll()

    suspend fun getRewritesForPackage(packageName: String): List<PackageTextRewrite> =
        packageTextRewriteDao.getForPackage(packageName)

    suspend fun upsertRewrite(
        packageName: String,
        pattern: String,
        replacement: String,
        now: Instant = Clock.System.now(),
    ) {
        packageTextRewriteDao.upsert(
            PackageTextRewrite(
                packageName = packageName,
                pattern = pattern,
                replacement = replacement,
                learnedAt = now,
            ),
        )
    }

    suspend fun deleteRewrite(packageName: String, pattern: String) {
        packageTextRewriteDao.delete(packageName, pattern)
    }

    fun observeUserFacingSources(): Flow<List<UserFacingSource>> =
        userFacingSourceDao.observeAll()

    fun observeApprovedSourcePackages(): Flow<List<String>> =
        approvedSourceDao.observeAllPackageNames()

    fun observeApprovedSources(): Flow<List<ApprovedSource>> =
        approvedSourceDao.observeAll()

    fun observePool(
        filter: PoolFilter,
        packageName: String? = null,
    ): Flow<List<CapturedNotification>> =
        combine(
            if (packageName == null) capturedNotificationDao.observeAll()
            else capturedNotificationDao.observeForPackage(packageName),
            rejectedSourceDao.observeAllPackageNames(),
        ) { rows, rejected ->
            val rejectedSet = rejected.toSet()
            rows.filter { row ->
                when (filter) {
                    PoolFilter.PENDING ->
                        row.disposition == CaptureDisposition.PENDING &&
                            row.packageName !in rejectedSet
                    PoolFilter.NOISE -> row.disposition == CaptureDisposition.NOISE
                    PoolFilter.PROMOTED -> row.disposition == CaptureDisposition.PROMOTED
                    PoolFilter.ALL -> true
                }
            }
        }

    fun observePoolPendingCount(): Flow<Int> =
        capturedNotificationDao.observeVisiblePendingCount()

    fun observeTrackedPackages(): Flow<List<TrackedPackageRow>> =
        combine(
            approvedSourceDao.observeAllPackageNames(),
            rejectedSourceDao.observeAllPackageNames(),
            // Wrap the DAO call in `flow { }` so `Clock.System.now()` is evaluated lazily,
            // on each collection rather than once when `observeTrackedPackages()` is invoked.
            // Each re-subscription (e.g. user re-opens the tracked-apps screen) picks up a
            // fresh 30-day cutoff. The cutoff is still frozen for the lifetime of a single
            // collection — sufficient for this surface since the screen is opened on demand.
            flow {
                emitAll(capturedNotificationDao.observePackageStatsSince(Clock.System.now() - 30.days))
            },
        ) { approved, rejected, stats ->
            buildTrackedPackageRows(
                approved = approved.toSet(),
                rejected = rejected.toSet(),
                stats = stats,
            )
        }

    fun observeRejectedPackages(): Flow<List<RejectedSource>> =
        rejectedSourceDao.observeAll()

    /** True when the user has explicitly rejected this package as a notification source. */
    suspend fun isPackageRejected(packageName: String): Boolean =
        rejectedSourceDao.isRejected(packageName)

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
        // UNDEFINED is the parser's "no recipient in the notification" sentinel — it labels
        // many unrelated transactions. Writing a note keyed by UNDEFINED would surface that
        // note on every future unattributed tx, which is wrong. Drop the write silently.
        if (merchantNormalized == UNDEFINED_MERCHANT) return
        val cleaned = note?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned == null) {
            merchantNoteDao.delete(merchantNormalized)
        } else {
            merchantNoteDao.upsert(MerchantNote(merchantNormalized, cleaned, now))
        }
    }

    // Funding source management ------------------------------------------

    fun observeFundingSources(): Flow<List<FundingSource>> = fundingSourceDao.observeAll()

    suspend fun fundingSourceTxCount(id: Long): Int = fundingSourceDao.txCountFor(id)

    suspend fun renameFundingSource(id: Long, displayName: String, now: Instant = Clock.System.now()) {
        val existing = fundingSourceDao.getById(id) ?: return
        fundingSourceDao.update(
            existing.copy(displayName = displayName.trim(), isUserNamed = true, updatedAt = now),
        )
    }

    suspend fun setFundingSourceKind(id: Long, kind: FundingSourceKind, now: Instant = Clock.System.now()) {
        val existing = fundingSourceDao.getById(id) ?: return
        // The seeded Cash source's kind is locked; UI greys out the selector but defend here.
        val defaultCashId = fundingSourceDao.getDefaultCash()?.id
        if (defaultCashId != null && existing.id == defaultCashId) return
        fundingSourceDao.update(existing.copy(kind = kind, isUserNamed = true, updatedAt = now))
    }

    /**
     * Merge [sourceId] into [targetId]. All transactions previously pointing to source are
     * re-linked to target, then source is deleted. Single DB transaction so the FK reference
     * never dangles.
     */
    suspend fun mergeFundingSources(sourceId: Long, targetId: Long) {
        if (sourceId == targetId) return
        // Defense against accidentally destroying the seeded Cash row, which is the
        // classifier's MANUAL_SOURCE_APP target and the AddManual default. The UI also
        // disables the merge button for this row; this guard is the safety net.
        val defaultCashId = fundingSourceDao.getDefaultCash()?.id
        if (defaultCashId != null && sourceId == defaultCashId) return
        database.withTransaction { mergeFundingSourcesBody(sourceId, targetId) }
    }

    /**
     * Body of [mergeFundingSources], extracted so unit tests can drive it directly without
     * mocking Room's `withTransaction` extension. Production callers MUST go through
     * [mergeFundingSources] so the work runs atomically.
     */
    internal suspend fun mergeFundingSourcesBody(sourceId: Long, targetId: Long) {
        transactionDao.relinkFundingSource(oldId = sourceId, newId = targetId)
        fundingSourceDao.deleteById(sourceId)
    }

    suspend fun deleteFundingSource(id: Long) {
        // UI only enables delete when count == 0; defend here too.
        if (fundingSourceDao.txCountFor(id) > 0) return
        fundingSourceDao.getById(id) ?: return
        val defaultCashId = fundingSourceDao.getDefaultCash()?.id
        if (defaultCashId != null && id == defaultCashId) return   // seeded Cash is non-deletable
        fundingSourceDao.deleteById(id)
    }

    suspend fun setTransactionFundingSource(txId: Long, fundingSourceId: Long?) {
        transactionDao.updateFundingSource(txId, fundingSourceId)
    }

    // Reimbursement entries (multi-person). The cached Transaction.reimbursedMinor is kept in
    // step after every mutation so all net-spend SQL stays unchanged.

    fun observeReimbursementEntries(txId: Long): Flow<List<ReimbursementEntry>> =
        reimbursementEntryDao.observeForTransaction(txId)

    suspend fun getReimbursementEntries(txId: Long): List<ReimbursementEntry> =
        reimbursementEntryDao.getForTransaction(txId)

    /** All entries across all transactions, grouped by transactionId. Used by CSV export. */
    suspend fun getReimbursementEntriesByTransaction(): Map<Long, List<ReimbursementEntry>> =
        reimbursementEntryDao.getAll().groupBy { it.transactionId }

    suspend fun addReimbursementEntry(
        txId: Long,
        amountMinor: Long,
        destinationKind: FundingSourceKind,
        personLabel: String?,
        now: Instant = Clock.System.now(),
    ) = database.withTransaction {
        reimbursementEntryDao.insert(
            ReimbursementEntry(
                transactionId = txId,
                amountMinor = amountMinor,
                destinationKind = destinationKind,
                personLabel = personLabel?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now,
            ),
        )
        recomputeReimbursedTotal(txId)
    }

    suspend fun updateReimbursementEntry(entry: ReimbursementEntry) = database.withTransaction {
        reimbursementEntryDao.update(
            entry.copy(personLabel = entry.personLabel?.trim()?.takeIf { it.isNotEmpty() }),
        )
        recomputeReimbursedTotal(entry.transactionId)
    }

    suspend fun deleteReimbursementEntry(entry: ReimbursementEntry) = database.withTransaction {
        reimbursementEntryDao.delete(entry)
        recomputeReimbursedTotal(entry.transactionId)
    }

    /** Re-derives and writes the cached `Transaction.reimbursedMinor` from the entry rows. */
    private suspend fun recomputeReimbursedTotal(txId: Long) {
        val total = reimbursementEntryDao.totalForTransaction(txId)
        transactionDao.updateReimbursed(txId, total.takeIf { it > 0 })
    }

    /**
     * Settings -> "Classify existing transactions" backfill. Iterates rows with NULL FK,
     * runs the classifier, writes the link. Manual entries (rawText IS NULL) get linked
     * to the Cash source via the classifier's MANUAL_SOURCE_APP short-circuit. Returns the
     * count of rows linked. Idempotent — re-running picks up where it left off.
     */
    suspend fun classifyAllUnlinkedTransactions(now: Instant = Clock.System.now()): Int {
        val rows = transactionDao.getUnlinkedFundingSourceRows()
        for (tx in rows) {
            val id = fundingSourceClassifier.classify(tx.rawText, tx.sourceApp, now)
            transactionDao.updateFundingSource(tx.id, id)
        }
        return rows.size
    }

    suspend fun getTransaction(id: Long): Transaction? = transactionDao.getById(id)

    suspend fun countPendingOlderThan(cutoff: Instant): Int =
        transactionDao.countPendingOlderThan(cutoff)

    suspend fun getAllTransactionsBetween(start: Instant, endExclusive: Instant): List<Transaction> =
        transactionDao.getAllBetween(start, endExclusive)

    suspend fun getCategory(id: Long): Category? = categoryDao.getById(id)

    /** Cheap row count without materializing all rows. Used by the cloud-sync guard. */
    suspend fun countAllTransactions(): Long = transactionDao.countAll()

    /** All transactions sorted by occurredAt ASC. Used by CSV export. */
    suspend fun getAllTransactionsOnce(): List<Transaction> = transactionDao.getAllOnce()

    /** All transactions for [currency] sorted by occurredAt ASC. Used by per-currency CSV export. */
    suspend fun getAllTransactionsOnceForCurrency(currency: String): List<Transaction> =
        transactionDao.getAllForCurrency(currency)

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

    suspend fun insertCapturedNotification(
        packageName: String,
        postedAt: Instant,
        amountMinor: Long,
        currency: String,
        rawText: String,
        rewrittenText: String?,
        now: Instant = Clock.System.now(),
    ): Long? {
        val dedupeKey = computeCapturedNotificationDedupeKey(
            packageName = packageName,
            amountMinor = amountMinor,
            currency = currency,
            rawText = rawText,
            postedAt = postedAt,
        )
        val id = capturedNotificationDao.insert(
            CapturedNotification(
                packageName = packageName,
                postedAt = postedAt,
                amountMinor = amountMinor,
                currency = currency,
                rawText = rawText,
                rewrittenText = rewrittenText,
                disposition = CaptureDisposition.PENDING,
                promotedToTxId = null,
                capturedAt = now,
                dedupeKey = dedupeKey,
            ),
        )
        return id.takeIf { it >= 0 }
    }

    suspend fun markPoolEntryNoise(id: Long) {
        capturedNotificationDao.markNoise(id)
    }

    suspend fun rejectPackage(packageName: String, now: Instant = Clock.System.now()) =
        database.withTransaction {
            rejectedSourceDao.upsert(RejectedSource(packageName, now))
            approvedSourceDao.delete(packageName)
            capturedNotificationDao.markPendingNoiseForPackage(packageName)
        }

    suspend fun trackPackage(packageName: String, now: Instant = Clock.System.now()) =
        database.withTransaction {
            rejectedSourceDao.delete(packageName)
            approvedSourceDao.insert(ApprovedSource(packageName, now))
        }

    suspend fun unrejectPackage(packageName: String, now: Instant = Clock.System.now()) {
        trackPackage(packageName, now)
    }

    suspend fun deleteRejectedPoolEntriesBefore(cutoff: Instant): Int =
        capturedNotificationDao.deleteRejectedBefore(cutoff)

    suspend fun promotePoolEntry(
        id: Long,
        edit: PromoteEdit,
        now: Instant = Clock.System.now(),
    ): Long? = database.withTransaction { promotePoolEntryBody(id, edit, now) }

    /**
     * Body of [promotePoolEntry], extracted so unit tests can drive it directly without
     * mocking Room's `withTransaction` extension (which is awkward with mockk's generics).
     * Production callers MUST go through [promotePoolEntry] so the work runs atomically.
     */
    internal suspend fun promotePoolEntryBody(
        id: Long,
        edit: PromoteEdit,
        now: Instant,
    ): Long? {
        val pool = capturedNotificationDao.get(id) ?: return null
        val merchant = edit.merchantRaw.trim()
        if (merchant.isEmpty()) return null

        val merchantNormalized = normalizeMerchant(merchant)
        val bucket = bucketOf(edit.occurredAt)
        val dedupeKey = computeDedupeKey(
            amountMinor = edit.amountMinor,
            merchantNormalized = merchantNormalized,
            occurredAt = edit.occurredAt,
            currency = edit.currency,
        )
        val needsCurrencyConfirmation = if (edit.currency == "MYR") {
            false
        } else {
            ensureTrackedCurrency(edit.currency, now)
            findActiveTrip(edit.currency, edit.occurredAt) == null
        }

        val rowId = transactionDao.insert(
            Transaction(
                amountMinor = edit.amountMinor,
                currency = edit.currency,
                merchantRaw = merchant,
                merchantNormalized = merchantNormalized,
                categoryId = edit.categoryId,
                description = edit.description?.trim()?.takeIf { it.isNotEmpty() },
                occurredAt = edit.occurredAt,
                timeBucket = bucket,
                sourceApp = pool.packageName,
                rawText = pool.rawText,
                direction = Direction.OUT,
                createdAt = now,
                notificationDedupeKey = dedupeKey,
                needsVerification = false,
                needsCurrencyConfirmation = needsCurrencyConfirmation,
            ),
        )
        val txId = rowId.takeIf { it >= 0 } ?: return null
        capturedNotificationDao.markPromoted(id, txId)
        rejectedSourceDao.delete(pool.packageName)
        approvedSourceDao.insert(ApprovedSource(pool.packageName, now))
        return txId
    }

    /**
     * Convenience for the manual-entry sheet. Builds a [Transaction] with `sourceApp = "manual"`,
     * a UUID-based dedupe key (so two manual entries never collide with each other or with a
     * captured notification), `direction = OUT`, and the user-supplied fields. The merchant
     * string is normalized via [normalizeMerchant], and the time bucket is computed from
     * [occurredAt] in the supplied [Instant]. Returns the new row id.
     *
     * [fundingSourceId] is the user-selected funding source from the picker. Defaults to null
     * so callers that pre-date the funding-source feature are unaffected.
     */
    suspend fun addManualTransaction(
        amountMinor: Long,
        merchantRaw: String,
        categoryId: Long?,
        description: String?,
        occurredAt: Instant,
        currency: String = "MYR",
        fundingSourceId: Long? = null,
        reimbursedMinor: Long? = null,
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
            fundingSourceId = fundingSourceId,
            reimbursedMinor = reimbursedMinor,
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
            // Skip merchant→category learning when the merchant is the parser's UNDEFINED
            // sentinel — otherwise the user's first manual labeling of an unattributed tx
            // would auto-categorize every future unattributed tx the same way. Category→
            // description propagation below is still safe (keyed by category, not merchant).
            if (tx.merchantNormalized != UNDEFINED_MERCHANT) {
                merchantMappingDao.upsert(
                    MerchantMapping(
                        merchantNormalized = tx.merchantNormalized,
                        categoryId = categoryId,
                        learnedAt = now,
                    ),
                )
            }
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
            // Same rationale as setCategory: don't poison the UNDEFINED bucket with the
            // first user-typed description. Category-keyed mapping below is still allowed.
            if (tx.merchantNormalized != UNDEFINED_MERCHANT) {
                descriptionMappingDao.upsertMerchant(
                    MerchantDescriptionMapping(
                        merchantNormalized = tx.merchantNormalized,
                        timeBucket = tx.timeBucket,
                        description = cleaned,
                        learnedAt = now,
                    ),
                )
            }
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
     * Also flips `merchantUserEdited` to true via the DAO update, so the Settings →
     * "Re-parse merchants from raw text" sweep skips this row from now on.
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
        // Defensive: callers that pick a currency outside the existing
        // tracked_currencies list (notably the proactive "Start a trip" flow
        // in Currencies settings) would otherwise create a TripWindow row that
        // the Currencies screen renders against, but the row is invisible
        // because the screen iterates tracked_currencies first. Ensure the
        // parent row exists so the trip surfaces in the list.
        ensureTrackedCurrency(currency, now)
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
     * Deletes a trip outright. Like [closeTrip], does NOT re-flag any
     * transactions — rows promoted by this trip stay promoted. Used for
     * Upcoming trips the user changed their mind about, or for cleaning up
     * stale historical trips.
     */
    suspend fun deleteTrip(tripId: Long) {
        tripWindowDao.delete(tripId)
    }

    /**
     * Updates an existing trip's date range. Atomically:
     *   1. Writes the new [startAt] and [endAt] on the trip row.
     *   2. Re-runs [clearCurrencyConfirmationForRange] for the NEW range so
     *      any rows newly covered by the trip get promoted retroactively.
     *
     * Rows that were promoted by this trip's OLD range but fall outside the
     * new range stay promoted — same philosophy as [closeTrip]: the trip is
     * an audit trail, not a live gate. Re-parking previously-promoted rows
     * would also be ambiguous if multiple trips overlap.
     *
     * Returns false when the trip id is unknown.
     */
    suspend fun editTrip(
        tripId: Long,
        startAt: Instant,
        endAt: Instant?,
    ): Boolean = database.withTransaction {
        val trip = tripWindowDao.get(tripId) ?: return@withTransaction false
        tripWindowDao.updateDates(tripId, startAt, endAt)
        transactionDao.clearCurrencyConfirmationForRange(
            currency = trip.currency,
            startAt = startAt,
            endAtExclusive = endAt ?: DISTANT_FUTURE,
        )
        true
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

    /**
     * Re-inserts a transaction that was just deleted, preserving its original id so any
     * restored reimbursement children re-link. Parent is inserted before children to satisfy
     * the reimbursement_entries → transactions foreign key. Used by the edit-sheet "Not a
     * transaction" Undo. Both `transactionDao.insert` and `reimbursementEntryDao.insert` use
     * IGNORE-on-conflict, so restoring the same snapshot more than once is a harmless no-op
     * rather than a crash.
     */
    suspend fun restoreTransaction(tx: Transaction, reimbursements: List<ReimbursementEntry>) =
        database.withTransaction { restoreTransactionBody(tx, reimbursements) }

    /**
     * Body of [restoreTransaction], extracted so unit tests can drive it directly without
     * mocking Room's `withTransaction` extension. Production callers MUST go through
     * [restoreTransaction] so the parent + children insert runs atomically.
     */
    internal suspend fun restoreTransactionBody(
        tx: Transaction,
        reimbursements: List<ReimbursementEntry>,
    ) {
        transactionDao.insert(tx)
        // Children were cascade-deleted with the parent, so no id conflict is possible on re-insert.
        reimbursements.forEach { reimbursementEntryDao.insert(it) }
    }

    /**
     * Runs [CategorizationEngine.categorize] over every transaction with `categoryId == null`,
     * applying the result where non-null. Returns the count of rows updated.
     */
    suspend fun recategorizeNullRows(): Int {
        val rows = transactionDao.getNullCategoryRows()
        var updated = 0
        for (row in rows) {
            val newCategoryId = categorizationEngine.categorize(row.merchantNormalized) ?: continue
            transactionDao.updateCategory(row.id, newCategoryId)
            updated++
        }
        return updated
    }

    /**
     * Re-runs the heuristic parser against each row's stored `rawText` and rewrites the
     * merchant if it differs. Operates ONLY on captured rows where the user hasn't
     * manually fixed the merchant (`merchantUserEdited = 0`) and the row has a
     * `rawText` (excludes manual entries by definition — they have no source text to
     * re-derive against).
     *
     * Amount, currency, occurredAt, category, description, note, and verification state
     * are intentionally NOT touched: the user may have already corrected those, and
     * re-derivation would silently undo their fixes. The dedupe key IS recomputed
     * because it's a function of the new merchant.
     *
     * Rows whose new dedupe key collides with another existing row are counted as
     * `skippedCollision` and left as-is — rare in practice but possible if two prior
     * mis-parses converge onto the same merchant after the fix.
     *
     * Returns counts so the UI can show what happened.
     */
    suspend fun reparseMerchantsFromRawText(): ReparseResult {
        val rows = transactionDao.getReparseCandidates()
        val symbolDefaults = trackedCurrencyDao.getDefaultsForSymbol()
            .associate { it.displaySymbol to it.code }

        var updated = 0
        var unchanged = 0
        var parserMiss = 0
        var skippedCollision = 0

        for (row in rows) {
            val raw = row.rawText ?: continue // SQL filter already excludes null, defensive
            val rewritten = rewriteEngine.apply(row.sourceApp, raw)
            val parsed = heuristicExtractor.extract(
                text = rewritten,
                sourceApp = row.sourceApp,
                postedAt = row.occurredAt,
                symbolDefaults = symbolDefaults,
            )
            if (parsed == null) { parserMiss++; continue }
            val newNormalized = normalizeMerchant(parsed.merchantRaw)
            if (newNormalized == row.merchantNormalized) { unchanged++; continue }
            val newKey = computeDedupeKey(
                amountMinor = row.amountMinor,
                merchantNormalized = newNormalized,
                occurredAt = row.occurredAt,
                currency = row.currency,
            )
            try {
                transactionDao.updateMerchantFromReparse(
                    id = row.id,
                    merchantRaw = parsed.merchantRaw,
                    merchantNormalized = newNormalized,
                    notificationDedupeKey = newKey,
                )
                updated++
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                skippedCollision++
            }
        }

        return ReparseResult(
            scanned = rows.size,
            updated = updated,
            unchanged = unchanged,
            parserMiss = parserMiss,
            skippedCollision = skippedCollision,
        )
    }

    /**
     * Runs [DescriptionEngine.suggest] over every transaction with `description == null`,
     * applying the result where non-null. Returns the count of rows updated.
     */
    suspend fun redescribeNullRows(): Int {
        val rows = transactionDao.getNullDescriptionRows()
        var updated = 0
        for (row in rows) {
            val suggestion = descriptionEngine.suggest(
                merchantNormalized = row.merchantNormalized,
                categoryId = row.categoryId,
                bucket = row.timeBucket,
            ) ?: continue
            transactionDao.updateDescription(row.id, suggestion)
            updated++
        }
        return updated
    }

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
        // 1. Categories: the backup is AUTHORITATIVE. Make the local set match the backup's
        //    exactly — categories absent from the backup are deleted, those present are
        //    upserted (updated in place by name so referencing rows keep their link, or
        //    inserted when new). This stops seed defaults the user deleted/renamed on the
        //    source device from surviving a restore: a fresh install re-seeds the 10 defaults
        //    in onCreate, and the old additive merge never removed the ones the backup omitted.
        //
        //    Guard: an EMPTY backup category list leaves the local set untouched. Real exports
        //    always carry the full category list (BackupExporter exports getAllCategoriesOnce),
        //    so an empty list signals a partial/degenerate payload — never an intent to wipe.
        //
        //    Deletions are FK-safe: transactions.categoryId is ON DELETE SET NULL (rows fall
        //    back to Unverified) and the merchant/category description mapping tables CASCADE.
        //    Backup mappings and transactions are re-resolved by category name in later steps.
        var categoriesCreated = 0
        if (backup.categories.isNotEmpty()) {
            val backupNames = backup.categories.mapTo(mutableSetOf()) { it.name }
            for (local in categoryDao.getAll()) {
                if (local.name !in backupNames) categoryDao.delete(local)
            }
            val survivingByName = categoryDao.getAll().associateBy { it.name }
            for (bc in backup.categories) {
                val local = survivingByName[bc.name]
                if (local == null) {
                    categoryDao.insert(
                        Category(
                            name = bc.name,
                            color = bc.color,
                            isCustom = bc.isCustom,
                            sortOrder = bc.sortOrder,
                            keywordPattern = bc.keywordPattern,
                        ),
                    )
                    categoriesCreated++
                } else {
                    categoryDao.update(
                        local.copy(
                            color = bc.color,
                            isCustom = bc.isCustom,
                            sortOrder = bc.sortOrder,
                            keywordPattern = bc.keywordPattern,
                        ),
                    )
                }
            }
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

        // 11. Funding sources. Match on (sourceAppHint, last4) — displayName is mutable and
        //     excluded from the key to avoid insert conflicts when the same card was renamed
        //     differently on two devices. On conflict, the user-named row wins over auto-named;
        //     among rows of equal isUserNamed, the one with the later updatedAt wins. On no
        //     match, insert as a new row. Build a lookupKey -> local-id map after all
        //     updates/inserts so it reflects the final state.
        val existingFundingSources = fundingSourceDao.getAll()
        // Key: (sourceAppHint, last4)
        val existingFsPair = existingFundingSources.associateBy { fs ->
            fs.sourceAppHint to fs.last4
        }
        // lookupKey = "<sourceAppHint ?: "">|<last4 ?: "">" -> local id
        val lookupKeyToId = mutableMapOf<String, Long>()
        // Seed with existing rows first so unmodified local sources are always referenceable.
        for (fs in existingFundingSources) {
            val key = "${fs.sourceAppHint ?: ""}|${fs.last4 ?: ""}"
            // If multiple local rows share the same lookup key, the last one wins here — that
            // mirrors the first-arrival-wins invariant since the DB unique index on
            // (sourceAppHint, last4) prevents duplicates in practice.
            lookupKeyToId[key] = fs.id
        }
        for (bfs in backup.fundingSources) {
            val pair = bfs.sourceAppHint to bfs.last4
            val existing = existingFsPair[pair]
            val backupUpdatedAt = Instant.fromEpochMilliseconds(bfs.updatedAt)
            val backupCreatedAt = Instant.fromEpochMilliseconds(bfs.createdAt)
            val backupKind = runCatching { FundingSourceKind.valueOf(bfs.kind) }.getOrNull()
                ?: continue
            val lookupKey = "${bfs.sourceAppHint ?: ""}|${bfs.last4 ?: ""}"
            if (existing == null) {
                // No match — insert a new row.
                val newId = fundingSourceDao.insert(
                    FundingSource(
                        kind = backupKind,
                        displayName = bfs.displayName,
                        last4 = bfs.last4,
                        sourceAppHint = bfs.sourceAppHint,
                        isUserNamed = bfs.isUserNamed,
                        createdAt = backupCreatedAt,
                        updatedAt = backupUpdatedAt,
                    ),
                )
                lookupKeyToId[lookupKey] = newId
            } else {
                // Match found on (sourceAppHint, last4). Prefer user-named; if equal, latest
                // updatedAt wins. Apply conflict resolution to mutable fields only.
                val localWins = existing.isUserNamed && !bfs.isUserNamed ||
                    existing.isUserNamed == bfs.isUserNamed && existing.updatedAt >= backupUpdatedAt
                if (!localWins) {
                    fundingSourceDao.update(
                        existing.copy(
                            kind = backupKind,
                            displayName = bfs.displayName,
                            isUserNamed = bfs.isUserNamed,
                            updatedAt = backupUpdatedAt,
                        ),
                    )
                }
                // Either way, the local id is the authoritative one.
                lookupKeyToId[lookupKey] = existing.id
            }
        }

        // 12. Transactions. Insert with IGNORE on the unique notificationDedupeKey index —
        //     local transactions always win on conflict. Backup transactions whose
        //     categoryName doesn't resolve to a local category get inserted with
        //     categoryId = null (Unverified, same as a fresh capture).
        val newTxIdByDedupe = mutableMapOf<String, Long>()
        var transactionsAdded = 0
        for (bt in backup.transactions) {
            val categoryId = bt.categoryName?.let { categoriesByName[it]?.id }
            val fundingSourceId = bt.fundingSourceLookupKey?.let { lookupKeyToId[it] }
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
                    fundingSourceId = fundingSourceId,
                    reimbursedMinor = bt.reimbursedMinor,
                ),
            )
            if (rowId >= 0) transactionsAdded++
            if (rowId >= 0) newTxIdByDedupe[bt.notificationDedupeKey] = rowId
        }

        // 13. Reimbursement entries. Only attach to transactions THIS import inserted
        //     (newTxIdByDedupe) — when a local tx won the dedupe conflict, its entries stay
        //     local. v10 backups carry explicit entries; for older backups (or any inserted
        //     reimbursed tx with no matching entries) synthesize a single DEBIT_BANK entry so
        //     the funding columns reconcile, mirroring the v12->v13 migration backfill.
        val entriesByDedupe = backup.reimbursementEntries.groupBy { it.transactionDedupeKey }
        for ((dedupe, newId) in newTxIdByDedupe) {
            val backupEntries = entriesByDedupe[dedupe]
            if (!backupEntries.isNullOrEmpty()) {
                for (be in backupEntries) {
                    val kind = runCatching { FundingSourceKind.valueOf(be.destinationKind) }.getOrNull()
                        ?: FundingSourceKind.DEBIT_BANK
                    reimbursementEntryDao.insert(
                        ReimbursementEntry(
                            transactionId = newId,
                            amountMinor = be.amountMinor,
                            destinationKind = kind,
                            personLabel = be.personLabel,
                            createdAt = be.createdAt,
                        ),
                    )
                }
            } else {
                val bt = backup.transactions.firstOrNull { it.notificationDedupeKey == dedupe }
                val reimbursed = bt?.reimbursedMinor
                if (bt != null && reimbursed != null && reimbursed > 0) {
                    reimbursementEntryDao.insert(
                        ReimbursementEntry(
                            transactionId = newId,
                            amountMinor = reimbursed,
                            destinationKind = FundingSourceKind.DEBIT_BANK,
                            personLabel = null,
                            createdAt = bt.createdAt,
                        ),
                    )
                }
            }
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
 * SHA-1 hash of `(packageName, amountMinor, currency, rawText, postedAt)` used as the
 * `dedupeKey` column on [CapturedNotification]. Re-fires of the same Android notification
 * — common after a ranking change or content update — produce the same hash, so
 * `OnConflictStrategy.IGNORE` drops the duplicate row at insert time.
 */
fun computeCapturedNotificationDedupeKey(
    packageName: String,
    amountMinor: Long,
    currency: String,
    rawText: String,
    postedAt: Instant,
): String {
    val payload = "$packageName|$amountMinor|$currency|$rawText|${postedAt.toEpochMilliseconds()}"
    return sha1Hex(payload)
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

private fun buildTrackedPackageRows(
    approved: Set<String>,
    rejected: Set<String>,
    stats: List<PoolPackageStats>,
): List<TrackedPackageRow> {
    val statsByPackage = stats.associateBy { it.packageName }
    val packages = SourcePackages.PERMISSIVE_PACKAGES + approved + rejected + statsByPackage.keys
    return packages
        .map { packageName ->
            val stat = statsByPackage[packageName]
            val isBuiltIn = packageName in SourcePackages.PERMISSIVE_PACKAGES
            val status = when {
                packageName in rejected -> PackageStatus.REJECTED
                isBuiltIn || packageName in approved -> PackageStatus.TRACKED
                else -> PackageStatus.UNTRACKED
            }
            TrackedPackageRow(
                packageName = packageName,
                label = SourceLabels.label(packageName),
                status = status,
                isBuiltIn = isBuiltIn,
                poolEntryCountLast30Days = stat?.entryCount ?: 0,
                lastCapturedAt = stat?.lastCapturedAt,
            )
        }
        .sortedWith(compareBy<TrackedPackageRow> { it.status.ordinal }.thenBy { it.label })
}

private fun sha1Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1")
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { "%02x".format(it) }
}
