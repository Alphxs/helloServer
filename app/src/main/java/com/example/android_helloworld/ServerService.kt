package com.example.android_helloworld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.room.Room
import com.example.android_helloworld.db.AppDatabase
import com.example.android_helloworld.db.User
import com.example.android_helloworld.helpers.getIpAddress
import com.example.android_helloworld.service_announcer.ServiceAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class ServerService : Service() {

    private var server: testServer? = null
    private var serviceAnnouncer: ServiceAnnouncer? = null

    @Volatile
    private var isStarting = false

    @Volatile
    private var isRunning = false

    private val db by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "hello-server-db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
            startForeground(2, buildNotification())
        }

        if (isRunning) {
            Log.i("ServerService", "Server already running, ignoring duplicate start.")
            return START_STICKY
        }

        if (isStarting) {
            Log.w("ServerService", "Server already starting, ignoring duplicate call.")
            return START_STICKY
        }
        isStarting = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                server?.stop()
                server = null
                Thread.sleep(2000)

                val edgeIpAddress = getIpAddress(applicationContext)
                val imageDir = File(filesDir, "images")
                if (!imageDir.exists()) imageDir.mkdirs()

                val userDao = db.userDao()
                if (userDao.findByUsername("testuser") == null) {
                    userDao.insert(User(username = "testuser", passwordHash = "password123"))
                }

                server = testServer(applicationContext, userDao, 8080)
                server?.start(0, false)
                isRunning = true
                Log.i("ServerService", "Server started successfully on port 8080.")

                serviceAnnouncer = ServiceAnnouncer(applicationContext)
                serviceAnnouncer?.registerService(8080, edgeIpAddress)
                Log.i("ServerService", "Service announcer initialized.")

            } catch (e: IOException) {
                Log.e("ServerService", "Server failed to start.", e)
            } catch (e: Exception) {
                Log.e("ServerService", "Unexpected error during server startup.", e)
            } finally {
                isStarting = false  // reset flag when done
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        isStarting = false
        CoroutineScope(Dispatchers.IO).launch {
            server?.stop()
            Log.i("ServerService", "Server stopped.")
        }
        serviceAnnouncer?.unregisterService()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "server_channel",
                "Server Status",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Log.i("ServerService", "Notification channel created.")
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "server_channel")
                .setContentTitle("Server Running")
                .setContentText("Listening on port 8080")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)        // can't be dismissed by swiping
                .setOnlyAlertOnce(true)  // no sound/vibration on update
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Server Running")
                .setContentText("Listening on port 8080")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}