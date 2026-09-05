package com.jadenjsj.betterflow

import android.content.Context
import android.util.Base64
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

class WisprClient(context: Context) {
    private val auth = AuthStore(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val activeTranscriptionCall = AtomicReference<Call?>(null)

    suspend fun login(email: String, password: String): WisprSession = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("is_platform_user", false)
        val response = postJson("$BASE_URL/email/signin", payload, emptyMap())
        val access = response.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(response.optString("message", "Wispr login failed"))
        val session = WisprSession(
            email = email,
            accessToken = access,
            refreshToken = response.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresAt = AuthStore.jwtExpiresAt(access),
        )
        auth.save(session)
        session
    }

    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val session = freshSession()
        val payload = JSONObject().put("audio", Base64.encodeToString(wav, Base64.NO_WRAP))
        val attempts = listOf(session.accessToken, "Bearer ${session.accessToken}")
        var lastError: Throwable? = null
        for (authorization in attempts) {
            try {
                val result = postJson(
                    "$BASE_URL/llm/api",
                    payload,
                    mapOf("Authorization" to authorization),
                    trackTranscription = true,
                )
                result.optString("text").takeIf { it.isNotBlank() }?.let { return@withContext it }
                throw IllegalStateException("Wispr returned no text: $result")
            } catch (t: HttpStatusException) {
                lastError = t
                if (t.status !in setOf(401, 403)) throw t
            }
        }
        throw lastError ?: IllegalStateException("Wispr transcription failed")
    }


    suspend fun freshAccessToken(): String = withContext(Dispatchers.IO) { freshSession().accessToken }

    suspend fun transcribeLegacyPcm(pcm: ByteArray): String {
        if (pcm.isEmpty()) throw IllegalStateException("No recorded audio")
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
                val text = transcribe(AudioRecorderController.pcmToWav(chunk)).trim()
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

    private fun freshSession(): WisprSession {
        val session = auth.load() ?: throw IllegalStateException("Wispr is not authenticated. Open betterFlow settings first.")
        val expiry = session.expiresAt ?: AuthStore.jwtExpiresAt(session.accessToken)
        if (expiry == null || expiry > (System.currentTimeMillis() / 1000L) + 300L) return session
        val refresh = session.refreshToken ?: throw IllegalStateException("Wispr session expired and has no refresh token")
        val payload = JSONObject().put("refresh_token", refresh)
        val refreshed = postJson(
            "$SUPABASE_URL/auth/v1/token?grant_type=refresh_token",
            payload,
            mapOf(
                "apikey" to SUPABASE_ANON_KEY,
                "Authorization" to "Bearer $SUPABASE_ANON_KEY",
            ),
        )
        val access = refreshed.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Wispr session refresh returned no access token")
        val next = WisprSession(
            email = refreshed.optJSONObject("user")?.optString("email")?.takeIf { it.isNotBlank() } ?: session.email,
            accessToken = access,
            refreshToken = refreshed.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh,
            expiresAt = refreshed.optLong("expires_at", 0L).takeIf { it > 0L } ?: AuthStore.jwtExpiresAt(access),
        )
        auth.save(next)
        return next
    }

    private fun postJson(
        url: String,
        payload: JSONObject,
        headers: Map<String, String>,
        trackTranscription: Boolean = false,
    ): JSONObject {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonType))
            .header("Accept", "application/json")
            .header("User-Agent", "betterFlow/${BuildConfig.VERSION_NAME} (Android)")
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        val call = client.newCall(requestBuilder.build())
        if (trackTranscription) activeTranscriptionCall.set(call)
        try {
            call.execute().use { response ->
                val text = response.body.string()
                val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                if (!response.isSuccessful) throw HttpStatusException(response.code, json.toString())
                return json
            }
        } finally {
            if (trackTranscription) activeTranscriptionCall.compareAndSet(call, null)
        }
    }

    private class HttpStatusException(val status: Int, body: String) : RuntimeException("HTTP $status: $body")

    companion object {
        private const val BASE_URL = "https://api.wisprflow.ai"
        private const val SUPABASE_URL = "https://dodjkfqhwrzqjwkfnthl.supabase.co"
        private const val FALLBACK_MAX_SECONDS = 355
        // Supabase anon keys are public client identifiers embedded in the official client; user tokens are never compiled in.
        private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRvZGprZnFod3J6cWp3a2ZudGhsIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MTk4ODQzMDcsImV4cCI6MjAzNTQ2MDMwN30.h6EeQ_6kqFeznH25icVUX0Szn9__kc8HoSXAsxxBWG8"
    }
}
