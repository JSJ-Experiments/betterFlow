package com.jadenjsj.betterflow

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Event-driven recovery notice; never scheduled or polled. */
object HistoryNotifier {
    const val EXTRA_OPEN_HISTORY = "com.jadenjsj.betterflow.OPEN_HISTORY"
    private const val CHANNEL = "history_recovery"

    fun show(context: Context, id: String, detail: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Voice history recovery", NotificationManager.IMPORTANCE_DEFAULT,
            ))
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_HISTORY, true)
            val pending = PendingIntent.getActivity(context, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(id.hashCode(), Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("betterFlow capture needs attention")
                .setContentText(detail)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build())
        }
    }

    fun clear(context: Context, id: String) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(id.hashCode()) }
    }
}
