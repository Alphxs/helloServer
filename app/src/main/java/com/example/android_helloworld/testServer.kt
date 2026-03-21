package com.example.android_helloworld

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import com.example.android_helloworld.db.UserDao
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

// Data class for sending predictions back as JSON
data class Prediction(val label: String, val score: Float)

class testServer(
    private val context: Context,
    private val userDao: UserDao,
    port: Int
) : NanoHTTPD(port) {

    @Volatile private var cachedBatteryJson: String? = null
    @Volatile private var lastBatteryFetchTime: Long = 0
    private val batteryCacheDurationMs = 1000 // Cache for 1 second

    private val activeTokens = ConcurrentHashMap.newKeySet<String>()
    private val gson = Gson()

    // --- Concurrency Management ---
    @Volatile
    private var maxConcurrentThreads = Semaphore(5, true)

    /**
     * Start of timestamp logs to CSV
     */
    private val csvOutputFile by lazy {
        File(context.getExternalFilesDir(null), "recognition_metrics.csv")
    }
    private val csvLock = Any()

    private fun getDetailedTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    }

    private fun logToCsv(
        clientId: String,
        received: String,
        start: String,
        end: String,
        sent: String
    ) {
        synchronized(csvLock) {
            try {
                val fileExists = csvOutputFile.exists()
                FileWriter(csvOutputFile, true).use { writer ->
                    if (!fileExists) {
                        writer.append("ID,Request_Received,Recognition_Start,Recognition_End,Response_Sent\n")
                    }
                    writer.append("$clientId,$received,$start,$end,$sent\n")
                }
            } catch (e: Exception) {
                Log.e("testServer", "CSV Write Error: ${e.message}")
            }
        }
    }

    /**
     * The main entry point for all HTTP requests.
     */
    override fun serve(session: IHTTPSession): Response {
        // Use runBlocking on a background thread to safely wait for our suspend functions.
        return runBlocking(Dispatchers.IO) {
            try {
                // Handle CORS preflight OPTIONS requests first.
                if (session.method == Method.OPTIONS) {
                    addCorsHeaders(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, null))
                } else {
                    handleRequest(session)
                }
            } catch (e: Exception) {
                Log.e("TestServer", "Unhandled error in serve: ${session.uri}", e)
                addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error: ${e.message}"))
            }
        }
    }

    /**
     * Routes incoming requests to the correct handler function.
     */
    private suspend fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.i("TestServer", "Handling: $method $uri")

        return when {
            method == Method.GET && uri == "/" -> serveHtmlPage()
            method == Method.POST && uri == "/login" -> handleSecureLogin(session)
            method == Method.POST && uri == "/recognize" -> handleRecognition(session)
            method == Method.POST && uri == "/set-concurrency" -> handleConcurrencyChange(session)
            method == Method.GET && uri == "/battery" -> handleBatteryRequest()
            method == Method.GET && uri == "/status" -> handleBatteryRequest()
            method == Method.GET && uri == "/download" -> handleFileDownload(session)
            else -> {
                Log.w("TestServer", "Unhandled request for URI: $uri")
                addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Error: The requested resource was not found."))
            }
        }
    }

    private fun serveHtmlPage(): Response {
        return try {
            val html = context.assets.open("login.html").use { it.bufferedReader().readText() }
            addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/html", html))
        } catch (e: IOException) {
            Log.e("TestServer", "Could not serve login.html", e)
            addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Could not load main page."))
        }
    }

    private suspend fun handleSecureLogin(session: IHTTPSession): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val params = session.parameters
            val username = params["username"]?.firstOrNull()
            val password = params["password"]?.firstOrNull()

            if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Username and password are required."))
            } else {
                val user = userDao.findByUsername(username)
                if (user != null && password == user.passwordHash) {
                    val token = generateNewToken()
                    activeTokens.add(token)
                    Log.i("TestServer", "Login successful for '$username'. Issued token.")
                    val jsonResponse = "{\"token\": \"$token\"}"
                    addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse))
                } else {
                    Log.w("TestServer", "Login failed for user '$username'.")
                    addCorsHeaders(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Invalid username or password."))
                }
            }
        } catch (e: Exception) {
            Log.e("TestServer", "Error during login", e)
            addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "An internal error occurred during login."))
        }
    }

