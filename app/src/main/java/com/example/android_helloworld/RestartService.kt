package com.example.android_helloworld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class RestartService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, buildNotification())
        Log.i("RestartService", "Can draw overlays: ${android.provider.Settings.canDrawOverlays(this)}")
        Log.i("RestartService", "Restarting MainActivity via foreground service.")
        val launch = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(launch)

        // Delay stopSelf to give the activity time to launch
        android.os.Handler(mainLooper).postDelayed({
            stopSelf()
        }, 3000)

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "restart_channel",
            "App Restart",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, "restart_channel")
            .setContentTitle("Restarting server...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}