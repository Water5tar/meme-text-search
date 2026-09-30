package com.memeocr.core

import org.junit.Assert.*
import org.junit.Test

class VerticalTextLayoutTest {
    private fun glyph(char: Char, x: Int, y: Int) = OcrGlyph(char.toString(), x, y, x + 20, y + 20)

    @Test fun twoVerticalColumnsReadRightToLeftAndTopToBottom() {
        val input = ("你好呀".mapIndexed { i, ch -> glyph(ch, 40, i * 24) } +
            "我来了".mapIndexed { i, ch -> glyph(ch, 8, i * 24) }).reversed()
        assertEquals("你好呀\n我来了", VerticalTextLayout.readingOrder(input))
        assertTrue(VerticalTextLayout.withReadingOrder("我你\n来好\n了呀", input).startsWith("你好呀\n我来了"))
    }

    @Test fun horizontalGridAndAmbiguousSquareKeepOriginalText() {
        val horizontal = (0 until 3).flatMap { row ->
            "天天好开心".mapIndexed { col, ch -> glyph(ch, col * 24, row * 24) }
        }
        assertEquals("", VerticalTextLayout.readingOrder(horizontal))
        assertEquals("原有顺序", VerticalTextLayout.withReadingOrder("原有顺序", horizontal))
        val square = (0 until 3).flatMap { row ->
            "很好呀".mapIndexed { col, ch -> glyph(ch, col * 24, row * 24) }
        }
        assertEquals("", VerticalTextLayout.readingOrder(square))
    }

    @Test fun alreadyCorrectTextIsNotDuplicated() {
        val input = "你好呀".mapIndexed { i, ch -> glyph(ch, 10, i * 24) }
        assertEquals("你好呀", VerticalTextLayout.withReadingOrder("你好呀", input))
    }

    @Test fun wholeVerticalLinesFromRecognizerReadRightToLeft() {
        val lines = listOf(OcrGlyph("你我他", 10, 70, 110, 420), OcrGlyph("天地人", 340, 45, 440, 460))
        assertEquals("天地人\n你我他", VerticalTextLayout.verticalLineOrder(lines))
        assertEquals("天地人\n你我他\n你我他\n天地人",
            VerticalTextLayout.withReadingOrder("你我他\n天地人", emptyList(), lines))
    }
}
