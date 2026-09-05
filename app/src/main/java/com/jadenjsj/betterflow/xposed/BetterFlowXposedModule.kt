package com.jadenjsj.betterflow.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.View
import com.jadenjsj.betterflow.InputInjector
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BetterFlowXposedModule(
    base: XposedInterface,
    moduleParam: ModuleLoadedParam,
) : XposedModule(base, moduleParam) {

    init {
        activeModule = this
        log("$TAG module loaded in ${moduleParam.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (!hookInstalled.compareAndSet(false, true)) return

        val onCreate = InputMethodService::class.java.getDeclaredMethod("onCreate")
        hook(onCreate, ImeOnCreateHooker::class.java)

        val onDestroy = InputMethodService::class.java.getDeclaredMethod("onDestroy")
        hook(onDestroy, ImeOnDestroyHooker::class.java)

        if (param.packageName == GBOARD_PACKAGE) {
            val performClick = View::class.java.getDeclaredMethod("performClick")
            hook(performClick, ViewClickDiagnosticHooker::class.java)
            log("$TAG Gboard click diagnostics armed")
        }
        log("$TAG InputMethodService bridge armed for ${param.packageName}")
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
                        log("$TAG commitText failed: ${t.message}", t)
                        false
                    }
                    resultReceiver?.send(
                        if (ok) InputInjector.RESULT_OK else InputInjector.RESULT_FAILED,
                        Bundle().apply {
                            putString(InputInjector.EXTRA_REQUEST_ID, intent.getStringExtra(InputInjector.EXTRA_REQUEST_ID))
                        },
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
            log("$TAG IME commit receiver registered in ${service.packageName}")
        }
    }

    private fun removeReceiver(service: InputMethodService) {
        synchronized(receivers) {
            receivers.remove(service)?.let { receiver ->
                runCatching { service.unregisterReceiver(receiver) }
            }
        }
    }

    private fun logClickedView(view: View) {
        val resourceName = if (view.id != View.NO_ID) {
            runCatching { view.resources.getResourceName(view.id) }.getOrNull()
        } else null
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val ancestors = buildList {
            var current = view.parent
            var depth = 0
            while (current is View && depth < 6) {
                val name = if (current.id != View.NO_ID) {
                    runCatching { current.resources.getResourceName(current.id) }.getOrNull()
                } else null
                add("${current.javaClass.name}${name?.let { "#$it" }.orEmpty()}")
                current = current.parent
                depth++
            }
        }.joinToString(" <- ")
        log(
            "$TAG CLICK class=${view.javaClass.name} id=${view.id} res=${resourceName ?: "-"} " +
                "desc=${view.contentDescription ?: "-"} pos=${location[0]},${location[1]} " +
                "size=${view.width}x${view.height} ancestors=[$ancestors]",
        )
    }

    @XposedHooker
    class ImeOnCreateHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            fun after(callback: AfterHookCallback) {
                val service = callback.thisObject as? InputMethodService ?: return
                runCatching { activeModule?.installReceiver(service) }
                    .onFailure { activeModule?.log("$TAG installReceiver failed: ${it.message}", it) }
            }
        }
    }

    @XposedHooker
    class ImeOnDestroyHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback) {
                val service = callback.thisObject as? InputMethodService ?: return
                runCatching { activeModule?.removeReceiver(service) }
            }
        }
    }

    @XposedHooker
    class ViewClickDiagnosticHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback) {
                val view = callback.thisObject as? View ?: return
                runCatching { activeModule?.logClickedView(view) }
            }
        }
    }

    companion object {
        private const val TAG = "betterFlow/Xposed"
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        @Volatile private var activeModule: BetterFlowXposedModule? = null
        private val hookInstalled = AtomicBoolean(false)
        private val receivers = WeakHashMap<InputMethodService, BroadcastReceiver>()
    }
}
