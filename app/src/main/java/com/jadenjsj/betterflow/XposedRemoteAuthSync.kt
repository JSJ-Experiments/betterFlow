package com.jadenjsj.betterflow

import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object XposedRemoteAuthSync {
    const val GROUP = "betterflow_auth"
    const val KEY_EMAIL = "email"
    const val KEY_ACCESS = "access_token"
    const val KEY_REFRESH = "refresh_token"
    const val KEY_EXPIRES = "expires_at"

    @Volatile private var appContext: Context? = null
    @Volatile private var service: XposedService? = null
    @Volatile private var initialized = false

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(bound: XposedService) {
                    service = bound
                    Log.i(TAG, "LSPosed companion service connected: API ${bound.getAPIVersion()}")
                    syncFromLocal()
                }

                override fun onServiceDied(dead: XposedService) {
                    if (service === dead) service = null
                    Log.w(TAG, "LSPosed companion service disconnected")
                }
            })
        }
    }

    fun sync(session: WisprSession?) {
        val bound = service ?: return
        runCatching {
            val prefs = bound.getRemotePreferences(GROUP) ?: return
            val edit = prefs.edit()
                .remove(KEY_EMAIL)
                .remove(KEY_ACCESS)
                .remove(KEY_REFRESH)
                .remove(KEY_EXPIRES)
            if (session != null) {
                edit.putString(KEY_EMAIL, session.email)
                    .putString(KEY_ACCESS, session.accessToken)
                    .putLong(KEY_EXPIRES, session.expiresAt ?: AuthStore.jwtExpiresAt(session.accessToken) ?: 0L)
                session.refreshToken?.let { edit.putString(KEY_REFRESH, it) }
            }
            check(edit.commit()) { "remote preference commit returned false" }
            Log.i(TAG, if (session != null) "Wispr auth synced to LSPosed remote prefs" else "Wispr auth cleared from LSPosed remote prefs")
        }.onFailure {
            Log.e(TAG, "Could not sync Wispr auth to LSPosed", it)
        }
    }

    private fun syncFromLocal() {
        val context = appContext ?: return
        sync(AuthStore(context).load())
    }

    private const val TAG = "betterFlow/XposedSync"
}
