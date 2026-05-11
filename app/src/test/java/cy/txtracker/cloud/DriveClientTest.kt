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
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"abc123"}]}"""))
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
    fun upload_creates_new_when_no_existing_file() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"new-id"}"""))
        val result = client.upload("""{"hello":"world"}""")
        assertThat(result.isSuccess).isTrue()
        val listReq = server.takeRequest()
        assertThat(listReq.path).contains("appDataFolder")
        val uploadReq = server.takeRequest()
        assertThat(uploadReq.path).contains("uploadType=multipart")
    }

    @Test
    fun upload_updates_when_file_exists() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"existing"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"existing"}"""))
        val result = client.upload("""{"hello":"world"}""")
        assertThat(result.isSuccess).isTrue()
        server.takeRequest()
        val updateReq = server.takeRequest()
        assertThat(updateReq.path).contains("files/existing")
        assertThat(updateReq.method).isEqualTo("PATCH")
    }

    @Test
    fun download_returns_null_when_no_file() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        val result = client.download().getOrThrow()
        assertThat(result).isNull()
    }

    @Test
    fun download_returns_content_when_file_exists() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"abc"}]}"""))
        server.enqueue(MockResponse().setBody("""{"version":5}"""))
        val result = client.download().getOrThrow()
        assertThat(result).isEqualTo("""{"version":5}""")
    }

    @Test
    fun upload_returns_transient_on_5xx() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        val result = client.upload("x")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TransientNetworkException::class.java)
    }

    @Test
    fun upload_returns_drive_api_on_4xx_other() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))
        val result = client.upload("x")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(DriveApiException::class.java)
    }

    private class FakeSignInState(private val token: String) : SignInTokenSource {
        override suspend fun currentAccessToken(): String = token
        override fun currentAccountEmail(): String? = "test@example.com"
    }
}
