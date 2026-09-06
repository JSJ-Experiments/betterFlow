package com.jadenjsj.betterflow

import android.content.Context

enum class InputBackend(val wireName: String) {
    AUTO("auto"),
    LSPOSED("lsposed"),
    CLIPBOARD_PASTE("clipboard_paste");

    companion object {
        fun fromWireName(value: String?): InputBackend = entries.firstOrNull { it.wireName == value } ?: AUTO
    }
}

enum class NotificationPriority(val wireName: String, val displayName: String) {
    MIN("min", "Minimum"),
    LOW("low", "Low"),
    DEFAULT("default", "Normal"),
    HIGH("high", "High");

    companion object {
        fun fromWireName(value: String?): NotificationPriority =
            entries.firstOrNull { it.wireName == value } ?: LOW
    }
}

enum class BubbleDockSide(val wireName: String) {
    NONE("none"),
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun fromWireName(value: String?): BubbleDockSide =
            entries.firstOrNull { it.wireName == value } ?: NONE
    }
}

object Prefs {
    private const val NAME = "betterflow"
    private const val KEY_BACKEND = "input_backend"
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"
    private const val KEY_BUBBLE_VISIBLE = "bubble_visible"
    private const val KEY_BUBBLE_DOCK_SIDE = "bubble_dock_side"
    private const val KEY_BUBBLE_SIZE_DP = "bubble_size_dp"
    private const val KEY_BUBBLE_OPACITY_PERCENT = "bubble_opacity_percent"
    private const val KEY_NOTIFICATION_PRIORITY = "notification_priority"
    private const val KEY_GBOARD_MIC_ENABLED = "gboard_mic_enabled"
    private const val KEY_VOICE_TRIGGER_V2_MIGRATED = "voice_trigger_v2_migrated"
    private const val KEY_LEGACY_TRANSCRIPTION = "legacy_transcription"
    private const val KEY_STREAMING_API_KEY = "streaming_api_key"

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun ensureVoiceTriggerDefaults(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_VOICE_TRIGGER_V2_MIGRATED, false)) return
        // v1 used the floating bubble as the primary UI. v2 makes the native
        // Gboard mic the primary trigger and retains the bubble as opt-in.
        p.edit()
            .putBoolean(KEY_GBOARD_MIC_ENABLED, true)
            .putBoolean(KEY_BUBBLE_VISIBLE, false)
            .putBoolean(KEY_VOICE_TRIGGER_V2_MIGRATED, true)
            .commit()
    }

    fun backend(context: Context): InputBackend = InputBackend.fromWireName(prefs(context).getString(KEY_BACKEND, InputBackend.AUTO.wireName))

    fun setBackend(context: Context, backend: InputBackend) {
        prefs(context).edit().putString(KEY_BACKEND, backend.wireName).apply()
    }

    fun bubblePosition(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_BUBBLE_X, 12) to p.getInt(KEY_BUBBLE_Y, 420)
    }

    fun setBubblePosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
    }

    fun bubbleDockSide(context: Context): BubbleDockSide =
        BubbleDockSide.fromWireName(prefs(context).getString(KEY_BUBBLE_DOCK_SIDE, BubbleDockSide.NONE.wireName))

    fun setBubbleDockSide(context: Context, side: BubbleDockSide) {
        prefs(context).edit().putString(KEY_BUBBLE_DOCK_SIDE, side.wireName).apply()
    }

    fun bubbleVisible(context: Context): Boolean {
        ensureVoiceTriggerDefaults(context)
        return prefs(context).getBoolean(KEY_BUBBLE_VISIBLE, false)
    }

    fun setBubbleVisible(context: Context, visible: Boolean) {
        ensureVoiceTriggerDefaults(context)
        prefs(context).edit().putBoolean(KEY_BUBBLE_VISIBLE, visible).apply()
    }

    fun bubbleSizeDp(context: Context): Int =
        prefs(context).getInt(KEY_BUBBLE_SIZE_DP, DEFAULT_BUBBLE_SIZE_DP)
            .coerceIn(MIN_BUBBLE_SIZE_DP, MAX_BUBBLE_SIZE_DP)

    fun setBubbleSizeDp(context: Context, sizeDp: Int) {
        prefs(context).edit()
            .putInt(KEY_BUBBLE_SIZE_DP, sizeDp.coerceIn(MIN_BUBBLE_SIZE_DP, MAX_BUBBLE_SIZE_DP))
            .apply()
    }

    fun bubbleOpacityPercent(context: Context): Int =
        prefs(context).getInt(KEY_BUBBLE_OPACITY_PERCENT, DEFAULT_BUBBLE_OPACITY_PERCENT)
            .coerceIn(MIN_BUBBLE_OPACITY_PERCENT, MAX_BUBBLE_OPACITY_PERCENT)

    fun setBubbleOpacityPercent(context: Context, opacityPercent: Int) {
        prefs(context).edit()
            .putInt(
                KEY_BUBBLE_OPACITY_PERCENT,
                opacityPercent.coerceIn(MIN_BUBBLE_OPACITY_PERCENT, MAX_BUBBLE_OPACITY_PERCENT),
            )
            .apply()
    }

    fun notificationPriority(context: Context): NotificationPriority =
        NotificationPriority.fromWireName(
            prefs(context).getString(KEY_NOTIFICATION_PRIORITY, NotificationPriority.LOW.wireName),
        )

    fun setNotificationPriority(context: Context, priority: NotificationPriority) {
        prefs(context).edit().putString(KEY_NOTIFICATION_PRIORITY, priority.wireName).apply()
    }

    fun gboardMicEnabled(context: Context): Boolean {
        ensureVoiceTriggerDefaults(context)
        return prefs(context).getBoolean(KEY_GBOARD_MIC_ENABLED, true)
    }

    fun setGboardMicEnabled(context: Context, enabled: Boolean) {
        ensureVoiceTriggerDefaults(context)
        prefs(context).edit().putBoolean(KEY_GBOARD_MIC_ENABLED, enabled).apply()
        XposedRemoteAuthSync.syncFromLocal()
    }

    fun legacyTranscription(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEGACY_TRANSCRIPTION, false)

    fun setLegacyTranscription(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEGACY_TRANSCRIPTION, enabled).apply()
        XposedRemoteAuthSync.syncFromLocal()
    }

    fun streamingApiKey(context: Context): String? =
        prefs(context).getString(KEY_STREAMING_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setStreamingApiKey(context: Context, apiKey: String?) {
        val editor = prefs(context).edit()
        val normalized = apiKey?.trim().orEmpty()
        if (normalized.isEmpty()) editor.remove(KEY_STREAMING_API_KEY)
        else editor.putString(KEY_STREAMING_API_KEY, normalized)
        editor.apply()
        XposedRemoteAuthSync.syncFromLocal()
    }

    const val MIN_BUBBLE_SIZE_DP = 36
    const val MAX_BUBBLE_SIZE_DP = 88
    const val DEFAULT_BUBBLE_SIZE_DP = 58
    const val MIN_BUBBLE_OPACITY_PERCENT = 20
    const val MAX_BUBBLE_OPACITY_PERCENT = 100
    const val DEFAULT_BUBBLE_OPACITY_PERCENT = 100
}

object VoiceRuntimeState {
    @Volatile var wireName: String = "idle"
}
