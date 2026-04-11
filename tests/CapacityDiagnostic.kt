package com.example.android_helloworld

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * CapacityDiagnostic translates the logic of the Python diagnostic tester into a Kotlin 
 * implementation suitable for the Android-based Reverse Proxy.
 * 
 * It manages the lifecycle of discovering an edge server's maximum thread capacity 
 * by incrementally increasing concurrency, verifying stability under load, and 
 * handling potential crashes or saturation signals.
 */
class CapacityDiagnostic(private val client: OkHttpClient) {

    private val TAG = "CapacityDiagnostic"

    /**
     * Executes the capacity diagnostic for a specific edge server.
     * 
     * @param edgeIp The IP address of the newly registered edge server.
     * @param testImage A sample image file used to stress test the recognition endpoint.
     * @param maxTestLimit The maximum thread count to attempt.
     * @param step The increment for each test iteration.
     * @return The last verified stable concurrency limit.
     */
    suspend fun runDiagnostic(
        edgeIp: String,
        testImage: File,
        maxTestLimit: Int = 40,
        step: Int = 2
    ): Int = withContext(Dispatchers.IO) {
        val baseUrl = "http://$edgeIp:8080"
        
        // Dynamically fetch starting threads from the server's current setting
        val currentLimit = getCurrentLimit(baseUrl)
        var lastStableLimit = currentLimit
        var terminationReason = "Reached Max Test Limit"

        Log.i(TAG, "====================================================")
        Log.i(TAG, "Starting Capacity Diagnostic for Edge: $edgeIp")
        Log.i(TAG, "Current limit from server: $currentLimit")
        Log.i(TAG, "====================================================")

        // Start testing from the current setting + step
        for (threads in (currentLimit + step)..maxTestLimit step step) {
            Log.i(TAG, "Testing Concurrency Level: $threads...")

            // 1. Attempt to update the concurrency limit on the edge server
            val (success, reason) = setConcurrency(baseUrl, threads)
            
            if (!success) {
                terminationReason = reason
                Log.w(TAG, "Termination trigger: $reason")
                
                // Revert to last known stable limit on saturation
                if (reason == "Saturation") {
                    setConcurrency(baseUrl, lastStableLimit)
                }
                break
            }

            // 2. Verify stability by sending a batch of concurrent requests (batchSize = threads + 2)
            val batchSize = threads + 2
            val isStable = verifyStability(baseUrl, testImage, batchSize)
            
            if (isStable) {
                lastStableLimit = threads
                Log.i(TAG, "Level $threads verified stable.")
            } else {
                terminationReason = "Load Failure (Verification Step)"
                Log.w(TAG, "Stability check failed at level $threads. Reverting to $lastStableLimit")
                
                // Revert to last known stable limit on load failure
                setConcurrency(baseUrl, lastStableLimit)
                break
            }
        }

        Log.i(TAG, "----------------------------------------------------")
        Log.i(TAG, "DIAGNOSTIC SUMMARY")
        Log.i(TAG, "Termination Reason: $terminationReason")
        Log.i(TAG, "Final Stable Capacity: $lastStableLimit threads")
        
        // Handle Recovery Phase if the server crashed during the test
        if (terminationReason == "Crash") {
            Log.i(TAG, "Waiting for server reboot to recover persisted limit...")
            val recovered = getRecoveredLimit(baseUrl)
            Log.i(TAG, "Limit recovered from server storage: $recovered")
        } else if (terminationReason == "Saturation") {
            Log.i(TAG, "Server reached physical saturation point (Resource Guard 429).")
        }
        Log.i(TAG, "----------------------------------------------------")

        return@withContext lastStableLimit
    }

    /**
     * Retrieves the current max threads setting from the server.
     */
    private suspend fun getCurrentLimit(baseUrl: String): Int {
        val request = Request.Builder()
            .url("$baseUrl/get-max-threads")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.toIntOrNull() ?: 5
                } else {
                    5
                }
            }
        } catch (e: IOException) {
            5
        }
    }

    /**
     * Sets the concurrency limit on the edge server via the /set-concurrency endpoint.
     */
    private fun setConcurrency(baseUrl: String, limit: Int): Pair<Boolean, String> {
        val url = "$baseUrl/set-concurrency?maxThreads=$limit"
        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create(null, ByteArray(0))) // POST with empty body
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> true to "OK"
                    429 -> false to "Saturation"
                    else -> false to "Server_Error_${response.code}"
                }
            }
        } catch (e: IOException) {
            // Likely a crash due to resource exhaustion
            false to "Crash"
        }
    }

    /**
     * Sends a batch of concurrent recognition requests to verify the edge server's stability.
     */
    private suspend fun verifyStability(baseUrl: String, image: File, batchSize: Int): Boolean = coroutineScope {
        val deferredResults = (1..batchSize).map { reqId ->
            async(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$baseUrl/recognize")
                    .header("x-client-request-id", "diag-proxy-$reqId")
                    .post(image.asRequestBody("image/jpeg".toMediaType()))
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        response.isSuccessful
                    }
                } catch (e: IOException) {
                    false
                }
            }
        }
        // Ensure all requests in the batch returned successfully
        deferredResults.awaitAll().all { it }
    }

    /**
     * Attempts to retrieve the last successfully persisted limit after an edge reboot.
     */
    private suspend fun getRecoveredLimit(baseUrl: String): String {
        // Wait for potential reboot
        delay(5000)
        
        val request = Request.Builder()
            .url("$baseUrl/get-max-threads")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string() ?: "Empty Response"
                } else {
                    "Error_${response.code}"
                }
            }
        } catch (e: IOException) {
            "Failed to reach server"
        }
    }
}
