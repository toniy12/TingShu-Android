package com.example.tingshu

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor


private val Bg = Color(0xFF090D18)
private val Panel = Color(0xFF151C2D)
private val Accent = Color(0xFF9B8CFF)
private val Muted = Color(0xFF9AA5BC)

private data class AudioSource(val title: String, val author: String, val uri: String, val kind: String)
private data class PublicBook(val id: String, val title: String, val author: String, val description: String, val rss: String)
private data class Chapter(val title: String, val url: String)
private data class PlayerUi(
    val mediaId: String = "", val title: String = "", val isPlaying: Boolean = false,
    val position: Long = 0L, val duration: Long = 0L, val speed: Float = 1f,
    val hasPrev: Boolean = false, val hasNext: Boolean = false
)

private val SpeedSteps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private val SleepSteps = listOf(0, 15, 30, 45, 60, 90)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)
    private var pendingAction: ((MediaController) -> Unit)? = null
    private var playerUi by mutableStateOf(PlayerUi())
    private var history by mutableStateOf<List<AudioSource>>(emptyList())
    private var sleepEndAt by mutableLongStateOf(0L)
    private var sleepJob: Job? = null
    private var tick = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refreshUi(player) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveProgress() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { mediaItem?.let { recordHistory(it) } }
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@MainActivity, "播放失败：${error.errorCodeName}（请检查网络、链接或文件是否有效）", Toast.LENGTH_LONG).show()
        }
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                runCatching {
                    val c = future.get()
                    mediaController = c
                    c.addListener(playerListener)
                    refreshUi(c)
                    pendingAction?.invoke(c)
                    pendingAction = null
                }
            }, Executor { it.run() })
        }
    }

    override fun onStop() { saveProgress(); super.onStop() }

    override fun onDestroy() {
        sleepJob?.cancel()
        mediaController?.removeListener(playerListener)
        mediaController = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    private fun refreshUi(p: Player) {
        playerUi = PlayerUi(
            mediaId = p.currentMediaItem?.mediaId.orEmpty(),
            title = p.mediaMetadata.title?.toString().orEmpty(),
            isPlaying = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING),
            position = p.currentPosition.coerceAtLeast(0L),
            duration = if (p.duration == C.TIME_UNSET) 0L else p.duration.coerceAtLeast(0L),
            speed = p.playbackParameters.speed,
            hasPrev = p.hasPreviousMediaItem(),
            hasNext = p.hasNextMediaItem()
        )
    }

    private fun prefs() = getSharedPreferences("tingshu", MODE_PRIVATE)
    private fun posKey(id: String) = "pos_${id.hashCode()}"

    private fun saveProgress() {
        val c = mediaController ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        if (id.isBlank()) return
        val dur = c.duration
        val pos = c.currentPosition
        val value = if (dur != C.TIME_UNSET && pos > dur - 3000) 0L else pos.coerceAtLeast(0L)
        prefs().edit().putLong(posKey(id), value).apply()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = mediaController
        if (c == null) pendingAction = block else block(c)
    }

    /** 播放一个队列（章节连播 / 书架顺序播放），并从上次保存的进度继续。 */
    private fun playQueue(items: List<AudioSource>, startIndex: Int) {
        if (items.isEmpty()) return
        val start = startIndex.coerceIn(items.indices)
        withController { c ->
            saveProgress()
            val mediaItems = items.map { s ->
                MediaItem.Builder().setMediaId(s.uri).setUri(s.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.author).build()).build()
            }
            val resume = prefs().getLong(posKey(items[start].uri), 0L)
            c.setMediaItems(mediaItems, start, resume)
            c.prepare()
            c.play()
        }
    }

    private fun togglePlay() {
        mediaController?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun cycleSpeed() {
        val c = mediaController ?: return
        val idx = SpeedSteps.indexOfFirst { it == c.playbackParameters.speed }
        c.setPlaybackSpeed(SpeedSteps[(idx + 1) % SpeedSteps.size])
    }

    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel(); sleepJob = null
        if (minutes <= 0) { sleepEndAt = 0L; Toast.makeText(this, "已关闭定时", Toast.LENGTH_SHORT).show(); return }
        sleepEndAt = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = lifecycleScope.launch {
            delay(minutes * 60_000L)
            mediaController?.pause()
            sleepEndAt = 0L; sleepJob = null
        }
        Toast.makeText(this, "将在 $minutes 分钟后暂停播放", Toast.LENGTH_SHORT).show()
    }

    private fun recordHistory(item: MediaItem) {
        val uri = item.mediaId
        if (uri.isBlank()) return
        val src = AudioSource(
            item.mediaMetadata.title?.toString().orEmpty().ifBlank { "未命名音频" },
            item.mediaMetadata.artist?.toString().orEmpty(), uri,
            if (uri.startsWith("content:")) "local" else "public"
        )
        history = (listOf(src) + history.filterNot { it.uri == uri }).take(30)
        saveList("history", history)
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "本地音频"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        return name
    }

    private fun loadList(key: String): List<AudioSource> {
        val raw = prefs().getString(key, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                AudioSource(o.optString("title"), o.optString("author"), o.optString("uri"), o.optString("kind"))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveList(key: String, items: List<AudioSource>) {
        val array = JSONArray()
        items.forEach { s -> array.put(JSONObject().put("title", s.title).put("author", s.author).put("uri", s.uri).put("kind", s.kind)) }
        prefs().edit().putString(key, array.toString()).apply()
    }

    private fun saveSources(items: List<AudioSource>) = saveList("sources", items)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = loadList("history")
        connectController()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            var selectedTab by remember { mutableIntStateOf(0) }
            var sources by remember { mutableStateOf(loadList("sources")) }
            var books by remember { mutableStateOf<List<PublicBook>>(emptyList()) }
            var loading by remember { mutableStateOf(false) }
            var search by remember { mutableStateOf("") }
            var status by remember { mutableStateOf("公开书库来自 LibriVox，当前显示可公开访问的作品") }
            var showUrlDialog by remember { mutableStateOf(false) }
            var urlInput by remember { mutableStateOf("") }
            var urlTitle by remember { mutableStateOf("") }
            var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
            var chapterBookTitle by remember { mutableStateOf("") }
            var showChapters by remember { mutableStateOf(false) }
            var showHistory by remember { mutableStateOf(false) }
            var showSleep by remember { mutableStateOf(false) }
            var showAbout by remember { mutableStateOf(false) }
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

            // 播放进度轮询 + 周期性保存进度
            LaunchedEffect(mediaController) {
                while (true) {
                    now = System.currentTimeMillis()
                    mediaController?.let { c ->
                        if (c.isPlaying) { refreshUi(c); if (++tick % 10 == 0) saveProgress() }
                    }
                    delay(500)
                }
            }

            val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val name = displayName(uri)
                    val item = AudioSource(name, "本地文件", uri.toString(), "local")
                    sources = (sources.filterNot { it.uri == item.uri } + item)
                    saveSources(sources)
                    playQueue(listOf(item), 0)
                    status = "已导入并开始播放：$name"
                }
            }

            fun loadPublicBooks(query: String) {
                loading = true
                status = "正在连接免费公开书库…"
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { fetchLibriVoxBooks(query) }
                    books = result
                    loading = false
                    status = if (result.isEmpty()) "暂时没有找到结果，请检查网络、更换关键词或稍后重试" else "找到 ${result.size} 本公开有声书 · LibriVox"
                }
            }

            fun openBook(book: PublicBook) {
                chapterBookTitle = book.title
                loading = true
                status = "正在获取《${book.title}》章节…"
                lifecycleScope.launch {
                    val list = withContext(Dispatchers.IO) { fetchRssChapters(book.rss) }
                    chapters = list
                    loading = false
                    if (list.isNotEmpty()) { showChapters = true; status = "《${book.title}》共 ${list.size} 章" }
                    else status = "该书暂时没有可识别的在线音频章节，请稍后重试"
                }
            }

            LaunchedEffect(Unit) { loadPublicBooks("") }

            val sleepLabel = if (sleepEndAt > now) "${(sleepEndAt - now) / 60000 + 1} 分后暂停" else "定时关闭"

            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Accent, onBackground = Color.White, onSurface = Color.White)) {
                Scaffold(containerColor = Bg, bottomBar = {
                    NavigationBar(containerColor = Color(0xFF101625), contentColor = Color.White) {
                        listOf("书库", "书架", "导入", "我的").forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = { Icon(when (index) { 0 -> Icons.Default.Explore; 1 -> Icons.Default.Bookmarks; 2 -> Icons.Default.AddCircle; else -> Icons.Default.Person }, null) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Color(0xFF292342), unselectedIconColor = Muted, unselectedTextColor = Muted)
                            )
                        }
                    }
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Box(Modifier.weight(1f)) {
                            when (selectedTab) {
                                0 -> LibraryScreen(
                                    books = books, loading = loading, status = status, search = search,
                                    onSearchChange = { search = it }, onSearch = { loadPublicBooks(search) },
                                    onLoadLatest = { loadPublicBooks("") }, onOpenBook = { openBook(it) },
                                    onImport = { importAudio.launch(arrayOf("audio/*")) }
                                )
                                1 -> ShelfScreen(sources, playerUi.mediaId, onPlay = { source ->
                                    playQueue(sources, sources.indexOfFirst { it.uri == source.uri })
                                }, onDelete = { source -> sources = sources.filterNot { it.uri == source.uri }; saveSources(sources) },
                                    onImport = { importAudio.launch(arrayOf("audio/*")) })
                                2 -> ImportScreen(onPickFile = { importAudio.launch(arrayOf("audio/*")) }, onAddUrl = { showUrlDialog = true }, sources = sources.size)
                                else -> ProfileScreen(
                                    sourceCount = sources.size,
                                    onImport = { selectedTab = 2 },
                                    onHistory = { showHistory = true },
                                    onSleep = { showSleep = true },
                                    onDownloads = { Toast.makeText(this@MainActivity, "下载管理将在后续版本提供", Toast.LENGTH_SHORT).show() },
                                    onAbout = { showAbout = true }
                                )
                            }
                        }
                        MiniPlayer(
                            ui = playerUi, sleepLabel = sleepLabel,
                            onToggle = { togglePlay() },
                            onSeek = { mediaController?.seekTo(it); mediaController?.let { c -> refreshUi(c) } },
                            onPrev = { mediaController?.seekToPreviousMediaItem() },
                            onNext = { mediaController?.seekToNextMediaItem() },
                            onSpeed = { cycleSpeed() },
                            onSleep = { showSleep = true }
                        )
                    }
                }

                if (showUrlDialog) {
                    AlertDialog(
                        onDismissRequest = { showUrlDialog = false },
                        title = { Text("导入网络音源") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("粘贴可直接播放的 MP3、M4A、AAC、WAV 或 HLS 音频地址。需要是你有权使用的音源。仅支持 https（http 会自动升级为 https）。", color = Muted, fontSize = 12.sp)
                                OutlinedTextField(value = urlTitle, onValueChange = { urlTitle = it }, label = { Text("音源名称（可选）") }, singleLine = true)
                                OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text("音频 URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val parsed = runCatching { Uri.parse(urlInput.trim()) }.getOrNull()
                                if (parsed != null && parsed.scheme in listOf("http", "https") && parsed.host != null) {
                                    val secure = parsed.buildUpon().scheme("https").build().toString()
                                    val title = urlTitle.trim().ifBlank { parsed.lastPathSegment?.substringAfterLast('/')?.ifBlank { "网络音源" } ?: "网络音源" }
                                    val item = AudioSource(title, "网络音源", secure, "remote")
                                    sources = (sources.filterNot { it.uri == item.uri } + item)
                                    saveSources(sources)
                                    playQueue(listOf(item), 0)
                                    status = "已添加网络音源：$title"
                                    showUrlDialog = false; urlInput = ""; urlTitle = ""
                                    selectedTab = 1
                                } else Toast.makeText(this@MainActivity, "请输入有效的 http 或 https 音频地址", Toast.LENGTH_SHORT).show()
                            }) { Text("添加并播放") }
                        },
                        dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("取消") } }
                    )
                }

                if (showChapters) {
                    AlertDialog(
                        onDismissRequest = { showChapters = false },
                        title = { Text(chapterBookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        text = {
                            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                items(chapters.size) { index ->
                                    val chapter = chapters[index]
                                    Row(Modifier.fillMaxWidth().clickable {
                                        val queue = chapters.map { AudioSource(it.title, chapterBookTitle, it.url, "public") }
                                        val item = queue[index]
                                        sources = (sources.filterNot { it.uri == item.uri } + item); saveSources(sources)
                                        playQueue(queue, index)
                                        showChapters = false
                                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PlayCircle, null, tint = Accent); Spacer(Modifier.width(10.dp))
                                        Text(chapter.title, color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                    }
                                    HorizontalDivider(color = Color(0xFF293248))
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showChapters = false }) { Text("关闭") } }
                    )
                }

                if (showHistory) {
                    AlertDialog(
                        onDismissRequest = { showHistory = false },
                        title = { Text("播放历史") },
                        text = {
                            if (history.isEmpty()) Text("还没有播放记录", color = Muted)
                            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                items(history.size) { index ->
                                    val h = history[index]
                                    Column(Modifier.fillMaxWidth().clickable { playQueue(listOf(h), 0); showHistory = false }.padding(vertical = 10.dp)) {
                                        Text(h.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (h.author.isNotBlank()) Text(h.author, color = Muted, fontSize = 11.sp, maxLines = 1)
                                    }
                                    HorizontalDivider(color = Color(0xFF293248))
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } },
                        dismissButton = { TextButton(enabled = history.isNotEmpty(), onClick = { history = emptyList(); saveList("history", history) }) { Text("清空") } }
                    )
                }

                if (showSleep) {
                    AlertDialog(
                        onDismissRequest = { showSleep = false },
                        title = { Text("定时关闭") },
                        text = {
                            Column {
                                SleepSteps.forEach { m ->
                                    TextButton(onClick = { setSleepTimer(m); showSleep = false }) {
                                        Text(if (m == 0) "不启用" else "$m 分钟后暂停", color = Color.White)
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showSleep = false }) { Text("关闭") } }
                    )
                }

                if (showAbout) {
                    AlertDialog(
                        onDismissRequest = { showAbout = false },
                        title = { Text("关于听书") },
                        text = { Text("听书 0.3.0\n公开书库：LibriVox（公有领域有声书）\n支持本地音频、网络音源、书架、章节连播、倍速、定时关闭、进度记忆与通知栏控制。\n请仅导入你拥有或获准使用的音源。", color = Muted, fontSize = 13.sp) },
                        confirmButton = { TextButton(onClick = { showAbout = false }) { Text("好的") } }
                    )
                }
            }
        }
    }
}

