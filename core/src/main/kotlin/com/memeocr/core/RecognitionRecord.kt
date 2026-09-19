package com.memeocr.core

/**
 * 图片识别状态与一张记录的持久化模型（数据库字段对应）。
 */
enum class Status { PENDING, DONE, EMPTY_TEXT, FAILED }

data class RecognitionRecord(
    val uri: String,
    val size: Long,
    val lastModified: Long,
    val status: Status,
    /** 识别出的原始文本，可能为空。 */
    val text: String,
    /** 失败原因简述，仅 FAILED 有意义。 */
    val error: String,
    /** 记录写入时间戳（epoch millis）。 */
    val updatedAt: Long,
) {
    /** 用 [MediaItem] 的版本标识比较，判断缓存是否仍有效。 */
    fun matchesVersion(item: MediaItem): Boolean =
        uri == item.uri && size == item.size && lastModified == item.lastModified
}
