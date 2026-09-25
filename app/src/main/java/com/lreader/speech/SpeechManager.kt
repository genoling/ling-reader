package com.lreader.speech

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 单词发音。**首选系统 TTS（离线、无次数限制）**，引擎不可用时自动改用在线发音兜底。
 *
 * 进程级单例（[get]）：四个页面共用一份引擎，避免「每进一个页面就 init / shutdown 一次」，
 * 这是「首次点击单词不发音」「背单词页不发音」的主因之一。
 *
 * 修掉的历史问题：
 *  1. 设备没有 TTS 引擎时彻底没声音（MuMu 等模拟器 `tts_default_synth=null`，onInit 回调 status=-1）。
 *     现在自动切到在线发音（有道 dictvoice，mp3 缓存到 cacheDir），并回调 UI 展示原因；
 *  2. 引擎初始化失败后**每次点击都重建一个 TextToSpeech 再失败一遍**（日志刷屏），
 *     现在失败后进入 [RETRY_COOLDOWN_MS] 冷却期，且不再无谓阻塞；
 *  3. 每次 speak 前无脑 `stop()`，未绑定引擎时会抛 "stop failed: not bound to TTS engine"，
 *     且 stop→speak 连用可能把刚排上的语音一起打断，现在只在 [stop] 里显式停止；
 *  4. 待播队列会按顺序把连续点击的单词一个个读完（连读），现在只保留**最后一个**；
 *  5. 初始化被阅读页的 `dict.ensureReady()`（首次解压 115MB）拖后才开始，
 *     现在由 [com.lreader.LRreaderApp] 在进程启动时就 init，点词即可出声。
 *
 * 另注意：Android 11+ 必须在 AndroidManifest 里声明
 * `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`，
 * 否则引擎根本不可见，会表现为「完全没声音」（已在 Manifest 中声明）。
 */
class SpeechManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    enum class Accent(val locale: Locale, val label: String, val youdaoType: Int) {
        US(Locale.US, "美音", 2),
        UK(Locale.UK, "英音", 1)
    }

    /** 引擎就绪状态，供设置页自检展示 */
    enum class Status { IDLE, INITIALIZING, READY, NO_ENGINE, NO_ENGLISH, ERROR }

    /** 实际发声通道，供设置页自检展示 */
    enum class Mode(val label: String) {
        SYSTEM("系统语音引擎（离线）"),
        ONLINE("在线发音（兜底）"),
        SYSTEM_PENDING("正在初始化…"),
        NONE("不可用")
    }

    companion object {
        private const val TAG = "SpeechManager"

        /** 引擎初始化失败后的重试冷却，避免每次点击都重建引擎 */
        private const val RETRY_COOLDOWN_MS = 15_000L

        /** 有道免费发音接口（与免费翻译同源，无需 Key） */
        private const val ONLINE_BASE = "https://dict.youdao.com/dictvoice?audio="
        private const val ONLINE_DIR = "tts_cache"

        @Volatile
        private var inst: SpeechManager? = null

        /** 进程级单例：四个页面共用一份，反复 init/shutdown 会导致「第一次点没声音」 */
        fun get(context: Context): SpeechManager =
            inst ?: synchronized(this) {
                inst ?: SpeechManager(context.applicationContext).also { inst = it }
            }
    }

    var status: Status = Status.IDLE
        private set

    /** 当前实际发声通道 */
    var mode: Mode = Mode.NONE
        private set

    /** 实际生效的语言（可能因引擎限制而与用户选择不同） */
    var resolvedLocale: Locale? = null
        private set

    /** 系统默认 TTS 引擎名，设置页展示用 */
    var engineName: String? = null
        private set

    /** 状态变化回调（设置页自检用），注意在页面 dispose 时置空 */
    var onStatusChange: ((Status, Mode) -> Unit)? = null

    /** 在线兜底也失败（一般是断网）时回调一次，供 UI 给用户一个明确提示 */
    var onOnlineError: ((String) -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var initialized = false
    private var initializing = false
    private var lastFailAt = 0L
    private var lastFailReason: String? = null

    /** 引擎就绪前只保留最后一个待播词（连续点词时不该排队连读） */
    private var pendingWord: String? = null

    private val main = Handler(Looper.getMainLooper())

    /** 在线兜底下载线程（单线程，天然串行） */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "speech-online").apply { isDaemon = true }
    }

    private var player: MediaPlayer? = null

    /**
     * 指定要使用的系统 TTS 引擎包名；null / 空串 = 跟随系统默认引擎。
     *
     * 手机与平板音色不同，正是因为各自装/默认了不同的引擎。赋值会**重新绑定**引擎，
     * 由设置页「语音引擎」列表调用（见 [engineItems]）。
     */
    var enginePackage: String? = null
        set(value) {
            val v = value?.takeIf { it.isNotBlank() }
            if (field == v) return
            field = v
            rebind()
        }

    /**
     * true = 不依赖系统引擎，**始终走在线发音**（有道 dictvoice）。
     * 适合设备没装引擎、或想让手机/平板音色完全一致的用户；代价是每次发音都要联网。
     * 切换后会重新绑定（关掉时要把系统引擎重新建起来）。
     */
    var preferOnline: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebind()
        }

    var accent: Accent = Accent.US
        set(value) {
            field = value
            if (initialized) applyLanguage(value.locale)
        }

    /** 语速 0.5 ~ 2.0 */
    var rate: Float = 0.9f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setSpeechRate(field)
        }

    var enabled: Boolean = true

    /** 触发初始化（可重复调用；失败后在冷却期内不会重复重建引擎） */
    fun init() {
        if (initialized || initializing) return
        if (status == Status.NO_ENGINE && System.currentTimeMillis() - lastFailAt < RETRY_COOLDOWN_MS) {
            // 冷却期内不再重复 new TextToSpeech（原来每点一次就失败刷一条日志）
            mode = Mode.ONLINE
            notifyStatus()
            return
        }
        if (preferOnline) {
            // 用户选择「始终在线发音」：不建引擎，直接走在线通道
            status = Status.READY
            mode = Mode.ONLINE
            notifyStatus()
            flushPending()
            return
        }
        initializing = true
        status = Status.INITIALIZING
        mode = Mode.SYSTEM_PENDING
        notifyStatus()

        val wanted = enginePackage
        val listener = TextToSpeech.OnInitListener { st ->
            val engine = tts
            when {
                // 指定引擎不可用（被卸载 / 包名过期）：清掉选择并回退系统默认，重试一次
                st != TextToSpeech.SUCCESS && wanted != null -> {
                    Log.w(TAG, "指定语音引擎不可用：$wanted → 回退系统默认引擎")
                    runCatching { engine?.shutdown() }
                    tts = null
                    initialized = false
                    initializing = false
                    lastFailAt = System.currentTimeMillis()
                    lastFailReason = "指定的语音引擎不可用"
                    main.post { enginePackage = null }   // setter → rebind() → init()
                    notifyStatus()
                }

                // 设备没装 TTS 引擎（模拟器常见）或引擎异常：切在线兜底，不要静默失败
                st != TextToSpeech.SUCCESS || engine == null -> {
                    Log.e(
                        TAG,
                        "TTS 初始化失败, status=$st（设备可能未安装语音引擎；" +
                            "Android 11+ 还需 Manifest 声明 TTS_SERVICE queries）→ 改用在线发音兜底"
                    )
                    runCatching { engine?.shutdown() }
                    tts = null
                    initialized = false
                    initializing = false
                    status = Status.NO_ENGINE
                    lastFailAt = System.currentTimeMillis()
                    lastFailReason = "未安装系统语音引擎"
                    mode = Mode.ONLINE
                    notifyStatus()
                    flushPending()
                }

                else -> {
                    engineName = systemDefaultEngine()
                    engine.setSpeechRate(rate)
                    engine.setPitch(1.0f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {}

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "TTS onError id=$utteranceId")
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.e(TAG, "TTS onError id=$utteranceId code=$errorCode")
                        }
                    })
                    initialized = true
                    initializing = false
                    applyLanguage(accent.locale)
                    Log.i(TAG, "TTS 就绪 engine=${engineLabel()} locale=$resolvedLocale status=$status")
                    notifyStatus()
                    flushPending()
                }
            }
        }

        tts = if (wanted.isNullOrBlank()) {
            TextToSpeech(appContext, listener)
        } else {
            TextToSpeech(appContext, listener, wanted)
        }
    }

    /** 切换引擎：释放旧引擎后重新初始化（设置页切换引擎时调用） */
    private fun rebind() {
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
        initializing = false
        status = Status.IDLE
        mode = Mode.NONE
        lastFailAt = 0L
        lastFailReason = null
        init()
    }

    /** 系统里已安装的语音引擎（供设置页选择） */
    data class EngineItem(val pkg: String, val label: String, val isDefault: Boolean)

    /** 系统当前默认的引擎包名（读 secure 设置 `tts_default_synth`，不依赖 TextToSpeech 实例） */
    private fun systemDefaultEngine(): String? = runCatching {
        android.provider.Settings.Secure.getString(
            appContext.contentResolver, "tts_default_synth"
        )
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 设备上已安装的 TTS 引擎（列出响应 `android.intent.action.TTS_SERVICE` 的服务）。
     *
     * 这里不用 `TextToSpeech.getEngines()`：该 API 需要先持有实例、且在部分 SDK 上取不到。
     * Manifest 已声明 `<queries>` 中的 TTS_SERVICE，Android 11+ 才查得到这些包。
     * 列表为空通常意味着系统里没装任何引擎（模拟器常见），此时只能用在线发音。
     */
    fun engineItems(): List<EngineItem> {
        val def = systemDefaultEngine()
        val intent = android.content.Intent("android.intent.action.TTS_SERVICE")
        val services = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.queryIntentServices(
                    intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.queryIntentServices(intent, 0)
            }
        }.getOrDefault(emptyList())
        return services.mapNotNull { si ->
            val info = si.serviceInfo ?: return@mapNotNull null
            EngineItem(
                pkg = info.packageName,
                label = runCatching { info.loadLabel(appContext.packageManager).toString() }
                    .getOrNull().orEmpty().ifBlank { info.packageName },
                isDefault = info.packageName == def
            )
        }.distinctBy { it.pkg }
    }

    /** 当前生效的引擎显示名（用户指定优先，其次系统默认） */
    fun engineLabel(): String {
        val pkg = enginePackage ?: systemDefaultEngine()
        if (pkg.isNullOrBlank()) return engineName ?: "系统默认"
        return engineItems().firstOrNull { it.pkg == pkg }?.label ?: pkg
    }

    /**
     * 逐级回退设置语言：优先用户选择 → 另一种口音 → 任意英语 → 不修改（用引擎默认）。
     * 记录最终结果到 [resolvedLocale]，无法支持英语时置 [Status.NO_ENGLISH]。
     */
    private fun applyLanguage(preferred: Locale) {
        val engine = tts ?: return
        val candidates = LinkedHashSet<Locale>().apply {
            add(preferred)
            add(if (preferred == Locale.UK) Locale.US else Locale.UK)
            add(Locale.ENGLISH)
        }
        for (loc in candidates) {
            val r = runCatching { engine.setLanguage(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) continue
            resolvedLocale = loc
            status = Status.READY
            mode = Mode.SYSTEM
            return
        }
        // 所有英语变体都不支持：再试一下引擎是否至少认英语
        val hasEnglish = runCatching {
            engine.availableLanguages.any { it.language.equals("en", ignoreCase = true) }
        }.getOrDefault(false)
        if (hasEnglish) {
            status = Status.READY
            mode = Mode.SYSTEM
        } else {
            status = Status.NO_ENGLISH
            mode = Mode.ONLINE
            lastFailAt = System.currentTimeMillis()
            lastFailReason = "系统语音引擎不支持英语"
        }
        Log.e(TAG, "引擎不支持英语，availableLanguages=${runCatching { engine.availableLanguages }.getOrNull()}")
    }

    /** 引擎可用语言（诊断用） */
    fun availableLanguages(): Set<Locale> =
        runCatching { tts?.availableLanguages ?: emptySet<Locale>() }.getOrDefault(emptySet())

    fun availableVoices(): Set<Voice> =
        runCatching { tts?.voices ?: emptySet<Voice>() }.getOrDefault(emptySet())

    /** 自检摘要，设置页直接展示 */
    fun statusText(): String = when {
        preferOnline -> "在线发音（已选「始终在线发音」）"
        mode == Mode.SYSTEM -> "系统语音引擎：${engineLabel()}（离线可用）"
        mode == Mode.SYSTEM_PENDING -> "正在初始化系统语音引擎…"
        mode == Mode.ONLINE -> {
            val why = lastFailReason ?: "系统语音引擎不可用"
            "已切换在线发音（$why）"
        }

        else -> "发音不可用"
    }

    /**
     * 朗读文本。引擎尚未就绪时会记住该词，就绪后自动播放。
     * @return true 表示已立即发声（系统 TTS 或在线兜底），false 表示已排队等待引擎就绪
     */
    fun speak(text: String): Boolean {
        if (!enabled) return false
        val clean = text.trim()
        if (clean.isEmpty()) return false

        if (preferOnline) {
            // 设置里选了「始终在线发音」：跳过系统引擎，保证跨设备音色一致
            speakOnline(clean)
            return true
        }

        when (status) {
            Status.READY -> {
                doSpeak(clean)
                return true
            }

            Status.NO_ENGINE, Status.NO_ENGLISH -> {
                // 系统没引擎：直接走在线兜底，保证「点了就有声音」
                speakOnline(clean)
                return true
            }

            else -> {
                pendingWord = clean
                init()
                return false
            }
        }
    }

    /** 引擎就绪后把排队的词补播（只播最后一个） */
    private fun flushPending() {
        val w = pendingWord ?: return
        pendingWord = null
        when (status) {
            Status.READY -> doSpeak(w)
            Status.NO_ENGINE, Status.NO_ENGLISH -> speakOnline(w)
            else -> Unit
        }
    }

    private fun doSpeak(text: String) {
        val engine = tts ?: return
        val ok = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lr_${System.nanoTime()}")
        }.getOrElse {
            Log.e(TAG, "speak 异常", it)
            TextToSpeech.ERROR
        }
        if (ok == TextToSpeech.ERROR) {
            // 引擎表面就绪但实际报错（部分模拟器如此），别让用户干等：转在线兜底
            Log.e(TAG, "speak() 返回 ERROR，转在线发音: $text")
            speakOnline(text)
        }
    }

    // ------------------------------------------------------------------
    // 在线发音兜底
    // ------------------------------------------------------------------

    private fun speakOnline(text: String) {
        mode = Mode.ONLINE
        val dir = File(appContext.cacheDir, ONLINE_DIR)
        val key = Integer.toHexString("${text.lowercase()}|${accent.name}".hashCode())
        val file = File(dir, "$key.mp3")

        worker.execute {
            if (!file.exists() || file.length() < 512) {
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "无法创建发音缓存目录")
                    main.post { onOnlineError?.invoke(text) }
                    return@execute
                }
                if (!download(text, file)) {
                    main.post { onOnlineError?.invoke(text) }
                    return@execute
                }
            }
            main.post { play(file) }
        }
    }

    private fun download(text: String, file: File): Boolean {
        val url = ONLINE_BASE + URLEncoder.encode(text, "UTF-8") + "&type=" + accent.youdaoType
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) LingReader")
            }
            val code = conn.responseCode
            val ok = code == 200 && conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
                file.length() >= 512
            }
            conn.disconnect()
            if (!ok) {
                Log.e(TAG, "在线发音下载失败 code=$code url=$url")
                file.delete()
            }
            ok
        }.getOrElse {
            Log.e(TAG, "在线发音下载异常 url=$url", it)
            runCatching { file.delete() }
            false
        }
    }

    private fun play(file: File) {
        runCatching { player?.release() }
        player = null
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    mp.release()
                    if (player === mp) player = null
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer onError what=$what extra=$extra")
                    mp.release()
                    if (player === mp) player = null
                    true
                }
                prepare()
                start()
            }
            Log.i(TAG, "在线发音播放 ${file.name}")
        }.onFailure {
            Log.e(TAG, "在线发音播放失败", it)
            runCatching { player?.release() }
            player = null
            onOnlineError?.invoke(file.nameWithoutExtension)
        }
    }

    /** 立即停止当前发音（含在线播放） */
    fun stop() {
        runCatching { tts?.stop() }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        pendingWord = null
    }

    /**
     * 释放引擎（进程退出时用）。
     * 注意：页面**不要**在 onDispose 里调用——实例是单例，随便 release 会导致回到页面后没声音。
     */
    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
        initializing = false
        resolvedLocale = null
        engineName = null
        onStatusChange = null
        onOnlineError = null
        status = Status.IDLE
        mode = Mode.NONE
    }

    private fun notifyStatus() {
        val s = status
        val m = mode
        main.post { onStatusChange?.invoke(s, m) }
    }
}
