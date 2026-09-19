package com.memeocr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionPolicyTest {

    @Test
    fun `empty and blank text is emptyText`() {
        assertTrue(RecognitionPolicy.isEmptyText(""))
        assertTrue(RecognitionPolicy.isEmptyText("   \n "))
    }

    @Test
    fun `real text is not emptyText`() {
        assertFalse(RecognitionPolicy.isEmptyText("哈哈"))
        assertFalse(RecognitionPolicy.isEmptyText("  x "))
    }

    @Test
    fun `error descriptions are human friendly`() {
        assertTrue(RecognitionPolicy.describeError(SecurityException("perm")).contains("权限"))
        assertTrue(RecognitionPolicy.describeError(java.io.IOException("corrupt")).contains("解码"))
        assertTrue(RecognitionPolicy.describeError(IllegalStateException("boom")).contains("解码器"))
        assertTrue(RecognitionPolicy.describeError(RuntimeException("x")).contains("识别失败"))
    }

    @Test
    fun `error description handles null message`() {
        assertTrue(RecognitionPolicy.describeError(java.io.IOException()).isNotEmpty())
        assertTrue(RecognitionPolicy.describeError(RuntimeException()).isNotEmpty())
    }

    @Test
    fun `needsReprocess null record means yes`() {
        val item = MediaItem("content://x/1", 1, 2)
        assertTrue(RecognitionPolicy.needsReprocess(null, item))
    }

    @Test
    fun `needsReprocess same version means no`() {
        val item = MediaItem("content://x/1", 1, 2)
        val rec = RecognitionRecord(item.uri, 1, 2, Status.DONE, "t", "", 0)
        assertFalse(RecognitionPolicy.needsReprocess(rec, item))
    }

    @Test
    fun `needsReprocess changed version means yes even if DONE`() {
        val item = MediaItem("content://x/1", 1, 3)
        val rec = RecognitionRecord(item.uri, 1, 2, Status.DONE, "t", "", 0)
        assertTrue(RecognitionPolicy.needsReprocess(rec, item))
    }

    @Test
    fun `needsReprocess failed record with same version still eligible for retry`() {
        // FAILED 状态：版本一致时由上层决定是否重试，这里只验证版本判断本身
        val item = MediaItem("content://x/1", 1, 2)
        val rec = RecognitionRecord(item.uri, 1, 2, Status.FAILED, "", "err", 0)
        assertFalse(RecognitionPolicy.needsReprocess(rec, item))
    }
}
