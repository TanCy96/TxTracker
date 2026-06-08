package cy.txtracker.export

import android.content.Context
import android.net.Uri
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
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
        return importFromJsonString(json)
    }

    /** Convenience for cloud restore: parse a JSON string and call applyBackup. */
    suspend fun importFromJsonString(json: String): ImportResult {
        val backup = BackupExporter.JSON.decodeFromString(Backup.serializer(), json)
        require(backup.version in SUPPORTED_VERSIONS) {
            "Backup version ${backup.version} is not supported by this app version " +
                "(expected ${Backup.CURRENT_VERSION})."
        }
        return repository.applyBackup(backup)
    }

    /** Fresh-install heuristic for the auto-restore prompt: both transactions and merchant
     *  mappings are empty. Seed categories don't count. */
    suspend fun isLocalDataEmpty(): Boolean {
        val transactions = repository.getAllTransactionsOnce()
        val mappingCount = repository.observeMerchantMappings().first().size
        return transactions.isEmpty() && mappingCount == 0
    }

    private fun readText(uri: Uri): String? =
        context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }

    companion object {
        val JSON: Json = Json { ignoreUnknownKeys = true }

        /** Versions this app can import. v5 is forward-compat: missing fields default cleanly. */
        val SUPPORTED_VERSIONS = 5..Backup.CURRENT_VERSION
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
    val transactionsAdded: Int = 0,
)
