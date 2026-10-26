package com.memeocr.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PhotoEntity::class, MediaSyncState::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photos(): PhotoDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Revisit previously recognized photos once so the new geometry can be indexed.
                // Keep their old text searchable until each replacement has finished.
                db.execSQL("UPDATE photos SET ocrStatus = 'pending' WHERE ocrStatus = 'indexed'")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, "photo-index.db"
        ).addMigrations(MIGRATION_1_2).build()
    }
}
