package cy.txtracker.export

import android.content.Context
import android.net.Uri
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Reads a JSON backup file at the given content [Uri], parses it into [Backup], and applies
 * the merge via [TransactionRepository.applyBackup]. Throws [IOException] if the file can't
 * be opened, [kotlinx.serialization.SerializationException] if the JSON is malformed, or
 * [IllegalArgumentException] if the backup version isn't supported.
 *
 * The merge itself runs in a single Room transaction inside the repository, so a partial
 * failure mid-import doesn't leave the DB in a half-restored state.
 */
@Singleton
class BackupImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TransactionRepository,
) {
    suspend fun import(uri: Uri): ImportResult {
        val json = readText(uri) ?: throw IOException("Couldn't open backup file")
        val backup = JSON.decodeFromString<Backup>(json)
        require(backup.version == Backup.CURRENT_VERSION) {
            "Backup version ${backup.version} is not supported by this app version " +
                "(expected ${Backup.CURRENT_VERSION})."
        }
        return repository.applyBackup(backup)
    }

    private fun readText(uri: Uri): String? =
        context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }

    companion object {
        val JSON: Json = Json { ignoreUnknownKeys = true }
    }
}

/** Counts of what a single applyBackup call did, for the success message in the UI. */
data class ImportResult(
    val categoriesCreated: Int,
    val merchantMappingsAdded: Int,
    val merchantMappingsUpdated: Int,
    val merchantDescriptionsAdded: Int,
    val merchantDescriptionsUpdated: Int,
    val categoryDescriptionsAdded: Int,
    val categoryDescriptionsUpdated: Int,
    val skippedDueToMissingCategory: Int,
)
