package com.jadenjsj.betterflow

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log

class GboardTriggerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != METHOD_TOGGLE) return Bundle().apply { putBoolean(KEY_OK, false) }
        val ctx = context ?: return Bundle().apply { putBoolean(KEY_OK, false) }
        val callerUid = Binder.getCallingUid()
        val callerPackages = ctx.packageManager.getPackagesForUid(callerUid).orEmpty()
        if (GBOARD_PACKAGE !in callerPackages) {
            Log.w(TAG, "Rejected provider trigger from uid=$callerUid packages=${callerPackages.joinToString()}")
            return Bundle().apply { putBoolean(KEY_OK, false) }
        }

        Log.i(TAG, "Accepted Gboard provider trigger from uid=$callerUid")
        val identity = Binder.clearCallingIdentity()
        return try {
            val serviceIntent = Intent(ctx, OverlayService::class.java)
                .setAction(OverlayService.ACTION_TOGGLE)
            ctx.startForegroundService(serviceIntent)
            Bundle().apply { putBoolean(KEY_OK, true) }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start betterFlow service from provider trigger", t)
            Bundle().apply {
                putBoolean(KEY_OK, false)
                putString(KEY_ERROR, t.javaClass.name + ": " + (t.message ?: ""))
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
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
        const val AUTHORITY = "com.jadenjsj.betterflow.gboard-trigger"
        const val METHOD_TOGGLE = "toggle"
        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        private const val TAG = "betterFlow/GboardProvider"
    }
}
