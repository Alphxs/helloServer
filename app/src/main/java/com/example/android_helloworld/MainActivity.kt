package com.example.android_helloworld

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.room.Room
import com.example.android_helloworld.db.AppDatabase
import com.example.android_helloworld.db.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import com.example.android_helloworld.helpers.getIpAddress
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    private var server: testServer? = null

    private val db by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "hello-server-db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    private val viewModel: RecognitionViewModel by viewModels {
        RecognitionViewModelFactory(db.userDao())
    }

    private var serviceAnnouncer: ServiceAnnouncer? = null // edge service announcer

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupCrashHandler()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            0
        )

        // Start server
        startService(Intent(this, ServerService::class.java))

        // Start watchdog
        startService(Intent(this, WatchdogService::class.java))

        setContent {
            RecognitionHistoryScreen(viewModel = viewModel)
        }
    }

    /**
     * Sets a global handler to restart the app via AlarmManager when a crash occurs.
     * This ensures the server stays up even if it hits a fatal error like OOM.
     */
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("APP_CRASH", "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)

            // Prepare intent to restart the main activity
            val intent = Intent(applicationContext, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, 
                0, 
                intent, 
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            // Schedule the restart to happen in 1 second
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent)

            // Log and allow the process to terminate so the alarm can trigger the restart
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                exitProcess(2)
            }
        }
    }

    private fun startServer() {
        // Use a dedicated CoroutineScope for the server's lifecycle.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val edgeIpAddress = getIpAddress(applicationContext)

                // Ensure the directory for storing uploaded images exists.
                val imageDir = File(filesDir, "images")
                if (!imageDir.exists()) {
                    imageDir.mkdirs()
                }

                // Get a reference to the DAO.
                val userDao = db.userDao()

                // Insert the sample user for testing if it doesn't already exist.
                if (userDao.findByUsername("testuser") == null) {
                    userDao.insert(User(username = "testuser", passwordHash = "password123"))
                    Log.i("MainActivity", "Sample user 'testuser' inserted into database.")
                }

                // Initialize and start the NanoHTTPD server.
                server = testServer(applicationContext, userDao, 8080)
                server?.start()
                Log.i("MainActivity", "Server started successfully on port 8080.")

                serviceAnnouncer = ServiceAnnouncer(applicationContext)
                serviceAnnouncer?.registerService(8080, edgeIpAddress)
                Log.i("MainActivity", "Service announcer initialized and service registration requested.")

            } catch (e: IOException) {
                Log.e("MainActivity", "Server failed to start.", e)
            } catch (e: Exception) {
                Log.e("MainActivity", "An unexpected error occurred during server startup.", e)
            }
        }
    }
    companion object {
        const val ACTION_SHUTDOWN = "com.example.android_helloworld.ACTION_SHUTDOWN"
    }

    override fun onDestroy() {
        super.onDestroy()
        val stopWatchdog = Intent(this, WatchdogService::class.java)
        stopWatchdog.action = MainActivity.ACTION_SHUTDOWN
        startService(stopWatchdog)
        serviceAnnouncer?.unregisterService()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.e("MEMORY_MONITOR", "CRITICAL: The system is about to kill this app to reclaim RAM!")
            }
            TRIM_MEMORY_RUNNING_LOW -> {
                Log.w("MEMORY_MONITOR", "WARNING: The system is running low on RAM. Consider reducing concurrency.")
            }
            TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.i("MEMORY_MONITOR", "INFO: System memory is becoming tight.")
            }
        }
    }
}
