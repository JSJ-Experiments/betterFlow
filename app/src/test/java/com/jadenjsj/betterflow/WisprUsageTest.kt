package com.jadenjsj.betterflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

internal fun testUsageHistory(used: Any = 900, soft: Any = 1_000, hard: Any = 1_500, schema: Any = 1): JSONObject =
    JSONObject().put("mobile_words_this_week", used)
        .put("words_this_week", 999_999) // Total/desktop counts are not the mobile quota counter.
        .put("weekly_word_limits", JSONObject().put("schema_version", schema)
            .put("android", JSONObject().put("soft", soft).put("hard", hard).put("source", "experiment")))

class WisprUsageTest {
    private fun usage(history: JSONObject, plan: String = "FLOW_BASIC") =
        WisprUsage.fromResponses(history, JSONObject().put("plan", plan), 1_000L)

    @Test
    fun readsOnlyAuthoritativeMobileUsageAndServedAndroidLimits() {
        val result = usage(testUsageHistory())
        assertEquals(900L, result.usedWords)
        assertEquals(1_000L, result.limitWords)
        assertEquals(1_500L, result.hardLimitWords)
        assertEquals(100L, result.remainingWords)
        assertEquals("experiment", result.limitsSource)
        assertFalse(result.exhausted)
        assertFalse(result.hardLimitReached)
    }

    @Test
    fun softExhaustionAndHardCapAreDistinct() {
        val soft = usage(testUsageHistory(1_000))
        assertTrue(soft.exhausted)
        assertFalse(soft.hardLimitReached)
        assertEquals(0L, soft.remainingWords)
        val hard = usage(testUsageHistory(1_500))
        assertTrue(hard.exhausted)
        assertTrue(hard.hardLimitReached)
    }

    @Test
    fun paidAndUnknownPlansCannotBeDeclaredExhausted() {
        val paid = usage(testUsageHistory(100_000), "FLOW_PRO_MONTHLY")
        assertEquals(false, paid.isLimited)
        assertNull(paid.remainingWords)
        assertFalse(paid.exhausted)
        assertFalse(paid.hardLimitReached)
        val unknown = usage(testUsageHistory(100_000), "NEW_UNKNOWN_PLAN")
        assertNull(unknown.isLimited)
        assertFalse(unknown.hardLimitReached)
        val laggedBasic = WisprUsage.fromResponses(testUsageHistory(100_000), JSONObject()
            .put("plan", "FLOW_BASIC").put("is_subscribed", true).put("status", "active"), 1_000)
        assertEquals(false, laggedBasic.isLimited)
        assertFalse(laggedBasic.hardLimitReached)
    }

    @Test
    fun missingOrFutureSchemaLimitsRemainUnknownWithoutFixedFallback() {
        for (history in listOf(JSONObject().put("mobile_words_this_week", 5_000),
            testUsageHistory(5_000, schema = 2), testUsageHistory(5_000, soft = 2_000, hard = 1_500))) {
            val result = usage(history)
            assertNull(result.limitWords)
            assertNull(result.hardLimitWords)
            assertFalse(result.exhausted)
            assertFalse(result.hardLimitReached)
        }
    }

    @Test
    fun nullNegativeFractionalAndStringCountersAreNotAssumedToBeZeroOrExhausted() {
        for (used in listOf(JSONObject.NULL, -1, 1.5, "1500")) {
            val result = usage(testUsageHistory(used))
            assertNull(result.usedWords)
            assertNull(result.remainingWords)
            assertFalse(result.exhausted)
        }
        assertNull(usage(testUsageHistory(5_000, soft = "1000")).limitWords)
    }

    @Test
    fun resetIsSundayAt2345UtcAndAdvancesAtExactBoundary() {
        fun epoch(value: String) = Instant.parse(value).epochSecond
        val boundary = epoch("2026-10-25T23:45:00Z")
        assertEquals(boundary, WisprUsage.nextResetAt(epoch("2026-10-20T00:00:00Z")))
        assertEquals(boundary, WisprUsage.nextResetAt(boundary - 1))
        assertEquals(epoch("2026-11-01T23:45:00Z"), WisprUsage.nextResetAt(boundary))
    }
}
