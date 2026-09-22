package com.lreader.translate

import com.lreader.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val http: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

private val JSON = "application/json; charset=utf-8".toMediaType()

// =====================================================================
// DeepL
// =====================================================================
class DeepLTranslator(private val apiKey: String) : TranslationEngine {
    override val id = "deepl"
    override val displayName = "DeepL"
    override fun isConfigured() = apiKey.isNotBlank()

    override suspend fun translate(text: String, from: String, to: String) =
        withContext(Dispatchers.IO) {
            try {
                // 免费版端点含 :fx
                val host = if (apiKey.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
                val body = FormBody.Builder()
                    .add("text", text)
                    .add("target_lang", to.uppercase())
                    .apply { if (from != "auto") add("source_lang", from.uppercase()) }
                    .build()
                val req = Request.Builder()
                    .url("https://$host/v2/translate")
                    .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
                    .post(body)
                    .build()
                val resp = http.newCall(req).execute()
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext fail(text, displayName, "HTTP ${resp.code}: $str")
                }
                val arr = JSONObject(str).getJSONArray("translations")
                val out = arr.getJSONObject(0).getString("text")
                ok(text, out, displayName)
            } catch (e: Exception) {
                fail(text, displayName, e.message)
            }
        }
}

// =====================================================================
// 百度翻译
// =====================================================================
class BaiduTranslator(
    private val appId: String,
    private val secretKey: String
) : TranslationEngine {
    override val id = "baidu"
    override val displayName = "百度翻译"
    override fun isConfigured() = appId.isNotBlank() && secretKey.isNotBlank()

    override suspend fun translate(text: String, from: String, to: String) =
        withContext(Dispatchers.IO) {
            try {
                val salt = System.currentTimeMillis().toString()
                val sign = md5(appId + text + salt + secretKey)
                val body = FormBody.Builder()
                    .add("q", text)
                    .add("from", if (from == "auto") "auto" else from)
                    .add("to", to)
                    .add("appid", appId)
                    .add("salt", salt)
                    .add("sign", sign)
                    .build()
                val req = Request.Builder()
                    .url("https://fanyi-api.baidu.com/api/trans/vip/translate")
                    .post(body)
                    .build()
                val resp = http.newCall(req).execute()
                val str = resp.body?.string().orEmpty()
                val json = JSONObject(str)
                if (json.has("error_code")) {
                    return@withContext fail(
                        text, displayName,
                        "错误 ${json.optString("error_code")}: ${json.optString("error_msg")}"
                    )
                }
                val sb = StringBuilder()
                val arr = json.getJSONArray("trans_result")
                for (i in 0 until arr.length()) {
                    if (i > 0) sb.append("\n")
                    sb.append(arr.getJSONObject(i).getString("dst"))
                }
                ok(text, sb.toString(), displayName)
            } catch (e: Exception) {
                fail(text, displayName, e.message)
            }
        }
}

// =====================================================================
// OpenAI 兼容（ChatGPT / DeepSeek / 任意兼容平台）
// =====================================================================
class OpenAITranslator(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val model: String = "gpt-3.5-turbo"
) : TranslationEngine {
    override val id = "openai"
    override val displayName = "ChatGPT / 兼容平台"
    override fun isConfigured() = apiKey.isNotBlank()

    override suspend fun translate(text: String, from: String, to: String) =
        withContext(Dispatchers.IO) {
            try {
                val langName = langName(to)
                val payload = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "你是专业翻译引擎。只输出译文，不要任何解释、引号或多余内容。")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "把下面的文本翻译成$langName：\n$text")
                        })
                    })
                }
                val req = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
                val resp = http.newCall(req).execute()
                val str = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext fail(text, displayName, "HTTP ${resp.code}: ${str.take(200)}")
                }
                val out = JSONObject(str)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                ok(text, out, displayName)
            } catch (e: Exception) {
                fail(text, displayName, e.message)
            }
        }
}

