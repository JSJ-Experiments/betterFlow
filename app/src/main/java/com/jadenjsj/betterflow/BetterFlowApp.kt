package com.jadenjsj.betterflow

import android.app.Application

class BetterFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedRemoteAuthSync.init(this)
    }
}
