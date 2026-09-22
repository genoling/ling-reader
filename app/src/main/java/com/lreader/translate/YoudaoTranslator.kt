package com.lreader.translate

import com.lreader.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 网易有道智云 · 文本翻译（官方接口）。
 *
 * 文档：https://ai.youdao.com/DOCSIRMA/html/trans/api/wbfy/index.html
 * 端点：POST https://openapi.youdao.com/api
 * 鉴权：signType=v3，sign = SHA256(appKey + truncate(q) + salt + curtime + appSecret)
 *
 * 需要用户在「设置 → 翻译引擎」里填 AppKey 与 AppSecret。
 * 单独成文件是为了不干扰 [Translators] 里已有的实现。
 */
class YoudaoTranslator(
    private val appKey: String,
    private val appSecret: String
) : TranslationEngine {

    override val id = "youdao"
    override val displayName = "有道智云（需 Key）"

    override fun isConfigured(): Boolean = appKey.isNotBlank() && appSecret.isNotBlank()

    override suspend fun translate(
        text: String,
        from: String,
        to: String
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext fail(text, "未填写有道 AppKey / AppSecret")
        }
        try {
            val salt = UUID.randomUUID().toString()
            val curtime = (System.currentTimeMillis() / 1000).toString()
            val sign = sha256(appKey + truncate(text) + salt + curtime + appSecret)

            val body = FormBody.Builder()
                .add("q", text)
                .add("from", mapLang(from))
                .add("to", mapLang(to))
                .add("appKey", appKey)
                .add("salt", salt)
                .add("sign", sign)
                .add("signType", "v3")
                .add("curtime", curtime)
                .build()

            val req = Request.Builder()
                .url(ENDPOINT)
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext fail(text, "HTTP ${resp.code}: ${raw.take(120)}")
                }
                val json = JSONObject(raw)
                val code = json.optString("errorCode", "-1")
                if (code != "0") {
                    return@withContext fail(text, "有道错误 $code：${ERRORS[code] ?: "未知错误"}")
                }
                val translated = json.optJSONArray("translation")?.optString(0).orEmpty()
                if (translated.isBlank()) {
                    return@withContext fail(text, "有道返回结果为空")
                }
                TranslationResult(
                    sourceText = text,
                    translatedText = translated,
                    engine = displayName,
                    success = true
                )
            }
        } catch (e: Exception) {
            fail(text, e.message ?: "网络请求失败")
        }
    }

    private fun fail(src: String, msg: String) = TranslationResult(
        sourceText = src,
        translatedText = "",
        engine = displayName,
        success = false,
        errorMessage = msg
    )

    /** 有道签名规则：长度 > 20 时取「前 10 + 长度 + 后 10」 */
    private fun truncate(q: String): String {
        val len = q.length
        return if (len <= 20) q else q.substring(0, 10) + len + q.substring(len - 10)
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 有道用 zh-CHS / zh-CHT，本 App 内部用 zh */
    private fun mapLang(lang: String): String = when (lang.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-CHS"
        "zh-tw", "zh-hant" -> "zh-CHT"
        "auto", "" -> "auto"
        else -> lang
    }

    companion object {
        private const val ENDPOINT = "https://openapi.youdao.com/api"

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        private val ERRORS = mapOf(
            "101" to "缺少必填参数",
            "102" to "不支持的语言类型",
            "103" to "翻译文本过长",
            "108" to "应用 ID 无效",
            "110" to "无相关服务的有效实例",
            "111" to "开发者账号无效",
            "112" to "请求服务无效",
            "202" to "签名检验失败（检查 AppSecret）",
            "203" to "访问 IP 不在可访问列表",
            "401" to "账户已欠费",
            "411" to "访问频率受限"
        )
    }
}
