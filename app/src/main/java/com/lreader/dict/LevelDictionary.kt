package com.lreader.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lreader.model.LevelConfig

/**
 * 分级词库（CET4/CET6/考研/GRE…）。
 *
 * 资源**随包内置**（见 [DictCatalog.LEVELS]）：assets/levels.db 由 [DictManager] 释放到 filesDir，
 * 之后以只读方式打开。词数较小（39K），可全量载入内存构建哈希索引，查询 O(1)。
 *
 * 另有一份**整本书词表**——《雅思词汇真经》（刘洪波）3600+ 词，来自 assets/ielts_zj.txt，
 * 由 [loadBookList] 合并进同一份索引，对外就是一个普通级别（[BOOK_ZJ]）。
 */
class LevelDictionary(private val context: Context) {

    private val DEFAULT_COLORS = mapOf(
        "CET4" to 0xFF2E7D32.toInt(),
        "CET6" to 0xFF1565C0.toInt(),
        "KaoYan" to 0xFF6A1B9A.toInt(),
        "TEM4" to 0xFF00695C.toInt(),
        "TEM8" to 0xFFAD1457.toInt(),
        "GRE" to 0xFFD84315.toInt(),
        "IELTS" to 0xFF00838F.toInt(),
        "TOEFL" to 0xFFAF601A.toInt(),
        "GAOZHONG" to 0xFF558B2F.toInt(),
        "BEC" to 0xFF4E342E.toInt(),
        // 真经用较亮的紫罗兰：现有 10 档都是深色（#2E7D32…），石板灰那类深色在米黄纸上
        // 与正文几乎同色，实机看不出高亮
        "IELTS_ZJ" to BOOK_ZJ_COLOR
    )

    /**
     * 级别难度序（从易到难）。
     *
     * 一个词常常同时属于多个级别：`unlikely` 既是四级也是雅思，`immense` 属于
     * 四级/BEC/考研/雅思。这种情况统一取**最容易**的级别作为归属 ——
     * 既决定正文高亮颜色，也决定查词弹层里显示的级别标签。
     * 注意 [level_meta] 表的顺序是建库顺序（CET4/CET6/KaoYan/…/GAOZHONG/BEC），
     * 不能直接当难度序用。
     */
    private val LEVEL_ORDER = listOf(
        "GAOZHONG", "CET4", "CET6", "BEC", "KaoYan", "TEM4", "TEM8", "IELTS", "TOEFL", "GRE",
        // 整本书词表排在最后：同一个词若也属于某个考试词表，仍按考试级别着色
        BOOK_ZJ
    )

    /** 未知级别排到最后 */
    private fun rankOf(level: String): Int =
        LEVEL_ORDER.indexOf(level).let { if (it < 0) LEVEL_ORDER.size else it }

    companion object {
        /** 内置资源描述：体积小（4.6MB）随包分发；将来要改成「按需下载」只需把 assetName 置空 */
        private val RES = DictCatalog.LEVELS

        /** 《雅思词汇真经》（刘洪波）整本书词表的级别 key */
        const val BOOK_ZJ = "IELTS_ZJ"

        /** 词表资源：每行一个词条，`#` 开头为注释 */
        private const val BOOK_ZJ_ASSET = "ielts_zj.txt"
        private const val BOOK_ZJ_NAME = "雅思词汇真经"

        /** 真经的高亮色（正文与查词弹层标签共用） */
        const val BOOK_ZJ_COLOR = 0xFF7E57C2.toInt()

        /**
         * 视为「基础词」的级别（最基础的三档）。
         * levels.db 是按「考试要求掌握的词汇」收录的，高级词表里混进了大量基础词，见 [matchLevel]。
         */
        val BASIC_LEVELS = setOf("GAOZHONG", "CET4", "CET6")

        /**
         * 「整本书词表」型级别：**不参与**「忽略基础词」的过滤判断。
         *
         * 真经 3600+ 词绝大多数同时属于高中/四级/六级，若按考试词表那样过滤，
         * 只勾真经时几乎整本书都会被滤空（实测雅思词表就是这么只剩 5% 的）。
         */
        val BOOK_LEVELS = setOf(BOOK_ZJ)
    }

    /** 整本书词表里的一个词条位置（查词弹层显示「第 N 章 章节名」） */
    data class BookInfo(val chapter: Int, val name: String)

    /** word(lowercase) -> 级别列表 */
    private val index = HashMap<String, MutableList<String>>()
    /** word(lowercase) -> 在整本书词表里的章节 */
    private val bookChapters = HashMap<String, BookInfo>()
    /** level -> 显示名 */
    val levelNames = LinkedHashMap<String, String>()
    /** level -> 词数 */
    val levelCounts = LinkedHashMap<String, Int>()

    var ready = false
        private set

