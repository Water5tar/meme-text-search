package com.memeocr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {

    private fun doneRec(text: String, uri: String = "u") =
        RecognitionRecord(uri, 1, 1, Status.DONE, text, "", 0)

    private fun emptyRec(uri: String = "e") =
        RecognitionRecord(uri, 1, 1, Status.EMPTY_TEXT, "", "", 0)

    private fun failedRec(uri: String = "f") =
        RecognitionRecord(uri, 1, 1, Status.FAILED, "", "err", 0)

    private fun pendingRec(uri: String = "p") =
        RecognitionRecord(uri, 1, 1, Status.PENDING, "", "", 0)

    // ---------- literal search ----------

    @Test
    fun `literal search finds by contains`() {
        val recs = listOf(doneRec("今天也要开心鸭"), doneRec("摸鱼中", "u2"))
        val hits = TextSearch.search(recs, SearchMode.Literal("开心"))
        assertEquals(1, hits.size)
        assertEquals("u", hits[0].record.uri)
    }

    @Test
    fun `literal search case insensitive`() {
        val recs = listOf(doneRec("Say HELLO"))
        assertEquals(1, TextSearch.search(recs, SearchMode.Literal("hello")).size)
        assertEquals(1, TextSearch.search(recs, SearchMode.Literal("HELLO")).size)
    }

    @Test
    fun `literal search fullwidth matches halfwidth keyword`() {
        val recs = listOf(doneRec("ＯＫ！"))
        assertEquals(1, TextSearch.search(recs, SearchMode.Literal("ok")).size)
    }

    @Test
    fun `literal search keyword with spaces`() {
        val recs = listOf(doneRec("hello   world"))
        assertEquals(1, TextSearch.search(recs, SearchMode.Literal("hello world")).size)
    }

    @Test
    fun `literal empty keyword returns nothing`() {
        val recs = listOf(doneRec("some text"))
        assertTrue(TextSearch.search(recs, SearchMode.Literal("")).isEmpty())
        assertTrue(TextSearch.search(recs, SearchMode.Literal("   ")).isEmpty())
    }

    @Test
    fun `literal keyword with regex metachars treated literally`() {
        val recs = listOf(doneRec("price is 100$ (sale)"), doneRec("a.c x", "u2"))
        assertEquals(1, TextSearch.search(recs, SearchMode.Literal("100$ (sale)")).size)
        // "a.c" 作为字面量只匹配原文，不匹配 "abc"
        assertEquals(1, TextSearch.search(recs, SearchMode.Literal("a.c")).size)
        assertEquals("u2", TextSearch.search(recs, SearchMode.Literal("a.c"))[0].record.uri)
    }

    @Test
    fun `literal search excludes failed and pending`() {
        val recs = listOf(failedRec(), pendingRec(), emptyRec())
        assertTrue(TextSearch.search(recs, SearchMode.Literal("a")).isEmpty())
    }

    @Test
    fun `empty text records never match literal`() {
        val recs = listOf(emptyRec())
        assertTrue(TextSearch.search(recs, SearchMode.Literal("x")).isEmpty())
    }

    // ---------- regex search ----------

    @Test
    fun `regex basic pattern`() {
        val recs = listOf(doneRec("电话 13800138000"), doneRec("没有号码", "u2"))
        val hits = TextSearch.search(recs, SearchMode.Regex("\\d{11}"))
        assertEquals(1, hits.size)
        assertEquals("u", hits[0].record.uri)
    }

    @Test
    fun `regex anchors`() {
        val recs = listOf(doneRec("哈哈"), doneRec("呵呵", "u2"))
        assertEquals(1, TextSearch.search(recs, SearchMode.Regex("^哈")).size)
        assertEquals(1, TextSearch.search(recs, SearchMode.Regex("呵$")).size)
    }

    @Test
    fun `regex alternation`() {
        val recs = listOf(doneRec("我想吃苹果"), doneRec("我要吃香蕉", "u2"), doneRec("喝奶茶", "u3"))
        val hits = TextSearch.search(recs, SearchMode.Regex("苹果|香蕉"))
        assertEquals(2, hits.size)
    }

    @Test
    fun `regex invalid pattern throws with Chinese message`() {
        val recs = listOf(doneRec("abc"))
        val mode = SearchMode.Regex("(?=lookahead)")
        try {
            TextSearch.search(recs, mode)
            throw AssertionError("should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("正则"))
        }
    }

    @Test
    fun `regex validate rejects backreference`() {
        val result = RegexSearch.validate("(a)\\1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `regex validate rejects empty`() {
        assertTrue(RegexSearch.validate("").isFailure)
    }

    @Test
    fun `regex validate accepts simple pattern`() {
        assertTrue(RegexSearch.validate("\\d+").isSuccess)
    }

    @Test
    fun `regex unbalanced parens rejected`() {
        val recs = listOf(doneRec("abc"))
        try {
            TextSearch.search(recs, SearchMode.Regex("(abc"))
            throw AssertionError("should throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `regex preserves raw fullwidth text`() {
        // 正则保留原文，不改写表达式或图片文字。
        val recs = listOf(doneRec("ＡＢＣ"))
        assertEquals(1, TextSearch.search(recs, SearchMode.Regex("ＡＢＣ")).size)
    }

    @Test
    fun `regex empty normalized text skipped`() {
        val recs = listOf(emptyRec())
        assertTrue(TextSearch.search(recs, SearchMode.Regex(".*")).isEmpty())
    }

    // ---------- scale ----------

    @Test
    fun `literal search at 9000 scale`() {
        val recs = (1..9000).map { doneRec("meme number $it", "u$it") }
        val hits = TextSearch.search(recs, SearchMode.Literal("meme number 8999"))
        assertEquals(1, hits.size)
        assertEquals("u8999", hits[0].record.uri)
    }

    @Test
    fun `regex search at 9000 scale with pathological-free pattern`() {
        val recs = (1..9000).map { doneRec("item-$it ok", "u$it") }
        val hits = TextSearch.search(recs, SearchMode.Regex("item-\\d+ ok"))
        assertEquals(9000, hits.size)
    }

    @Test
    fun `literal search finds multiple`() {
        val recs = listOf(doneRec("a"), doneRec("ab", "u2"), doneRec("ba", "u3"), doneRec("c", "u4"))
        val hits = TextSearch.search(recs, SearchMode.Literal("a"))
        assertEquals(3, hits.size)
    }

    @Test
    fun `literal search with very long keyword`() {
        val longKeyword = "x".repeat(5000)
        val recs = listOf(doneRec("y".repeat(10000) + longKeyword + "z"))
        assertEquals(1, TextSearch.search(recs, SearchMode.Literal(longKeyword)).size)
    }

    @Test
    fun `search special characters in text`() {
        val recs = listOf(doneRec("[]{}()*+?.\\^$|"), doneRec("normal", "u2"))
        val hits = TextSearch.search(recs, SearchMode.Literal("[]{}()*+?.\\^$|"))
        assertEquals(1, hits.size)
    }
}
