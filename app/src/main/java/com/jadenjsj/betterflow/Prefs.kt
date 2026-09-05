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

object Prefs {
    private const val NAME = "betterflow"
    private const val KEY_BACKEND = "input_backend"
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"
    private const val KEY_BUBBLE_VISIBLE = "bubble_visible"
    private const val KEY_GBOARD_MIC_ENABLED = "gboard_mic_enabled"
    private const val KEY_VOICE_TRIGGER_V2_MIGRATED = "voice_trigger_v2_migrated"

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

    fun bubbleVisible(context: Context): Boolean {
        ensureVoiceTriggerDefaults(context)
        return prefs(context).getBoolean(KEY_BUBBLE_VISIBLE, false)
    }

    fun setBubbleVisible(context: Context, visible: Boolean) {
        ensureVoiceTriggerDefaults(context)
        prefs(context).edit().putBoolean(KEY_BUBBLE_VISIBLE, visible).apply()
    }

    fun gboardMicEnabled(context: Context): Boolean {
        ensureVoiceTriggerDefaults(context)
        return prefs(context).getBoolean(KEY_GBOARD_MIC_ENABLED, true)
    }

    fun setGboardMicEnabled(context: Context, enabled: Boolean) {
        ensureVoiceTriggerDefaults(context)
        prefs(context).edit().putBoolean(KEY_GBOARD_MIC_ENABLED, enabled).apply()
    }
}

object VoiceRuntimeState {
    @Volatile var wireName: String = "idle"
}
