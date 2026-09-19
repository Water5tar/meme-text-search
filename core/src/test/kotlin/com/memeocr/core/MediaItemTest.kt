package com.memeocr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemTest {
    @Test
    fun `version key includes uri size and mtime`() {
        val a = MediaItem("content://x/1", 100L, 200L)
        assertEquals("content://x/1|100|200", a.versionKey)
    }

    @Test
    fun `same uri different size has different version`() {
        val a = MediaItem("content://x/1", 100L, 200L)
        val b = MediaItem("content://x/1", 101L, 200L)
        assertTrue(a.versionKey != b.versionKey)
    }

    @Test
    fun `same uri different mtime has different version`() {
        val a = MediaItem("content://x/1", 100L, 200L)
        val b = MediaItem("content://x/1", 100L, 201L)
        assertTrue(a.versionKey != b.versionKey)
    }

    @Test
    fun `same content different uri not equal`() {
        // 文件移动/复制不承诺去重：URI 变了就是不同记录
        val a = MediaItem("content://x/1", 100L, 200L)
        val b = MediaItem("content://x/2", 100L, 200L)
        assertTrue(a.versionKey != b.versionKey)
    }

    @Test
    fun `zero size and negative mtime tolerated`() {
        val a = MediaItem("content://x/1", 0L, -5L)
        assertEquals("content://x/1|0|-5", a.versionKey)
    }
}
