package com.lreader.ui.vocab

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lreader.analysis.WordAnalysis
import com.lreader.data.SettingsStore
import com.lreader.data.VocabRepository
import com.lreader.dict.DictDatabase
import com.lreader.export.VocabExporter
import com.lreader.export.VocabImporter
import com.lreader.model.VocabWord
import com.lreader.speech.SpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生词本列表页。
 *
 * 列表只呈现「单词 / 音标 / 词性 / 含义」四项，保持简洁；
 * 整行点击即朗读，右上角可把生词本导出为 CSV / JSON。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(
    onStartReview: () -> Unit,
    /** 内嵌到「背单词」Tab 时隐藏自身顶栏，由外层提供二级切换 */
    embedded: Boolean = false
) {
    val context = LocalContext.current
    val repo = remember { VocabRepository(context) }
    val settings = remember { SettingsStore(context) }
    // 进程级单例，见 SpeechManager.get
    val speech = SpeechManager.get(context)
    val dict = remember { DictDatabase(context) }

    var words by remember { mutableStateOf<List<VocabWord>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var dueCount by remember { mutableStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun refresh() {
        words = repo.all()
        dueCount = repo.dueToday().size
    }

    LaunchedEffect(Unit) {
        speech.accent = if (settings.accent == "UK") SpeechManager.Accent.UK else SpeechManager.Accent.US
        speech.rate = settings.speechRate
        speech.init()
        refresh()

        // 修补历史数据：早期版本没解析音标就写库了，这里顺手补上（不动表结构）。
        // 仅当词典已经释放过才执行，避免在生词本页触发 115MB 首次解压。
        withContext(Dispatchers.IO) {
            if (!dict.isExtracted()) return@withContext
            val all = repo.all()
            val needPhonetic = all.filter { it.phonetic.isBlank() }
            // 副词等变形词早期存的是「abrupt的变形」或「adv. xxx的变形 该词为…参考…：」，
            // 要么没中文、要么中文被说明文字挡在后面，一并重写成「词性 + 源词中文释义」
            val needMeaning = all.filter {
                !WordAnalysis.hasChinese(it.meaning) || WordAnalysis.hasFormNote(it.meaning)
            }
            if (needPhonetic.isEmpty() && needMeaning.isEmpty()) return@withContext
            if (!dict.ensureReady()) return@withContext
            needPhonetic.forEach { w ->
                val p = dict.lookup(w.word)?.phonetic
                if (!p.isNullOrBlank()) repo.updatePhonetic(w.id, p)
            }
            needMeaning.forEach { w ->
                val text = dict.lookup(w.word)?.html?.let { WordAnalysis.meaningText(it) }.orEmpty()
                if (WordAnalysis.hasChinese(text)) repo.updateMeaning(w.id, text)
            }
        }
        refresh()
    }
    DisposableEffect(Unit) {
        onDispose {
            // 只解除回调，不 shutdown 单例（否则回到本页时又得重建引擎）
            speech.onOnlineError = null
        }
    }

    /** 导出到应用外部目录（免权限，可用文件管理器 / USB 取走） */
    fun doExport(format: VocabExporter.Format) {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    VocabExporter.writeTo(File(dir, "exports"), repo.all(), format).absolutePath
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            snackbar.showSnackbar(if (path != null) "已导出：$path" else "导出失败")
        }
    }

    val filtered = remember(words, query) {
        if (query.isBlank()) words
        else words.filter {
            it.word.contains(query, ignoreCase = true) ||
                it.meaning.contains(query, ignoreCase = true)
        }
    }

    /** 从 CSV 文件导入生词（已存在的自动跳过） */
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: return@withContext "无法读取文件"
                    val res = VocabImporter.fromCsv(text)
                    if (res.error != null) return@withContext "导入失败：${res.error}"
                    val r = repo.importAll(res.words)
                    "导入完成：新增 ${r.first} 个，跳过 ${r.second} 个（已存在）"
                } catch (e: Exception) {
                    "导入失败：${e.message}"
                }
            }
            refresh()
            snackbar.showSnackbar(msg)
        }
    }

    val menuButton: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("导出 CSV", fontSize = 14.sp) },
                    enabled = words.isNotEmpty(),
                    onClick = {
                        showMenu = false
                        doExport(VocabExporter.Format.CSV)
                    }
                )
                DropdownMenuItem(
                    text = { Text("导出 JSON", fontSize = 14.sp) },
                    enabled = words.isNotEmpty(),
                    onClick = {
                        showMenu = false
                        doExport(VocabExporter.Format.JSON)
                    }
                )
                Divider()
                DropdownMenuItem(
                    text = { Text("导入 CSV", fontSize = 14.sp) },
                    onClick = {
                        showMenu = false
                        importLauncher.launch(arrayOf("text/*", "text/csv", "*/*"))
                    }
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = { Text("背单词", fontWeight = FontWeight.Bold) },
                    actions = { menuButton() }
                )
            }
        },
        floatingActionButton = {
            if (dueCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = onStartReview,
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    text = { Text("开始复习 ($dueCount)") }
                )
            } else if (words.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onStartReview,
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    text = { Text("复习全部") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (embedded) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "生词本 ${words.size} 词 · 待复习 $dueCount",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    menuButton()
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索单词或释义", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (words.isEmpty()) "生词本还是空的" else "没有匹配的单词",
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "阅读时点击单词，在弹窗中点「+」即可收藏",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { w ->
                        VocabRow(
                            w = w,
                            onSpeak = { speech.speak(w.word) },
                            onDelete = {
                                repo.remove(w.id)
                                refresh()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条生词：单词 + 词性 + 音标 + 含义。整行可点击朗读。
 */
@Composable
private fun VocabRow(w: VocabWord, onSpeak: () -> Unit, onDelete: () -> Unit) {
    val pos = remember(w.meaning) { WordAnalysis.partOfSpeech(w.meaning) }
    val meaning = remember(w.word, w.meaning) { WordAnalysis.cleanMeaning(w.word, w.meaning) }

    Card(Modifier.fillMaxWidth().clickable { onSpeak() }) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.word, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    if (pos.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        ) {
                            Text(
                                WordAnalysis.posLabel(pos),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                if (w.phonetic.isNotBlank()) {
                    Text(
                        w.phonetic,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (meaning.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        meaning,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}
