package com.example.android_helloworld

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.android_helloworld.db.AppDatabase
import com.example.android_helloworld.db.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var server: testServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Setup Database and Server ---
        // Get reference to database DAO
        val userDao = AppDatabase.getDatabase(this).userDao()

        // Insert sample user for testing
        CoroutineScope(Dispatchers.IO).launch {
            // Check if duplicate exists
            if (userDao.findByUsername("testuser") == null) {
                // Passwords are not hashed yet
                userDao.insert(User(username = "testuser", passwordHash = "password123"))
                Log.i("MainActivity", "Sample user 'testuser' inserted into database.")
            }
        }

        // start server
        try {
            server = testServer(applicationContext, userDao, 8080)
            server?.start()
            Log.i("MainActivity", "Server started on port 8080. Open a browser on the same WiFi network to access it.")
        } catch (e: IOException) {
            Log.e("MainActivity", "Server failed to start.", e)
        }

        // UI display
        setContent {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Web server is running on port 8080")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        Log.i("MainActivity", "Server stopped.")
    }
}
