package com.memeocr.app.ocr

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.memeocr.app.MainActivity
import com.memeocr.app.R
import com.memeocr.app.data.RecognitionDb
import com.memeocr.app.media.ImageLoader
import com.memeocr.app.media.MediaStoreReader
import com.memeocr.core.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class OcrService : Service() {
    companion object {
        const val ACTION_START = "com.memeocr.app.ocr.START"
        const val ACTION_CANCEL = "com.memeocr.app.ocr.CANCEL"
        const val EXTRA_BUCKET_ID = "bucketId"
        const val EXTRA_BATCH_SIZE = "batchSize"
        const val EXTRA_RETRY = "retry"
        @Volatile var liveProgress = ProgressState.idle()
            private set
    }
    private val cancelled = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private var started = false
    private var lastNotification = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("ocr", "文字识别进度", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        val bucket = intent.getStringExtra(EXTRA_BUCKET_ID)
        if (bucket == null) { stopSelf(); return START_NOT_STICKY }
        val size = intent.getIntExtra(EXTRA_BATCH_SIZE, 1000).coerceIn(1, 10000)
        val retry = intent.getBooleanExtra(EXTRA_RETRY, false)
        started = true
        liveProgress = ProgressState(0, 0, 0, 0, 0, true, false)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, notification())
        worker.execute {
            val db = RecognitionDb(this)
            var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
            try {
                val reader = MediaStoreReader(this)
                val batch = BatchPlan.select(reader.listImages(bucket), db.allRecords().associateBy { it.uri }, size, retry)
                val engine = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                recognizer = engine
                BatchRunner(reader::statUri, { item ->
                    val bitmap = ImageLoader.decodeForOcr { reader.openInputStream(item.uri) }
                        ?: throw java.io.IOException("无法解码图片")
                    try { Tasks.await(engine.process(InputImage.fromBitmap(bitmap, 0))).text }
                    finally { bitmap.recycle() }
                }, db::upsert).run(batch, cancelled::get, ::publish)
            } catch (e: Exception) {
                liveProgress = liveProgress.copy(running = false, lastError = RecognitionPolicy.describeError(e))
            } finally {
                recognizer?.close()
                db.close()
                liveProgress = liveProgress.copy(running = false, cancelled = cancelled.get())
                Handler(Looper.getMainLooper()).post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(state: ProgressState) {
        liveProgress = state
        val now = System.currentTimeMillis()
        if (now - lastNotification >= 750 || !state.running) {
            lastNotification = now
            getSystemService(NotificationManager::class.java).notify(1, notification())
        }
    }

    private fun notification(): Notification {
        val p = liveProgress
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OcrService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "ocr").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Meme 文字识别")
            .setContentText(if (p.batchTotal == 0) "正在检查相册…" else "" + p.processed + " / " + p.batchTotal + " · 失败 " + p.failed)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(p.batchTotal, p.processed, p.batchTotal == 0)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled.set(true)
        liveProgress = liveProgress.copy(running = false, lastError = "系统暂停了后台任务，已保存的结果保留")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
