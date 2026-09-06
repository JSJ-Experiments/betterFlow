package com.jadenjsj.betterflow

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

/**
 * Synchronous Binder bridge used by the Gboard Xposed hook.
 *
 * HyperOS can defer explicit cross-app broadcasts and hide exported services
 * from bindService() resolution. ContentProvider.call() is direct Binder IPC,
 * gives us the real Binder caller UID, and wakes this process on demand.
 */
class GboardBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return rejected("provider has no context")
        val callerUid = Binder.getCallingUid()
        val callerPackages = ctx.packageManager.getPackagesForUid(callerUid).orEmpty()
        if (GBOARD_PACKAGE !in callerPackages) {
            Log.w(TAG, "Rejected provider call method=$method uid=$callerUid packages=${callerPackages.joinToString()}")
            return rejected("caller is not Gboard")
        }

        Log.i(TAG, "Accepted Gboard provider call method=$method uid=$callerUid")
        return when (method) {
            METHOD_SNAPSHOT -> Bundle().apply {
                putBoolean(KEY_OK, true)
                putString(KEY_VOICE_STATE, VoiceRuntimeState.wireName)
                putBoolean(KEY_GBOARD_MIC_ENABLED, Prefs.gboardMicEnabled(ctx))
            }
            METHOD_GET_TOGGLE_PENDING_INTENT -> {
                val requestCode = (SystemClock.elapsedRealtimeNanos() and 0x7fffffffL).toInt()
                val pendingIntent = PendingIntent.getForegroundService(
                    ctx,
                    requestCode,
                    Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_TOGGLE),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                )
                Bundle().apply {
                    putBoolean(KEY_OK, true)
                    putParcelable(KEY_TOGGLE_PENDING_INTENT, pendingIntent)
                }
            }
            else -> rejected("unknown method")
        }
    }

    private fun rejected(message: String): Bundle = Bundle().apply {
        putBoolean(KEY_OK, false)
        putString(KEY_ERROR, message)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.jadenjsj.betterflow.gboard-bridge"
        const val METHOD_SNAPSHOT = "snapshot"
        const val METHOD_GET_TOGGLE_PENDING_INTENT = "toggle_pending_intent"
        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        const val KEY_VOICE_STATE = "voice_state"
        const val KEY_GBOARD_MIC_ENABLED = "gboard_mic_enabled"
        const val KEY_TOGGLE_PENDING_INTENT = "toggle_pending_intent"
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        private const val TAG = "betterFlow/GboardProvider"
    }
}
