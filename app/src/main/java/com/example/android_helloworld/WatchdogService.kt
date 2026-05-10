package com.example.android_helloworld


import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

class WatchdogService : Service() {

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MainActivity.ACTION_SHUTDOWN) {
            Log.i("WatchdogService", "Shutdown signal received. Stopping watchdog.")
            running = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) return START_STICKY

        running = true
        Log.i("WatchdogService", "Watchdog started in separate process.")

        Thread {
            while (running) {
                try {
                    Thread.sleep(3000)
                    if (!isMainProcessRunning()) {
                        Log.w("WatchdogService", "Main process not found — restarting app.")
                        restartMainApp() // <-- replaced startActivity() with this
                    }
                } catch (e: InterruptedException) {
                    Log.w("WatchdogService", "Watchdog thread interrupted.")
                    break
                } catch (e: Exception) {
                    Log.e("WatchdogService", "Watchdog error: ${e.message}", e)
                }
            }
        }.start()

        return START_STICKY
    }

    private fun isMainProcessRunning(): Boolean {
        return try {
            File("/proc").listFiles()
                ?.filter { it.name.all { c -> c.isDigit() } }
                ?.any { pidDir ->
                    val cmdline = File(pidDir, "cmdline").readText()
                        .trimEnd('\u0000')
                    cmdline == packageName
                } ?: false
        } catch (e: Exception) {
            Log.e("WatchdogService", "Error checking process: ${e.message}")
            true
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun restartMainApp() {
        Log.i("WatchdogService", "Restarting ServerService directly.")
        val intent = Intent(applicationContext, ServerService::class.java)
        startForegroundService(intent) // no activity launch needed!
        Thread.sleep(5000)
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}