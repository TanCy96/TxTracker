package cy.txtracker.cloud

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Supplies the OAuth token. Implemented by [GoogleSignInStateProvider] in production, faked
 * in tests.
 */
interface SignInTokenSource {
    suspend fun currentAccessToken(): String
    fun currentAccountEmail(): String?
}

/**
 * Drive REST client for the app-private AppData folder. Operates over multiple dated backup
 * files (`txtracker-backup-<ISO>.json`), not a single canonical file. Filename pattern uses
 * ISO 8601 basic form so lexicographic sort = chronological sort.
 *
 * Endpoints:
 *   - `GET drive/v3/files?spaces=appDataFolder&q=name contains 'txtracker-backup'` — list
 *   - `POST upload/drive/v3/files?uploadType=multipart` — upload new dated file
 *   - `GET drive/v3/files/<id>?alt=media` — download specific file
 *   - `DELETE drive/v3/files/<id>` — delete specific file
 *
 * Legacy `txtracker-backup.json` (single-file model) is picked up by the prefix-based list
 * query, so existing installs are backward-compatible without an explicit migration.
 */
@Singleton
class DriveClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val signInState: SignInTokenSource,
    private val baseUrl: String = "https://www.googleapis.com/",
) {

    /** Lists every backup file in AppData (legacy `txtracker-backup.json` and dated form). */
    suspend fun listAll(): Result<List<BackupFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            listAllImpl(token)
        }
    }

    /**
     * Creates a new dated backup file. Timestamp formatted as ISO 8601 basic:
     * `txtracker-backup-20260515T103045Z.json` — lexicographic sort = chronological.
     */
    suspend fun uploadDated(content: String, at: Instant = Clock.System.now()): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = signInState.currentAccessToken()
                val name = "$FILE_PREFIX-${formatBasicIso(at)}.json"
                createFile(token, name, content)
            }
        }

    /**
     * Newest backup's content. Returns null if AppData has no backups. Used by Onboarding /
     * Settings "Restore from cloud" (latest).
     */
    suspend fun download(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            val newest = listAllImpl(token).maxByOrNull { it.modifiedAt }
                ?: return@runCatching null
            getFileContent(token, newest.id)
        }
    }

    /** Downloads a specific file by id. Used by the Settings restore-picker. */
    suspend fun download(fileId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            getFileContent(token, fileId)
        }
    }

    /** True iff at least one backup file is in AppData. */
    suspend fun exists(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            listAllImpl(token).isNotEmpty()
        }
    }

    /** Deletes every backup file in AppData. Used by sign-out-and-delete. */
    suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            listAllImpl(token).forEach { deleteFile(token, it.id) }
        }
    }

    /** Deletes a specific file by id. Used by the worker to prune older backups. */
    suspend fun delete(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            deleteFile(token, fileId)
        }
    }

    private fun listAllImpl(token: String): List<BackupFile> {
        val q = "name contains '$FILE_PREFIX' and trashed = false"
        val url = "${baseUrl}drive/v3/files?spaces=appDataFolder" +
            "&q=" + percentEncode(q) +
            "&fields=files(id,name,modifiedTime)" +
            "&pageSize=1000"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        executeOrThrow(req).use { resp ->
            val body = resp.body?.string().orEmpty()
            val parsed = json.decodeFromString(FileListResponse.serializer(), body)
            return parsed.files.map { f ->
                BackupFile(
                    id = f.id,
                    name = f.name,
                    modifiedAt = Instant.parse(f.modifiedTime),
                )
            }
        }
    }

    private fun createFile(token: String, name: String, content: String) {
        val metadata = """{"name":"$name","parents":["appDataFolder"]}"""
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(
                metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()),
            )
            .addPart(content.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("${baseUrl}upload/drive/v3/files?uploadType=multipart")
            .post(body)
            .header("Authorization", "Bearer $token")
            .build()
        executeOrThrow(req).use { /* discard body */ }
    }

    private fun deleteFile(token: String, fileId: String) {
        val req = Request.Builder()
            .url("${baseUrl}drive/v3/files/$fileId")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        executeOrThrow(req).use { /* discard body */ }
    }

    private fun getFileContent(token: String, fileId: String): String {
        val req = Request.Builder()
            .url("${baseUrl}drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        executeOrThrow(req).use { resp ->
            return resp.body?.string().orEmpty()
        }
    }

    private fun executeOrThrow(request: Request): Response {
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw TransientNetworkException(e.message ?: "Network failure", e)
        }
        when (response.code) {
            in 200..299 -> return response
            401, 403 -> {
                response.close()
                throw AuthExpiredException()
            }
            429, in 500..599 -> {
                val msg = response.message
                response.close()
                throw TransientNetworkException("HTTP ${response.code} $msg")
            }
            else -> {
                val msg = response.body?.string().orEmpty()
                response.close()
                throw DriveApiException(response.code, msg)
            }
        }
    }

    private fun percentEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** ISO 8601 basic format: `yyyyMMddTHHmmssZ` — lexicographic == chronological. */
    private fun formatBasicIso(at: Instant): String {
        val s = at.toString() // e.g. 2026-05-15T10:30:45.123Z or 2026-05-15T10:30:45Z
        val noFraction = s.substringBefore('.').let { if (it.endsWith("Z")) it else "${it}Z" }
        // 2026-05-15T10:30:45Z → 20260515T103045Z
        return noFraction.replace("-", "").replace(":", "")
    }

    @Serializable
    private data class FileListResponse(val files: List<FileEntry> = emptyList())

    @Serializable
    private data class FileEntry(val id: String, val name: String, val modifiedTime: String)

    private companion object {
        const val FILE_PREFIX = "txtracker-backup"
        val MULTIPART_RELATED = "multipart/related".toMediaType()
    }
}
