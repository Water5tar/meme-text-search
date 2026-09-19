package com.memeocr.core

/**
 * 文本标准化：用于缓存里识别文本与搜索词对齐，减少全半角、空白造成的漏配。
 * 规则保持简单：去首尾空白、合并连续空白、全角转半角、统一小写。
 */
object TextNormalizer {

    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when (ch) {
                in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
                '\u3000' -> ' '
                else -> ch
            }
            sb.append(c)
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