private fun fetchLibriVoxBooks(query: String): List<PublicBook> = runCatching {
    val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
    val endpoint = if (query.isBlank()) "https://librivox.org/api/feed/audiobooks?format=json&extended=1&limit=30&sort_order=desc"
    else "https://librivox.org/api/feed/audiobooks?format=json&extended=1&limit=30&title=$encoded"
    val json = JSONObject(httpGet(endpoint))
    val array = json.optJSONArray("books") ?: json.optJSONArray("audiobooks") ?: JSONArray()
    (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val authors = o.optJSONArray("authors")
        val author = authors?.optJSONObject(0)?.optString("first_name", "")?.let { first ->
            val last = authors.optJSONObject(0)?.optString("last_name", "") ?: ""
            "$first $last".trim()
        } ?: "LibriVox 志愿朗读者"
        val rss = o.optString("url_rss", "")
        if (rss.isBlank()) null else PublicBook(o.optString("id"), o.optString("title", "未命名作品"), author,
            o.optString("description", "公开领域作品 · 志愿者朗读").replace(Regex("<[^>]*>"), "").trim(), rss.replaceFirst("http://", "https://"))
    }
}.getOrDefault(emptyList())

private fun fetchRssChapters(rssUrl: String): List<Chapter> = runCatching {
    val connection = URL(rssUrl).openConnection() as HttpURLConnection
    connection.connectTimeout = 15000; connection.readTimeout = 20000
    connection.setRequestProperty("User-Agent", "TingShu-Android/0.3")
    val parser = Xml.newPullParser()
    parser.setInput(connection.inputStream, "UTF-8")
    val result = mutableListOf<Chapter>()
    var event = parser.eventType
    var inItem = false; var title = "章节"; var audio = ""
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                when (parser.name.lowercase()) {
                    "item" -> { inItem = true; title = "章节"; audio = "" }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "enclosure" -> if (inItem) audio = parser.getAttributeValue(null, "url") ?: ""
                    "content" -> if (inItem && audio.isBlank()) audio = parser.getAttributeValue("http://www.w3.org/2005/Atom", "url") ?: ""
                }
            }
            XmlPullParser.END_TAG -> if (parser.name.equals("item", true)) {
                if (audio.startsWith("http")) result.add(Chapter(title, audio.replaceFirst("http://", "https://")))
                inItem = false
            }
        }
        event = parser.next()
    }
    connection.disconnect()
    result
}.getOrDefault(emptyList())