// =====================================================================
// 腾讯云翻译（TC3-HMAC-SHA256 签名）
// =====================================================================
class TencentTranslator(
    private val secretId: String,
    private val secretKey: String,
    private val region: String = "ap-guangzhou"
) : TranslationEngine {
    override val id = "tencent"
    override val displayName = "腾讯云翻译"
    override fun isConfigured() = secretId.isNotBlank() && secretKey.isNotBlank()

    override suspend fun translate(text: String, from: String, to: String) =
        withContext(Dispatchers.IO) {
            try {
                val host = "tmt.tencentcloudapi.com"
                val service = "tmt"
                val action = "TextTranslate"
                val version = "2018-03-21"
                val timestamp = System.currentTimeMillis() / 1000
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date(timestamp * 1000))

                val payloadObj = JSONObject().apply {
                    put("SourceText", text)
                    put("Source", if (from == "auto") "auto" else from)
                    put("Target", to)
                    put("ProjectId", 0)
                }
                val payload = payloadObj.toString()

                // 1. 拼接规范请求串
                val canonicalRequest = listOf(
                    "POST", "/", "",
                    "content-type:application/json; charset=utf-8\nhost:$host\n",
                    "content-type;host",
                    sha256Hex(payload)
                ).joinToString("\n")

                // 2. 拼接待签名字符串
                val credentialScope = "$date/$service/tc3_request"
                val stringToSign = listOf(
                    "TC3-HMAC-SHA256", timestamp.toString(), credentialScope,
                    sha256Hex(canonicalRequest)
                ).joinToString("\n")

                // 3. 计算签名
                val secretDate = hmac("TC3$secretKey".toByteArray(), date)
                val secretService = hmac(secretDate, service)
                val secretSigning = hmac(secretService, "tc3_request")
                val signature = hmacHex(secretSigning, stringToSign)

                val authorization =
                    "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, " +
                        "SignedHeaders=content-type;host, Signature=$signature"

                val req = Request.Builder()
                    .url("https://$host/")
                    .addHeader("Authorization", authorization)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("Host", host)
                    .addHeader("X-TC-Action", action)
                    .addHeader("X-TC-Version", version)
                    .addHeader("X-TC-Timestamp", timestamp.toString())
                    .addHeader("X-TC-Region", region)
                    .post(payload.toRequestBody(JSON))
                    .build()

                val resp = http.newCall(req).execute()
                val str = resp.body?.string().orEmpty()
                val json = JSONObject(str).optJSONObject("Response") ?: JSONObject(str)
                if (json.has("Error")) {
                    val err = json.getJSONObject("Error")
                    return@withContext fail(
                        text, displayName,
                        "${err.optString("Code")}: ${err.optString("Message")}"
                    )
                }
                ok(text, json.optString("TargetText"), displayName)
            } catch (e: Exception) {
                fail(text, displayName, e.message)
            }
        }
}

// =====================================================================
// 免费在线翻译（免 Key，开箱即用）—— 默认引擎
// =====================================================================

/** 免费接口的 HTTP 客户端：超时短一些，避免多端点串行等待太久 */
private val freeHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
}

private const val UA =
    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * 默认翻译引擎：调用公开的**免费**翻译接口，无需任何 API Key，装上就能用。
 *
 * 依次尝试多个端点，一个失败自动换下一个，全部失败才报错：
 * 1. 有道智云体验接口 `aidemo.youdao.com/trans`（国内直连，实测可用）
 * 2. MyMemory `api.mymemory.translated.net/get`（匿名可用，有每日额度）
 * 3. Google 网页端 `translate.googleapis.com/translate_a/single`（境外线路更快）
 *
 * 说明：这些都是公开的免费服务，可能限流或调整；仅用于个人阅读时查句。
 */
class FreeWebTranslator : TranslationEngine {
    override val id = "free"
    override val displayName = "网易有道（免费）"
    override fun isConfigured() = true

