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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
import com.lreader.model.TranslationResult
import com.lreader.speech.SpeechManager
import com.lreader.translate.TranslationEngines
import kotlinx.coroutines.Dispatchers
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
                    // 复用既有字段记录页码，不改数据库结构
                    lastScrollY = pageIndex
                )
                repo.save(list)
            }
        } catch (_: Exception) {
        }
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
    theme: ReadingTheme,
    onWordClick: (String, String) -> Unit,
    onLongPressSentence: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // levelsReady 必须参与 key：LevelDictionary 的索引是普通对象（非 Compose State），
    // 不入 key 时索引就绪后不会重新着色（历史 bug：首次进入无色，切章才上色）。
    val annotated = remember(
        text, enabledLevels, vocabWords, highlightVocab, skipBasic, levelsReady
    ) {
        buildAnnotatedString {
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
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 手势块只随 (annotated, layout) 重建，回调要用最新值，否则闭包里是旧页
    val curWordClick by rememberUpdatedState(onWordClick)
    val curLongPress by rememberUpdatedState(onLongPressSentence)

    Text(
        text = annotated,
        modifier = modifier
            .fillMaxSize()
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
            color = theme.text
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

/**
 * 按「每页行数」把整章文本切片成多页。
 * 全部在行首处切分，保证每页内部的换行与原排版一致。
 */
private fun paginate(text: String, layout: TextLayoutResult, linesPerPage: Int): List<String> {
    if (text.isEmpty()) return listOf("")
    if (layout.lineCount <= 0) return listOf(text)
    val out = ArrayList<String>()
    var startLine = 0
    while (startLine < layout.lineCount) {
        val endLine = minOf(startLine + linesPerPage, layout.lineCount)
        val start = layout.getLineStart(startLine)
        val end = if (endLine >= layout.lineCount) text.length else layout.getLineStart(endLine)
        if (end > start) out.add(text.substring(start, end))
        startLine = endLine
    }
    return out.ifEmpty { listOf(text) }
}

/**
 * 阅读页的一页：正文页（走 [WordText]，带点词与分级高亮）或**插图页**。
 * EPUB 的插图会作为独立一页，插在它原本所在的正文位置后面。
 */
private sealed interface ReaderPage {
    data class Text(val text: String) : ReaderPage
    data class Image(val image: ChapterImage) : ReaderPage
}

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
    onLongPressSentence: (String) -> Unit
) {
    val density = LocalDensity.current
    var pages by remember(chapterKey) { mutableStateOf<List<ReaderPage>>(emptyList()) }
    // 含插图时按占位符切段、逐段测量（每段一个透明 Text）
    var segLayouts by remember(chapterKey) { mutableStateOf<Map<Int, TextLayoutResult>>(emptyMap()) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageHeightPx = with(density) { (maxHeight - 26.dp).toPx() }
        val lineHeightPx = with(density) { lineHeight.sp.toPx() }
        val perPage = maxOf(1, (pageHeightPx / lineHeightPx).toInt())

        // 分页结果同步给父级；放在 LaunchedEffect 里，避免在 layout 阶段写 state
        LaunchedEffect(pages) { if (pages.isNotEmpty()) onPagesReady(pages) }

        // 没插图就是整章文本；有插图时按 IMG_MARK 切段（段数 = 图数 + 1）
        val segments = remember(chapterKey, text, images) {
            if (images.isEmpty()) listOf(text) else text.split(BookParser.IMG_MARK)
        }

        // 逐段测量完成后拼装页序列：段0 的页 → 图0 → 段1 的页 → 图1 → …
        LaunchedEffect(segLayouts, segments, perPage) {
            if (segLayouts.size < segments.size) return@LaunchedEffect
            val built = ArrayList<ReaderPage>()
            segments.forEachIndexed { i, seg ->
                val lr = segLayouts[i] ?: return@forEachIndexed
                paginate(seg, lr, perPage).forEach { built.add(ReaderPage.Text(it)) }
                images.getOrNull(i)?.let { built.add(ReaderPage.Image(it)) }
            }
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
                segments.forEachIndexed { i, seg ->
                    Text(
                        text = seg,
                        style = TextStyle(fontSize = fontSize.sp, lineHeight = lineHeight.sp),
                        modifier = Modifier.fillMaxWidth(),
                        onTextLayout = { lr -> segLayouts = segLayouts + (i to lr) }
                    )
                }
            }
        } else {
            var idx by remember(chapterKey) { mutableStateOf(0) }
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
                when (val page = pages[i.coerceIn(0, pages.size - 1)]) {
                    is ReaderPage.Text -> WordText(
                        text = page.text,
                        enabledLevels = enabledLevels,
                        levels = levels,
                        levelsReady = levelsReady,
                        vocabWords = vocabWords,
                        highlightVocab = highlightVocab,
                        skipBasic = skipBasic,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        theme = theme,
                        onWordClick = onWordClick,
                        onLongPressSentence = onLongPressSentence,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    )

                    is ReaderPage.Image -> ImagePage(
                        image = page.image,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 插图页：整页居中等比显示一张 EPUB 插图。
 *
 * 大图统一降采样解码（RGB_565）以免 OOM；图片缺失时显示「［图片］+ alt」占位，翻页不受影响。
 */
@Composable
private fun ImagePage(image: ChapterImage, modifier: Modifier = Modifier) {
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

    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = image.alt.ifBlank { null },
                modifier = Modifier.fillMaxWidth(),
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
