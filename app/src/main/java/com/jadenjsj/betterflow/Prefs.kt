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

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

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

    fun bubbleVisible(context: Context): Boolean = prefs(context).getBoolean(KEY_BUBBLE_VISIBLE, true)

    fun setBubbleVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_VISIBLE, visible).apply()
    }
}

object VoiceRuntimeState {
    @Volatile var wireName: String = "idle"
}
