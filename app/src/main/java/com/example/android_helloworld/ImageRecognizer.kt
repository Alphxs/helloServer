package com.example.android_helloworld

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.example.android_helloworld.db.UserDao
import com.google.gson.Gson
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import java.io.IOException
import java.io.Closeable

/**
 * Encapsulates the logic for image recognition. It loads the model and provides
 * a method to perform detection on an image file. This class is NOT thread-safe
 * on its own and should be used by a queuing mechanism like RecognitionTaskQueue.
 */
class ImageRecognizer(context: Context, private val userDao: UserDao): Closeable {

    private val gson = Gson()
    private val objectDetector: ObjectDetector

    init {
        Log.i("ImageRecognizer", "Initializing ObjectDetector...")
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(5)
            .setScoreThreshold(0.5f)
            .build()
        objectDetector = ObjectDetector.createFromFileAndOptions(
            context,
            "model_detection.tflite", // Ensure this model is in app/src/main/assets
            options
        )
        Log.i("ImageRecognizer", "ObjectDetector initialized successfully.")

        }
    override fun close() {
        objectDetector?.close()
        Log.i("ImageRecognizer", "ObjectDetector has been closed.")
    }

    /**
     * Processes a single image file, runs detection, saves the result, and returns a JSON string.
     * This method is NOT thread-safe and should only be called from a single thread at a time.
     */
    suspend fun processImage(permanentImageFile: File): String {
        val options = BitmapFactory.Options().apply {
            // Read dimensions only (no memory used)
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(permanentImageFile.absolutePath, this)

            // Calculate a sample size to ensure the bitmap isn't massive.
            // A value of 4 means 1/4 width and 1/4 height (1/16th total pixels).
            inSampleSize = calculateInSampleSize(this, 1024, 1024)
            inJustDecodeBounds = false
        }

        val bitmap = BitmapFactory.decodeFile(permanentImageFile.absolutePath, options)
            ?: throw IOException("Failed to decode the image file.")

        try {
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val results: List<Detection> = objectDetector.detect(tensorImage)

            val predictions = results.flatMap { detection ->
                detection.categories.map { category ->
                    Prediction(category.label, category.score)
                }
            }

            val jsonResponse = gson.toJson(predictions)
            val recognizedObjectsStr = predictions.joinToString(", ") { it.label }

            if (recognizedObjectsStr.isNotEmpty()) {
                val recognitionResult = com.example.android_helloworld.db.RecognitionResult(
                    timestamp = System.currentTimeMillis(),
                    imagePath = permanentImageFile.absolutePath,
                    recognizedObjects = recognizedObjectsStr
                )
                userDao.insertRecognitionResult(recognitionResult)
            }

            return jsonResponse
        } finally {
            // Manually free the memory immediately
            bitmap.recycle()
        }
    }

    // Helper to calculate how much to shrink the image
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
