package com.jadenjsj.betterflow.xposed

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.HapticFeedbackConstants
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
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class BetterFlowXposedModule(
    base: XposedInterface,
    moduleParam: ModuleLoadedParam,
) : XposedModule(base, moduleParam) {

    private val recentCommitRequests = LinkedHashMap<String, Long>()
    @Volatile private var voiceState = VoiceState.IDLE

    init {
        activeModule = this
        log("$TAG module loaded in ${moduleParam.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (!hookInstalled.compareAndSet(false, true)) return

        hook(
            InputMethodService::class.java.getDeclaredMethod("onCreate"),
            ImeOnCreateHooker::class.java,
        )
        hook(
            InputMethodService::class.java.getDeclaredMethod("onDestroy"),
            ImeOnDestroyHooker::class.java,
        )

        if (param.packageName == GBOARD_PACKAGE) {
            hook(
                InputMethodService::class.java.getDeclaredMethod("onWindowShown"),
                ImeWindowShownHooker::class.java,
            )
            // Gboard's visible SoftKeyView is semantic only; actual gestures are
            // dispatched by the surrounding SoftKeyboardView.
            hook(
                ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java),
                GboardTouchHooker::class.java,
            )
            log("$TAG Gboard mic interception armed")
        }
        log("$TAG InputMethodService bridge armed for ${param.packageName}")
    }

    private fun installReceiver(service: InputMethodService) {
        currentIme = WeakReference(service)
        if (service.packageName == GBOARD_PACKAGE) bindBridge(service)
        synchronized(receivers) {
            if (receivers.containsKey(service)) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        InputInjector.ACTION_VOICE_STATE -> {
                            voiceState = VoiceState.fromWire(intent.getStringExtra(InputInjector.EXTRA_VOICE_STATE))
                            setMicVisual(voiceState)
                            log("$TAG voice state <- ${voiceState.wireName}")
                            return
                        }
                        InputInjector.ACTION_CONFIG_CHANGED -> {
                            gboardMicEnabled = intent.getBooleanExtra(InputInjector.EXTRA_GBOARD_MIC_ENABLED, true)
                            micGestureActive = false
                            micPressed = false
                            if (gboardMicEnabled) locateGboardMic(service) else restoreMicVisual(micKeyView?.get())
                            log("$TAG Gboard mic config <- enabled=$gboardMicEnabled")
                            return
                        }
                        InputInjector.ACTION_COMMIT_TEXT -> Unit
                        else -> return
                    }

                    val text = intent.getStringExtra(InputInjector.EXTRA_TEXT).orEmpty()
                    val resultReceiver = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(InputInjector.EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(InputInjector.EXTRA_RESULT_RECEIVER)
                    }
                    val requestId = intent.getStringExtra(InputInjector.EXTRA_REQUEST_ID).orEmpty()
                    val deadline = intent.getLongExtra(InputInjector.EXTRA_DEADLINE_ELAPSED_REALTIME, Long.MAX_VALUE)
                    val now = SystemClock.elapsedRealtime()
                    val expired = deadline != Long.MAX_VALUE && now > deadline
                    val duplicate = requestId.isNotEmpty() && !claimCommitRequest(requestId, now)
                    val ok = when {
                        expired -> {
                            log("$TAG rejecting expired commit request=$requestId lateBy=${now - deadline}ms")
                            false
                        }
                        duplicate -> {
                            log("$TAG suppressing duplicate commit request=$requestId")
                            true
                        }
                        text.isEmpty() -> false
                        else -> try {
                            val connection = service.currentInputConnection
                            connection != null && connection.commitText(text, 1)
                        } catch (t: Throwable) {
                            log("$TAG commitText failed request=$requestId: ${t.message}", t)
                            false
                        }
                    }
                    resultReceiver?.send(
                        if (ok) InputInjector.RESULT_OK else InputInjector.RESULT_FAILED,
                        Bundle().apply { putString(InputInjector.EXTRA_REQUEST_ID, requestId) },
                    )
                    log("$TAG commit request=$requestId expired=$expired duplicate=$duplicate success=$ok")
                }
            }
            val filter = IntentFilter().apply {
                addAction(InputInjector.ACTION_COMMIT_TEXT)
                addAction(InputInjector.ACTION_VOICE_STATE)
                addAction(InputInjector.ACTION_CONFIG_CHANGED)
            }
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

    private fun claimCommitRequest(requestId: String, now: Long): Boolean = synchronized(recentCommitRequests) {
        val iterator = recentCommitRequests.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > COMMIT_DEDUPE_TTL_MS) iterator.remove()
        }
        if (recentCommitRequests.containsKey(requestId)) {
            false
        } else {
            recentCommitRequests[requestId] = now
            true
        }
    }

    private fun removeReceiver(service: InputMethodService) {
        synchronized(receivers) {
            receivers.remove(service)?.let { receiver ->
                runCatching { service.unregisterReceiver(receiver) }
            }
        }
        if (currentIme?.get() === service) {
            unbindBridge(service)
            currentIme = null
        }
        micPressed = false
        voiceState = VoiceState.IDLE
        restoreMicVisual(micKeyView?.get())
        micKeyView = null
        micOriginalContentDescription = null
        micGestureActive = false
    }

    private fun resourceName(view: View): String? = if (view.id != View.NO_ID) {
        runCatching { view.resources.getResourceName(view.id) }.getOrNull()
    } else null

    private fun screenBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private data class MicCandidate(val view: View, val score: Int, val signals: String)

    private fun micCandidate(view: View): MicCandidate? {
        val res = resourceName(view).orEmpty()
        val resLower = res.lowercase()
        val desc = view.contentDescription?.toString().orEmpty()
        val descLower = desc.lowercase()
        val signals = mutableListOf<String>()
        var score = 0

        val resourceSemantic = VOICE_RESOURCE_TOKENS.any { it in resLower }
        val descriptionSemantic = VOICE_DESCRIPTION_TOKENS.any { it in descLower }
        val knownVoiceSlot = res.endsWith(":id/key_pos_header_power_key")
        val softKey = view.javaClass.name == SOFT_KEY_CLASS

        if (resourceSemantic) { score += 9; signals += "semantic-resource" }
        if (descriptionSemantic) { score += 9; signals += "semantic-description" }
        if (knownVoiceSlot) { score += 7; signals += "known-header-slot" }
        if (softKey) { score += 10; signals += "soft-key" }
        if (view.isShown) { score += 1; signals += "shown" }

        // Fail safe: position/slot identity is only a ranking hint. We intercept
        // only a view that advertises voice semantics through its resource or
        // accessibility description. If Gboard changes unexpectedly, its native
        // behavior wins instead of betterFlow stealing the wrong key.
        val semanticallyVoice = resourceSemantic || descriptionSemantic
        if (!semanticallyVoice) return null
        return MicCandidate(view, score, signals.joinToString("+"))
    }

    private fun locateGboardMic(service: InputMethodService) {
        val root = service.window?.window?.decorView ?: return
        var best: MicCandidate? = null
        fun walk(view: View) {
            micCandidate(view)?.let { candidate ->
                if (best == null || candidate.score > best!!.score) best = candidate
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        val found = best?.view
        if (found != null) {
            val previous = micKeyView?.get()
            if (previous !== found) {
                restoreMicVisual(previous)
                micOriginalContentDescription = found.contentDescription
            }
            micKeyView = WeakReference(found)
            setMicVisual(voiceState)
            log(
                "$TAG Gboard mic target score=${best?.score} signals=${best?.signals} " +
                    "res=${resourceName(found)} desc=${found.contentDescription} " +
                    "class=${found.javaClass.name} bounds=${screenBounds(found)}",
            )
        } else {
            restoreMicVisual(micKeyView?.get())
            micKeyView = null
            micOriginalContentDescription = null
            log("$TAG Gboard mic target not found in live IME tree; original Gboard behavior preserved")
        }
    }

    private fun handleGboardTouch(callback: BeforeHookCallback) {
        val view = callback.thisObject as? View ?: return
        if (view.javaClass.name != SOFT_KEYBOARD_CLASS) return
        val event = callback.args.firstOrNull() as? MotionEvent ?: return
        if (!gboardMicEnabled) return

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val current = micKeyView?.get()
            if (current == null || !current.isAttachedToWindow || !current.isShown || current.width <= 0 || current.height <= 0) {
                currentIme?.get()?.let(::locateGboardMic)
            }
        }
        val target = micKeyView?.get()
        val bounds = target?.takeIf { it.isAttachedToWindow && it.isShown }?.let(::screenBounds)
        val inside = bounds?.contains(event.rawX.toInt(), event.rawY.toInt()) == true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return
                micGestureActive = true
                micPressed = true
                setMicVisual(voiceState)
                target?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                log("$TAG Gboard mic gesture DOWN at ${event.rawX.toInt()},${event.rawY.toInt()}")
                callback.returnAndSkip(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (micGestureActive) callback.returnAndSkip(true)
            }
            MotionEvent.ACTION_UP -> {
                if (!micGestureActive) return
                micGestureActive = false
                micPressed = false
                callback.returnAndSkip(true)
                val previous = voiceState
                if (previous == VoiceState.PROCESSING) {
                    voiceState = VoiceState.IDLE
                    setMicVisual(voiceState)
                    target?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    log("$TAG Gboard mic gesture UP while processing -> cancel")
                    if (!triggerBetterFlow()) {
                        voiceState = previous
                        setMicVisual(voiceState)
                    }
                    return
                }
                voiceState = if (previous == VoiceState.IDLE) VoiceState.RECORDING else VoiceState.PROCESSING
                setMicVisual(voiceState)
                log("$TAG Gboard mic gesture UP -> betterFlow toggle, optimistic=${voiceState.wireName}")
                if (!triggerBetterFlow()) {
                    voiceState = previous
                    setMicVisual(voiceState)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!micGestureActive) return
                micGestureActive = false
                micPressed = false
                setMicVisual(voiceState)
                callback.returnAndSkip(true)
                log("$TAG Gboard mic gesture CANCEL")
            }
        }
    }

    private fun bindBridge(service: InputMethodService) {
        if (bridgeConnection != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bridgeBinder = binder
                syncBridgeConfig()
                if (pendingToggle) {
                    pendingToggle = false
                    if (!transactToggle()) syncBridgeState()
                } else {
                    syncBridgeState()
                }
                log("$TAG Gboard Binder bridge connected: $name state=${voiceState.wireName}")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bridgeBinder = null
                log("$TAG Gboard Binder bridge disconnected: $name")
            }

            override fun onBindingDied(name: ComponentName?) {
                bridgeBinder = null
                log("$TAG Gboard Binder bridge binding died: $name")
            }

            override fun onNullBinding(name: ComponentName?) {
                bridgeBinder = null
                log("$TAG Gboard Binder bridge returned null binding: $name")
            }
        }
        bridgeConnection = connection
        val intent = Intent()
            .setClassName(BETTERFLOW_PACKAGE, GBOARD_BRIDGE_SERVICE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        val ok = runCatching {
            service.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        }.getOrElse {
            log("$TAG Gboard Binder bridge bind failed: ${it.message}", it)
            false
        }
        if (!ok) {
            bridgeConnection = null
            log("$TAG Gboard Binder bridge bind returned false")
        } else {
            log("$TAG Gboard Binder bridge binding requested")
        }
    }

    private fun unbindBridge(service: InputMethodService) {
        val connection = bridgeConnection ?: return
        bridgeConnection = null
        bridgeBinder = null
        pendingToggle = false
        runCatching { service.unbindService(connection) }
    }

    private fun transactToggle(): Boolean {
        val binder = bridgeBinder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(GBOARD_BRIDGE_DESCRIPTOR)
            if (!binder.transact(GBOARD_TRANSACTION_TOGGLE, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
        } catch (t: Throwable) {
            log("$TAG Gboard Binder transaction failed: ${t.message}", t)
            bridgeBinder = null
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun syncBridgeState() {
        val binder = bridgeBinder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(GBOARD_BRIDGE_DESCRIPTOR)
            if (!binder.transact(GBOARD_TRANSACTION_GET_STATE, data, reply, 0)) return
            reply.readException()
            voiceState = VoiceState.fromWire(reply.readString())
            setMicVisual(voiceState)
        } catch (t: Throwable) {
            log("$TAG Gboard Binder state query failed: ${t.message}", t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun syncBridgeConfig() {
        val binder = bridgeBinder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(GBOARD_BRIDGE_DESCRIPTOR)
            if (!binder.transact(GBOARD_TRANSACTION_GET_CONFIG, data, reply, 0)) return
            reply.readException()
            gboardMicEnabled = reply.readInt() != 0
            if (gboardMicEnabled) {
                currentIme?.get()?.let(::locateGboardMic)
            } else {
                micGestureActive = false
                micPressed = false
                restoreMicVisual(micKeyView?.get())
            }
            log("$TAG Gboard mic config synced enabled=$gboardMicEnabled")
        } catch (t: Throwable) {
            log("$TAG Gboard Binder config query failed: ${t.message}", t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun triggerBetterFlow(): Boolean {
        val service = currentIme?.get() ?: run {
            log("$TAG cannot trigger betterFlow: no live IME service")
            return false
        }
        if (transactToggle()) {
            log("$TAG authenticated Gboard Binder toggle accepted")
            return true
        }
        pendingToggle = true
        bindBridge(service)
        if (bridgeBinder != null && pendingToggle) {
            pendingToggle = false
            if (transactToggle()) {
                log("$TAG authenticated Gboard Binder toggle accepted after rebind")
                return true
            }
        }
        // A successful bind request may complete asynchronously and consume pendingToggle.
        return pendingToggle || bridgeConnection != null
    }

    private fun clearMicStateOverlay(mic: View?) {
        val overlay = micStateOverlay?.get() ?: return
        mic?.overlay?.remove(overlay)
        micStateOverlay = null
    }

    private fun restoreMicVisual(mic: View?) {
        if (mic == null) return
        clearMicStateOverlay(mic)
        mic.scaleX = 1f
        mic.scaleY = 1f
        mic.alpha = 1f
        micOriginalContentDescription?.let { mic.contentDescription = it }
        mic.invalidate()
    }

    private fun setMicVisual(state: VoiceState) {
        val mic = micKeyView?.get() ?: return
        if (!gboardMicEnabled) {
            restoreMicVisual(mic)
            return
        }
        clearMicStateOverlay(mic)
        val scale = if (micPressed) 0.82f else when (state) {
            VoiceState.IDLE -> 1.0f
            VoiceState.RECORDING -> 0.94f
            VoiceState.PROCESSING -> 0.88f
        }
        mic.scaleX = scale
        mic.scaleY = scale
        mic.alpha = if (micPressed) 0.70f else when (state) {
            VoiceState.IDLE -> 1.0f
            VoiceState.RECORDING -> 0.92f
            VoiceState.PROCESSING -> 0.74f
        }
        val tint = when (state) {
            VoiceState.IDLE -> null
            VoiceState.RECORDING -> 0x66D14D4D.toInt()
            VoiceState.PROCESSING -> 0x66B17A30.toInt()
        }
        if (tint != null && mic.width > 0 && mic.height > 0) {
            val inset = (min(mic.width, mic.height) * 0.08f).toInt()
            val overlay = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tint)
                setBounds(inset, inset, mic.width - inset, mic.height - inset)
            }
            mic.overlay.add(overlay)
            micStateOverlay = WeakReference(overlay as Drawable)
        }
        mic.contentDescription = when (state) {
            VoiceState.IDLE -> micOriginalContentDescription ?: mic.contentDescription
            VoiceState.RECORDING -> "betterFlow recording; tap to finish"
            VoiceState.PROCESSING -> "betterFlow transcribing; tap to cancel"
        }
        mic.invalidate()
    }

    private enum class VoiceState(val wireName: String) {
        IDLE("idle"),
        RECORDING("recording"),
        PROCESSING("processing");

        companion object {
            fun fromWire(value: String?): VoiceState = entries.firstOrNull { it.wireName == value } ?: IDLE
        }
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
                    runCatching { activeModule?.locateGboardMic(service) }
                        .onFailure { activeModule?.log("$TAG Gboard mic discovery failed: ${it.message}", it) }
                }, 350L)
            }
        }
    }

    @XposedHooker
    class GboardTouchHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: BeforeHookCallback) {
                runCatching { activeModule?.handleGboardTouch(callback) }
                    .onFailure { activeModule?.log("$TAG Gboard touch hook failed: ${it.message}", it) }
            }
        }
    }

    companion object {
        private const val TAG = "betterFlow/Xposed"
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        private const val BETTERFLOW_PACKAGE = "com.jadenjsj.betterflow"
        private const val GBOARD_BRIDGE_SERVICE = "com.jadenjsj.betterflow.GboardBridgeService"
        private const val GBOARD_BRIDGE_DESCRIPTOR = "com.jadenjsj.betterflow.GboardBridge"
        private const val GBOARD_TRANSACTION_TOGGLE = IBinder.FIRST_CALL_TRANSACTION
        private const val GBOARD_TRANSACTION_GET_STATE = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val GBOARD_TRANSACTION_GET_CONFIG = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val COMMIT_DEDUPE_TTL_MS = 10_000L
        private const val SOFT_KEY_CLASS = "com.google.android.libraries.inputmethod.widgets.SoftKeyView"
        private const val SOFT_KEYBOARD_CLASS = "com.google.android.libraries.inputmethod.widgets.SoftKeyboardView"
        private val VOICE_RESOURCE_TOKENS = listOf("voice", "microphone", "dictat", "speech", "mic_")
        private val VOICE_DESCRIPTION_TOKENS = listOf(
            "voice", "microphone", "dictat", "speech",
            "语音", "麦克风", "听写", "音声", "マイク", "음성", "마이크",
            "suara", "mikrofon", "dikte", "voz", "voix", "voce", "sprache", "голос",
        )

        @Volatile private var activeModule: BetterFlowXposedModule? = null
        @Volatile private var currentIme: WeakReference<InputMethodService>? = null
        @Volatile private var bridgeBinder: IBinder? = null
        @Volatile private var bridgeConnection: ServiceConnection? = null
        @Volatile private var pendingToggle = false
        @Volatile private var gboardMicEnabled = true
        @Volatile private var micKeyView: WeakReference<View>? = null
        @Volatile private var micOriginalContentDescription: CharSequence? = null
        @Volatile private var micStateOverlay: WeakReference<Drawable>? = null
        @Volatile private var micGestureActive = false
        @Volatile private var micPressed = false
        private val hookInstalled = AtomicBoolean(false)
        private val receivers = WeakHashMap<InputMethodService, BroadcastReceiver>()
    }
}
