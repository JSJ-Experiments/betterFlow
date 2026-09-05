package com.jadenjsj.betterflow

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorderController()
    private lateinit var wispr: WisprClient
    private lateinit var injector: InputInjector
    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var state = BubbleState.IDLE

    override fun onCreate() {
        super.onCreate()
        wispr = WisprClient(applicationContext)
        injector = InputInjector(applicationContext)
        windowManager = getSystemService(WindowManager::class.java)
        ensureNotificationChannel()
        updateForeground(BubbleState.IDLE)
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideBubble()
            ACTION_SHOW -> showBubble()
            ACTION_TOGGLE -> toggleRecording()
            ACTION_STOP -> stopSelf()
            else -> showBubble()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (recorder.isRecording()) runCatching { recorder.stopAndGetWav() }
        hideBubble()
        scope.cancel()
        super.onDestroy()
    }

    private fun showBubble() {
        if (bubble != null) return
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                    p.x = downX + dx
                    p.y = downY + dy
                    runCatching { windowManager.updateViewLayout(image, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Prefs.setBubblePosition(this, p.x, p.y)
                    if (!dragged) toggleRecording()
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
            Toast.makeText(this, "betterFlow cannot draw the bubble: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        params = null
    }

    private fun toggleRecording() {
        if (state == BubbleState.PROCESSING) return
        if (recorder.isRecording()) stopAndTranscribe() else startRecording()
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is missing; open betterFlow settings.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        try {
            recorder.start()
            updateState(BubbleState.RECORDING)
        } catch (t: Throwable) {
            Toast.makeText(this, "Microphone start failed: ${t.message}", Toast.LENGTH_LONG).show()
            updateState(BubbleState.IDLE)
        }
    }

    private fun stopAndTranscribe() {
        val wav = recorder.stopAndGetWav()
        if (wav.size <= 44) {
            updateState(BubbleState.IDLE)
            return
        }
        updateState(BubbleState.PROCESSING)
        scope.launch {
            try {
                val text = wispr.transcribe(wav)
                val result = injector.inject(text)
                val message = if (result.success) {
                    "Inserted via ${result.backend.wireName}"
                } else {
                    "Could not insert automatically; transcript is in the clipboard if fallback ran"
                }
                Toast.makeText(this@OverlayService, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(this@OverlayService, "betterFlow failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                updateState(BubbleState.IDLE)
            }
        }
    }

    private fun updateState(next: BubbleState) {
        state = next
        updateBubbleVisual(next)
        updateForeground(next)
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("betterFlow")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class BubbleState { IDLE, RECORDING, PROCESSING }

    companion object {
        const val ACTION_SHOW = "com.jadenjsj.betterflow.action.SHOW"
        const val ACTION_HIDE = "com.jadenjsj.betterflow.action.HIDE"
        const val ACTION_TOGGLE = "com.jadenjsj.betterflow.action.TOGGLE"
        const val ACTION_STOP = "com.jadenjsj.betterflow.action.STOP"
        private const val CHANNEL_ID = "betterflow-overlay"
        private const val NOTIFICATION_ID = 1206
    }
}
