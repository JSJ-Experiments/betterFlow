package com.jadenjsj.betterflow

import android.content.Context
import java.util.Base64
import org.json.JSONObject
import org.json.JSONException

enum class WisprAuthProvider(val wireName: String) {
    SUPABASE("supabase"),
    WORKOS("workos");

    companion object {
        fun fromWireName(value: String?): WisprAuthProvider? = entries.firstOrNull { it.wireName == value }
    }
}

data class WisprSession(
    val email: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,
    val provider: WisprAuthProvider? = null,
    val userId: String? = null,
)

interface WisprSessionStore {
    fun load(): WisprSession?
    fun save(session: WisprSession)
    fun activate(session: WisprSession) { save(session) }
    // Hook processes can delegate renewal to the companion's persistent session owner.
    fun refresh(rejectedAccessToken: String?): WisprSession? = null
}

class AuthStore(context: Context) : WisprSessionStore {
    private val prefs = context.getSharedPreferences("betterflow_auth", Context.MODE_PRIVATE)

    override fun load(): WisprSession? = synchronized(ACCOUNT_LOCK) { readAccounts().activeSession }

    internal fun savedAccounts(): List<AccountSessions.Account> = synchronized(ACCOUNT_LOCK) { readAccounts().accounts }
    internal fun activeAccountId(): String? = synchronized(ACCOUNT_LOCK) { readAccounts().activeAccountId }

    /** Token refresh updates only its existing account, without selecting or restoring a removed one. */
    override fun save(session: WisprSession) = synchronized(ACCOUNT_LOCK) {
        val current = readAccounts()
        val account = current.accountFor(session) ?: return@synchronized
        persist(current.update(account.id, session))
    }

    /** Explicit login/import adds or replaces a saved session and selects it. */
    override fun activate(session: WisprSession) = synchronized(ACCOUNT_LOCK) {
        persist(readAccounts().activate(session))
    }

    internal fun selectAccount(id: String): WisprSession? = synchronized(ACCOUNT_LOCK) {
        val current = readAccounts()
        require(current.accounts.any { it.id == id }) { "Saved account no longer exists" }
        val next = current.select(id)
        persist(next)
        next.activeSession
    }

    internal fun removeAccount(id: String): WisprSession? = synchronized(ACCOUNT_LOCK) {
        val next = readAccounts().remove(id)
        persist(next)
        next.activeSession
    }

    /** Sign out of the selected account only; other saved accounts are retained. */
    fun clear() = synchronized(ACCOUNT_LOCK) {
        val current = readAccounts()
        persist(current.activeAccountId?.let(current::remove) ?: current)
    }

    private fun readAccounts(): AccountSessions {
        prefs.getString(ACCOUNTS_KEY, null)?.let { return AccountSessions.decode(it) }
        // One-time migration from the original single-session preferences.
        val access = prefs.getString("access_token", null)?.takeIf { it.isNotBlank() }
        val legacy = access?.let {
            WisprSession(
                email = prefs.getString("email", "wispr-user") ?: "wispr-user",
                accessToken = it,
                refreshToken = prefs.getString("refresh_token", null),
                expiresAt = prefs.getLong("expires_at", 0L).takeIf { value -> value > 0L } ?: jwtExpiresAt(it),
                provider = WisprAuthProvider.fromWireName(prefs.getString("provider", null)) ?: jwtProvider(it),
                userId = prefs.getString("user_id", null)?.takeIf(String::isNotBlank) ?: jwtUserId(it),
            )
        }
        return AccountSessions.fromLegacy(legacy).also { state -> writeAccounts(state) }
    }

    private fun persist(accounts: AccountSessions) {
        writeAccounts(accounts)
        // Publish only the currently selected account, never the late refresh that triggered this save.
        XposedRemoteAuthSync.sync(accounts.activeSession)
    }

    private fun writeAccounts(accounts: AccountSessions) {
        check(prefs.edit().putString(ACCOUNTS_KEY, accounts.encode())
            .remove("email").remove("access_token").remove("refresh_token")
            .remove("expires_at").remove("provider").remove("user_id").commit()) {
            "Could not save Wispr accounts"
        }
    }

    fun importJson(raw: String): WisprSession {
        val json = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            throw IllegalArgumentException("Invalid session JSON")
        }
        val session = sessionFromJson(json)
        activate(session)
        return session
    }

    companion object {
        private const val ACCOUNTS_KEY = "saved_accounts_v1"
        private val ACCOUNT_LOCK = Any()
        internal fun JSONObject.nonBlankString(name: String): String? =
            (opt(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }

        fun sessionFromJson(json: JSONObject, fallbackEmail: String = "wispr-user"): WisprSession {
            val access = json.nonBlankString("access_token")
                ?: throw IllegalArgumentException("session JSON has no access_token")
            val providerName = json.nonBlankString("provider")
            val provider = WisprAuthProvider.fromWireName(providerName)
            require(providerName == null || provider != null) { "Unsupported Wispr authentication provider" }
            return WisprSession(
                email = json.nonBlankString("email") ?: json.optJSONObject("user")?.nonBlankString("email")
                    ?: decodeJwtEmail(access) ?: fallbackEmail,
                accessToken = access,
                refreshToken = json.nonBlankString("refresh_token"),
                expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0L } ?: jwtExpiresAt(access),
                provider = provider ?: jwtProvider(access),
                userId = json.nonBlankString("user_id") ?: json.optJSONObject("user")?.nonBlankString("id") ?: jwtUserId(access),
            )
        }

        // JWT claims here route an already-held session; they do not verify its signature.
        fun jwtSubject(token: String): String? = decodeJwtPayload(token)?.nonBlankString("sub")

        fun jwtUserId(token: String): String? {
            val external = decodeJwtPayload(token)?.nonBlankString("urn:wispr:user_external_id") ?: return null
            return external.takeIf {
                it.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
            }
        }

        fun jwtProvider(token: String): WisprAuthProvider? {
            val issuer = decodeJwtPayload(token)?.nonBlankString("iss")?.trimEnd('/') ?: return null
            // Defaults recovered from the current official client's issuer allowlist.
            return when (issuer) {
                "https://dodjkfqhwrzqjwkfnthl.supabase.co/auth/v1" -> WisprAuthProvider.SUPABASE
                "https://api.workos.com" -> WisprAuthProvider.WORKOS
                else -> null
            }
        }

        fun jwtExpiresAt(token: String): Long? = decodeJwtPayload(token)?.optLong("exp", 0L)?.takeIf { it > 0L }

        fun decodeJwtEmail(token: String): String? {
            val payload = decodeJwtPayload(token) ?: return null
            payload.nonBlankString("email")?.let { return it }
            return payload.optJSONObject("user_metadata")?.nonBlankString("email")
        }

        private fun decodeJwtPayload(token: String): JSONObject? {
            val parts = token.split('.')
            if (parts.size != 3) return null
            val part = parts[1].takeIf { it.isNotEmpty() && it.length <= 8192 } ?: return null
            return try {
                val decoded = Base64.getUrlDecoder().decode(part)
                JSONObject(decoded.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }
    }
}
