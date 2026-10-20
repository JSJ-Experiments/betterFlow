package com.jadenjsj.betterflow

import com.jadenjsj.betterflow.AuthStore.Companion.nonBlankString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Official 2.8 device-code login. No custom callback interception, passwords or client secret. */
class WisprBrowserAuth(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build(),
    private val apiBaseUrl: String = "https://api.wisprflow.ai",
    private val timeoutMillis: Long = 180_000L,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    class Attempt(val deviceCode: UUID = UUID.randomUUID(), google: Boolean = false) {
        val loginUrl = "https://wisprflow.ai/login?device_code=$deviceCode&workos_capable=true" +
            "&client=android&auto_signin=false" + if (google) "&provider=google" else ""
        override fun toString() = "BrowserSignInAttempt(redacted)"
    }

    /** Poll only during an explicit, cancellable sign-in, never as background account sync. */
    suspend fun awaitSession(attempt: Attempt): WisprSession =
        withTimeoutOrNull(timeoutMillis) { poll(attempt) }
            ?: throw IllegalStateException("Browser sign-in timed out. Start a new sign-in and try again.")

    private suspend fun poll(attempt: Attempt): WisprSession {
        var failures = 0
        while (true) {
            coroutineContext.ensureActive()
            val reply = try {
                get("/device/poll?device_code=${attempt.deviceCode}")
            } catch (_: NetworkFailure) {
                pause(backoff(++failures))
                continue
            }
            when (reply.code) {
                200 -> {
                    val json = requireNotNull(reply.json)
                    when (json.nonBlankString("status")) {
                        "complete" -> return complete(json)
                        "pending" -> { failures = 0; pause(2_000L) }
                        "timeout" -> throw IllegalStateException("Browser sign-in expired. Start a new sign-in.")
                        else -> throw IllegalStateException("Wispr returned an unrecognized browser sign-in status.")
                    }
                }
                429, 502, 503, 504 -> pause(reply.retryAfterMillis ?: backoff(++failures))
                else -> throw IllegalStateException("Browser sign-in failed (HTTP ${reply.code}). Try again or use the existing sign-in options.")
            }
        }
    }

    private suspend fun complete(json: JSONObject): WisprSession {
        val session = try { AuthStore.sessionFromJson(json) } catch (_: Exception) {
            throw IllegalStateException("Browser sign-in returned an incomplete session. Try signing in again.")
        }
        check(session.refreshToken != null && session.provider != null) {
            "Browser sign-in returned an incomplete session. Try signing in again."
        }
        // DeviceAuthResponse omits email. This read-only route provides the account label and UUID.
        val profile = try {
            get("/api/v1/user/profile", session.accessToken).takeIf { it.code == 200 }?.json
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        val canonicalId = session.userId ?: AuthStore.jwtSubject(session.accessToken)
            ?.takeIf { it.matches(Regex("^[0-9a-fA-F-]{36}$")) }
        val profileId = profile?.nonBlankString("user_id")
        check(canonicalId == null || profileId == null || canonicalId.equals(profileId, ignoreCase = true)) {
            "Browser sign-in profile did not match the session. Start a new sign-in."
        }
        coroutineContext.ensureActive()
        return session.copy(email = profile?.nonBlankString("email") ?: session.email,
            userId = canonicalId ?: profileId)
    }

    private data class Reply(val code: Int, val json: JSONObject?, val retryAfterMillis: Long?)
    private class NetworkFailure : IOException("Could not reach Wispr browser sign-in.")

    private suspend fun get(path: String, accessToken: String? = null): Reply = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(apiBaseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .header(WisprProtocol.CLIENT_HEADER_NAME, WisprProtocol.CLIENT_HEADER)
            .apply { if (accessToken != null) header("Authorization", accessToken) }.build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Never expose a request URL/device code or an upstream response in an error.
                continuation.resumeWith(Result.failure(NetworkFailure()))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val json = if (it.code == 200) try { JSONObject(it.body.string()) } catch (_: Exception) {
                            throw IllegalStateException("Wispr returned an invalid browser sign-in response.")
                        } else null
                        Reply(it.code, json, it.header("Retry-After")?.toLongOrNull()
                            ?.coerceIn(1L, 30L)?.times(1_000L))
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private fun backoff(failures: Int): Long = (2_000L shl (failures - 1).coerceIn(0, 3)).coerceAtMost(15_000L)
}
