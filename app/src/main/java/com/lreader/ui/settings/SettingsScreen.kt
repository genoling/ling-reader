package com.lreader.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lreader.data.ReleaseInfo
import com.lreader.data.SettingsStore
import com.lreader.data.UpdateRepository
import com.lreader.data.VocabRepository
import com.lreader.dict.DictCatalog
import com.lreader.dict.DictDatabase
import com.lreader.dict.DictManager
import com.lreader.dict.DictResource
import com.lreader.dict.LevelDictionary
import com.lreader.speech.SpeechManager
import com.lreader.speech.TtsCatalog
import com.lreader.speech.TtsInstaller
import com.lreader.sync.SyncCode
import com.lreader.sync.SyncManager
import com.lreader.translate.TranslationEngines
import com.lreader.ui.theme.AppThemeState
import com.lreader.ui.theme.ThemePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 管理员入口密码（需求指定） */
private const val ADMIN_PASSWORD = "8848"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenAdmin: () -> Unit = {}) {
    val context = LocalContext.current
    val dict = remember { DictDatabase(context) }
    val levels = remember { LevelDictionary(context) }
    val settings = remember { SettingsStore(context) }
    val vocab = remember { VocabRepository(context) }

    // 词典资源管理（进程级单例：安装状态与下载进度跨页面共享）
    val dictMgr = remember { DictManager.get(context) }

    var dictCount by remember { mutableStateOf(0) }
    var suppCount by remember { mutableStateOf(0) }
    var levelTotal by remember { mutableStateOf(0) }
    var vocabCount by remember { mutableStateOf(0) }
    /** 状态刷新令牌：资源管理器通知变化时自增，驱动词条数/按钮状态重算 */
    var dictVersion by remember { mutableStateOf(0) }
    var editingSource by remember { mutableStateOf(false) }
    var sourceDraft by remember { mutableStateOf("") }

    var autoSpeak by remember { mutableStateOf(settings.autoSpeak) }
    var accent by remember { mutableStateOf(settings.accent) }
    var speechRate by remember { mutableStateOf(settings.speechRate) }
    var fontSize by remember { mutableStateOf(settings.fontSize) }
    var lineHeight by remember { mutableStateOf(settings.lineHeight) }
    var enabledLevels by remember { mutableStateOf(settings.enabledLevels) }
    var highlightVocab by remember { mutableStateOf(settings.highlightVocab) }
    var skipBasicWords by remember { mutableStateOf(settings.skipBasicWords) }
    var themePreset by remember { mutableStateOf(settings.themePreset) }
    var engineId by remember { mutableStateOf(TranslationEngines.preferredEngine) }

    // 进程级单例，见 SpeechManager.get
    val speech = SpeechManager.get(context)
    var speechStatus by remember { mutableStateOf(speech.statusText()) }
    var speechError by remember { mutableStateOf<String?>(null) }
    /** 设备上已安装的语音引擎；装了新引擎后点「刷新」重新读取 */
    var engineItems by remember { mutableStateOf(speech.engineItems()) }

    // ---- 关于 / 版本更新 ----
    val scope = rememberCoroutineScope()
    val updater = remember { UpdateRepository(context) }
    val versionName = remember { updater.currentVersionName() }
    val versionCode = remember { updater.currentVersionCode() }
    var checking by remember { mutableStateOf(false) }
    var newRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var updateChecked by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }

    fun checkUpdate() {
        scope.launch {
            checking = true
            updateError = null
            updater.check()
                .onSuccess { info ->
                    newRelease = info.takeIf { UpdateRepository.isNewer(it.version, versionName) }
                    updateChecked = true
                }
                .onFailure {
                    updateError = it.message ?: "检查更新失败"
                    updateChecked = true
                }
            checking = false
        }
    }

    fun downloadAndInstall() {
        val info = newRelease ?: return
        scope.launch {
            downloading = true
            downloadProgress = 0f
            val file = updater.download(info) { downloadProgress = it }
            downloading = false
            if (file == null) {
                updateError = "下载失败，请换网络后重试"
            } else {
                TtsInstaller.install(context, file).onFailure {
                    updateError = "无法打开安装界面：${it.message ?: ""}"
                }
            }
        }
    }

    // 词典安装状态变化（下载完成 / 被删除）后重新统计词条数
    LaunchedEffect(dictVersion) {
        withContext(Dispatchers.IO) {
            dictCount = if (dict.ensureReady()) dict.entryCount else 0
            suppCount = dict.supplementCount
        }
    }

    DisposableEffect(dictMgr) {
        val prev = dictMgr.onChanged
        dictMgr.onChanged = { dictVersion++ }
        onDispose { dictMgr.onChanged = prev }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (levels.ensureReady()) levelTotal = levels.levelCounts.values.sum()
            vocabCount = vocab.count()
        }
        speech.accent = if (accent == "UK") SpeechManager.Accent.UK else SpeechManager.Accent.US
        speech.rate = speechRate
        speech.init()
        speechStatus = speech.statusText()
    }
    DisposableEffect(Unit) {
        // 引擎状态变化（含「无引擎 → 自动切在线兜底」）即时反馈到自检行
        speech.onStatusChange = { _, _ -> speechStatus = speech.statusText() }
        speech.onOnlineError = { speechError = "发音失败：$it（在线兜底也没成功，请检查网络）" }
        onDispose {
            speech.onStatusChange = null
            speech.onOnlineError = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------- 界面配色 ----------
            SectionTitle("界面配色")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "选择后立即生效，仅影响界面主色",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemePreset.values().forEachIndexed { i, p ->
                            FilterChip(
                                selected = i == themePreset,
                                onClick = {
                                    themePreset = i
                                    settings.themePreset = i
                                    AppThemeState.apply(i)
                                },
                                label = { Text(p.label, fontSize = 12.sp) },
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(13.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(p.accent)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // ---------- 云同步（生词本） ----------
            SectionTitle("云同步")
            SyncSection(settings)

            // ---------- 本地词典（按需下载） ----------
            SectionTitle("本地词典")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "词典资源按需下载，不占用安装包体积；安装后点词查询完全离线、无次数限制。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    DictCatalog.resources.forEachIndexed { i, res ->
                        if (i > 0) {
                            Spacer(Modifier.height(10.dp))
                            Divider()
                            Spacer(Modifier.height(10.dp))
                        }
                        DictResourceRow(
                            res = res,
                            status = dictMgr.statusOf(res.id),
                            sizeOnDisk = dictMgr.sizeOnDisk(res),
                            entryCount = when (res.id) {
                                DictCatalog.ID_MAIN -> dictCount
                                DictCatalog.ID_ECDICT -> suppCount
                                DictCatalog.ID_LEVELS -> levelTotal
                                else -> 0
                            },
                            url = dictMgr.urlOf(res),
                            onDownload = { dictMgr.download(res) },
                            onCancel = { dictMgr.cancel(res) },
                            onRemove = { dictMgr.remove(res) }
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "生词本：$vocabCount 词（保存在本地，与词典无关）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            editingSource = !editingSource
                            if (editingSource) sourceDraft = settings.dictSourceBase
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            if (editingSource) "收起下载源设置" else "下载源设置（默认 GitHub Release）",
                            fontSize = 12.sp
                        )
                    }
                    if (editingSource) {
                        Text(
                            "当前地址：${dictMgr.sourceUrl()}",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = sourceDraft,
                            onValueChange = { sourceDraft = it },
                            label = { Text("下载源前缀", fontSize = 12.sp) },
                            placeholder = { Text(DictCatalog.DEFAULT_SOURCE, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    settings.dictSourceBase = sourceDraft
                                    dictVersion++
                                    editingSource = false
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("保存", fontSize = 12.sp) }
                            Spacer(Modifier.width(12.dp))
                            TextButton(
                                onClick = {
                                    sourceDraft = ""
                                    settings.dictSourceBase = ""
                                    dictVersion++
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("恢复默认", fontSize = 12.sp) }
                        }
                        Hint(
                            "填一个以 / 结尾的前缀（末尾拼附件名即为完整地址），例如 " +
                                "https://mirror.example.com/dict/ 。直连 GitHub 较慢时可换镜像或自建服务器。"
                        )
                    }
                }
            }

            // ---------- 发音 ----------
            SectionTitle("发音")
            Card {
                Column(Modifier.padding(14.dp)) {
                    SwitchRow("点击单词自动发音", autoSpeak) {
                        autoSpeak = it
                        settings.autoSpeak = it
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("口音", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentChip("美音", accent == "US") {
                            accent = "US"
                            settings.accent = "US"
                            speech.accent = SpeechManager.Accent.US
                            speech.speak("hello")
                        }
                        AccentChip("英音", accent == "UK") {
                            accent = "UK"
                            settings.accent = "UK"
                            speech.accent = SpeechManager.Accent.UK
                            speech.speak("hello")
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("语速：${"%.1f".format(speechRate)}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Slider(
                        value = speechRate,
                        onValueChange = {
                            speechRate = it
                            settings.speechRate = it
                            speech.rate = it
                        },
                        valueRange = 0.5f..2.0f,
                        steps = 5
                    )

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("语音引擎", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                engineItems = speech.engineItems()
                                speechStatus = speech.statusText()
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("刷新", fontSize = 12.sp) }
                    }
                    Hint(
                        "手机与平板发音不同，是因为各自用了本机的默认引擎；两台设备选同一个引擎即可统一。" +
                            "列表里没有想要的引擎时，先在系统里装好，再回来点「刷新」。"
                    )
                    EngineRow(
                        title = "跟随系统默认",
                        subtitle = "由系统的「文字转语音 → 首选引擎」决定",
                        selected = settings.ttsEngine.isBlank() && !settings.preferOnlineSpeech
                    ) {
                        settings.ttsEngine = ""
                        settings.preferOnlineSpeech = false
                        // 先记录引擎选择、再关掉强制在线：后者才真正把系统引擎建起来
                        speech.enginePackage = null
                        speech.preferOnline = false
                        speech.speak("hello")
                    }
                    engineItems.forEach { item ->
                        EngineRow(
                            title = item.label,
                            subtitle = item.pkg + if (item.isDefault) "（系统默认）" else "",
                            selected = settings.ttsEngine == item.pkg && !settings.preferOnlineSpeech
                        ) {
                            settings.ttsEngine = item.pkg
                            settings.preferOnlineSpeech = false
                            speech.enginePackage = item.pkg
                            speech.preferOnline = false
                            speech.speak("hello")
                        }
                    }
                    if (engineItems.isEmpty()) {
                        Hint("没有检测到系统语音引擎（部分模拟器如此），可直接打开下面的在线发音。")
                    }

                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    Text("安装离线语音引擎（可选）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Hint(
                        "开源离线引擎（sherpa-onnx，Apache-2.0），音质比系统自带的机械音自然得多。" +
                            "下载完点「安装」，系统会弹窗确认（首次需允许「安装未知来源应用」）；" +
                            "装好后回到上面的「语音引擎」选中它即可。仅支持 arm64 设备。"
                    )
                    TtsCatalog.ENGINES.forEachIndexed { i, eng ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        TtsEngineRow(
                            res = eng,
                            status = dictMgr.statusOf(eng.id),
                            sizeOnDisk = dictMgr.sizeOnDisk(eng),
                            onDownload = { dictMgr.download(eng) },
                            onCancel = { dictMgr.cancel(eng) },
                            onInstall = {
                                TtsInstaller.install(context, dictMgr.fileOf(eng)).onFailure {
                                    speechError = "安装失败：${it.message ?: "无法打开系统安装界面"}"
                                }
                            },
                            onRemove = { dictMgr.remove(eng) }
                        )
                    }

                    SwitchRow("始终使用在线发音（免装引擎、音色统一）", settings.preferOnlineSpeech) { on ->
                        settings.preferOnlineSpeech = on
                        speech.preferOnline = on
                        speech.speak("hello")
                    }

                    Spacer(Modifier.height(10.dp))
                    Divider()
                    Spacer(Modifier.height(10.dp))
                    Text("发音自检", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        speechStatus,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = if (speech.mode == SpeechManager.Mode.SYSTEM) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Hint(
                        "点上面的「美音 / 英音」可直接试听。设备没有系统语音引擎时" +
                            "（部分模拟器如此）App 会自动改用在线发音兜底，需要联网。"
                    )
                    speechError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, fontSize = 11.sp, lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            val intent = Intent(ACTION_TTS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }.onFailure {
                                runCatching {
                                    context.startActivity(
                                        Intent(ACTION_SYSTEM_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("打开系统语音设置", fontSize = 13.sp)
                    }
                }
            }

            // ---------- 阅读显示 ----------
            SectionTitle("阅读显示")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("字号：${fontSize.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Slider(
                        value = fontSize,
                        onValueChange = {
                            fontSize = it
                            settings.fontSize = it
                        },
                        valueRange = 13f..26f,
                        steps = 12
                    )
                    Text("行距：${lineHeight.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Slider(
                        value = lineHeight,
                        onValueChange = {
                            lineHeight = it
                            settings.lineHeight = it
                        },
                        valueRange = 20f..44f,
                        steps = 11
                    )
                }
            }

            // ---------- 分级词汇高亮 ----------
            SectionTitle("分级词汇高亮")
            Card {
                Column(Modifier.padding(14.dp)) {
                    SwitchRow("高亮生词本中的词（红色）", highlightVocab) {
                        highlightVocab = it
                        settings.highlightVocab = it
                    }
                    Spacer(Modifier.height(4.dp))
                    SwitchRow("忽略基础词（高中 / 四级 / 六级）", skipBasicWords) {
                        skipBasicWords = it
                        settings.skipBasicWords = it
                    }
                    Text(
                        "雅思 / 托福 / GRE 的词表是按「考试要求掌握的词汇」收录的，混有大量高中、四级、六级词" +
                            "（实测雅思 3427 词里 82.7% 与更基础的级别重叠）。" +
                            "开启后勾选这些高阶级别时不再高亮基础词，只留下真正有难度的；" +
                            "只勾高中 / 四级 / 六级时该开关不生效。",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "勾选后在阅读时对应级别的词汇会着色显示",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    levels.levelNames.forEach { (key, name) ->
                        val on = enabledLevels.contains(key)
                        val cnt = levels.levelCounts[key] ?: 0
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val newSet = if (on) enabledLevels - key else enabledLevels + key
                                enabledLevels = newSet
                                settings.enabledLevels = newSet
                            }.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = {
                                    val newSet = if (on) enabledLevels - key else enabledLevels + key
                                    enabledLevels = newSet
                                    settings.enabledLevels = newSet
                                }
                            )
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(levels.colorOf(key)))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("$cnt", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ---------- 翻译引擎 ----------
            SectionTitle("翻译引擎")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "长按句子翻译时，使用这里选中的引擎",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TranslationEngines.selectable.forEach { (id, label) ->
                            val configured = TranslationEngines.registry
                                .firstOrNull { it.id == id }?.isConfigured() == true
                            FilterChip(
                                selected = engineId == id,
                                onClick = {
                                    engineId = id
                                    TranslationEngines.setPreferred(id)
                                },
                                label = { Text(label, fontSize = 12.sp) },
                                leadingIcon = if (configured) {
                                    { Icon(Icons.Filled.Check, null, Modifier.size(14.dp)) }
                                } else null
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    when (engineId) {
                        "free" -> {
                            Text("网易有道（免费） · 免配置", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Hint(
                                "默认引擎，不需要任何 API Key。首选网易有道的免费接口，失败时" +
                                    "自动改用 MyMemory → Google 兜底，保证「装上就能翻译」。" +
                                    "公开接口可能限流，介意可改用下面需要 Key 的引擎。"
                            )
                        }

                        "youdao" -> {
                            Text("网易有道智云 · 文本翻译", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            KeyField("AppKey", "youdao_appkey")
                            KeyField("AppSecret", "youdao_secret")
                            Hint("在 ai.youdao.com 创建「文本翻译」应用后获取，需自行申请 Key")
                        }

                        "baidu" -> {
                            Text("百度翻译开放平台", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            KeyField("AppID", "baidu_appid")
                            KeyField("密钥", "baidu_key")
                            Hint("在 fanyi-api.baidu.com 申请「通用文本翻译」")
                        }

                        "tencent" -> {
                            Text("腾讯云机器翻译 (TMT)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            KeyField("SecretId", "tencent_id")
                            KeyField("SecretKey", "tencent_key")
                            Hint("在 console.cloud.tencent.com 创建访问密钥")
                        }

                        "deepl" -> {
                            Text("DeepL", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            KeyField("API Key", "deepl")
                            Hint("以 :fx 结尾的是免费版 Key")
                        }

                        "openai" -> {
                            Text("OpenAI / 兼容平台", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            KeyField("API Key", "openai_key")
                            KeyField("平台地址（可选）", "openai_url")
                            KeyField("模型名（可选）", "openai_model")
                            Hint("可填 DeepSeek、通义等兼容 OpenAI 协议的平台")
                        }
                    }
                }
            }

            // ---------- 关于 / 版本更新 ----------
            // ---------- 高级（管理员，需密码） ----------
            SectionTitle("高级")
            AdminEntryCard(onOpenAdmin = onOpenAdmin)

            SectionTitle("关于")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("LingReader", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "当前版本 v$versionName（$versionCode）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    when {
                        checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在检查更新…", fontSize = 12.sp)
                        }

                        downloading -> Column {
                            Text("正在下载…", fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = downloadProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${(downloadProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        newRelease != null -> Column {
                            Text(
                                "发现新版本 ${newRelease!!.version}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val notes = newRelease!!.notes
                            if (notes.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    notes,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { downloadAndInstall() }) {
                                    Text("下载并安装", fontSize = 13.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { checkUpdate() }) {
                                    Text("重新检查", fontSize = 12.sp)
                                }
                            }
                        }

                        updateChecked -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已是最新版本",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { checkUpdate() }) {
                                Text("重新检查", fontSize = 12.sp)
                            }
                        }

                        else -> TextButton(
                            onClick = { checkUpdate() },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("检查更新", fontSize = 13.sp) }
                    }

                    updateError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "说明：本 App 为本地阅读器，词典查询完全离线、无次数限制。\n" +
                    "翻译默认走免费的公开接口，无需配置；也可自行填第三方平台 Key，" +
                    "额度与稳定性由所选平台决定。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

/** 输入框下方的小字提示 */
@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 语音引擎单选一行（标题 + 包名小字） */
@Composable
private fun EngineRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 语音引擎资源一行：名称 / 说明 / 体积 / 下载·安装·删除。
 *
 * APK 体积大（50~330MB）且不能静默安装，所以状态只有四种：
 * 未下载 →（下载中）→ 已下载（点「安装」交给系统安装器）。
 */
@Composable
private fun TtsEngineRow(
    res: DictResource,
    status: DictManager.Status,
    sizeOnDisk: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(res.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            res.description,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        when {
            status.downloading -> {
                LinearProgressIndicator(
                    progress = status.progress,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "下载中 ${(status.progress * 100).toInt()}% / ${DictCatalog.formatSize(res.sizeBytes)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) {
                        Text("取消", fontSize = 12.sp)
                    }
                }
            }

            status.installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已下载 · ${DictCatalog.formatSize(sizeOnDisk)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onInstall, contentPadding = PaddingValues(0.dp)) {
                    Text("安装", fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRemove, contentPadding = PaddingValues(0.dp)) {
                    Text("删除", fontSize = 12.sp)
                }
            }

            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DictCatalog.formatSize(res.sizeBytes),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDownload, contentPadding = PaddingValues(0.dp)) {
                    Text("下载", fontSize = 12.sp)
                }
            }
        }
        status.error?.let {
            Text(
                it,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) }
    )
}

/**
 * 管理员入口：点击后输入密码，正确才进入 [com.lreader.ui.admin.AdminScreen]。
 *
 * 面板里能看/删本机各数据库（生词本、书架、设置、书籍、封面、插图缓存），
 * 也能列出并删除云端各用户的数据目录。
 */
@Composable
private fun AdminEntryCard(onOpenAdmin: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    pwd = ""
                    error = null
                    showDialog = true
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("管理员模式", fontSize = 14.sp)
                Text(
                    "查看 / 清理本机数据库，删除云端用户数据",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("进入", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("管理员密码", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("密码", fontSize = 12.sp) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error!!, fontSize = 11.sp) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pwd == ADMIN_PASSWORD) {
                            showDialog = false
                            onOpenAdmin()
                        } else {
                            error = "密码不正确"
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("取消") } }
        )
    }
}

/**
 * 云同步（生词本）：把加密后的生词本同步到用户自己的 GitHub 私有仓库。
 *
 * 密钥由同步码里的口令派生（`SyncCrypto`），云端只有密文；第二台设备粘贴同一个同步码即完成配对。
 */
@Composable
private fun SyncSection(settings: SettingsStore) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var codeDraft by remember { mutableStateOf(settings.syncCode) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showGenerator by remember { mutableStateOf(false) }

    val cfg = remember(codeDraft) { SyncCode.decode(codeDraft) }

    fun runSync(push: Boolean = true) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SyncManager.sync(context, push) } }
            busy = false
            message = r.fold(
                onSuccess = { "同步完成：云端共 ${it.total} 条，本地更新 ${it.applied} 条" },
                onFailure = { "同步失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    Card {
        Column(Modifier.padding(14.dp)) {
            Text(
                "生词本加密同步到自己的 GitHub 私有仓库：云端只存密文，仓库主人也读不到内容。" +
                    "在第一台设备生成同步码，第二台粘贴同一个码即完成配对。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (cfg != null) {
                Text("已配置：${cfg.repoLabel}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "目录 users/${cfg.syncId} · 上次同步 " + fmtSyncTime(settings.lastSyncAt),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "未配置同步码",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = codeDraft,
                onValueChange = { codeDraft = it },
                label = { Text("同步码（LR1.…）", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        settings.syncCode = codeDraft
                        if (SyncCode.decode(codeDraft) == null) {
                            message = "同步码格式无效，请检查是否完整"
                        } else {
                            runSync()
                        }
                    }
                ) { Text(if (busy) "同步中…" else "保存并同步", fontSize = 12.sp) }

                TextButton(
                    onClick = {
                        if (codeDraft.isNotBlank()) {
                            clipboard.setText(AnnotatedString(codeDraft))
                            message = "同步码已复制，粘贴到第二台设备即可"
                        }
                    }
                ) { Text("复制", fontSize = 12.sp) }

                TextButton(onClick = { showGenerator = true }) { Text("生成", fontSize = 12.sp) }

                if (settings.syncCode.isNotBlank()) {
                    TextButton(
                        onClick = {
                            settings.syncCode = ""
                            codeDraft = ""
                            message = "已断开同步（云端数据不会被删除）"
                        }
                    ) { Text("断开", fontSize = 12.sp) }
                }
            }
            SwitchRow("自动同步（进入 App 时）", settings.syncAuto) { settings.syncAuto = it }
            message?.let { Hint(it) }
        }
    }

    if (showGenerator) {
        SyncCodeGeneratorDialog(
            onDismiss = { showGenerator = false },
            onCreated = { code ->
                settings.syncCode = code
                codeDraft = code
                showGenerator = false
                message = "已生成同步码：复制到其它设备粘贴即可"
            }
        )
    }
}

/** 「生成同步码」对话框：填仓库与 token，生成新的同步 id + 加密口令 */
@Composable
private fun SyncCodeGeneratorDialog(
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit
) {
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成同步码", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "1. 在 GitHub 建一个**私有**仓库（例如 lr-sync）\n" +
                        "2. 生成 fine-grained token，权限只给 Contents: Read and write\n" +
                        "3. 填在下面生成同步码，再复制到其它设备",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = owner, onValueChange = { owner = it },
                    label = { Text("GitHub 用户名", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = repo, onValueChange = { repo = it },
                    label = { Text("仓库名", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = branch, onValueChange = { branch = it },
                    label = { Text("分支", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Token（github_pat_…）", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank(),
                onClick = {
                    onCreated(SyncCode.encode(SyncManager.newConfig(owner, repo, branch, token)))
                }
            ) { Text("生成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 上次同步时间（简短格式） */
private fun fmtSyncTime(ms: Long): String =
    if (ms <= 0) "从未同步"
    else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))

@Composable
private fun KeyField(label: String, keyName: String) {
    var value by remember { mutableStateOf(TranslationEngines.getKey(keyName)) }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        trailingIcon = {
            TextButton(onClick = { TranslationEngines.saveKey(keyName, value) }) {
                Text("保存", fontSize = 12.sp)
            }
        }
    )
}

/**
 * 词典资源一行：名称 / 说明 / 状态 / 操作。
 *
 * 三种状态：**未下载**（下载按钮）· **下载中**（进度条 + 取消）· **已安装**（体积 + 词条数 + 删除）。
 * 未安装时把真实下载地址以小字显示，方便用户排查「404 = 资源没发布 / 地址不对」。
 */
@Composable
private fun DictResourceRow(
    res: DictResource,
    status: DictManager.Status,
    sizeOnDisk: Long,
    entryCount: Int,
    url: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(res.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            res.description,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        when {
            status.downloading -> {
                LinearProgressIndicator(
                    progress = status.progress,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "下载中 ${(status.progress * 100).toInt()}%（请勿退出 App）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) {
                        Text("取消", fontSize = 12.sp)
                    }
                }
            }

            status.installed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append("已安装 · ")
                            append(DictCatalog.formatSize(sizeOnDisk))
                            if (entryCount > 0) append(" · $entryCount 词条")
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (res.removable) {
                        TextButton(onClick = onRemove, contentPadding = PaddingValues(0.dp)) {
                            Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text("内置", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "未下载 · 约 ${DictCatalog.formatSize(res.sizeBytes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onDownload,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("下载", fontSize = 13.sp)
                    }
                }
            }
        }

        status.error?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (!status.installed && !status.downloading) {
            Spacer(Modifier.height(2.dp))
            Text(
                url,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 系统语音设置页。`android.provider.Settings.ACTION_TTS_SETTINGS` 在本工程 android.jar 里解析不到，用字面量 */
private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

/** 兜底跳系统设置主页 */
private const val ACTION_SYSTEM_SETTINGS = "android.settings.SETTINGS"
