package com.example.android_helloworld.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// A helper object to safely load a bitmap from a file path.
object BitmapLoader {
    /**
     * Loads a bitmap from a given file path.
     * Uses `remember` to avoid reloading the image on every recomposition.
     */
    @Composable
    fun loadBitmapFromFile(path: String): Bitmap? {
        return remember(path) { // Key the remember to the path
            try {
                BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                Log.e("BitmapLoader", "Failed to load bitmap from path: $path", e)
                null // Return null if loading fails
            }
        }
    }
}
