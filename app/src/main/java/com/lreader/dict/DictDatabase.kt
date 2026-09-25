package com.lreader.dict

import android.content.Context
import com.lreader.analysis.WordAnalysis
import com.lreader.model.DictEntry
import java.io.File
import java.util.zip.Inflater

/**
 * 本地词典库。
 *
 * - **主词典**：由 `21_en_zh.mdx` 转换而来（33.2 万词条，专业义项齐全）。
 * - **补充词典**：由 ECDICT `ecdict.csv` 转换而来（77 万词条，99.8% 带中文释义）。
 *
 * 两者表结构一致：
 * ```
 * CREATE TABLE dict (word TEXT PRIMARY KEY, html BLOB)   -- html 为 zlib 压缩的 UTF-8 片段
 * ```
 * 补充词典额外带一张变形表：
 * ```
 * CREATE TABLE lemma(form TEXT PRIMARY KEY, base TEXT)   -- is → be、conversions → conversion
 * ```
 *
 * **文件都不随包内置**（v1.1.0 起）：115MB 的主词典由用户在 设置 → 本地词典 里按需下载，
 * 落盘路径仍是 `filesDir/dict_en_zh.db`（与 v1.0.x 一致，老用户升级后无需重新下载）。
 * 查询顺序：**主词典 → 补充词典**，两个库都没有才算「未收录」。
 * 本类只负责「打开已安装的库 + 查询」，安装/下载/删除见 [DictManager]。
 */
class DictDatabase(private val context: Context) {

