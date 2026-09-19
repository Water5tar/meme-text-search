package com.memeocr.core

/** Saved failures are retried explicitly; changed versions become eligible again. */
object BatchPlan {
    fun eligible(item: MediaItem, record: RecognitionRecord?, retryFailed: Boolean): Boolean =
        if (retryFailed) record?.status == Status.FAILED
        else record == null || !record.matchesVersion(item) || record.status == Status.PENDING

    fun select(items: List<MediaItem>, records: Map<String, RecognitionRecord>, size: Int,
               retryFailed: Boolean = false): List<MediaItem> {
        require(size in 1..10000) { "本批数量须为 1–10000" }
        return items.asSequence().distinctBy { it.uri }
            .filter { eligible(it, records[it.uri], retryFailed) }.take(size).toList()
    }
}

/** The Android service and interruption tests use this same processing loop. */
class BatchRunner(
    private val stat: (String) -> MediaItem?,
    private val recognize: (MediaItem) -> String,
    private val save: (RecognitionRecord) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun run(batch: List<MediaItem>, cancelled: () -> Boolean,
            publish: (ProgressState) -> Unit): ProgressState {
        var state = ProgressState(batch.size, 0, 0, 0, 0, true, false)
        publish(state)
        for (candidate in batch) {
            if (cancelled()) break
            var current = candidate
            var permissionLost = false
            val record = try {
                current = stat(candidate.uri) ?: throw java.io.IOException("图片已删除或不可访问")
                val text = recognize(current)
                if (stat(current.uri) != current) throw java.io.IOException("图片在识别期间发生变化，请重试")
                RecognitionRecord(current.uri, current.size, current.lastModified,
                    if (RecognitionPolicy.isEmptyText(text)) Status.EMPTY_TEXT else Status.DONE,
                    text, "", clock())
            } catch (e: Exception) {
                permissionLost = e is SecurityException
                RecognitionRecord(current.uri, current.size, current.lastModified, Status.FAILED,
                    "", RecognitionPolicy.describeError(e), clock())
            }
            save(record) // Count only after saving succeeds.
            state = state.copy(
                processed = state.processed + 1,
                succeeded = state.succeeded + if (record.status == Status.DONE) 1 else 0,
                emptyText = state.emptyText + if (record.status == Status.EMPTY_TEXT) 1 else 0,
                failed = state.failed + if (record.status == Status.FAILED) 1 else 0,
                lastError = record.error.takeIf { it.isNotEmpty() } ?: state.lastError,
            )
            publish(state)
            if (permissionLost) break
        }
        state = state.copy(running = false, cancelled = cancelled())
        publish(state)
        return state
    }
}
