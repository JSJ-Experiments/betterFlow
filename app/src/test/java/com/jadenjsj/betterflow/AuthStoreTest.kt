package com.jadenjsj.betterflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

internal fun testJwt(
    issuer: String = "https://api.workos.com/",
    expiresAt: Long = 10_000L,
    extra: JSONObject = JSONObject(),
): String {
    val payload = extra.put("iss", issuer).put("exp", expiresAt)
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
    return "eyJhbGciOiJub25lIn0.$encoded.test-signature"
}

class AuthStoreTest {
    @Test
    fun importsLegacySupabaseSessionWithoutProvider() {
        val access = testJwt("https://dodjkfqhwrzqjwkfnthl.supabase.co/auth/v1", extra = JSONObject()
            .put("sub", "supabase-user").put("user_metadata", JSONObject().put("email", "legacy@example.test")))
        val session = AuthStore.sessionFromJson(JSONObject().put("access_token", access)
            .put("refresh_token", "legacy-refresh"))
        assertEquals(WisprAuthProvider.SUPABASE, session.provider)
        assertEquals(10_000L, session.expiresAt)
        assertEquals("legacy@example.test", session.email)
        assertEquals("supabase-user", AuthStore.jwtSubject(access))
    }

    @Test
    fun importsWorkosIdentityAndIgnoresJsonNullTokens() {
        val userId = "32dde0dc-1b75-4d3b-bfe3-88ba148257e4"
        val access = testJwt(extra = JSONObject().put("sub", "user_workos")
            .put("urn:wispr:user_external_id", userId))
        val session = AuthStore.sessionFromJson(JSONObject().put("access_token", access)
            .put("email", JSONObject.NULL).put("refresh_token", JSONObject.NULL))
        assertEquals(WisprAuthProvider.WORKOS, session.provider)
        assertEquals(userId, session.userId)
        assertEquals("wispr-user", session.email)
        assertNull(session.refreshToken)
        assertEquals("user_workos", AuthStore.jwtSubject(access))
    }

    @Test
    fun explicitProviderAndCanonicalIdSurviveCustomIssuer() {
        val session = AuthStore.sessionFromJson(JSONObject()
            .put("access_token", testJwt("https://custom.example.test"))
            .put("provider", "workos").put("user_id", "canonical-user")
            .put("user", JSONObject().put("email", "user@example.test")))
        assertEquals(WisprAuthProvider.WORKOS, session.provider)
        assertEquals("canonical-user", session.userId)
        assertEquals("user@example.test", session.email)
    }

    @Test
    fun workosProviderIdentityCannotShadowCanonicalWisprIdentity() {
        val canonicalId = "32dde0dc-1b75-4d3b-bfe3-88ba148257e4"
        val access = testJwt(extra = JSONObject().put("urn:wispr:user_external_id", canonicalId))
        val session = AuthStore.sessionFromJson(JSONObject().put("access_token", access)
            .put("user_id", "user_stale_provider_id")
            .put("user", JSONObject().put("id", "user_workos")))
        assertEquals(canonicalId, session.userId)
        val noCanonicalClaim = AuthStore.sessionFromJson(JSONObject().put("access_token", testJwt())
            .put("user", JSONObject().put("id", "user_workos")))
        assertNull(noCanonicalClaim.userId)
    }

    @Test
    fun supabaseUserObjectRetainsCanonicalIdentity() {
        val canonicalId = "32dde0dc-1b75-4d3b-bfe3-88ba148257e4"
        val session = AuthStore.sessionFromJson(JSONObject()
            .put("access_token", testJwt("https://dodjkfqhwrzqjwkfnthl.supabase.co/auth/v1"))
            .put("user", JSONObject().put("id", canonicalId)))
        assertEquals(canonicalId, session.userId)
    }

    @Test
    fun unknownIssuerIsNotAssumedToBeSupabase() {
        assertNull(AuthStore.jwtProvider(testJwt("https://api.workos.com.attacker.example")))
        assertNull(AuthStore.jwtProvider(testJwt("http://api.workos.com")))
        assertNull(AuthStore.jwtProvider(testJwt("https://unknown.example")))
        assertNull(AuthStore.jwtExpiresAt("not-a-jwt"))
        assertNull(AuthStore.jwtSubject("a.%%%.b"))
        assertNull(AuthStore.jwtUserId(testJwt(extra = JSONObject().put("urn:wispr:user_external_id", "not-uuid"))))
    }

    @Test
    fun invalidImportedSessionIsRejectedWithoutEchoingIt() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthStore.sessionFromJson(JSONObject().put("access_token", JSONObject.NULL))
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AuthStore.sessionFromJson(JSONObject().put("access_token", "secret").put("provider", "unknown"))
        }
        assertEquals("Unsupported Wispr authentication provider", failure.message)
    }
}