    override suspend fun translate(text: String, from: String, to: String) =
        withContext(Dispatchers.IO) {
            val errors = ArrayList<String>(4)
            val providers: List<Pair<String, () -> String?>> = listOf(
                "有道" to { youdao(text, from, to) },
                "MyMemory" to { myMemory(text, from, to) },
                "Google" to { google(text, from, to) }
            )
            providers.forEach { (name, call) ->
                try {
                    val out = call()?.trim()
                    if (!out.isNullOrBlank()) return@withContext ok(text, out, displayName)
                    errors.add("$name 返回空译文")
                } catch (e: Exception) {
                    errors.add("$name ${e.message ?: e.javaClass.simpleName}")
                }
            }
            fail(text, displayName, "免费翻译接口暂时不可用（${errors.joinToString("；")}）")
        }

    /** 有道智云体验接口，返回 JSON 的 translation 数组 */
    private fun youdao(text: String, from: String, to: String): String {
        val url = "https://aidemo.youdao.com/trans?q=${enc(text)}" +
            "&from=${if (from == "auto") "auto" else ydLang(from)}&to=${ydLang(to)}"
        val json = JSONObject(get(url))
        val code = json.optString("errorCode", "0")
        if (code.isNotBlank() && code != "0") throw IOException("错误码 $code")
        val arr = json.optJSONArray("translation") ?: throw IOException("无 translation 字段")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.optString(i))
        return sb.toString()
    }

    /** MyMemory 匿名接口，返回 responseData.translatedText */
    private fun myMemory(text: String, from: String, to: String): String {
        val src = if (from == "auto") "en" else gtLang(from)
        val url = "https://api.mymemory.translated.net/get?q=${enc(text)}" +
            "&langpair=$src|${gtLang(to)}"
        val json = JSONObject(get(url))
        val out = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
        // 额度用尽时接口仍返回 200，但正文是告警语，不能当译文
        if (out.contains("MYMEMORY WARNING", ignoreCase = true) ||
            out.contains("QUERY LENGTH LIMIT", ignoreCase = true)
        ) {
            throw IOException("免费额度已用尽")
        }
        return out
    }

    /** Google 网页端接口，返回 [[[译文, 原文, ...], ...], ...] 嵌套数组 */
    private fun google(text: String, from: String, to: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=${if (from == "auto") "auto" else gtLang(from)}&tl=${gtLang(to)}" +
            "&dt=t&q=${enc(text)}"
        val arr = JSONArray(get(url)).optJSONArray(0) ?: throw IOException("响应结构异常")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            sb.append(arr.optJSONArray(i)?.optString(0).orEmpty())
        }
        return sb.toString()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).addHeader("User-Agent", UA).get().build()
        freeHttp.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return body
        }
    }
}

/** 有道语言码：中文要区分简繁 */
private fun ydLang(code: String): String = when (code.lowercase()) {
    "zh", "zh-cn", "zh_cn", "zh-hans" -> "zh-CHS"
    "zh-tw", "zh_tw", "zh-hant" -> "zh-CHT"
    else -> code
}

/** Google / MyMemory 语言码：中文用 BCP-47 */
private fun gtLang(code: String): String = when (code.lowercase()) {
    "zh", "zh-cn", "zh_cn", "zh-hans" -> "zh-CN"
    "zh-tw", "zh_tw", "zh-hant" -> "zh-TW"
    else -> code
}

private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

// =====================================================================
// 工具函数
// =====================================================================
private fun ok(src: String, dst: String, engine: String) =
    TranslationResult(src, dst, engine, true)

private fun fail(src: String, engine: String, msg: String?) =
    TranslationResult(src, "", engine, false, msg ?: "未知错误")

private fun langName(code: String): String = when (code.lowercase()) {
    "zh", "zh-cn", "zh_cn" -> "简体中文"
    "zh-tw", "zh_tw" -> "繁体中文"
    "en" -> "英语"
    "ja" -> "日语"
    "ko" -> "韩语"
    "fr" -> "法语"
    "de" -> "德语"
    "es" -> "西班牙语"
    "ru" -> "俄语"
    else -> code
}

private fun md5(s: String): String {
    val md = MessageDigest.getInstance("MD5")
    return md.digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun sha256Hex(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun hmac(key: ByteArray, msg: String): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
}

private fun hmacHex(key: ByteArray, msg: String): String =
    hmac(key, msg).joinToString("") { "%02x".format(it) }
