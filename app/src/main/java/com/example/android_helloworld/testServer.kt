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
import android.os.Build
import android.graphics.BitmapFactory

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

    // --- Memory Watchdog Variables ---
    private val MEMORY_THRESHOLD_BYTES = 20L * 1024 * 1024 // 20 MB
    @Volatile private var isSaturated = false
    private var baseMaxThreads = 5 // Store the original limit to restore it later


    // --- Concurrency Management ---
    @Volatile
    private var currentMaxThreads = 5
    
    @Volatile
    private var maxConcurrentThreads = Semaphore(currentMaxThreads, true)

    @Volatile
    private var isLowMemory = false

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

    // Step 1: START of Memory Limit Tracker
    private val isOldAndroid = Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 // Android 7.1 or lower
    private val jvmMaxLimit: Long
    private var availableJavaMemAtStartup: Long = 0

    // Thread-safe tracker for "reserved" memory by active tasks
    @Volatile private var currentReservedJavaMem: Long = 0
    private val memoryLock = Any()

    // Gets available Java heap memory at startup
    // Reason why at startup: when methods are invoked after the first image recog, memory aren't deallocated.
    // Hence, they show it as if it is occupied when in reality it is free to use.
    init {
        val runtime = Runtime.getRuntime()
        val maxJvm = runtime.maxMemory()
        val allocatedJvm = runtime.totalMemory()
        val freeJvm = runtime.freeMemory()

        // Formula: availableJavaMem = (maxJvmHeap - allocatedJvmHeap) + freeJvmHeap
        availableJavaMemAtStartup = (maxJvm - allocatedJvm) + freeJvm
        jvmMaxLimit = maxJvm

        Log.i("AdmissionControl", "Startup - OS: ${Build.VERSION.RELEASE}, Avail JVM: ${availableJavaMemAtStartup / 1024 / 1024}MB")

        CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                monitorAndSignalProxy()
                delay(500) // Poll every 500ms for high responsiveness
            }
        }
    }
    // Step 1: START of Memory Limit Tracker


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
            } catch (t: Throwable) {
                // Throwable catches BOTH Exceptions and Errors (like OOM)
                Log.e("TestServer", "CRASH in serve(): ${t.localizedMessage}")
                t.printStackTrace() // This prints the full error to Logcat
                addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Fatal Crash"))
            }catch (e: Exception) {
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
            method == Method.GET && uri == "/memory" -> handleMemoryRequest()
            method == Method.GET && uri == "/is-low-memory" -> handleLowMemoryStatus()
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

            // --- Step 2: Compute Allocated Memory Prediction ---
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(permanentFile.absolutePath, options)
            val w = options.outWidth.toLong()
            val h = options.outHeight.toLong()

            val (allocJavaMem, allocNativeMem) = calculatePredictedMemory(w, h)

            // --- Step 3 & 4: Admission Control ---
            val isAdmissible = synchronized(memoryLock) {
                val availableNative = getAvailableNativeMem()
                val currentActualJavaRoom = availableJavaMemAtStartup - currentReservedJavaMem

                val javaFit = allocJavaMem <= currentActualJavaRoom
                val nativeFit = allocNativeMem <= availableNative

                if (javaFit && nativeFit) {
                    // Step 4: Continue - Increment reserved memory
                    currentReservedJavaMem += allocJavaMem
                    true
                } else {
                    Log.w("AdmissionControl", "REJECTED - JavaFit: $javaFit, NativeFit: $nativeFit")
                    false
                }
            }

            if (!isAdmissible) {
                return addCorsHeaders(newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "text/plain", "Insufficient Memory"))
            }

            maxConcurrentThreads.acquire()
            try {
                val recognitionStartTime = getDetailedTimestamp()

                // Use .use to ensure the recognizer is closed and native memory is freed
                val result = ImageRecognizer(context, userDao).use { recognizer ->
                    recognizer.processImage(permanentFile)
                }

                val recognitionEndTime = getDetailedTimestamp()
                val responseSentTime = getDetailedTimestamp()

                logToCsv(clientId, requestReceivedTime, recognitionStartTime, recognitionEndTime, responseSentTime)

                return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", result))
            } finally {
                // Step 5: Done - Decrement reserved memory
                synchronized(memoryLock) {
                    currentReservedJavaMem -= allocJavaMem
                }
                maxConcurrentThreads.release()
            }
        } catch (oom: OutOfMemoryError) {
            val causeDetails = oom.cause?.toString() ?: oom.message ?: "No specific cause details available"

            Log.e("CRASH_LOG", "CRITICAL: Out of Memory during recognition! Client: $clientId. Cause: $causeDetails", oom)

            return addCorsHeaders(newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Server crashed: Out of Memory. Details: $causeDetails"
            ))
        }catch (e: Exception) {
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

    // Monitors currentJavaRoom.
    // If it reached the threshold, set maxConcurrentThreads to 0
    // Else if it is okay again (below threshold), set maxConcurrentThreads to its original value 5
    private fun monitorAndSignalProxy() {
        val currentJavaRoom = availableJavaMemAtStartup - currentReservedJavaMem
        val currentNativeRoom = getAvailableNativeMem()

        // 1. Threshold Check (20MB)
        // Stop if EITHER is <= 20MB
        val needsStop = currentJavaRoom <= MEMORY_THRESHOLD_BYTES || currentNativeRoom <= MEMORY_THRESHOLD_BYTES

        // 2. Recovery Check
        // Okay again only if BOTH are > 20MB
        val canResume = currentJavaRoom > MEMORY_THRESHOLD_BYTES && currentNativeRoom > MEMORY_THRESHOLD_BYTES

        if (needsStop && !isSaturated) {
            // TRANSITION TO STOP
            isSaturated = true
            Log.e("Watchdog", "!!! CRITICAL MEMORY !!! Java: ${currentJavaRoom/1024/1024}MB, Native: ${currentNativeRoom/1024/1024}MB")
            updateProxyCapacity(0)
        }
        else if (canResume && isSaturated) {
            // TRANSITION TO START
            isSaturated = false
            Log.i("Watchdog", "Memory Recovered. Restoring capacity to $baseMaxThreads")
            updateProxyCapacity(baseMaxThreads)
        }
    }

    // Updates the current max concurrent threads when threshold is reached
    private fun updateProxyCapacity(newLimit: Int) {
        synchronized(this) {
            currentMaxThreads = newLimit
            // We re-initialize the semaphore to block/unblock incoming requests
            maxConcurrentThreads = Semaphore(if (newLimit == 0) 1 else newLimit, true)
            if (newLimit == 0) {
                // Drain the semaphore if we are stopping
                maxConcurrentThreads.acquireUninterruptibly(1)
            }
        }
        // By setting this to 0, the Proxy's routing algorithm will see 0 capacity and skip this edge.
        Log.i("ProxySignal", "SIGNAL: MaxLimit updated to $newLimit")
    }

    // IMPORTANT: Formula for computing the allocated memory of a single image
    private fun calculatePredictedMemory(w: Long, h: Long): Pair<Long, Long> {
        val arena = 3.9 * 1024 * 1024
        var javaMem: Long = 0
        var nativeMem: Long = 0

        if (isOldAndroid) { // Android 7.1 and lower
            // Java: decode(w*h*4) + detect(w*h*4 + w*h*3)
            javaMem = (w * h * 4) + (w * h * 4) + (w * h * 3)
            // Native: detectNative(w*h*4 + arena) + decode(w*h*3)
            nativeMem = (w * h * 4 + arena.toLong()) + (w * h * 3)
        } else { // Android 8.0+ Higher versions
            // Java: detect(w*h*4 + w*h*3)
            javaMem = (w * h * 4) + (w * h * 3)
            // Native: detectNative(w*h*4 + arena) + decode((w*h*4) + (w*h*3))
            nativeMem = ((w * h * 4) + arena.toLong()) + (w * h * 4) + (w * h * 3)
        }
        return Pair(javaMem, nativeMem)
    }

    // Helper function to retrieve current available system RAM
    private fun getAvailableNativeMem(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        // availableNativeMem = system.AvailMem() - system.Threshold()
        return memInfo.availMem - memInfo.threshold
    }

    // Not used in the actual implementation, just for testing purposes
    private fun handleMemoryRequest(): Response {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()

        // 1. JVM Heap Stats (Standard)
        val maxHeapMb = runtime.maxMemory() / (1024 * 1024)
        val allocatedHeapMb = runtime.totalMemory() / (1024 * 1024)
        val freeInAllocatedMb = runtime.freeMemory() / (1024 * 1024)
        val actualUsedHeapMb = allocatedHeapMb - freeInAllocatedMb

        // 2. Process-wide Native Stats (Critical for TFLite/Bitmaps)
        val debugMemInfo = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(debugMemInfo)

        // PSS (Proportional Set Size) is the most accurate measure of actual RAM impact
        val nativePssMb = debugMemInfo.nativePss / 1024
        val dalvikPssMb = debugMemInfo.dalvikPss / 1024
        val totalPssMb = debugMemInfo.totalPss / 1024

        // 3. System-wide RAM
        val totalSystemRamMb = memoryInfo.totalMem / (1024 * 1024)
        val availableSystemRamMb = memoryInfo.availMem / (1024 * 1024)
        val lowMemoryAdvisory = memoryInfo.lowMemory

        // 4. Native Memory Stats (Raw Heap)
        val nativeHeapSizeMb = android.os.Debug.getNativeHeapSize() / (1024 * 1024)
        val nativeHeapAllocatedMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val nativeHeapFreeMb = android.os.Debug.getNativeHeapFreeSize() / (1024 * 1024)

        // 3.5 Practical Limits (Context for Native/System)
        // The 'threshold' is the most important "limit" for native memory.
        // If available RAM falls below this, the OS kills your server.
        val systemThresholdMb = memoryInfo.threshold / (1024 * 1024)

        // Memory Class represents the per-app "safe" limit defined by the manufacturer
        val memoryClassMb = activityManager.memoryClass
        val largeMemoryClassMb = activityManager.largeMemoryClass

        val memoryData = mapOf(
            "jvm_heap" to mapOf(
                "used_mb" to actualUsedHeapMb,
                "allocated_mb" to allocatedHeapMb,
                "max_limit_mb" to maxHeapMb
            ),
            "pss_mem" to mapOf(
                "native_pss_mb" to nativePssMb,
                "dalvik_pss_mb" to dalvikPssMb,
                "total_process_pss_mb" to totalPssMb,
            ),
            "system_ram" to mapOf(
                "available_mb" to availableSystemRamMb,
                "total_mb" to totalSystemRamMb,
                "low_ram_kill_threshold_mb" to systemThresholdMb, // THE NATIVE LIMIT
                "is_low_memory_advisory" to lowMemoryAdvisory
            ),
            "native_mem" to mapOf(
                "native_heap_size_mb" to nativeHeapSizeMb,
                "native_heap_allocated_mb" to nativeHeapAllocatedMb,
                "native_heap_free_mb" to nativeHeapFreeMb
            ),
            "device_limits" to mapOf(
                "standard_memory_class_mb" to memoryClassMb,
                "large_memory_class_mb" to largeMemoryClassMb
            )
        )

        Log.i("TestServer", "Memory Status: JVM Used: ${actualUsedHeapMb}MB, Native: ${nativePssMb}MB, System Avail: ${availableSystemRamMb}MB")

        val jsonResponse = gson.toJson(memoryData)
        return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse))
    }

    // Not used in the actual implementation, just for testing purposes
    private fun handleLowMemoryStatus(): Response {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLowRamDevice = activityManager.isLowRamDevice

        val currentJava = (availableJavaMemAtStartup - currentReservedJavaMem) / (1024 * 1024)
        val currentNative = getAvailableNativeMem() / (1024 * 1024)

        val jsonResponse = """
            {
                "isLowMemoryAdvisory": $isSaturated,
                "isLowRamDevice": $isLowRamDevice,
                "availableJavaMb": $currentJava,
                "availableNativeMb": $currentNative,
                "currentMaxThreads": $currentMaxThreads
            }
        """.trimIndent()
        return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse))
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
