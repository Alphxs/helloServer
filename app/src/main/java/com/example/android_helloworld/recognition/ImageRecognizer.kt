package com.example.android_helloworld.recognition

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import com.example.android_helloworld.ProxyNotifier
import com.example.android_helloworld.Prediction
import com.example.android_helloworld.db.RecognitionResult
import com.example.android_helloworld.db.UserDao
import com.google.gson.Gson
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class ImageRecognizer(
    private val context: Context,
    private val userDao: UserDao,
    private val proxyNotifier: ProxyNotifier
): Closeable {

    private val gson = Gson()
    private val objectDetector: ObjectDetector
    private val csvFile = File(context.getExternalFilesDir(null), "heap_profile_log.csv")

    init {
        ensureCsvHeader()
        Log.i("ImageRecognizer", "Initializing ObjectDetector...")
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(5)
            .setScoreThreshold(0.5f)
            .build()
        objectDetector = ObjectDetector.createFromFileAndOptions(
            context,
            "model_detection.tflite",
            options
        )
        Log.i("ImageRecognizer", "ObjectDetector initialized successfully.")
    }

    override fun close() {
        objectDetector?.close()
        Log.i("ImageRecognizer", "ObjectDetector has been closed.")
    }

    suspend fun processImage(permanentImageFile: File): String {
        // Milestone 1: After decoding the file
        val bitmap = BitmapFactory.decodeFile(permanentImageFile.absolutePath)
        recordMilestone("AFTER_DECODE")

        if (bitmap == null) {
            throw IOException("Failed to decode the image file.")
        }

        try {
            // Milestone 2: After creating the TensorImage
            val tensorImage = TensorImage.fromBitmap(bitmap)
            recordMilestone("AFTER_TENSOR")

            // Milestone 3: After Native Detection (The heaviest part)
            val results: List<Detection> = objectDetector.detect(tensorImage)
            recordMilestone("AFTER_DETECT")

            val predictions = results.flatMap { detection ->
                detection.categories.map { category ->
                    Prediction(category.label, category.score)
                }
            }

            val jsonResponse = gson.toJson(predictions)

            val recognizedObjectsStr = predictions.joinToString(", ") { it.label }
            if (recognizedObjectsStr.isNotEmpty()) {
                val recognitionResult = RecognitionResult(
                    timestamp = System.currentTimeMillis(),
                    imagePath = permanentImageFile.absolutePath,
                    recognizedObjects = recognizedObjectsStr
                )
                userDao.insertRecognitionResult(recognitionResult)
            }

            return jsonResponse

        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Captures a snapshot of the memory and writes it to the CSV.
     * Uses Dalvik PSS to match the Android Studio Profiler "Java" bar.
     */
    private fun recordMilestone(event: String) {
        try {
            val runtime = Runtime.getRuntime()
            val maxMb = runtime.maxMemory() / (1024.0 * 1024.0)
            val jvmUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)

            // Capture Physical Java Memory (includes ART overhead)
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)

            val profilerJavaMb = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                // 'summary.java' is exactly what the Profiler's Java bar shows
                (memInfo.getMemoryStat("summary.java")?.toDouble() ?: memInfo.dalvikPss.toDouble()) / 1024.0
            } else {
                memInfo.dalvikPss.toDouble() / 1024.0
            }

            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val line = "$timestamp,${"%.2f".format(maxMb)},${"%.2f".format(jvmUsedMb)},${"%.2f".format(profilerJavaMb)},$event"

            writeToProfileCsv(line)
        } catch (e: Exception) {
            Log.e("HeapProfiler", "Error recording milestone: ${e.message}")
        }
    }

    private fun ensureCsvHeader() {
        if (!csvFile.exists()) {
            writeToProfileCsv("Timestamp,Max_Limit_MB,JVM_Used_MB,Profiler_Java_MB,Event")
        }
    }

    private fun writeToProfileCsv(line: String) {
        try {
            // We open, flush, and close immediately to ensure data persists through a crash
            val writer = FileWriter(csvFile, true)
            writer.append("$line\n")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.e("HeapProfiler", "CSV Write Error: ${e.message}")
        }
    }
}