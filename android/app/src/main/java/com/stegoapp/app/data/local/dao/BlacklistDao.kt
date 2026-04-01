package com.stegoapp.app.data.local.dao

import androidx.room.*
import com.stegoapp.app.data.local.entity.BlacklistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlacklistDao {
    @Query("SELECT * FROM blacklist")
    fun getAll(): Flow<List<BlacklistEntity>>

    @Query("SELECT COUNT(*) > 0 FROM blacklist WHERE userId = :userId")
    suspend fun isBlacklisted(userId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BlacklistEntity)

    @Query("DELETE FROM blacklist WHERE userId = :userId")
    suspend fun deleteById(userId: String)

    @Query("DELETE FROM blacklist")
    suspend fun deleteAll()
}
