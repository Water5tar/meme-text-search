package com.memeocr.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PhotoEntity::class, MediaSyncState::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photos(): PhotoDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, "photo-index.db"
        ).build()
    }
}

