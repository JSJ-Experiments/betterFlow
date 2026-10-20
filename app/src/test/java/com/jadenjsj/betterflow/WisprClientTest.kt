package com.jadenjsj.betterflow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class WisprClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryStore
    private lateinit var http: OkHttpClient
    private lateinit var endpoints: WisprClient.Endpoints

    private class MemoryStore(@Volatile var session: WisprSession? = null) : WisprSessionStore {
        override fun load() = session
        override fun save(session: WisprSession) { this.session = session }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = MemoryStore()
        http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        endpoints = WisprClient.Endpoints(
            apiBaseUrl = server.url("/").toString(),
            supabaseRefreshUrl = server.url("/auth/v1/token?grant_type=refresh_token").toString(),
            workosRefreshUrl = server.url("/user_management/authenticate").toString(),
        )
    }

    @After
    fun tearDown() {
        server.close()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun client() = WisprClient(store, http, endpoints) { 1_000L }
    private fun response(json: JSONObject, code: Int = 200) = MockResponse(code = code, body = json.toString())
    private fun request(): RecordedRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
    private fun body(request: RecordedRequest) = JSONObject(requireNotNull(request.body).utf8())
    private fun session(expiry: Long = 10_000L, issuer: String = "https://api.workos.com/") = WisprSession(
        "user@example.test", testJwt(issuer, expiry), "refresh-before", expiry,
        userId = "canonical-id",
    )
    private suspend fun failure(block: suspend () -> Any?): Throwable {
        try { block() } catch (failure: Throwable) { return failure }
        throw AssertionError("Expected request failure")
    }

    @Test
    fun loginAdvertisesWorkosAndStoresProviderAndIdentity() = runBlocking {
        val token = testJwt()
        server.enqueue(response(JSONObject().put("access_token", token).put("refresh_token", "rotated")
            .put("provider", "workos").put("user_id", "canonical-user")))
        val session = client().login("user@example.test", "test-password")
        val request = request()
        assertEquals("/email/signin", request.url.encodedPath)
        assertEquals("POST", request.method)
        assertEquals(WisprProtocol.CLIENT_HEADER, request.headers[WisprProtocol.CLIENT_HEADER_NAME])
        assertNull(request.headers["Authorization"])
        assertEquals(true, body(request).getBoolean("workos_capable"))
        assertEquals(false, body(request).getBoolean("is_platform_user"))
        assertEquals("test-password", body(request).getString("password"))
        assertEquals(WisprAuthProvider.WORKOS, session.provider)
        assertEquals("canonical-user", session.userId)
        assertEquals(session, store.load())
    }

    @Test
    fun verificationChallengeIsNotSavedAndOtpCompletesSameLogin() = runBlocking {
        server.enqueue(response(JSONObject().put("verification_method", "email_otp")
            .put("pending_authentication_token", "pending-test")))
        val challenge = failure { client().login("user@example.test", "password") }
        assertTrue(challenge is WisprClient.EmailVerificationRequired)
        challenge as WisprClient.EmailVerificationRequired
        assertEquals("pending-test", challenge.pendingToken)
        assertFalse(challenge.toString().contains("pending-test"))
        assertNull(store.load())
        request()
        server.enqueue(response(JSONObject().put("access_token", testJwt()).put("provider", "workos")))
        val session = client().verifyEmailOtp(challenge.email, challenge.pendingToken, " 123456 ")
        val otpRequest = request()
        assertEquals("/email/verify-email-otp", otpRequest.url.encodedPath)
        val otpBody = body(otpRequest)
        assertEquals(setOf("code", "pending_authentication_token"), otpBody.keys().asSequence().toSet())
        assertEquals("123456", otpBody.getString("code"))
        assertEquals("pending-test", otpBody.getString("pending_authentication_token"))
        assertEquals("user@example.test", session.email)
    }

    @Test
    fun workosRefreshUsesPublicClientIdAndPersistsRotatedPair() = runBlocking {
        store.session = session(expiry = 900)
        val next = testJwt(expiresAt = 20_000)
        server.enqueue(response(JSONObject().put("access_token", next).put("refresh_token", "refresh-after")))
        assertEquals(next, client().freshAccessToken())
        val request = request()
        assertEquals("/user_management/authenticate", request.url.encodedPath)
        val payload = body(request)
        assertEquals("refresh_token", payload.getString("grant_type"))
        assertEquals(endpoints.workosClientId, payload.getString("client_id"))
        assertEquals("refresh-before", payload.getString("refresh_token"))
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["apikey"])
        assertEquals("refresh-after", store.load()?.refreshToken)
        assertEquals(20_000L, store.load()?.expiresAt)
        assertEquals(WisprAuthProvider.WORKOS, store.load()?.provider)
        assertEquals("canonical-id", store.load()?.userId)
    }

    @Test
    fun oldSupabaseSessionStillRefreshesWithoutProviderField() = runBlocking {
        store.session = session(900, "https://dodjkfqhwrzqjwkfnthl.supabase.co/auth/v1")
        val next = testJwt("https://dodjkfqhwrzqjwkfnthl.supabase.co/auth/v1", 20_000)
        server.enqueue(response(JSONObject().put("access_token", next)))
        assertEquals(next, client().freshAccessToken())
        val request = request()
        assertEquals("/auth/v1/token", request.url.encodedPath)
        assertEquals("refresh_token", request.url.queryParameter("grant_type"))
        assertNotNull(request.headers["apikey"])
        assertEquals("Bearer ${request.headers["apikey"]}", request.headers["Authorization"])
        assertEquals("refresh-before", store.load()?.refreshToken)
        assertEquals(WisprAuthProvider.SUPABASE, store.load()?.provider)
    }

    @Test
    fun transcriptionMatchesOfficialRawAuthorizationAndWavPayload() = runBlocking {
        val session = session()
        store.session = session
        server.enqueue(response(JSONObject().put("text", "hello world")))
        val wav = byteArrayOf(82, 73, 70, 70, 1, 2)
        var raw = ""
        assertEquals("hello world", client().transcribe(wav) { raw = it })
        val request = request()
        assertEquals("/llm/api", request.url.encodedPath)
        assertEquals(session.accessToken, request.headers["Authorization"])
        assertEquals(WisprProtocol.CLIENT_HEADER, request.headers[WisprProtocol.CLIENT_HEADER_NAME])
        assertEquals(Base64.getEncoder().encodeToString(wav), body(request).getString("audio"))
        assertEquals("hello world", JSONObject(raw).getString("text"))
    }

    @Test
    fun nonJson401RefreshesOnceAndRetriesWithRotatedToken() = runBlocking {
        val previous = session()
        store.session = previous
        val next = testJwt(expiresAt = 20_000)
        server.enqueue(MockResponse(code = 401, body = "<html>unauthorized</html>"))
        server.enqueue(response(JSONObject().put("access_token", next).put("refresh_token", "new-refresh")))
        server.enqueue(response(JSONObject().put("text", "recovered")))
        assertEquals("recovered", client().transcribe(byteArrayOf(1)))
        assertEquals(previous.accessToken, request().headers["Authorization"])
        assertEquals("/user_management/authenticate", request().url.encodedPath)
        assertEquals(next, request().headers["Authorization"])
        assertEquals(3, server.requestCount)
    }

    @Test
    fun second401StopsWithoutRefreshLoop() = runBlocking {
        store.session = session()
        server.enqueue(MockResponse(code = 401))
        server.enqueue(response(JSONObject().put("access_token", testJwt(expiresAt = 20_000))
            .put("refresh_token", "new-refresh")))
        server.enqueue(MockResponse(code = 401, body = "sensitive-rejected-token"))
        val error = failure { client().transcribe(byteArrayOf(1)) }
        assertEquals("Wispr request failed (HTTP 401)", error.message)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun forbiddenDoesNotRefreshOrAssumeQuotaAndNonJsonErrorsDoNotLeakBodies() = runBlocking {
        store.session = session()
        for (code in listOf(403, 500)) {
            server.enqueue(MockResponse(code = code, body = "sensitive-token-and-transcript"))
            if (code == 403) {
                server.enqueue(response(testUsageHistory(900)))
                server.enqueue(response(JSONObject().put("plan", "FLOW_BASIC")))
            }
            val error = failure { client().transcribe(byteArrayOf(1)) }
            assertEquals("Wispr request failed (HTTP $code)", error.message)
            assertFalse(error.toString().contains("sensitive"))
            assertNull(error.cause)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun malformedSuccessAndNullTextFailWithoutResponseLeakage() = runBlocking {
        store.session = session()
        server.enqueue(MockResponse(body = "not-json-sensitive"))
        val malformed = failure { client().transcribe(byteArrayOf(1)) }
        assertEquals("Wispr returned an invalid JSON response (HTTP 200)", malformed.message)
        assertNull(malformed.cause)
        server.enqueue(response(JSONObject().put("text", JSONObject.NULL).put("secret", "sensitive")))
        assertEquals("Wispr returned no text", failure { client().transcribe(byteArrayOf(1)) }.message)
    }

    @Test
    fun concurrentClientsShareSingleRefreshRotation() = runBlocking {
        store.session = session(900)
        val next = testJwt(expiresAt = 20_000)
        server.enqueue(response(JSONObject().put("access_token", next).put("refresh_token", "new-refresh")))
        val tokens = listOf(client(), client()).map { wispr -> async { wispr.freshAccessToken() } }.awaitAll()
        assertEquals(listOf(next, next), tokens)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun usageUsesReadOnlyHistoryAndSubscriptionRoutesWithCurrentAuth() = runBlocking {
        store.session = session()
        server.enqueue(response(testUsageHistory(125)))
        server.enqueue(response(JSONObject().put("plan", "FLOW_BASIC")))
        val usage = client().usage()
        assertEquals(125L, usage.usedWords)
        assertEquals(875L, usage.remainingWords)
        assertEquals(1_000L, usage.limitWords)
        assertEquals(1_500L, usage.hardLimitWords)
        val stats = request()
        val plan = request()
        assertEquals("/history/stats", stats.url.encodedPath)
        assertEquals("/api/v1/payment/subscription", plan.url.encodedPath)
        for (request in listOf(stats, plan)) {
            assertEquals("GET", request.method)
            assertEquals(store.load()?.accessToken, request.headers["Authorization"])
            assertEquals(WisprProtocol.CLIENT_HEADER, request.headers[WisprProtocol.CLIENT_HEADER_NAME])
        }
    }

    @Test
    fun quotaFailureRequiresFreshConfirmedBasicHardCap() = runBlocking {
        store.session = session()
        server.enqueue(MockResponse(code = 429, body = "not a quota diagnosis by itself"))
        server.enqueue(response(testUsageHistory(1_500)))
        server.enqueue(response(JSONObject().put("plan", "FLOW_BASIC")))
        val error = failure { client().transcribe(byteArrayOf(1)) }
        assertTrue(error is WeeklyUsageLimitException)
        assertTrue(error.message.orEmpty().contains("weekly hard word limit reached"))
        assertTrue(error.message.orEmpty().contains("1500/1500"))
        assertTrue(error.message.orEmpty().contains("Wait for the reset"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun softGraceAndPaidPlansAreNotMisdiagnosedAsHardQuotaFailures() = runBlocking {
        store.session = session()
        for ((used, plan) in listOf(1_200 to "FLOW_BASIC", 10_000 to "FLOW_PRO_MONTHLY")) {
            server.enqueue(MockResponse(code = 403))
            server.enqueue(response(testUsageHistory(used)))
            server.enqueue(response(JSONObject().put("plan", plan)))
            val error = failure { client().transcribe(byteArrayOf(1)) }
            assertFalse(error is WeeklyUsageLimitException)
            assertEquals("Wispr request failed (HTTP 403)", error.message)
        }
    }

    @Test
    fun unavailableUsagePreservesOriginalFailure() = runBlocking {
        store.session = session()
        server.enqueue(MockResponse(code = 503, body = "private-server-details"))
        val original = IllegalStateException("original safe failure")
        assertEquals(original, client().explainTranscriptionFailure(original))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun usageNeverCombinesTwoDifferentAccounts() = runBlocking {
        val first = session()
        val second = session().copy(email = "other@example.test", userId = "other-id")
        var loads = 0
        val switchingStore = object : WisprSessionStore {
            override fun load(): WisprSession = if (++loads < 3) first else second
            override fun save(session: WisprSession) = Unit
        }
        server.enqueue(response(testUsageHistory(1_500)))
        val error = failure { WisprClient(switchingStore, http, endpoints) { 1_000L }.usage() }
        assertTrue(error.message.orEmpty().contains("account changed"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun accountSwitchAfter401DoesNotUploadWithAnotherAccountsToken() = runBlocking {
        val first = session()
        val second = session().copy(email = "other@example.test", userId = "other-id")
        var loads = 0
        val switchingStore = object : WisprSessionStore {
            override fun load(): WisprSession = if (++loads == 1) first else second
            override fun save(session: WisprSession) = Unit
        }
        server.enqueue(MockResponse(code = 401))
        val error = failure { WisprClient(switchingStore, http, endpoints) { 1_000L }.transcribe(byteArrayOf(1)) }
        assertTrue(error.message.orEmpty().contains("account changed"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun bridgeRefreshRejectsAnExpectedAccountThatWasSwitchedAway() = runBlocking {
        val first = session()
        store.session = session().copy(email = "other@example.test", userId = "other-id")
        val error = failure { client().refreshStreamingSession(first.accessToken, expectedSession = first) }
        assertTrue(error.message.orEmpty().contains("account changed"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun delegatedRefreshUsesCompanionWithoutSendingLocalCredentials() = runBlocking {
        val previous = session(900).copy(refreshToken = null)
        val renewed = session(20_000).copy(refreshToken = "companion-rotated")
        var saved: WisprSession? = null
        var rejected: String? = null
        val delegated = object : WisprSessionStore {
            override fun load() = previous
            override fun save(session: WisprSession) { saved = session }
            override fun refresh(rejectedAccessToken: String?): WisprSession {
                rejected = rejectedAccessToken
                return renewed
            }
        }
        assertEquals(renewed, WisprClient(delegated, http, endpoints) { 1_000L }.freshStreamingSession())
        assertEquals(previous.accessToken, rejected)
        assertEquals(renewed, saved)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unknownIssuerNeverReceivesRefreshCredentials() = runBlocking {
        store.session = session(900, "https://unknown.example")
        val error = failure { client().freshAccessToken() }
        assertTrue(error.message.orEmpty().contains("Unknown Wispr session issuer"))
        assertEquals(0, server.requestCount)
    }
}
