package com.memeocr.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.work.WorkManager
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.memeocr.app.data.*
import com.memeocr.app.media.*
import com.memeocr.app.worker.IndexControl
import com.memeocr.core.TextNormalizer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhotoViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val graph = app as MemeApplication
    private val dao = graph.database.photos()
    val access = MutableStateFlow(AccessLevel.NONE)
    val accessEpoch = MutableStateFlow(0L)
    val query = MutableStateFlow("")
    private val normalized = query.debounce(200).map(TextNormalizer::normalize).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val photos = normalized.flatMapLatest { text ->
        Pager(PagingConfig(pageSize = 60, initialLoadSize = 60, enablePlaceholders = false)) {
            dao.search(text)
        }.flow
    }.cachedIn(viewModelScope)
    val count = normalized.flatMapLatest(dao::observeResultCount)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val stats = dao.observeStats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PhotoStats.EMPTY)
    val works = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(IndexControl.NAME)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val paused = MutableStateFlow(graph.control.paused)
    fun refreshAccess() {
        access.value = PhotoAccess.level(graph)
        accessEpoch.value++
        viewModelScope.launch(Dispatchers.IO) {
            if (access.value != AccessLevel.FULL) dao.hideAll()
            if (access.value != AccessLevel.NONE) graph.control.requestSync()
        }
    }
    fun sync() { viewModelScope.launch(Dispatchers.IO) { graph.control.requestSync() } }
    fun togglePause() {
        viewModelScope.launch(Dispatchers.IO) {
            if (graph.control.paused) graph.control.resume() else graph.control.pause()
            paused.value = graph.control.paused
        }
    }
    fun retry() = viewModelScope.launch(Dispatchers.IO) { dao.retryFailed(); graph.control.resume(); paused.value = false }
}

