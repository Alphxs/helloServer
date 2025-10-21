package com.example.android_helloworld.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recognition_results")
data class RecognitionResult(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    val imagePath: String,
    val recognizedObjects: String // e.g., "cat, dog, person"
)