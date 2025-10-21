package com.example.android_helloworld.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    // Finds a user by their username. Returns null if not found.
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    fun findByUsername(username: String): User?

    // Inserts a new user. Ignores the insert if the username already exists.
    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    suspend fun insert(user: User)

    // Inserts a new recognition result
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecognitionResult(result: RecognitionResult)

    // Use Flow to automatically update UI when the recognition model is used
    @Query("SELECT * FROM recognition_results ORDER BY timestamp DESC")
    fun getAllRecognitionResults(): Flow<List<RecognitionResult>>

    // Delete history
    @Query("DELETE FROM recognition_results")
    suspend fun clearAllResults()
}