package com.jadenjsj.betterflow

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Invisible root-module recovery entry point.
 *
 * HyperOS does not expose `cmd package set-stopped-state`, and a force-stopped
 * package cannot have a service resolved directly. Starting an activity is the
 * platform-supported way to clear that state. The KernelSU watchdog invokes
 * this no-display activity only after a direct OverlayService wake failed.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startForegroundService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_WAKE),
            )
        }.onFailure {
            Log.e(TAG, "Could not wake OverlayService", it)
        }
        finish()
    }

    companion object {
        private const val TAG = "betterFlow/WakeActivity"
    }
}
