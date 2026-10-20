package com.jadenjsj.betterflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object TranscriptClipboard {
    /** Best effort: clipboard failure must never prevent editor insertion or saving history. */
    fun copy(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        return runCatching {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("betterFlow transcript", text))
            true
        }.getOrDefault(false)
    }
}
