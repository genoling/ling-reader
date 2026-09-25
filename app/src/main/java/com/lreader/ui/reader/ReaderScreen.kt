package com.lreader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lreader.analysis.WordAnalysis
import com.lreader.book.BookParser
import com.lreader.data.BookRepository
import com.lreader.data.SettingsStore
import com.lreader.data.VocabRepository
import com.lreader.dict.DictDatabase
import com.lreader.dict.LevelDictionary
import com.lreader.model.Book
import com.lreader.model.Chapter
import com.lreader.model.ChapterImage
import com.lreader.model.DictEntry
import com.lreader.model.TocEntry
import com.lreader.model.TranslationResult
import com.lreader.speech.SpeechManager
import com.lreader.sync.SyncManager
import com.lreader.translate.TranslationEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读页：分级词汇高亮 + 点词查词（自动发音）+ 长按选句翻译。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { BookRepository(context) }
    val dict = remember { DictDatabase(context) }
    val levels = remember { LevelDictionary(context) }
    val vocab = remember { VocabRepository(context) }
    val settings = remember { SettingsStore(context) }
    // 进程级单例：不要每个页面各自 init/shutdown，否则切页面就把引擎拆了重建
    val speech = SpeechManager.get(context)

    var book by remember { mutableStateOf<Book?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var chapterIndex by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var dictReady by remember { mutableStateOf(false) }
    /** 主词典是否已下载（未下载时点词只提示去设置） */
    var dictInstalled by remember { mutableStateOf(true) }
    var enabledLevels by remember { mutableStateOf(settings.enabledLevels) }
    var vocabWords by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fontSize by remember { mutableStateOf(settings.fontSize) }
    var lineHeight by remember { mutableStateOf(settings.lineHeight) }
    var highlightVocab by remember { mutableStateOf(settings.highlightVocab) }
    var skipBasic by remember { mutableStateOf(settings.skipBasicWords) }
    var showLevelDialog by remember { mutableStateOf(false) }

    // ---- 阅读主题 / 翻页 / 下拉参数面板 / 详情 ----
    var themeIndex by remember { mutableStateOf(settings.readingTheme) }
    val theme = ReadingTheme.of(themeIndex)
    var pages by remember { mutableStateOf<List<ReaderPage>>(emptyList()) }
    var pageIndex by remember { mutableStateOf(0) }
    var showParams by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    /** 目录（EPUB 自带 ncx / nav，含层级）；为空时面板回退成章节列表 */
    var tocList by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    /** 跨设备同步来的章内百分比（<0 = 不用）；页码无效时按它定位 */
    var restorePercent by remember { mutableStateOf(-1f) }

    // 查词
    var selectedWord by remember { mutableStateOf<String?>(null) }
    var selectedSentence by remember { mutableStateOf("") }
    var dictEntry by remember { mutableStateOf<DictEntry?>(null) }
    var lookupDone by remember { mutableStateOf(false) }

    // 翻译
    var translating by remember { mutableStateOf(false) }
    var translation by remember { mutableStateOf<TranslationResult?>(null) }

    // 选中的句子（长按）
    var pickedSentence by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(bookId) {
        loading = true
        val b = withContext(Dispatchers.IO) { repo.load().firstOrNull { it.id == bookId } }
        book = b
        if (b != null) {
            chapters = withContext(Dispatchers.IO) { BookParser.loadChapters(b) }
            chapterIndex = b.lastChapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            // 章内页码同样要恢复（复用 lastScrollY 存页码）：只恢复章号会「翻过的页白翻了」
            pageIndex = b.lastScrollY.coerceAtLeast(0)
            // 没有本机页码、却有百分比 → 说明进度是从别的设备同步来的，交给阅读页按比例定位
            restorePercent = if (b.lastScrollY <= 0 && b.lastPercent > 0f) b.lastPercent else -1f
            // 目录（含层级），与章节用同一份解析结果，避免点击后跳错章
            tocList = BookParser.loadToc(b, chapters)
        }

        // 词典 + 分级库 + TTS
        // ⚠️ 必须放在 loading = false 之前：WordText 一旦先渲染出「空索引」的正文，
        // 就不会因为 LevelDictionary 稍后就绪而重算，导致首次进入看不到分级高亮（历史 bug）。
        // 主词典已改为「按需下载」：未安装时 ensureReady 立即返回 false、不阻塞进入正文，
        // 点词改由弹层提示「去设置里下载词典」。
        dictInstalled = withContext(Dispatchers.IO) { dict.ensureReady() }
        withContext(Dispatchers.IO) { levels.ensureReady() }
        dictReady = true

        speech.accent = if (settings.accent == "UK") SpeechManager.Accent.UK else SpeechManager.Accent.US
        speech.rate = settings.speechRate
        speech.init()

        // 等启动时的云同步跑完（最多几秒）：否则刚进 App 就点进书时，生词高亮用的是同步前的旧数据
        withContext(Dispatchers.IO) { SyncManager.awaitInitialSync() }
        // 统一小写，保证「生词红色高亮」与正文大小写无关
        vocabWords = withContext(Dispatchers.IO) { vocab.all().map { it.word.lowercase() }.toSet() }

        loading = false
    }


    // 保存阅读进度
    fun saveProgress() {
        val b = book ?: return
        try {
            val list = repo.load()
            val i = list.indexOfFirst { it.id == b.id }
            if (i >= 0) {
                list[i] = list[i].copy(
                    lastChapterIndex = chapterIndex,
                    // 本机用它精确回到同一页
                    lastScrollY = pageIndex,
                    // 跨设备同步的是「章节 + 百分比」：页码换个字号/屏幕就变了，传出去没意义
                    lastPercent = if (pages.isEmpty()) 0f else (pageIndex + 1f) / pages.size,
                    progressUpdatedAt = System.currentTimeMillis()
                )
                repo.save(list)
            }
        } catch (_: Exception) {
        }
    }

    // 翻页 / 切章后自动保存（防抖 800ms），退出页面时再兜一次 —— 否则「退出再进」会丢进度
    LaunchedEffect(chapterIndex, pageIndex) {
        if (chapters.isEmpty()) return@LaunchedEffect
        delay(800)
        saveProgress()
    }
    DisposableEffect(Unit) {
        onDispose { saveProgress() }
    }

    Scaffold(
        containerColor = theme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background,
                    titleContentColor = theme.text,
                    navigationIconContentColor = theme.text,
                    actionIconContentColor = theme.secondaryText
                ),
                title = {
                    Column {
                        Text(book?.title ?: "加载中…", maxLines = 1, fontSize = 15.sp)
                        if (chapters.isNotEmpty()) {
                            Text(chapters[chapterIndex].title, maxLines = 1, fontSize = 11.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { saveProgress(); onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Text(
                        "生词 ${vocabWords.size}",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 2.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    // 目录（EPUB 的 ncx / nav，txt 则是章节列表）
                    IconButton(onClick = { showToc = true }) {
                        Icon(Icons.Filled.List, contentDescription = "目录")
                    }
                    // 阅读中直接开关分级高亮
                    IconButton(onClick = { showLevelDialog = true }) {
                        Icon(Icons.Filled.Palette, contentDescription = "高亮级别")
                    }
                }
            )
        },
        bottomBar = {
            if (chapters.isNotEmpty()) {
                Surface(color = theme.background) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "第 ${chapterIndex + 1}/${chapters.size} 章",
                            fontSize = 11.sp,
                            color = theme.secondaryText
                        )
                        Spacer(Modifier.weight(1f))
                        // 页码（对齐参考图右下角样式）
                        Text(
                            "${pageIndex + 1}/${pages.size.coerceAtLeast(1)}",
                            fontSize = 11.sp,
                            color = theme.secondaryText
                        )
                        Spacer(Modifier.width(18.dp))
                        Text(
                            "详情",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showDetail = true }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(theme.background)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                chapters.isEmpty() -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.MenuBook, contentDescription = null)
                    Spacer(Modifier.height(8.dp))
                    Text("无法解析该书（支持 txt / epub / fb2 / html）")
                }

                else -> PagedReader(
                    // key 变化（换章 / 改字号行距）时重新分页
                    chapterKey = "$bookId#$chapterIndex#$fontSize#$lineHeight",
                    text = chapters[chapterIndex].content,
                    images = chapters[chapterIndex].images,
                    // 定位到上次读到的页码（首次进入为 0）；页码无效时用 restorePercent
                    initialPage = pageIndex,
                    initialPercent = restorePercent,
                    theme = theme,
                    enabledLevels = enabledLevels,
                    levels = levels,
                    levelsReady = dictReady,
                    vocabWords = vocabWords,
                    highlightVocab = highlightVocab,
                    skipBasic = skipBasic,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    onPagesReady = { pages = it },
                    onPageChanged = { pageIndex = it },
                    onPullDown = { showParams = true },
                    // 书内超链接（EPUB 目录页 / 栏目页）→ 跳到目标章节
                    onInternalLink = { target ->
                        val idx = chapters.indexOfFirst { it.sourcePath == target }
                        if (idx >= 0 && idx != chapterIndex) {
                            chapterIndex = idx
                            pageIndex = 0
                            saveProgress()
                        }
                    },
                    hasPrevChapter = chapterIndex > 0,
                    hasNextChapter = chapterIndex < chapters.size - 1,
                    onPrevChapter = {
                        if (chapterIndex > 0) {
                            chapterIndex--
                            pageIndex = 0
                            saveProgress()
                        }
                    },
                    onNextChapter = {
                        if (chapterIndex < chapters.size - 1) {
                            chapterIndex++
                            pageIndex = 0
                            saveProgress()
                        }
                    },
                    onWordClick = { word, sentence ->
                        // 自动发音
                        if (settings.autoSpeak) speech.speak(word)
                        selectedWord = word
                        selectedSentence = sentence
                        dictEntry = null
                        lookupDone = false
                        translation = null
                        scope.launch {
                            val e = withContext(Dispatchers.IO) { dict.lookup(word, levels) }
                            dictEntry = e
                            lookupDone = true
                        }
                    },
                    onLongPressSentence = { sentence ->
                        // 长按正文 → 直接翻译该处所在整句（默认引擎：网易有道（免费））
                        pickedSentence = sentence
                        translation = null
                        translating = true
                        scope.launch {
                            translation = withContext(Dispatchers.IO) {
                                TranslationEngines.translate(sentence, to = settings.targetLang)
                            }
                            translating = false
                        }
                    }
                )
            }

            if (!loading && chapters.isNotEmpty() && !dictInstalled) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        "本地词典未下载：点词无释义。请到 设置 → 本地词典 下载",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    // ---------- 目录 ----------
    val tocItems = remember(tocList, chapters) {
        // 优先用 EPUB 自带目录（含父子层级）；txt 没有目录信息时回退成章节列表
        if (tocList.isNotEmpty()) tocList
        else chapters.map { TocEntry(it.title, it.index, 0) }
    }
    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text(
                "目录",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(tocItems) { entry ->
                    val current = entry.chapterIndex == chapterIndex
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                chapterIndex = entry.chapterIndex
                                pageIndex = 0
                                saveProgress()
                                showToc = false
                            }
                            .padding(
                                start = (16 + entry.level.coerceIn(0, 2) * 18).dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 12.dp
                            )
                    ) {
                        Text(
                            entry.title,
                            fontSize = if (entry.level == 0) 14.sp else 13.sp,
                            fontWeight = if (entry.level == 0) FontWeight.Medium else FontWeight.Normal,
                            color = if (current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }

    // ---------- 查词弹层 ----------
    selectedWord?.let { w ->
        DictBottomSheet(
            word = w,
            sentence = selectedSentence,
            entry = dictEntry,
            done = lookupDone,
            dictInstalled = dictInstalled,
            sourceBook = book?.title ?: "",
            inVocab = vocabWords.contains((dictEntry?.word ?: w).lowercase()),
            onSpeak = { speech.speak(dictEntry?.word ?: w) },
            onRemoveVocab = {
                val target = dictEntry?.word ?: w
                vocabWords = vocabWords - target.lowercase()
                scope.launch {
                    withContext(Dispatchers.IO) { vocab.removeByWord(target) }
                }
            },
            onAddVocab = {
                val e = dictEntry
                scope.launch {
                    val added = withContext(Dispatchers.IO) {
                        vocab.add(
                            com.lreader.model.VocabWord(
                                word = e?.word ?: w,
                                // 优先取「词性 + 中文释义」那一行；直接去标签会把
                                // 「单词 音标 adv. xxx的变形 该词为…参考…：」整段存进来，
                                // 列表只有两行，真正的中文意思会被挤到看不见
                                meaning = e?.html?.let { h ->
                                    WordAnalysis.meaningText(h).ifBlank { stripHtml(h).take(500) }
                                } ?: "",
                                phonetic = e?.phonetic ?: "",
                                sentence = selectedSentence,
                                sourceBook = book?.title ?: "",
                                level = e?.level ?: ""
                            )
                        )
                    }
                    if (added) {
                        vocabWords = vocabWords + (e?.word ?: w).lowercase()
                    }
                }
            },
            onTranslateSentence = {
                if (selectedSentence.isNotBlank()) {
                    translating = true
                    scope.launch {
                        translation = withContext(Dispatchers.IO) {
                            TranslationEngines.translate(
                                selectedSentence,
                                to = settings.targetLang
                            )
                        }
                        translating = false
                    }
                }
            },
            translating = translating,
            translation = translation,
            onDismiss = {
                selectedWord = null
                translation = null
            }
        )
    }

    // ---------- 选句翻译弹层 ----------
    pickedSentence?.let { s ->
        SentenceSheet(
            sentence = s,
            translating = translating,
            translation = translation,
            onTranslate = {
                translating = true
                scope.launch {
                    translation = withContext(Dispatchers.IO) {
                        TranslationEngines.translate(s, to = settings.targetLang)
                    }
                    translating = false
                }
            },
            onDismiss = {
                pickedSentence = null
                translation = null
            }
        )
    }

    // ---------- 下拉阅读参数面板 ----------
    if (showParams) {
        ModalBottomSheet(onDismissRequest = { showParams = false }) {
            ReadingParamsPanel(
                fontSize = fontSize,
                lineHeight = lineHeight,
                themeIndex = themeIndex,
                onFontSize = { fontSize = it; settings.fontSize = it },
                onLineHeight = { lineHeight = it; settings.lineHeight = it },
                onTheme = { themeIndex = it; settings.readingTheme = it }
            )
        }
    }

    // ---------- 详情 ----------
    if (showDetail) {
        ModalBottomSheet(onDismissRequest = { showDetail = false }) {
            ReaderDetailPanel(
                book = book,
                chapterTitle = chapters.getOrNull(chapterIndex)?.title.orEmpty(),
                chapterIndex = chapterIndex,
                chapterCount = chapters.size,
                pageIndex = pageIndex,
                pageCount = pages.size,
                charCount = chapters.getOrNull(chapterIndex)?.content?.length ?: 0,
                vocabCount = vocabWords.size,
                onPrevChapter = {
                    if (chapterIndex > 0) { chapterIndex--; pageIndex = 0; saveProgress() }
                },
                onNextChapter = {
                    if (chapterIndex < chapters.size - 1) { chapterIndex++; pageIndex = 0; saveProgress() }
                }
            )
        }
    }

    // ---------- 分级高亮开关 ----------
    if (showLevelDialog) {
        LevelHighlightDialog(
            levels = levels,
            enabled = enabledLevels,
            highlightVocab = highlightVocab,
            skipBasic = skipBasic,
            onToggleSkipBasic = {
                skipBasic = it
                settings.skipBasicWords = it
            },
            onToggleLevel = { key ->
                val newSet = if (key in enabledLevels) enabledLevels - key else enabledLevels + key
                enabledLevels = newSet
                settings.enabledLevels = newSet
            },
            onToggleVocab = {
                highlightVocab = it
                settings.highlightVocab = it
            },
            onDismiss = { showLevelDialog = false }
        )
    }
}

/**
 * 阅读页的分级高亮开关弹窗。
 * 勾选立即写入 SettingsStore 并即时重绘正文，无需回到设置页。
 */
@Composable
private fun LevelHighlightDialog(
    levels: LevelDictionary,
    enabled: Set<String>,
    highlightVocab: Boolean,
    skipBasic: Boolean,
    onToggleLevel: (String) -> Unit,
    onToggleVocab: (Boolean) -> Unit,
    onToggleSkipBasic: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("词汇高亮", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已收藏的生词（红色）", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = highlightVocab, onCheckedChange = onToggleVocab)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("忽略基础词（高中/四级/六级）", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = skipBasic, onCheckedChange = onToggleSkipBasic)
                }
                Text(
                    "勾选雅思/托福等较高级别时，不再高亮同时属于高中/四级/六级的词" +
                        "（这些词表里混有大量基础词）；只勾高中/四级/六级时不生效。",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (levels.levelNames.isEmpty()) {
                    Text(
                        "分级词库载入中…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    levels.levelNames.forEach { (key, name) ->
                        val on = key in enabled
                        val count = levels.levelCounts[key] ?: 0
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggleLevel(key) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = { onToggleLevel(key) })
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(levels.colorOf(key)))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(
                                "$count",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/** 去 HTML 标签 */
private fun stripHtml(html: String): String =
    html.replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * 渲染正文：分级词汇着色 + 点击查词 + 长按整句翻译。
 */
@Composable
private fun WordText(
    text: String,
    enabledLevels: Set<String>,
    levels: LevelDictionary,
    levelsReady: Boolean,
    vocabWords: Set<String>,
    highlightVocab: Boolean,
    skipBasic: Boolean,
    fontSize: Float,
    lineHeight: Float,
    /** 标题类块加粗 */
    bold: Boolean = false,
    /** 栏目 / 引用类块用次要色 */
    dim: Boolean = false,
    /** 段落开头做首行缩进（样式与 [displayText] 必须一致，否则分页与渲染对不上） */
    indentFirstLine: Boolean = false,
    theme: ReadingTheme,
    onWordClick: (String, String) -> Unit,
    onLongPressSentence: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // levelsReady 必须参与 key：LevelDictionary 的索引是普通对象（非 Compose State），
    // 不入 key 时索引就绪后不会重新着色（历史 bug：首次进入无色，切章才上色）。
    val annotated = remember(
        text, enabledLevels, vocabWords, highlightVocab, skipBasic, levelsReady, indentFirstLine
    ) {
        val plain = buildAnnotatedString {
            val wordRegex = Regex("[A-Za-z][A-Za-z'\\-]*[A-Za-z]|[A-Za-z]")
            var last = 0
            for (m in wordRegex.findAll(text)) {
                if (m.range.first > last) append(text.substring(last, m.range.first))
                val word = m.value

                // 决定颜色
                var color: Color? = null
                if (highlightVocab && vocabWords.contains(word.lowercase())) {
                    // 生词本中的词：红色（夜间模式下提亮）
                    color = theme.levelColor(0xFFD32F2F.toInt())
                } else {
                    val lv = levels.matchLevel(word, enabledLevels, skipBasic)
                    if (lv != null) color = theme.levelColor(levels.colorOf(lv))
                }

                // 记录词与所在句子，供点击时使用
                pushStringAnnotation("WORD", word)
                pushStringAnnotation("SENT", sentenceOf(text, m.range.first))
                val style = if (color != null) {
                    SpanStyle(color = color, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.None)
                } else {
                    SpanStyle(color = theme.text, textDecoration = TextDecoration.None)
                }
                withStyle(style) { append(word) }
                pop()
                pop()

                last = m.range.last + 1
            }
            if (last < text.length) append(text.substring(last))
        }
        // 首行缩进：与测量侧 displayText 同源（只挂段落样式、不增删字符，查词 offset 不受影响）
        if (indentFirstLine) {
            buildAnnotatedString {
                withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = INDENT_CHARS.em))) {
                    append(plain)
                }
            }
        } else {
            plain
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 手势块只随 (annotated, layout) 重建，回调要用最新值，否则闭包里是旧页
    val curWordClick by rememberUpdatedState(onWordClick)
    val curLongPress by rememberUpdatedState(onLongPressSentence)

    Text(
        text = annotated,
        // 高度由内容决定（不要 fillMaxSize：图文混排时它会把同页的插图挤出屏幕）
        modifier = modifier
            .pointerInput(annotated, layout) {
                val lr = layout ?: return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress != null) {
                        // 长按 → 取该处所在整句，直接进入句子翻译弹层并开始翻译
                        curLongPress(sentenceOf(text, lr.getOffsetForPosition(longPress.position)))
                    } else {
                        // 只有「原地抬手」才算点词；横向翻页 / 纵向下拉的位移要排除
                        val cur = currentEvent.changes.firstOrNull { it.id == down.id }
                        val moved = cur != null &&
                            (cur.position - down.position).getDistance() > viewConfiguration.touchSlop
                        if (cur != null && !cur.pressed && !moved) {
                            val offset = lr.getOffsetForPosition(down.position)
                            annotated.getStringAnnotations("WORD", offset, offset)
                                .firstOrNull()?.let { a ->
                                    val sent = annotated.getStringAnnotations("SENT", offset, offset)
                                        .firstOrNull()?.item ?: ""
                                    curWordClick(a.item, sent)
                                }
                        }
                    }
                }
            },
        onTextLayout = { layout = it },
        style = TextStyle(
            fontSize = fontSize.sp,
            lineHeight = lineHeight.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (dim) theme.secondaryText else theme.text
        )
    )
}

/** 取 offset 所在的句子 */
private val SENT_ENDINGS = charArrayOf('.', '!', '?', '\n', '。', '！', '？')

private fun sentenceOf(text: String, offset: Int): String {
    if (text.isEmpty()) return ""
    val safeOffset = offset.coerceIn(0, text.length - 1)
    var start = 0
    var i = safeOffset - 1
    while (i >= 0) {
        if (text[i] in SENT_ENDINGS) {
            start = i + 1
            break
        }
        i--
    }
    var end = text.length
    var j = safeOffset
    while (j < text.length) {
        if (text[j] in SENT_ENDINGS) {
            end = j + 1
            break
        }
        j++
    }
    return text.substring(start, end)
        .replace('\n', ' ')
        .trim()
        .take(500)
}

// ---------------------------------------------------------------------------
// 分页阅读
// ---------------------------------------------------------------------------

/** 插图最多占页面高度的比例，其余留给同段的文字 */
private const val IMAGE_MAX_PAGE_RATIO = 0.62f

/**
 * 正文块层级：来自 HTML 的 `h1/h2/h3/h4~h6/blockquote/普通段落`。
 *
 * 拍平成纯文本时这些层级会丢失（全文一个字号）；带上标记后按 [scale] 放大/缩小，
 * 与纸质书、官方阅读器的观感一致（标题大而粗、正文常规、栏目/引用次要色）。
 */
private enum class BlockKind(val scale: Float, val bold: Boolean, val dim: Boolean = false) {
    TITLE(1.55f, true),          // h1
    SUBTITLE(1.25f, true),       // h2
    SECTION(1.10f, true),        // h3
    MINOR(1.00f, true, dim = true), // h4~h6
    QUOTE(0.96f, false, dim = true), // blockquote
    BODY(1f, false)
}

/** 一段文字里的一个排版块（插图不在这里，由调用方按段序单独插入） */
private data class TextBlock(
    val text: String,
    val kind: BlockKind,
    /** 内部链接目标（zip 内路径）；非空表示这一块整体是超链接，点击跳章 */
    val link: String? = null
)

/**
 * 把带层级标记的正文解析成块序列（标记见 `BookParser.MARK_*`）。
 *
 * 规则：标记开启一个新块并记住类型；连续两个换行结束当前块；类型回到正文。
 * 没有标记的文本（TXT 书、旧缓存）会整体成为正文块，行为与改造前一致。
 */
private fun parseBlocks(seg: String): List<TextBlock> {
    val out = ArrayList<TextBlock>()
    val buf = StringBuilder()
    var kind = BlockKind.BODY

    /** 链接解析状态（标记见 `BookParser.MARK_LINK*`） */
    var linkTarget: String? = null
    var linkText: String? = null
    var targetBuf: StringBuilder? = null
    var labelBuf: StringBuilder? = null

    fun flush() {
        val t = buf.toString().trim()
        if (t.isNotEmpty()) {
            // 只有「整块内容就是这个链接」才算超链接；正文里混排的链接退化为普通文字，
            // 否则会把整段都染成链接色（列表项 "• Leaders" 这类是列表符号 + 链接，仍算链接）
            val link = linkTarget?.takeIf { linkText != null && t.endsWith(linkText!!) }
            out.add(TextBlock(t, kind, link))
        }
        buf.setLength(0)
        kind = BlockKind.BODY
        linkTarget = null
        linkText = null
    }

    /** 结束当前块并以新层级开启下一块（赋值不能直接作为 `when` 分支体） */
    fun begin(k: BlockKind) {
        flush()
        kind = k
    }

    for (c in seg) {
        when {
            // Windows 文本（TXT 原文 / EPUB 源文件多是 CRLF）里段间空行是 "\r\n\r\n"：
            // CR 必须忽略，否则 buf 末尾永远是 '\r'，「连续两个换行」判定永不成立 → 整章挤成一块，
            // 首行缩进与段间距只在第一片生效（分页后 2~N 页缩进全丢）
            c == '\r' -> {}
            c == '\n' -> if (buf.isNotEmpty() && buf.last() == '\n') flush() else buf.append(c)
            // 链接标记：不切块，让「• 」这类前缀与链接文字待在同一块里
            c == BookParser.MARK_LINK -> targetBuf = StringBuilder()
            c == BookParser.MARK_LINK_TEXT -> {
                linkTarget = targetBuf?.toString()
                targetBuf = null
                labelBuf = StringBuilder()
            }

            c == BookParser.MARK_LINK_END -> {
                linkText = labelBuf?.toString()?.trim()
                labelBuf = null
            }

            c == BookParser.MARK_TITLE -> begin(BlockKind.TITLE)
            c == BookParser.MARK_SUBTITLE -> begin(BlockKind.SUBTITLE)
            c == BookParser.MARK_SECTION -> begin(BlockKind.SECTION)
            c == BookParser.MARK_MINOR -> begin(BlockKind.MINOR)
            c == BookParser.MARK_QUOTE -> begin(BlockKind.QUOTE)
            targetBuf != null -> targetBuf.append(c)
            else -> {
                buf.append(c)
                if (linkTarget != null) labelBuf?.append(c)
            }
        }
    }
    flush()
    return out
}

/** 块样式：测量与渲染必须用同一份（字号/行高/字重），否则分页会与实际排版对不上 */
private fun blockStyle(kind: BlockKind, fontSize: Float, lineHeight: Float, theme: ReadingTheme) =
    TextStyle(
        fontSize = (fontSize * kind.scale).sp,
        lineHeight = (lineHeight * kind.scale).sp,
        fontWeight = if (kind.bold) FontWeight.Bold else FontWeight.Normal,
        color = if (kind.dim) theme.secondaryText else theme.text
    )

/** 首行缩进的字符数（2 = 中文书籍习惯）。改动会改变每段行数 → 分页随之变化，属正常 */
private const val INDENT_CHARS = 2f

/**
 * 段间距（相对行高的倍数）：段落之间空一行，与「整段一个 Text + `\n\n`」时期观感一致。
 * 0 = 段间不额外留白（会像 v1.6.0 之前那样贴在一起）。
 */
private const val PARA_GAP_RATIO = 1f

/** 正文 / 引文首行缩进；标题与列表项（"• "、"- "、"1. "）不缩进更自然 */
private fun needsIndent(kind: BlockKind, text: String): Boolean {
    if (kind != BlockKind.BODY && kind != BlockKind.QUOTE) return false
    val t = text.trimStart()
    return !(t.startsWith("•") || t.startsWith("·") || t.startsWith("- ") || t.startsWith("* "))
}

/**
 * 带首行缩进的显示文本（**测量与渲染共用**）。
 * 只挂段落样式、不增删字符，因此查词用的字符 offset 不受影响。
 */
private fun displayText(text: String, kind: BlockKind): AnnotatedString =
    if (needsIndent(kind, text)) {
        buildAnnotatedString {
            withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = INDENT_CHARS.em))) {
                append(text)
            }
        }
    } else {
        AnnotatedString(text)
    }

