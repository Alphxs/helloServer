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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.sql.Types.NULL
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger


// Data class for sending predictions back as JSON
data class Prediction(val label: String, val score: Float)

class testServer(
    private val context: Context,
    private val userDao: UserDao,
    port: Int
) : NanoHTTPD(port) {
    // --- NEW: Concurrency Control Variables ---
    @Volatile // Ensures writes are visible across threads
    private var maxConcurrentTasks = 2 // Default to 2 concurrent tasks

    // A semaphore to limit the number of active recognition tasks.
    @Volatile
    private var recognitionSemaphore = Semaphore(maxConcurrentTasks, true) // `true` for fairness
    private val serverJob = SupervisorJob()
    private val serverScope = CoroutineScope(Dispatchers.IO + serverJob)

    private val activeProcessingTasks = AtomicInteger(0)
    private val queuedWaitingTasks = AtomicInteger(0)
    private val taskResults = ConcurrentHashMap<String, String>()
    @Volatile private var cachedBatteryJson: String? = null
    @Volatile private var lastBatteryFetchTime: Long = 0
    private val batteryCacheDurationMs = 1000 // Cache for 1 second

    private val activeTokens = ConcurrentHashMap.newKeySet<String>()
    private val gson = Gson()

    override fun stop() {
        super.stop()
        // Ensure the queue is stopped when the server stops.
//        RecognitionTaskQueue.stop()
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
            method == Method.POST && uri == "/recognize" -> handleSecureRecognition(session)
            // --- NEW ROUTES ---
            method == Method.POST && uri == "/set-concurrency" -> handleConcurrencyChange(session)
            method == Method.GET && uri == "/queue-status" -> handleQueueStatus()
            // --- END NEW ---
            method == Method.GET && uri == "/battery" -> handleSecureBatteryRequest(session)
            method == Method.GET && uri == "/status" -> handleBatteryRequest()
            method == Method.GET && uri.startsWith("/result/") -> handleResultRequest(uri)
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

    /**
     * Endpoint to dynamically set the maximum number of concurrent recognition threads.
     * This is synchronized to prevent race conditions.
     */
    private fun handleConcurrencyChange(session: IHTTPSession): Response {
        // Synchronize to prevent race conditions when changing the semaphore.
        synchronized(this) {
            return try {
                val body = hashMapOf<String, String>()
                session.parseBody(body)
                val json = org.json.JSONObject(body["postData"] ?: "{}")
                val newLimit = json.optInt("maxThreads", -1)

                if (newLimit <= 0) {
                    return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "'maxThreads' must be a positive integer."))
                }

                if (newLimit == maxConcurrentTasks) {
                    return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain", "Concurrency is already set to $newLimit."))
                }

                Log.w("TestServer", "ADMIN: Changing max concurrency from $maxConcurrentTasks to $newLimit")
                maxConcurrentTasks = newLimit

                // The new semaphore will immediately apply to the next task waiting to acquire a permit.
                recognitionSemaphore = Semaphore(newLimit, true)

                addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain", "Max concurrency successfully set to $newLimit."))
            } catch (e: Exception) {
                Log.e("TestServer", "Error setting concurrency", e)
                addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to parse request."))
            }
        }
    }


    /**
     * Endpoint to report the current status of the recognition queue.
     */
    private fun handleQueueStatus(): Response {
        // This is now the source of truth, no calculation needed.
        val statusMap = mapOf(
            "maxConcurrency" to maxConcurrentTasks,
            "activeProcessingTasks" to activeProcessingTasks.get(),
            "queuedWaitingTasks" to queuedWaitingTasks.get(),
        )

        val jsonResponse = gson.toJson(statusMap)
        return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse))
    }



    private suspend fun handleSecureRecognition(session: IHTTPSession): Response {
//        if (!isTokenValid(session)) {
//            return addCorsHeaders(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized: Missing or invalid token."))
//        }
//        Log.i("TestServer", "Token valid, proceeding to queue recognition task.")
        return handleRecognition(session)
    }

    private suspend fun handleRecognition(session: IHTTPSession): Response {
        val taskId = java.util.UUID.randomUUID().toString()
        Log.i("TestServer", "Handling: ${session.method} ${session.uri} for task $taskId")
        try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val tempImageFilePath = files["imageFile"]
            if (tempImageFilePath.isNullOrEmpty()) {
                return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No image file was uploaded."))
            }

            val tempFile = File(tempImageFilePath)
            val permanentImageDir = File(context.filesDir, "images").apply { mkdirs() }
            val permanentFile = File(permanentImageDir, "img_${taskId}.jpg")
            tempFile.copyTo(permanentFile, overwrite = true)

            // 1. Mark the task as "queued" immediately.
            taskResults[taskId] = gson.toJson(mapOf("taskId" to taskId, "status" to "queued"))
            Log.i("TestServer", "[$taskId] - Task queued. Copied to ${permanentFile.absolutePath}")

            // 2. Launch the processing task on our controlled dispatcher. It will wait for a permit.
            serverScope.launch(Dispatchers.IO) {
                    processRecognitionTask(taskId, permanentFile)
                }


            // 3. Immediately return an ACCEPTED response with the taskId.
            val responseJson = gson.toJson(mapOf("taskId" to taskId, "status" to "queued"))
            return addCorsHeaders(newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", responseJson))

        } catch (e: Exception) {
            Log.e("TestServer", "[$taskId] - Error handling recognition request", e)
            taskResults.remove(taskId) // Clean up failed task
            return addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error processing image: ${e.message}"))
        }
    }

    private suspend fun processRecognitionTask(taskId: String, imageFile: File) {
        queuedWaitingTasks.incrementAndGet()
        Log.i("TestServer", "[$taskId] - Task is now waiting for a permit. (Queued: ${queuedWaitingTasks.get()})")

        recognitionSemaphore.acquire()
        // --- From this point on, a permit is held. We MUST release it in a finally block. ---

        try {
            // --- STATE UPDATE: Task is now moving from queued to active ---
            queuedWaitingTasks.decrementAndGet()
            activeProcessingTasks.incrementAndGet()
            Log.i("TestServer", "[$taskId] - Permit acquired, now active. (Active: ${activeProcessingTasks.get()}, Queued: ${queuedWaitingTasks.get()})")

            Log.d("TestServer", "[$taskId] - Starting 8-second artificial delay to test queue...")
//            delay(4000L) // 8-second delay
            Log.d("TestServer", "[$taskId] - Artificial delay finished. Starting actual work.")

            // --- Use withTimeoutOrNull for the actual work, but handle state outside of it ---
            val processingResult = withTimeoutOrNull(120000L) { // 120-second timeout
                ImageRecognizer(context, userDao).use { perRequestRecognizer ->
                    taskResults[taskId] = gson.toJson(mapOf("taskId" to taskId, "status" to "processing"))
                    perRequestRecognizer.processImage(imageFile) // Returns the JSON result
                }
            }

            // --- After the work is done (or timed out), update the final result ---
            if (processingResult != null) {
                // SUCCESS
                val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
                val resultsList: List<Map<String, Any>> = gson.fromJson(processingResult, listType)

                if (resultsList.isNotEmpty()) {
                    val firstResultMap = resultsList[0].toMutableMap()
                    firstResultMap["taskId"] = taskId
                    firstResultMap["status"] = "complete"
                    taskResults[taskId] = gson.toJson(firstResultMap)
                    Log.i("TestServer", "[$taskId] - Recognition successful.")
                } else {
                    taskResults[taskId] = gson.toJson(mapOf("taskId" to taskId, "status" to "error", "message" to "Recognition completed with no results."))
                    Log.w("TestServer", "[$taskId] - Recognition completed but no results were found.")
                }
            } else {
                // TIMEOUT
                Log.e("TestServer", "[$taskId] - Task TIMED OUT after 120 seconds.")
                taskResults[taskId] = gson.toJson(mapOf("taskId" to taskId, "status" to "error", "message" to "Task timed out after 120 seconds."))
            }

        } catch (e: Exception) {
            // CATCH ALL OTHER ERRORS (e.g., from ImageRecognizer)
            Log.e("TestServer", "[$taskId] - A critical error occurred during task processing.", e)
            taskResults[taskId] = gson.toJson(mapOf("taskId" to taskId, "status" to "error", "message" to (e.message ?: "Unknown recognition error")))
        } finally {
            // --- STATE UPDATE: This block ALWAYS runs, guaranteeing cleanup ---
            // The task is no longer active, regardless of success, failure, or timeout.
            activeProcessingTasks.decrementAndGet()
            recognitionSemaphore.release()
            Log.i("TestServer", "[$taskId] - Permit released, task finished. (Active: ${activeProcessingTasks.get()}, Queued: ${queuedWaitingTasks.get()})")
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
            // This catch block will handle the case where the asset does not exist.
            Log.w("TestServer", "Asset file not found for download: $filename", e)
            return addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Error: Asset file not found."))
        }
    }

    private fun handleSecureBatteryRequest(session: IHTTPSession): Response {
//        if (!isTokenValid(session)) {
//            return addCorsHeaders(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized: Missing or invalid token."))
//        }
        return handleBatteryRequest()
    }

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

        val batteryData = mapOf("level" to "%.1f%%".format(batteryPct), "status" to chargingStatus)
        val jsonResponse = gson.toJson(batteryData)

        // --- Update the cache ---
        cachedBatteryJson = jsonResponse
        lastBatteryFetchTime = currentTime

        return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse))
    }

    /**
     * Handles polling requests for the result of a specific task.
     * It extracts the taskId from the URL path.
     */
    private fun handleResultRequest(uri: String): Response {
        // 1. Extract the taskId from the URL (e.g., "/result/some-uuid")
        val taskId = uri.substringAfterLast('/')

        if (taskId.isBlank()) {
            return addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Task ID is missing from the URL."))
        }

        // 2. Look up the result in our concurrent map.
        val resultJson = taskResults[taskId]

        return if (resultJson != null) {
            // 3. Task was found, return its current status (queued, processing, complete, etc.)
            Log.i("TestServer", "[$taskId] - Serving result: $resultJson")
            addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", resultJson))
        } else {
            // 4. Task ID was not found in the map. This can happen if the client polls
            //    too quickly after submitting. Return a 404 Not Found.
            Log.w("TestServer", "[$taskId] - Result requested but not found in map.")
            addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Task ID not found."))
        }
    }



    private fun generateNewToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun isTokenValid(session: IHTTPSession): Boolean {
        val authHeader = session.headers["authorization"] ?: return false
        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return false
        val token = authHeader.substringAfter("Bearer ")
        return activeTokens.contains(token)
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "origin, x-requested-with, content-type, accept, Authorization")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return response
    }
}
