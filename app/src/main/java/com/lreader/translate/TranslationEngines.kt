package com.lreader.translate

import android.content.Context
import com.lreader.model.TranslationResult
import java.io.File

/**
 * 翻译引擎注册中心 + API Key 持久化。
 */
object TranslationEngines {

    /** 可在设置里选择的引擎（id → 显示名），顺序即推荐顺序 */
    val selectable = listOf(
        "free" to "网易有道（免费）",
        "youdao" to "有道智云（Key）",
        "baidu" to "百度翻译",
        "tencent" to "腾讯云",
        "deepl" to "DeepL",
        "openai" to "AI 大模型"
    )

    /**
     * 默认引擎 = 网易有道（免费在线接口）：
     * 免 Key、装上就能翻译，首选端点就是网易有道，失败才依次换 MyMemory / Google。
     * 用户若自己申请了有道智云的 AppKey，可在设置里切到「有道智云（Key）」。
     */
    private const val DEFAULT_ENGINE = "free"
    private const val KEY_ENGINE = "engine"

    val registry: MutableList<TranslationEngine> = mutableListOf()

    private var keyStore: KeyStore? = null

    /** 用户在设置里选中的引擎 id（默认网易有道） */
    var preferredEngine: String = DEFAULT_ENGINE
        private set

    fun init(context: Context) {
        keyStore = KeyStore(context)
        preferredEngine = keyStore?.get(KEY_ENGINE).orEmpty().ifBlank { DEFAULT_ENGINE }
        reload()
    }

    /** 切换首选引擎（持久化到 translate_keys.json） */
    fun setPreferred(id: String) {
        preferredEngine = id
        keyStore?.put(KEY_ENGINE, id)
        reload()
    }

    /** 读取最新 Key 后重建引擎实例 */
    fun reload() {
        val ks = keyStore ?: return
        registry.clear()
        // 免费在线接口放最前：免 Key 且永远可用，其他引擎没配 Key 时兜底到它
        registry.add(FreeWebTranslator())
        registry.add(YoudaoTranslator(ks.get("youdao_appkey"), ks.get("youdao_secret")))
        registry.add(BaiduTranslator(ks.get("baidu_appid"), ks.get("baidu_key")))
        registry.add(TencentTranslator(ks.get("tencent_id"), ks.get("tencent_key")))
        registry.add(DeepLTranslator(ks.get("deepl")))
        registry.add(
            OpenAITranslator(
                apiKey = ks.get("openai_key"),
                baseUrl = ks.get("openai_url").ifBlank { "https://api.openai.com" },
                model = ks.get("openai_model").ifBlank { "gpt-3.5-turbo" }
            )
        )
    }

    fun saveKey(name: String, value: String) {
        keyStore?.put(name, value)
        reload()
    }

    fun getKey(name: String): String = keyStore?.get(name) ?: ""

    /** 已配置可用的引擎 */
    fun available(): List<TranslationEngine> = registry.filter { it.isConfigured() }

    fun hasEngine(): Boolean = available().isNotEmpty()

    /** 当前生效的引擎：优先生效用户在设置里选中的那个 */
    fun current(): TranslationEngine? =
        registry.firstOrNull { it.id == preferredEngine && it.isConfigured() }

    /** 译文内存缓存（LRU，最多 128 句）：翻来覆去复习同一张卡不必重复联网 */
    private const val CACHE_MAX = 128
    private val cache = object : LinkedHashMap<String, TranslationResult>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TranslationResult>?
        ) = size > CACHE_MAX
    }

    /**
     * 句子翻译。
     * 先用用户在设置里选中的引擎；没配置则退回任意一个已配置的引擎
     * （默认的免 Key 免费在线引擎永远可用，所以正常情况下一定有引擎），
     * 并在返回结果里注明实际使用的引擎，避免「以为在用有道、其实没生效」。
     *
     * 成功的结果会进内存缓存，重复翻译同一句直接命中，不再联网。
     */
    suspend fun translate(
        text: String,
        from: String = "en",
        to: String = "zh"
    ): TranslationResult {
        val key = "$from|$to|$text"
        synchronized(cache) { cache[key] }?.let { return it }

        val engine = current() ?: available().firstOrNull()
            ?: return TranslationResult(
                sourceText = text,
                translatedText = "",
                engine = "none",
                success = false,
                errorMessage = "未配置翻译引擎，请在「设置 → 翻译引擎」中选择并填写 API Key"
            )
        val result = engine.translate(text, from, to)
        if (result.success && result.translatedText.isNotBlank()) {
            synchronized(cache) { cache[key] = result }
        }
        return result
    }

    /** 清空译文缓存（切换引擎 / 目标语言后可调） */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private class KeyStore(context: Context) {
        private val file = File(context.filesDir, "translate_keys.json")
        private val map = HashMap<String, String>()

        init {
            load()
        }

        fun get(name: String): String = map[name] ?: ""

        fun put(name: String, value: String) {
            map[name] = value
            persist()
        }

        private fun load() {
            try {
                if (file.exists()) {
                    val o = org.json.JSONObject(file.readText(Charsets.UTF_8))
                    o.keys().forEach { k -> map[k] = o.optString(k) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun persist() {
            try {
                val o = org.json.JSONObject()
                map.forEach { (k, v) -> o.put(k, v) }
                file.writeText(o.toString(2), Charsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
