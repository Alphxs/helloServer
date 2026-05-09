package com.example.android_helloworld

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class MetricsLogger(context: Context) {
    private val csvOutputFile = File(context.getExternalFilesDir(null), "recognition_metrics.csv")
    private val csvLock = Any()

    fun getDetailedTimestamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    fun logToCsv(clientId: String, received: String, start: String, end: String, sent: String, waitTimeMs: String) {
        synchronized(csvLock) {
            try {
                val fileExists = csvOutputFile.exists()
                FileWriter(csvOutputFile, true).use { writer ->
                    if (!fileExists) {
                        writer.append("ID,Request_Received,Recognition_Start,Recognition_End,Response_Sent\n")
                    }
                    writer.append("$clientId,$received,$start,$end,$sent,$waitTimeMs\n")
                }
            } catch (e: Exception) {
                Log.e("MetricsLogger", "CSV Write Error: ${e.message}")
            }
        }
    }
}