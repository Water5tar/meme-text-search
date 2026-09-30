package com.memeocr.core

import kotlin.math.abs

/** A single OCR symbol and its axis-aligned image coordinates. */
data class OcrGlyph(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    val width get() = right - left
    val height get() = bottom - top
}

/** Adds reading-order aliases only when character geometry is clearly taller than wide. */
object VerticalTextLayout {
    fun verticalLineOrder(lines: List<OcrGlyph>): String {
        val columns = lines.filter { line ->
            line.text.length >= 2 && line.text.all(Char::isLetterOrDigit) &&
                line.width > 0 && line.height >= line.width * 1.8f
        }
        if (columns.size !in 2..128) return ""
        val overlap = columns.minOf { it.bottom } - columns.maxOf { it.top }
        if (overlap < columns.minOf { it.height } * .4f) return ""
        return columns.sortedByDescending(OcrGlyph::centerX).joinToString("\n") { it.text }
    }

    fun readingOrder(glyphs: List<OcrGlyph>): String {
        val eligible = glyphs.filter {
            it.text.length == 1 && it.text[0].isLetterOrDigit() && it.width > 0 && it.height > 0 &&
                it.height <= it.width * 1.65f && it.width <= it.height * 1.65f
        }
        if (eligible.size !in 3..512) return ""
        val size = eligible.map { it.width }.sorted()[eligible.size / 2].toFloat()
        val columns = mutableListOf<MutableList<OcrGlyph>>()
        for (glyph in eligible.sortedBy { it.centerX }) {
            val column = columns.minByOrNull { abs(it.map(OcrGlyph::centerX).average() - glyph.centerX) }
            if (column != null && abs(column.map(OcrGlyph::centerX).average() - glyph.centerX) <= size * .65f)
                column.add(glyph)
            else columns.add(mutableListOf(glyph))
        }
        val groups = mutableListOf<MutableList<List<OcrGlyph>>>()
        for (column in columns.sortedBy { it.map(OcrGlyph::centerX).average() }) {
            val previous = groups.lastOrNull()?.lastOrNull()
            val previousX = previous?.map(OcrGlyph::centerX)?.average() ?: Double.NEGATIVE_INFINITY
            if (previous != null && column.map(OcrGlyph::centerX).average() - previousX <= size * 2.5f)
                groups.last().add(column)
            else groups.add(mutableListOf(column))
        }
        return groups.sortedByDescending { group -> group.maxOf { column -> column.map(OcrGlyph::centerX).average() } }
            .mapNotNull { group ->
            val region = group.flatten()
            val tallest = group.maxOf { it.size }
            val rows = mutableListOf<MutableList<OcrGlyph>>()
            for (glyph in region.sortedBy { it.centerY }) {
                val row = rows.minByOrNull { abs(it.map(OcrGlyph::centerY).average() - glyph.centerY) }
                if (row != null && abs(row.map(OcrGlyph::centerY).average() - glyph.centerY) <= size * .65f)
                    row.add(glyph)
                else rows.add(mutableListOf(glyph))
            }
            val widest = rows.maxOf { it.size }
            if (tallest < 3 || tallest <= widest || group.any { column ->
                val ordered = column.sortedBy(OcrGlyph::centerY)
                ordered.zipWithNext().any { (a, b) -> b.centerY - a.centerY < size * .65f }
            }) null else group.sortedByDescending { it.map(OcrGlyph::centerX).average() }
                .joinToString("\n") { column -> column.sortedBy(OcrGlyph::centerY).joinToString("") { it.text } }
        }.filter(String::isNotBlank).joinToString("\n")
    }

    fun withReadingOrder(raw: String, glyphs: List<OcrGlyph>, lines: List<OcrGlyph> = emptyList()): String {
        val ordered = verticalLineOrder(lines).ifBlank { readingOrder(glyphs) }
        if (ordered.isBlank() || TextNormalizer.normalize(raw).contains(TextNormalizer.normalize(ordered))) return raw
        return if (raw.isBlank()) ordered else "$ordered\n$raw"
    }
}
