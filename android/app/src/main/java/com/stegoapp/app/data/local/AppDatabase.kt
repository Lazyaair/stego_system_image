package com.stegoapp.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stegoapp.app.data.local.dao.BlacklistDao
import com.stegoapp.app.data.local.dao.ContactDao
import com.stegoapp.app.data.local.dao.MessageDao
import com.stegoapp.app.data.local.dao.UserSettingsDao
import com.stegoapp.app.data.local.entity.BlacklistEntity
import com.stegoapp.app.data.local.entity.ContactEntity
import com.stegoapp.app.data.local.entity.MessageEntity
import com.stegoapp.app.data.local.entity.UserSettingsEntity

@Database(
    entities = [
        ContactEntity::class,
        MessageEntity::class,
        BlacklistEntity::class,
        UserSettingsEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun blacklistDao(): BlacklistDao
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2: 为 contacts 增加 E2EE 对方助记词相关三列,并新建 user_settings 表。
         * 列名沿用 ContactEntity 的 Kotlin 属性名 (camelCase,没有 @ColumnInfo 覆盖)。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN peerPhrase TEXT")
                db.execSQL("ALTER TABLE contacts ADD COLUMN peerUserKeyHex TEXT")
                db.execSQL("ALTER TABLE contacts ADD COLUMN peerFingerprintHex TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        phrase TEXT NOT NULL,
                        userKeyHex TEXT NOT NULL,
                        fingerprintHex TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stego-app.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
