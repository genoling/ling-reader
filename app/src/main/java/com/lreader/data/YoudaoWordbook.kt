package com.lreader.data

import android.webkit.CookieManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 有道生词本同步（**网页内部接口，非官方**）。
 *
 * 背景：有道**官方不提供**单词本 API（`fanyi.youdao.com/openapi` 明确答复「暂不支持」），
 * 但网页版生词本有 `wordbook/ajax?action=addword`，用**登录 Cookie** 鉴权
 * （未登录会返回 `{"message":"nouser"}`，已实测）。
 *
 * 有道的生词本是**账号级云同步**的 —— 词进了账号，手机 APP 自动就能看到，
 * 不需要去操作 APP。Cookie 由 App 内的 WebView 登录后自动读取
 * （见 `ui/settings/YoudaoSection.kt`），因此天然按「用户自己的账号」区分。
 */
object YoudaoWordbook {

    /**
     * 登录页：有道的统一登录页 `common-login-web`。
     * `redirect_url` 指定登录成功后回跳的地址 —— 回到词典域名，Cookie 也就写到该域。
     * （`account.youdao.com/login` 实测不带回跳、会落到 www.youdao.com 主站，不用它。）
     */
    const val LOGIN_URL =
        "https://c.youdao.com/common-login-web/index.html?redirect_url=https%3A%2F%2Fdict.youdao.com%2F"

    /** 读取 Cookie 用的地址（CookieManager 按域返回整串） */
    private const val COOKIE_URL = "https://dict.youdao.com/"

    /** [addWord] 返回此标记 = 登录态失效，UI 应提示重新登录 */
    const val NOT_LOGGED_IN = "__NOT_LOGGED_IN__"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** 从 WebView 的 CookieManager 取回整串 Cookie（登录后调用） */
    fun readCookie(): String =
        runCatching { CookieManager.getInstance().getCookie(COOKIE_URL) }.getOrNull().orEmpty()

    /**
     * 是否「像是已登录」：有道登录后会在 dict.youdao.com 域写入 `DICT_*` 登录 Cookie。
     * 这里只做宽松判断，真正的失败由 [addWord] 的返回兜底。
     */
    fun looksLoggedIn(cookie: String): Boolean {
        val c = cookie.lowercase()
        return c.contains("dict_sess") || c.contains("dict_login") || c.contains("dict_auth")
    }

    /** 有道 addword 需要的「格林威治标准时间」字符串 */
    private fun gmtNow(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT+08:00")
        return fmt.format(Date()) + " (中国标准时间)"
    }

