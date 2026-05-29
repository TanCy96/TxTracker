package cy.txtracker.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import cy.txtracker.data.FundingSourceDao
import cy.txtracker.data.TrackedCurrencyDao
import cy.txtracker.data.TransactionRepository
import cy.txtracker.data.TripWindowDao
import cy.txtracker.domain.MalaysiaTimeZone
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Builds a JSON backup of learning state + transactions, optionally filtered by a
 * year-month floor on transactions.
 *
 * Two entry points:
 *   - [export]: writes to cacheDir/exports/ for sharing via FileProvider (the existing
 *     manual-backup flow). Always uses no cutoff — manual exports include everything.
 *   - [exportToJsonString]: returns the JSON content directly. Honors a cutoff if
 *     supplied. Used by cloud-sync upload.
 *
 * Plus [saveLocalRollbackSnapshot] for before-restore / before-cutoff-change safety —
 * writes a full no-cutoff snapshot to cacheDir, retains last 5 per name prefix.
 */
@Singleton
class BackupExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TransactionRepository,
    private val trackedCurrencyDao: TrackedCurrencyDao,
    private val tripWindowDao: TripWindowDao,
    private val fundingSourceDao: FundingSourceDao,
    private val slDebitDao: cy.txtracker.data.SlDebitDao,
) {
    suspend fun export(): Uri {
        val json = exportToJsonString(transactionCutoff = null)
        val file = writeToCache(json, prefix = "txtracker-backup")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /**
     * Builds the backup JSON. If [transactionCutoff] is non-null, only transactions with
     * `occurredAt >= cutoffStartInstant` are included; cutoffStart is the first instant
     * of the cutoff year-month in [MalaysiaTimeZone].
     */
    suspend fun exportToJsonString(transactionCutoff: YearMonth? = null): String {
        val categories = repository.getAllCategoriesOnce()
        val nameById = categories.associate { it.id to it.name }

        val txs = if (transactionCutoff != null) {
            val cutoffStart = LocalDate(transactionCutoff.year, transactionCutoff.month, 1)
                .atStartOfDayIn(MalaysiaTimeZone)
            repository.getAllTransactionsOnceFrom(cutoffStart)
        } else {
            repository.getAllTransactionsOnce()
        }

        val fundingSources = fundingSourceDao.getAll()
        // Build a map from id to lookup key so tx.fundingSourceId resolves in O(1).
        val fundingSourceKeyById = fundingSources.associate { fs ->
            fs.id to "${fs.sourceAppHint ?: ""}|${fs.last4 ?: ""}"
        }

        val backup = Backup(
            exportedAt = Clock.System.now(),
            transactionCutoff = transactionCutoff,
            categories = categories.map {
                BackupCategory(
                    name = it.name,
                    color = it.color,
                    sortOrder = it.sortOrder,
                    isCustom = it.isCustom,
                    keywordPattern = it.keywordPattern,
                )
            },
            merchantMappings = repository.observeMerchantMappings().first().mapNotNull { m ->
                val categoryName = nameById[m.categoryId] ?: return@mapNotNull null
                BackupMerchantMapping(
                    merchant = m.merchantNormalized,
                    categoryName = categoryName,
                    learnedAt = m.learnedAt,
                )
            },
            merchantDescriptionMappings = repository.observeMerchantDescriptionMappings()
                .first().map { d ->
                    BackupMerchantDescriptionMapping(
                        merchant = d.merchantNormalized,
                        bucket = d.timeBucket,
                        description = d.description,
                        learnedAt = d.learnedAt,
                    )
                },
            categoryDescriptionMappings = repository.observeCategoryDescriptionMappings()
                .first().mapNotNull { d ->
                    val categoryName = nameById[d.categoryId] ?: return@mapNotNull null
                    BackupCategoryDescriptionMapping(
                        categoryName = categoryName,
                        bucket = d.timeBucket,
                        description = d.description,
                        learnedAt = d.learnedAt,
                    )
                },
            merchantNotes = repository.observeMerchantNotes().first().map { n ->
                BackupMerchantNote(
                    merchant = n.merchantNormalized,
                    note = n.note,
                    updatedAt = n.updatedAt,
                )
            },
            userFacingSources = repository.observeUserFacingSources().first().map { s ->
                BackupUserFacingSource(packageName = s.packageName, addedAt = s.addedAt)
            },
            approvedSources = repository.observeApprovedSources().first().map { s ->
                BackupApprovedSource(packageName = s.packageName, firstApprovedAt = s.firstApprovedAt)
            },
            transactions = txs.map { tx ->
                BackupTransaction(
                    amountMinor = tx.amountMinor,
                    currency = tx.currency,
                    merchantRaw = tx.merchantRaw,
                    merchantNormalized = tx.merchantNormalized,
                    categoryName = tx.categoryId?.let { nameById[it] },
                    description = tx.description,
                    occurredAt = tx.occurredAt,
                    timeBucket = tx.timeBucket,
                    sourceApp = tx.sourceApp,
                    rawText = tx.rawText,
                    direction = tx.direction,
                    createdAt = tx.createdAt,
                    notificationDedupeKey = tx.notificationDedupeKey,
                    needsVerification = tx.needsVerification,
                    needsCurrencyConfirmation = tx.needsCurrencyConfirmation,
                    fundingSourceLookupKey = tx.fundingSourceId?.let { fundingSourceKeyById[it] },
                    slShareMinor = tx.slShareMinor,
                )
            },
            fundingSources = fundingSources.map { fs ->
                BackupFundingSource(
                    kind = fs.kind.name,
                    displayName = fs.displayName,
                    last4 = fs.last4,
                    sourceAppHint = fs.sourceAppHint,
                    isUserNamed = fs.isUserNamed,
                    createdAt = fs.createdAt.toEpochMilliseconds(),
                    updatedAt = fs.updatedAt.toEpochMilliseconds(),
                )
            },
            trackedCurrencies = trackedCurrencyDao.observeAll().first().map {
                BackupTrackedCurrency(
                    code = it.code,
                    displaySymbol = it.displaySymbol,
                    isDefaultForSymbol = it.isDefaultForSymbol,
                    addedAt = it.addedAt,
                )
            },
            tripWindows = tripWindowDao.observeAll().first().map {
                BackupTripWindow(
                    currency = it.currency,
                    startAt = it.startAt,
                    endAt = it.endAt,
                    createdAt = it.createdAt,
                )
            },
            slDebitAccount = slDebitDao.getAccount()?.let {
                BackupSlDebitAccount(displayName = it.displayName, defaultSharePercent = it.defaultSharePercent)
            },
            slDebitDeposits = slDebitDao.getDepositsOnce().map {
                BackupSlDebitDeposit(
                    amountMinor = it.amountMinor,
                    occurredAt = it.occurredAt,
                    note = it.note,
                    createdAt = it.createdAt,
                )
            },
        )

        return JSON.encodeToString(backup)
    }

    /**
     * Writes a no-cutoff snapshot to cacheDir/exports/ with a `<name>-<ts>.json`
     * filename so the user can roll back via the existing "Restore from backup"
     * Settings flow. Cleans up older snapshots with the same prefix, keeping the
     * most recent 5.
     */
    suspend fun saveLocalRollbackSnapshot(name: String): Uri {
        val json = exportToJsonString(transactionCutoff = null)
        val file = writeToCache(json, prefix = name)
        pruneSnapshots(prefix = name, keep = 5)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    private fun writeToCache(json: String, prefix: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val filename = "$prefix-${System.currentTimeMillis()}.json"
        val file = File(dir, filename)
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    /** Keeps the most recent [keep] snapshots whose filename starts with `$prefix-`,
     *  deleting older ones. Cheap idempotent loop. */
    private fun pruneSnapshots(prefix: String, keep: Int) {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) return
        val matches = dir.listFiles { f -> f.name.startsWith("$prefix-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        matches.drop(keep).forEach { it.delete() }
    }

    companion object {
        /** Pretty-printed for human inspection. `encodeDefaults` so the version field is
         *  always emitted — matters when a future format upgrade reads the field to
         *  decide how to parse. */
        val JSON: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
