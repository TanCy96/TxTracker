package cy.txtracker.cloud

import android.util.Log
import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import cy.txtracker.export.BackupExporter
import cy.txtracker.service.CloudSyncPrefs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.days

class CloudSyncWorkerTest {

    @Before
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    private val prefs = mockk<CloudSyncPrefs>(relaxed = true)
    private val exporter = mockk<BackupExporter>()
    private val driveClient = mockk<DriveClient>()
    private val now = Instant.parse("2026-05-15T12:00:00Z")

    private fun stubEnabledPrefs(baseline: Long = CloudSyncGuard.UNKNOWN_BASELINE) {
        every { prefs.enabled } returns MutableStateFlow(true)
        every { prefs.paused } returns MutableStateFlow(false)
        every { prefs.transactionCutoff } returns MutableStateFlow(null)
        every { prefs.lastUploadedRowCount } returns MutableStateFlow(baseline)
    }

    private suspend fun worker(currentRowCount: Long): ListenableWorker.Result =
        CloudSyncWorker.execute(
            prefs = prefs,
            backupExporter = exporter,
            driveClient = driveClient,
            currentRowCount = currentRowCount,
            now = now,
        )

    @Test
    fun bails_when_disabled() = runTest {
        every { prefs.enabled } returns MutableStateFlow(false)
        every { prefs.paused } returns MutableStateFlow(false)
        val result = worker(currentRowCount = 10)
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { exporter.exportToJsonString(any()) }
    }

    @Test
    fun bails_when_paused() = runTest {
        every { prefs.enabled } returns MutableStateFlow(true)
        every { prefs.paused } returns MutableStateFlow(true)
        val result = worker(currentRowCount = 10)
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { exporter.exportToJsonString(any()) }
    }

    @Test
    fun guard_blocks_upload_when_local_emptied() = runTest {
        // Baseline says 100 rows, current is 0 — the smoking-gun scenario from ISSUE.md #1.
        stubEnabledPrefs(baseline = 100L)
        val result = worker(currentRowCount = 0)
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { exporter.exportToJsonString(any()) }
        coVerify(exactly = 0) { driveClient.uploadDated(any(), any()) }
        verify { prefs.setSyncBlockedReason(match { it.contains("empty") }) }
        verify { prefs.setLastSync(success = false, error = match { it.contains("empty") }) }
        // Baseline NOT clobbered.
        verify(exactly = 0) { prefs.setLastUploadedRowCount(any()) }
    }

    @Test
    fun happy_path_uploads_dated_and_updates_baseline_and_prunes() = runTest {
        stubEnabledPrefs(baseline = CloudSyncGuard.UNKNOWN_BASELINE)
        coEvery { exporter.exportToJsonString(null) } returns "{}"
        coEvery { driveClient.uploadDated("{}", now) } returns Result.success(Unit)
        coEvery { driveClient.listAll() } returns Result.success(
            listOf(BackupFile("new-id", "txtracker-backup-20260515T120000Z.json", now)),
        )

        val result = worker(currentRowCount = 3)

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        verify { prefs.setLastUploadedRowCount(3L) }
        verify { prefs.setSyncBlockedReason(null) }
        verify { prefs.setLastSync(success = true, error = null) }
    }

    @Test
    fun prunes_older_files_per_retention_policy() = runTest {
        // 22 old files all tied at now-60d + the newly-uploaded one. Kotlin sortedByDescending
        // is stable, so insertion order survives: new-id (rank 1) then old-1..old-22 (rank 2..23).
        // Retention keeps rank ≤ 20 → deletes the three at rank 21-23 (old-20..old-22).
        val oldFiles = (1..22).map {
            BackupFile(
                id = "old-$it",
                name = "txtracker-backup-old-$it.json",
                modifiedAt = now.minus(60.days),
            )
        }
        val afterUpload = oldFiles + BackupFile("new-id", "txtracker-backup-20260515T120000Z.json", now)
        stubEnabledPrefs(baseline = 100L)
        coEvery { exporter.exportToJsonString(null) } returns "{}"
        coEvery { driveClient.uploadDated("{}", now) } returns Result.success(Unit)
        coEvery { driveClient.listAll() } returns Result.success(afterUpload)
        coEvery { driveClient.delete(any<String>()) } returns Result.success(Unit)

        val result = worker(currentRowCount = 100)

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // The newest 20 by modifiedAt survive (new-id + old-1..old-19). old-20..old-22 are
        // beyond rank 20 AND older than 30 days → deleted.
        coVerify { driveClient.delete("old-20") }
        coVerify { driveClient.delete("old-21") }
        coVerify { driveClient.delete("old-22") }
    }

    @Test
    fun retries_on_transient_upload_failure() = runTest {
        stubEnabledPrefs()
        coEvery { exporter.exportToJsonString(null) } returns "{}"
        coEvery { driveClient.uploadDated("{}", now) } returns Result.failure(
            TransientNetworkException("503"),
        )
        val result = worker(currentRowCount = 10)
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        verify { prefs.setLastSync(success = false, error = "503") }
        verify(exactly = 0) { prefs.setLastUploadedRowCount(any()) }
    }

    @Test
    fun fails_on_auth_expired() = runTest {
        stubEnabledPrefs()
        coEvery { exporter.exportToJsonString(null) } returns "{}"
        coEvery { driveClient.uploadDated("{}", now) } returns Result.failure(AuthExpiredException())
        val result = worker(currentRowCount = 10)
        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        verify { prefs.setLastSync(success = false, error = any()) }
        verify(exactly = 0) { prefs.setLastUploadedRowCount(any()) }
    }

    @Test
    fun prune_failure_does_not_fail_the_worker() = runTest {
        // Upload succeeds; listAll fails → the worker should still return success and update
        // baseline. The upload is the load-bearing operation.
        stubEnabledPrefs()
        coEvery { exporter.exportToJsonString(null) } returns "{}"
        coEvery { driveClient.uploadDated("{}", now) } returns Result.success(Unit)
        coEvery { driveClient.listAll() } returns Result.failure(TransientNetworkException("list 503"))

        val result = worker(currentRowCount = 5)

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        verify { prefs.setLastUploadedRowCount(5L) }
        verify { prefs.setLastSync(success = true, error = null) }
    }
}