/** 页内内容块：文字段或插图（**按原文顺序混排**，与杂志/书的版式一致） */
private sealed interface PageBlock {
    data class Text(
        val text: String,
        val kind: BlockKind = BlockKind.BODY,
        /** 非空表示整块是内部链接（点击跳章） */
        val link: String? = null,
        /**
         * 这一片是所在**段落的开头**：渲染时在它上方留段间距、正文还要做首行缩进。
         * 段落被切到下一页的后续片为 false（不重复缩进，也不在页首多留空白）。
         */
        val startsParagraph: Boolean = true
    ) : PageBlock
    /** [heightPx] 是本页该图的实际显示高度（按正文宽度等比算出，分页时已计入） */
    data class Image(val image: ChapterImage, val heightPx: Float) : PageBlock
}

/** 阅读页：一页由若干块组成，文字与插图交错排列 */
private data class ReaderPage(val blocks: List<PageBlock>)

/**
 * 图文混排分页：按像素高度把「文字块 + 插图」装进一页，装不下就换页。
 *
 * 文字块带层级（见 [BlockKind]），每块按**自己的行高**累加占位，因此标题行更大。
 * 插图不独占一页，而是接在它原本所在的文字后面（如「导语 → 图片 → 正文」）。
 *
 * @param blocksPerSeg 先按 [BookParser.IMG_MARK] 切段、再各自解析成的块序列；段 i 之后是第 i 张图
 * @param layouts      各文字块的排版结果，key = [layoutKey]
 * @param imageHeights 各图在正文宽度下的高度（px）
 * @param gapPx        段间距（px）：段与段之间空一行，与渲染侧的 Spacer 同源，缺一不可
 */
