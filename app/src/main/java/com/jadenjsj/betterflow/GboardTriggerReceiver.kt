package com.jadenjsj.betterflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class GboardTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GBOARD_TOGGLE) return
        if (Build.VERSION.SDK_INT < 34) {
            Log.w(TAG, "Rejecting Gboard trigger: sender UID verification requires Android 14+")
            return
        }

        val senderUid = sentFromUid
        val senderPackages = context.packageManager.getPackagesForUid(senderUid).orEmpty()
        if (GBOARD_PACKAGE !in senderPackages) {
            Log.w(TAG, "Rejected trigger from uid=$senderUid packages=${senderPackages.joinToString()}")
            return
        }

        Log.i(TAG, "Accepted Gboard mic trigger from uid=$senderUid")
        val serviceIntent = Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_TOGGLE)
        try {
            context.startForegroundService(serviceIntent)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start betterFlow service from Gboard trigger", t)
        }
    }

    companion object {
        const val ACTION_GBOARD_TOGGLE = "com.jadenjsj.betterflow.action.GBOARD_TOGGLE"
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        private const val TAG = "betterFlow/GboardTrigger"
    }
}
