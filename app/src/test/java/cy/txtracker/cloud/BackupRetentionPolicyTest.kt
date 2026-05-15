package cy.txtracker.cloud

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import org.junit.Test

class BackupRetentionPolicyTest {

    private val now = Instant.parse("2026-05-15T12:00:00Z")

    private fun file(id: String, daysAgo: Long): BackupFile =
        BackupFile(id = id, name = "txtracker-backup-x.json", modifiedAt = now.minus(daysAgo.days))

    @Test
    fun keeps_everything_when_below_count_and_age_limits() {
        // 5 files, all fresh — nothing should be deleted.
        val files = (1..5).map { file("id-$it", daysAgo = it.toLong()) }
        val ids = BackupRetentionPolicy.selectToDelete(files, now)
        assertThat(ids).isEmpty()
    }

    @Test
    fun keeps_top_20_when_all_old() {
        // 25 files, all 60+ days old — keep the 20 newest, delete the 5 oldest.
        val files = (1..25).map { file("id-$it", daysAgo = 60L + it) }
        val ids = BackupRetentionPolicy.selectToDelete(files, now)
        assertThat(ids).hasSize(5)
        // The 5 deleted are id-21..id-25 (oldest by daysAgo).
        assertThat(ids).containsExactly("id-21", "id-22", "id-23", "id-24", "id-25")
    }

    @Test
    fun keeps_files_younger_than_30_days_even_beyond_top_20() {
        // 30 files all 10 days old — rank-wise 20 of them would be deleted, but the
        // OR-30-days rule keeps everything because all are < 30 days.
        val files = (1..30).map { file("id-$it", daysAgo = 10L) }
        val ids = BackupRetentionPolicy.selectToDelete(files, now)
        assertThat(ids).isEmpty()
    }

    @Test
    fun mixed_age_keeps_recent_and_top_20() {
        // 15 files at 5 days old + 10 files at 100 days old.
        // Recent 15 all kept by age. Old 10: 5 are within top-20-by-recency overall (slots 16-20), kept by rank.
        // Old 10's slots 21-25 are pruned.
        val recent = (1..15).map { file("recent-$it", daysAgo = 5L) }
        val old = (1..10).map { file("old-$it", daysAgo = 100L + it) }
        val ids = BackupRetentionPolicy.selectToDelete(recent + old, now)
        assertThat(ids).containsExactly("old-6", "old-7", "old-8", "old-9", "old-10")
    }

    @Test
    fun empty_input_returns_empty() {
        assertThat(BackupRetentionPolicy.selectToDelete(emptyList(), now)).isEmpty()
    }
}
