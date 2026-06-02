package cy.txtracker.export

import cy.txtracker.data.Direction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BackupReimbursementEntry(
    /** Parent transaction's notificationDedupeKey — stable across reinstalls/devices. */
    val transactionDedupeKey: String,
    val amountMinor: Long,
    val destinationKind: String,   // FundingSourceKind name
    val personLabel: String?,
    val createdAt: Instant,
)

@Serializable
data class BackupFundingSource(
    val kind: String,            // FundingSourceKind name
    val displayName: String,
    val last4: String?,
    val sourceAppHint: String?,
    val isUserNamed: Boolean,
    val createdAt: Long,         // epoch ms
    val updatedAt: Long,
)

/**
 * Wire format for the local JSON backup. Versioned so future changes to the schema can be
 * handled by reading the [version] field before deciding how to parse the rest.
 *
 * Mappings reference categories by **name** rather than id so a backup remains valid across
 * reinstalls (where ids are regenerated) and across devices (where the same name maps to a
 * different id). On import, missing categories are created from [BackupCategory] and then
 * mappings are translated to local ids.
 *
 * Version history:
 *   v5 – baseline (transactions, categories, mappings, notes, sources)
 *   v6 – added [trackedCurrencies] and [tripWindows] (foreign-currency tracking)
 *   v7 – added [keywordPattern] on [BackupCategory]
 *   v8 – added [fundingSources] and [BackupTransaction.fundingSourceLookupKey]
 *   v9 – added [BackupTransaction.reimbursedMinor] (reimbursed-by-others share)
 *   v10 – added [reimbursementEntries] (multi-person reimbursement entries; the per-tx
 *         reimbursedMinor is the cached sum and is also carried on each BackupTransaction)
 */
@Serializable
data class Backup(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Instant,
    @Serializable(with = YearMonthSerializer::class)
    val transactionCutoff: YearMonth? = null,
    val categories: List<BackupCategory>,
    val merchantMappings: List<BackupMerchantMapping>,
    val merchantDescriptionMappings: List<BackupMerchantDescriptionMapping>,
    val categoryDescriptionMappings: List<BackupCategoryDescriptionMapping>,
    val merchantNotes: List<BackupMerchantNote> = emptyList(),
    val userFacingSources: List<BackupUserFacingSource> = emptyList(),
    val approvedSources: List<BackupApprovedSource> = emptyList(),
    val transactions: List<BackupTransaction> = emptyList(),
    val trackedCurrencies: List<BackupTrackedCurrency> = emptyList(),
    val tripWindows: List<BackupTripWindow> = emptyList(),
    val fundingSources: List<BackupFundingSource> = emptyList(),
    val reimbursementEntries: List<BackupReimbursementEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 10
    }
}

@Serializable
data class BackupCategory(
    val name: String,
    val color: Int,
    val sortOrder: Int,
    val isCustom: Boolean,
    val keywordPattern: String? = null,
)

@Serializable
data class BackupMerchantMapping(
    val merchant: String,
    val categoryName: String,
    val learnedAt: Instant,
)

@Serializable
data class BackupMerchantDescriptionMapping(
    val merchant: String,
    val bucket: TimeBucket,
    val description: String,
    val learnedAt: Instant,
)

@Serializable
data class BackupCategoryDescriptionMapping(
    val categoryName: String,
    val bucket: TimeBucket,
    val description: String,
    val learnedAt: Instant,
)

@Serializable
data class BackupUserFacingSource(
    val packageName: String,
    val addedAt: Instant,
)

@Serializable
data class BackupApprovedSource(
    val packageName: String,
    val firstApprovedAt: Instant,
)

@Serializable
data class BackupMerchantNote(
    val merchant: String,
    val note: String,
    val updatedAt: Instant,
)

@Serializable
data class BackupTrackedCurrency(
    val code: String,
    val displaySymbol: String,
    val isDefaultForSymbol: Boolean,
    val addedAt: Instant,
)

@Serializable
data class BackupTripWindow(
    val currency: String,
    val startAt: Instant,
    val endAt: Instant?,
    val createdAt: Instant,
)

@Serializable
data class BackupTransaction(
    val amountMinor: Long,
    val currency: String,
    val merchantRaw: String,
    val merchantNormalized: String,
    val categoryName: String?,
    val description: String?,
    val occurredAt: Instant,
    val timeBucket: TimeBucket,
    val sourceApp: String,
    val rawText: String?,
    val direction: Direction,
    val createdAt: Instant,
    val notificationDedupeKey: String,
    val needsVerification: Boolean,
    val needsCurrencyConfirmation: Boolean = false,
    /** "<sourceAppHint>|<last4>" — null when unlinked. Empty strings for null parts (e.g. Cash = "|"). */
    val fundingSourceLookupKey: String? = null,
    /** Portion others reimbursed, in minor units. null = not reimbursed. Default keeps v8 backups parseable. */
    val reimbursedMinor: Long? = null,
)
