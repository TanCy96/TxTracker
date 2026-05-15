package cy.txtracker.cloud

import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

/**
 * Metadata for a single backup file in Drive AppData. Comes from [DriveClient.listAll].
 *
 * @property id Drive file id, used for download/delete by id.
 * @property name The filename in AppData (e.g., `txtracker-backup-20260515T120000Z.json`).
 * @property modifiedAt Drive's `modifiedTime` for the file.
 */
data class BackupFile(
    val id: String,
    val name: String,
    val modifiedAt: Instant,
)

/**
 * Decides which backup files to delete given a current set in Drive AppData.
 *
 * A file is **kept** iff one of:
 *   - Its rank in the newest-first ordering is ≤ [MAX_KEEP_COUNT] (20), OR
 *   - Its age (now - modifiedAt) is less than [MAX_KEEP_AGE_DAYS] (30 days).
 *
 * A file is **deleted** iff both conditions fail: rank > 20 AND age ≥ 30 days.
 *
 * The "OR" gives complementary guarantees:
 *   - Light users get long history (last 20 backups regardless of age — could span months).
 *   - Heavy users get fresh history (all uploads from the last 30 days regardless of count).
 *
 * Pure function — caller is responsible for invoking [DriveClient.delete] on each returned id.
 */
object BackupRetentionPolicy {

    const val MAX_KEEP_COUNT: Int = 20
    const val MAX_KEEP_AGE_DAYS: Long = 30

    fun selectToDelete(files: List<BackupFile>, now: Instant): List<String> {
        val sortedNewestFirst = files.sortedByDescending { it.modifiedAt }
        val cutoffInstant = now.minus(MAX_KEEP_AGE_DAYS.days)
        return sortedNewestFirst
            .withIndex()
            .filter { (rankZeroBased, file) ->
                val rank = rankZeroBased + 1
                // Delete iff rank > MAX_KEEP_COUNT AND age >= MAX_KEEP_AGE_DAYS.
                // Equivalently: NOT (rank <= MAX_KEEP_COUNT OR modifiedAt > cutoffInstant).
                rank > MAX_KEEP_COUNT && file.modifiedAt <= cutoffInstant
            }
            .map { it.value.id }
    }
}
