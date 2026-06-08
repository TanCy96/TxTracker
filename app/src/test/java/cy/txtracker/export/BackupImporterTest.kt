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
}
