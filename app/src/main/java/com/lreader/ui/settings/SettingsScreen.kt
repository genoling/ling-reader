package com.lreader.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lreader.data.SettingsStore
import com.lreader.data.VocabRepository
import com.lreader.dict.DictCatalog
import com.lreader.dict.DictDatabase
import com.lreader.dict.DictManager
import com.lreader.dict.DictResource
import com.lreader.dict.LevelDictionary
import com.lreader.speech.SpeechManager
import com.lreader.translate.TranslationEngines
import com.lreader.ui.theme.AppThemeState
import com.lreader.ui.theme.ThemePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val dict = remember { DictDatabase(context) }
    val levels = remember { LevelDictionary(context) }
    val settings = remember { SettingsStore(context) }
    val vocab = remember { VocabRepository(context) }

    // 词典资源管理（进程级单例：安装状态与下载进度跨页面共享）
    val dictMgr = remember { DictManager.get(context) }

    var dictCount by remember { mutableStateOf(0) }
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

    // 词典安装状态变化（下载完成 / 被删除）后重新统计词条数
    LaunchedEffect(dictVersion) {
        withContext(Dispatchers.IO) {
            dictCount = if (dict.ensureReady()) dict.entryCount else 0
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) }
    )
}

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
