package com.memeocr.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.VerificationModes.times
import android.app.Instrumentation.ActivityResult
import com.memeocr.app.data.OcrStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeFlowTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val uris = mutableListOf<Uri>()
    @After fun cleanup() {
        val app = compose.activity.application as MemeApplication
        uris.forEach { app.contentResolver.delete(it, null, null) }
        app.control.requestSync()
    }
    @Test fun previewReturnKeepsScrolledPhotoVisible() {
        val app = compose.activity.application as MemeApplication
        val resolver = app.contentResolver
        val picture = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .context.assets.open("chinese.png").use { it.readBytes() }
        repeat(42) { i ->
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "scroll-test-${System.currentTimeMillis()}-$i.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MemeTest")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })!!
            uris.add(uri)
            resolver.openOutputStream(uri)!!.use { it.write(picture) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        app.control.resume()
        compose.waitUntil(120_000) {
            runBlocking { app.database.photos().observeStats().first().total >= 42 }
        }
        compose.waitUntil(15_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(24)
        val visible = compose.onAllNodes(SemanticsMatcher("photo tag") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("photo:") == true
        }).fetchSemanticsNodes()
        val selectedTag = visible[visible.size / 2].config[SemanticsProperties.TestTag]
        compose.onNodeWithTag(selectedTag).performClick()
        compose.onNodeWithText("图片预览").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag(selectedTag).assertIsDisplayed()
    }
    @Test fun searchGridPreviewAndReturnUseActualOcr() {
        val app = compose.activity.application as MemeApplication
        val resolver = app.contentResolver
        for ((i, words) in listOf("笑死 HELLO", "我真的会谢 WORLD").withIndex()) {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ui-test-${System.currentTimeMillis()}-$i.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MemeTest")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })!!
            uris.add(uri)
            val image = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
            Canvas(image).apply {
                drawColor(Color.WHITE)
                drawText(words, 40f, 200f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 76f })
            }
            resolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        app.control.resume()
        compose.waitUntil(120_000) {
            runBlocking { app.database.photos().byUris(uris.map {
                "content://media/external_primary/images/media/${it.lastPathSegment}"
            }).count { it.ocrStatus == OcrStatus.INDEXED } == 2 }
        }
        compose.onNode(hasSetTextAction()).performTextInput("笑死")
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("表情包").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("表情包").performClick()
        compose.onNodeWithText("图片预览").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed()
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(android.app.Activity.RESULT_OK, null))
            compose.onNodeWithText("分享").performClick()
            Intents.intended(hasAction(Intent.ACTION_CHOOSER), times(1))
            val chooser = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            org.junit.Assert.assertEquals(Intent.ACTION_SEND, send.action)
            org.junit.Assert.assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            org.junit.Assert.assertEquals("image/png", send.type)
            org.junit.Assert.assertEquals("content", send.clipData!!.getItemAt(0).uri.scheme)
            @Suppress("DEPRECATION")
            val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            org.junit.Assert.assertEquals(send.clipData!!.getItemAt(0).uri, stream)
        } finally { Intents.release() }
        compose.onNodeWithText("返回").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("崩溃")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("没有匹配的图片\n只匹配已识别的图片文字").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("hello")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("表情包").fetchSemanticsNodes().size == 1 }
    }
}
