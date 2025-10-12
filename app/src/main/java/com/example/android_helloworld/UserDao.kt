package com.example.android_helloworld.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserDao {
    // Finds a user by their username. Returns null if not found.
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    fun findByUsername(username: String): User?

    // Inserts a new user. Ignores the insert if the username already exists.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(user: User)
}
