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
import com.jadenjsj.betterflow.AudioRecorderController
import com.jadenjsj.betterflow.AuthStore
import com.jadenjsj.betterflow.BuildConfig
import com.jadenjsj.betterflow.InputInjector
import com.jadenjsj.betterflow.Prefs
import com.jadenjsj.betterflow.WisprClient
import com.jadenjsj.betterflow.WisprSession
import com.jadenjsj.betterflow.WisprSessionStore
import com.jadenjsj.betterflow.WisprStreamingClient
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
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class BetterFlowXposedModule(
    base: XposedInterface,
    moduleParam: ModuleLoadedParam,
) : XposedModule(base, moduleParam) {

    private val recentCommitRequests = LinkedHashMap<String, Long>()
    private val hookRecorder = AudioRecorderController()
    private val hookStreamLock = Any()
    private val hookQueuedAudio = ArrayDeque<ByteArray>()
    @Volatile private var hookStreamSession: WisprStreamingClient.Session? = null
    @Volatile private var hookStreamFailure: Throwable? = null
    @Volatile private var hookStreamReady = CountDownLatch(0)
    @Volatile private var hookClients: HookClients? = null
    @Volatile private var hookOperationGeneration = 0L
    @Volatile private var hookOwnsVoiceSession = false
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
            val touchMethod = runCatching {
                Class.forName(SOFT_KEYBOARD_CLASS, false, param.classLoader)
                    .getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            }.getOrElse {
                log("$TAG using ViewGroup touch-hook fallback: ${it.message}")
                ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            }
            hook(
                touchMethod,
                GboardTouchHooker::class.java,
            )
            prepareHookConfig()
            log("$TAG Gboard mic interception armed (in-process streaming primary)")
        }
        log("$TAG InputMethodService bridge armed for ${param.packageName}")
    }

    private fun installReceiver(service: InputMethodService) {
        currentIme = WeakReference(service)
        if (service.packageName == GBOARD_PACKAGE) prepareHookConfig()
        synchronized(receivers) {
            if (receivers.containsKey(service)) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        InputInjector.ACTION_VOICE_STATE -> {
                            if (hookOwnsVoiceSession) return
                            voiceState = VoiceState.fromWire(intent.getStringExtra(InputInjector.EXTRA_VOICE_STATE))
                            applyVoiceStateVisual(service, voiceState)
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
            cancelHookVoice("IME destroyed")
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

    private fun micTargetUsable(): Boolean {
        val mic = micKeyView?.get() ?: return false
        return mic.isAttachedToWindow && mic.isShown && mic.width > 0 && mic.height > 0
    }

    private fun applyVoiceStateVisual(service: InputMethodService, expected: VoiceState) {
        if (!gboardMicEnabled) return
        if (micTargetUsable()) {
            setMicVisual(expected)
            return
        }

        // Gboard can rebuild its header/toolbar immediately after the intercepted
        // mic gesture. The old SoftKeyView may vanish between optimistic feedback
        // and the authoritative service state. Reacquire the semantic voice key
        // after that transient rebuild and repaint the current state.
        MIC_REACQUIRE_DELAYS_MS.forEach { delayMs ->
            Handler(service.mainLooper).postDelayed({
                if (!gboardMicEnabled || voiceState != expected) return@postDelayed
                if (!micTargetUsable()) locateGboardMic(service)
                if (micTargetUsable()) setMicVisual(expected)
            }, delayMs)
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
                when (voiceState) {
                    VoiceState.IDLE -> {
                        log("$TAG Gboard mic gesture UP -> in-process recording start")
                        startHookRecording(service = currentIme?.get() ?: return)
                    }
                    VoiceState.RECORDING -> {
                        log("$TAG Gboard mic gesture UP -> in-process stream commit")
                        stopHookRecordingAndTranscribe(currentIme?.get() ?: return)
                    }
                    VoiceState.PROCESSING -> {
                        target?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        log("$TAG Gboard mic gesture UP while processing -> in-process cancel")
                        cancelHookVoice("cancelled from Gboard mic")
                        applyVoiceStateVisual(currentIme?.get() ?: return, VoiceState.IDLE)
                    }
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

    private data class HookClients(
        val wispr: WisprClient,
        val streaming: WisprStreamingClient,
        val legacyOnly: Boolean,
        val preservePreTapAudio: Boolean,
        val audioDrainTimeoutMs: Int,
    )

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
            // Hook-side LSPosed remote prefs are read-only. Keep any refreshed
            // token in this Gboard process; the companion app remains persistent owner.
            refreshedInMemory = session
        }

        private fun remoteSession(): WisprSession? {
            val access = prefs.getString(REMOTE_KEY_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
            return WisprSession(
                email = prefs.getString(REMOTE_KEY_EMAIL, "wispr-user") ?: "wispr-user",
                accessToken = access,
                refreshToken = prefs.getString(REMOTE_KEY_REFRESH, null)?.takeIf { it.isNotBlank() },
                expiresAt = prefs.getLong(REMOTE_KEY_EXPIRES, 0L).takeIf { it > 0L }
                    ?: AuthStore.jwtExpiresAt(access),
            )
        }
    }

    private fun remoteHookPreferences(): SharedPreferences? = runCatching {
        getRemotePreferences(REMOTE_AUTH_GROUP)
    }.onFailure {
        log("$TAG could not open LSPosed remote preferences: ${it.message}", it)
    }.getOrNull()

    private fun prepareHookConfig() {
        val prefs = remoteHookPreferences() ?: return
        gboardMicEnabled = prefs.getBoolean(REMOTE_KEY_GBOARD_MIC_ENABLED, true)
        log("$TAG remote config loaded gboardMicEnabled=$gboardMicEnabled")
    }

    private fun createHookClients(): HookClients {
        val prefs = remoteHookPreferences()
            ?: error("LSPosed remote settings are unavailable; open betterFlow once")
        val store = HookSessionStore(prefs)
        check(store.load() != null) { "Wispr session is not synced; open betterFlow once" }
        val wispr = WisprClient(store)
        val remoteKey = prefs.getString(REMOTE_KEY_STREAMING_API_KEY, null)?.trim().orEmpty()
        val apiKey = remoteKey.ifBlank { BuildConfig.WISPR_BASETEN_API_KEY.trim() }
        return HookClients(
            wispr = wispr,
            streaming = WisprStreamingClient(wispr) { apiKey },
            legacyOnly = prefs.getBoolean(REMOTE_KEY_LEGACY_TRANSCRIPTION, false),
            preservePreTapAudio = prefs.getBoolean(REMOTE_KEY_PRESERVE_PRE_TAP_AUDIO, true),
            audioDrainTimeoutMs = prefs.getInt(
                REMOTE_KEY_AUDIO_DRAIN_TIMEOUT_MS,
                Prefs.DEFAULT_AUDIO_DRAIN_TIMEOUT_MS,
            ).coerceIn(Prefs.MIN_AUDIO_DRAIN_TIMEOUT_MS, Prefs.MAX_AUDIO_DRAIN_TIMEOUT_MS),
        )
    }

    private fun startHookRecording(service: InputMethodService) {
        if (hookOwnsVoiceSession || hookRecorder.isRecording()) return
        val clients = runCatching(::createHookClients).getOrElse { t ->
            log("$TAG cannot start in-process voice: ${t.message}", t)
            android.widget.Toast.makeText(service, "betterFlow: ${t.message}", android.widget.Toast.LENGTH_LONG).show()
            voiceState = VoiceState.IDLE
            applyVoiceStateVisual(service, voiceState)
            return
        }

        val generation = ++hookOperationGeneration
        hookOwnsVoiceSession = true
        hookClients = clients
        hookStreamSession = null
        hookStreamFailure = null
        hookStreamReady = CountDownLatch(if (clients.legacyOnly) 0 else 1)
        synchronized(hookStreamLock) { hookQueuedAudio.clear() }
        voiceState = VoiceState.RECORDING
        applyVoiceStateVisual(service, voiceState)

        try {
            if (clients.legacyOnly) {
                hookRecorder.start()
                log("$TAG in-process legacy recording started generation=$generation")
                return
            }

            hookRecorder.start(onPcmChunk = { chunk ->
                if (hookOperationGeneration != generation || !hookOwnsVoiceSession) return@start
                synchronized(hookStreamLock) {
                    val session = hookStreamSession
                    if (session != null) {
                        session.sendAudio(chunk)
                    } else {
                        hookQueuedAudio.addLast(chunk)
                    }
                }
            })
            log("$TAG in-process recording started; opening Wispr stream generation=$generation")
            Thread({
                var opened: WisprStreamingClient.Session? = null
                try {
                    opened = runBlocking {
                        withTimeout(HOOK_STREAM_OPEN_TIMEOUT_MS) {
                            clients.streaming.open(onPartial = { partial ->
                                log("$TAG in-process Wispr partial chars=${partial.length}")
                            })
                        }
                    }
                    synchronized(hookStreamLock) {
                        if (hookOperationGeneration != generation || !hookOwnsVoiceSession) {
                            opened.cancel("stale Gboard voice operation")
                            return@synchronized
                        }
                        hookStreamSession = opened
                        while (hookQueuedAudio.isNotEmpty()) {
                            opened.sendAudio(hookQueuedAudio.removeFirst())
                        }
                    }
                    log("$TAG in-process Wispr stream ready generation=$generation")
                } catch (t: Throwable) {
                    if (hookOperationGeneration == generation) {
                        hookStreamFailure = t
                        log("$TAG in-process Wispr stream open failed: ${t.message}", t)
                    } else {
                        opened?.cancel("stale Gboard voice operation")
                    }
                } finally {
                    hookStreamReady.countDown()
                }
            }, "betterflow-gboard-stream-open").start()
        } catch (t: Throwable) {
            log("$TAG in-process recording start failed: ${t.message}", t)
            cancelHookVoice("recording start failed")
            android.widget.Toast.makeText(service, "betterFlow mic failed: ${t.message}", android.widget.Toast.LENGTH_LONG).show()
            applyVoiceStateVisual(service, VoiceState.IDLE)
        }
    }

    private fun stopHookRecordingAndTranscribe(service: InputMethodService) {
        if (!hookOwnsVoiceSession || voiceState != VoiceState.RECORDING) return
        val cutoffNanos = System.nanoTime()
        val generation = hookOperationGeneration
        val clients = hookClients ?: run {
            cancelHookVoice("missing hook clients")
            return
        }
        voiceState = VoiceState.PROCESSING
        applyVoiceStateVisual(service, voiceState)

        Thread({
            var text: String? = null
            var error: Throwable? = null
            try {
                val pcm = hookRecorder.stopAndGetPcm(
                    preservePreTapTail = clients.preservePreTapAudio,
                    drainTimeoutMs = clients.audioDrainTimeoutMs,
                    cutoffNanos = cutoffNanos,
                )
                check(pcm.isNotEmpty()) { "no microphone audio captured" }
                text = if (clients.legacyOnly) {
                    runBlocking { clients.wispr.transcribeLegacyPcm(pcm) }.trim()
                } else {
                    runCatching { transcribeHookStream(generation) }
                        .getOrElse { streamError ->
                            log("$TAG in-process streaming failed; using legacy fallback: ${streamError.message}", streamError)
                            runBlocking { clients.wispr.transcribeLegacyPcm(pcm) }
                        }
                        .trim()
                }
                check(text.isNotBlank()) { "Wispr returned empty text" }
            } catch (t: Throwable) {
                error = t
            }
            Handler(service.mainLooper).post {
                finishHookVoice(service, generation, text, error)
            }
        }, "betterflow-gboard-transcribe").start()
    }

    private fun transcribeHookStream(generation: Long): String {
        if (!hookStreamReady.await(HOOK_STREAM_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Wispr stream open timed out")
        }
        if (hookOperationGeneration != generation || !hookOwnsVoiceSession) {
            throw IllegalStateException("Gboard voice operation was cancelled")
        }
        hookStreamFailure?.let { throw it }
        val session = synchronized(hookStreamLock) {
            val current = hookStreamSession ?: throw IllegalStateException("Wispr stream did not open")
            while (hookQueuedAudio.isNotEmpty()) current.sendAudio(hookQueuedAudio.removeFirst())
            current.commit()
            current
        }
        val result = runBlocking {
            withTimeout(HOOK_STREAM_RESULT_TIMEOUT_MS) { session.awaitResult() }
        }
        return result.text
    }

    private fun finishHookVoice(
        service: InputMethodService,
        generation: Long,
        text: String?,
        error: Throwable?,
    ) {
        if (hookOperationGeneration != generation) return
        if (error != null) {
            log("$TAG in-process transcription failed: ${error.message}", error)
            android.widget.Toast.makeText(service, "betterFlow failed: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
        } else if (!text.isNullOrBlank()) {
            val connection = service.currentInputConnection
            val ok = runCatching { connection != null && connection.commitText(text, 1) }.getOrDefault(false)
            if (ok) {
                log("$TAG in-process transcription committed through Gboard InputConnection (${text.length} chars)")
            } else {
                log("$TAG transcription ready but Gboard InputConnection is unavailable")
                android.widget.Toast.makeText(service, "betterFlow: text ready but input field was lost", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        clearHookVoiceState(cancelSession = false)
        voiceState = VoiceState.IDLE
        applyVoiceStateVisual(service, voiceState)
    }

    private fun cancelHookVoice(reason: String) {
        ++hookOperationGeneration
        if (hookRecorder.isRecording()) runCatching { hookRecorder.stopAndDiscard() }
        hookClients?.wispr?.cancelActiveTranscription()
        clearHookVoiceState(cancelSession = true, reason = reason)
        voiceState = VoiceState.IDLE
        hookOwnsVoiceSession = false
        currentIme?.get()?.let { applyVoiceStateVisual(it, voiceState) }
        log("$TAG in-process voice cancelled: $reason")
    }

    private fun clearHookVoiceState(cancelSession: Boolean, reason: String = "operation complete") {
        val session = synchronized(hookStreamLock) {
            val current = hookStreamSession
            hookStreamSession = null
            hookQueuedAudio.clear()
            current
        }
        if (cancelSession) session?.cancel(reason)
        hookStreamFailure = null
        hookClients = null
        hookOwnsVoiceSession = false
        hookStreamReady.countDown()
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
                    runCatching {
                        activeModule?.prepareHookConfig()
                        activeModule?.locateGboardMic(service)
                    }.onFailure { activeModule?.log("$TAG Gboard mic discovery failed: ${it.message}", it) }
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
        private const val REMOTE_AUTH_GROUP = "betterflow_auth"
        private const val REMOTE_KEY_EMAIL = "email"
        private const val REMOTE_KEY_ACCESS = "access_token"
        private const val REMOTE_KEY_REFRESH = "refresh_token"
        private const val REMOTE_KEY_EXPIRES = "expires_at"
        private const val REMOTE_KEY_STREAMING_API_KEY = "streaming_api_key"
        private const val REMOTE_KEY_GBOARD_MIC_ENABLED = "gboard_mic_enabled"
        private const val REMOTE_KEY_LEGACY_TRANSCRIPTION = "legacy_transcription"
        private const val REMOTE_KEY_PRESERVE_PRE_TAP_AUDIO = "preserve_pre_tap_audio"
        private const val REMOTE_KEY_AUDIO_DRAIN_TIMEOUT_MS = "audio_drain_timeout_ms"
        private const val HOOK_STREAM_OPEN_TIMEOUT_MS = 20_000L
        private const val HOOK_STREAM_RESULT_TIMEOUT_MS = 30_000L
        private const val COMMIT_DEDUPE_TTL_MS = 10_000L
        private val MIC_REACQUIRE_DELAYS_MS = longArrayOf(0L, 60L, 140L, 280L, 520L, 900L)
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
