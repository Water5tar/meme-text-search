package com.memeocr.core

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/**
 * 搜索逻辑：普通文字包含搜索 + 可选 RE2/J 正则搜索。
 * 纯 Kotlin（RE2/J 是纯 Java 库），可在 JVM 测试。
 */
sealed class SearchMode {
    /** 普通包含搜索。 */
    data class Literal(val keyword: String) : SearchMode()

    /** 正则搜索。语法不支持时通过 [RegexSearch.validate] 返回错误。 */
    data class Regex(val pattern: String) : SearchMode()
}

object RegexSearch {
    /** 校验并预编译，返回编译结果或用户可读的错误说明（中文）。 */
    fun validate(pattern: String): Result<Pattern> {
        if (pattern.isEmpty()) return Result.failure(IllegalArgumentException("正则表达式不能为空"))
        return try {
            Result.success(Pattern.compile(pattern))
        } catch (e: PatternSyntaxException) {
            Result.failure(
                IllegalArgumentException("正则表达式无效：${e.description ?: "语法错误"}。本应用使用 RE2，不支持前后查找 (?=)、反向引用 \\1 等语法。")
            )
        }
    }
}

data class SearchHit(
    val record: RecognitionRecord,
    /** 命中的那条文本（标准化后），用于 UI 提示。 */
    val matchedLine: String,
)

object TextSearch {

    /**
     * 在标准化后的记录文本中搜索。
     * @param records 缓存中状态为 DONE 或 EMPTY_TEXT 的记录
     */
    fun search(records: List<RecognitionRecord>, mode: SearchMode): List<SearchHit> {
        return when (mode) {
            is SearchMode.Literal -> {
                val keyword = TextNormalizer.normalize(mode.keyword)
                if (keyword.isEmpty()) return emptyList()
                records.asSequence()
                    .filter { it.status == Status.DONE || it.status == Status.EMPTY_TEXT }
                    .mapNotNull { rec ->
                        val normalized = TextNormalizer.normalize(rec.text)
                        if (normalized.contains(keyword)) SearchHit(rec, normalized) else null
                    }
                    .toList()
            }
            is SearchMode.Regex -> {
                val compiled = RegexSearch.validate(mode.pattern).getOrThrow()
                records.asSequence()
                    .filter { it.status == Status.DONE || it.status == Status.EMPTY_TEXT }
                    .mapNotNull { rec ->
                        val normalized = rec.text
                        if (normalized.isEmpty()) return@mapNotNull null
                        // RE2 保证线性时间，这里逐条匹配不会灾难性回溯
                        val matcher = compiled.matcher(normalized)
                        if (matcher.find()) SearchHit(rec, normalized) else null
                    }
                    .toList()
            }
        }
    }
}
