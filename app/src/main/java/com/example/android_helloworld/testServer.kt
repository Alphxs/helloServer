package com.example.android_helloworld

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.example.android_helloworld.db.UserDao
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import com.example.android_helloworld.recognition.ImageRecognizer
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.Semaphore

// Data class for sending predictions back as JSON
data class Prediction(val label: String, val score: Float)

enum class RoutingAlgorithm {
    RANDOM,
    RANDOM_CPT,
    RTTMS,
    RESAT_V2
}

class testServer(private val context: Context, private val userDao: UserDao, port: Int) : NanoHTTPD(port) {
    @Volatile private var cachedBatteryJson: String? = null
    @Volatile private var lastBatteryFetchTime: Long = 0

    @Volatile var currentAlgorithm = RoutingAlgorithm.RTTMS

    fun setAlgorithm(algo: RoutingAlgorithm) {
        currentAlgorithm = algo
        Log.i("TestServer", "Routing Algorithm changed to: $algo")
    }
    private val batteryCacheDurationMs = 1000 // Cache for 1 second
    private val gson = Gson()
    private val logger = MetricsLogger(context)

    private val memoryLock = Mutex()

    private val memoryTracker = MemoryTracker(context)
    private val proxyNotifier = ProxyNotifier(context, "http://192.168.137.28:8080/status")

    private lateinit var memorySemaphore: Semaphore
    private var totalMemoryPermits: Int = 0

    //private val heapProfiler = HeapProfiler(context)

    init {
        // Start tracking heap behavior every 100ms
        //heapProfiler.startProfiling(50)

        // Initialize semaphore based on available bytes
        val initialMb = memoryTracker.getInitialPlaygroundMem()
        val permits = initialMb.coerceAtLeast(1)

        totalMemoryPermits = permits

        // true = fair queuing (First-Come-First-Served)
        memorySemaphore = Semaphore(permits, true)

        Log.i("TestServer", "Memory Semaphore initialized with $permits bytes permits")
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
            method == Method.POST && uri == "/recognize" -> handleRecognition(session)
            method == Method.GET && uri == "/battery" || method == Method.GET && uri == "/status" -> handleBatteryRequest()
            method == Method.GET && uri == "/download" -> handleFileDownload(session)
            method == Method.GET && uri == "/memory" -> handleMemoryRequest()
            method == Method.POST && uri == "/proxy-handshake" -> handleProxyHandshake(session)
            else -> {
                Log.w("TestServer", "Unhandled request for URI: $uri")
                addCorsHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Error: Not Found"))
            }
        }
    }

    private suspend fun handleRecognition(session: IHTTPSession): Response {
        val receivedTime = logger.getDetailedTimestamp()
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

            if (currentAlgorithm == RoutingAlgorithm.RANDOM || currentAlgorithm == RoutingAlgorithm.RESAT_V2) {
                Log.i("ALGO", "Executing in RANDOM/RESAT_V2 mode (No Crash Prevention)")
                val startTime = logger.getDetailedTimestamp()
                val result = ImageRecognizer(context, userDao, proxyNotifier).use { it.processImage(permanentFile!!) }
                val endTime = logger.getDetailedTimestamp()

                // waitTime is 0 because there is no queue in Random mode
                logger.logToCsv(clientId, receivedTime, startTime, endTime, logger.getDetailedTimestamp(), "0")
                return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", result))
            }

            // Compute Allocated Memory for Java heap and Native mem
            val (allocJavaMem, allocNativeMem) = memoryTracker.calculateMemory(permanentFile.absolutePath)

            var waitStartTime = System.currentTimeMillis()

            // Acquire permits.
            memorySemaphore.acquire(allocJavaMem.toInt())

            val waitDurationMs = System.currentTimeMillis() - waitStartTime

            return try {
                val recognitionStartTime = logger.getDetailedTimestamp()

                val result = ImageRecognizer(context, userDao, proxyNotifier).use { it.processImage(permanentFile) }

                val recognitionEndTime = logger.getDetailedTimestamp()

                logger.logToCsv(clientId, receivedTime, recognitionStartTime, recognitionEndTime, logger.getDetailedTimestamp(), waitDurationMs.toString())
                addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", result))
            } finally {
                // Release permits
                memorySemaphore.release(allocJavaMem.toInt())

                if (permanentFile.exists()) permanentFile.delete()
            }
        } catch (oom: OutOfMemoryError) {
            val causeDetails = oom.cause?.toString() ?: oom.message ?: "No specific cause details available"
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

        val androidVersion = getAndroidVersion()

        val freeMem = memoryTracker.getInitialPlaygroundMem()

        val batteryData = mapOf(
            "level" to "%.1f%%".format(batteryPct),
            "status" to "AVAILABLE",
            "chargingStatus" to chargingStatus,
            "remaining_mah" to mahString,
            "android_version" to androidVersion,
            "freeMem" to freeMem
        )
        val jsonResponse = gson.toJson(batteryData)

        // --- Update the cache ---
        cachedBatteryJson = jsonResponse
        lastBatteryFetchTime = currentTime
        return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "application/json", cachedBatteryJson))
    }

    private fun handleProxyHandshake(session: IHTTPSession): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            // The Proxy sends its own IP and Port in the post body or parameters
            val proxyIp = session.parameters["ip"]?.firstOrNull()
            val proxyPort = session.parameters["port"]?.firstOrNull()?.toIntOrNull() ?: 8080

            if (proxyIp != null) {
                proxyNotifier.updateProxyIp(proxyIp, proxyPort)
                Log.i("TestServer", "Handshake successful! Proxy introduces itself at: $proxyIp:$proxyPort")
                addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain", "Handshake Accepted"))
            } else {
                addCorsHeaders(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing IP"))
            }
        } catch (e: Exception) {
            addCorsHeaders(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message))
        }
    }

    // Not used in the actual implementation, just for testing purposes
    private fun handleMemoryRequest(): Response {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()

        // 1. JVM Heap Stats (Standard)
        val maxHeapMb = runtime.maxMemory() / (1024.0 * 1024.0)
        val totalHeapMb = runtime.totalMemory() / (1024.0 * 1024.0)
        val freeInTotalMb = runtime.freeMemory() / (1024.0 * 1024.0)
        val actualUsedHeapMb = totalHeapMb - freeInTotalMb

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
                "free_mb" to "%.2f".format(freeInTotalMb),
                "total_mb" to "%.2f".format(totalHeapMb),
                "max_limit_mb" to "%.2f".format(maxHeapMb),
                "actual_used_mb" to "%.2f".format(actualUsedHeapMb)
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

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "origin, x-requested-with, content-type, accept, Authorization")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return response
    }

    private fun getAndroidVersion(): Int {
        return android.os.Build.VERSION.SDK_INT
    }

}
