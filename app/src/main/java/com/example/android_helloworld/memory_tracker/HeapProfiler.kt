package com.example.android_helloworld

import android.content.Context
import android.os.Debug
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class HeapProfiler(private val context: Context) {
    private val csvFile = File(context.getExternalFilesDir(null), "heap_profile_log.csv")
    private var isRunning = false
    private var profilingThread: Thread? = null

    fun startProfiling(intervalMs: Long = 50) {
        if (isRunning) return
        isRunning = true

        setupCrashHandler()

        if (!csvFile.exists()) {
            writeLine("Timestamp,Max_Limit_MB,JVM_Used_MB,Profiler_Java_MB,Total_App_PSS_MB,Event")
        }

        profilingThread = thread(start = true, priority = Thread.MAX_PRIORITY, name = "HeapWatchdog") {
            val memInfo = Debug.MemoryInfo()
            while (isRunning) {
                val startTime = System.currentTimeMillis()
                try {
                    recordMemory(memInfo, "LOG")

                    // CALCULATE REMAINING SLEEP
                    // This subtracts the time spent doing IO from the interval
                    val workTime = System.currentTimeMillis() - startTime
                    val sleepTime = (intervalMs - workTime).coerceAtLeast(1)

                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        Log.i("HeapProfiler", "Persistent Profiler started with adaptive interval.")
    }

    private fun recordMemory(memInfo: Debug.MemoryInfo, event: String) {
        val runtime = Runtime.getRuntime()

        // JVM Logical Metrics
        val max = runtime.maxMemory() / (1024.0 * 1024.0)
        val total = runtime.totalMemory() / (1024.0 * 1024.0)
        val free = runtime.freeMemory() / (1024.0 * 1024.0)
        val jvmUsed = total - free

        // Physical Metrics (Matching the Profiler)
        Debug.getMemoryInfo(memInfo)

        // Use summary.java (this is exactly what the Profiler Java bar uses)
        val profilerJavaKb = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            memInfo.getMemoryStat("summary.java")?.toDouble() ?: memInfo.dalvikPss.toDouble()
        } else {
            memInfo.dalvikPss.toDouble()
        }

        val profilerJavaMb = profilerJavaKb / 1024.0
        val totalPssMb = memInfo.totalPss / 1024.0
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        val line = "$timestamp,${"%.2f".format(max)},${"%.2f".format(jvmUsed)},${"%.2f".format(profilerJavaMb)},${"%.2f".format(totalPssMb)},$event"
        writeLine(line)
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // --- THE FINAL SNAPSHOT ---
            // This code runs AFTER the crash happens but BEFORE the process is killed
            try {
                recordMemory(Debug.MemoryInfo(), "CRASH_OOM")
                Log.e("HeapProfiler", "Captured final memory state before crash.")
            } catch (e: Exception) {
                // Ignore errors during crash
            }

            // Pass the crash back to the system
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun stopProfiling() {
        isRunning = false
        profilingThread?.interrupt()
    }

    private fun writeLine(line: String) {
        try {
            // 3. HARDWARE PERSISTENCE
            // Using FileOutputStream and FileDescriptor.sync()
            // This forces the disk hardware to write the data NOW.
            val fos = FileOutputStream(csvFile, true)
            fos.write("$line\n".toByteArray())
            fos.flush()

            // Force the physical hardware to sync
            fos.fd.sync()

            fos.close()
        } catch (e: Exception) {
            Log.e("HeapProfiler", "Write Error: ${e.message}")
        }
    }
}