    companion object {
        /** 必须与 [DictCatalog.MAIN].fileName 一致（老用户已释放的文件才能被复用） */
        private val DB_FILE = DictCatalog.MAIN.fileName

        /** 补充词典（ECDICT，可选下载）落盘文件名 */
        private val SUP_FILE = DictCatalog.ECDICT.fileName

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

    /** 补充词典句柄（未下载时为 null，属正常情况） */
    private var sup: android.database.sqlite.SQLiteDatabase? = null

    private var ready = false

    /** 上次打开补充词典时的文件指纹（长度 xor 修改时间）；用于感知「刚下载完 / 刚被删除」 */
    private var supStamp: Long = -1L

    @Volatile
    var entryCount: Int = 0
        private set

    /** 补充词典词条数（未下载时为 0） */
    @Volatile
    var supplementCount: Int = 0
        private set

    /** 词典是否已就绪（不需要再释放） */
    fun isReady(): Boolean = ready

    /** 主词典是否已下载到本地（未下载时 [ensureReady] 会直接返回 false） */
    fun isInstalled(): Boolean {
        val f = File(context.filesDir, DB_FILE)
        return f.exists() && f.length() > 0
    }

    /** 补充词典是否已下载（可选资源） */
    fun isSupplementInstalled(): Boolean {
        val f = File(context.filesDir, SUP_FILE)
        return f.exists() && f.length() > 0
    }

    /**
     * 打开本地词典库。
     *
     * **不联网、不拷贝 assets**：只打开已下载的文件；主词典缺失时返回 false（补充词典缺失不影响）。
     * 建议在 IO 线程执行。
     */
    fun ensureReady(): Boolean {
        val target = File(context.filesDir, DB_FILE)
        val fileOk = target.exists() && target.length() > 0L
        if (ready && fileOk) {
            // 主词典没变，但补充词典可能刚下载完 / 刚被删除 —— 重新探测一次，
            // 否则用户要重启 App 才能生效（词条数一直是 0、查询也走不到补充词典）。
            reopenSupplementIfChanged()
            return true
        }
        // 词典被用户删除后，旧句柄仍能读到内容，必须显式释放
        if (ready) close()
        if (!fileOk) return false

        return try {
            db = openReadOnly(target)
            entryCount = countOf(db)
            sup = openSupplement()
            supStamp = supStampOf()
            supplementCount = countOf(sup)
            ready = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 补充词典文件指纹；未安装为 -1 */
    private fun supStampOf(): Long {
        val f = File(context.filesDir, SUP_FILE)
        return if (f.exists() && f.length() > 0L) f.length() xor f.lastModified() else -1L
    }

    /** 补充词典文件有变动（新下载 / 被删除）时重开句柄 */
    private fun reopenSupplementIfChanged() {
        val stamp = supStampOf()
        if (stamp == supStamp) return
        try {
            sup?.close()
        } catch (_: Exception) {
        }
        sup = openSupplement()
        supStamp = stamp
        supplementCount = countOf(sup)
    }

    private fun openReadOnly(f: File): android.database.sqlite.SQLiteDatabase =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            f.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )

    /** 打开补充词典；未下载（或文件损坏）时返回 null */
    private fun openSupplement(): android.database.sqlite.SQLiteDatabase? {
        val f = File(context.filesDir, SUP_FILE)
        if (!f.exists() || f.length() <= 0L) return null
        return try {
            openReadOnly(f)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun countOf(handle: android.database.sqlite.SQLiteDatabase?): Int = try {
        handle?.rawQuery("SELECT COUNT(*) FROM dict", null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        } ?: 0
    } catch (e: Exception) {
        0
    }

    /** 词典名称（用于展示） */
    fun dictionaryName(): String = DictCatalog.MAIN.name

    /**
     * 查询单词：主词典优先，查不到再回退到补充词典。
     *
     * @param levels 可选的分级查询：返回该词所属级别
     */
    fun lookup(
        word: String,
        levels: LevelDictionary? = null
    ): DictEntry? {
        if (!ready) return null
        val w = word.trim()
        if (w.isEmpty()) return null

        // 用户可能刚在设置页下载/删除了补充词典，这里顺带探测一次（一次 stat，开销可忽略）
        reopenSupplementIfChanged()

        return lookupIn(db, DictCatalog.MAIN.name, w, levels)
            ?: lookupIn(sup, DictCatalog.ECDICT.name, w, levels)
    }

    /** 在指定库里查一个词：词形还原 → 取 html → 变形补全 → 无中文时补说明 */
    private fun lookupIn(
        handle: android.database.sqlite.SQLiteDatabase?,
        dictName: String,
        word: String,
        levels: LevelDictionary?
    ): DictEntry? {
        if (handle == null) return null
        val key = resolveKeyIn(handle, word) ?: return null
        val raw = readHtmlIn(handle, key) ?: return null
        // 副词/变形词条在词典里只有「xxx的变形」，没有中文释义，这里补上源词的释义
        val html = ensureReadable(enrichFormEntry(handle, key, raw))

        val lv = levels?.primaryLevel(key)
        val lvName = levels?.let { dict -> lv?.let { dict.levelNames[it] } }
        return DictEntry(
            word = key,
            html = html,
            dictionaryName = dictName,
            level = lv,
            levelName = lvName,
            phonetic = extractPhonetic(html),
            // 词形还原命中：用户查的词词典没直接收录（receptiveness → receptive）
            formOf = key.takeIf { !it.equals(word, ignoreCase = true) }
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
    private fun enrichFormEntry(
        handle: android.database.sqlite.SQLiteDatabase?,
        key: String,
        html: String
    ): String {
        val target = WordAnalysis.crossRefTarget(html) ?: return html
        if (target.equals(key, ignoreCase = true)) return html
        val rootKey = resolveKeyIn(handle, target) ?: return html
        if (rootKey.equals(key, ignoreCase = true)) return html
        val rootHtml = readHtmlIn(handle, rootKey) ?: return html

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

    /**
     * 词条正文里一个中文都没有时补一条说明。
     *
     * 这类词条（如 `vt. subdue的变形`、化学名词的 `= 1-octene`）连源词也没有中文释义，
     * 补全逻辑无能为力，用户点开就只看到一行词性 + 变形说明，很像 App 出错。
     * 这里显式说明原因，并指一条可用的路（整句翻译 / 稍后复习）。
     */
    private fun ensureReadable(html: String): String {
        val body = html.substringAfter("</h4>", html)
        if (WordAnalysis.hasChinese(body)) return html
        return html + "\n<div class=\"xref\">该词条在词典中只有词形说明，暂无中文释义；" +
            "可点下方「翻译」按句理解，或加入生词本稍后复习。</div>"
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

    /** 依次尝试：原词 -> 小写 -> 首字母大写 -> 变形表 -> 词形还原 */
    private fun resolveKeyIn(
        handle: android.database.sqlite.SQLiteDatabase?,
        word: String
    ): String? {
        if (handle == null) return null
        val candidates = LinkedHashSet<String>()
        candidates.add(word)
        val lower = word.lowercase()
        candidates.add(lower)
        candidates.add(lower.replaceFirstChar { it.uppercase() })

        // 补充词典自带变形表（form → base），比后缀规则准，优先使用
        lemmaBaseIn(handle, lower)?.let { candidates.add(it) }

        // 缩写 / 所有格：don't → dont / do
        if (lower.any { it == '\'' || it == '’' }) {
            val noApos = lower.replace("'", "").replace("’", "")
            candidates.add(noApos)
            candidates.add(noApos.removeSuffix("s"))
            candidates.add(lower.substringBefore('\'').substringBefore('’'))
            candidates.addAll(lemmaCandidates(noApos))
        }
        candidates.addAll(lemmaCandidates(lower))

        for (c in candidates) {
            if (c.length >= 2 && existsIn(handle, c)) return c
        }
        return null
    }

    /** 查补充词典的变形表（主词典没有这张表，异常直接吞掉） */
    private fun lemmaBaseIn(
        handle: android.database.sqlite.SQLiteDatabase?,
        form: String
    ): String? = try {
        handle?.rawQuery("SELECT base FROM lemma WHERE form = ? LIMIT 1", arrayOf(form))?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 词形还原候选集。
     *
     * 词典只收了 `receptive`、没收 `receptiveness`：旧的 -s/-ed/-ing 规则查不到，
     * 用户看到的就是「未收录该单词」。补上派生后缀后，这类词会落到源词词条上，
     * 弹层会注明「未收录 X，以下为词根 Y 的释义」（见 `DictEntry.formOf`）。
     *
     * 只有整词在词典里查不到时才会用到这些候选，故短后缀（-al / -ic）的误伤概率很低。
     */
    private fun lemmaCandidates(word: String): List<String> {
        val w = word.trim()
        val out = LinkedHashSet<String>()
        if (w.length < 3 || !w.first().isLetter()) return out.toList()

        /** 去掉 tail 个字符，并派生「补 e」「去重复辅音」「i→y」三个常见变体 */
        fun stems(tail: Int) {
            val s = w.dropLast(tail)
            if (s.length < 2) return
            out.add(s)
            out.add(s + "e")
            if (s.length >= 3 && s.last() == s[s.length - 2]) out.add(s.dropLast(1))
            if (s.endsWith("i")) out.add(s.dropLast(1) + "y")
        }

        // ---- 屈折变化 ----
        if (w.endsWith("ies") && w.length > 4) out.add(w.dropLast(3) + "y")
        if (w.endsWith("ves")) {
            out.add(w.dropLast(3) + "f")
            out.add(w.dropLast(3) + "fe")
        }
        if (w.endsWith("ied") && w.length > 4) out.add(w.dropLast(3) + "y")
        if (w.endsWith("ed") && w.length > 4) stems(2)
        if (w.endsWith("ing") && w.length > 5) stems(3)
        if (w.endsWith("iest") && w.length > 5) out.add(w.dropLast(4) + "y")
        if (w.endsWith("est") && w.length > 4) stems(3)
        if (w.endsWith("ier") && w.length > 4) out.add(w.dropLast(3) + "y")
        if (w.endsWith("er") && w.length > 3) stems(2)
        if (w.endsWith("es") && w.length > 3) out.add(w.dropLast(2))
        if (w.endsWith("s") && !w.endsWith("ss")) out.add(w.dropLast(1))

        // 副词回推形容词：-ably → -able、-ibly → -ible、-ly → -le（simply → simple）
        if (w.endsWith("ably") && w.length > 5) out.add(w.dropLast(4) + "able")
        if (w.endsWith("ibly") && w.length > 5) out.add(w.dropLast(4) + "ible")
        if (w.endsWith("ly")) out.add(w.dropLast(2) + "le")

        // ---- 派生后缀（长后缀在前，避免 -al / -ic 提前命中）----
        for ((suffix, len) in DERIVE_SUFFIXES) {
            if (w.length > len + 2 && w.endsWith(suffix)) stems(len)
        }
        out.remove(w)
        return out.toList()
    }

    /** 常见派生后缀 → 需要去掉的字符数（长后缀在前） */
    private val DERIVE_SUFFIXES = listOf(
        "ization" to 7, "isation" to 7, "ation" to 5,
        "ically" to 6, "lessly" to 6, "ness" to 4, "ment" to 4,
        "tion" to 4, "sion" to 4, "ance" to 4, "ence" to 4,
        "able" to 4, "ible" to 4, "less" to 4,
        "ity" to 3, "ety" to 3, "ive" to 3, "ous" to 3, "ism" to 3,
        "ist" to 3, "ize" to 3, "ise" to 3, "ful" to 3, "ish" to 3,
        "ary" to 3, "ory" to 3, "ly" to 2, "al" to 2, "ic" to 2
    )

    private fun existsIn(
        handle: android.database.sqlite.SQLiteDatabase?,
        word: String
    ): Boolean {
        handle?.rawQuery("SELECT 1 FROM dict WHERE word = ? LIMIT 1", arrayOf(word))?.use { c ->
            return c.moveToFirst()
        }
        return false
    }

    private fun readHtmlIn(
        handle: android.database.sqlite.SQLiteDatabase?,
        word: String
    ): String? {
        handle?.rawQuery("SELECT html FROM dict WHERE word = ? LIMIT 1", arrayOf(word))?.use { c ->
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

    /** 前缀联想（只用主词典，够用且避免两库结果混杂） */
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
        try {
            sup?.close()
        } catch (_: Exception) {
        }
        db = null
        sup = null
        supStamp = -1L
        ready = false
    }
}
