package com.stegoapp.app.data.local.dao

import androidx.room.*
import com.stegoapp.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY createdAt ASC")
    fun getByContact(contactId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET revoked = 1, content = '' WHERE id = :id")
    suspend fun markRevoked(id: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("""
        SELECT * FROM messages WHERE id IN (
            SELECT id FROM messages GROUP BY contactId
            HAVING createdAt = MAX(createdAt)
        )
    """)
    fun getLastMessages(): Flow<List<MessageEntity>>
}
