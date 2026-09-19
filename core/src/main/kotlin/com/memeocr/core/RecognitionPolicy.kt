package com.memeocr.core

/**
 * OCR 处理一个图片时把平台无关的判定抽出来，便于单测。
 */
object RecognitionPolicy {

    /** 解码得到的文本是否视为"成功但无文字"。 */
    fun isEmptyText(text: String): Boolean = TextNormalizer.normalize(text).isEmpty()

    /** 根据异常信息粗分类错误，便于 UI 提示与重试判断。 */
    fun describeError(t: Throwable): String = when (t) {
        is SecurityException -> "没有访问该图片的权限"
        is java.io.IOException -> "图片读取或解码失败：${t.message ?: "IO错误"}"
        is IllegalStateException -> "解码器状态异常：${t.message ?: "未知"}"
        else -> "识别失败：${t.javaClass.simpleName}${t.message?.let { "：$it" } ?: ""}"
    }

    /** 版本变化判断：记录与当前媒体元数据是否一致。 */
    fun needsReprocess(record: RecognitionRecord?, item: MediaItem): Boolean {
        if (record == null) return true
        return !record.matchesVersion(item)
    }
}
