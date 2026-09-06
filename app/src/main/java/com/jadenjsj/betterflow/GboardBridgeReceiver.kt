package com.jadenjsj.betterflow

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log

/**
 * Explicit, authenticated bridge for the injected Gboard hook.
 *
 * Some HyperOS builds return false from cross-app bindService() even for an
 * exported explicit service. Explicit broadcasts remain reliable. We verify
 * the framework-reported sender UID belongs to Gboard before returning either
 * state/config or a one-shot PendingIntent capability for OverlayService.
 */
class GboardBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BRIDGE) return
        val resultReceiver = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)
        }
        val senderUid = if (Build.VERSION.SDK_INT >= 34) sentFromUid else -1
        val senderPackages = context.packageManager.getPackagesForUid(senderUid).orEmpty()
        if (GBOARD_PACKAGE !in senderPackages) {
            Log.w(TAG, "Rejected bridge broadcast uid=$senderUid packages=${senderPackages.joinToString()}")
            resultReceiver?.send(RESULT_REJECTED, Bundle.EMPTY)
            return
        }

        when (intent.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_SNAPSHOT -> {
                resultReceiver?.send(
                    RESULT_OK,
                    Bundle().apply {
                        putString(EXTRA_VOICE_STATE, VoiceRuntimeState.wireName)
                        putBoolean(EXTRA_GBOARD_MIC_ENABLED, Prefs.gboardMicEnabled(context))
                    },
                )
            }
            COMMAND_GET_TOGGLE_PENDING_INTENT -> {
                val requestCode = (SystemClock.elapsedRealtime() and 0x7fffffffL).toInt()
                val pendingIntent = PendingIntent.getForegroundService(
                    context,
                    requestCode,
                    Intent(context, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_TOGGLE),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                )
                resultReceiver?.send(
                    RESULT_OK,
                    Bundle().apply { putParcelable(EXTRA_TOGGLE_PENDING_INTENT, pendingIntent) },
                )
            }
            else -> resultReceiver?.send(RESULT_REJECTED, Bundle.EMPTY)
        }
    }

    companion object {
        const val ACTION_BRIDGE = "com.jadenjsj.betterflow.action.GBOARD_BRIDGE"
        const val COMMAND_SNAPSHOT = "snapshot"
        const val COMMAND_GET_TOGGLE_PENDING_INTENT = "toggle_pending_intent"
        const val EXTRA_COMMAND = "bridge_command"
        const val EXTRA_RESULT_RECEIVER = "bridge_result_receiver"
        const val EXTRA_VOICE_STATE = "bridge_voice_state"
        const val EXTRA_GBOARD_MIC_ENABLED = "bridge_gboard_mic_enabled"
        const val EXTRA_TOGGLE_PENDING_INTENT = "bridge_toggle_pending_intent"
        const val RESULT_OK = 1
        const val RESULT_REJECTED = 0
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        private const val TAG = "betterFlow/GboardReceiver"
    }
}
