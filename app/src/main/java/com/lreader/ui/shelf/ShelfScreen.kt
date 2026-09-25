package com.lreader.ui.shelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lreader.data.BookRepository
import com.lreader.model.Book
import com.lreader.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 书架：**封面网格**（3 列）+ 搜索 + 长按拖动排序。
 *
 * 交互约定：
 *  - 轻点封面 → 打开书；
 *  - **长按后拖动 → 调整顺序**（松手即持久化到 `bookshelf.json` 的数组顺序）；
 *  - 长按后原地抬手（没有拖动）→ 弹出「打开 / 删除」菜单（原来长按直接删，容易误触）。
 *
 * 封面来自 EPUB 内部（`BookParser.extractCover`），首帧按需为老书生成并回写 `coverPath`。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    onOpenBook: (String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { BookRepository(context) }

    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 长按但没有拖动 → 操作菜单；确认删除 → pendingDelete
    var menuBook by remember { mutableStateOf<Book?>(null) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    // 拖动排序状态
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    val gridState = rememberLazyGridState()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun refresh() {
        books = repo.load()
    }

    LaunchedEffect(Unit) {
        refresh()
        // 老书（v1.2.0 之前导入的）没有封面：按需提取一次，完成后只更新内存，避免整表回写
        val pending = books.filter { it.format == BookFormat.EPUB && it.coverPath.isNullOrBlank() }
        if (pending.isNotEmpty()) {
            val updated = withContext(Dispatchers.IO) { pending.map { repo.ensureCover(it) } }
            val map = updated.associateBy { it.id }
            books = books.map { map[it.id] ?: it }
        }
    }

    fun cancelDrag() {
        dragId = null
        dragStartIndex = -1
        dragX = 0f
        dragY = 0f
    }

    /** 松手：位移很小视为「长按点击」→ 弹菜单；否则按行列位移换位并保存顺序。 */
    fun endDrag() {
        val startIndex = dragStartIndex
        val id = dragId
        val dx = dragX
        val dy = dragY
        cancelDrag()
        if (id == null || startIndex !in books.indices) return

        if (abs(dx) + abs(dy) < 16f) {
            menuBook = books.firstOrNull { it.id == id }
            return
        }

        val items = gridState.layoutInfo.visibleItemsInfo
        val me = items.firstOrNull { it.index == startIndex } ?: return
        val cellW = me.size.width.toFloat()
        val cellH = me.size.height.toFloat()
        if (cellW <= 0f || cellH <= 0f) return

        // 每行几个：同一 y 偏移的项数（通常就是列数 3）
        val cols = items.groupBy { it.offset.y }.maxByOrNull { it.value.size }?.value?.size ?: 3
        val dRow = (dy / cellH).roundToInt()
        val dCol = (dx / cellW).roundToInt()
        val minIdx = items.minOfOrNull { it.index } ?: 0
        val maxIdx = items.maxOfOrNull { it.index } ?: books.lastIndex

        val target = (startIndex + dRow * cols + dCol)
            .coerceIn(minIdx.coerceAtLeast(0), maxIdx.coerceAtMost(books.lastIndex))
        if (target == startIndex) return

        val list = books.toMutableList()
        val moved = list.removeAt(startIndex)
        list.add(target, moved)
        books = list
        repo.save(list)
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            val ok = withContext(Dispatchers.IO) { importUri(context, repo, uri) }
            loading = false
            refresh()
            // 导入后立刻生成封面（EPUB）
            val newest = books.lastOrNull()
            if (ok && newest != null) {
                val withCover = withContext(Dispatchers.IO) { repo.ensureCover(newest) }
                books = books.map { if (it.id == withCover.id) withCover else it }
            }
            snackbar.showSnackbar(if (ok) "导入成功" else "导入失败，支持 txt/epub/fb2/html")
        }
    }

    val shown = if (query.isBlank()) books
    else books.filter { it.title.contains(query.trim(), ignoreCase = true) }

    Scaffold(
        topBar = {
            if (searching) {
                TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索书名", fontSize = 14.sp) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("书架", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(BookFormat.mimeTypes) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("导入书籍") }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                books.isEmpty() -> EmptyShelf()
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配的书", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyVerticalGrid(
                    state = gridState,
                    // 手机竖屏约 3 列；平板 / 横屏自动排更多列（固定 3 列在平板上封面会过大）
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(shown, key = { _, b -> b.id }) { index, book ->
                        BookGridItem(
                            book = book,
                            dragging = dragId == book.id,
                            offsetX = if (dragId == book.id) dragX else 0f,
                            offsetY = if (dragId == book.id) dragY else 0f,
                            onOpen = { onOpenBook(book.id) },
                            onLongPress = {
                                dragId = book.id
                                dragStartIndex = index
                                dragX = 0f
                                dragY = 0f
                            },
                            onDrag = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onRelease = { endDrag() }
                        )
                    }
                }
            }
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    // 长按未拖动 → 操作菜单
    menuBook?.let { target ->
        AlertDialog(
            onDismissRequest = { menuBook = null },
            title = {
                Text(
                    target.title,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Text(
                    "长按封面后拖动可以调整顺序；点「删除」把这本书移出书架。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    menuBook = null
                    onOpenBook(target.id)
                }) { Text("打开") }
            },
            dismissButton = {
                TextButton(onClick = {
                    menuBook = null
                    pendingDelete = target
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    // 删除确认
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这本书？") },
            text = {
                Text(
                    "《${target.title}》将从书架移除，已收藏的生词不受影响。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(target)
                    val list = repo.load()
                    list.removeAll { it.id == target.id }
                    repo.save(list)
                    pendingDelete = null
                    refresh()
                    scope.launch { snackbar.showSnackbar("已删除《${target.title}》") }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 网格里的一本书：封面 + 书名。
 *
 * 手势**手写**（不用 `combinedClickable`）：需要区分「轻点打开」「长按原地抬手弹菜单」
 * 「长按后拖动排序」三件事，用 `combinedClickable` + 拖动手势会互相抢事件。
 */
@Composable
private fun BookGridItem(
    book: Book,
    dragging: Boolean,
    offsetX: Float,
    offsetY: Float,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onRelease: () -> Unit
) {
    Column(
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationX = offsetX
                translationY = offsetY
                val s = if (dragging) 1.06f else 1f
                scaleX = s
                scaleY = s
                shadowElevation = if (dragging) 14f else 0f
            }
            .pointerInput(book.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var traveled = 0f
                    var longPressed = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.positionChange()

                        if (!longPressed &&
                            change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                        ) {
                            longPressed = true
                            onLongPress()
                        }

                        if (change.pressed) {
                            traveled += abs(delta.x) + abs(delta.y)
                            if (longPressed) {
                                onDrag(delta.x, delta.y)
                                change.consume()
                            }
                        }
                        if (!change.pressed) break
                    }

                    when {
                        longPressed -> onRelease()
                        // 轻点：位移小于 touchSlop 才算点击（否则是滚动列表）
                        traveled < viewConfiguration.touchSlop -> onOpen()
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BookCover(
            path = book.coverPath,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptyShelf() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(12.dp))
        Text("书架空空如也", fontWeight = FontWeight.Medium)
        Text(
            "导入 txt / epub / fb2 / html 开始阅读",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun importUri(context: android.content.Context, repo: BookRepository, uri: Uri): Boolean {
    return try {
        val name = queryDisplayName(context, uri) ?: "imported.txt"
        val tmp = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        } ?: return false

        if (BookFormat.fromFileName(name) == BookFormat.UNKNOWN) {
            tmp.delete()
            return false
        }

        val book = repo.importFile(tmp) ?: return false
        val list = repo.load().toMutableList()
        if (list.none { it.id == book.id }) list.add(book)
        repo.save(list)
        tmp.delete()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        uri.lastPathSegment
    }
}
