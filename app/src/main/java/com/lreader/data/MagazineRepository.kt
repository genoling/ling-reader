package com.lreader.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** 一期外刊（仓库里一个 epub 文件） */
data class Magazine(
    /** 仓库子目录，如 `01_economist` */
    val group: String,
    /** 书名（不含扩展名），如 `TheEconomist.2025.01.04` */
    val title: String,
    /** 期号日期 `2025.01.04`，解析不到时为空 */
    val date: String,
    /** 仓库内完整路径 */
    val path: String,
    val sizeBytes: Long
)

/**
 * 外刊杂志目录 + 下载（数据源：GitHub 仓库 `hehonghui/awesome-english-ebooks`）。
 *
 * - **目录**：优先 jsDelivr 的 data API（国内通常可直连），失败回退 GitHub API，再失败用本地缓存，
 *   因此断网时仍能看到上次拉到的期号列表。
 * - **下载**：按「jsDelivr CDN → raw.githubusercontent → ghproxy 镜像」顺序重试，任一成功即返回。
 * - 下载得到的 epub 交给 [BookRepository.importFile] 入书架（含封面提取）。
 */
class MagazineRepository(private val context: Context) {

    companion object {
        const val REPO = "hehonghui/awesome-english-ebooks"
        private const val BRANCH = "master"
        private const val CACHE_FILE = "magazines.json"

        /** 目录接口，按优先级尝试 */
        private val LIST_URLS = listOf(
            "https://data.jsdelivr.com/v1/packages/gh/$REPO@$BRANCH?structure=flat",
            "https://api.github.com/repos/$REPO/git/trees/$BRANCH?recursive=1"
        )

        /** 附件下载前缀，按优先级尝试 */
        private val FILE_BASES = listOf(
            "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/",
            "https://raw.githubusercontent.com/$REPO/$BRANCH/",
            "https://ghproxy.net/https://raw.githubusercontent.com/$REPO/$BRANCH/"
        )

        /** 目录名 → 展示名 */
        private val GROUP_NAMES = mapOf(
            "01_economist" to "The Economist",
            "02_new_yorker" to "The New Yorker",
            "03_guardian" to "The Guardian",
            "04_atlantic" to "The Atlantic",
            "05_wired" to "Wired"
        )

        fun groupLabel(dir: String): String = GROUP_NAMES[dir] ?: dir

        private val DATE_REGEX = Regex("""\d{4}\.\d{2}\.\d{2}""")
    }

    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)

    /** 拉取期号列表（失败自动回退缓存） */
    suspend fun list(): Result<List<Magazine>> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (url in LIST_URLS) {
            try {
                val text = get(url) ?: continue
                val items = parseList(text)
                if (items.isEmpty()) continue
                saveCache(items)
                return@withContext Result.success(items)
            } catch (e: Exception) {
                lastError = e
            }
        }
        val cached = loadCache()
        if (cached.isNotEmpty()) return@withContext Result.success(cached)
        Result.failure(lastError ?: IllegalStateException("无法获取外刊目录，请检查网络"))
    }

    /** 已缓存（离线）的期号列表 */
    fun cachedList(): List<Magazine> = loadCache()

    /**
     * 下载一期杂志到 `cacheDir`。
     * @param onProgress 0~1，**主线程**回调
     * @return 下载好的文件；全部镜像都失败返回 null
     */
    suspend fun download(mag: Magazine, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            // 文件名直接用期号：导入书架后「书名 = 文件名」，加了前缀会显示成 mag_xxx
            val safe = mag.title.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(context.cacheDir, "$safe.epub")
            val tmp = File(context.cacheDir, "$safe.epub.part")

            for (base in FILE_BASES) {
                var ok = false
                try {
                    val request = Request.Builder()
                        .url(base + mag.path)
                        .header("User-Agent", "LingReader")
                        .build()
                    ok = http.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        val body = resp.body ?: return@use false
                        val total = body.contentLength().takeIf { it > 0 } ?: mag.sizeBytes
                        body.byteStream().use { input ->
                            FileOutputStream(tmp).use { out ->
                                val buf = ByteArray(1 shl 16)
                                var read = 0L
                                var last = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    read += n
                                    val now = System.currentTimeMillis()
                                    if (now - last >= 200) {
                                        last = now
                                        if (total > 0) {
                                            val p = (read.toFloat() / total).coerceIn(0f, 0.99f)
                                            main.post { onProgress(p) }
                                        }
                                    }
                                }
                                out.flush()
                                out.fd.sync()
                            }
                        }
                        tmp.length() >= 1024
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    tmp.delete()
                }

                if (ok) {
                    if (target.exists()) target.delete()
                    if (tmp.renameTo(target)) {
                        main.post { onProgress(1f) }
                        return@withContext target
                    }
                    tmp.delete()
                }
            }
            null
        }

    // ------------------------------------------------------------------ 内部

    private fun get(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", "LingReader").build()
        http.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    /** 兼容两种返回：jsDelivr `{files:[{name,size}]}` 与 GitHub `{tree:[{path,size}]}` */
    private fun parseList(text: String): List<Magazine> {
        val out = ArrayList<Magazine>()
        val root = JSONObject(text)
        root.optJSONArray("files")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                addEpub(out, o.optString("name").removePrefix("/"), o.optLong("size"))
            }
        }
        root.optJSONArray("tree")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                addEpub(out, o.optString("path"), o.optLong("size"))
            }
        }
        return out.sortedWith(
            compareByDescending<Magazine> { it.date }.thenByDescending { it.title }
        )
    }

    private fun addEpub(out: MutableList<Magazine>, path: String, size: Long) {
        if (!path.endsWith(".epub", ignoreCase = true)) return
        val fileName = path.substringAfterLast('/')
        val title = fileName.substringBeforeLast('.')
        val date = DATE_REGEX.find(title)?.value.orEmpty()
        out.add(
            Magazine(
                group = path.substringBefore('/', ""),
                title = title,
                date = date,
                path = path,
                sizeBytes = size
            )
        )
    }

    private fun saveCache(items: List<Magazine>) {
        try {
            val arr = JSONArray()
            items.forEach { m ->
                arr.put(
                    JSONObject().apply {
                        put("group", m.group)
                        put("title", m.title)
                        put("date", m.date)
                        put("path", m.path)
                        put("size", m.sizeBytes)
                    }
                )
            }
            cacheFile.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCache(): List<Magazine> {
        if (!cacheFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(cacheFile.readText(Charsets.UTF_8))
            val out = ArrayList<Magazine>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Magazine(
                        group = o.optString("group"),
                        title = o.optString("title"),
                        date = o.optString("date"),
                        path = o.optString("path"),
                        sizeBytes = o.optLong("size")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
