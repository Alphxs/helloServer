package com.example.android_helloworld

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ProxyNotifier(private val context: Context, initialProxyUrl: String) {

    // 1. Create a ConnectionPool to keep the socket open
    private val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(0, TimeUnit.SECONDS) // No timeout for connecting
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var proxyUrl: String = initialProxyUrl

    fun updateProxyIp(ip: String, port: Int) {
        this.proxyUrl = "http://$ip:$port/status"
        Log.i("ProxyNotifier", "Proxy IP established: $proxyUrl")
        // Send an initial ping to "warm up" the connection
        sendStatusUpdate(true, "INITIAL_HANDSHAKE")
    }

    fun sendStatusUpdate(isAvailable: Boolean, reason: String) {
        if (proxyUrl.contains("YOUR_PROXY_IP")) return

        val status = if (isAvailable) "AVAILABLE" else "BUSY"
        val json = """{"edge_id": "edge_1", "status": "$status", "reason": "$reason"}"""
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(proxyUrl)
            .post(body)
            // 2. Explicitly ask the Proxy to keep the connection open
            .header("Connection", "Keep-Alive")
            .build()

        // We use execute() in a background thread or enqueue to send immediately
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.i("ProxyNotifier", "Failed to send status: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                // 3. IMPORTANT: You MUST close or read the body to return
                // the connection to the pool so it stays open!
                response.close()
                Log.i("ProxyNotifier", "Status sent successfully")
            }
        })
    }
}