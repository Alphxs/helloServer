package com.example.android_helloworld

import android.app.ActivityManager
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
    private var currentMaxThreads = 5
    
    @Volatile
    private var maxConcurrentThreads = Semaphore(currentMaxThreads, true)

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
        return runBlocking(Dispatchers.IO) {
            try {
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
            method == Method.GET && uri == "/get-max-threads" -> handleGetMaxThreads()
            method == Method.GET && uri == "/battery" || method == Method.GET && uri == "/status" -> handleBatteryRequest()
            method == Method.GET && uri == "/download" -> handleFileDownload(session)
            else -> {
                Log.w("TestServer", "Unhandled request for URI: $uri")
                addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Error: Not Found"))
            }
        }
    }

    private fun serveHtmlPage(): Response {
        return try {
            val html = context.assets.open("login.html").use { it.bufferedReader().readText() }
            addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/html", html))
        } catch (e: IOException) {
            addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error loading page."))
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

                // --- Capture System Memory BEFORE ---
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                val availableBefore = memInfo.availMem / (1024 * 1024)

                // Use .use to ensure the recognizer is closed and native memory is freed
                val result = ImageRecognizer(context, userDao).use { recognizer ->
                    recognizer.processImage(permanentFile)
                }

                // --- Capture System Memory AFTER ---
                activityManager.getMemoryInfo(memInfo)
                val availableAfter = memInfo.availMem / (1024 * 1024)

                // Calculate actual RAM impact (Native + JVM)
                val ramConsumed = availableBefore - availableAfter
                val recognitionEndTime = getDetailedTimestamp()
                val responseSentTime = getDetailedTimestamp()

                // --- INJECT METRICS INTO JSON ---
                val resultWithMetrics = result.trim().removeSuffix("}") +
                        """,
                "memory_metrics": {
                    "ram_used_mb": $ramConsumed,
                    "available_at_start_mb": $availableBefore,
                    "available_at_end_mb": $availableAfter
                },
                "timing_metrics": {
                    "start": "$recognitionStartTime",
                    "end": "$recognitionEndTime"
                }
                }""".trimIndent()

                logToCsv(clientId, requestReceivedTime, recognitionStartTime, recognitionEndTime, responseSentTime)

                return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", resultWithMetrics))

            } finally {
                maxConcurrentThreads.release()
            }

        } catch (e: Exception) {
            Log.e("TestServer", "Error during recognition", e)
            return addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message))
        } finally {
            permanentFile?.let { if (it.exists()) it.delete() }
        }
    }

    private fun handleConcurrencyChange(session: IHTTPSession): Response {
        try {
            val maxThreadsParam = session.parameters["maxThreads"]?.firstOrNull()
            val newLimit = maxThreadsParam?.toIntOrNull() ?: -1

            if (newLimit <= 0) {
                return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid limit"))
            }

            // --- Resource Saturation Guard ---
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            if (memoryInfo.lowMemory) {
                Log.w("TestServer", "Rejecting concurrency increase: Memory saturation detected.")
                return addCorsHeaders(newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "text/plain", "Saturation Reached: System memory low."))
            }

            synchronized(this) {
                currentMaxThreads = newLimit
                maxConcurrentThreads = Semaphore(newLimit, true)
            }

            Log.i("TestServer", "Concurrency updated to: $newLimit")
            return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain", "Max threads set to $newLimit"))
        } catch (e: Exception) {
            return addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}"))
        }
    }

    private fun handleGetMaxThreads(): Response {
        Log.i("TestServer", "Returning current max threads: $currentMaxThreads")
        return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain", currentMaxThreads.toString()))
    }

    private fun handleFileDownload(session: IHTTPSession): Response {
        val params = session.parameters
        val filename = params["file"]?.firstOrNull() ?: return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing filename"))

        try {
            if (filename.contains("/") || filename.contains("\\")) {
                return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename"))
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
            return addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found"))
        }
    }

    private fun handleBatteryRequest(): Response {
        val currentTime = System.currentTimeMillis()
        if (cachedBatteryJson != null && (currentTime - lastBatteryFetchTime) < batteryCacheDurationMs) {
            Log.i("TestServer", "Serving cached battery status.")
            return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", cachedBatteryJson))
        }

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
        return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", cachedBatteryJson))
    }

    private fun generateNewToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "origin, x-requested-with, content-type, accept, Authorization")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return response
    }
}
