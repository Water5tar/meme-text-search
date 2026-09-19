package com.memeocr.core

/**
 * 一次识别任务的进度快照。服务每处理完一张就更新一次，UI 只展示。
 */
data class ProgressState(
    /** 本批总数。 */
    val batchTotal: Int,
    /** 本批已处理（成功+空文本+失败）。 */
    val processed: Int,
    /** 本批成功且有文字。 */
    val succeeded: Int,
    /** 本批成功但无文字。 */
    val emptyText: Int,
    /** 本批失败。 */
    val failed: Int,
    /** 是否仍在运行。 */
    val running: Boolean,
    /** 用户是否请求过取消（用于区分自然结束与取消）。 */
    val cancelled: Boolean,
    /** 最近一条失败原因，可为 null。 */
    val lastError: String? = null,
) {
    companion object {
        fun idle() = ProgressState(0, 0, 0, 0, 0, running = false, cancelled = false)
    }
}
