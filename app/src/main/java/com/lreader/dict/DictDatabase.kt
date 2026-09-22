package com.lreader.dict

import android.content.Context
import com.lreader.analysis.WordAnalysis
import com.lreader.model.DictEntry
import java.io.File
import java.util.zip.Inflater

/**
 * 本地词典库（由 21_en_zh.mdx 转换而来）。
 *
 * 结构：SQLite
 *   CREATE TABLE dict (word TEXT PRIMARY KEY, html BLOB)
 *   html 为 zlib 压缩后的 UTF-8 片段（已剥离外层 <head>/<div> 包裹）。
 *
 * **文件不再随包内置**（v1.1.0 起）：115MB 的词典由用户在 设置 → 本地词典 里按需下载，
 * 落盘路径仍是 `filesDir/dict_en_zh.db`（与 v1.0.x 一致，老用户升级后无需重新下载）。
 * 本类只负责「打开已安装的库 + 查询」，安装/下载/删除见 [DictManager]。
 */
class DictDatabase(private val context: Context) {

    companion object {
        /** 必须与 [DictCatalog.MAIN].fileName 一致（老用户已释放的文件才能被复用） */
        private val DB_FILE = DictCatalog.MAIN.fileName

        /**
         * 音标位于词条 HTML 的 `<span class="phonetic wordGroup">[ə'bændən]</span>`。
         * class 可能是 "phonetic" 或 "phonetic wordGroup"，故用前缀匹配。
         */
        private val PHONETIC_REGEX = Regex(
            "<span[^>]*class=\"phonetic[^\"]*\"[^>]*>(.*?)</span>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }

    private var db: android.database.sqlite.SQLiteDatabase? = null
    private var ready = false

    @Volatile
    var entryCount: Int = 0
        private set

    /** 词典是否已就绪（不需要再释放） */
    fun isReady(): Boolean = ready

    /** 词典是否已下载到本地（未下载时 [ensureReady] 会直接返回 false） */
    fun isInstalled(): Boolean {
        val f = File(context.filesDir, DB_FILE)
        return f.exists() && f.length() > 0
    }

    /**
     * 打开本地词典库。
     *
     * **不联网、不拷贝 assets**：只打开已下载的文件；若用户还没下载（或文件被外部删掉），
     * 返回 false，由调用方提示「去设置里下载词典」。
     * 建议在 IO 线程执行。
     */
    fun ensureReady(): Boolean {
        val target = File(context.filesDir, DB_FILE)
        val fileOk = target.exists() && target.length() > 0L
        if (ready && fileOk) return true
        // 词典被用户删除后，旧句柄仍能读到内容，必须显式释放
        if (ready) close()
        if (!fileOk) return false

        return try {
            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                target.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            entryCount = queryCount()
            ready = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun queryCount(): Int = try {
        db?.rawQuery("SELECT COUNT(*) FROM dict", null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        } ?: 0
    } catch (e: Exception) {
        0
    }

    /** 词典名称（用于展示） */
    fun dictionaryName(): String = DictCatalog.MAIN.name

    /**
     * 精确查询单词，返回 HTML 释义。
     * @param levels 可选的分级查询：返回该词所属级别
     * @param levelNames 级别显示名映射
     */
    fun lookup(
        word: String,
        levels: LevelDictionary? = null
    ): DictEntry? {
        if (!ready) return null
        val w = word.trim()
        if (w.isEmpty()) return null

        val key = resolveKey(w) ?: return null
        val raw = readHtml(key) ?: return null
        // 副词/变形词条在词典里只有「xxx的变形」，没有中文释义，这里补上源词的释义
        val html = enrichFormEntry(key, raw)

        val lv = levels?.primaryLevel(key)
        val lvName = levels?.let { dict -> lv?.let { dict.levelNames[it] } }
        return DictEntry(
            word = key,
            html = html,
            dictionaryName = dictionaryName(),
            level = lv,
            levelName = lvName,
            phonetic = extractPhonetic(html)
        )
    }

    /**
     * 补全「变形词条」的释义。
     *
     * 21 世纪大英汉词典对变形词（如 adv. `simultaneously`、`abruptly`）只收录
     * `simultaneous的变形` 这类指向性说明，**没有中文释义**，用户看到的就是一句
     * 「adv. simultaneous的变形」——有词性、没有意思。
     *
     * 这里取源词（simultaneous）的释义，且把源词的词性标签**换成变形词自己的词性**，
     * 于是弹层呈现为「adv. + 中文释义」；源词说明缩成一行小字放在末尾，
     * 不再挡在中文释义前面。源词自身若无中文释义则不加工，避免空段落。
     */
    private fun enrichFormEntry(key: String, html: String): String {
        val target = WordAnalysis.crossRefTarget(html) ?: return html
        if (target.equals(key, ignoreCase = true)) return html
        val rootKey = resolveKey(target) ?: return html
        if (rootKey.equals(key, ignoreCase = true)) return html
        val rootHtml = readHtml(rootKey) ?: return html

        val h4End = html.indexOf("</h4>")
        if (h4End < 0) return html
        val idx = rootHtml.indexOf("</h4>")
        val body = (if (idx >= 0) rootHtml.substring(idx + 5) else rootHtml)
            .replace(Regex("(?s)<head>.*?</head>"), " ")
            .trim()
        if (body.isBlank() || !WordAnalysis.hasChinese(body)) return html

        // 原词条正文只有 `simultaneous的变形`（没有中文），整段换掉，只留标题（单词 + 音标）
        val title = html.substring(0, h4End + 5)
        val titleWithPhonetic = if (extractPhonetic(html) != null) title else {
            val p = extractPhonetic(rootHtml) ?: ""
            if (p.isBlank()) title
            else title.replaceFirst(
                "</h4>",
                " <span class=\"phonetic wordGroup\">$p</span></h4>"
            )
        }

        // 源词的词性（adj.）换成变形词自己的词性（adv.），释义内容照抄源词
        val ownPos = WordAnalysis.parseDefinitions(html).firstOrNull()?.pos.orEmpty()
        val rootPosSpan = if (ownPos.isBlank()) null else POS_SPAN_REGEX.find(body)
        val retagged = when {
            ownPos.isBlank() -> body
            rootPosSpan != null ->
                body.substring(0, rootPosSpan.range.first) +
                    "<span${rootPosSpan.groupValues[1]}>$ownPos</span>" +
                    body.substring(rootPosSpan.range.last + 1)
            else -> "<span class=\"pos\">$ownPos</span>$body"
        }

        val note = "<div class=\"xref\">$key 是 <b>$rootKey</b> 的变形，释义取自 $rootKey</div>"
        return titleWithPhonetic + "\n" + retagged + "\n" + note
    }

    /** 把源词的词性标签整段换成变形词自己的词性（只换标签，不动释义） */
    private val POS_SPAN_REGEX = Regex(
        "<span([^>]*class=\"pos[^\"]*\"[^>]*)>.*?</span>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * 取出音标（不含方括号以外的加工）。
     * 历史 bug：该字段长期未赋值，导致查词弹层与生词本的音标一直是空的。
     */
    fun extractPhonetic(html: String): String? {
        val m = PHONETIC_REGEX.find(html) ?: return null
        return m.groupValues[1].trim().ifEmpty { null }
    }

    /** 词典是否已下载到本地（历史函数名，调用方见 `VocabScreen` 的旧数据回填） */
    fun isExtracted(): Boolean = isInstalled()

    /** 依次尝试：原词 -> 小写 -> 首字母大写 -> 词形还原 */
    private fun resolveKey(word: String): String? {
        val candidates = LinkedHashSet<String>()
        candidates.add(word)
        candidates.add(word.lowercase())
        candidates.add(word.lowercase().replaceFirstChar { it.uppercase() })

        val w = word.lowercase()
        if (w.endsWith("s")) candidates.add(w.dropLast(1))
        if (w.endsWith("es")) candidates.add(w.dropLast(2))
        if (w.endsWith("ies")) candidates.add(w.dropLast(3) + "y")
        if (w.endsWith("ed")) {
            candidates.add(w.dropLast(2))
            candidates.add(w.dropLast(1))
            candidates.add(w.dropLast(2) + "e")
        }
        if (w.endsWith("ing")) {
            candidates.add(w.dropLast(3))
            candidates.add(w.dropLast(3) + "e")
        }
        if (w.endsWith("ly")) candidates.add(w.dropLast(2))
        if (w.endsWith("er")) {
            candidates.add(w.dropLast(2))
            candidates.add(w.dropLast(1))
        }
        if (w.endsWith("est")) {
            candidates.add(w.dropLast(3))
            candidates.add(w.dropLast(2))
        }

        for (c in candidates) {
            if (exists(c)) return c
        }
        return null
    }

    private fun exists(word: String): Boolean {
        db?.rawQuery("SELECT 1 FROM dict WHERE word = ? LIMIT 1", arrayOf(word))?.use { c ->
            return c.moveToFirst()
        }
        return false
    }

    private fun readHtml(word: String): String? {
        db?.rawQuery("SELECT html FROM dict WHERE word = ? LIMIT 1", arrayOf(word))?.use { c ->
            if (!c.moveToFirst()) return null
            val blob = c.getBlob(0) ?: return null
            return inflate(blob)
        }
        return null
    }

    private fun inflate(data: ByteArray): String? {
        return try {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
            inflater.end()
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 前缀联想 */
    fun suggest(prefix: String, limit: Int = 20): List<String> {
        if (!ready) return emptyList()
        val p = prefix.trim()
        if (p.isEmpty()) return emptyList()
        val list = ArrayList<String>()
        db?.rawQuery(
            "SELECT word FROM dict WHERE word >= ? AND word < ? ORDER BY word LIMIT ?",
            arrayOf(p, p + "\uFFFF", limit.toString())
        )?.use { c ->
            while (c.moveToNext()) list.add(c.getString(0))
        }
        return list
    }

    fun close() {
        try {
            db?.close()
        } catch (_: Exception) {
        }
        db = null
        ready = false
    }
}
