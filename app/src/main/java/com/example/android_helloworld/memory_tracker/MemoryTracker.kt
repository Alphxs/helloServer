package com.example.android_helloworld

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

class MemoryTracker(private val context: Context, private val onSaturationChanged: (Int) -> Unit) {
    private val MEMORY_THRESHOLD_BYTES = 20L * 1024 * 1024
    private val isOldAndroid = Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1

    private var playgroundMem: Long = 0
    private val currentReservedJavaMem = AtomicLong(0)

    @Volatile var isSaturated = false
        private set

    init {
        val runtime = Runtime.getRuntime()
        playgroundMem = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

        CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                checkMemoryPressure()
                delay(500)
            }
        }
    }

    // Helper function to retrieve current available system RAM
    fun getAvailableNativeMem(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem - mi.threshold
    }

    fun getRemainingJavaRoom() = playgroundMem - currentReservedJavaMem.get()

    // Monitors currentJavaRoom.
    // If it reached the threshold, set maxConcurrentThreads to 0
    // Else if it is okay again (below threshold), set maxConcurrentThreads to its original value 5
    private fun checkMemoryPressure() {
        val javaRoom = getRemainingJavaRoom()
        val nativeRoom = getAvailableNativeMem()

        val needsStop = javaRoom <= MEMORY_THRESHOLD_BYTES || nativeRoom <= MEMORY_THRESHOLD_BYTES
        val canResume = javaRoom > MEMORY_THRESHOLD_BYTES && nativeRoom > MEMORY_THRESHOLD_BYTES

        if (needsStop && !isSaturated) {
            isSaturated = true
            onSaturationChanged(0)
        } else if (canResume && isSaturated) {
            isSaturated = false
            onSaturationChanged(-1) // Signal to restore base limit
        }
    }

    // IMPORTANT: Formula for computing the allocated memory of a single image

    fun predictMemory(path: String): Pair<Long, Long> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        val w = options.outWidth.toLong()
        val h = options.outHeight.toLong()
        val arena = 3.9 * 1024 * 1024

        return if (isOldAndroid) {
            // Java: decode(w*h*4) + detect(w*h*4 + w*h*3)
            val java = (w * h * 4) + (w * h * 4) + (w * h * 3)
            // Native: detectNative(w*h*4 + arena) + decode(w*h*3)
            val native = (w * h * 4 + arena.toLong()) + (w * h * 3)
            Pair(java, native) // Logic as per your 7.1 requirement
        } else {
            // Java: detect(w*h*4 + w*h*3)
            val java = (w * h * 4) + (w * h * 3)
            // Native: detectNative(w*h*4 + arena) + decode((w*h*4) + (w*h*3))
            val native = ((w * h * 4) + arena.toLong()) + (w * h * 4) + (w * h * 3)
            Pair(java, native)
        }
    }

    fun tryReserve(javaMem: Long, nativeMem: Long): Boolean {
        if (javaMem > getRemainingJavaRoom() || nativeMem > getAvailableNativeMem()) return false
        currentReservedJavaMem.addAndGet(javaMem)
        return true
    }

    fun release(javaMem: Long) {
        currentReservedJavaMem.addAndGet(-javaMem)
    }
}