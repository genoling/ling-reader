package com.lreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 应用设置（JSON 持久化）。
 *
 * **进程级单例**（见 [get]）：写盘是「整份 JSON 覆盖」，若各页面各持一份内存副本，
 * 后写的会把别人刚改的字段（比如更新检查时间戳）覆盖回旧值。
 */
class SettingsStore private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context.applicationContext).also { instance = it }
        }
    }

    private val file = File(context.filesDir, "settings.json")
    private val obj = JSONObject()

    init {
        load()
    }

    private fun load() {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText(Charsets.UTF_8))
                o.keys().forEach { k -> obj.put(k, o.get(k)) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persist() {
        try {
            file.writeText(obj.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---- 发音 ----
    var autoSpeak: Boolean
        get() = obj.optBoolean("autoSpeak", true)
        set(v) { obj.put("autoSpeak", v); persist() }

    var accent: String   // "US" / "UK"
        get() = obj.optString("accent", "US")
        set(v) { obj.put("accent", v); persist() }

    var speechRate: Float
        get() = obj.optDouble("speechRate", 0.9).toFloat()
        set(v) { obj.put("speechRate", v.toDouble()); persist() }

    /**
     * 指定使用的系统语音引擎包名（空串 = 跟随系统默认）。
     * 手机与平板音色不同就是各自引擎不同，选同一个引擎即可统一。
     */
    var ttsEngine: String
        get() = obj.optString("ttsEngine", "")
        set(v) { obj.put("ttsEngine", v.trim()); persist() }

    /** 背单词：是否乱序（**默认开启**；UI 不再暴露开关） */
    var reviewShuffle: Boolean
        get() = obj.optBoolean("reviewShuffle", true)
        set(v) { obj.put("reviewShuffle", v); persist() }

    /** 始终使用在线发音：跳过系统引擎，音色跨设备一致，但每次发音需联网 */
    var preferOnlineSpeech: Boolean
        get() = obj.optBoolean("preferOnlineSpeech", false)
        set(v) { obj.put("preferOnlineSpeech", v); persist() }

    // ---- 阅读 ----
    var fontSize: Float
        get() = obj.optDouble("fontSize", 17.0).toFloat()
        set(v) { obj.put("fontSize", v.toDouble()); persist() }

    var lineHeight: Float
        get() = obj.optDouble("lineHeight", 28.0).toFloat()
        set(v) { obj.put("lineHeight", v.toDouble()); persist() }

    /** 界面配色方案索引（见 ThemePreset） */
    var themePreset: Int
        get() = obj.optInt("themePreset", 0)
        set(v) { obj.put("themePreset", v); persist() }

    /** 阅读页配色：0 纸白 / 1 米黄 / 2 夜间 */
    var readingTheme: Int
        get() = obj.optInt("readingTheme", 1)
        set(v) { obj.put("readingTheme", v); persist() }

    // ---- 分级高亮 ----
    /** 已启用的级别集合 */
    var enabledLevels: Set<String>
        get() {
            val arr = obj.optJSONArray("enabledLevels") ?: return emptySet()
            val set = LinkedHashSet<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            return set
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            obj.put("enabledLevels", arr)
            persist()
        }

    /**
     * 忽略「基础词」：勾选雅思/托福等高阶级别时，
     * 不再高亮同时属于高中词表的 be / make / even 这类基础词。默认开启。
     */
    var skipBasicWords: Boolean
        get() = obj.optBoolean("skipBasicWords", true)
        set(v) { obj.put("skipBasicWords", v); persist() }

    /** 是否显示已加入生词本的词（高亮成另一种样式） */
    var highlightVocab: Boolean
        get() = obj.optBoolean("highlightVocab", true)
        set(v) { obj.put("highlightVocab", v); persist() }

    // ---- 翻译目标语言 ----
    var targetLang: String
        get() = obj.optString("targetLang", "zh")
        set(v) { obj.put("targetLang", v); persist() }

    // ---- 词典下载源 ----
    /**
     * 词典资源下载源前缀（末尾 `/` 可省略）。
     * 空串 = 用内置默认值（见 [com.lreader.dict.DictCatalog.DEFAULT_SOURCE]，指向本仓库 Release）。
     * 留出这个开关是为了：镜像加速、自建服务器、离线局域网分发。
     */
    var dictSourceBase: String
        get() = obj.optString("dictSourceBase", "")
        set(v) { obj.put("dictSourceBase", v.trim()); persist() }

    // ---- 云同步（生词本，见 sync 包）----
    /** 同步码：内含仓库、token、同步 id 与加密口令；空串 = 未配置 */
    var syncCode: String
        get() = obj.optString("syncCode", "")
        set(v) { obj.put("syncCode", v.trim()); persist() }

    /** 进入 App 时自动同步一次 */
    var syncAuto: Boolean
        get() = obj.optBoolean("syncAuto", true)
        set(v) { obj.put("syncAuto", v); persist() }

    /** 上次同步成功时间（毫秒，0 = 从未同步） */
    var lastSyncAt: Long
        get() = obj.optLong("lastSyncAt", 0L)
        set(v) { obj.put("lastSyncAt", v); persist() }

    // ---- 有道生词本（见 data/YoudaoWordbook.kt）----
    /**
     * 有道网页登录后的 Cookie：**属于每个用户自己的账号**（换账号 = 换 Cookie）。
     * 空串 = 未登录。由设置页的 WebView 登录后自动写入。
     */
    var youdaoCookie: String
        get() = obj.optString("youdaoCookie", "")
        set(v) { obj.put("youdaoCookie", v.trim()); persist() }

    /** 有道账号昵称 / 标识（仅界面显示用；从登录页读取，可能为空） */
    var youdaoAccount: String
        get() = obj.optString("youdaoAccount", "")
        set(v) { obj.put("youdaoAccount", v.trim()); persist() }

    /** 上次同步到有道的时间（毫秒，0 = 从未同步） */
    var lastYoudaoAt: Long
        get() = obj.optLong("lastYoudaoAt", 0L)
        set(v) { obj.put("lastYoudaoAt", v); persist() }

    /**
     * 同步到有道的**目标单词本** bookId；空串 或 [YoudaoWordbook.DEFAULT_BOOK_ID]("0") = 默认「无标签」本。
     *
     * 词默认只会进「无标签」，而部分客户端（如有道翻译电脑版）只列具名单词本 —— 那样词会"看不到"，
     * 所以允许指定一个具名单词本，同步后自动把词归类过去。
     */
    var youdaoBookId: String
        get() = obj.optString("youdaoBookId", "")
        set(v) { obj.put("youdaoBookId", v.trim()); persist() }

    /** 目标单词本的显示名（仅界面用，避免每次都要拉接口） */
    var youdaoBookName: String
        get() = obj.optString("youdaoBookName", "")
        set(v) { obj.put("youdaoBookName", v.trim()); persist() }

    // ---- 应用更新（逻辑见 data/UpdateController.kt）----
    /** 启动时自动检查新版本；「以后不提醒」会关掉它，可在设置页重新打开 */
    var autoCheckEnabled: Boolean
        get() = obj.optBoolean("autoCheckEnabled", true)
        set(v) { obj.put("autoCheckEnabled", v); persist() }

    /** 上次**成功**检查更新的时间（毫秒，0 = 从未检查）——24 小时节流就看它 */
    var lastCheckAt: Long
        get() = obj.optLong("lastCheckAt", 0L)
        set(v) { obj.put("lastCheckAt", v); persist() }

    /** 用户点过「本次不更新」的版本号：只忽略这一个版本，更高的版本仍会提示 */
    var ignoredVersion: String
        get() = obj.optString("ignoredVersion", "")
        set(v) { obj.put("ignoredVersion", v.trim()); persist() }

    /** 首次启动时间（毫秒）：新用户首日不弹更新，先让他熟悉产品 */
    var firstInstallAt: Long
        get() = obj.optLong("firstInstallAt", 0L)
        set(v) { obj.put("firstInstallAt", v); persist() }

    /** 取首次启动时间；还没有记录时以当前时刻为准并落盘 */
    fun ensureFirstInstallAt(now: Long = System.currentTimeMillis()): Long {
        val v = firstInstallAt
        if (v > 0L) return v
        firstInstallAt = now
        return now
    }
}
