package com.memeocr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun `trims and collapses whitespace`() {
        assertEquals("hello world", TextNormalizer.normalize("  hello \n world \t "))
    }

    @Test
    fun `fullwidth ascii to halfwidth`() {
        assertEquals("abc123", TextNormalizer.normalize("ＡＢＣ１２３"))
    }

    @Test
    fun `fullwidth punctuation`() {
        assertEquals("hello,world!", TextNormalizer.normalize("hello，world！"))
    }

    @Test
    fun `ideographic space becomes normal space`() {
        assertEquals("a b", TextNormalizer.normalize("a\u3000b"))
    }

    @Test
    fun `uppercase to lowercase`() {
        assertEquals("hello", TextNormalizer.normalize("HELLO"))
    }

    @Test
    fun `cjk preserved`() {
        assertEquals("哈哈笑死", TextNormalizer.normalize("哈哈笑死"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", TextNormalizer.normalize(""))
    }

    @Test
    fun `whitespace-only becomes empty`() {
        assertEquals("", TextNormalizer.normalize("   \n\t"))
    }

    @Test
    fun `mixed content`() {
        assertEquals("ok! 好 的", TextNormalizer.normalize("ＯＫ！ 好　的"))
    }

    @Test
    fun `emoji preserved as-is`() {
        assertEquals("😂😂", TextNormalizer.normalize("😂😂"))
    }

    @Test
    fun `idempotent`() {
        val input = "  Ｈｅｌｌｏ，世界  "
        val once = TextNormalizer.normalize(input)
        assertEquals(once, TextNormalizer.normalize(once))
    }

    @Test
    fun `long text performance sanity`() {
        val big = "abcdefg ".repeat(10000)
        val result = TextNormalizer.normalize(big)
        assertTrue(result.startsWith("abcdefg"))
        assertTrue(!result.contains("  "))
    }
}
