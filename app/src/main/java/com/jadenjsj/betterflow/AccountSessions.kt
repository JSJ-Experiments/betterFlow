package com.jadenjsj.betterflow

import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Immutable saved-account state. Persist mutations against the latest state under one owner lock. */
class AccountSessions private constructor(
    val accounts: List<Account>,
    val activeAccountId: String?,
) {
    class Account(val id: String, val session: WisprSession) {
        // Never let incidental logging of account state disclose credentials.
        override fun toString(): String = "Account(id=$id, email=${session.email})"
    }

    val activeSession: WisprSession?
        get() = accounts.firstOrNull { it.id == activeAccountId }?.session

    /** Lookup for renewal persistence; an unknown or ambiguous identity must not add an account. */
    fun accountFor(session: WisprSession): Account? =
        try { findAccount(session) } catch (_: IllegalArgumentException) { null }

    /** Explicit login/import selects the account. An upgraded identity retains its persisted ID. */
    fun activate(session: WisprSession): AccountSessions {
        val existing = findAccount(session)
        val id = existing?.id ?: identityId(session)
        val saved = session.copy(userId = session.userId ?: existing?.session?.userId)
        val next = if (existing == null) accounts + Account(id, saved)
        else accounts.map { if (it.id == id) Account(id, saved) else it }
        return AccountSessions(next, id)
    }

    /** A refresh uses the account ID captured before networking, never the current selection. */
    fun update(accountId: String, session: WisprSession): AccountSessions {
        val existing = accounts.firstOrNull { it.id == accountId } ?: return this
        val oldUser = userId(existing.session)
        val newUser = userId(session)
        require(oldUser == null || newUser == null || oldUser == newUser) {
            "Refreshed session belongs to a different account"
        }
        val merged = session.copy(
            refreshToken = session.refreshToken ?: existing.session.refreshToken,
            provider = session.provider ?: existing.session.provider,
            userId = session.userId ?: existing.session.userId,
        )
        return AccountSessions(accounts.map { if (it.id == accountId) Account(accountId, merged) else it }, activeAccountId)
    }

    fun select(accountId: String): AccountSessions =
        if (accounts.any { it.id == accountId }) AccountSessions(accounts, accountId) else this

    fun remove(accountId: String): AccountSessions {
        val next = accounts.filterNot { it.id == accountId }
        val active = activeAccountId?.takeIf { selected -> next.any { it.id == selected } }
            ?: next.firstOrNull()?.id
        return AccountSessions(next, active)
    }

    fun encode(): String = JSONObject()
        .put("version", 1)
        .put("active_account_id", activeAccountId ?: JSONObject.NULL)
        .put("accounts", JSONArray().apply {
            accounts.forEach { account ->
                put(JSONObject().put("id", account.id).put("session", sessionJson(account.session)))
            }
        }).toString()

    override fun toString(): String = "AccountSessions(count=${accounts.size}, activeAccountId=$activeAccountId)"

    private fun findAccount(session: WisprSession): Account? {
        val canonical = userId(session)
        if (canonical != null) accounts.firstOrNull { userId(it.session) == canonical }?.let { return it }
        val subject = subjectIdentity(session)
        if (subject != null) accounts.singleOrNull {
            subjectIdentity(it.session) == subject &&
                (canonical == null || userId(it.session) == null || userId(it.session) == canonical)
        }?.let { return it }
        val normalizedEmail = email(session) ?: return accounts.singleOrNull { it.session.accessToken == session.accessToken }
        // Email can promote a legacy email-only identity, but cannot merge two distinct users.
        val matches = accounts.filter {
            email(it.session) == normalizedEmail && (canonical == null || userId(it.session) == null)
        }
        matches.singleOrNull { it.session.accessToken == session.accessToken }?.let { return it }
        require(matches.size <= 1) { "Session needs a user ID because multiple saved accounts use this email" }
        return matches.singleOrNull()
    }

    companion object {
        fun empty(): AccountSessions = AccountSessions(emptyList(), null)

        fun fromLegacy(session: WisprSession?): AccountSessions =
            if (session == null) empty() else empty().activate(session)

        /** Accepts both the versioned envelope and a legacy single-session JSON document. */
        fun decode(raw: String?): AccountSessions {
            if (raw.isNullOrBlank()) return empty()
            val json = try { JSONObject(raw) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid saved account data")
            }
            if (!json.has("accounts")) return fromLegacy(AuthStore.sessionFromJson(json))
            require(json.optInt("version", 0) == 1) { "Unsupported saved account data version" }
            val array = json.optJSONArray("accounts")
                ?: throw IllegalArgumentException("Invalid saved account data")
            val accounts = mutableListOf<Account>()
            for (index in 0 until array.length()) {
                val record = array.optJSONObject(index) ?: continue
                val id = (record.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: continue
                val session = record.optJSONObject("session")?.let {
                    try { AuthStore.sessionFromJson(it) } catch (_: IllegalArgumentException) { null }
                } ?: continue
                if (accounts.none { it.id == id }) accounts += Account(id, session)
            }
            val active = (json.opt("active_account_id") as? String)
                ?.takeIf { selected -> accounts.any { it.id == selected } } ?: accounts.firstOrNull()?.id
            return AccountSessions(accounts.toList(), active)
        }

        private fun userId(session: WisprSession): String? {
            val value = session.userId?.trim()?.takeIf { it.isNotEmpty() }
                ?: AuthStore.jwtUserId(session.accessToken)
                ?: AuthStore.jwtSubject(session.accessToken)?.takeIf { canonicalUuid(it) != null }
                ?: return null
            return canonicalUuid(value) ?: value
        }

        private fun canonicalUuid(value: String): String? =
            if (value.length != 36) null else runCatching { UUID.fromString(value).toString() }.getOrNull()

        private fun subjectIdentity(session: WisprSession): String? {
            val provider = session.provider ?: AuthStore.jwtProvider(session.accessToken) ?: return null
            val subject = AuthStore.jwtSubject(session.accessToken) ?: return null
            return "${provider.wireName}:$subject"
        }

        private fun email(session: WisprSession): String? = session.email.trim().lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() && it != "wispr-user" }

        private fun identityId(session: WisprSession): String = userId(session)?.let { "user:$it" }
            ?: email(session)?.let { "email:$it" }
            ?: subjectIdentity(session)?.let { "subject:$it" } ?: "anonymous:${UUID.randomUUID()}"

        private fun sessionJson(session: WisprSession): JSONObject = JSONObject()
            .put("email", session.email)
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken ?: JSONObject.NULL)
            .put("expires_at", session.expiresAt ?: JSONObject.NULL)
            .put("provider", session.provider?.wireName ?: JSONObject.NULL)
            .put("user_id", session.userId ?: JSONObject.NULL)
    }
}
