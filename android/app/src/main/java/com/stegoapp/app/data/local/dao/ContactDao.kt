package com.stegoapp.app.data.local.dao

import androidx.room.*
import com.stegoapp.app.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY addedAt DESC")
    fun getAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    suspend fun getById(userId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE userId = :userId")
    suspend fun deleteById(userId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}
