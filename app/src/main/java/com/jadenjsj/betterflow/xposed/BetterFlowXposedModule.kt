package com.jadenjsj.betterflow.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import com.jadenjsj.betterflow.InputInjector
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BetterFlowXposedModule : XposedModule() {
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!hookInstalled.compareAndSet(false, true)) return
        val onCreate = InputMethodService::class.java.getDeclaredMethod("onCreate")
        hook(onCreate)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? InputMethodService)?.let(::installReceiver)
                result
            }

        val onDestroy = InputMethodService::class.java.getDeclaredMethod("onDestroy")
        hook(onDestroy)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                (chain.thisObject as? InputMethodService)?.let(::removeReceiver)
                chain.proceed()
            }
        log(Log.INFO, TAG, "InputMethodService bridge armed for ${param.packageName}")
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = true

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        param.oldHookHandles.forEach { it.unhook() }
        hookInstalled.set(false)
    }

    private fun installReceiver(service: InputMethodService) {
        synchronized(receivers) {
            if (receivers.containsKey(service)) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != InputInjector.ACTION_COMMIT_TEXT) return
                    val text = intent.getStringExtra(InputInjector.EXTRA_TEXT).orEmpty()
                    val resultReceiver = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(InputInjector.EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(InputInjector.EXTRA_RESULT_RECEIVER)
                    }
                    val ok = try {
                        val connection = service.currentInputConnection
                        connection != null && text.isNotEmpty() && connection.commitText(text, 1)
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "commitText failed: ${t.message}")
                        false
                    }
                    resultReceiver?.send(
                        if (ok) InputInjector.RESULT_OK else InputInjector.RESULT_FAILED,
                        Bundle().apply { putString(InputInjector.EXTRA_REQUEST_ID, intent.getStringExtra(InputInjector.EXTRA_REQUEST_ID)) },
                    )
                }
            }
            val filter = IntentFilter(InputInjector.ACTION_COMMIT_TEXT)
            if (Build.VERSION.SDK_INT >= 33) {
                service.registerReceiver(receiver, filter, InputInjector.COMMIT_PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                service.registerReceiver(receiver, filter, InputInjector.COMMIT_PERMISSION, null)
            }
            receivers[service] = receiver
            log(Log.INFO, TAG, "IME commit receiver registered in ${service.packageName}")
        }
    }

    private fun removeReceiver(service: InputMethodService) {
        synchronized(receivers) {
            receivers.remove(service)?.let {
                runCatching { service.unregisterReceiver(it) }
            }
        }
    }

    companion object {
        private const val TAG = "betterFlow/Xposed"
        private val hookInstalled = AtomicBoolean(false)
        private val receivers = WeakHashMap<InputMethodService, BroadcastReceiver>()
    }
}
