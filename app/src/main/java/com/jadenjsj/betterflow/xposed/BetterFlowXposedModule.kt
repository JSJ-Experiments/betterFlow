package com.jadenjsj.betterflow.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
            val onWindowShown = InputMethodService::class.java.getDeclaredMethod("onWindowShown")
            hook(onWindowShown, ImeWindowShownHooker::class.java)

            val performClick = View::class.java.getDeclaredMethod("performClick")
            hook(performClick, ViewClickDiagnosticHooker::class.java)

            val dispatchTouch = View::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            hook(dispatchTouch, ViewTouchDiagnosticHooker::class.java)
            val dispatchGroupTouch = ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            hook(dispatchGroupTouch, ViewTouchDiagnosticHooker::class.java)
            log("$TAG Gboard view-tree + touch diagnostics armed")
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


    private fun resourceName(view: View): String? = if (view.id != View.NO_ID) {
        runCatching { view.resources.getResourceName(view.id) }.getOrNull()
    } else null

    private fun viewSummary(view: View): String {
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        return "class=${view.javaClass.name} id=${view.id} res=${resourceName(view) ?: "-"} " +
            "desc=${view.contentDescription ?: "-"} pos=${location[0]},${location[1]} " +
            "size=${view.width}x${view.height} visible=${view.visibility == View.VISIBLE}"
    }

    private fun dumpImeTree(service: InputMethodService) {
        val root = service.window?.window?.decorView ?: run {
            log("$TAG TREE no IME decorView")
            return
        }
        var visited = 0
        var candidates = 0
        fun walk(view: View, depth: Int) {
            visited++
            val res = resourceName(view).orEmpty()
            val desc = view.contentDescription?.toString().orEmpty()
            val haystack = "$res $desc ${view.javaClass.name}".lowercase()
            val location = IntArray(2)
            runCatching { view.getLocationOnScreen(location) }
            val rootLocation = IntArray(2)
            runCatching { root.getLocationOnScreen(rootLocation) }
            val toolbarBand = view.visibility == View.VISIBLE &&
                location[1] >= rootLocation[1] && location[1] <= rootLocation[1] + 260
            val interesting = listOf("mic", "voice", "dictat", "speech", "toolbar").any(haystack::contains) ||
                (toolbarBand && location[0] >= root.width * 4 / 5)
            if (interesting) {
                candidates++
                log("$TAG TREE depth=$depth ${viewSummary(view)}")
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        log("$TAG TREE complete visited=$visited candidates=$candidates root=${viewSummary(root)}")
    }

    private fun logTouch(view: View, event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_UP) return
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val screenX = location[0] + event.x.toInt()
        val screenY = location[1] + event.y.toInt()
        log("$TAG TOUCH up screen=$screenX,$screenY local=${event.x.toInt()},${event.y.toInt()} ${viewSummary(view)}")
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
    class ImeWindowShownHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            fun after(callback: AfterHookCallback) {
                val service = callback.thisObject as? InputMethodService ?: return
                Handler(service.mainLooper).postDelayed({
                    runCatching { activeModule?.dumpImeTree(service) }
                        .onFailure { activeModule?.log("$TAG dumpImeTree failed: ${it.message}", it) }
                }, 500L)
            }
        }
    }

    @XposedHooker
    class ViewTouchDiagnosticHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback) {
                val view = callback.thisObject as? View ?: return
                val event = callback.args.firstOrNull() as? MotionEvent ?: return
                runCatching { activeModule?.logTouch(view, event) }
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
