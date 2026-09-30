package com.memeocr.app.data

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PhotoDao {
    // instr is literal: % and _ supplied by the user are never SQL wildcards.
    @Query("""SELECT * FROM photos WHERE accessible = 1
        AND (:query = '' OR ((ocrStatus = 'indexed' OR (ocrStatus = 'pending' AND indexedAt > 0)) AND instr(normalizedText, :query) > 0))
        ORDER BY dateAdded DESC, mediaStoreId DESC, contentUri DESC""")
    abstract fun search(query: String): PagingSource<Int, PhotoEntity>

    @Query("""SELECT COUNT(*) FROM photos WHERE accessible = 1 AND
        (:query = '' OR ((ocrStatus = 'indexed' OR (ocrStatus = 'pending' AND indexedAt > 0)) AND instr(normalizedText, :query) > 0))""")
    abstract fun observeResultCount(query: String): Flow<Int>

    @Query("""SELECT COUNT(*) AS total,
        COALESCE(SUM(CASE WHEN ocrStatus = 'indexed' THEN 1 ELSE 0 END), 0) AS `indexed`,
        COALESCE(SUM(CASE WHEN ocrStatus = 'pending' THEN 1 ELSE 0 END), 0) AS pending,
        COALESCE(SUM(CASE WHEN ocrStatus = 'failed' THEN 1 ELSE 0 END), 0) AS failed
        FROM photos WHERE accessible = 1""")
    abstract fun observeStats(): Flow<PhotoStats>

    @Query("SELECT * FROM photos WHERE contentUri IN (:uris)")
    abstract suspend fun byUris(uris: List<String>): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE contentUri = :uri")
    abstract suspend fun byUri(uri: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE accessible = 1 AND ocrStatus = 'pending' ORDER BY dateAdded DESC LIMIT :limit")
    abstract suspend fun pending(limit: Int): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE accessible = 1 AND ocrStatus = 'pending'")
    abstract suspend fun pendingCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(rows: List<PhotoEntity>)

    @Transaction
    open suspend fun mergeMedia(rows: List<PhotoEntity>) {
        if (rows.isEmpty()) return
        val existing = byUris(rows.map { it.contentUri }).associateBy { it.contentUri }
        put(rows.map { row ->
            existing[row.contentUri]?.takeIf { it.sameVersion(row) }?.let {
                it.copy(dateAdded = row.dateAdded, mimeType = row.mimeType,
                    accessible = true, seenEpoch = row.seenEpoch)
            } ?: row
        })
    }

    @Query("UPDATE photos SET seenEpoch = :epoch, accessible = 1 WHERE contentUri IN (:uris)")
    abstract suspend fun markVisible(uris: List<String>, epoch: Long)

    @Query("UPDATE photos SET accessible = 0 WHERE volumeName = :volume AND seenEpoch != :epoch")
    abstract suspend fun hideMissing(volume: String, epoch: Long)

    @Query("DELETE FROM photos WHERE volumeName = :volume AND seenEpoch != :epoch")
    abstract suspend fun deleteMissing(volume: String, epoch: Long)

    @Query("UPDATE photos SET accessible = 0 WHERE volumeName NOT IN (:volumes)")
    abstract suspend fun hideAbsentVolumes(volumes: List<String>)

    @Query("UPDATE photos SET accessible = 0")
    abstract suspend fun hideAll()

    @Query("UPDATE photos SET ocrStatus = 'pending', ocrText = '', normalizedText = '', indexedAt = 0, error = '' WHERE volumeName = :volume")
    abstract suspend fun invalidateVolume(volume: String)

    @Query("SELECT * FROM media_sync WHERE volumeName = :volume")
    abstract suspend fun syncState(volume: String): MediaSyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSyncState(state: MediaSyncState)

    @Transaction
    open suspend fun finishSync(state: MediaSyncState, epoch: Long) {
        if (state.partial) hideMissing(state.volumeName, epoch)
        else deleteMissing(state.volumeName, epoch)
        putSyncState(state)
    }

    // Version guards prevent an obsolete result overwriting a newly discovered version.
    @Query("""UPDATE photos SET ocrText = :text, normalizedText = :normalized,
        ocrStatus = :status, error = :error, indexedAt = :now
        WHERE contentUri = :uri AND sizeBytes = :size AND dateModified = :modified
        AND mediaGeneration = :generation""")
    abstract suspend fun finishOcr(uri: String, size: Long, modified: Long, generation: Long,
        text: String, normalized: String, status: String, error: String, now: Long): Int

    @Query("UPDATE photos SET ocrStatus = 'pending', error = '' WHERE ocrStatus = 'failed' AND accessible = 1")
    abstract suspend fun retryFailed()

    @Query("UPDATE photos SET accessible = 0 WHERE contentUri = :uri")
    abstract suspend fun hideUri(uri: String)
}
