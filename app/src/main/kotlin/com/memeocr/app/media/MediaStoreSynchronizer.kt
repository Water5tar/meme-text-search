package com.memeocr.app.media

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.memeocr.app.data.*
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Only metadata is scanned; bitmap decoding belongs to the durable OCR queue. */
class MediaStoreSynchronizer(private val context: Context, private val dao: PhotoDao) {
    private val resolver = context.contentResolver
    suspend fun sync() {
        val access = PhotoAccess.level(context)
        if (access == AccessLevel.NONE) { dao.hideAll(); return }
        val volumes = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.getExternalVolumeNames(context).toList() else listOf("external")
        if (volumes.isEmpty()) { dao.hideAll(); return }
        dao.hideAbsentVolumes(volumes)
        if (access == AccessLevel.PARTIAL) dao.hideAll()
        for (volume in volumes) {
            coroutineContext.ensureActive()
            val uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(volume)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val version = version(volume)
            val generation = generation(volume)
            val previous = dao.syncState(volume)
            if (previous != null && (previous.storeVersion != version || generation < previous.generation)) {
                dao.invalidateVolume(volume)
            }
            val epoch = System.currentTimeMillis()
            val full = previous == null || previous.storeVersion != version ||
                previous.partial != (access == AccessLevel.PARTIAL) || access == AccessLevel.PARTIAL ||
                generation < previous.generation
            val base = buildList {
                if (Build.VERSION.SDK_INT >= 29) add("is_pending = 0")
                if (Build.VERSION.SDK_INT >= 30) add("is_trashed = 0")
            }.joinToString(" AND ").ifEmpty { "1=1" }
            val filter = when {
                full -> base
                Build.VERSION.SDK_INT >= 30 -> "$base AND generation_modified > ${previous!!.generation}"
                else -> "$base AND (date_modified >= ${previous!!.syncedAtSeconds - 2} OR date_added >= ${previous.syncedAtSeconds - 2})"
            }
            readMetadata(uri, volume, epoch, filter)
            // An ID-only pass detects deletions and newly authorized older images without loading photos.
            resolver.query(uri, arrayOf("_id"), base, null, null)?.use { cursor ->
                val ids = mutableListOf<Long>()
                suspend fun flush() {
                    if (ids.isEmpty()) return
                    val uris = ids.map { Uri.withAppendedPath(uri, it.toString()).toString() }
                    val known = dao.byUris(uris).map { it.contentUri }.toSet()
                    val missing = ids.filterIndexed { i, _ -> uris[i] !in known }
                    if (missing.isNotEmpty()) readMetadata(uri, volume, epoch,
                        "$base AND _id IN (${missing.joinToString()})")
                    dao.markVisible(uris, epoch)
                    ids.clear()
                }
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    ids.add(cursor.getLong(0))
                    if (ids.size == 300) flush()
                }
                flush()
            } ?: error("MediaStore did not return an ID cursor")
            // A changing library or permission scope must not commit a deletion checkpoint.
            check(PhotoAccess.level(context) == access && version(volume) == version &&
                generation(volume) == generation) { "相册发生变化，稍后重试" }
            dao.finishSync(MediaSyncState(volume, version, generation,
                epoch / 1000, access == AccessLevel.PARTIAL), epoch)
        }
    }
    private fun version(volume: String): String = when {
        Build.VERSION.SDK_INT >= 30 -> MediaStore.getVersion(context, volume)
        Build.VERSION.SDK_INT >= 29 -> MediaStore.getVersion(context)
        else -> "legacy"
    }
    private fun generation(volume: String): Long = if (Build.VERSION.SDK_INT >= 30)
        MediaStore.getGeneration(context, volume) else -1
    private suspend fun readMetadata(uri: Uri, volume: String, epoch: Long, filter: String) {
        val projection = mutableListOf("_id", "date_added", "date_modified", "_size", "mime_type")
        if (Build.VERSION.SDK_INT >= 30) projection.add("generation_modified")
        resolver.query(uri, projection.toTypedArray(), filter, null, null)?.use { cursor ->
            val batch = ArrayList<PhotoEntity>(300)
            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()
                batch.add(PhotoEntity(
                    contentUri = Uri.withAppendedPath(uri, cursor.getLong(0).toString()).toString(),
                    mediaStoreId = cursor.getLong(0), volumeName = volume,
                    dateAdded = cursor.getLong(1), dateModified = cursor.getLong(2),
                    sizeBytes = cursor.getLong(3), mimeType = cursor.getString(4) ?: "image/*",
                    mediaGeneration = if (Build.VERSION.SDK_INT >= 30) cursor.getLong(5) else -1,
                    seenEpoch = epoch))
                if (batch.size == 300) { dao.mergeMedia(batch); batch.clear() }
            }
            if (batch.isNotEmpty()) dao.mergeMedia(batch)
        } ?: error("MediaStore did not return a metadata cursor")
    }
}
