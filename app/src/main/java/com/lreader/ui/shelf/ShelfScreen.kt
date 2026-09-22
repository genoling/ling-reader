package com.lreader.ui.shelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lreader.data.BookRepository
import com.lreader.model.Book
import com.lreader.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    onOpenBook: (String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { BookRepository(context) }

    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    // 长按书籍后才出现的删除确认
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun refresh() {
        books = repo.load()
    }

    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            val ok = withContext(Dispatchers.IO) { importUri(context, repo, uri) }
            loading = false
            refresh()
            snackbar.showSnackbar(if (ok) "导入成功" else "导入失败，支持 txt/epub/fb2/html")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("书架", fontWeight = FontWeight.Bold) })
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
            if (books.isEmpty()) {
                EmptyShelf()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(books, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            onClick = { onOpenBook(book.id) },
                            onLongClick = { pendingDelete = book }
                        )
                    }
                }
            }
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    // 长按书籍 → 确认删除（平时界面上不显示删除按钮）
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                Text(
                    book.format.name,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
