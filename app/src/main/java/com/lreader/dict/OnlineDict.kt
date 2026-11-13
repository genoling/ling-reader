package com.lreader.dict

import android.content.Context
import com.lreader.model.DictEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线查词兜底（网易有道）。
 *
 * 本地词典收词有限：`national-security` 这类带连字符的复合词、专名、新词都查不到，
 * 弹层只会显示「未收录该单词」，用户一个中文释义也拿不到（2026-11-13 反馈）。
 *
 * 这里在本地查空时补一次有道在线查询，并把结果转成**与本地词典相同的 HTML 结构**
 * （`ul > li`，不含 `.pos` 分组），这样 `DictHtmlView` 直接能渲染，
 * `WordAnalysis.meaningText` 也能取出「n. 苹果；苹果树」这种可用作生词释义的文本 ——
 * 释义字符串自带词性前缀，不需要额外分组。
 *
 * 结果按词缓存在 cache 目录：同一本书里反复点同一个词不会重复走网络。
 * 任何失败（超时、无释义、JSON 结构变了）都返回 null，由 UI 提示「在线也没查到」。
 */
object OnlineDict {

    private const val ENDPOINT = "https://dict.youdao.com/jsonapi?q="
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 查不到 / 网络失败都返回 null */
    suspend fun lookup(context: Context, word: String): DictEntry? = withContext(Dispatchers.IO) {
        val key = word.trim().lowercase()
        if (key.isBlank()) return@withContext null

        cacheFile(context, key).takeIf { it.isFile }?.let { f ->
            runCatching { fromCache(word, f.readText()) }.getOrNull()?.let { return@withContext it }
        }
        val entry = runCatching { fetch(word) }.getOrNull() ?: return@withContext null
        runCatching { cacheFile(context, key).writeText(toCache(entry)) }
        entry
    }

    /**
     * 本地查空才走在线：给「生词本详情 / 复习详情」这类只想要一份释义的场景用。
     * 在线也查不到时把本地结果（可能为 null）原样返回。
     */
    suspend fun lookupOrLocal(context: Context, local: DictEntry?, word: String): DictEntry? =
        if (local != null && local.html.isNotBlank()) local
        else lookup(context, word) ?: local

    // ---------- 网络 ----------

    private fun fetch(word: String): DictEntry? {
        val url = ENDPOINT + URLEncoder.encode(word, "UTF-8") + "&dicts=ec&le=en"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", UA)
        }
        val body = try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        val root = JSONObject(body)
        val ec = root.optJSONObject("ec")
        val w = ec?.optJSONArray("word")?.optJSONObject(0)

        val headword = w?.optJSONObject("return-phrase")?.optJSONObject("l")
            ?.optString("i").orEmpty().trim().ifBlank { word.trim() }
        val phonetic = w?.optString("usphone").orEmpty().ifBlank {
            w?.optString("ukphone").orEmpty()
        }.trim()

        val defs = LinkedHashSet<String>()
        // ec.word[0].trs[i].tr[j].l.i 可能是数组，也可能是字符串
        val trs = w?.optJSONArray("trs")
        for (i in 0 until (trs?.length() ?: 0)) {
            val trArr = trs?.optJSONObject(i)?.optJSONArray("tr") ?: continue
            for (j in 0 until trArr.length()) {
                val l = trArr.optJSONObject(j)?.optJSONObject("l") ?: continue
                when (val node = l.opt("i")) {
                    is JSONArray -> for (k in 0 until node.length()) {
                        node.optString(k).trim().takeIf { it.isNotBlank() }?.let { defs.add(it) }
                    }
                    is String -> node.trim().takeIf { it.isNotBlank() }?.let { defs.add(it) }
                }
            }
        }
        // 兜底：网络释义（复合词常见，如 national-security → 国家安全）
        if (defs.isEmpty()) {
            val web = root.optJSONObject("web_trans")
                ?.optJSONArray("web-translation")
            for (i in 0 until (web?.length() ?: 0)) {
                val trans = web?.optJSONObject(i)?.optJSONArray("trans") ?: continue
                for (j in 0 until trans.length()) {
                    trans.optJSONObject(j)?.optString("value").orEmpty()
                        .trim().takeIf { it.isNotBlank() }?.let { defs.add(it) }
                }
            }
        }
        if (defs.isEmpty()) return null

        // 每条释义包一层 `<span class="def">`：本地词典就是这么标记的，
        // 这样 `WordAnalysis.parseDefinitions` 能逐条取到释义（否则只会得到拼平的一整段），
        // 加进生词本时 meaning 才是干净的「n. 苹果；苹果树」
        val html = buildString {
            append("<ul>")
            defs.take(12).forEach {
                append("<li><span class=\"def\">").append(escape(it)).append("</span></li>")
            }
            append("</ul>")
        }
        return DictEntry(
            word = headword,
            html = html,
            dictionaryName = "有道在线",
            phonetic = phonetic.ifBlank { null }
        )
    }

    // ---------- 缓存 ----------

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "online_dict").apply { if (!exists()) mkdirs() }

    private fun cacheFile(context: Context, key: String): File {
        // 词可能含 / : 等文件系统敏感字符，统一转成安全文件名
        val safe = key.map { if (it.isLetterOrDigit() || it == '-' || it == '\'') it else '_' }
            .joinToString("")
        return File(cacheDir(context), "$safe.json")
    }

    private fun toCache(e: DictEntry): String = JSONObject().apply {
        put("word", e.word)
        put("html", e.html)
        put("phonetic", e.phonetic ?: "")
    }.toString()

    private fun fromCache(tapped: String, raw: String): DictEntry? {
        val o = JSONObject(raw)
        val html = o.optString("html")
        if (html.isBlank()) return null
        return DictEntry(
            word = o.optString("word").ifBlank { tapped },
            html = html,
            dictionaryName = "有道在线",
            phonetic = o.optString("phonetic").ifBlank { null }
        )
    }

    // ---------- 工具 ----------

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
