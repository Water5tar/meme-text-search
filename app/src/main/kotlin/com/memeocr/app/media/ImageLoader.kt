package com.memeocr.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.util.LruCache
import java.io.InputStream

/** Read-only, bounded decoding. Animated images use their first frame. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun decode(open: () -> InputStream?, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = open() ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = try {
            open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } catch (_: Exception) { 1 }
        val transform = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        if (transform.isIdentity) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun decodeForOcr(open: () -> InputStream?): Bitmap? = decode(open, 2560)
    fun thumbnail(key: String, open: () -> InputStream?): Bitmap? {
        cache.get(key)?.let { return it }
        val bitmap = decode(open, 384) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}
