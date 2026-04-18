package com.example.android_helloworld

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.room.Room
import com.example.android_helloworld.db.AppDatabase
import com.example.android_helloworld.db.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import com.example.android_helloworld.helpers.getIpAddress

class MainActivity : ComponentActivity() {

    private var server: testServer? = null

    // Lazily initialize the database instance.
    private val db by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "hello-server-db"
        )
            // This will delete and recreate the database on schema changes.
            // It's simple for development but not for production apps with real user data.
            .fallbackToDestructiveMigration()
            .build()
    }

    // Initialize the ViewModel using the factory that provides the Dao.
    private val viewModel: RecognitionViewModel by viewModels {
        RecognitionViewModelFactory(db.userDao())
    }

    private var serviceAnnouncer: ServiceAnnouncer? = null // edge service announcer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake, for stress testing
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start the server in a background coroutine.
        startServer()

        // Set the content of the activity to be our new history screen.
        setContent {
            // The RecognitionHistoryScreen composable will now be the main UI.
            // It observes the ViewModel for data changes.
            RecognitionHistoryScreen(viewModel = viewModel)
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

    override fun onDestroy() {
        super.onDestroy()
        // Stop the server when the activity is destroyed to free up the port.
        CoroutineScope(Dispatchers.IO).launch {
            server?.stop()
            Log.i("MainActivity", "Server stopped.")
        }
        serviceAnnouncer?.unregisterService()
        Log.i("MainActivity", "Service announcer stopped.")
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