private fun httpGet(address: String): String {
    val connection = URL(address).openConnection() as HttpURLConnection
    connection.connectTimeout = 15000; connection.readTimeout = 20000
    connection.setRequestProperty("User-Agent", "TingShu-Android/0.3")
    return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also { connection.disconnect() }
}

@Composable
private fun LibraryScreen(books: List<PublicBook>, loading: Boolean, status: String, search: String,
    onSearchChange: (String) -> Unit, onSearch: () -> Unit, onLoadLatest: () -> Unit,
    onOpenBook: (PublicBook) -> Unit, onImport: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column { Text("听书", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White); Text("免费公开书库 · 个人音源", color = Muted, fontSize = 13.sp) }
                Box(Modifier.size(44.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Headphones, null, tint = Accent) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF342B63), Color(0xFF172B46))), RoundedCornerShape(22.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("发现值得听的故事", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("探索公开授权的有声作品", color = Color(0xFFD2D0E8), fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp)); Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF151229))) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(5.dp)); Text("导入我的音源") }
                }
                Icon(Icons.Default.GraphicEq, null, tint = Color(0xFFC5BFFF), modifier = Modifier.size(54.dp))
            }
        }
        item {
            OutlinedTextField(value = search, onValueChange = onSearchChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索公开书库标题") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { IconButton(onClick = onSearch) { Icon(Icons.Default.ArrowForward, "搜索") } })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(status, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onLoadLatest) { Text("最新作品") }
            }
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
        if (books.isEmpty() && !loading) item { EmptyLibrary(onLoadLatest) }
        items(books) { book ->
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).clickable { onOpenBook(book) }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(62.dp).background(Brush.linearGradient(listOf(Color(0xFF51477F), Color(0xFF243A54))), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoStories, null, tint = Color(0xFFE0DCFF), modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.author, color = Accent, fontSize = 11.sp)
                    Text(book.description.ifBlank { "公开领域有声作品 · 点击查看章节" }, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.PlayCircle, null, tint = Accent, modifier = Modifier.size(28.dp))
            }
        }
        item { Text("资源说明：公开书库接入 LibriVox 目录 API。作品及录音的权利状态可能因国家/地区而异，请在使用前核对当地适用规则。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp) }
    }
}

