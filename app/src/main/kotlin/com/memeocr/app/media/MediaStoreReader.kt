package com.memeocr.app.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.memeocr.core.Album
import com.memeocr.core.MediaItem

/**
 * MediaStore 只读查询。不写入、不修改任何媒体。
 */
class MediaStoreReader(private val context: Context) {

    companion object {
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
    }

    /** 列出有图片的相册（含数量），按名称排序。 */
    fun listAlbums(): List<Album> {
        val counts = LinkedHashMap<Pair<String, String>, Int>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx) ?: id
                val key = id to name
                counts[key] = (counts[key] ?: 0) + 1
            }
        } ?: return emptyList()
        return counts.map { (key, count) -> Album(key.first, key.second, count) }
            .sortedWith(compareByDescending<Album> { it.imageCount }.thenBy { it.name })
    }

    /** 列出某相册全部图片元数据（顺序稳定：按日期修改时间倒序），供批次选择。 */
    fun listImages(bucketId: String? = null): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val selection = bucketId?.let { "${MediaStore.Images.Media.BUCKET_ID} = ?" }
        val args = bucketId?.let { arrayOf(it) }
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            selection, args,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media._ID} DESC"
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mtimeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                out.add(
                    MediaItem(
                        uri = uri.toString(),
                        size = c.getLong(sizeIdx),
                        lastModified = c.getLong(mtimeIdx),
                    )
                )
            }
        }
        return out
    }

    /** 查询单个 URI 的当前元数据；媒体已删除/不可访问时返回 null。 */
    fun statUri(uriStr: String): MediaItem? {
        val uri = Uri.parse(uriStr)
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_MODIFIED),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mtimeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            return MediaItem(uriStr, c.getLong(sizeIdx), c.getLong(mtimeIdx))
        }
        return null
    }

    /** 只读打开解码用输入流。调用方负责关闭。 */
    fun openInputStream(uriStr: String) = context.contentResolver.openInputStream(Uri.parse(uriStr))
}
