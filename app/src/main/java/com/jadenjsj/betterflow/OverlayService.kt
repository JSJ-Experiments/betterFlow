package com.jadenjsj.betterflow

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorderController()
    private lateinit var wispr: WisprClient
    private lateinit var streaming: WisprStreamingClient
    private lateinit var injector: InputInjector
    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var state = BubbleState.IDLE
    private var operationGeneration = 0L
    private var legacyForCurrentRecording = false
    private var currentPcm = ByteArray(0)
    private var processingJob: Job? = null
    private var streamWorker: Job? = null
    @Volatile private var activeStream: WisprStreamingClient.Session? = null
    @Volatile private var streamingFailure: Throwable? = null
    @Volatile private var streamStopRequested = false
    @Volatile private var streamCaptureFinalized = false
    private var streamQueue: ArrayBlockingQueue<ByteArray>? = null
    private val streamQueueEnabled = AtomicBoolean(false)
    private val streamQueuedBytes = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        wispr = WisprClient(applicationContext)
        streaming = WisprStreamingClient(applicationContext)
        injector = InputInjector(applicationContext)
        windowManager = getSystemService(WindowManager::class.java)
        ensureNotificationChannel()
        VoiceRuntimeState.wireName = BubbleState.IDLE.wireName
        updateForeground(BubbleState.IDLE)
        broadcastVoiceState(BubbleState.IDLE)
        if (Prefs.bubbleVisible(this)) showBubble(persist = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideBubble(persist = true)
            ACTION_SHOW -> showBubble(persist = true)
            ACTION_TOGGLE -> toggleRecording()
            ACTION_STOP -> stopSelf()
            else -> if (Prefs.bubbleVisible(this)) showBubble(persist = false) else hideBubble(persist = false)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        operationGeneration++
        streamQueueEnabled.set(false)
        streamStopRequested = true
        activeStream?.cancel("betterFlow service destroyed")
        streamWorker?.cancel()
        processingJob?.cancel()
        wispr.cancelActiveTranscription()
        if (recorder.isRecording()) runCatching { recorder.stopAndDiscard() }
        state = BubbleState.IDLE
        VoiceRuntimeState.wireName = BubbleState.IDLE.wireName
        broadcastVoiceState(BubbleState.IDLE)
        hideBubble(persist = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun showBubble(persist: Boolean = true) {
        if (persist) Prefs.setBubbleVisible(this, true)
        if (bubble != null) {
            updateForeground(state)
            return
        }
        val size = dp(58)
        val image = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            elevation = dp(8).toFloat()
        }
        val (savedX, savedY) = Prefs.bubblePosition(this)
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        params = lp
        bubble = image
        updateBubbleVisual(BubbleState.IDLE)

        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragged = false
        image.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = p.x
                    downY = p.y
                    dragged = false
                    image.scaleX = 0.84f
                    image.scaleY = 0.84f
                    image.alpha = 0.72f
                    image.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        dragged = true
                        image.scaleX = 1f
                        image.scaleY = 1f
                        updateBubbleVisual(state)
                    }
                    p.x = downX + dx
                    p.y = downY + dy
                    runCatching { windowManager.updateViewLayout(image, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Prefs.setBubblePosition(this, p.x, p.y)
                    image.scaleX = 1f
                    image.scaleY = 1f
                    if (!dragged) toggleRecording() else updateBubbleVisual(state)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    image.scaleX = 1f
                    image.scaleY = 1f
                    updateBubbleVisual(state)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(image, lp)
        } catch (t: Throwable) {
            bubble = null
            params = null
            if (persist) Prefs.setBubbleVisible(this, false)
            Toast.makeText(this, "betterFlow cannot draw the bubble: ${t.message}", Toast.LENGTH_LONG).show()
        }
        updateForeground(state)
    }

    private fun hideBubble(persist: Boolean = true) {
        if (persist) Prefs.setBubbleVisible(this, false)
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        params = null
        updateForeground(state)
    }

    private fun toggleRecording() {
        when (state) {
            BubbleState.IDLE -> startRecording()
            BubbleState.RECORDING -> stopRecording()
            BubbleState.PROCESSING -> cancelProcessing()
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is missing; open betterFlow settings.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        operationGeneration++
        val generation = operationGeneration
        legacyForCurrentRecording = Prefs.legacyTranscription(this)
        currentPcm = ByteArray(0)
        streamingFailure = null
        streamStopRequested = false
        streamCaptureFinalized = false
        streamQueueEnabled.set(false)
        streamQueuedBytes.set(0L)
        streamQueue = null
        processingJob?.cancel()
        processingJob = null
        updateState(BubbleState.RECORDING)

        try {
            if (legacyForCurrentRecording) {
                recorder.start()
                Log.i(TAG, "recording started with legacy whole-file transcription")
            } else {
                startStreamingRecording(generation)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "microphone start failed", t)
            cancelStreamOnly("microphone start failed")
            Toast.makeText(this, "Microphone start failed: ${t.message}", Toast.LENGTH_LONG).show()
            updateState(BubbleState.IDLE)
        }
    }

    private fun startStreamingRecording(generation: Long) {
        val queue = ArrayBlockingQueue<ByteArray>(STREAM_QUEUE_CAPACITY)
        streamQueue = queue
        streamQueueEnabled.set(true)
        streamWorker = scope.launch(Dispatchers.IO) {
            var session: WisprStreamingClient.Session? = null
            try {
                session = withTimeoutOrNull(STREAM_OPEN_TIMEOUT_MS) {
                    streaming.open(
                        onPartial = { partial ->
                            Log.d(TAG, "Wispr partial: ${partial.take(160)}")
                        },
                    )
                } ?: throw IllegalStateException("Wispr gRPC startup timed out")
                if (generation != operationGeneration) {
                    session.cancel("stale recording")
                    return@launch
                }
                activeStream = session
                Log.i(TAG, "Wispr gRPC stream connected; feeding 100 ms PCM chunks")

                while (true) {
                    val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                    if (chunk != null) session.sendAudio(chunk)
                    if (streamStopRequested && streamCaptureFinalized && queue.isEmpty()) {
                        // If capture stopped between AudioRecord reads, its final sub-100ms
                        // PCM tail may not have entered the live queue. Send exactly the
                        // unqueued suffix before commit so the wire audio remains complete.
                        var offset = streamQueuedBytes.get().coerceAtMost(currentPcm.size.toLong()).toInt()
                        while (offset < currentPcm.size) {
                            val end = minOf(currentPcm.size, offset + AudioRecorderController.STREAM_CHUNK_BYTES)
                            session.sendAudio(currentPcm.copyOfRange(offset, end))
                            offset = end
                        }
                        break
                    }
                }
                session.commit()
                Log.i(TAG, "Wispr gRPC commit sent")
                val result = withTimeoutOrNull(STREAM_RESULT_TIMEOUT_MS) {
                    session.awaitResult()
                } ?: throw IllegalStateException("Wispr gRPC timed out waiting for final result")
                withContext(Dispatchers.Main.immediate) {
                    if (generation == operationGeneration && state == BubbleState.PROCESSING) {
                        handleStreamingResult(result, generation)
                    }
                }
            } catch (cancelled: CancellationException) {
                session?.cancel("stream coroutine cancelled")
                throw cancelled
            } catch (t: Throwable) {
                session?.cancel("stream failed")
                streamingFailure = t
                streamQueueEnabled.set(false)
                Log.w(TAG, "Wispr streaming failed: ${t.message}", t)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == operationGeneration && state == BubbleState.PROCESSING) {
                        startLegacyFallback(currentPcm, generation, "streaming failed: ${t.message}")
                    }
                }
            } finally {
                if (activeStream === session) activeStream = null
            }
        }

        recorder.start(
            onPcmChunk = { chunk ->
                if (!streamQueueEnabled.get()) return@start
                try {
                    var queued = false
                    while (streamQueueEnabled.get() && !queued) {
                        queued = queue.offer(chunk, 100, TimeUnit.MILLISECONDS)
                    }
                    if (queued) streamQueuedBytes.addAndGet(chunk.size.toLong())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            chunkBytes = AudioRecorderController.STREAM_CHUNK_BYTES,
        )
        Log.i(TAG, "recording started: 16 kHz mono PCM16, 100 ms chunks")
    }

    private fun stopRecording() {
        if (state != BubbleState.RECORDING) return
        updateState(BubbleState.PROCESSING)
        streamQueueEnabled.set(false)
        streamStopRequested = true
        currentPcm = recorder.stopAndGetPcm()
        streamCaptureFinalized = true

        if (currentPcm.isEmpty()) {
            cancelStreamOnly("empty recording")
            updateState(BubbleState.IDLE)
            return
        }

        if (legacyForCurrentRecording) {
            startLegacyFallback(currentPcm, operationGeneration, "legacy mode")
            return
        }

        streamingFailure?.let { failure ->
            cancelStreamOnly("stream already failed")
            startLegacyFallback(currentPcm, operationGeneration, "streaming failed: ${failure.message}")
            return
        }
        // The stream worker drains all queued PCM, sends the separate commit frame,
        // waits for the final response, then calls handleStreamingResult().
    }

    private fun handleStreamingResult(result: WisprStreamingClient.Result, generation: Long) {
        if (generation != operationGeneration || state != BubbleState.PROCESSING) return
        val expectedSeconds = currentPcm.size.toDouble() /
            (AudioRecorderController.SAMPLE_RATE * AudioRecorderController.CHANNELS * AudioRecorderController.SAMPLE_WIDTH_BYTES)
        val receivedSeconds = result.audioDurationSeconds ?: result.audioReceivedSeconds?.toDouble()
        if (receivedSeconds != null) {
            val missingSeconds = expectedSeconds - receivedSeconds
            if (missingSeconds > max(0.75, expectedSeconds * 0.10)) {
                Log.w(TAG, "stream missed ${"%.2f".format(missingSeconds)}s; using legacy fallback")
                startLegacyFallback(
                    currentPcm,
                    generation,
                    "streaming appears to have missed ${"%.1f".format(missingSeconds)}s of audio",
                )
                return
            }
        }
        startTranscriptDelivery(result.text, generation, "streaming")
    }

    private fun startLegacyFallback(pcm: ByteArray, generation: Long, reason: String) {
        if (generation != operationGeneration || state != BubbleState.PROCESSING) return
        Log.i(TAG, "starting legacy HTTP fallback: $reason")
        processingJob?.cancel()
        processingJob = scope.launch {
            try {
                val transcript = wispr.transcribeLegacyPcm(pcm)
                if (generation != operationGeneration || state != BubbleState.PROCESSING) return@launch
                deliverTranscript(transcript, generation, "legacy_http")
            } catch (_: CancellationException) {
                // User cancellation is intentionally silent.
            } catch (t: Throwable) {
                if (generation != operationGeneration || state != BubbleState.PROCESSING) return@launch
                Log.e(TAG, "legacy transcription failed", t)
                Toast.makeText(this@OverlayService, "betterFlow failed: ${t.message}", Toast.LENGTH_LONG).show()
                finishOperation(generation)
            }
        }
    }

    private fun startTranscriptDelivery(text: String, generation: Long, source: String) {
        processingJob?.cancel()
        processingJob = scope.launch {
            deliverTranscript(text, generation, source)
        }
    }

    private suspend fun deliverTranscript(text: String, generation: Long, source: String) {
        if (generation != operationGeneration || state != BubbleState.PROCESSING) return
        try {
            val result = injector.inject(text)
            if (generation != operationGeneration || state != BubbleState.PROCESSING) return
            val message = if (result.success) {
                "Inserted via ${result.backend.wireName} ($source)"
            } else {
                "Could not insert automatically; transcript is in the clipboard if fallback ran"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (_: CancellationException) {
            return
        } catch (t: Throwable) {
            if (generation == operationGeneration && state == BubbleState.PROCESSING) {
                Toast.makeText(this, "betterFlow failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            if (generation == operationGeneration && state == BubbleState.PROCESSING) {
                finishOperation(generation)
            }
        }
    }

    private fun cancelProcessing() {
        if (state != BubbleState.PROCESSING) return
        operationGeneration++
        streamQueueEnabled.set(false)
        streamStopRequested = true
        streamCaptureFinalized = true
        activeStream?.cancel("cancelled by user while processing")
        activeStream = null
        streamWorker?.cancel()
        streamWorker = null
        processingJob?.cancel()
        processingJob = null
        val cancelledHttp = wispr.cancelActiveTranscription()
        Log.i(TAG, "legacy HTTP call cancelled=$cancelledHttp")
        streamQueue = null
        streamingFailure = null
        currentPcm = ByteArray(0)
        Log.i(TAG, "processing cancelled by user")
        updateState(BubbleState.IDLE)
    }

    private fun cancelStreamOnly(reason: String) {
        streamQueueEnabled.set(false)
        streamStopRequested = true
        streamCaptureFinalized = true
        activeStream?.cancel(reason)
        activeStream = null
        streamWorker?.cancel()
        streamWorker = null
        streamQueue = null
    }

    private fun finishOperation(generation: Long) {
        if (generation != operationGeneration) return
        cancelStreamOnly("operation complete")
        processingJob = null
        streamingFailure = null
        currentPcm = ByteArray(0)
        updateState(BubbleState.IDLE)
    }

    private fun updateState(next: BubbleState) {
        state = next
        VoiceRuntimeState.wireName = next.wireName
        updateBubbleVisual(next)
        updateForeground(next)
        broadcastVoiceState(next)
    }

    private fun broadcastVoiceState(next: BubbleState) {
        val flattened = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return
        val imePackage = ComponentName.unflattenFromString(flattened)?.packageName ?: return
        sendBroadcast(
            Intent(InputInjector.ACTION_VOICE_STATE)
                .setPackage(imePackage)
                .putExtra(InputInjector.EXTRA_VOICE_STATE, next.wireName),
        )
    }

    private fun updateBubbleVisual(next: BubbleState) {
        val color = when (next) {
            BubbleState.IDLE -> 0xFF526D82.toInt()
            BubbleState.RECORDING -> 0xFFD14D4D.toInt()
            BubbleState.PROCESSING -> 0xFFB17A30.toInt()
        }
        bubble?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        bubble?.alpha = if (next == BubbleState.PROCESSING) 0.8f else 1f
    }

    private fun updateForeground(next: BubbleState) {
        val notification = buildNotification(next)
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (next == BubbleState.RECORDING) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= 29 && next == BubbleState.RECORDING) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(next: BubbleState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (next) {
            BubbleState.IDLE -> getString(R.string.notification_idle)
            BubbleState.RECORDING -> getString(R.string.notification_recording)
            BubbleState.PROCESSING -> getString(R.string.notification_processing)
        }
        val bubbleVisible = bubble != null
        val bubbleAction = PendingIntent.getService(
            this,
            if (bubbleVisible) 2 else 1,
            Intent(this, OverlayService::class.java).setAction(if (bubbleVisible) ACTION_HIDE else ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("betterFlow")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_mic,
                    if (bubbleVisible) "Hide floating mic" else "Show floating mic",
                    bubbleAction,
                ).build(),
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class BubbleState(val wireName: String) { IDLE("idle"), RECORDING("recording"), PROCESSING("processing") }

    companion object {
        const val ACTION_SHOW = "com.jadenjsj.betterflow.action.SHOW"
        const val ACTION_HIDE = "com.jadenjsj.betterflow.action.HIDE"
        const val ACTION_TOGGLE = "com.jadenjsj.betterflow.action.TOGGLE"
        const val ACTION_STOP = "com.jadenjsj.betterflow.action.STOP"
        private const val CHANNEL_ID = "betterflow-overlay"
        private const val NOTIFICATION_ID = 1206
        private const val STREAM_QUEUE_CAPACITY = 128
        private const val STREAM_OPEN_TIMEOUT_MS = 20_000L
        private const val STREAM_RESULT_TIMEOUT_MS = 30_000L
        private const val TAG = "betterFlow/Voice"
    }
}
