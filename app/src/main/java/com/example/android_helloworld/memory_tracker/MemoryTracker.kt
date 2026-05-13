package com.example.android_helloworld

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import java.util.concurrent.atomic.AtomicLong

class MemoryTracker(private val context: Context) {
    private val isOldAndroid = Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1

    private var playgroundMem: Long = 0
    // Not used anymore. Currently using semaphore
//    private val currentReservedJavaMem = AtomicLong(0)

    init {
        val runtime = Runtime.getRuntime()
        // playgroundMem is the absolute ceiling of the JVM minus currently occupied space
        playgroundMem = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    }

    fun getInitialPlaygroundMem(): Int {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val availableBytes = maxHeap - usedHeap
        return (availableBytes).toInt()
    }

    /**
     * Retrieves current available system RAM minus the LMK threshold.
     */
    fun getAvailableNativeMem(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem - mi.threshold
    }

    /**
     * Calculates the memory impact based on RTTms formulas for different Android versions.
     */
    fun calculateMemory(path: String): Pair<Long, Long> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        val w = options.outWidth.toLong()
        val h = options.outHeight.toLong()
        val arena = 3.9 * 1024 * 1024

        return if (isOldAndroid) {
            // Android 7.1 and lower
            val java = (w * h * 4) + (w * h * 4) + (w * h * 3)
            val native = (w * h * 4 + arena.toLong()) + (w * h * 3)
            Pair(java, native)
        } else {
            // Android 8.0+
            val java = (w * h * 4) + (w * h * 3)
            val native = ((w * h * 4) + arena.toLong()) + (w * h * 4) + (w * h * 3)
            Pair(java, native)
        }
    }

    /**
     * Performs a non-blocking check to see if an image fits.
     * Used to trigger the 'BUSY' status notification.
     */
//    fun canFit(javaMem: Long, nativeMem: Long): Boolean {
//        return javaMem <= getRemainingJavaRoom() && nativeMem <= getAvailableNativeMem()
//    }

    /**
     * Atomically reserves memory if it fits.
     */
//    fun tryReserve(javaMem: Long, nativeMem: Long): Boolean {
//        if (javaMem > getRemainingJavaRoom() || nativeMem > getAvailableNativeMem()) return false
//        currentReservedJavaMem.addAndGet(javaMem)
//        return true
//    }

    /**
     * Releases the reserved Java memory once the recognition is done.
     */
//    fun release(javaMem: Long) {
//        currentReservedJavaMem.addAndGet(-javaMem)
//    }

    /**
     * Returns the remaining capacity in the Java Heap (Playground Memory).
     */
//    fun getRemainingJavaRoom() = playgroundMem - currentReservedJavaMem.get()
}