//    private suspend fun handleSecureRecognition(session: IHTTPSession): Response {
//        if (!isTokenValid(session)) {
//            return addCorsHeaders(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized: Missing or invalid token."))
//        }
//        Log.i("TestServer", "Token valid, proceeding to queue recognition task.")
//        return handleRecognition(session)
//    }

    private suspend fun handleRecognition(session: IHTTPSession): Response {
        val requestReceivedTime = getDetailedTimestamp()
        val clientId = session.headers["x-client-request-id"] ?: "unknown"
        val taskId = java.util.UUID.randomUUID().toString()
        var permanentFile: File? = null

        try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val tempImageFilePath = files["imageFile"]
            if (tempImageFilePath.isNullOrEmpty()) {
                return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No image"))
            }

            val tempFile = File(tempImageFilePath)
            val permanentImageDir = File(context.filesDir, "images").apply { mkdirs() }
            permanentFile = File(permanentImageDir, "img_${taskId}.jpg")
            tempFile.copyTo(permanentFile, overwrite = true)

            maxConcurrentThreads.acquire()
            try {
                val recognitionStartTime = getDetailedTimestamp()

                // Recognition logic
                val recognizer = ImageRecognizer(context, userDao)
                val result = recognizer.processImage(permanentFile)
                val recognitionEndTime = getDetailedTimestamp()

                val responseSentTime = getDetailedTimestamp()
                logToCsv(clientId, requestReceivedTime, recognitionStartTime, recognitionEndTime, responseSentTime)

                return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", result))
            } finally {
                maxConcurrentThreads.release()
            }

        } catch (e: Exception) {
            Log.e("TestServer", "Error", e)
            return addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message))
        } finally {
            // --- DELETE THE IMAGE TO AVOID STORAGE OVERLOAD ---
            permanentFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
        }
    }

    private fun handleConcurrencyChange(session: IHTTPSession): Response {
        try {
            val maxThreadsParam = session.parameters["maxThreads"]?.firstOrNull()
            val newLimit = maxThreadsParam?.toIntOrNull() ?: -1

            if (newLimit > 0) {
                synchronized(this) {
                    maxConcurrentThreads = Semaphore(newLimit, true)
                }
                Log.i("TestServer", "Concurrency limit updated to $newLimit")
                return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain", "Max threads set to $newLimit"))
            } else {
                return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Error: 'maxThreads' parameter must be a positive integer."))
            }
        } catch (e: Exception) {
            Log.e("TestServer", "Error updating concurrency", e)
            return addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}"))
        }
    }

    private fun handleFileDownload(session: IHTTPSession): Response {
        val params = session.parameters
        val filename = params["file"]?.firstOrNull()

        if (filename.isNullOrEmpty()) {
            return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Error: 'file' parameter is missing."))
        }

        try {
            // Prevent directory traversal
            if (filename.contains("/") || filename.contains("\\")) {
                Log.e("TestServer", "Security Alert: Path characters detected in filename: $filename")
                return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Error: Invalid filename."))
            }

            // Open an input stream from the assets folder.
            // This will throw an IOException if the file does not exist.
            val fileInputStream = context.assets.open(filename)
            // Use .available() to get the size of the stream, which works for compressed assets.
            val fileSize = fileInputStream.available().toLong()

            Log.i("TestServer", "Serving asset file for download: $filename")

            // Serve the file. NanoHTTPD will handle closing the stream.
            // "application/octet-stream" forces a download prompt.
            val response = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", fileInputStream, fileSize)

            // This header suggests a filename to the browser for the "Save As" dialog.
            response.addHeader("Content-Disposition", "attachment; filename=\"$filename\"")

            return addCorsHeaders(response)
        } catch (e: IOException) {
            Log.w("TestServer", "Asset file not found for download: $filename", e)
            return addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Error: Asset file not found."))
        }
    }

//    private fun handleSecureBatteryRequest(session: IHTTPSession): Response {
//        if (!isTokenValid(session)) {
//            return addCorsHeaders(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized: Missing or invalid token."))
//        }
//        return handleBatteryRequest()
//    }

    private fun handleBatteryRequest(): Response {
        val currentTime = System.currentTimeMillis()

        // Check if we have a valid, non-stale cache
        if (cachedBatteryJson != null && (currentTime - lastBatteryFetchTime) < batteryCacheDurationMs) {
            Log.i("TestServer", "Serving cached battery status.")
            return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", cachedBatteryJson))
        }

        // --- If cache is stale or empty, fetch new data ---
        Log.i("TestServer", "Fetching new battery status (cache miss or stale).")
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, intentFilter)

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level / scale.toFloat()) * 100 else -1.0f

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val chargingStatus = when {
            isCharging -> "Charging"
            status == BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            else -> "Not Charging"
        }

        // Get mAh using BatteryManager
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        
        val mahString = if (chargeCounter != Int.MIN_VALUE) {
            "%.3f mAh".format(chargeCounter / 1000.0)
        } else {
            "Not Supported"
        }

        val batteryData = mapOf(
            "level" to "%.1f%%".format(batteryPct),
            "status" to chargingStatus,
            "remaining_mah" to mahString
        )
        val jsonResponse = gson.toJson(batteryData)

        // --- Update the cache ---
        cachedBatteryJson = jsonResponse
        lastBatteryFetchTime = currentTime
        return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse))
    }

    private fun generateNewToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

//    private fun isTokenValid(session: IHTTPSession): Boolean {
//        val authHeader = session.headers["authorization"] ?: return false
//        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return false
//        val token = authHeader.substringAfter("Bearer ")
//        return activeTokens.contains(token)
//    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "origin, x-requested-with, content-type, accept, Authorization")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return response
    }
}
