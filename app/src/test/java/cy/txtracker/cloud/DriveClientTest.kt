package cy.txtracker.cloud

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class DriveClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DriveClient

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        client = DriveClient(
            okHttpClient = OkHttpClient(),
            json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
            signInState = FakeSignInState("test-token"),
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun exists_returns_true_when_file_in_listing() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"files":[{"id":"abc123","name":"txtracker-backup.json","modifiedTime":"2026-05-15T10:00:00.000Z"}]}""",
            ),
        )
        val result = client.exists().getOrThrow()
        assertThat(result).isTrue()
    }

    @Test
    fun exists_returns_false_when_listing_is_empty() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        val result = client.exists().getOrThrow()
        assertThat(result).isFalse()
    }

    @Test
    fun download_returns_null_when_no_file() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        val result = client.download().getOrThrow()
        assertThat(result).isNull()
    }

    @Test
    fun download_returns_content_when_file_exists() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"files":[{"id":"abc","name":"txtracker-backup-20260515T100000Z.json","modifiedTime":"2026-05-15T10:00:00.000Z"}]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"version":5}"""))
        val result = client.download().getOrThrow()
        assertThat(result).isEqualTo("""{"version":5}""")
    }

    @Test
    fun listAll_returns_files_with_metadata() = runTest {
        // Drive responds with two backup files.
        server.enqueue(
            MockResponse().setBody(
                """{"files":[
                    {"id":"abc","name":"txtracker-backup-20260515T100000Z.json","modifiedTime":"2026-05-15T10:00:00.000Z"},
                    {"id":"def","name":"txtracker-backup-20260514T100000Z.json","modifiedTime":"2026-05-14T10:00:00.000Z"}
                ]}""",
            ),
        )
        val result = client.listAll().getOrThrow()
        assertThat(result).hasSize(2)
        assertThat(result.map { it.id }).containsExactly("abc", "def")
        assertThat(result[0].name).isEqualTo("txtracker-backup-20260515T100000Z.json")
    }

    @Test
    fun listAll_empty_when_no_files() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        val result = client.listAll().getOrThrow()
        assertThat(result).isEmpty()
    }

    @Test
    fun uploadDated_creates_file_with_timestamped_name() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"new-id"}"""))
        val result = client.uploadDated("""{"hello":"world"}""", kotlinx.datetime.Instant.parse("2026-05-15T10:30:45Z"))
        assertThat(result.isSuccess).isTrue()
        val req = server.takeRequest()
        assertThat(req.path).contains("uploadType=multipart")
        // The multipart body should contain the timestamped name.
        val body = req.body.readUtf8()
        assertThat(body).contains("txtracker-backup-20260515T103045Z.json")
    }

    @Test
    fun uploadDated_returns_transient_on_5xx() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = client.uploadDated("x", kotlinx.datetime.Instant.parse("2026-05-15T10:30:45Z"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TransientNetworkException::class.java)
    }

    @Test
    fun uploadDated_returns_drive_api_on_4xx_other() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))
        val result = client.uploadDated("x", kotlinx.datetime.Instant.parse("2026-05-15T10:30:45Z"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(DriveApiException::class.java)
    }

    @Test
    fun download_returns_newest_files_content() = runTest {
        // listAll returns 2 files, the newer one (abc) wins.
        server.enqueue(
            MockResponse().setBody(
                """{"files":[
                    {"id":"abc","name":"txtracker-backup-20260515T100000Z.json","modifiedTime":"2026-05-15T10:00:00.000Z"},
                    {"id":"def","name":"txtracker-backup-20260514T100000Z.json","modifiedTime":"2026-05-14T10:00:00.000Z"}
                ]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"newest":true}"""))
        val result = client.download().getOrThrow()
        assertThat(result).isEqualTo("""{"newest":true}""")
        server.takeRequest() // list
        val getReq = server.takeRequest()
        assertThat(getReq.path).contains("files/abc")
    }

    @Test
    fun download_by_id_fetches_specific_file() = runTest {
        server.enqueue(MockResponse().setBody("""{"specific":true}"""))
        val result = client.download("specific-id").getOrThrow()
        assertThat(result).isEqualTo("""{"specific":true}""")
        val req = server.takeRequest()
        assertThat(req.path).contains("files/specific-id")
    }

    @Test
    fun delete_by_id_deletes_specific_file() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.delete("doomed-id")
        assertThat(result.isSuccess).isTrue()
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("DELETE")
        assertThat(req.path).contains("files/doomed-id")
    }

    @Test
    fun delete_no_args_deletes_all_backup_files() = runTest {
        // sign-out-and-delete must wipe every dated file plus any legacy file.
        server.enqueue(
            MockResponse().setBody(
                """{"files":[
                    {"id":"a","name":"txtracker-backup-20260515T100000Z.json","modifiedTime":"2026-05-15T10:00:00.000Z"},
                    {"id":"b","name":"txtracker-backup.json","modifiedTime":"2026-05-14T10:00:00.000Z"}
                ]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.delete()
        assertThat(result.isSuccess).isTrue()
        // 1 list + 2 deletes.
        server.takeRequest()
        val firstDelete = server.takeRequest()
        val secondDelete = server.takeRequest()
        assertThat(firstDelete.method).isEqualTo("DELETE")
        assertThat(secondDelete.method).isEqualTo("DELETE")
    }

    private class FakeSignInState(private val token: String) : SignInTokenSource {
        override suspend fun currentAccessToken(): String = token
        override fun currentAccountEmail(): String? = "test@example.com"
    }
}
