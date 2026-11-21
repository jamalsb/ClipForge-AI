package com.clipforge.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class ClipWorkService : Service() {
    companion object {
        const val ACTION_START = "com.clipforge.ai.action.START_CLIP_WORK"
        private const val CHANNEL_ID = "clip_work"
        private const val NOTIFICATION_ID = 369
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ClipForge processing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notification = builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("ClipForge AI is working")
                .setContentText("Your clips are being processed. You can use other apps.")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