class MainActivity : ComponentActivity() {
    private val model by viewModels<PhotoViewModel>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.refreshAccess()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) { MemeScreen(model, { permission.launch(PhotoAccess.permissions()) }, ::share) }
        } }
        val prefs = getPreferences(MODE_PRIVATE)
        if (!prefs.getBoolean("asked", false) && PhotoAccess.level(this) == AccessLevel.NONE) {
            prefs.edit().putBoolean("asked", true).apply()
            permission.launch(PhotoAccess.permissions())
        }
    }
    override fun onResume() { super.onResume(); model.refreshAccess() }
    private fun share(photo: PhotoEntity) {
        try {
            val uri = Uri.parse(photo.contentUri)
            contentResolver.openFileDescriptor(uri, "r")?.close() ?: error("图片不可访问")
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: photo.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("meme", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享表情包"))
        } catch (_: Exception) { Toast.makeText(this, "图片已删除或未获授权，请重新同步", Toast.LENGTH_LONG).show(); model.refreshAccess() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemeScreen(model: PhotoViewModel, grant: () -> Unit, share: (PhotoEntity) -> Unit) {
    val query by model.query.collectAsStateWithLifecycle()
    val access by model.access.collectAsStateWithLifecycle()
    val accessEpoch by model.accessEpoch.collectAsStateWithLifecycle()
    val stats by model.stats.collectAsStateWithLifecycle()
    val count by model.count.collectAsStateWithLifecycle()
    val works by model.works.collectAsStateWithLifecycle()
    val paused by model.paused.collectAsStateWithLifecycle()
    val photos = model.photos.collectAsLazyPagingItems()
    // Keep the grid state in the screen scope: preview temporarily removes the grid from composition.
    val gridState = rememberLazyGridState()
    var preview by remember { mutableStateOf<PhotoEntity?>(null) }
    LaunchedEffect(access) { if (access == AccessLevel.NONE) preview = null }
    BackHandler(preview != null) { preview = null }
    val context = LocalContext.current
    LaunchedEffect(accessEpoch, preview?.contentUri) {
        val photo = preview ?: return@LaunchedEffect
        val readable = withContext(Dispatchers.IO) {
            try { context.contentResolver.openFileDescriptor(Uri.parse(photo.contentUri), "r")?.use { true } ?: false }
            catch (_: Exception) { false }
        }
        if (!readable && preview?.contentUri == photo.contentUri) { preview = null; model.sync() }
    }
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 4 else 3
    val active = works.lastOrNull { it.state == WorkInfo.State.RUNNING }
    val queued = works.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    Scaffold(topBar = { TopAppBar(title = { Text(if (preview == null) "Meme 文字搜索" else "图片预览") },
        navigationIcon = { if (preview != null) TextButton(onClick = { preview = null }) { Text("返回") } },
        actions = { preview?.let { photo -> TextButton(onClick = { share(photo) }) { Text("分享") } } }) }) { padding ->
        if (preview != null) {
            val photo = preview!!
            var failed by remember(photo.contentUri) { mutableStateOf(false) }
            Column(Modifier.padding(padding).fillMaxSize()) {
                AsyncImage(ImageRequest.Builder(context).data(photo.contentUri).size(2048)
                    .memoryCacheKey("preview:${photo.contentUri}:${photo.dateModified}:${photo.mediaGeneration}")
                    .diskCachePolicy(CachePolicy.DISABLED).build(), "表情包预览",
                    modifier = Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit,
                    onError = { failed = true })
                Text(if (failed) "图片已删除或未获授权，请返回并同步相册" else photo.ocrText.ifBlank { "未识别到文字" },
                    Modifier.padding(16.dp).heightIn(max = 120.dp), maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        } else Column(Modifier.padding(padding).fillMaxSize()) {
            Text("你的照片始终留在设备上", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
            if (access == AccessLevel.NONE) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text("授权读取相册后，自动识别图片文字。\n输入图片中的原文即可找到表情包。")
                    Spacer(Modifier.height(16.dp)); Button(onClick = grant) { Text("授权读取照片") }
                    TextButton(onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    }) { Text("打开应用权限设置") }
                }
            } else {
                if (access == AccessLevel.PARTIAL) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("仅搜索获准照片", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = grant) { Text("管理照片授权") }
                }
                OutlinedTextField(query, { model.query.value = it }, Modifier.fillMaxWidth().padding(12.dp),
                    placeholder = { Text("Search memes...") }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { model.query.value = "" }) { Text("清除") } })
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("$count 张 · 已识别 ${stats.indexed}/${stats.total}" +
                        if (stats.failed > 0) " · 失败 ${stats.failed}" else "", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = model::sync) { Text("同步") }
                }
                if (paused || active != null || queued || stats.pending > 0 || stats.failed > 0) {
                    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(when {
                            paused -> "索引已暂停，搜索仍可使用"
                            active != null -> active.progress.getString("stage") ?: "正在建立索引"
                            queued -> "等待后台任务，搜索仍可使用"
                            else -> "待识别 ${stats.pending} 张"
                        }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        if (stats.failed > 0) TextButton(onClick = { model.retry() }) { Text("重试") }
                        TextButton(onClick = model::togglePause) { Text(if (paused) "继续" else "暂停") }
                    }
                    if (active != null) LinearProgressIndicator(
                        progress = { if (stats.total == 0) 0f else (stats.indexed + stats.failed).toFloat() / stats.total },
                        modifier = Modifier.fillMaxWidth())
                }
                if (photos.itemCount == 0) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "相册中暂无可访问图片" else "没有匹配的图片\n只匹配已识别的图片文字",
                        style = MaterialTheme.typography.bodyMedium)
                } else LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize(), state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(photos.itemCount) { index -> photos[index]?.let { photo ->
                        AsyncImage(ImageRequest.Builder(context).data(photo.contentUri).size(360)
                            .memoryCacheKey("${photo.contentUri}:${photo.dateModified}:${photo.mediaGeneration}")
                            .diskCachePolicy(CachePolicy.DISABLED).build(), "表情包",
                            Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("photo:${photo.contentUri}")
                                .clickable { preview = photo }, contentScale = ContentScale.Crop)
                    } }
                }
            }
        }
    }
}
