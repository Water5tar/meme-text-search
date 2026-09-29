package com.memeocr.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object OcrStatus {
    const val PENDING = "pending"
    const val INDEXED = "indexed"
    const val FAILED = "failed"
}

@Entity(tableName = "photos", indices = [
    Index(value = ["volumeName", "mediaStoreId"], unique = true),
    Index(value = ["accessible", "ocrStatus", "dateAdded"])
])
data class PhotoEntity(
    @PrimaryKey val contentUri: String,
    val mediaStoreId: Long,
    val volumeName: String,
    val dateAdded: Long,
    val dateModified: Long,
    val sizeBytes: Long,
    val mediaGeneration: Long = -1,
    val mimeType: String = "image/jpeg",
    val ocrText: String = "",
    val normalizedText: String = "",
    val indexedAt: Long = 0,
    val ocrStatus: String = OcrStatus.PENDING,
    val error: String = "",
    val accessible: Boolean = true,
    val seenEpoch: Long = 0
) {
    fun sameVersion(other: PhotoEntity) =
        contentUri == other.contentUri && sizeBytes == other.sizeBytes &&
        dateModified == other.dateModified && mediaGeneration == other.mediaGeneration
}

@Entity(tableName = "media_sync")
data class MediaSyncState(
    @PrimaryKey val volumeName: String,
    val storeVersion: String,
    val generation: Long,
    val syncedAtSeconds: Long,
    val partial: Boolean
)

data class PhotoStats(val total: Int, val indexed: Int, val pending: Int, val failed: Int) {
    companion object { val EMPTY = PhotoStats(0, 0, 0, 0) }
}

