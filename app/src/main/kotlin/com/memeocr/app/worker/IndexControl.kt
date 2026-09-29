package com.memeocr.app.worker

import android.content.Context
import androidx.work.*

class IndexControl(context: Context) {
    private val prefs = context.getSharedPreferences("index-control", Context.MODE_PRIVATE)
    private val work = WorkManager.getInstance(context)
    val paused: Boolean get() = prefs.getBoolean("paused", false)
    @Synchronized fun consumeSync(): Boolean {
        val requested = hasSyncRequest()
        if (requested) prefs.edit().putBoolean("sync", false).putBoolean("syncInFlight", true).commit()
        return requested
    }
    fun hasSyncRequest() = prefs.getBoolean("sync", false) || prefs.getBoolean("syncInFlight", false)
    @Synchronized fun finishSync() { prefs.edit().putBoolean("syncInFlight", false).commit() }
    @Synchronized fun requestSync() {
        prefs.edit().putBoolean("sync", true).commit()
        // A foreground/manual sync also wakes a chain waiting in exponential backoff.
        // The Worker mutex and per-image commits make replacement safe during OCR.
        if (!paused) enqueue(ExistingWorkPolicy.REPLACE)
    }
    @Synchronized fun resume() {
        prefs.edit().putBoolean("paused", false).commit()
        requestSync()
    }
    @Synchronized fun pause() {
        prefs.edit().putBoolean("paused", true).commit()
        work.cancelUniqueWork(NAME)
    }
    fun continueQueue() { if (!paused) enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE) }
    private fun enqueue(policy: ExistingWorkPolicy) {
        work.enqueueUniqueWork(NAME, policy, OneTimeWorkRequestBuilder<PhotoIndexWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build())
    }
    companion object { const val NAME = "local-photo-index" }
}
