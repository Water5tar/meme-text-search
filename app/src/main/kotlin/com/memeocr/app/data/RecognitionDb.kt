package com.memeocr.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.memeocr.core.MediaItem
import com.memeocr.core.RecognitionRecord
import com.memeocr.core.Status

/**
 * SQLite 持久层。
 * - 每张识别完成立即写入（单行 upsert），中断/进程被杀后已完成的结果仍在。
 * - 版本标识 = uri + size + lastModified，不依赖文件名。
 */
class RecognitionDb(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "recognition.db"
        const val DB_VERSION = 1

        private const val TABLE = "recognition"
        private const val COL_URI = "uri"
        private const val COL_SIZE = "size"
        private const val COL_MTIME = "last_modified"
        private const val COL_STATUS = "status"
        private const val COL_TEXT = "text"
        private const val COL_ERROR = "error"
        private const val COL_UPDATED = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                $COL_URI TEXT PRIMARY KEY NOT NULL,
                $COL_SIZE INTEGER NOT NULL,
                $COL_MTIME INTEGER NOT NULL,
                $COL_STATUS TEXT NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                $COL_ERROR TEXT NOT NULL,
                $COL_UPDATED INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_status ON $TABLE($COL_STATUS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 版本 1，暂无迁移
    }

    private fun RecognitionRecord.toValues(now: Long): ContentValues = ContentValues().apply {
        put(COL_URI, uri)
        put(COL_SIZE, size)
        put(COL_MTIME, lastModified)
        put(COL_STATUS, status.name)
        put(COL_TEXT, text)
        put(COL_ERROR, error)
        put(COL_UPDATED, now)
    }

    private fun cursorToRecord(
        uriIdx: Int, sizeIdx: Int, mtimeIdx: Int, statusIdx: Int, textIdx: Int, errIdx: Int, updIdx: Int,
        cursor: android.database.Cursor,
    ): RecognitionRecord = RecognitionRecord(
        uri = cursor.getString(uriIdx),
        size = cursor.getLong(sizeIdx),
        lastModified = cursor.getLong(mtimeIdx),
        status = Status.valueOf(cursor.getString(statusIdx)),
        text = cursor.getString(textIdx),
        error = cursor.getString(errIdx),
        updatedAt = cursor.getLong(updIdx),
    )

    /** 单条 upsert，识别一张存一张。 */
    @Synchronized
    fun upsert(record: RecognitionRecord) {
        val db = writableDatabase
        check(db.insertWithOnConflict(TABLE, null, record.toValues(record.updatedAt), SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存识别结果失败" }
    }

    /** 按 URI 查一条记录；不存在返回 null。 */
    @Synchronized
    fun findByUri(uri: String): RecognitionRecord? {
        val db = readableDatabase
        db.rawQuery("SELECT $COL_URI,$COL_SIZE,$COL_MTIME,$COL_STATUS,$COL_TEXT,$COL_ERROR,$COL_UPDATED FROM $TABLE WHERE $COL_URI = ?", arrayOf(uri)).use { c ->
            if (!c.moveToFirst()) return null
            return cursorToRecord(0, 1, 2, 3, 4, 5, 6, c)
        }
    }

    /** 全部记录（搜索用）。 */
    @Synchronized
    fun allRecords(): List<RecognitionRecord> {
        val db = readableDatabase
        val out = mutableListOf<RecognitionRecord>()
        db.rawQuery(
            "SELECT $COL_URI,$COL_SIZE,$COL_MTIME,$COL_STATUS,$COL_TEXT,$COL_ERROR,$COL_UPDATED FROM $TABLE ORDER BY $COL_UPDATED DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(cursorToRecord(0, 1, 2, 3, 4, 5, 6, c))
            }
        }
        return out
    }

    /** 已成功（DONE 或 EMPTY_TEXT）的 URI 集合，用于跳过判断。 */
    @Synchronized
    fun completedUris(): Set<String> {
        val db = readableDatabase
        val out = mutableSetOf<String>()
        db.rawQuery(
            "SELECT $COL_URI FROM $TABLE WHERE $COL_STATUS IN (?, ?)",
            arrayOf(Status.DONE.name, Status.EMPTY_TEXT.name)
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /** 失败记录数。 */
    @Synchronized
    fun failedCount(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE $COL_STATUS = ?", arrayOf(Status.FAILED.name)).use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    /** 统计各状态数量。 */
    @Synchronized
    fun countByStatus(): Map<Status, Int> {
        val db = readableDatabase
        val out = mutableMapOf<Status, Int>()
        db.rawQuery("SELECT $COL_STATUS, COUNT(*) FROM $TABLE GROUP BY $COL_STATUS", null).use { c ->
            while (c.moveToNext()) {
                out[Status.valueOf(c.getString(0))] = c.getInt(1)
            }
        }
        return out
    }

    /** 删除一条（媒体已不存在时清理）。 */
    @Synchronized
    fun deleteByUri(uri: String) {
        writableDatabase.delete(TABLE, "$COL_URI = ?", arrayOf(uri))
    }

    /** 失败重试：把 FAILED 行删掉，让它们重新进入待处理。 */
    @Synchronized
    fun clearFailed() {
        writableDatabase.delete(TABLE, "$COL_STATUS = ?", arrayOf(Status.FAILED.name))
    }
}

