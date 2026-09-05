package com.jadenjsj.betterflow

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

class GboardBridgeService : Service() {
    private val bridge = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_TOGGLE && code != TRANSACTION_GET_STATE && code != TRANSACTION_GET_CONFIG) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(DESCRIPTOR)

            val callerUid = Binder.getCallingUid()
            val callerPackages = packageManager.getPackagesForUid(callerUid).orEmpty()
            if (GBOARD_PACKAGE !in callerPackages) {
                Log.w(TAG, "Rejected Gboard bridge call from uid=$callerUid packages=${callerPackages.joinToString()}")
                reply?.writeNoException()
                when (code) {
                    TRANSACTION_TOGGLE -> reply?.writeInt(0)
                    TRANSACTION_GET_STATE -> reply?.writeString("idle")
                    TRANSACTION_GET_CONFIG -> reply?.writeInt(0)
                }
                return true
            }

            if (code == TRANSACTION_GET_STATE) {
                reply?.writeNoException()
                reply?.writeString(VoiceRuntimeState.wireName)
                return true
            }

            if (code == TRANSACTION_GET_CONFIG) {
                reply?.writeNoException()
                reply?.writeInt(if (Prefs.gboardMicEnabled(this@GboardBridgeService)) 1 else 0)
                return true
            }

            Log.i(TAG, "Accepted Gboard Binder toggle from uid=$callerUid")
            val identity = Binder.clearCallingIdentity()
            val ok = try {
                startForegroundService(
                    Intent(this@GboardBridgeService, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_TOGGLE),
                )
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Could not start OverlayService from Gboard Binder trigger", t)
                false
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
            reply?.writeNoException()
            reply?.writeInt(if (ok) 1 else 0)
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Gboard Binder bridge created")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Gboard Binder bridge bound")
        return bridge
    }

    override fun onDestroy() {
        Log.i(TAG, "Gboard Binder bridge destroyed")
        super.onDestroy()
    }

    companion object {
        const val DESCRIPTOR = "com.jadenjsj.betterflow.GboardBridge"
        const val TRANSACTION_TOGGLE = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_GET_STATE = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_GET_CONFIG = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        private const val TAG = "betterFlow/GboardBridge"
    }
}
