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
    const val KEY_STREAMING_API_KEY = "streaming_api_key"
    const val KEY_GBOARD_MIC_ENABLED = "gboard_mic_enabled"
    const val KEY_LEGACY_TRANSCRIPTION = "legacy_transcription"

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
                .remove(KEY_STREAMING_API_KEY)
                .remove(KEY_GBOARD_MIC_ENABLED)
                .remove(KEY_LEGACY_TRANSCRIPTION)
            if (session != null) {
                edit.putString(KEY_EMAIL, session.email)
                    .putString(KEY_ACCESS, session.accessToken)
                    .putLong(KEY_EXPIRES, session.expiresAt ?: AuthStore.jwtExpiresAt(session.accessToken) ?: 0L)
                session.refreshToken?.let { edit.putString(KEY_REFRESH, it) }
            }
            appContext?.let { context ->
                Prefs.streamingApiKey(context)?.let { edit.putString(KEY_STREAMING_API_KEY, it) }
                edit.putBoolean(KEY_GBOARD_MIC_ENABLED, Prefs.gboardMicEnabled(context))
                edit.putBoolean(KEY_LEGACY_TRANSCRIPTION, Prefs.legacyTranscription(context))
            }
            check(edit.commit()) { "remote preference commit returned false" }
            Log.i(TAG, if (session != null) "Wispr auth synced to LSPosed remote prefs" else "Wispr auth cleared from LSPosed remote prefs")
        }.onFailure {
            Log.e(TAG, "Could not sync Wispr auth to LSPosed", it)
        }
    }

    fun syncFromLocal() {
        val context = appContext ?: return
        sync(AuthStore(context).load())
    }

    private const val TAG = "betterFlow/XposedSync"
}
