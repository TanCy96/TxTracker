package cy.txtracker.cloud

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Minimal abstraction that supplies the OAuth token. Implemented by
 * [GoogleSignInStateProvider] in production and faked in tests.
 */
interface SignInTokenSource {
    suspend fun currentAccessToken(): String
    fun currentAccountEmail(): String?
}

/**
 * Drive REST client for the app-private AppData folder. Hits the endpoints needed for a
 * single-canonical-file backup workflow:
 *   - `GET drive/v3/files?spaces=appDataFolder&q=...` to find existing id
 *   - `POST upload/drive/v3/files?uploadType=multipart` to create
 *   - `PATCH upload/drive/v3/files/<id>?uploadType=media` to update
 *   - `GET drive/v3/files/<id>?alt=media` to download
 *   - `DELETE drive/v3/files/<id>` for sign-out-and-delete
 *
 * Returns [Result] with typed exceptions from [CloudSyncException] so the worker can
 * decide retry vs failure.
 */
@Singleton
class DriveClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val signInState: SignInTokenSource,
    /** Override for tests (MockWebServer). Production uses the real Google base URL. */
    private val baseUrl: String = "https://www.googleapis.com/",
) {
    suspend fun upload(jsonContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            val existingId = findExistingFileId(token)
            if (existingId != null) updateFile(token, existingId, jsonContent)
            else createFile(token, jsonContent)
        }
    }

    suspend fun download(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            val fileId = findExistingFileId(token) ?: return@runCatching null
            getFileContent(token, fileId)
        }
    }

    suspend fun exists(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            findExistingFileId(token) != null
        }
    }

    suspend fun delete(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = signInState.currentAccessToken()
            val fileId = findExistingFileId(token) ?: return@runCatching
            executeOrThrow(
                Request.Builder()
                    .url("${baseUrl}drive/v3/files/$fileId")
                    .delete()
                    .header("Authorization", "Bearer $token")
                    .build(),
            ).use { /* discard body */ }
        }
    }

    private fun findExistingFileId(token: String): String? {
        val url = "${baseUrl}drive/v3/files?spaces=appDataFolder" +
            "&q=" + percentEncode("name='$FILE_NAME'") +
            "&fields=files(id)"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        executeOrThrow(req).use { resp ->
            val body = resp.body?.string().orEmpty()
            val parsed = json.decodeFromString(FileListResponse.serializer(), body)
            return parsed.files.firstOrNull()?.id
        }
    }

    private fun createFile(token: String, content: String) {
        val metadata = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
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

    private fun updateFile(token: String, fileId: String, content: String) {
        val body = content.toRequestBody("application/json; charset=UTF-8".toMediaType())
        val req = Request.Builder()
            .url("${baseUrl}upload/drive/v3/files/$fileId?uploadType=media")
            .patch(body)
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

    /** Throws typed exceptions on HTTP errors. Returns the response on 2xx so the caller
     *  can read the body. Caller must `.use { }` to close. */
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

    @Serializable
    private data class FileListResponse(val files: List<FileEntry> = emptyList())

    @Serializable
    private data class FileEntry(val id: String)

    private companion object {
        const val FILE_NAME = "txtracker-backup.json"
        val MULTIPART_RELATED = "multipart/related".toMediaType()
    }
}
