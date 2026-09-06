package com.jadenjsj.betterflow

import android.content.Context
import android.util.Base64
import org.json.JSONObject

data class WisprSession(
    val email: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,
)

interface WisprSessionStore {
    fun load(): WisprSession?
    fun save(session: WisprSession)
}

class AuthStore(context: Context) : WisprSessionStore {
    private val prefs = context.getSharedPreferences("betterflow_auth", Context.MODE_PRIVATE)

    override fun load(): WisprSession? {
        val access = prefs.getString("access_token", null)?.takeIf { it.isNotBlank() } ?: return null
        return WisprSession(
            email = prefs.getString("email", "wispr-user") ?: "wispr-user",
            accessToken = access,
            refreshToken = prefs.getString("refresh_token", null),
            expiresAt = prefs.getLong("expires_at", 0L).takeIf { it > 0L } ?: jwtExpiresAt(access),
        )
    }

    override fun save(session: WisprSession) {
        prefs.edit()
            .putString("email", session.email)
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .putLong("expires_at", session.expiresAt ?: jwtExpiresAt(session.accessToken) ?: 0L)
            .apply()
        XposedRemoteAuthSync.sync(session)
    }

    fun clear() {
        prefs.edit().clear().apply()
        XposedRemoteAuthSync.sync(null)
    }

    fun importJson(raw: String): WisprSession {
        val json = JSONObject(raw)
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("session JSON has no access_token")
        val session = WisprSession(
            email = json.optString("email", decodeJwtEmail(access) ?: "wispr-user"),
            accessToken = access,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0L } ?: jwtExpiresAt(access),
        )
        save(session)
        return session
    }

    companion object {
        fun jwtExpiresAt(token: String): Long? = decodeJwtPayload(token)?.optLong("exp", 0L)?.takeIf { it > 0L }

        fun decodeJwtEmail(token: String): String? {
            val payload = decodeJwtPayload(token) ?: return null
            payload.optString("email").takeIf { it.isNotBlank() }?.let { return it }
            return payload.optJSONObject("user_metadata")?.optString("email")?.takeIf { it.isNotBlank() }
        }

        private fun decodeJwtPayload(token: String): JSONObject? {
            val part = token.split('.').getOrNull(1) ?: return null
            return try {
                val decoded = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                JSONObject(decoded.toString(Charsets.UTF_8))
            } catch (_: Throwable) {
                null
            }
        }
    }
}
