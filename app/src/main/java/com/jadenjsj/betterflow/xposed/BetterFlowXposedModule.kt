package com.jadenjsj.betterflow.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.jadenjsj.betterflow.AudioRecorderController
import com.jadenjsj.betterflow.AuthStore
import com.jadenjsj.betterflow.InputInjector
import com.jadenjsj.betterflow.WisprClient
import com.jadenjsj.betterflow.WisprSession
import com.jadenjsj.betterflow.WisprSessionStore
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class BetterFlowXposedModule(
    base: XposedInterface,
    moduleParam: ModuleLoadedParam,
) : XposedModule(base, moduleParam) {

    private val recorder = AudioRecorderController()
    private val recentCommitRequests = LinkedHashMap<String, Long>()
    @Volatile private var voiceState = VoiceState.IDLE
    @Volatile private var hookWispr: WisprClient? = null

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
            // The visible SoftKeyView is the semantic key. Gboard dispatches the
            // actual gesture through the surrounding SoftKeyboardView.
            hook(
                ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java),
                GboardTouchHooker::class.java,
            )
            prepareHookWispr()
            log("$TAG Gboard mic interception armed (in-process voice path)")
        }
        log("$TAG InputMethodService bridge armed for ${param.packageName}")
    }

    private fun prepareHookWispr() {
        val prefs = runCatching { getRemotePreferences(REMOTE_AUTH_GROUP) }
            .onFailure { log("$TAG could not open remote auth prefs: ${it.message}", it) }
            .getOrNull()
            ?: return
        val store = HookSessionStore(prefs)
        hookWispr = WisprClient(store)
        val session = store.load()
        log(
            "$TAG remote Wispr auth ${if (session != null) "available" else "not yet synced"}" +
                (session?.email?.takeIf { it.isNotBlank() }?.let { " for $it" } ?: ""),
        )
    }

    private fun installReceiver(service: InputMethodService) {
        currentIme = WeakReference(service)
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
        if (currentIme?.get() === service) currentIme = null
        if (recorder.isRecording()) runCatching { recorder.stopAndGetWav() }
        voiceState = VoiceState.IDLE
        micPressed = false
        setMicVisual(VoiceState.IDLE)
        clearMicStateOverlay(micKeyView?.get())
        micKeyView = null
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

    private fun locateGboardMic(service: InputMethodService) {
        val root = service.window?.window?.decorView ?: return
        var match: View? = null
        fun walk(view: View) {
            if (match != null) return
            val res = resourceName(view).orEmpty()
            val desc = view.contentDescription?.toString().orEmpty()
            if (
                res.endsWith(":id/key_pos_header_power_key") ||
                (view.javaClass.name == SOFT_KEY_CLASS && desc.equals("Use voice typing", ignoreCase = true))
            ) {
                match = view
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        val found = match
        if (found != null) {
            val previous = micKeyView?.get()
            if (previous != null && previous !== found) clearMicStateOverlay(previous)
            micKeyView = WeakReference(found)
            setMicVisual(voiceState)
            log(
                "$TAG Gboard mic target res=${resourceName(found)} desc=${found.contentDescription} " +
                    "class=${found.javaClass.name} bounds=${screenBounds(found)}",
            )
        } else {
            micKeyView = null
            log("$TAG Gboard mic target not found in live IME tree")
        }
    }

    private fun handleGboardTouch(callback: BeforeHookCallback) {
        val view = callback.thisObject as? View ?: return
        if (view.javaClass.name != SOFT_KEYBOARD_CLASS) return
        val event = callback.args.firstOrNull() as? MotionEvent ?: return

        val target = micKeyView?.get()
        val bounds = target?.takeIf { it.visibility == View.VISIBLE }?.let(::screenBounds)
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
                log("$TAG Gboard mic gesture UP -> in-process voice toggle")
                toggleHookVoice()
                setMicVisual(voiceState)
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

    private fun toggleHookVoice() {
        val service = currentIme?.get() ?: run {
            log("$TAG voice toggle ignored: no live IME service")
            return
        }
        when (voiceState) {
            VoiceState.IDLE -> startHookRecording(service)
            VoiceState.RECORDING -> stopHookRecordingAndTranscribe(service)
            VoiceState.PROCESSING -> {
                micKeyView?.get()?.performHapticFeedback(HapticFeedbackConstants.REJECT)
                log("$TAG voice toggle ignored while processing")
            }
        }
    }

    private fun startHookRecording(service: InputMethodService) {
        if (hookWispr == null) prepareHookWispr()
        val authAvailable = runCatching {
            getRemotePreferences(REMOTE_AUTH_GROUP).getString(KEY_ACCESS, null)?.isNotBlank() == true
        }.getOrDefault(false)
        if (!authAvailable) {
            log("$TAG cannot record: Wispr auth has not been synced to LSPosed remote prefs")
            Toast.makeText(service, "betterFlow: open the app once to sync Wispr login", Toast.LENGTH_LONG).show()
            return
        }
        voiceState = VoiceState.RECORDING
        setMicVisual(VoiceState.RECORDING)
        try {
            recorder.start()
            Toast.makeText(service, "betterFlow recording — tap mic to finish", Toast.LENGTH_SHORT).show()
            log("$TAG in-process recording started")
        } catch (t: Throwable) {
            voiceState = VoiceState.IDLE
            setMicVisual(VoiceState.IDLE)
            log("$TAG in-process recording start failed: ${t.message}", t)
            Toast.makeText(service, "betterFlow mic failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopHookRecordingAndTranscribe(service: InputMethodService) {
        if (voiceState != VoiceState.RECORDING) return
        voiceState = VoiceState.PROCESSING
        setMicVisual(VoiceState.PROCESSING)
        Toast.makeText(service, "betterFlow transcribing…", Toast.LENGTH_SHORT).show()
        val serviceRef = WeakReference(service)
        Thread({
            try {
                val wav = recorder.stopAndGetWav()
                check(wav.size > 44) { "no microphone audio captured" }
                val client = hookWispr ?: run {
                    prepareHookWispr()
                    hookWispr ?: error("Wispr client unavailable")
                }
                log("$TAG captured ${wav.size} WAV bytes; sending to Wispr")
                val text = runBlocking { client.transcribe(wav) }.trim()
                check(text.isNotEmpty()) { "Wispr returned empty text" }
                val ime = serviceRef.get() ?: error("IME service disappeared before transcription completed")
                Handler(ime.mainLooper).post {
                    try {
                        val connection = ime.currentInputConnection
                        val ok = connection != null && connection.commitText(text, 1)
                        if (ok) {
                            log("$TAG Wispr transcription committed through Gboard InputConnection (${text.length} chars)")
                            Toast.makeText(ime, "betterFlow inserted ${text.length} characters", Toast.LENGTH_SHORT).show()
                        } else {
                            log("$TAG Wispr transcription ready but InputConnection was unavailable")
                            Toast.makeText(ime, "betterFlow: text ready but input field was lost", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        voiceState = VoiceState.IDLE
                        setMicVisual(VoiceState.IDLE)
                    }
                }
            } catch (t: Throwable) {
                log("$TAG in-process transcription failed: ${t.message}", t)
                serviceRef.get()?.let { ime ->
                    Handler(ime.mainLooper).post {
                        voiceState = VoiceState.IDLE
                        setMicVisual(VoiceState.IDLE)
                        Toast.makeText(ime, "betterFlow failed: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                } ?: run {
                    voiceState = VoiceState.IDLE
                    setMicVisual(VoiceState.IDLE)
                }
            }
        }, "betterflow-gboard-transcribe").start()
    }

    private fun clearMicStateOverlay(mic: View?) {
        val overlay = micStateOverlay?.get() ?: return
        mic?.overlay?.remove(overlay)
        micStateOverlay = null
    }

    private fun setMicVisual(state: VoiceState) {
        val mic = micKeyView?.get() ?: return
        clearMicStateOverlay(mic)

        val scale = if (micPressed) {
            0.82f
        } else {
            when (state) {
                VoiceState.IDLE -> 1.0f
                VoiceState.RECORDING -> 0.94f
                VoiceState.PROCESSING -> 0.88f
            }
        }
        mic.scaleX = scale
        mic.scaleY = scale
        mic.alpha = if (micPressed) {
            0.70f
        } else {
            when (state) {
                VoiceState.IDLE -> 1.0f
                VoiceState.RECORDING -> 0.92f
                VoiceState.PROCESSING -> 0.74f
            }
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
            VoiceState.IDLE -> "Use voice typing"
            VoiceState.RECORDING -> "betterFlow recording; tap to finish"
            VoiceState.PROCESSING -> "betterFlow transcribing"
        }
        mic.invalidate()
    }

    private class HookSessionStore(private val prefs: SharedPreferences) : WisprSessionStore {
        @Volatile private var refreshedInMemory: WisprSession? = null

        override fun load(): WisprSession? {
            val remote = remoteSession()
            val memory = refreshedInMemory
            if (memory == null) return remote
            if (remote == null) return memory
            val memoryExpiry = memory.expiresAt ?: AuthStore.jwtExpiresAt(memory.accessToken) ?: 0L
            val remoteExpiry = remote.expiresAt ?: AuthStore.jwtExpiresAt(remote.accessToken) ?: 0L
            return if (memoryExpiry >= remoteExpiry) memory else remote
        }

        override fun save(session: WisprSession) {
            // Hook-side remote prefs are intentionally read-only. Keep refreshed
            // credentials for this Gboard process; the companion app owns persistence.
            refreshedInMemory = session
        }

        private fun remoteSession(): WisprSession? {
            val access = prefs.getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
            return WisprSession(
                email = prefs.getString(KEY_EMAIL, "wispr-user") ?: "wispr-user",
                accessToken = access,
                refreshToken = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotBlank() },
                expiresAt = prefs.getLong(KEY_EXPIRES, 0L).takeIf { it > 0L } ?: AuthStore.jwtExpiresAt(access),
            )
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

    private enum class VoiceState { IDLE, RECORDING, PROCESSING }

    companion object {
        private const val TAG = "betterFlow/Xposed"
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        private const val SOFT_KEY_CLASS = "com.google.android.libraries.inputmethod.widgets.SoftKeyView"
        private const val SOFT_KEYBOARD_CLASS = "com.google.android.libraries.inputmethod.widgets.SoftKeyboardView"
        private const val REMOTE_AUTH_GROUP = "betterflow_auth"
        private const val KEY_EMAIL = "email"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val COMMIT_DEDUPE_TTL_MS = 10_000L

        @Volatile private var activeModule: BetterFlowXposedModule? = null
        @Volatile private var currentIme: WeakReference<InputMethodService>? = null
        @Volatile private var micKeyView: WeakReference<View>? = null
        @Volatile private var micStateOverlay: WeakReference<Drawable>? = null
        @Volatile private var micGestureActive = false
        @Volatile private var micPressed = false
        private val hookInstalled = AtomicBoolean(false)
        private val receivers = WeakHashMap<InputMethodService, BroadcastReceiver>()
    }
}
