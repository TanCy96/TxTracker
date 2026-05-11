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
import org.junit.Before
import org.junit.Test

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

    private suspend fun worker(): ListenableWorker.Result =
        CloudSyncWorker.execute(
            prefs = prefs,
            backupExporter = exporter,
            driveClient = driveClient,
        )

    @Test
    fun bails_when_disabled() = runTest {
        every { prefs.enabled } returns MutableStateFlow(false)
        every { prefs.paused } returns MutableStateFlow(false)
        val result = worker()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { exporter.exportToJsonString(any()) }
    }

    @Test
    fun bails_when_paused() = runTest {
        every { prefs.enabled } returns MutableStateFlow(true)
        every { prefs.paused } returns MutableStateFlow(true)
        val result = worker()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { exporter.exportToJsonString(any()) }
    }

    @Test
    fun uploads_and_records_success() = runTest {
        every { prefs.enabled } returns MutableStateFlow(true)
        every { prefs.paused } returns MutableStateFlow(false)
        every { prefs.transactionCutoff } returns MutableStateFlow(null)
        coEvery { exporter.exportToJsonString(null) } returns "{}"
        coEvery { driveClient.upload("{}") } returns Result.success(Unit)

        val result = worker()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        verify { prefs.setLastSync(success = true, error = null) }
    }

    @Test
    fun retries_on_transient_failure() = runTest {
        every { prefs.enabled } returns MutableStateFlow(true)
        every { prefs.paused } returns MutableStateFlow(false)
        every { prefs.transactionCutoff } returns MutableStateFlow(null)
        coEvery { exporter.exportToJsonString(null) } returns "{}"
        coEvery { driveClient.upload("{}") } returns Result.failure(
            TransientNetworkException("503"),
        )

        val result = worker()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        verify { prefs.setLastSync(success = false, error = "503") }
    }

    @Test
    fun fails_on_auth_expired() = runTest {
        every { prefs.enabled } returns MutableStateFlow(true)
        every { prefs.paused } returns MutableStateFlow(false)
        every { prefs.transactionCutoff } returns MutableStateFlow(null)
        coEvery { exporter.exportToJsonString(null) } returns "{}"
        coEvery { driveClient.upload("{}") } returns Result.failure(AuthExpiredException())

        val result = worker()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        verify { prefs.setLastSync(success = false, error = any()) }
    }
}
