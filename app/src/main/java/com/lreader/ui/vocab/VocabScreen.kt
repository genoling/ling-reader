package com.lreader.ui.vocab

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.lreader.model.DictEntry
import com.lreader.model.VocabWord
import com.lreader.speech.SpeechManager
import com.lreader.translate.TranslationEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 生词本的分类方式（列表排序 / 分组） */
private enum class VocabGroup(val label: String) {
    NONE("默认"),
    SOURCE("按来源书"),
    LEVEL("按等级")
}

/**
 * 生词本列表页。
 *
 * 列表只呈现「单词 / 音标 / 词性 / 含义」四项，保持简洁；
 * 整行点击即朗读，右上角可把生词本导出为 CSV / JSON。
 * 支持按「来源书 / 分级词库（雅思托福等）」分类查看。
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
    /** 分类方式：默认按加入顺序，也可按来源书 / 分级词库分组 */
    var groupBy by remember { mutableStateOf(VocabGroup.NONE) }
    val listState = rememberLazyListState()
    // 切换分类后回到列表顶部，否则会停在旧的滚动位置（看不到第一个分组标题）
    LaunchedEffect(groupBy) { listState.scrollToItem(0) }

    // 单词详情弹层（点击某一行打开；打开时自动朗读一次）
    var detailWord by remember { mutableStateOf<VocabWord?>(null) }
    var detailEntry by remember { mutableStateOf<DictEntry?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    /** 原句译文（详情弹层里直接显示，省掉一次手动翻译） */
    var detailTranslation by remember { mutableStateOf<String?>(null) }
    var detailTranslating by remember { mutableStateOf(false) }

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

    /**
     * 打开单词详情：**先出声**（首次进入即朗读一次），再异步取词典释义。
     * 词典 HTML 交给 [WordDetailSheet] 里的 WebView 渲染，长词条也能滚到底。
     */
    fun openDetail(w: VocabWord) {
        detailWord = w
        detailEntry = null
        detailLoading = true
        detailTranslation = null
        detailTranslating = false
        speech.accent = if (settings.accent == "UK") SpeechManager.Accent.UK else SpeechManager.Accent.US
        speech.rate = settings.speechRate
        speech.speak(w.word)
        scope.launch {
            detailEntry = withContext(Dispatchers.IO) {
                if (dict.ensureReady()) dict.lookup(w.word) else null
            }
            detailLoading = false
            // 顺手把原句译文取回来（失败就留空，弹层会提示）
            if (w.sentence.isNotBlank()) {
                detailTranslating = true
                val r = runCatching {
                    TranslationEngines.translate(w.sentence, "en", settings.targetLang)
                }.getOrNull()
                detailTranslation = r?.takeIf { it.success }?.translatedText
                detailTranslating = false
            }
        }
    }

    val filtered = remember(words, query) {
        if (query.isBlank()) words
        else words.filter {
            it.word.contains(query, ignoreCase = true) ||
                it.meaning.contains(query, ignoreCase = true)
        }
    }

    // 分类后的 (组标题, 该组单词)；默认只有一组、标题为空（不显示分组头）
    val grouped = remember(filtered, groupBy) {
        when (groupBy) {
            VocabGroup.NONE -> listOf("" to filtered)
            VocabGroup.SOURCE -> filtered
                .groupBy { it.sourceBook.trim().ifBlank { "未知来源" } }
                .entries.sortedBy { it.key }.map { it.key to it.value }
            VocabGroup.LEVEL -> filtered
                .groupBy { it.level.trim().ifBlank { "未分级" } }
                .entries.sortedBy { it.key }.map { it.key to it.value }
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

            // 分类：默认 / 按来源书 / 按等级（雅思托福等分级词库）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VocabGroup.values().forEach { g ->
                    FilterChip(
                        selected = groupBy == g,
                        onClick = { groupBy = g },
                        label = { Text(g.label, fontSize = 12.sp) }
                    )
                }
            }

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
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    grouped.forEach { (title, list) ->
                        if (title.isNotEmpty()) {
                            item(key = "header-$title") {
                                Text(
                                    "$title (${list.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        items(list, key = { it.id }) { w ->
                            VocabRow(
                                w = w,
                                onOpen = { openDetail(w) },
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

    // 单词详情弹层
    detailWord?.let { w ->
        WordDetailSheet(
            word = w.word,
            phonetic = w.phonetic,
            level = w.level,
            sentence = w.sentence,
            translation = detailTranslation,
            translating = detailTranslating,
            entry = detailEntry,
            loading = detailLoading,
            onSpeak = { speech.speak(w.word) },
            onDismiss = { detailWord = null }
        )
    }
}

/**
 * 单条生词：单词 + 词性 + 音标 + 含义。整行点击进入详情（进入时自动朗读一次）。
 */
@Composable
private fun VocabRow(w: VocabWord, onOpen: () -> Unit, onDelete: () -> Unit) {
    val pos = remember(w.meaning) { WordAnalysis.partOfSpeech(w.meaning) }
    val meaning = remember(w.word, w.meaning) { WordAnalysis.cleanMeaning(w.word, w.meaning) }

    Card(Modifier.fillMaxWidth().clickable { onOpen() }) {
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
