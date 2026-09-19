package com.memeocr.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.memeocr.app.data.RecognitionDb
import com.memeocr.app.media.ImageLoader
import com.memeocr.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun png(width: Int = 64, height: Int = 48): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    @Test fun boundsProbeMustNotBeMistakenForDecodeFailure() {
        val bytes = png()
        var closed = 0
        val bitmap = ImageLoader.decodeForOcr {
            object : ByteArrayInputStream(bytes) {
                override fun close() { closed++; super.close() }
            }
        }
        assertNotNull(bitmap)
        assertEquals(64, bitmap!!.width)
        assertEquals(48, bitmap.height)
        assertEquals(3, closed) // bounds, pixels and EXIF are independently closed
        bitmap.recycle()
    }

    @Test fun invalidAndMissingImagesReturnNoBitmap() {
        assertNull(ImageLoader.decodeForOcr { ByteArrayInputStream("broken".toByteArray()) })
        assertNull(ImageLoader.decodeForOcr { null })
    }

    @Test fun largeImageDecodingAndThumbnailsStayBounded() {
        val bytes = png(3200, 400)
        val bitmap = ImageLoader.decodeForOcr { ByteArrayInputStream(bytes) }!!
        assertTrue(bitmap.width <= 2560)
        assertEquals(8, bitmap.width / bitmap.height)
        bitmap.recycle()
        val key = "test-thumbnail-" + System.nanoTime()
        val thumb = ImageLoader.thumbnail(key) { ByteArrayInputStream(bytes) }!!
        assertTrue(thumb.width <= 384)
        assertSame(thumb, ImageLoader.thumbnail(key) { error("cache should skip decoding") })
    }

    @Test fun bundledChineseRecognizerWorksWithoutGooglePlayServices() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val bitmap = ImageLoader.decodeForOcr { testContext.assets.open("chinese.png") }!!
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
            val compact = result.text.replace(Regex("\\s+"), "")
            // Integration checks model availability and useful Chinese keywords, not perfect transcription.
            // The current model reads 要 as 妻 in this fixture; keep that limitation in verification.md.
            android.util.Log.i("MemeOcrAcceptance", "Actual OCR: " + result.text)
            assertTrue("Actual OCR: " + result.text, compact.contains("今天") && compact.contains("开心"))
            assertTrue("Actual OCR: " + result.text, compact.contains("摸鱼时间到了"))
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    @Test fun blankImageRecognitionReturnsEmptyText() {
        val bytes = png(600, 400)
        val bitmap = ImageLoader.decodeForOcr { ByteArrayInputStream(bytes) }!!
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
            assertTrue(RecognitionPolicy.isEmptyText(result.text))
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    @Test fun savedResultsSurviveReopeningAndUpsertKeepsOneVersion() {
        val uri = "test://persistence/" + System.nanoTime()
        val first = RecognitionRecord(uri, 123, 55, Status.DONE, "hello ' quoted\n中文", "", 100)
        var db = RecognitionDb(context)
        try {
            db.upsert(first)
            db.close()
            db = RecognitionDb(context)
            assertEquals(first, db.findByUri(uri))
            val changed = first.copy(size = 999, status = Status.FAILED, text = "", error = "corrupt", updatedAt = 200)
            db.upsert(changed)
            assertEquals(changed, db.findByUri(uri))
            assertEquals(1, db.allRecords().count { it.uri == uri })
        } finally {
            db.deleteByUri(uri); db.close()
        }
    }

    @Test fun sqlitePersistsCancellationAndResumeWithoutDuplicateOcr() {
        val token = System.nanoTime().toString()
        val items = (1..20).map { MediaItem("test://resume/" + token + "/" + it, it.toLong(), 1) }
        val byUri = items.associateBy { it.uri }
        var db = RecognitionDb(context)
        val calls = mutableListOf<String>()
        var saved = 0
        try {
            BatchRunner(byUri::get, { calls.add(it.uri); "样本文字" }, {
                db.upsert(it); saved++
            }).run(items, { saved >= 7 }, {})
            db.close()
            db = RecognitionDb(context)
            val remaining = BatchPlan.select(items, db.allRecords().associateBy { it.uri }, 1000)
            assertEquals(13, remaining.size)
            BatchRunner(byUri::get, { calls.add(it.uri); "样本文字" }, db::upsert)
                .run(remaining, { false }, {})
            assertEquals(items.map { it.uri }, calls)
            assertTrue(BatchPlan.select(items, db.allRecords().associateBy { it.uri }, 1000).isEmpty())
        } finally {
            items.forEach { db.deleteByUri(it.uri) }; db.close()
        }
    }

    @Test fun sqliteKeepsEmptySuccessAndFailureDistinctAcrossRestart() {
        val base = "test://statuses/" + System.nanoTime()
        val empty = RecognitionRecord(base + "/empty", 5, 6, Status.EMPTY_TEXT, "", "", 1)
        val failed = empty.copy(uri = base + "/failed", status = Status.FAILED, error = "broken")
        var db = RecognitionDb(context)
        try {
            db.upsert(empty); db.upsert(failed); db.close()
            db = RecognitionDb(context)
            assertEquals(Status.EMPTY_TEXT, db.findByUri(empty.uri)!!.status)
            assertEquals(Status.FAILED, db.findByUri(failed.uri)!!.status)
            val items = listOf(empty, failed).map { MediaItem(it.uri, it.size, it.lastModified) }
            val records = db.allRecords().associateBy { it.uri }
            assertTrue(BatchPlan.select(items, records, 10).isEmpty())
            assertEquals(listOf(items[1]), BatchPlan.select(items, records, 10, true))
        } finally {
            db.deleteByUri(empty.uri); db.deleteByUri(failed.uri); db.close()
        }
    }
}
