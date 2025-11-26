package com.example.android_helloworld

import java.io.File

/**
 * A data class to hold all the necessary information for a single
 * image recognition task.
 */
data class RecognitionTask(val imageFile: File,
                           val onComplete: (String) -> Unit,
                           val onError: (String) -> Unit
)
