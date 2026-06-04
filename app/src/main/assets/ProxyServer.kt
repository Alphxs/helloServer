import android.util.Log
import com.example.proxy.logger.DecisionLogger
import com.example.proxy.mdnsDiscovery.EdgeRegistry
import com.example.proxy.mdnsDiscovery.TaskType
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ProxyServer(
    private val edgeRegistry: EdgeRegistry,
    port: Int,
    private val logger: (String) -> Unit
) : fi.iki.elonen.NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun serve(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val uri = session.uri
        val method = session.method
        logger("Incoming request: $method $uri") // <-- REPLACE Log.i with logger()
        android.util.Log.i("ProxyServer", "Incoming request: $method $uri")

        return when {
            uri == "/" && method == fi.iki.elonen.NanoHTTPD.Method.GET -> handleHtmlRequest()
            uri == "/login" && method == fi.iki.elonen.NanoHTTPD.Method.POST -> handleLoginRequest(session)

            uri == "/status" && method == fi.iki.elonen.NanoHTTPD.Method.POST -> handleStatusUpdateFromEdge(session)

            uri == "/recognize" && method == fi.iki.elonen.NanoHTTPD.Method.POST -> handleRecognitionRequest(session)
            uri.startsWith("/result/") && method == fi.iki.elonen.NanoHTTPD.Method.GET -> handleForwardingRequest(session)
            uri == "/queue-status" && method == fi.iki.elonen.NanoHTTPD.Method.GET -> handleForwardingRequest(session)
            uri == "/set-concurrency" && method == fi.iki.elonen.NanoHTTPD.Method.POST -> handleForwardingRequest(session)

            uri == "/battery" && method == fi.iki.elonen.NanoHTTPD.Method.GET -> handleBatteryRequest(session)
            uri == "/download" && method == fi.iki.elonen.NanoHTTPD.Method.GET -> handleFileDownloadRequest(session)
            else -> fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND,
                "text/plain",
                "404 Not Found"
            )
        }
    }

    private fun handleHtmlRequest(): fi.iki.elonen.NanoHTTPD.Response {
        val edge = edgeRegistry.getBestEdge(TaskType.SHORT)
            ?: return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "No edge servers available"
            )

        val edgeUrl = "http://${edge.ip}:8080/"
        logger("Fetching HTML from edge: $edgeUrl")
        android.util.Log.i("ProxyServer", "Fetching HTML from edge: $edgeUrl")

        return try {
            val request = Request.Builder().url(edgeUrl).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val mime = response.header("Content-Type") ?: "text/html"
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.lookup(response.code)
                        ?: fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                    mime,
                    body
                )
            }
        } catch (e: Exception) {
            logger("Error fetching HTML")
            android.util.Log.e("ProxyServer", "Error fetching HTML", e)
            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Proxy Error: ${e.message}"
            )
        }
    }

    private fun handleLoginRequest(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val edge = edgeRegistry.getBestEdge(TaskType.SHORT)
            ?: return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "No edge servers available"
            )

        val edgeUrl = "http://${edge.ip}:8080${session.uri}"
        logger("Forwarding login to $edgeUrl")
        android.util.Log.i("ProxyServer", "Forwarding login to $edgeUrl")

        return try {
            val tempFiles = mutableMapOf<String, String>()
            session.parseBody(tempFiles)

            val formData = session.parameters.map { (key, values) ->
                val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
                val encodedValue = java.net.URLEncoder.encode(values.firstOrNull() ?: "", "UTF-8")
                "$encodedKey=$encodedValue"
            }.joinToString("&")

            val body = formData.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val request = Request.Builder().url(edgeUrl)
                .post(body)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { edgeResponse ->
                val bytes = edgeResponse.body?.bytes() ?: ByteArray(0)
                val mime = edgeResponse.header("Content-Type") ?: "application/json"

                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.lookup(edgeResponse.code)
                        ?: fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                    mime,
                    java.io.ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            logger("Error forwarding /login")
            android.util.Log.e("ProxyServer", "Error forwarding /login", e)
            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Proxy Error: ${e.message}"
            )
        }
    }

    private fun handleStatusUpdateFromEdge(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = session.parameters
            val status = postData["status"]?.firstOrNull() ?: "AVAILABLE"
            val edgeIp = session.remoteIpAddress // Detect edge by source IP

            edgeRegistry.updateStatus(edgeIp, status)
            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                "text/plain",
                "Status Updated"
            )
        } catch (e: Exception) {
            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "text/plain",
                e.message
            )
        }
    }

    private fun handleRecognitionRequest(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val edge = edgeRegistry.getBestEdge(TaskType.LONG)
            ?: return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "No edge servers available for recognition"
            )

        edgeRegistry.incrementQueue(edge.ip)

        // Capture the client's Request ID
        val clientRequestId = session.headers["x-client-request-id"] ?: "unknown"

        // 1. Create the initial decision record (sets timestampOfReceivingRequest)
        val decision = DecisionLogger.createRecord(session.uri, edge, clientRequestId)

        val edgeUrl = "http://${edge.ip}:8080/recognize"
        logger("Forwarding image request to edge: $edgeUrl")

        val contentType = session.headers["content-type"]
            ?: return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain",
                "Content-Type header is missing"
            )

        if (!contentType.startsWith("multipart/form-data", ignoreCase = true)) {
            decision.status = "Client_Error_400"
            DecisionLogger.finalizeAndWrite(decision)
            return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                "text/plain",
                "Invalid Content-Type"
            )
        }

        val authorization = session.headers["authorization"]
        var tempImageFile: java.io.File? = null

        try {
            val tempFiles = mutableMapOf<String, String>()
            session.parseBody(tempFiles)

            val tempImageFilePath = tempFiles["imageFile"]
                ?: throw java.io.IOException("File 'imageFile' not found in multipart request.")

            tempImageFile = java.io.File(tempImageFilePath)
            decision.imageSizeBytes = tempImageFile.length()

            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "imageFile",
                    tempImageFile.name,
                    tempImageFile.asRequestBody(contentType.toMediaTypeOrNull())
                )
                .build()

            val requestBuilder = Request.Builder()
                .url(edgeUrl)
                .post(requestBody)
                .header("X-Client-Request-ID", clientRequestId)

            if (authorization != null) {
                requestBuilder.header("Authorization", authorization)
            }

            // 2. RECORD FORWARDING TIMESTAMP
            decision.timestampOfForwardingRequest = java.lang.System.currentTimeMillis()

            // Execute the blocking call to the edge
            return client.newCall(requestBuilder.build()).execute().use { edgeResponse ->

                // 3. RECORD RECEIVING FROM EDGE TIMESTAMP (Headers received)
                decision.timestampOfReceivingEdgeResponse = java.lang.System.currentTimeMillis()

                val responseBody = edgeResponse.body?.bytes() ?: ByteArray(0)

                // 4. Manually set End Timestamp for total RTT calculation
                decision.timestampOfSendingResponse = java.lang.System.currentTimeMillis()
                val totalRtt = decision.rttMs ?: 0L

                if (edgeResponse.isSuccessful) {
                    decision.status = "Success"
                    // Update algorithms using Total RTT (Full Proxy Round Trip)
                    edgeRegistry.updateRtt(edge.ip, totalRtt, TaskType.LONG)
                } else {
                    decision.status = "Edge_Error_${edgeResponse.code}"
                }

                DecisionLogger.finalizeAndWrite(decision)

                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.lookup(edgeResponse.code)
                        ?: fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                    edgeResponse.header("Content-Type") ?: "application/json",
                    java.io.ByteArrayInputStream(responseBody),
                    responseBody.size.toLong()
                )
            }

        } catch (e: Exception) {
            decision.status = "Proxy_Error"
            DecisionLogger.finalizeAndWrite(decision)
            android.util.Log.e("ProxyServer", "Error forwarding /recognize", e)
            return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Proxy Error: ${e.message}"
            )
        } finally {
            edgeRegistry.decrementQueue(edge.ip)
            // Clean up temp file
            if (tempImageFile?.exists() == true) {
                tempImageFile.delete()
            }
        }
    }



    private fun handleBatteryRequest(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val edge = edgeRegistry.getBestEdge(TaskType.SHORT)
            ?: return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "No edge"
            )

        // 1. Capture Client Request ID
        val clientRequestId = session.headers["x-client-request-id"] ?: "unknown"

        // 2. Create the Decision Record
        val decision = DecisionLogger.createRecord(session.uri, edge, clientRequestId)

        val edgeUrl = "http://${edge.ip}:8080/battery"
        val requestBuilder = Request.Builder()
            .url(edgeUrl)
            .header("X-Client-Request-ID", clientRequestId)

        session.headers["authorization"]?.let {
            requestBuilder.header("Authorization", it)
        }

        // 3. Record Forwarding Timestamp
        decision.timestampOfForwardingRequest = java.lang.System.currentTimeMillis()

        return try {
            client.newCall(requestBuilder.build()).execute().use { edgeResponse ->
                val endTime = java.lang.System.currentTimeMillis()
                val rtt = endTime - decision.timestampOfForwardingRequest!!

                // Update Rtt and Decrement Queue on success
                if (edgeResponse.isSuccessful) {
                    edgeRegistry.updateRtt(edge.ip, rtt, TaskType.SHORT)
                }

                // 4. Record Edge Response Timestamp
                decision.timestampOfReceivingEdgeResponse = java.lang.System.currentTimeMillis()

                val bytes = edgeResponse.body?.bytes() ?: ByteArray(0)

                if (edgeResponse.isSuccessful) {
                    decision.status = "Battery_Success"
                } else {
                    decision.status = "Battery_Edge_Error_${edgeResponse.code}"
                }

                // 5. Finalize the Log (sets timestampOfSendingResponse)
                DecisionLogger.finalizeAndWrite(decision)

                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.lookup(edgeResponse.code)
                        ?: fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                    edgeResponse.header("Content-Type") ?: "application/json",
                    java.io.ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            decision.status = "Battery_Proxy_Error"
            DecisionLogger.finalizeAndWrite(decision)
            android.util.Log.e("ProxyServer", "Battery request failed", e)
            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error: ${e.message}"
            )
        } finally {
        }
    }

    private fun handleFileDownloadRequest(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val edge = edgeRegistry.getBestEdge(TaskType.SHORT)
            ?: return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "No edge servers available for file download"
            )

        // 2. Capture Client Request ID for end-to-end traceability
        val clientRequestId = session.headers["x-client-request-id"] ?: "unknown"

        // 3. Create initial decision record (Captures timestampOfReceivingRequest)
        val decision = DecisionLogger.createRecord(session.uri, edge, clientRequestId)

        // Construct the full URL, including the query string (e.g., ?file=test.txt)
        val queryString = if (session.queryParameterString != null) "?${session.queryParameterString}" else ""
        val edgeUrl = "http://${edge.ip}:8080${session.uri}$queryString"

        logger("Forwarding download request to edge: $edgeUrl")

        return try {
            val requestBuilder = Request.Builder()
                .url(edgeUrl)
                .header("X-Client-Request-ID", clientRequestId)

            session.headers["authorization"]?.let {
                requestBuilder.header("Authorization", it)
            }

            // 4. RECORD FORWARDING TIMESTAMP (Proxy -> Edge)
            decision.timestampOfForwardingRequest = java.lang.System.currentTimeMillis()

            client.newCall(requestBuilder.build()).execute().use { edgeResponse ->

                // 5. RECORD EDGE RESPONSE TIMESTAMP (Edge -> Proxy headers received)
                decision.timestampOfReceivingEdgeResponse = java.lang.System.currentTimeMillis()

                if (!edgeResponse.isSuccessful) {
                    decision.status = "Download_Error_${edgeResponse.code}"
                    decision.timestampOfSendingResponse = java.lang.System.currentTimeMillis()
                    DecisionLogger.finalizeAndWrite(decision)
                    return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.lookup(
                            edgeResponse.code
                        ), "text/plain", "Error"
                    )
                }

                // Store and Forward
                val fileBytes = edgeResponse.body?.bytes() ?: ByteArray(0)
                decision.fileSizeBytes = fileBytes.size.toLong()

                decision.timestampOfSendingResponse = java.lang.System.currentTimeMillis()
                val totalRtt = decision.rttMs ?: 0L

                decision.status = "Success"
                edgeRegistry.updateRtt(edge.ip, totalRtt, TaskType.SHORT)

                DecisionLogger.finalizeAndWrite(decision)

                val mime = edgeResponse.header("Content-Type") ?: "application/octet-stream"

                // FORWARD: Send the byte array to the client
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                    mime,
                    java.io.ByteArrayInputStream(fileBytes),
                    fileBytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            decision.status = "Download_Proxy_Error"
            DecisionLogger.finalizeAndWrite(decision)

            logger("Error forwarding /download request")
            android.util.Log.e("ProxyServer", "Error forwarding /download request", e)

            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Proxy Error: ${e.message}"
            )
        } finally {
        }
    }

    private fun handleForwardingRequest(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val edge = edgeRegistry.getBestEdge(TaskType.LONG)
            ?: return fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "No edge"
            )

        val edgeUrl = "http://${edge.ip}:8080${session.uri}"

        val clientRequestId = session.headers["x-client-request-id"] ?: "unknown"
        val decision = DecisionLogger.getRecord(clientRequestId)

        val request = Request.Builder()
            .url(edgeUrl)
            .get()
            .build()

        return client.newCall(request).execute().use { edgeResponse ->
            // RECORD RECEIVING FROM EDGE TIMESTAMP
            decision?.timestampOfReceivingEdgeResponse = java.lang.System.currentTimeMillis()

            val body = edgeResponse.body?.bytes() ?: ByteArray(0)

            if (edgeResponse.code == 200 && decision != null) {
                decision.status = "Success"
                DecisionLogger.finalizeAndWrite(decision) // This sets timestampOfSendingResponse
            } else if (edgeResponse.code >= 400 && decision != null) {
                decision.status = "Final_Error_${edgeResponse.code}"
                DecisionLogger.finalizeAndWrite(decision)
            }

            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.lookup(edgeResponse.code)
                    ?: fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                edgeResponse.header("Content-Type") ?: "application/json",
                java.io.ByteArrayInputStream(body),
                body.size.toLong()
            )
        }
    }

}

