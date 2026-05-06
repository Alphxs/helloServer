//package com.example.android_helloworld
//
//import android.util.Log
//import kotlinx.coroutines.*
//import kotlinx.coroutines.channels.Channel
//
///**
// * A thread-safe singleton queue to process image recognition tasks sequentially.
// * This prevents crashes from multiple threads accessing the TFLite model simultaneously.
// */
//object RecognitionTaskQueue {
//
//    // A coroutine channel acts as our thread-safe queue.
//    private val queue = Channel<RecognitionTask>(Channel.UNLIMITED)
//
//    // The single instance of our image recognizer.
//    private lateinit var recognizer: ImageRecognizer
//
//    // The dedicated coroutine scope for the background processing worker.
//    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
//    private var workerJob: Job? = null
//
//    /**
//     * Initializes the queue and starts the background worker.
//     * This MUST be called once when the server starts.
//     */
//    fun start(imageRecognizerInstance: ImageRecognizer) {
//        if (workerJob?.isActive == true) {
//            Log.w("RecognitionTaskQueue", "Worker is already running.")
//            return
//        }
//        recognizer = imageRecognizerInstance
//        workerJob = scope.launch {
//            Log.i("RecognitionTaskQueue", "Worker started. Waiting for tasks.")
//            // This loop will continuously listen for new tasks on the channel.
//            for (task in queue) {
//                processTask(task)
//            }
//        }
//    }
//
//    /**
//     * Stops the background worker and clears the queue.
//     */
//    fun stop() {
//        workerJob?.cancel()
//        queue.close()
//        Log.i("RecognitionTaskQueue", "Worker stopped.")
//    }
//
//    /**
//     * Adds a new task to the queue for processing.
//     */
//    suspend fun submitTask(task: RecognitionTask) {
//        queue.send(task)
//    }
//
//    /**
//     * The actual processing logic that runs sequentially in the background.
//     */
//    private suspend fun processTask(task: RecognitionTask) {
//        Log.i("RecognitionTaskQueue", "Processing task for image: ${task.imageFile.name}")
//        try {
//            // This is the part that is NOT thread-safe.
//            // By running it here, we ensure it only runs on one thread at a time.
//            val resultsJson = recognizer.processImage(task.imageFile)
//            task.onComplete(resultsJson) // Call the success callback
//            Log.i("RecognitionTaskQueue", "Task completed successfully.")
//        } catch (e: Exception) {
//            Log.e("RecognitionTaskQueue", "Error processing task", e)
//            task.onError("Error processing image: ${e.message}") // Call the error callback
//        } finally {
//            // The image file is permanent, so we no longer delete it here.
//            // Cleanup is handled by the server if needed, but results point to it.
//        }
//    }
//}
