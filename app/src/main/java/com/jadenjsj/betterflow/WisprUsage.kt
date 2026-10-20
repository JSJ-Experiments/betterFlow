package com.jadenjsj.betterflow

import com.jadenjsj.betterflow.AuthStore.Companion.nonBlankString
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** Server-reported mobile words; desktop usage is a separate allowance in the official app. */
data class WisprUsage(
    val usedWords: Long?,
    val limitWords: Long?,
    val hardLimitWords: Long?,
    val plan: String?,
    val isLimited: Boolean?,
    val resetAtEpochSeconds: Long,
    val limitsSource: String?,
) {
    val usageFraction: Float?
        get() = if (isLimited == true && usedWords != null && limitWords != null && limitWords > 0) {
            (usedWords.toDouble() / limitWords).coerceIn(0.0, 1.0).toFloat()
        } else null
    val remainingWords: Long?
        get() = if (isLimited == true && usedWords != null && limitWords != null) {
            (limitWords - usedWords).coerceAtLeast(0)
        } else null
    val exhausted: Boolean
        get() = isLimited == true && usedWords != null && limitWords != null && usedWords >= limitWords
    val hardLimitReached: Boolean
        get() = isLimited == true && usedWords != null && hardLimitWords != null && usedWords >= hardLimitWords

    companion object {
        private val paidPlans = setOf(
            "FLOW_PRO_WEEKLY", "FLOW_PRO_MONTHLY", "FLOW_PRO_YEARLY", "FLOW_PRO_STUDENT_WEEKLY",
            "FLOW_STUDENT_MONTHLY", "FLOW_STUDENT_YEARLY", "FLOW_STUDENT_MONTHLY_OLD", "FLOW_STUDENT_YEARLY_OLD",
            "FLOW_TEAM_MONTHLY", "FLOW_TEAM_YEARLY", "FLOW_BUSINESS_MONTHLY", "FLOW_BUSINESS_YEARLY",
        )

        internal fun fromResponses(history: JSONObject, subscription: JSONObject, nowSeconds: Long): WisprUsage {
            val plan = subscription.nonBlankString("plan")
            val isLimited = when {
                // A synced active subscription must not be labelled exhausted if a Basic plan value lags it.
                subscription.opt("is_subscribed") == true && subscription.nonBlankString("status") in setOf("active", "trialing") -> false
                plan == "FLOW_BASIC" -> true
                plan in paidPlans -> false
                else -> null
            }
            val served = history.optJSONObject("weekly_word_limits")
                ?.takeIf { it.strictCount("schema_version") == 1L }
                ?.optJSONObject("android")
            val soft = served?.strictCount("soft")?.takeIf { it > 0 }
            val hard = served?.strictCount("hard")?.takeIf { it > 0 }
            val validLimits = soft != null && hard != null && soft <= hard
            return WisprUsage(
                usedWords = history.strictCount("mobile_words_this_week"),
                limitWords = soft.takeIf { validLimits },
                hardLimitWords = hard.takeIf { validLimits },
                plan = plan,
                isLimited = isLimited,
                resetAtEpochSeconds = nextResetAt(nowSeconds),
                limitsSource = served?.nonBlankString("source").takeIf { validLimits },
            )
        }

        /** Same weekly boundary used by the current official client's WordLimitPolicy. */
        internal fun nextResetAt(nowSeconds: Long): Long {
            var next = Instant.ofEpochSecond(nowSeconds).atOffset(ZoneOffset.UTC)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .withHour(23).withMinute(45).withSecond(0).withNano(0)
            if (next.toEpochSecond() <= nowSeconds) next = next.plusWeeks(1)
            return next.toEpochSecond()
        }

        private fun JSONObject.strictCount(name: String): Long? {
            val number = opt(name) as? Number ?: return null
            val value = number.toDouble()
            return value.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toLong()
        }
    }
}

class WeeklyUsageLimitException(val usage: WisprUsage) : IllegalStateException(
    "Wispr weekly ${if (usage.hardLimitReached) "hard " else ""}word limit reached " +
        "(${usage.usedWords}/${if (usage.hardLimitReached) usage.hardLimitWords else usage.limitWords} words). " +
        "Resets ${Instant.ofEpochSecond(usage.resetAtEpochSeconds)}. Wait for the reset or manage your plan in Wispr Flow.",
)
