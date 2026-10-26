package com.memeocr.app

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.memeocr.app.data.OcrStatus
import com.memeocr.app.media.ImageLoader
import com.memeocr.app.worker.OcrLayout
import com.memeocr.core.TextNormalizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealOcrTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES)
    @Test fun verticalChineseUsesGeometricReadingOrder(): Unit = runBlocking {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val bitmap = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }
            "天地人".forEachIndexed { index, ch -> drawText(ch.toString(), 510f, 160f + index * 130f, paint) }
            "你我他".forEachIndexed { index, ch -> drawText(ch.toString(), 180f, 160f + index * 130f, paint) }
        }
        try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val ordered = OcrLayout.text(result)
            Log.i("MemeOcr", "vertical_raw=${result.text}; vertical_ordered=$ordered")
            for (block in result.textBlocks) for (line in block.lines) {
                Log.i("MemeOcr", "line=${line.text} box=${line.boundingBox} elements=${line.elements.map { "${it.text}:${it.boundingBox}:symbols=${it.symbols.size}" }}")
            }
            assertTrue("Wrong column order in: $ordered", ordered.startsWith("天地人\n你我他"))
        } finally { recognizer.close(); bitmap.recycle() }
    }
    @Test fun bundledModelsRecognizeChineseAndEnglishOffline(): Unit = runBlocking {
        val test = InstrumentationRegistry.getInstrumentation().context
        val zh = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val en = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val chinese = ImageLoader.decodeForOcr { test.assets.open("chinese.png") }!!
        val english = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        Canvas(english).apply {
            drawColor(Color.WHITE)
            drawText("HELLO MEME 100%", 60f, 200f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f })
        }
        try {
            val begin = System.nanoTime()
            val chineseText = zh.process(InputImage.fromBitmap(chinese, 0)).await().text
            val englishText = en.process(InputImage.fromBitmap(english, 0)).await().text
            assertTrue(chineseText, TextNormalizer.normalize(chineseText).contains("摸鱼时间到了"))
            assertTrue(englishText, TextNormalizer.normalize(englishText).contains("hello meme"))
            Log.i("MemeOcr", "two_models_ms=${(System.nanoTime()-begin)/1e6}; chinese=$chineseText; english=$englishText")
        } finally { zh.close(); en.close(); chinese.recycle(); english.recycle() }
    }
    @Test fun mediaStoreWorkerRoomAndIncrementalSkip(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MemeApplication
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ocr-integration-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MemeTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!
        try {
            resolver.openOutputStream(uri)!!.use { out -> instrumentation.context.assets.open("chinese.png").use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            app.control.resume()
            val dao = app.database.photos()
            val id = uri.lastPathSegment!!.toLong()
            // The scanner uses the real volume URI, which differs from the aggregate insertion URI.
            suspend fun row() = dao.byUris(listOf("content://media/external_primary/images/media/$id")).firstOrNull()
            val started = System.currentTimeMillis()
            withTimeout(120_000) { while (row()?.ocrStatus != OcrStatus.INDEXED) delay(300) }
            val first = row()!!
            val indexingMs = System.currentTimeMillis() - started
            assertTrue(first.ocrText, first.normalizedText.contains("摸鱼时间到了"))
            app.control.requestSync()
            delay(2500)
            assertEquals(first.indexedAt, row()!!.indexedAt)
            Log.i("MemeOcr", "media_to_search_ms=$indexingMs; indexedAt=${first.indexedAt}; unchanged_after_sync=true")
        } finally { resolver.delete(uri, null, null); app.control.requestSync() }
    }
    @Test fun blankAndCorruptImagesAreDurableAndOnlyFailureRetries(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemeApplication
        val resolver = app.contentResolver
        val uris = mutableListOf<android.net.Uri>()
        val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bytes = java.io.ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        try {
            for ((i, data) in listOf(bytes, byteArrayOf(1, 2, 3, 4)).withIndex()) {
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "empty-invalid-${System.currentTimeMillis()}-$i.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MemeTest")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                })!!
                uris.add(uri)
                resolver.openOutputStream(uri)!!.use { it.write(data) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            val keys = uris.map { "content://media/external_primary/images/media/${it.lastPathSegment}" }
            val dao = app.database.photos()
            suspend fun rows() = keys.map { dao.byUri(it) }
            app.control.resume()
            withTimeout(60_000) { while (rows().any { it == null || it.ocrStatus == OcrStatus.PENDING }) delay(200) }
            val blank = rows()[0]!!; val corrupt = rows()[1]!!
            assertEquals(OcrStatus.INDEXED, blank.ocrStatus)
            assertEquals("", blank.ocrText)
            assertEquals(OcrStatus.FAILED, corrupt.ocrStatus)
            assertTrue(corrupt.error.isNotBlank())
            app.control.requestSync(); delay(2000)
            assertEquals(blank.indexedAt, rows()[0]!!.indexedAt)
            assertEquals(corrupt.indexedAt, rows()[1]!!.indexedAt)
            dao.retryFailed(); app.control.resume()
            withTimeout(60_000) {
                while (rows()[1]!!.ocrStatus != OcrStatus.FAILED || rows()[1]!!.indexedAt == corrupt.indexedAt) delay(200)
            }
            assertEquals(blank.indexedAt, rows()[0]!!.indexedAt)
        } finally { uris.forEach { resolver.delete(it, null, null) }; app.control.requestSync() }
    }
}