@Composable private fun EmptyLibrary(onLoad: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.LibraryMusic, null, tint = Muted, modifier = Modifier.size(48.dp)); Text("还没有加载书籍", color = Color.White)
        OutlinedButton(onClick = onLoad) { Text("加载公开书库") }
    }
}

@Composable
private fun ShelfScreen(sources: List<AudioSource>, activeUri: String, onPlay: (AudioSource) -> Unit, onDelete: (AudioSource) -> Unit, onImport: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { Text("我的书架", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("本地音频、网络音源和公开书库章节", color = Muted, fontSize = 12.sp) }
        if (sources.isEmpty()) item { EmptyState("书架还是空的", "从手机导入音频，或添加网络音源", onImport) }
        items(sources) { source ->
            Row(Modifier.fillMaxWidth().background(if (source.uri == activeUri) Color(0xFF241F42) else Panel, RoundedCornerShape(16.dp)).clickable { onPlay(source) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (source.kind == "local") Icons.Default.AudioFile else Icons.Default.Podcasts, null, tint = Accent, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) {
                    Text(source.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when (source.kind) { "local" -> "本地音频"; "public" -> "公开书库"; else -> "网络音源" }, color = Muted, fontSize = 11.sp)
                }
                IconButton(onClick = { onPlay(source) }) { Icon(Icons.Default.PlayCircle, null, tint = Accent) }
                IconButton(onClick = { onDelete(source) }) { Icon(Icons.Default.DeleteOutline, null, tint = Muted) }
            }
        }
    }
}