    fun ensureReady(): Boolean {
        if (ready) return true
        // 内置资源：首次使用时由 DictManager 从 assets 释放（走 .part 中转，失败不留半个库）
        val manager = DictManager.get(context)
        if (!manager.ensureInstalled(RES)) return false
        val target = manager.fileOf(RES)
        return try {
            val db = SQLiteDatabase.openDatabase(
                target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            db.rawQuery("SELECT level,name,word_count FROM level_meta", null).use { c ->
                while (c.moveToNext()) {
                    levelNames[c.getString(0)] = c.getString(1)
                    levelCounts[c.getString(0)] = c.getInt(2)
                }
            }
            db.rawQuery("SELECT word,level FROM level_word", null).use { c ->
                while (c.moveToNext()) {
                    val w = c.getString(0).lowercase()
                    val lv = c.getString(1)
                    index.getOrPut(w) { mutableListOf() }.add(lv)
                }
            }
            db.close()
            loadBookList()
            ready = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 载入随包内置的整本书词表（assets/ielts_zj.txt），合并进同一份索引。
     *
     * 行格式：`word<TAB>章节序号<TAB>章节名`（只写一个词也能用，章节信息缺失则弹层不显示章节）。
     * 词表缺失 / 读取失败只记日志，不影响考试词表的高亮（老设备升级后也不会崩）。
     */
    private fun loadBookList() {
        try {
            context.assets.open(BOOK_ZJ_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                var count = 0
                reader.forEachLine { line ->
                    val s = line.trim()
                    if (s.isEmpty() || s.startsWith("#")) return@forEachLine
                    val parts = s.split('\t')
                    val w = parts[0].trim()
                    if (w.isEmpty()) return@forEachLine
                    val key = w.lowercase()
                    val list = index.getOrPut(key) { mutableListOf() }
                    if (BOOK_ZJ !in list) {
                        list.add(BOOK_ZJ)
                        count++
                    }
                    if (bookChapters[key] == null && parts.size >= 3) {
                        val no = parts[1].trim().toIntOrNull() ?: 0
                        val name = parts[2].trim()
                        if (no > 0) bookChapters[key] = BookInfo(no, name)
                    }
                }
                if (count > 0) {
                    levelNames[BOOK_ZJ] = BOOK_ZJ_NAME
                    levelCounts[BOOK_ZJ] = count
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 该词在整本书词表（雅思词汇真经）里的章节；不在词表里返回 null */
    fun bookInfoOf(word: String): BookInfo? = bookChapters[word.trim().lowercase()]

    /** 查询某个词所属的所有级别 */
    fun levelsOf(word: String): List<String> =
        index[word.trim().lowercase()] ?: emptyList()

    /**
     * 决定该词用哪个级别着色；返回 null 表示不高亮。
     *
     * @param skipBasic 忽略「基础词」：勾了雅思/托福/考研等较高级别时，不再高亮同时属于
     *   高中/四级/六级的词。
     *   levels.db 的雅思词表是按「考雅思要掌握的词汇」收的，实测 3427 词里 82.7% 与更基础的级别
     *   重叠（四级 42.4%、六级 32.6%、考研 64.5%、高中 25.3%），只勾雅思时正文染出来的全是
     *   economic / unlikely / global 这类四六级词，「纯雅思词」只有 175 个（5.1%）。
     *   开启后只留下真正有难度的词（dearth / delirium / dormant / asylum…），实测外刊正文
     *   雅思高亮占比 3.06% → 0.44%。
     *   **只勾高中/四级/六级时不生效**：这三档本身就是基础档，否则勾高中会连自己词表里的词
     *   一起滤掉（实测正文高亮 59.5% → 1.05%、词种只剩 5%）。
     *   **整本书词表（[BOOK_ZJ] 雅思词汇真经）既不触发也不受此过滤影响**：该书 3600+ 词
     *   绝大多数与基础档重叠，参与过滤等于把整本书滤掉。
     */
    fun matchLevel(
        word: String,
        enabledLevels: Set<String>,
        skipBasic: Boolean = false
    ): String? {
        if (enabledLevels.isEmpty()) return null
        val list = index[word.trim().lowercase()] ?: return null
        // 该词属于某个**已勾选**的整本书词表时，一律不过滤：
        // 「忽略基础词」是为了让雅思/托福词表别被四六级词淹没，而真经是「这本书里的词」，
        // 勾了就该看到（真经 84.3% 的词与基础档重叠，参与过滤等于整本滤空）。
        val inEnabledBook = list.any { it in BOOK_LEVELS && it in enabledLevels }
        if (!inEnabledBook && skipBasic &&
            enabledLevels.any { it !in BASIC_LEVELS && it !in BOOK_LEVELS } &&
            list.any { it in BASIC_LEVELS && it !in enabledLevels }
        ) {
            return null
        }
        // 多级别词取「最容易」的那个：unlikely(CET4+IELTS) 同时勾了四级和雅思时，
        // 结果是四级（绿色），而不是看谁先在词库里出现（旧实现按索引顺序取，结果不确定）
        return list.filter { it in enabledLevels }.minByOrNull { rankOf(it) }
    }

    /** 该词是否属于指定级别集合中的任一级别 */
    fun matches(word: String, enabledLevels: Set<String>): Boolean {
        val list = index[word.trim().lowercase()] ?: return false
        for (lv in list) if (lv in enabledLevels) return true
        return false
    }

    /** 取主级别（用于查词弹层展示）：多级别词取最容易的级别 */
    fun primaryLevel(word: String): String? =
        levelsOf(word).minByOrNull { rankOf(it) }

    /** 全部级别配置（默认全关） */
    fun defaultConfigs(): List<LevelConfig> = levelNames.map { (k, v) ->
        LevelConfig(
            level = k,
            displayName = v,
            enabled = false,
            color = DEFAULT_COLORS[k] ?: 0xFF1E6F5C.toInt()
        )
    }

    /** 某级别对应的颜色 */
    fun colorOf(level: String): Int = DEFAULT_COLORS[level] ?: 0xFF1E6F5C.toInt()
}
