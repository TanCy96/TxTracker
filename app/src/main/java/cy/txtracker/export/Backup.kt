package cy.txtracker.export

import cy.txtracker.data.Direction
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Wire format for the local JSON backup. Versioned so future changes to the schema can be
 * handled by reading the [version] field before deciding how to parse the rest.
 *
 * Mappings reference categories by **name** rather than id so a backup remains valid across
 * reinstalls (where ids are regenerated) and across devices (where the same name maps to a
 * different id). On import, missing categories are created from [BackupCategory] and then
 * mappings are translated to local ids.
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
) {
    companion object {
        const val CURRENT_VERSION = 5
    }
}

@Serializable
data class BackupCategory(
    val name: String,
    val color: Int,
    val sortOrder: Int,
    val isCustom: Boolean,
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
)
