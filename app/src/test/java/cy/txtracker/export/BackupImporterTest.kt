package cy.txtracker.export

import android.content.Context
import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.TransactionRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class BackupImporterTest {

    private val repository = mockk<TransactionRepository>(relaxed = true)
    private val importer = BackupImporter(
        context = mockk<Context>(relaxed = true),
        repository = repository,
    )

    @Test
    fun importFromJsonString_rejectsUnsupportedBackupVersion() = runTest {
        val json = """
            {
              "version": ${Backup.CURRENT_VERSION + 1},
              "exportedAt": "2026-06-08T00:00:00Z",
              "categories": [],
              "merchantMappings": [],
              "merchantDescriptionMappings": [],
              "categoryDescriptionMappings": []
            }
        """.trimIndent()

        var thrown: IllegalArgumentException? = null
        try {
            importer.importFromJsonString(json)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown!!.message).contains("not supported")
        coVerify(exactly = 0) { repository.applyBackup(any()) }
    }

    @Test
    fun importFromJsonString_acceptsSupportedBackupVersion() = runTest {
        val json = """
            {
              "version": ${Backup.CURRENT_VERSION},
              "exportedAt": "${Instant.parse("2026-06-08T00:00:00Z")}",
              "categories": [],
              "merchantMappings": [],
              "merchantDescriptionMappings": [],
              "categoryDescriptionMappings": []
            }
        """.trimIndent()

        importer.importFromJsonString(json)

        coVerify { repository.applyBackup(any()) }
    }

    @Test
    fun `v12 backup with tripKey categories is accepted and forwarded to repository`() = runTest {
        val t0 = Instant.parse("2026-06-01T00:00:00Z")
        val tripKey = "USD|${t0.toEpochMilliseconds()}"
        val json = """
            {
              "version": 12,
              "exportedAt": "2026-07-01T00:00:00Z",
              "categories": [
                {"name":"Food","color":-1088257,"sortOrder":0,"isCustom":false,"tripKey":null},
                {"name":"Attractions","color":-6184960,"sortOrder":0,"isCustom":false,"tripKey":"$tripKey"}
              ],
              "merchantMappings": [],
              "merchantDescriptionMappings": [],
              "categoryDescriptionMappings": [],
              "tripWindows": [
                {"currency":"USD","startAt":"2026-06-01T00:00:00Z","endAt":null,"createdAt":"2026-06-01T00:00:00Z"}
              ],
              "transactions": [
                {
                  "amountMinor":2500,"currency":"USD","merchantRaw":"UNIVERSAL STUDIOS",
                  "merchantNormalized":"UNIVERSAL STUDIOS","categoryName":"Attractions",
                  "description":null,"occurredAt":"2026-06-15T10:00:00Z","timeBucket":"MIDDAY",
                  "sourceApp":"com.example.bank","rawText":null,"direction":"OUT",
                  "createdAt":"2026-06-15T10:00:00Z","notificationDedupeKey":"trip-test-001",
                  "needsVerification":false
                }
              ]
            }
        """.trimIndent()

        importer.importFromJsonString(json)

        coVerify { repository.applyBackup(any()) }
    }

    @Test
    fun `v11 backup without tripKey is still accepted`() = runTest {
        val json = """
            {
              "version": 11,
              "exportedAt": "2026-06-08T00:00:00Z",
              "categories": [
                {"name":"Food","color":-1088257,"sortOrder":0,"isCustom":false}
              ],
              "merchantMappings": [],
              "merchantDescriptionMappings": [],
              "categoryDescriptionMappings": []
            }
        """.trimIndent()

        importer.importFromJsonString(json)

        coVerify { repository.applyBackup(any()) }
    }
}
