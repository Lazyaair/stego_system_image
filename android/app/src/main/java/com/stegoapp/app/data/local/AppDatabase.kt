package com.stegoapp.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.stegoapp.app.data.local.dao.BlacklistDao
import com.stegoapp.app.data.local.dao.ContactDao
import com.stegoapp.app.data.local.dao.MessageDao
import com.stegoapp.app.data.local.entity.BlacklistEntity
import com.stegoapp.app.data.local.entity.ContactEntity
import com.stegoapp.app.data.local.entity.MessageEntity

@Database(
    entities = [ContactEntity::class, MessageEntity::class, BlacklistEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun blacklistDao(): BlacklistDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stego-app.db"
                ).build().also { INSTANCE = it }
            }
    }
}
