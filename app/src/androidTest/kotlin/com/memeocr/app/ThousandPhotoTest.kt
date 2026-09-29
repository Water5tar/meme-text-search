package com.memeocr.app

import android.Manifest
import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.os.Debug
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.memeocr.app.data.OcrStatus
import com.memeocr.app.media.AccessLevel
import com.memeocr.app.media.PhotoAccess
import com.memeocr.app.media.MediaStoreSynchronizer
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Synthetic images, actually inserted in MediaStore and decoded by the production Worker. */
@RunWith(AndroidJUnit4::class)
class ThousandPhotoTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES)
    @Test fun thousandRealImagesBoundedQueuePauseAndResume(): Unit = runBlocking {
        val total = InstrumentationRegistry.getArguments().getString("photoScale")?.toIntOrNull() ?: 1000
        require(total in listOf(1000, 5000, 10000))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemeApplication
        val dao = app.database.photos()
        val resolver = app.contentResolver
        val uris = ArrayList<Uri>()
        app.control.pause()
        delay(1000)
        val image = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            drawText("HELLO MEME", 20f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f })
        }
        val bytes = ByteArrayOutputStream().apply { image.compress(Bitmap.CompressFormat.JPEG, 85, this) }.toByteArray()
        image.recycle()
        try {
            for (i in 1..total) {
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "scale-${System.currentTimeMillis()}-$i.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MemeScale")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                })!!
                uris.add(uri)
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            val keys = uris.map { "content://media/external_primary/images/media/${it.lastPathSegment}" }
            val scanStart = System.nanoTime()
            MediaStoreSynchronizer(app, dao).sync()
            Log.i("MemeThousand", "metadata_scan_ms=${(System.nanoTime()-scanStart)/1e6}")
            app.control.resume()
            val start = System.currentTimeMillis()
            suspend fun indexed() = keys.chunked(300).flatMap { dao.byUris(it) }.filter { it.ocrStatus == OcrStatus.INDEXED }
            withTimeout(180_000) { while (indexed().size < 30) delay(250) }
            app.control.pause()
            delay(1000)
            val saved = indexed().associate { it.contentUri to it.indexedAt }
            val pausedCount = saved.size
            delay(1500)
            assertEquals(pausedCount, indexed().size)
            assertTrue(pausedCount in 30 until total)
            app.control.resume()
            var maxPss = 0
            withTimeout(1_800_000) {
                while (indexed().size < total) {
                    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                    maxPss = maxOf(maxPss, memory.totalPss)
                    delay(1000)
                }
            }
            val rows = indexed()
            assertEquals(total, rows.size)
            assertTrue(rows.all { it.normalizedText.contains("hello meme") })
            for (row in rows) saved[row.contentUri]?.let { assertEquals(it, row.indexedAt) }
            Log.i("MemeThousand", "photos=$total; elapsed_including_pause_ms=${System.currentTimeMillis()-start}; max_sampled_pss_kb=$maxPss; paused_at=$pausedCount; saved_results_unchanged=true")
        } finally {
            app.control.pause()
            uris.forEach { resolver.delete(it, null, null) }
            app.control.resume()
        }
    }
}
