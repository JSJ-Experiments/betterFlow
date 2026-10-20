package com.jadenjsj.betterflow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

class WisprBrowserAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val id = "32dde0dc-1b75-4d3b-bfe3-88ba148257e4"

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
    }
    @After fun teardown() {
        server.close()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
    private fun client(pause: suspend (Long) -> Unit = {}) =
        WisprBrowserAuth(http, server.url("/").toString(), pause = pause)
    private fun response(json: JSONObject) = MockResponse(code = 200, body = json.toString())
    private fun completed(extra: JSONObject = JSONObject()) = extra.put("status", "complete")
        .put("access_token", testJwt(extra = JSONObject().put("urn:wispr:user_external_id", id)))
        .put("refresh_token", "test-browser-refresh")
    private suspend fun failure(block: suspend () -> Any?): Throwable {
        try { block() } catch (error: Throwable) { return error }
        throw AssertionError("Expected sign-in failure")
    }

    @Test fun loginUrlUsesOfficialDeviceFlowAndDoesNotReuseBrowserAccountAutomatically() {
        val attempt = WisprBrowserAuth.Attempt(google = true)
        val url = URI(attempt.loginUrl)
        assertEquals("https", url.scheme)
        assertEquals("wisprflow.ai", url.host)
        assertEquals("/login", url.path)
        assertTrue(url.query.contains("device_code=${attempt.deviceCode}"))
        assertTrue(url.query.contains("workos_capable=true"))
        assertTrue(url.query.contains("client=android"))
        assertTrue(url.query.contains("auto_signin=false"))
        assertTrue(url.query.contains("provider=google"))
        assertFalse(WisprBrowserAuth.Attempt().loginUrl.contains("provider="))
        assertNotEquals(attempt.deviceCode, WisprBrowserAuth.Attempt().deviceCode)
        assertFalse(attempt.toString().contains(attempt.deviceCode.toString()))
    }

    @Test fun pendingThenCompleteLoadsReadOnlyProfileWithRawAuthorization() = runBlocking {
        val attempt = WisprBrowserAuth.Attempt()
        val waits = mutableListOf<Long>()
        server.enqueue(response(JSONObject().put("status", "pending")))
        server.enqueue(response(completed()))
        server.enqueue(response(JSONObject().put("email", "browser@example.test").put("user_id", id)))
        val session = client { waits += it }.awaitSession(attempt)
        assertEquals("browser@example.test", session.email)
        assertEquals(id, session.userId)
        assertEquals(WisprAuthProvider.WORKOS, session.provider)
        assertEquals("test-browser-refresh", session.refreshToken)
        assertEquals(listOf(2_000L), waits)
        repeat(2) {
            val poll = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/device/poll", poll.url.encodedPath)
            assertEquals(attempt.deviceCode.toString(), poll.url.queryParameter("device_code"))
            assertNull(poll.headers["Authorization"])
            assertEquals(WisprProtocol.CLIENT_HEADER, poll.headers[WisprProtocol.CLIENT_HEADER_NAME])
        }
        val profile = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/api/v1/user/profile", profile.url.encodedPath)
        assertEquals(session.accessToken, profile.headers["Authorization"])
    }

    @Test fun cancelStopsPollingWithoutFetchingOrSavingASession() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        server.enqueue(response(JSONObject().put("status", "pending")))
        val task = async { client { waiting.complete(Unit); awaitCancellation() }
            .awaitSession(WisprBrowserAuth.Attempt()) }
        waiting.await()
        task.cancelAndJoin()
        assertEquals(1, server.requestCount)
    }

    @Test fun serverTimeoutEndsAttemptWithoutLeakingResponseOrDeviceCode() = runBlocking {
        val attempt = WisprBrowserAuth.Attempt()
        server.enqueue(response(JSONObject().put("status", "timeout").put("secret", "sensitive")))
        val error = failure { client().awaitSession(attempt) }
        assertTrue(error.message.orEmpty().contains("expired"))
        assertFalse(error.stackTraceToString().contains("sensitive"))
        assertFalse(error.stackTraceToString().contains(attempt.deviceCode.toString()))
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedResponseIsSanitized() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "not-json-sensitive"))
        val error = failure { client().awaitSession(WisprBrowserAuth.Attempt()) }
        assertEquals("Wispr returned an invalid browser sign-in response.", error.message)
        assertFalse(error.stackTraceToString().contains("not-json-sensitive"))
    }

    @Test fun incompleteOrUnknownIssuerSessionIsRejected() = runBlocking {
        for (json in listOf(
            completed().put("refresh_token", JSONObject.NULL),
            completed().put("access_token", testJwt("https://unknown.example")),
            JSONObject().put("status", "complete").put("refresh_token", "sensitive"),
        )) {
            server.enqueue(response(json))
            val error = failure { client().awaitSession(WisprBrowserAuth.Attempt()) }
            assertTrue(error.message.orEmpty().contains("incomplete session"))
            assertFalse(error.stackTraceToString().contains("sensitive"))
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun profileCannotReplaceCanonicalAccountWithAnotherUser() = runBlocking {
        server.enqueue(response(completed()))
        server.enqueue(response(JSONObject().put("email", "other@example.test").put("user_id", UUID.randomUUID())))
        val error = failure { client().awaitSession(WisprBrowserAuth.Attempt()) }
        assertTrue(error.message.orEmpty().contains("did not match"))
        assertFalse(error.stackTraceToString().contains("test-browser-refresh"))
    }

    @Test fun unavailableProfileDoesNotDiscardValidRotatingTokens() = runBlocking {
        server.enqueue(response(completed()))
        server.enqueue(MockResponse(code = 503, body = "sensitive"))
        val session = client().awaitSession(WisprBrowserAuth.Attempt())
        assertEquals(id, session.userId)
        assertEquals("test-browser-refresh", session.refreshToken)
    }

    @Test fun rateLimitHonorsBoundedRetryAfterAndThenCompletes() = runBlocking {
        val waits = mutableListOf<Long>()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "999999").build())
        server.enqueue(response(completed()))
        server.enqueue(response(JSONObject().put("user_id", id)))
        client { waits += it }.awaitSession(WisprBrowserAuth.Attempt())
        assertEquals(listOf(30_000L), waits)
    }

    @Test fun localDeadlineCancelsPendingNetworkWork() = runBlocking {
        server.enqueue(response(JSONObject().put("status", "pending")))
        val client = WisprBrowserAuth(http, server.url("/").toString(), timeoutMillis = 100L)
        val error = failure { client.awaitSession(WisprBrowserAuth.Attempt()) }
        assertTrue(error.message.orEmpty().contains("timed out"))
        assertTrue(server.requestCount <= 1)
    }

    @Test fun unknownStatusAndHttpFailuresNeverEchoResponseBody() = runBlocking {
        server.enqueue(response(JSONObject().put("status", "sensitive")))
        server.enqueue(MockResponse(code = 403, body = "sensitive"))
        repeat(2) {
            val error = failure { client().awaitSession(WisprBrowserAuth.Attempt()) }
            assertFalse(error.stackTraceToString().contains("sensitive"))
        }
    }
}
