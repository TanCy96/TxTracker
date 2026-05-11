package cy.txtracker.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import cy.txtracker.data.TransactionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Builds a JSON backup of categories and learned mappings (merchant → category, merchant +
 * bucket → description, category + bucket → description) and writes it to cacheDir/exports/
 * for sharing via the existing FileProvider.
 *
 * Transactions are intentionally NOT included — they're large, contain potentially sensitive
 * merchant strings the user may not want to back up, and the categorization "voice" of the
 * mappings is what's actually expensive to lose. Re-capture from notifications fills in
 * future spending; restore brings back the lessons.
 */
@Singleton
class BackupExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TransactionRepository,
) {
    suspend fun export(): Uri {
        // Load everything once, join in memory by id → name. Categories table is small
        // (10s of rows at most), so this is cheaper than per-row lookups.
        val categories = repository.getAllCategoriesOnce()
        val nameById = categories.associate { it.id to it.name }

        val backup = Backup(
            exportedAt = Clock.System.now(),
            categories = categories.map {
                BackupCategory(
                    name = it.name,
                    color = it.color,
                    sortOrder = it.sortOrder,
                    isCustom = it.isCustom,
                )
            },
            merchantMappings = repository.observeMerchantMappings().first().mapNotNull { m ->
                val categoryName = nameById[m.categoryId] ?: return@mapNotNull null
                BackupMerchantMapping(
                    merchant = m.merchantNormalized,
                    categoryName = categoryName,
                    learnedAt = m.learnedAt,
                )
            },
            merchantDescriptionMappings = repository.observeMerchantDescriptionMappings()
                .first().map { d ->
                    BackupMerchantDescriptionMapping(
                        merchant = d.merchantNormalized,
                        bucket = d.timeBucket,
                        description = d.description,
                        learnedAt = d.learnedAt,
                    )
                },
            categoryDescriptionMappings = repository.observeCategoryDescriptionMappings()
                .first().mapNotNull { d ->
                    val categoryName = nameById[d.categoryId] ?: return@mapNotNull null
                    BackupCategoryDescriptionMapping(
                        categoryName = categoryName,
                        bucket = d.timeBucket,
                        description = d.description,
                        learnedAt = d.learnedAt,
                    )
                },
            userFacingSources = repository.observeUserFacingSources()
                .first().map { s ->
                    BackupUserFacingSource(packageName = s.packageName, addedAt = s.addedAt)
                },
            approvedSources = repository.observeApprovedSources()
                .first().map { s ->
                    BackupApprovedSource(packageName = s.packageName, firstApprovedAt = s.firstApprovedAt)
                },
        )

        val json = JSON.encodeToString(backup)
        val file = writeToCache(json)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    private fun writeToCache(json: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val filename = "txtracker-backup-${System.currentTimeMillis()}.json"
        val file = File(dir, filename)
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    companion object {
        /** Pretty-printed for human inspection. `encodeDefaults` so the version field is always
         *  emitted — matters when a future format upgrade reads the field to decide how to parse. */
        val JSON: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
