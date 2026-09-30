package com.memeocr.app.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.memeocr.app.MemeApplication
import com.memeocr.app.data.OcrStatus
import com.memeocr.app.data.PhotoEntity
import com.memeocr.app.media.*
import com.memeocr.core.TextNormalizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotoIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = serial.withLock { runQueue() }
    private suspend fun runQueue(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MemeApplication
        val dao = app.database.photos()
        if (app.control.paused) return@withContext Result.success()
        if (PhotoAccess.level(app) == AccessLevel.NONE) {
            dao.hideAll(); return@withContext Result.success()
        }
        val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            setProgress(workDataOf("stage" to "正在同步相册"))
            suspend fun syncIfRequested() {
                if (app.control.consumeSync()) {
                    MediaStoreSynchronizer(app, dao).sync()
                    app.control.finishSync()
                }
            }
            syncIfRequested()
            val started = System.currentTimeMillis()
            var processed = 0
            while (!app.control.paused && processed < 80 && System.currentTimeMillis() - started < 180_000) {
                currentCoroutineContext().ensureActive()
                syncIfRequested()
                val row = dao.pending(1).firstOrNull() ?: break
                setProgress(workDataOf("stage" to "正在识别图片文字"))
                // ML Kit tasks do not stop when a coroutine is cancelled. Finish the current image
                // before recycling its bitmap; the next image honors cancellation/pause.
                withContext(NonCancellable) {
                    var bitmap: android.graphics.Bitmap? = null
                    try {
                        val uri = Uri.parse(row.contentUri)
                        if (!sameMediaVersion(row)) {
                            dao.hideUri(row.contentUri); app.control.requestSync()
                            return@withContext
                        }
                        bitmap = ImageLoader.decodeForOcr { app.contentResolver.openInputStream(uri) }
                            ?: error("无法解码图片")
                        val image = InputImage.fromBitmap(bitmap!!, 0)
                        val zh = OcrLayout.text(chinese.process(image).await())
                        val en = latin.process(image).await().text
                        val text = listOf(zh, en).filter { it.isNotBlank() }.distinct().joinToString("\n")
                        if (sameMediaVersion(row)) {
                            dao.finishOcr(row.contentUri, row.sizeBytes, row.dateModified,
                                row.mediaGeneration, text, TextNormalizer.normalize(text),
                                OcrStatus.INDEXED, "", System.currentTimeMillis())
                        } else { dao.hideUri(row.contentUri); app.control.requestSync() }
                    } catch (e: SecurityException) {
                        dao.hideUri(row.contentUri)
                    } catch (e: Exception) {
                        dao.finishOcr(row.contentUri, row.sizeBytes, row.dateModified,
                            row.mediaGeneration, "", "", OcrStatus.FAILED,
                            (e.message ?: e.javaClass.simpleName).take(300), System.currentTimeMillis())
                    } finally { bitmap?.recycle() }
                }
                processed++
            }
            currentCoroutineContext().ensureActive()
            if (dao.pendingCount() > 0 || app.control.hasSyncRequest()) app.control.continueQueue()
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: SecurityException) { dao.hideAll(); Result.success() }
        catch (e: Exception) {
            app.control.requestSync()
            setProgress(workDataOf("stage" to "同步失败，自动重试：${e.message?.take(100)}"))
            Result.retry()
        } finally { chinese.close(); latin.close() }
    }
    private fun sameMediaVersion(row: PhotoEntity): Boolean {
        val columns = mutableListOf("_size", "date_modified")
        if (android.os.Build.VERSION.SDK_INT >= 30) columns.add("generation_modified")
        return applicationContext.contentResolver.query(Uri.parse(row.contentUri),
            columns.toTypedArray(), null, null, null)?.use {
            it.moveToFirst() && it.getLong(0) == row.sizeBytes && it.getLong(1) == row.dateModified &&
                (columns.size == 2 || it.getLong(2) == row.mediaGeneration)
        } ?: false
    }
    companion object { private val serial = Mutex() }
}
