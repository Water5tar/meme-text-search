package com.memeocr.app

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.memeocr.app.data.*
import com.memeocr.core.TextNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoDao
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        dao = db.photos()
    }
    @After fun close() = db.close()
    private fun photo(id: Long, text: String = "我真的会谢 HELLO 100%_", epoch: Long = 1) = PhotoEntity(
        "content://media/external_primary/images/media/$id", id, "external_primary", id, 5, 100,
        mediaGeneration = 5, ocrText = text, normalizedText = TextNormalizer.normalize(text),
        ocrStatus = OcrStatus.INDEXED, indexedAt = 123, seenEpoch = epoch)
    @Test fun literalSearchAndNoSemanticMatches(): Unit = runBlocking {
        dao.put(listOf(photo(1), photo(2, "hello world"), photo(3, "好累")))
        assertEquals(1, dao.observeResultCount(TextNormalizer.normalize(" 我真的\n会谢 ")).first())
        assertEquals(2, dao.observeResultCount(TextNormalizer.normalize("ＨＥＬＬＯ")).first())
        assertEquals(1, dao.observeResultCount("%_").first())
        assertEquals(0, dao.observeResultCount("崩溃").first())
        assertEquals(0, dao.observeResultCount("external_primary").first())
    }
    @Test fun unchangedEmptyAndFailedAreNotQueuedAgain(): Unit = runBlocking {
        val empty = photo(1, "")
        val failed = photo(2).copy(ocrStatus = OcrStatus.FAILED, error = "bad image")
        dao.put(listOf(empty, failed))
        dao.mergeMedia(listOf(empty.copy(ocrStatus = OcrStatus.PENDING, indexedAt = 0),
            failed.copy(ocrStatus = OcrStatus.PENDING)))
        assertEquals(0, dao.pendingCount())
        assertEquals(123, dao.byUri(empty.contentUri)!!.indexedAt)
        dao.retryFailed()
        assertEquals(1, dao.pendingCount())
    }
    @Test fun changesInvalidateAndObsoleteResultsCannotOverwrite(): Unit = runBlocking {
        val old = photo(1)
        dao.put(listOf(old))
        val changed = old.copy(sizeBytes = 200, dateModified = 6, mediaGeneration = 6,
            ocrStatus = OcrStatus.PENDING, normalizedText = "", ocrText = "", indexedAt = 0)
        dao.mergeMedia(listOf(changed))
        assertEquals(1, dao.pendingCount())
        assertEquals(0, dao.finishOcr(old.contentUri, old.sizeBytes, old.dateModified, old.mediaGeneration,
            "obsolete", "obsolete", OcrStatus.INDEXED, "", 999))
        assertEquals("", dao.byUri(old.contentUri)!!.ocrText)
    }
    @Test fun mediaDatabaseResetInvalidatesPreviouslyCachedResults(): Unit = runBlocking {
        dao.put(listOf(photo(1)))
        dao.invalidateVolume("external_primary")
        assertEquals(1, dao.pendingCount())
        assertEquals("", dao.byUri(photo(1).contentUri)!!.normalizedText)
    }
    @Test fun partialPermissionHidesRatherThanDeletesAndFullSyncDeletes(): Unit = runBlocking {
        dao.put(listOf(photo(1, epoch = 2), photo(2, epoch = 1)))
        dao.finishSync(MediaSyncState("external_primary", "v", 5, 0, true), 2)
        assertNotNull(dao.byUri(photo(2).contentUri))
        assertEquals(1, dao.observeStats().first().total)
        dao.markVisible(listOf(photo(2).contentUri), 2)
        assertEquals(2, dao.observeStats().first().total)
        dao.finishSync(MediaSyncState("external_primary", "v", 5, 0, false), 3)
        assertNull(dao.byUri(photo(1).contentUri))
    }
    @Test fun persistentQueueSurvivesDatabaseReopen(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "reopen-test.db"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        disk.photos().put(listOf(photo(1), photo(2).copy(ocrStatus = OcrStatus.PENDING)))
        disk.close()
        disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        assertEquals(1, disk.photos().pendingCount())
        assertEquals(123, disk.photos().byUri(photo(1).contentUri)!!.indexedAt)
        disk.close(); context.deleteDatabase(name)
    }
    @Test fun interruptedMediaSyncRequestSurvivesControllerRecreation(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MemeApplication>()
        app.control.pause()
        kotlinx.coroutines.delay(1000)
        app.control.requestSync()
        assertTrue(app.control.consumeSync())
        val recreated = com.memeocr.app.worker.IndexControl(app)
        assertTrue(recreated.consumeSync())
        recreated.finishSync()
        assertFalse(recreated.consumeSync())
        app.control.resume()
    }
    @Test fun scaleSearchAndPagingAtOneFiveTenAndFiftyThousandRows(): Unit = runBlocking {
        for (size in listOf(1000, 5000, 10000, 50000)) {
            for (start in 1..size step 300) dao.put((start..minOf(size, start + 299)).map {
                photo(it.toLong(), if (it % 10 == 0) "我真的会谢 HELLO $it" else "普通图片 $it")
            })
            val timings = mutableListOf<Double>()
            repeat(5) {
                val begin = System.nanoTime()
                assertEquals(size / 10, dao.observeResultCount("我真的会谢").first())
                val result = dao.search("我真的会谢").load(PagingSource.LoadParams.Refresh(null, 60, false))
                assertTrue(result is PagingSource.LoadResult.Page)
                assertEquals(60, (result as PagingSource.LoadResult.Page).data.size)
                timings.add((System.nanoTime() - begin) / 1_000_000.0)
            }
            Log.i("MemeScale", "rows=$size count+page60_ms=$timings")
        }
    }
}

