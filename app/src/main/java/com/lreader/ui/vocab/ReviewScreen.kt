package com.lreader.ui.vocab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lreader.analysis.PosDefs
import com.lreader.analysis.WordAnalysis
import com.lreader.data.SettingsStore
import com.lreader.data.VocabRepository
import com.lreader.dict.DictDatabase
import com.lreader.model.DictEntry
import com.lreader.model.ReviewGrade
import com.lreader.model.TranslationResult
import com.lreader.model.VocabWord
import com.lreader.speech.SpeechManager
import com.lreader.translate.TranslationEngines
import com.lreader.ui.reader.DictHtmlView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 背单词页。
 *
 * 交互规则：
 *  - 每张卡片出现时**自动朗读一次美音**；
 *  - 三个操作：**记得**（通过）、**不记得**（先跳过，排到队尾稍后再次出现）、**查看释义**（展开原句与详细解析）；
 *  - 进度 = 已掌握 / 本轮总数；
 *  - 只有「记得」才写入 SM-2 的正向进度，「不记得」按 grade 0 记录一次遗忘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    /** 内嵌到「背单词」Tab 时隐藏自身顶栏 */
    embedded: Boolean = false
) {
    val context = LocalContext.current
    val repo = remember { VocabRepository(context) }
    val settings = remember { SettingsStore(context) }
    // 进程级单例，见 SpeechManager.get
    val speech = SpeechManager.get(context)
    val dict = remember { DictDatabase(context) }

    var pending by remember { mutableStateOf<List<VocabWord>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var mastered by remember { mutableStateOf(0) }
    var forgotTimes by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    var dictEntry by remember { mutableStateOf<DictEntry?>(null) }
    var showMore by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        speech.accent = SpeechManager.Accent.US
        speech.rate = settings.speechRate
        speech.init()

        val list = withContext(Dispatchers.IO) {
            val due = repo.dueToday()
            val target = if (due.isEmpty()) repo.all() else due
            dict.ensureReady()
            target
        }
        pending = list
        total = list.size
        ready = true
    }
    DisposableEffect(Unit) {
        onDispose {
            // 只解除回调，不 shutdown 单例
            speech.onOnlineError = null
        }
    }

    val current = pending.firstOrNull()

    // 卡片切换时：复位展开态 + 自动朗读一次美音 + 预取词典条目
    LaunchedEffect(current?.id) {
        val w = current ?: return@LaunchedEffect
        revealed = false
        dictEntry = null
        showMore = false
        speech.accent = SpeechManager.Accent.US
        speech.speak(w.word)
        // 「详细解析」需要按词性分层展示，生词本里存的扁平文本做不到，故提前取词典 HTML
        dictEntry = withContext(Dispatchers.IO) { dict.lookup(w.word) }
    }

    fun speakWith(accent: SpeechManager.Accent) {
        val w = current ?: return
        speech.accent = accent
        speech.speak(w.word)
    }

    fun onKnown() {
        val w = current ?: return
        repo.review(w, ReviewGrade.KNOWN)
        mastered += 1
        pending = pending.drop(1)
    }

    fun onForgot() {
        val w = current ?: return
        repo.review(w, ReviewGrade.FORGOT)
        forgotTimes += 1
        // 跳过该词，排到队尾，本轮后面还会再出现
        pending = pending.drop(1) + w
    }

    fun openFullEntry() {
        val w = current ?: return
        if (dictEntry != null) {
            showMore = true
            return
        }
        scope.launch {
            dictEntry = withContext(Dispatchers.IO) { dict.lookup(w.word) }
            showMore = true
        }
    }

    Scaffold(
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = { Text("背单词", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !ready -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                total == 0 -> EmptyReview(Modifier.align(Alignment.Center))

                current == null -> FinishedView(mastered, total, forgotTimes, onBack)

                else -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                    ReviewCard(
                        word = current,
                        entry = dictEntry,
                        mastered = mastered,
                        total = total,
                        revealed = revealed,
                        onToggleReveal = { revealed = !revealed },
                        onMore = { openFullEntry() },
                        onSpeakUs = { speakWith(SpeechManager.Accent.US) },
                        onSpeakUk = { speakWith(SpeechManager.Accent.UK) },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onKnown() },
                            modifier = Modifier.weight(1f).height(54.dp)
                        ) { Text("😊 记得", fontSize = 15.sp) }

                        OutlinedButton(
                            onClick = { onForgot() },
                            modifier = Modifier.weight(1f).height(54.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("😢 不记得", fontSize = 15.sp) }
                    }
                }
            }
        }
    }

    // ---------- 完整词典（更多释义） ----------
    if (showMore && current != null) {
        ModalBottomSheet(onDismissRequest = { showMore = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp)
            ) {
                Text(current.word, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current.phonetic.isNotBlank()) {
                        Text(
                            current.phonetic,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (current.level.isNotBlank()) {
                        LevelChip(current.level)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Divider()
                Spacer(Modifier.height(8.dp))

                val entry = dictEntry
                when {
                    entry == null -> Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    entry.html.isBlank() -> Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("未收录该单词", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    else -> DictHtmlView(
                        html = entry.html,
                        modifier = Modifier.fillMaxWidth(),
                        autoHeight = true
                    )
                }

                if (current.sentence.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    Text("原文句子", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    SentenceWithWord(current.sentence, current.word)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 卡片
// ---------------------------------------------------------------------------

@Composable
private fun ReviewCard(
    word: VocabWord,
    entry: DictEntry?,
    mastered: Int,
    total: Int,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onMore: () -> Unit,
    onSpeakUs: () -> Unit,
    onSpeakUk: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier, elevation = CardDefaults.cardElevation(3.dp)) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            // 进度 + 更多释义
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$mastered/$total",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onMore) { Text("更多释义 >", fontSize = 13.sp) }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        word.word,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        AccentPhonetic("英", word.phonetic, onSpeakUk)
                        AccentPhonetic("美", word.phonetic, onSpeakUs)
                    }

                    Spacer(Modifier.height(22.dp))
                    OutlinedButton(onClick = onToggleReveal) {
                        Text(if (revealed) "收起释义" else "查看释义", fontSize = 14.sp)
                    }

                    if (revealed) {
                        Spacer(Modifier.height(18.dp))
                        WordDetail(word, entry)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/** 「英 /pə'ræmɪtə(r)/ 🔊」这样的音标行 */
@Composable
private fun AccentPhonetic(label: String, phonetic: String, onPlay: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(phonetic.ifBlank { "—" }, fontSize = 14.sp)
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onPlay, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Filled.VolumeUp,
                contentDescription = "$label 发音",
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/**
 * 详细解析：原句（高亮该词）+ 级别 / 词性分层的释义 / 出处 / 复习记录。
 *
 * 释义按**词性分层**展示（每个词性一个标签 + 逐条释义），而不是把整段释义
 * 挤成一坨文本；数据优先取词典 HTML（能还原词性与义项边界），
 * 词典查不到时才退回生词本里存的扁平文本。
 */
@Composable
private fun WordDetail(word: VocabWord, entry: DictEntry?) {
    val groups = remember(word.word, word.meaning, entry?.html) {
        fun layers(list: List<PosDefs>) = list
            .map { g -> g.copy(defs = g.defs.filter { !WordAnalysis.isCrossReference(it) }) }
            .filter { it.defs.isNotEmpty() }

        val fromHtml = entry?.html?.let { layers(WordAnalysis.parseDefinitions(it)) }.orEmpty()
        if (fromHtml.isNotEmpty()) fromHtml else layers(WordAnalysis.splitByPos(word.meaning))
    }
    // 变形词（abruptly → abrupt）释义取自源词，提示一下，避免用户疑惑
    val formOf = remember(word.meaning) { WordAnalysis.crossRefTargetOfText(word.meaning) }
    val df = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    Column(Modifier.fillMaxWidth()) {
        Divider()
        Spacer(Modifier.height(12.dp))

        if (word.sentence.isNotBlank()) {
            Text("原文句子", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            SentenceWithWord(word.sentence, word.word)
            SentenceTranslation(word.sentence)
            if (word.sourceBook.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "来自《${word.sourceBook}》",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        Text("详细解析", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        if (word.level.isNotBlank() || formOf != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (word.level.isNotBlank()) LevelChip(word.level)
                if (word.level.isNotBlank() && formOf != null) Spacer(Modifier.width(6.dp))
                if (formOf != null) LevelChip("$formOf 的变形")
            }
            Spacer(Modifier.height(8.dp))
        }

        if (groups.isEmpty()) {
            val fallback = WordAnalysis.cleanMeaning(word.word, word.meaning)
            if (fallback.isNotBlank()) Text(fallback, fontSize = 14.sp, lineHeight = 22.sp)
        } else {
            groups.forEachIndexed { i, g ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                PosGroup(g)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "历史复习 ${word.repetition} 次 · 难度系数 ${"%.2f".format(word.easiness)} · " +
                "下次 ${df.format(Date(word.nextReview))}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 一个词性的释义分块：词性标签 + 逐条义项。
 * 参考主流背单词 App 的排版：词性用标签高亮，义项一行一条，不要连成一整段。
 */
@Composable
private fun PosGroup(group: PosDefs) {
    Column(Modifier.fillMaxWidth()) {
        if (group.pos.isNotBlank()) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            ) {
                Text(
                    WordAnalysis.posLabel(group.pos),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(5.dp))
        }
        group.defs.forEachIndexed { i, def ->
            if (i > 0) Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "•",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(def, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

/**
 * 原句的**整句译文**，紧跟在原句下方。
 *
 * 展开释义后自动翻一次，走「设置 → 翻译引擎」里选中的引擎；
 * 默认是免 Key 的免费在线接口，装好即可用。同一句走内存缓存，
 * 前后翻卡片不会重复联网。失败时给出原因并可点「重试」。
 */
@Composable
private fun SentenceTranslation(sentence: String) {
    val context = LocalContext.current
    val targetLang = remember { SettingsStore(context).targetLang }
    var loading by remember(sentence) { mutableStateOf(false) }
    var result by remember(sentence) { mutableStateOf<TranslationResult?>(null) }
    var retry by remember(sentence) { mutableStateOf(0) }

    LaunchedEffect(sentence, retry) {
        if (sentence.isBlank()) return@LaunchedEffect
        loading = true
        result = withContext(Dispatchers.IO) {
            TranslationEngines.translate(sentence, to = targetLang)
        }
        loading = false
    }

    if (sentence.isBlank()) return

    Spacer(Modifier.height(8.dp))
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            val r = result
            when {
                loading -> Text(
                    "译文生成中…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                r?.success == true && r.translatedText.isNotBlank() ->
                    Text(r.translatedText, fontSize = 14.sp, lineHeight = 21.sp)

                r != null -> {
                    Text(
                        "翻译失败：${r.errorMessage}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(
                        onClick = { retry += 1 },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) { Text("重试", fontSize = 13.sp) }
                }

                else -> Text(
                    "译文生成中…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 原句，并把目标单词标红加粗 */
@Composable
private fun SentenceWithWord(sentence: String, word: String) {
    val annotated = remember(sentence, word) {
        buildAnnotatedString {
            WordAnalysis.splitByWord(sentence, word).forEach { (text, isTarget) ->
                if (isTarget) {
                    withStyle(
                        SpanStyle(color = Color(0xFFD32F2F), fontWeight = FontWeight.SemiBold)
                    ) { append(text) }
                } else {
                    append(text)
                }
            }
        }
    }
    Text(annotated, fontSize = 13.sp, lineHeight = 21.sp)
}

@Composable
private fun LevelChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    ) {
        Text(
            text,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ---------------------------------------------------------------------------
// 空态 / 完成
// ---------------------------------------------------------------------------

@Composable
private fun EmptyReview(modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("生词本里还没有单词", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "阅读时点击单词，在弹窗中点「+」即可收藏",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FinishedView(known: Int, total: Int, forgotTimes: Int, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("本轮复习完成", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("共 $total 个单词 · 记得 $known 个", fontSize = 14.sp)
        if (forgotTimes > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "累计跳过 $forgotTimes 次",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}
