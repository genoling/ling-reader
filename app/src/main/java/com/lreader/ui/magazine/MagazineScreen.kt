package com.lreader.ui.magazine

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lreader.data.BookRepository
import com.lreader.data.Magazine
import com.lreader.data.MagazineRepository
import com.lreader.dict.DictCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 外刊杂志：直接浏览 GitHub 仓库 `hehonghui/awesome-english-ebooks` 的期号，
 * 选一期下载 → epub 自动导入书架（带封面），之后与普通书籍一样阅读、点词查词。
 *
 * 目录走 jsDelivr / GitHub API（带本地缓存），下载走 jsDelivr CDN → raw → ghproxy 三级重试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagazineScreen() {
    val context = LocalContext.current
    val mags = remember { MagazineRepository(context) }
    val books = remember { BookRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var list by remember { mutableStateOf<List<Magazine>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var group by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var shelfTitles by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun refreshShelf() {
        shelfTitles = books.load().map { it.title }.toSet()
    }

    fun load() {
        scope.launch {
            loading = true
            error = null
            mags.list()
                .onSuccess { list = it }
                .onFailure { error = it.message ?: "获取外刊目录失败" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshShelf()
        load()
    }

    fun download(mag: Magazine) {
        if (downloading != null) return
        downloading = mag.title
        progress = 0f
        scope.launch {
            val file = mags.download(mag) { progress = it }
            if (file == null) {
                snackbar.showSnackbar("下载失败：${mag.title}（换个网络或稍后重试）")
            } else {
                val imported = withContext(Dispatchers.IO) {
                    val book = books.importFile(file) ?: return@withContext null
                    val l = books.load().toMutableList()
                    if (l.none { it.id == book.id }) l.add(book)
                    books.save(l)
                    books.ensureCover(book)
                }
                file.delete()
                refreshShelf()
                snackbar.showSnackbar(
                    if (imported != null) "已加入书架：${mag.title}" else "导入失败"
                )
            }
            downloading = null
            progress = 0f
        }
    }

    val groups = remember(list) { list.map { it.group }.distinct() }
    val shown = if (group == null) list else list.filter { it.group == group }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外刊杂志", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { load() }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新目录")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "选一期下载，epub 会自动进入书架，可点词查词、分级高亮。" +
                    "资源来自开源仓库 awesome-english-ebooks（下载源：jsDelivr / GitHub）。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            if (groups.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = group == null,
                        onClick = { group = null },
                        label = { Text("全部", fontSize = 12.sp) }
                    )
                    groups.forEach { g ->
                        FilterChip(
                            selected = group == g,
                            onClick = { group = g },
                            label = { Text(MagazineRepository.groupLabel(g), fontSize = 12.sp) }
                        )
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    loading && list.isEmpty() -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center)
                    )

                    list.isEmpty() -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.Newspaper,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            error ?: "还没有目录数据",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { load() }) { Text("重新获取") }
                    }

                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.path }) { mag ->
                            MagRow(
                                mag = mag,
                                // 同名书可能被 uniqueTarget 改名成 xxx_1，前缀相同也算「已在书架」
                                inShelf = shelfTitles.any {
                                    it == mag.title || it.startsWith(mag.title + "_")
                                },
                                downloading = downloading == mag.title,
                                progress = progress,
                                onDownload = { download(mag) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MagRow(
    mag: Magazine,
    inShelf: Boolean,
    downloading: Boolean,
    progress: Float,
    onDownload: () -> Unit
) {
    Card {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    mag.date.ifBlank { mag.title },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    MagazineRepository.groupLabel(mag.group) +
                        " · " + DictCatalog.formatSize(mag.sizeBytes),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (downloading) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            when {
                downloading -> Text(
                    "${(progress * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                inShelf -> Text(
                    "已在书架",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "下载并加入书架")
                }
            }
        }
    }
}