@Composable
private fun ImportScreen(onPickFile: () -> Unit, onAddUrl: () -> Unit, sources: Int) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("音源导入中心", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("把你有权使用的音频添加到个人书架", color = Muted)
        ImportCard("从手机选择音频", "浏览手机文件，支持系统可识别的音频格式", Icons.Default.FolderOpen, "选择本地文件", onPickFile)
        ImportCard("添加网络音源", "粘贴可直接播放的音频 URL，例如 MP3 / M4A / AAC / WAV / HLS", Icons.Default.Link, "添加音频地址", onAddUrl)
        Box(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Column { Text("已添加到书架", color = Color.White, fontWeight = FontWeight.Bold); Text("$sources 个音源", color = Accent, fontSize = 24.sp); Text("添加后可在书架中再次播放或删除。", color = Muted, fontSize = 11.sp) }
        }
        Text("注意：网络地址必须指向可访问的音频流，网页链接、需要登录/DRM 的平台链接通常不能直接播放。请仅导入你拥有或获准使用的音源。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable private fun ImportCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Accent, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(10.dp)); Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Text(subtitle, color = Muted, fontSize = 12.sp)
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF151229))) { Text(action) }
    }
}

@Composable private fun ProfileScreen(sourceCount: Int, onImport: () -> Unit, onHistory: () -> Unit, onSleep: () -> Unit, onDownloads: () -> Unit, onAbout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("我的", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AccountCircle, null, tint = Accent, modifier = Modifier.size(52.dp)); Spacer(Modifier.width(12.dp)); Column { Text("听书用户", color = Color.White, fontWeight = FontWeight.Bold); Text("已保存 $sourceCount 个音源", color = Muted, fontSize = 12.sp) } }
        }
        listOf("音源导入中心" to onImport, "播放历史" to onHistory, "定时关闭" to onSleep, "下载管理（开发中）" to onDownloads, "关于听书" to onAbout).forEach { (label, action) ->
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).clickable { action() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, color = Color.White, modifier = Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = Muted) }
        }
    }
}

