package com.jadenjsj.betterflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountSessionsTest {
    private fun session(email: String, userId: String? = null, token: String = "access-$email") = WisprSession(
        email, token, "refresh-$email", 12_345L, WisprAuthProvider.WORKOS, userId,
    )

    @Test fun explicitSignInAndSelectionKeepMultipleAccounts() {
        val first = session("First@Example.test", "first-user")
        val second = session("second@example.test", "second-user")
        var state = AccountSessions.empty().activate(first)
        val firstId = state.activeAccountId!!
        assertEquals("user:first-user", firstId)
        state = state.activate(second)
        assertEquals(listOf(first, second), state.accounts.map { it.session })
        assertEquals(second, state.activeSession)
        assertEquals(first, state.select(firstId).activeSession)
        assertSame(state, state.select("removed-account"))
    }

    @Test fun refreshingAnOldAccountNeverChangesActiveSelection() {
        val first = session("first@example.test", "first-user")
        var state = AccountSessions.fromLegacy(first)
        val firstId = state.activeAccountId!!
        val second = session("second@example.test", "second-user")
        state = state.activate(second)
        val selected = state.activeAccountId
        val refreshed = first.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh", expiresAt = 99_999L)
        state = state.update(firstId, refreshed)
        assertEquals(selected, state.activeAccountId)
        assertEquals(second, state.activeSession)
        assertEquals(refreshed, state.accounts.first { it.id == firstId }.session)
        state = state.remove(firstId)
        assertSame(state, state.update(firstId, refreshed)) // No resurrection after in-flight refresh.
    }

    @Test fun emailIdentityIsNormalizedAndCanGainCanonicalIdentityWithoutChangingId() {
        var state = AccountSessions.fromLegacy(session("  User@Example.TEST  "))
        val id = state.activeAccountId!!
        assertEquals("email:user@example.test", id)
        state = state.activate(session("user@example.test", "canonical-user", "next-token"))
        assertEquals(id, state.activeAccountId)
        assertEquals(1, state.accounts.size)
        // Canonical ID still finds the original record after the account email changes.
        state = state.activate(session("renamed@example.test", "canonical-user", "newest-token"))
        assertEquals(id, state.activeAccountId)
        assertEquals("renamed@example.test", state.activeSession!!.email)
    }

    @Test fun sameEmailCannotMergeDifferentCanonicalUsers() {
        val first = session("same@example.test", "first-user")
        val second = session("same@example.test", "second-user", "second-account-access")
        val state = AccountSessions.empty().activate(first).activate(second)
        assertEquals(2, state.accounts.size)
        assertThrows(IllegalArgumentException::class.java) {
            state.activate(session("same@example.test", token = "unrecognized-token"))
        }
        assertEquals("user:first-user", state.activate(first.copy(userId = null)).activeAccountId)
        assertThrows(IllegalArgumentException::class.java) { state.update("user:first-user", second) }
    }

    @Test fun refreshRetainsIdentityAndRefreshTokenWhenResponseOmitsThem() {
        val original = session("old@example.test", "user-id")
        val state = AccountSessions.fromLegacy(original)
        val next = state.update(state.activeAccountId!!,
            original.copy(email = "new@example.test", accessToken = "new-access", userId = null, provider = null, refreshToken = null))
        assertEquals(original.userId, next.activeSession!!.userId)
        assertEquals(original.provider, next.activeSession!!.provider)
        assertEquals(original.refreshToken, next.activeSession!!.refreshToken)
        assertEquals(state.activeAccountId, next.activeAccountId)
    }

    @Test fun lookupFindsRotatedAnonymousSessionsByProviderAndJwtSubject() {
        val token = testJwt(extra = JSONObject().put("sub", "user_workos"))
        val original = session("wispr-user", token = token)
        val state = AccountSessions.fromLegacy(original)
        val renewed = original.copy(accessToken = testJwt(expiresAt = 99_999L,
            extra = JSONObject().put("sub", "user_workos")))
        assertEquals(state.activeAccountId, state.accountFor(renewed)!!.id)
        assertEquals(1, state.activate(renewed).accounts.size)
        assertNull(state.accountFor(renewed.copy(provider = WisprAuthProvider.SUPABASE)))
        assertNull(state.accountFor(renewed.copy(accessToken = testJwt(extra = JSONObject().put("sub", "different_user")))))
        assertNull(state.remove(state.activeAccountId!!).accountFor(renewed))
    }

    @Test fun canonicalUuidSubjectAndUserIdMatchAcrossLegacyAndCurrentSessionFormats() {
        val id = "260C54F6-E58E-4E85-A2DA-C034EA48FAE4"
        val legacy = session("old@example.test", token = testJwt(
            "https://dodjkfqhwrzqjwkfnthl.supabase.co/auth/v1", extra = JSONObject().put("sub", id)))
        val state = AccountSessions.fromLegacy(legacy)
        assertEquals("user:${id.lowercase()}", state.activeAccountId)
        assertEquals(state.activeAccountId, state.accountFor(session("new@example.test", id.lowercase()))!!.id)
    }

    @Test fun removingActiveAccountSelectsNextAndRemovingLastSignsOut() {
        val first = session("first@example.test")
        val state = AccountSessions.fromLegacy(first).activate(session("second@example.test"))
        val removed = state.remove(state.activeAccountId!!)
        assertEquals(first, removed.activeSession)
        val empty = removed.remove(removed.activeAccountId!!)
        assertTrue(empty.accounts.isEmpty())
        assertNull(empty.activeAccountId)
        assertNull(empty.activeSession)
        assertSame(state.activeSession, state.remove("not-present").activeSession)
    }

    @Test fun envelopeRoundTripPreservesTokensProviderIdentityExpiryAndSelection() {
        val first = session("first@example.test", "first-user")
        val second = session("second@example.test", "second-user").copy(provider = WisprAuthProvider.SUPABASE)
        val state = AccountSessions.fromLegacy(first).activate(second).select("user:first-user")
        val restored = AccountSessions.decode(state.encode())
        assertEquals(state.activeAccountId, restored.activeAccountId)
        assertEquals(state.accounts.map { it.id }, restored.accounts.map { it.id })
        assertEquals(listOf(first, second), restored.accounts.map { it.session })
        assertFalse(restored.toString().contains(first.accessToken))
        assertFalse(restored.accounts.first().toString().contains(first.refreshToken!!))
    }

    @Test fun legacySessionMigratesAndAnonymousSessionsRemainSeparate() {
        val legacy = JSONObject().put("email", "legacy@example.test").put("access_token", "legacy-access")
            .put("refresh_token", "legacy-refresh").put("provider", "supabase").put("expires_at", 456L)
        val state = AccountSessions.decode(legacy.toString())
        assertEquals("email:legacy@example.test", state.activeAccountId)
        assertEquals("legacy-refresh", state.activeSession!!.refreshToken)
        assertEquals(456L, state.activeSession!!.expiresAt)
        assertEquals(WisprAuthProvider.SUPABASE, state.activeSession!!.provider)
        assertTrue(AccountSessions.decode(null).accounts.isEmpty())
        val anonymous = AccountSessions.empty().activate(session("wispr-user", token = "one"))
            .activate(session("wispr-user", token = "two"))
        assertEquals(2, anonymous.accounts.size)
        assertEquals(2, anonymous.activate(session("wispr-user", token = "one")).accounts.size)
    }

    @Test fun missingSelectionAndDamagedIndividualRecordsRecoverWithoutDiscardingGoodAccounts() {
        val state = AccountSessions.fromLegacy(session("good@example.test"))
        val json = JSONObject(state.encode()).put("active_account_id", "missing")
        val records = json.getJSONArray("accounts")
        records.put(JSONObject().put("id", "damaged").put("session", JSONObject().put("access_token", JSONObject.NULL)))
        records.put(records.getJSONObject(0)) // Duplicate IDs cannot survive decoding.
        val restored = AccountSessions.decode(json.toString())
        assertEquals(1, restored.accounts.size)
        assertEquals(state.activeAccountId, restored.activeAccountId)
        assertThrows(IllegalArgumentException::class.java) { AccountSessions.decode("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            AccountSessions.decode(JSONObject().put("version", 2).put("accounts", JSONArray()).toString())
        }
    }
}
