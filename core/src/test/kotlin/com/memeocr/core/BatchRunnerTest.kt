package com.memeocr.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BatchRunnerTest {
    private fun item(n: Int) = MediaItem("content://images/" + n, n.toLong() + 10, 50)
    private fun record(i: MediaItem, status: Status = Status.DONE) =
        RecognitionRecord(i.uri, i.size, i.lastModified, status, "文字", "", 100)

    @Test fun unchangedSuccessAndEmptyAreSkipped() {
        val items = (1..4).map(::item)
        val records = mapOf(items[0].uri to record(items[0]),
            items[1].uri to record(items[1], Status.EMPTY_TEXT),
            items[2].uri to record(items[2], Status.FAILED))
        assertEquals(listOf(items[3]), BatchPlan.select(items, records, 10))
        assertEquals(listOf(items[2]), BatchPlan.select(items, records, 10, true))
    }

    @Test fun bothSizeAndModificationTimeInvalidateCache() {
        val original = item(1)
        val cached = mapOf(original.uri to record(original))
        assertEquals(1, BatchPlan.select(listOf(original.copy(size = 999)), cached, 1).size)
        assertEquals(1, BatchPlan.select(listOf(original.copy(lastModified = 99)), cached, 1).size)
        assertEquals(0, BatchPlan.select(listOf(original), cached, 1).size)
    }

    @Test fun changedFailedImageIsEligibleWithoutExplicitRetry() {
        val original = item(1)
        val cached = mapOf(original.uri to record(original, Status.FAILED))
        assertEquals(listOf(original.copy(size = 800)),
            BatchPlan.select(listOf(original.copy(size = 800)), cached, 5))
    }

    @Test fun pendingRecordIsNotTreatedAsSavedSuccess() {
        val i = item(1)
        assertEquals(listOf(i), BatchPlan.select(listOf(i), mapOf(i.uri to record(i, Status.PENDING)), 1))
    }

    @Test fun retryUsesOnlyFailuresInRequestedAlbum() {
        val currentAlbum = listOf(item(1), item(2))
        val elsewhere = item(3)
        val cached = listOf(record(item(1)), record(item(2), Status.FAILED),
            record(elsewhere, Status.FAILED)).associateBy { it.uri }
        assertEquals(listOf(item(2)), BatchPlan.select(currentAlbum, cached, 1000, true))
    }

    @Test fun duplicateMediaUrisDoNotConsumeBatchSlots() {
        assertEquals(listOf(item(1), item(2)),
            BatchPlan.select(listOf(item(1), item(1), item(2)), emptyMap(), 2))
    }

    @Test fun invalidBatchSizesAreRejected() {
        for (n in listOf(-1, 0, 10001, Int.MAX_VALUE)) {
            try {
                BatchPlan.select(listOf(item(1)), emptyMap(), n)
                fail("Accepted invalid size " + n)
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun nineThousandImagesAreSavedOnceAcrossNineBatches() {
        val items = (1..9000).map(::item)
        val current = items.associateBy { it.uri }
        val saved = linkedMapOf<String, RecognitionRecord>()
        var calls = 0
        repeat(9) {
            val batch = BatchPlan.select(items, saved, 1000)
            assertEquals(1000, batch.size)
            val state = BatchRunner(current::get, { calls++; "你好" },
                { rec -> assertNull(saved.put(rec.uri, rec)) }).run(batch, { false }, {})
            assertEquals(1000, state.processed)
            assertEquals(1000, state.succeeded)
            assertFalse(state.running)
        }
        assertEquals(9000, calls)
        assertEquals(9000, saved.size)
        assertTrue(BatchPlan.select(items, saved, 1000).isEmpty())
    }

    @Test fun emptyBatchDoesNotReadRecognizeOrSave() {
        val events = mutableListOf<ProgressState>()
        val result = BatchRunner({ error("stat called") }, { error("OCR called") },
            { error("save called") }).run(emptyList(), { false }, events::add)
        assertEquals(0, result.processed)
        assertFalse(result.running)
        assertEquals(listOf(true, false), events.map { it.running })
    }

    @Test fun cancelledBeforeStartingLeavesEverythingPending() {
        val result = BatchRunner({ error("stat called") }, { error("OCR called") },
            { error("save called") }).run(listOf(item(1)), { true }, {})
        assertTrue(result.cancelled)
        assertEquals(0, result.processed)
    }

    @Test fun cancellingDuringRecognitionSavesCurrentImageAndSkipsRest() {
        val items = (1..3).map(::item)
        val saved = mutableListOf<RecognitionRecord>()
        var cancel = false
        val result = BatchRunner({ uri -> items.first { it.uri == uri } }, {
            cancel = true; "完成这一张"
        }, saved::add).run(items, { cancel }, {})
        assertEquals(1, saved.size)
        assertEquals(Status.DONE, saved[0].status)
        assertEquals(1, result.processed)
        assertEquals(1, result.succeeded)
        assertTrue(result.cancelled)
    }

    @Test fun restartingAfterCancellationDoesNotRecognizeSavedImagesAgain() {
        val items = (1..12).map(::item)
        val lookup = items.associateBy { it.uri }
        val saved = linkedMapOf<String, RecognitionRecord>()
        val called = mutableListOf<String>()
        val runner = BatchRunner(lookup::get, { called.add(it.uri); "文字" }, { saved[it.uri] = it })
        runner.run(items, { saved.size == 5 }, {})
        assertEquals(5, saved.size)
        runner.run(BatchPlan.select(items, saved, 1000), { false }, {})
        assertEquals(items.map { it.uri }, called)
        assertEquals(12, saved.size)
    }

    @Test fun blankRecognitionIsSavedAsCompletedAndNotRetried() {
        val i = item(1)
        val saved = mutableListOf<RecognitionRecord>()
        val result = BatchRunner({ i }, { " \n\t " }, saved::add).run(listOf(i), { false }, {})
        assertEquals(Status.EMPTY_TEXT, saved.single().status)
        assertEquals(1, result.emptyText)
        assertEquals(0, result.failed)
        assertTrue(BatchPlan.select(listOf(i), saved.associateBy { it.uri }, 10).isEmpty())
    }

    @Test fun unreadableImageDoesNotPreventFollowingImage() {
        val items = listOf(item(1), item(2))
        val saved = mutableListOf<RecognitionRecord>()
        val result = BatchRunner({ uri -> items.first { it.uri == uri } }, {
            if (it == items[0]) throw IOException("corrupt")
            "后一张成功"
        }, saved::add).run(items, { false }, {})
        assertEquals(listOf(Status.FAILED, Status.DONE), saved.map { it.status })
        assertEquals(2, result.processed)
        assertEquals(1, result.failed)
        assertEquals(1, result.succeeded)
        assertTrue(result.lastError!!.contains("corrupt"))
    }

    @Test fun missingMediaIsRecordedAndBatchContinues() {
        val saved = mutableListOf<RecognitionRecord>()
        val result = BatchRunner({ uri -> if (uri == item(1).uri) null else item(2) },
            { "second" }, saved::add).run(listOf(item(1), item(2)), { false }, {})
        assertEquals(2, saved.size)
        assertEquals(Status.FAILED, saved[0].status)
        assertEquals(1, result.succeeded)
    }

    @Test fun permissionLossDuringMetadataReadStopsBatch() {
        var reads = 0
        val saved = mutableListOf<RecognitionRecord>()
        val result = BatchRunner({ reads++; throw SecurityException() },
            { error("must not OCR") }, saved::add)
            .run(listOf(item(1), item(2)), { false }, {})
        assertEquals(1, reads)
        assertEquals(1, saved.size)
        assertEquals(1, result.failed)
        assertTrue(result.lastError!!.contains("权限"))
    }

    @Test fun permissionLossDuringImageReadStopsBatch() {
        val saved = mutableListOf<RecognitionRecord>()
        val result = BatchRunner({ item(1) }, { throw SecurityException() }, saved::add)
            .run(listOf(item(1), item(2)), { false }, {})
        assertEquals(1, saved.size)
        assertEquals(1, result.processed)
        assertFalse(result.running)
    }

    @Test fun changedDuringRecognitionNeverSavesWrongTextAsSuccess() {
        val i = item(1)
        var reads = 0
        val saved = mutableListOf<RecognitionRecord>()
        BatchRunner({ if (reads++ == 0) i else i.copy(size = 999) }, { "stale" }, saved::add)
            .run(listOf(i), { false }, {})
        assertEquals(Status.FAILED, saved.single().status)
        assertEquals("", saved.single().text)
        assertTrue(saved.single().error.contains("发生变化"))
        assertEquals(1, BatchPlan.select(listOf(i.copy(size = 999)),
            saved.associateBy { it.uri }, 10).size)
    }

    @Test fun diskFailureCannotBeReportedAsSuccessfulProgress() {
        val published = mutableListOf<ProgressState>()
        try {
            BatchRunner({ item(1) }, { "text" }, { throw IOException("disk full") })
                .run(listOf(item(1), item(2)), { false }, published::add)
            fail("Save failure should stop the run")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertTrue(published.all { it.processed == 0 && it.succeeded == 0 })
    }

    @Test fun progressNeverDoubleCountsOnCompletion() {
        val items = (1..3).map(::item)
        val published = mutableListOf<ProgressState>()
        val saved = mutableListOf<RecognitionRecord>()
        val result = BatchRunner({ uri -> items.first { it.uri == uri } }, {
            when (it) {
                items[0] -> "yes"
                items[1] -> ""
                else -> throw IOException("broken")
            }
        }, saved::add, { 456L }).run(items, { false }, published::add)
        assertEquals(3, result.processed)
        assertEquals(1, result.succeeded)
        assertEquals(1, result.emptyText)
        assertEquals(1, result.failed)
        assertTrue(saved.all { it.updatedAt == 456L })
        assertTrue(published.all { it.processed == it.succeeded + it.emptyText + it.failed })
        assertEquals(listOf(0, 1, 2, 3, 3), published.map { it.processed })
    }

    @Test fun rawRegexPreservesNewlinesAndCase() {
        val r = record(item(1)).copy(text = "Hello\nWorld")
        assertEquals(1, TextSearch.search(listOf(r), SearchMode.Regex("Hello\\nWorld")).size)
        assertEquals(0, TextSearch.search(listOf(r), SearchMode.Regex("hello")).size)
        assertEquals(1, TextSearch.search(listOf(r), SearchMode.Literal("hello world")).size)
    }

    @Test fun explicitRetryThenSuccessLeavesNoFailureToRetry() {
        val i = item(1)
        val saved = mutableMapOf(i.uri to record(i, Status.FAILED))
        val batch = BatchPlan.select(listOf(i), saved, 1000, true)
        BatchRunner({ i }, { "recovered" }, { saved[it.uri] = it }).run(batch, { false }, {})
        assertEquals("recovered", saved.getValue(i.uri).text)
        assertTrue(BatchPlan.select(listOf(i), saved, 1000, true).isEmpty())
        assertTrue(BatchPlan.select(listOf(i), saved, 1000).isEmpty())
    }
}