@Composable private fun EmptyState(title: String, subtitle: String, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.LibraryMusic, null, tint = Muted, modifier = Modifier.size(56.dp)); Spacer(Modifier.height(12.dp)); Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp); Spacer(Modifier.height(16.dp)); OutlinedButton(onClick = onImport) { Text("添加音源") }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%02d:%02d", m, sec)
}

@Composable private fun MiniPlayer(ui: PlayerUi, sleepLabel: String, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit, onSpeed: () -> Unit, onSleep: () -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().background(Color(0xFF171E30)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Color(0xFF34305A), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Headphones, null, tint = Accent) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.title.ifBlank { "还没有开始播放" }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("听书播放器", color = Muted, fontSize = 10.sp)
            }
            IconButton(onClick = onPrev, enabled = ui.hasPrev) { Icon(Icons.Default.SkipPrevious, "上一章", tint = if (ui.hasPrev) Color.White else Muted) }
            IconButton(onClick = onToggle) { Icon(if (ui.isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled, null, tint = Accent, modifier = Modifier.size(34.dp)) }
            IconButton(onClick = onNext, enabled = ui.hasNext) { Icon(Icons.Default.SkipNext, "下一章", tint = if (ui.hasNext) Color.White else Muted) }
        }
        if (ui.duration > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(drag?.toLong() ?: ui.position), color = Muted, fontSize = 10.sp)
                Slider(
                    value = (drag ?: ui.position.toFloat()).coerceIn(0f, ui.duration.toFloat()),
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { onSeek(it.toLong()) }; drag = null },
                    valueRange = 0f..ui.duration.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0xFF2C3550)),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(fmt(ui.duration), color = Muted, fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onSpeed) { Icon(Icons.Default.Speed, null, tint = Accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("倍速 ${ui.speed}x", color = Accent, fontSize = 12.sp) }
            TextButton(onClick = onSleep) { Icon(Icons.Default.Bedtime, null, tint = Accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(sleepLabel, color = Accent, fontSize = 12.sp) }
        }
    }
}