private fun paginateFlow(
    blocksPerSeg: List<List<TextBlock>>,
    images: List<ChapterImage>,
    layouts: Map<Long, TextLayoutResult>,
    imageHeights: Map<Int, Float>,
    pageHeight: Float,
    lineHeight: Float,
    gapPx: Float
): List<ReaderPage> {
    val pages = ArrayList<ReaderPage>()
    var blocks = ArrayList<PageBlock>()
    var used = 0f

    fun flush() {
        if (blocks.isNotEmpty()) {
            pages.add(ReaderPage(blocks))
            blocks = ArrayList()
            used = 0f
        }
    }

    for (si in blocksPerSeg.indices) {
        blocksPerSeg[si].forEachIndexed { bi, tb ->
            val lr = layouts[layoutKey(si, bi)]
            if (lr == null) return@forEachIndexed
            if (tb.text.isEmpty() || lr.lineCount <= 0) return@forEachIndexed
            // 该块的行高（标题字号大 → 行高也大）：取排版结果第一行的实际高度
            val blockLine = (lr.getLineBottom(0) - lr.getLineTop(0)).toFloat()
                .takeIf { it > 0f } ?: (lineHeight * tb.kind.scale)
            var startLine = 0
            var gapApplied = false
            while (startLine < lr.lineCount) {
                // 段首留段间距：本页已有内容才留（页首不留，否则页面顶部凭空空一行）
                if (startLine == 0 && !gapApplied) {
                    gapApplied = true
                    if (blocks.isNotEmpty() && gapPx > 0f) {
                        if (used + gapPx <= pageHeight) used += gapPx else flush()
                    }
                }
                var room = (pageHeight - used) / blockLine
                if (room < 1f) {
                    flush()
                    room = pageHeight / blockLine
                }
                val canLines = room.toInt().coerceAtLeast(1)
                val endLine = minOf(startLine + canLines, lr.lineCount)
                val start = lr.getLineStart(startLine)
                val end = if (endLine >= lr.lineCount) tb.text.length else lr.getLineStart(endLine)
                if (end > start) {
                    blocks.add(
                        PageBlock.Text(
                            text = tb.text.substring(start, end),
                            kind = tb.kind,
                            link = tb.link,
                            startsParagraph = startLine == 0
                        )
                    )
                    used += (endLine - startLine) * blockLine
                }
                startLine = endLine
                if (startLine < lr.lineCount) flush()
            }
        }

        // 段 si 后面的那张图：跟着文字一起排，装不下才换页
        val img = images.getOrNull(si) ?: continue
        val h = (imageHeights[si] ?: 0f).takeIf { it > 0f } ?: (lineHeight * 3)
        if (h >= pageHeight) {
            // 竖长图 / 封面：比一页还高时独占一页，整页等比显示
            flush()
            pages.add(ReaderPage(listOf(PageBlock.Image(img, pageHeight))))
        } else {
            if (used + h > pageHeight) flush()
            blocks.add(PageBlock.Image(img, h))
            used += h
        }
    }
    flush()
    return pages.ifEmpty { listOf(ReaderPage(listOf(PageBlock.Text("")))) }
}

