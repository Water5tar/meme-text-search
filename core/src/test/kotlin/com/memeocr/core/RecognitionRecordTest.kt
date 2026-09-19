package com.memeocr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRecordTest {
    private fun rec(
        uri: String = "content://x/1",
        size: Long = 10,
        mtime: Long = 20,
        status: Status = Status.DONE,
        text: String = "hi",
    ) = RecognitionRecord(uri, size, mtime, status, text, "", 1000L)

    @Test
    fun `matchesVersion true when all fields equal`() {
        val item = MediaItem("content://x/1", 10, 20)
        assertTrue(rec().matchesVersion(item))
    }

    @Test
    fun `matchesVersion false when size changed`() {
        val item = MediaItem("content://x/1", 11, 20)
        assertFalse(rec().matchesVersion(item))
    }

    @Test
    fun `matchesVersion false when mtime changed`() {
        val item = MediaItem("content://x/1", 10, 21)
        assertFalse(rec().matchesVersion(item))
    }

    @Test
    fun `matchesVersion false when uri changed`() {
        val item = MediaItem("content://x/2", 10, 20)
        assertFalse(rec().matchesVersion(item))
    }

    @Test
    fun `statuses exist`() {
        assertEquals(
            setOf(Status.PENDING, Status.DONE, Status.EMPTY_TEXT, Status.FAILED),
            Status.values().toSet()
        )
    }
}
