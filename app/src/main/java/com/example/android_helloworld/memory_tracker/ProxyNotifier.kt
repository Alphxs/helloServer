package com.example.android_helloworld

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ProxyNotifier(private val context: Context, initialProxyUrl: String) : ComponentCallbacks2 {
    private val client = OkHttpClient()

    @Volatile
    private var proxyUrl: String = initialProxyUrl
    private val JAVA_HEAP_THRESHOLD_MB = 192.0

    /**
     * This is called by the server whenever a memory-heavy event starts.
     */
    fun checkAndNotifyHeapThreshold() {
        val runtime = Runtime.getRuntime()
        val totalHeapMb = runtime.totalMemory() / (1024.0 * 1024.0)

        if (totalHeapMb > JAVA_HEAP_THRESHOLD_MB) {
            Log.e("ProxyNotifier", "THRESHOLD ALERT: Total Heap (%.2f MB) exceeded limit!".format(totalHeapMb))
            sendLowMemoryAlert("JVM_TOTAL_MEMORY_EXCEEDED_THRESHOLD")
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            sendLowMemoryAlert("TRIM_MEMORY_RUNNING_LOW")
        }
    }

    override fun onLowMemory() {
        sendLowMemoryAlert("CRITICAL_LOW_MEMORY")
    }

    fun sendLowMemoryAlert(reason: String) {
        val json = """{"status": "low_memory", "reason": "$reason", "edge_id": "edge_1"}"""
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(proxyUrl).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e("ProxyNotifier", "Failed to notify proxy: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    fun sendStatusUpdate(isAvailable: Boolean, reason: String) {
        val status = if (isAvailable) "AVAILABLE" else "BUSY"
        val json = """
        {
            "edge_id": "edge_1",
            "status": "$status",
            "reason": "$reason",
            "timestamp": "${System.currentTimeMillis()}"
        }
    """.trimIndent()

        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(proxyUrl) // Ensure this points to the proxy's status endpoint
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e("ProxyNotifier", "Failed to send status update: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    fun updateProxyIp(ip: String, port: Int = 8080) {
        this.proxyUrl = "http://$ip:$port/status"
        Log.i("ProxyNotifier", "Proxy IP updated to: $proxyUrl")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}
}
