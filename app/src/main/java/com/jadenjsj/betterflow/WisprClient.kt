package com.jadenjsj.betterflow

import android.content.Context
import java.util.Base64
import com.jadenjsj.betterflow.AuthStore.Companion.nonBlankString
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import org.json.JSONException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WisprClient(
    private val auth: WisprSessionStore,
    private val client: OkHttpClient = defaultHttpClient(),
    private val endpoints: Endpoints = Endpoints(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    constructor(context: Context) : this(AuthStore(context.applicationContext))

    data class Endpoints(
        val apiBaseUrl: String = "https://api.wisprflow.ai",
        val supabaseRefreshUrl: String = "https://dodjkfqhwrzqjwkfnthl.supabase.co/auth/v1/token?grant_type=refresh_token",
        val workosRefreshUrl: String = "https://api.workos.com/user_management/authenticate",
        // Public OAuth client identifier, not a client secret.
        val workosClientId: String = "client_01JEQ2G2M8BTPMXB7GS1WJA4X5",
    )

    class EmailVerificationRequired(val pendingToken: String, val email: String) :
        IllegalStateException("Enter the verification code sent to your email")

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val activeTranscriptionCall = AtomicReference<Call?>(null)

    suspend fun login(email: String, password: String): WisprSession = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("is_platform_user", false)
            .put("workos_capable", true)
        saveLoginResponse(postJson(apiUrl("/email/signin"), payload, emptyMap()), email)
    }

    suspend fun verifyEmailOtp(email: String, pendingToken: String, code: String): WisprSession =
        withContext(Dispatchers.IO) {
            require(pendingToken.isNotBlank() && code.isNotBlank()) { "Enter your email verification code" }
            val payload = JSONObject().put("code", code.trim()).put("pending_authentication_token", pendingToken)
            saveLoginResponse(postJson(apiUrl("/email/verify-email-otp"), payload, emptyMap()), email)
        }

    private fun saveLoginResponse(response: JSONObject, email: String): WisprSession {
        if (response.nonBlankString("access_token") == null) {
            val pending = response.nonBlankString("pending_authentication_token")
            if (pending != null && response.nonBlankString("verification_method") == "email_otp") {
                throw EmailVerificationRequired(pending, email)
            }
            // Never put an authentication response (which can contain tokens) in an exception.
            throw IllegalStateException("Wispr login returned no session. Sign in again or import a session JSON.")
        }
        return AuthStore.sessionFromJson(response, email).also(auth::activate)
    }

    suspend fun transcribe(wav: ByteArray, onRawResponse: (String) -> Unit = {}): String =
        transcribeWithSession(wav, onRawResponse, null)

    private suspend fun transcribeWithSession(
        wav: ByteArray,
        onRawResponse: (String) -> Unit,
        expectedSession: WisprSession?,
    ): String = withContext(Dispatchers.IO) {
        require(wav.isNotEmpty()) { "No recorded audio" }
        val session = freshSession(expectedSession = expectedSession)
        val payload = JSONObject().put("audio", Base64.getEncoder().encodeToString(wav))
        fun upload(accessToken: String): JSONObject = postJson(
            apiUrl("/llm/api"),
            payload,
            // The current official HTTP interceptor sends the JWT without a Bearer prefix.
            mapOf("Authorization" to accessToken),
            trackTranscription = true,
        )
        val result = try {
            try {
                upload(session.accessToken)
            } catch (failure: HttpStatusException) {
                if (failure.status != 401) throw failure
                coroutineContext.ensureActive()
                val refreshed = freshSession(rejectedToken = session.accessToken, expectedSession = session)
                if (refreshed.accessToken == session.accessToken) throw failure
                upload(refreshed.accessToken) // One retry only, including when this response is 401.
            }
        } catch (failure: HttpStatusException) {
            if (failure.status in setOf(402, 403, 429)) throw explainTranscriptionFailure(failure, session)
            throw failure
        }
        checkAccount(session, auth.load())
        onRawResponse(result.toString())
        result.nonBlankString("text") ?: throw IllegalStateException("Wispr returned no text")
    }


    suspend fun freshAccessToken(): String = withContext(Dispatchers.IO) { freshSession().accessToken }

    internal suspend fun freshStreamingSession(): WisprSession = withContext(Dispatchers.IO) { freshSession() }

    internal suspend fun refreshStreamingSession(
        rejectedAccessToken: String?,
        expectedSession: WisprSession? = null,
    ): WisprSession = withContext(Dispatchers.IO) {
        freshSession(rejectedToken = rejectedAccessToken, expectedSession = expectedSession)
    }

    suspend fun transcribeLegacyPcm(pcm: ByteArray, onRawResponse: (String) -> Unit = {}): String {
        if (pcm.isEmpty()) throw IllegalStateException("No recorded audio")
        val session = withContext(Dispatchers.IO) { freshSession() }
        val maxChunkBytes = AudioRecorderController.SAMPLE_RATE *
            AudioRecorderController.CHANNELS *
            AudioRecorderController.SAMPLE_WIDTH_BYTES *
            FALLBACK_MAX_SECONDS
        val texts = mutableListOf<String>()
        var skippedEmpty = 0
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(pcm.size, offset + maxChunkBytes)
            val chunk = pcm.copyOfRange(offset, end)
            try {
                val text = transcribeWithSession(AudioRecorderController.pcmToWav(chunk), onRawResponse, session).trim()
                if (text.isNotEmpty()) texts += text else skippedEmpty++
            } catch (t: IllegalStateException) {
                if (t.message?.contains("returned no text", ignoreCase = true) == true) {
                    skippedEmpty++
                } else {
                    throw t
                }
            }
            offset = end
        }
        if (texts.isEmpty()) {
            throw IllegalStateException(
                if (skippedEmpty > 0) "fallback transcription returned empty text"
                else "fallback transcription had no audio chunks to upload",
            )
        }
        return texts.joinToString("\n")
    }

    fun cancelActiveTranscription(): Boolean {
        val call = activeTranscriptionCall.getAndSet(null) ?: return false
        call.cancel()
        return true
    }

    suspend fun usage(): WisprUsage = usageForSession(null)

    private suspend fun usageForSession(expectedSession: WisprSession?): WisprUsage = withContext(Dispatchers.IO) {
        val session = freshSession(expectedSession = expectedSession)
        val history = authenticatedGet("/history/stats", session)
        val subscription = authenticatedGet("/api/v1/payment/subscription", session)
        checkAccount(session, auth.load())
        WisprUsage.fromResponses(history, subscription, nowSeconds())
    }

    /** Only a fresh, structured quota snapshot may turn a service error into a weekly-limit warning. */
    suspend fun explainTranscriptionFailure(error: Throwable, expectedSession: WisprSession? = null): Throwable {
        if (error is WeeklyUsageLimitException || error is CancellationException) return error
        val current = try {
            usageForSession(expectedSession)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return error // Unavailable/unknown usage never means an exhausted allowance.
        }
        return if (current.hardLimitReached) WeeklyUsageLimitException(current) else error
    }

    private fun freshSession(
        rejectedToken: String? = null,
        expectedSession: WisprSession? = null,
    ): WisprSession = synchronized(REFRESH_LOCK) {
        val session = auth.load()
            ?: throw IllegalStateException("Wispr is not authenticated. Open betterFlow settings first.")
        if (expectedSession != null) checkAccount(expectedSession, session)
        val expiry = session.expiresAt ?: AuthStore.jwtExpiresAt(session.accessToken)
        val rejected = rejectedToken != null && session.accessToken == rejectedToken
        if (!rejected && (expiry == null || expiry > nowSeconds() + 300L)) return@synchronized session
        auth.refresh(rejectedToken ?: session.accessToken)?.let { renewed ->
            checkAccount(session, renewed)
            auth.save(renewed)
            return@synchronized renewed
        }
        val refresh = session.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Wispr session needs renewal. Sign in again or import a fresh session.")
        val provider = session.provider ?: AuthStore.jwtProvider(session.accessToken)
            ?: throw IllegalStateException("Unknown Wispr session issuer. Sign in again or import a session with its provider.")
        val refreshed = when (provider) {
            WisprAuthProvider.WORKOS -> postJson(
                endpoints.workosRefreshUrl,
                JSONObject().put("grant_type", "refresh_token")
                    .put("client_id", endpoints.workosClientId)
                    .put("refresh_token", refresh),
                emptyMap(),
            )
            WisprAuthProvider.SUPABASE -> postJson(
                endpoints.supabaseRefreshUrl,
                JSONObject().put("refresh_token", refresh),
                mapOf("apikey" to SUPABASE_ANON_KEY, "Authorization" to "Bearer $SUPABASE_ANON_KEY"),
            )
        }
        val access = refreshed.nonBlankString("access_token")
            ?: throw IllegalStateException("Wispr session refresh returned no access token")
        val rotatedRefresh = refreshed.nonBlankString("refresh_token")
        if (provider == WisprAuthProvider.WORKOS && rotatedRefresh == null) {
            throw IllegalStateException("Wispr session refresh returned no refresh token. Sign in again.")
        }
        val next = WisprSession(
            email = refreshed.optJSONObject("user")?.nonBlankString("email")
                ?: AuthStore.decodeJwtEmail(access) ?: session.email,
            accessToken = access,
            refreshToken = rotatedRefresh ?: refresh,
            expiresAt = refreshed.optLong("expires_at", 0L).takeIf { it > 0L }
                ?: AuthStore.jwtExpiresAt(access)
                ?: refreshed.optLong("expires_in", 0L).takeIf { it > 0L }?.let { nowSeconds() + it },
            provider = provider,
            // WorkOS user.id is a provider ID, not Wispr's canonical UUID.
            userId = AuthStore.jwtUserId(access) ?: session.userId
                ?: if (provider == WisprAuthProvider.SUPABASE) refreshed.optJSONObject("user")?.nonBlankString("id") else null,
        )
        checkAccount(session, next)
        auth.save(next)
        next
    }

    private suspend fun authenticatedGet(path: String, expectedSession: WisprSession): JSONObject {
        val session = freshSession(expectedSession = expectedSession)
        fun get(token: String) = requestJson(apiUrl(path), null, mapOf("Authorization" to token))
        return try {
            get(session.accessToken)
        } catch (failure: HttpStatusException) {
            if (failure.status != 401) throw failure
            coroutineContext.ensureActive()
            val renewed = freshSession(rejectedToken = session.accessToken, expectedSession = session)
            if (renewed.accessToken == session.accessToken) throw failure
            get(renewed.accessToken)
        }
    }

    private fun checkAccount(expected: WisprSession, actual: WisprSession?) {
        val same = actual != null && AccountSessions.fromLegacy(expected).accountFor(actual) != null
        check(same) { "Wispr account changed during the request. Retry with the selected account." }
    }

    private fun apiUrl(path: String) = endpoints.apiBaseUrl.trimEnd('/') + path

    private fun postJson(
        url: String,
        payload: JSONObject,
        headers: Map<String, String>,
        trackTranscription: Boolean = false,
    ): JSONObject = requestJson(url, payload, headers, trackTranscription)

    private fun requestJson(
        url: String,
        payload: JSONObject?,
        headers: Map<String, String>,
        trackTranscription: Boolean = false,
    ): JSONObject {
        val requestBuilder = Request.Builder()
            .url(url)
            .apply { if (payload == null) get() else post(payload.toString().toRequestBody(jsonType)) }
            .header("Accept", "application/json")
            .header("User-Agent", "betterFlow/${BuildConfig.VERSION_NAME} (Android)")
            .header(WisprProtocol.CLIENT_HEADER_NAME, WisprProtocol.CLIENT_HEADER)
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        val call = client.newCall(requestBuilder.build())
        if (trackTranscription) activeTranscriptionCall.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                val text = response.body.string()
                return try {
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                } catch (_: JSONException) {
                    // JSONException can include the response, including credentials or transcripts.
                    throw IllegalStateException("Wispr returned an invalid JSON response (HTTP ${response.code})")
                }
            }
        } finally {
            if (trackTranscription) activeTranscriptionCall.compareAndSet(call, null)
        }
    }

    private class HttpStatusException(val status: Int) : RuntimeException("Wispr request failed (HTTP $status)")

    companion object {
        // Shared by app clients in this process so refresh-token rotation is not raced.
        private val REFRESH_LOCK = Any()
        // Keep each WAV below the official client's 10 MiB HTTP upload limit.
        private const val FALLBACK_MAX_SECONDS = 300

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
        // Supabase anon keys are public client identifiers embedded in the official client; user tokens are never compiled in.
        private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRvZGprZnFod3J6cWp3a2ZudGhsIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MTk4ODQzMDcsImV4cCI6MjAzNTQ2MDMwN30.h6EeQ_6kqFeznH25icVUX0Szn9__kc8HoSXAsxxBWG8"
    }
}