/** 文字块排版结果的 key：把「段序号 + 块序号」打平成一个 Long，避免嵌套 Map */
private fun layoutKey(segIndex: Int, blockIndex: Int): Long =
    segIndex.toLong() * 1000L + blockIndex

/**
 * 分页阅读器：左右滑动翻页、下拉调参、点击查词、长按整句翻译。
 *
 * 分页实现：先用一个透明 Text 在正文宽度下排版一次，拿到 [TextLayoutResult]，
 * 再按行切片成页，最后交给 HorizontalPager 渲染。
 * 这样不依赖 Compose 1.5 的 TextMeasurer，仍可停留在 Compose 1.4.3。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedReader(
    chapterKey: String,
    text: String,
    /** 本章插图，与 [text] 里的 `\uFFFC` 占位符按顺序对应 */
    images: List<ChapterImage>,
    /** 上次读到的页码；进入本章时定位到该页 */
    initialPage: Int,
    /** 跨设备同步来的章内百分比（0~1，<0 表示不可用）；页码无效时按它定位 */
    initialPercent: Float,
    theme: ReadingTheme,
    enabledLevels: Set<String>,
    levels: LevelDictionary,
    levelsReady: Boolean,
    vocabWords: Set<String>,
    highlightVocab: Boolean,
    skipBasic: Boolean,
    fontSize: Float,
    lineHeight: Float,
    onPagesReady: (List<ReaderPage>) -> Unit,
    onPageChanged: (Int) -> Unit,
    onPullDown: () -> Unit,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onWordClick: (String, String) -> Unit,
    onLongPressSentence: (String) -> Unit,
    /** 点击书内超链接（目录页 / 栏目页）→ 带上目标文件路径 */
    onInternalLink: (String) -> Unit
) {
    val density = LocalDensity.current
    var pages by remember(chapterKey) { mutableStateOf<List<ReaderPage>>(emptyList()) }
    // 每段解析成「带层级的块」后逐块测量（每块一个透明 Text）
    var segLayouts by remember(chapterKey) { mutableStateOf<Map<Long, TextLayoutResult>>(emptyMap()) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageHeightPx = with(density) { (maxHeight - 26.dp).toPx() }
        val lineHeightPx = with(density) { lineHeight.sp.toPx() }
        // 正文可用宽度（左右各 18dp 内边距）——插图按它等比换算显示高度
        val contentWidthPx = with(density) { (maxWidth - 36.dp).toPx() }

        // 分页结果同步给父级；放在 LaunchedEffect 里，避免在 layout 阶段写 state
        LaunchedEffect(pages) { if (pages.isNotEmpty()) onPagesReady(pages) }

        // 没插图就是整章文本；有插图时按 IMG_MARK 切段（段数 = 图数 + 1），再逐段解析成带层级的块
        val segments = remember(chapterKey, text, images) {
            if (images.isEmpty()) listOf(text) else text.split(BookParser.IMG_MARK)
        }
        val blocksPerSeg = remember(segments) { segments.map { parseBlocks(it) } }
        val textBlockCount = remember(blocksPerSeg) {
            blocksPerSeg.sumOf { seg -> seg.count { it.text.isNotEmpty() } }
        }

        // 插图尺寸：只读图片头部（inJustDecodeBounds，不真正解码像素），再按正文宽度等比换算
        var imageHeights by remember(chapterKey) { mutableStateOf<Map<Int, Float>>(emptyMap()) }
        LaunchedEffect(chapterKey, images, contentWidthPx, segments, pageHeightPx) {
            if (images.isEmpty() || contentWidthPx <= 0f) return@LaunchedEffect
            imageHeights = withContext(Dispatchers.IO) {
                // 本章几乎只有一张图（封面、整页海报）时放宽高度，让它尽量铺满；正文插图则限高，
                // 否则宽屏上图片会撑满整页、把同段文字全挤到下一页，失去「图文混排」的观感
                val textChars = segments.sumOf { s -> s.count { !it.isWhitespace() } }
                val ratio = if (textChars < 80) 0.94f else IMAGE_MAX_PAGE_RATIO
                images.mapIndexedNotNull { i, img ->
                    if (img.path.isBlank()) return@mapIndexedNotNull null
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(img.path, opts)
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) return@mapIndexedNotNull null
                    val raw = contentWidthPx * opts.outHeight / opts.outWidth
                    i to raw.coerceAtMost(pageHeightPx * ratio)
                }.toMap()
            }
        }

        // 文字测量 + 图片尺寸都就绪后拼装页序列（文字与插图混排）
        LaunchedEffect(segLayouts, imageHeights, blocksPerSeg, images, pageHeightPx, textBlockCount) {
            if (segLayouts.size < textBlockCount) return@LaunchedEffect
            if (images.isNotEmpty() && imageHeights.isEmpty()) return@LaunchedEffect
            val built = paginateFlow(
                blocksPerSeg = blocksPerSeg,
                images = images,
                layouts = segLayouts,
                imageHeights = imageHeights,
                pageHeight = pageHeightPx,
                lineHeight = lineHeightPx,
                gapPx = lineHeightPx * PARA_GAP_RATIO
            )
            if (built.isNotEmpty()) pages = built
        }

        if (pages.isEmpty()) {
            // 测量阶段：不可见地排版（纯文本排一次；含插图时逐段各排一次）
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .alpha(0f)
            ) {
                blocksPerSeg.forEachIndexed { si, segBlocks ->
                    segBlocks.forEachIndexed { bi, tb ->
                        if (tb.text.isNotEmpty()) {
                            Text(
                                // 用与渲染同一份「带首行缩进」的文本 + 同一份样式，否则测出的行数与实际排版对不上
                                text = displayText(tb.text, tb.kind),
                                style = blockStyle(tb.kind, fontSize, lineHeight, theme),
                                modifier = Modifier.fillMaxWidth(),
                                onTextLayout = { lr ->
                                    segLayouts = segLayouts + (layoutKey(si, bi) to lr)
                                }
                            )
                        }
                    }
                }
            }
        } else {
            var idx by remember(chapterKey) { mutableStateOf(initialPage.coerceAtLeast(0)) }
            var percentApplied by remember(chapterKey) { mutableStateOf(false) }
            // 分页是异步的：页数就绪后再校正定位
            LaunchedEffect(pages.size) {
                if (pages.isEmpty()) return@LaunchedEffect
                if (idx > pages.lastIndex) idx = 0
                // 没有本机页码（例如进度是从别的设备同步来的）→ 用百分比换算成页码
                if (!percentApplied && initialPage <= 0 && initialPercent in 0.01f..1f) {
                    idx = (initialPercent * pages.size).toInt().coerceIn(0, pages.lastIndex)
                    percentApplied = true
                    onPageChanged(idx)
                }
            }
            LaunchedEffect(idx) {
                onPageChanged(idx)
            }

            // 手势挂在 Crossfade 自身（父节点）上：子节点 WordText 的点击优先命中，
            // 父节点只在横向/纵向拖动超过阈值时才消费，因此不会挡住点词。
            Crossfade(
                targetState = idx,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(chapterKey, pages.size, idx, hasPrevChapter, hasNextChapter) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onHorizontalDrag = { _, d -> dx += d },
                            onDragEnd = {
                                // 章内翻页；到章节首/尾时继续滑动则切章
                                if (dx < -60f) {
                                    if (idx < pages.size - 1) idx++
                                    else if (hasNextChapter) onNextChapter()
                                } else if (dx > 60f) {
                                    if (idx > 0) idx--
                                    else if (hasPrevChapter) onPrevChapter()
                                }
                            }
                        )
                    }
                    .pointerInput(chapterKey) {
                        var drag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragCancel = { drag = 0f },
                            onVerticalDrag = { _, dy -> drag += dy },
                            onDragEnd = { if (drag > 70f) onPullDown() }
                        )
                    }
            ) { i ->
                val page = pages[i.coerceIn(0, pages.size - 1)]
                // 一页内的块按原文顺序纵向排列：文字走 WordText（点词 / 分级高亮），插图走 ChapterImageView
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    // 段间距（px → dp，与 paginateFlow 里累加的 gapPx 同源）
                    val gapDp = with(density) { (lineHeightPx * PARA_GAP_RATIO).toDp() }
                    page.blocks.forEachIndexed { index, block ->
                        // 段间距：本页第一块不加（页面顶部留白会显得空）
                        if (index > 0 && block is PageBlock.Text && block.startsParagraph) {
                            Spacer(Modifier.height(gapDp))
                        }
                        when (block) {
                            is PageBlock.Text -> {
                                val target = block.link
                                if (target != null) {
                                    // EPUB 目录页 / 栏目页里的链接：整行可点击跳章
                                    LinkLine(
                                        text = block.text,
                                        kind = block.kind,
                                        fontSize = fontSize,
                                        lineHeight = lineHeight,
                                        theme = theme,
                                        onClick = { onInternalLink(target) }
                                    )
                                } else {
                                    WordText(
                                        text = block.text,
                                        enabledLevels = enabledLevels,
                                        levels = levels,
                                        levelsReady = levelsReady,
                                        vocabWords = vocabWords,
                                        highlightVocab = highlightVocab,
                                        skipBasic = skipBasic,
                                        // 按块层级放大/缩小字号（H1 > H2 > H3 > 正文）
                                        fontSize = fontSize * block.kind.scale,
                                        lineHeight = lineHeight * block.kind.scale,
                                        bold = block.kind.bold,
                                        dim = block.kind.dim,
                                        theme = theme,
                                        onWordClick = onWordClick,
                                        onLongPressSentence = onLongPressSentence,
                                        // 首行缩进：只缩「段落开头那一片」的正文（与测量侧同源）
                                        indentFirstLine = block.startsParagraph &&
                                            needsIndent(block.kind, block.text),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            is PageBlock.Image -> ChapterImageView(
                                image = block.image,
                                heightPx = block.heightPx,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 书内超链接行（EPUB 的目录页 / 栏目页）：整块可点击跳转，蓝色 + 下划线。
 *
 * 样式与 [blockStyle] 同源（字号/行高一致），避免与实际测量结果对不上。
 */
@Composable
private fun LinkLine(
    text: String,
    kind: BlockKind,
    fontSize: Float,
    lineHeight: Float,
    theme: ReadingTheme,
    onClick: () -> Unit
) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        style = blockStyle(kind, fontSize, lineHeight, theme).copy(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline
        )
    )
}

/**
 * 章节插图：**内联在正文中**，按分页时算好的 [heightPx] 占位显示（不跳动）。
 *
 * 大图统一降采样解码（RGB_565）以免 OOM；图片缺失时显示「［图片］+ alt」占位，翻页不受影响。
 */
@Composable
private fun ChapterImageView(
    image: ChapterImage,
    heightPx: Float,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, image.path) {
        value = withContext(Dispatchers.IO) {
            if (image.path.isBlank()) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.path, bounds)
                var sample = 1
                while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 900) sample *= 2
                BitmapFactory.decodeFile(
                    image.path,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                )
            }.getOrNull()
        }
    }

    val height = with(LocalDensity.current) { heightPx.toDp() }
    Box(
        modifier
            .height(height)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = image.alt.ifBlank { null },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                "［图片］" + image.alt.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 下拉出现的阅读参数面板 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingParamsPanel(
    fontSize: Float,
    lineHeight: Float,
    themeIndex: Int,
    onFontSize: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onTheme: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
        Text("阅读设置", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        Text("字号 ${fontSize.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Slider(value = fontSize, onValueChange = onFontSize, valueRange = 13f..26f, steps = 12)

        Text("行距 ${lineHeight.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Slider(value = lineHeight, onValueChange = onLineHeight, valueRange = 20f..44f, steps = 11)

        Spacer(Modifier.height(6.dp))
        Text("背景", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReadingTheme.values().forEachIndexed { i, t ->
                FilterChip(
                    selected = i == themeIndex,
                    onClick = { onTheme(i) },
                    label = { Text(t.label, fontSize = 12.sp) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(t.background)
                        )
                    }
                )
            }
        }
    }
}

/** 右下角「详情」 */
@Composable
private fun ReaderDetailPanel(
    book: Book?,
    chapterTitle: String,
    chapterIndex: Int,
    chapterCount: Int,
    pageIndex: Int,
    pageCount: Int,
    charCount: Int,
    vocabCount: Int,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
        Text("阅读详情", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Text(book?.title ?: "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "第 ${chapterIndex + 1}/${chapterCount} 章 · 第 ${pageIndex + 1}/${pageCount.coerceAtLeast(1)} 页",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (chapterTitle.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(chapterTitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatChip("本章字数", charCount.toString())
            StatChip("已收藏", vocabCount.toString())
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onPrevChapter,
                enabled = chapterIndex > 0,
                modifier = Modifier.weight(1f)
            ) { Text("上一章", fontSize = 13.sp) }
            OutlinedButton(
                onClick = onNextChapter,
                enabled = chapterIndex < chapterCount - 1,
                modifier = Modifier.weight(1f)
            ) { Text("下一章", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