    /**
     * 把一个单词加入有道生词本。
     *
     * @return null = 成功；[NOT_LOGGED_IN] = 登录失效；其它字符串 = 具体错误
     */
    fun addWord(cookie: String, word: String): String? {
        val w = word.trim()
        if (w.isEmpty()) return "空单词"
        val url = buildString {
            append("http://dict.youdao.com/wordbook/ajax?action=addword")
            append("&q=").append(URLEncoder.encode(w, "UTF-8"))
            append("&date=").append(URLEncoder.encode(gmtNow(), "UTF-8"))
            append("&le=eng")
        }
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0 Mobile Safari/537.36"
            )
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://dict.youdao.com/")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty().trim()
                when {
                    !resp.isSuccessful -> "HTTP ${resp.code}"
                    body.contains("nouser") -> NOT_LOGGED_IN
                    // 有道添加成功的返回就是 {"message":"adddone"}（实测：既没有 code 也没有 success 字段）
                    body.contains("adddone") -> null
                    body.contains("\"code\":0") || body.contains("success", ignoreCase = true) -> null
                    else -> body.take(100).ifBlank { "未知响应" }
                }
            }
        } catch (e: IOException) {
            "网络异常：${e.message}"
        }
    }

    /** 批量同步结果 */
    data class SyncResult(val ok: Int, val fail: Int, val error: String?)

    /** 账号里的一个单词本；[DEFAULT_BOOK_ID] 是默认的「无标签」本 */
    data class Book(val id: String, val name: String)

    /** 默认本「无标签」的 bookId —— [addWord] 只会把词加进这个本 */
    const val DEFAULT_BOOK_ID = "0"

    private const val BASE = "https://dict.youdao.com"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /** 有道网页内部接口的 GET（网页版自己就是这么调的：桌面 UA + wordlist Referer） */
    private fun get(cookie: String, url: String): String? = try {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", WEB_UA)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$BASE/wordbook/wordlist")
                .get()
                .build()
        ).execute().use { it.body?.string()?.trim() }
    } catch (e: IOException) {
        null
    }

    /** 列出账号里的单词本（网页版生词本「分类」下拉用的接口） */
    fun listBooks(cookie: String): List<Book> {
        val body = get(cookie, "$BASE/wordbook/webapi/books") ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONArray("data")
            (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                arr?.optJSONObject(i)?.let { Book(it.optString("bookId"), it.optString("bookName")) }
            }
        }.getOrDefault(emptyList())
    }

    /** 账号里的一个词条（云端只有 word / phonetic / trans 三个可用字段） */
    data class Word(val word: String, val phonetic: String, val trans: String)

    /**
     * 拉取账号里**全部**词条（跨账号下所有单词本，即网页版生词本的「全部」视图）。
     *
     * 导出「本地 + 有道」合并 XML 时用：云端独有的词要一并带上。
     * 未登录 / 网络异常时返回空表，调用方按「云端 0 个词」处理即可
     * （导出场景下「没登录」与「真的没词」的行为一致，不必区分）。
     */
    fun allWords(cookie: String): List<Word> {
        if (!looksLoggedIn(cookie)) return emptyList()
        val body = get(cookie, "$BASE/wordbook/webapi/words?limit=2000&offset=0") ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("itemList")
            (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val o = arr?.optJSONObject(i) ?: return@mapNotNull null
                val w = o.optString("word").trim()
                if (w.isEmpty()) null
                else Word(w, o.optString("phonetic").trim(), o.optString("trans").trim())
            }
        }.getOrDefault(emptyList())
    }

    /** 词条（归类到单词本要用 itemId） */
    private data class Item(val itemId: String, val phonetic: String, val trans: String)

    /** 拉一份「词形(小写) → 词条」映射，归类时按词查 itemId（只拉一次，避免逐词查询） */
    private fun itemsByWord(cookie: String): Map<String, Item> {
        val body = get(cookie, "$BASE/wordbook/webapi/words?limit=2000&offset=0") ?: return emptyMap()
        return runCatching {
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("itemList")
            val out = HashMap<String, Item>()
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr?.optJSONObject(i) ?: continue
                val w = o.optString("word").trim().lowercase()
                if (w.isNotEmpty()) {
                    out[w] = Item(o.optString("itemId"), o.optString("phonetic"), o.optString("trans"))
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    /**
     * 网页版「修改单词」用的接口：把某个词条移到指定单词本。
     *
     * [addWord] 不支持 `bookId`（传了也只会进「无标签」，已实测），所以归类只能走这个接口。
     *
     * @return null = 成功；[NOT_LOGGED_IN] = 登录失效；其它字符串 = 具体错误
     */
    fun moveToBook(cookie: String, itemId: String, bookId: String, phonetic: String, trans: String): String? {
        val url = "$BASE/wordbook/webapi/modify" +
            "?itemId=" + URLEncoder.encode(itemId, "UTF-8") +
            "&bookId=" + URLEncoder.encode(bookId, "UTF-8") +
            "&phonetic=" + URLEncoder.encode(phonetic, "UTF-8") +
            "&trans=" + URLEncoder.encode(trans, "UTF-8")
        val body = get(cookie, url) ?: return "网络异常"
        return when {
            body.contains("nouser") -> NOT_LOGGED_IN
            body.contains("\"code\":0") -> null
            else -> body.take(100).ifBlank { "未知响应" }
        }
    }

    /**
     * 依次同步（有道无批量接口）；[onProgress] 在每次请求后回调，供 UI 显示进度。
     *
     * [targetBookId] 非空时，加完后会再把这些词**归类**到该单词本（可以是「无标签」，
     * 这样选错本后还能移回来）：词先 [addWord] 进「无标签」，再逐个 [moveToBook] 移过去
     * （两步，故进度总数翻倍）。传空串 = 不动词本归属。
     */
    fun syncAll(
        cookie: String,
        words: List<String>,
        targetBookId: String = "",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): SyncResult {
        val target = targetBookId.trim()
        // 显式选了目标本（含「无标签」= "0"）才归类；空串 = 保持词原本的归属
        val needMove = target.isNotEmpty()
        var ok = 0
        var fail = 0
        var firstErr: String? = null
        val added = ArrayList<String>()
        val total = words.size * (if (needMove) 2 else 1)

        words.forEachIndexed { i, w ->
            val err = addWord(cookie, w)
            if (err == null) {
                ok++
                added.add(w)
            } else {
                fail++
                if (firstErr == null) firstErr = err
            }
            onProgress(i + 1, total)
        }

        if (needMove && added.isNotEmpty()) {
            val items = itemsByWord(cookie)
            added.forEachIndexed { i, w ->
                val it = items[w.lowercase()]
                val err = if (it == null) "词条未找到" else
                    moveToBook(cookie, it.itemId, target, it.phonetic, it.trans)
                if (err != null) {
                    // 词加进账号了、但没进目标本，按"没同步成功"计
                    ok--
                    fail++
                    if (firstErr == null) firstErr = "归类失败：$err"
                }
                onProgress(words.size + i + 1, total)
            }
        }
        return SyncResult(ok, fail, firstErr)
    }
}
