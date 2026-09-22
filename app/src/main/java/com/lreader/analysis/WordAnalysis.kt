package com.lreader.analysis

/**
 * 单词文本分析（纯 Kotlin，不依赖 Android / 数据库）。
 *
 * 单独成模块的目的：生词本列表、复习页、阅读页都需要「词性 / 纯释义 / 原句切分」，
 * 把这些逻辑集中到无副作用的纯函数里，UI 层与数据层解耦，后续替换实现或做单元测试都互不影响。
 */
/**
 * 一个词性下的所有释义。
 *
 * 复习页「详细解析」要按词性分层展示（参考市面背单词 App 的格式），
 * 因此需要把扁平的释义文本还原成「词性 → 释义列表」结构。
 */
data class PosDefs(
    /** 词性标记，如 `adv.` / `adj.` / `短语:`；取不到时为空串 */
    val pos: String,
    val defs: List<String>
)

object WordAnalysis {

    /** 词性缩略（n. / vt. / adj. …），允许 "vt. & vi." 这类组合 */
    private val POS_REGEX = Regex(
        "(?i)(?<![a-z])((?:n|v|vt|vi|adj|adv|prep|conj|pron|art|num|int|interj|aux)\\." +
            "(?:\\s*(?:&|and|/)\\s*(?:n|v|vt|vi|adj|adv)\\.)*)"
    )

    /** 词性出现在释义开头附近，超出该窗口不再认为是词性 */
    private const val POS_SEARCH_WINDOW = 160

    private val OPEN_BRACKETS = mapOf('[' to ']', '（' to '）', '(' to ')', '【' to '】')

    /** 压缩所有空白 */
    fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /**
     * 从释义文本中提取词性，取不到返回空串。
     *
     * 生词本里存的释义形如 `botanist ['bɒtənɪst] n. 植物学家，专门研究植物的人`，
     * 因此不能只看开头，要在前 [POS_SEARCH_WINDOW] 个字符里找第一个词性标记。
     */
    fun partOfSpeech(meaning: String): String {
        val head = normalize(meaning).take(POS_SEARCH_WINDOW)
        if (head.isEmpty()) return ""
        return POS_REGEX.find(head)?.groupValues?.get(1)?.trim() ?: ""
    }

    /** 词性缩略 → 中文（`adv.` → 副词）。词典里用的是英文缩略，中文用户不一定认识 */
    private val POS_CN_MAP = mapOf(
        "n" to "名词", "v" to "动词", "vt" to "及物动词", "vi" to "不及物动词",
        "adj" to "形容词", "adv" to "副词", "prep" to "介词", "conj" to "连词",
        "pron" to "代词", "art" to "冠词", "num" to "数词", "aux" to "助动词",
        "int" to "感叹词", "interj" to "感叹词"
    )

    /** 释义串里出现的英文词性缩略（用于 [posLabel] 逐个替换） */
    private val POS_TOKEN = Regex(
        "(?i)\\b(interj|adj|adv|prep|conj|pron|art|num|aux|int|vt|vi|n|v)\\b"
    )

    /**
     * 词性标签转中文：`adv.` → `副词`，`vt. & vi.` → `及物动词/不及物动词`。
     * 已经是中文的（`短语:`）或认不出来的原样返回。
     */
    fun posLabel(pos: String): String {
        val s = pos.trim()
        if (s.isEmpty() || hasChinese(s)) return s
        val cn = POS_TOKEN.findAll(s).mapNotNull { POS_CN_MAP[it.groupValues[1].lowercase()] }.toList()
        return if (cn.isEmpty()) s else cn.distinct().joinToString("/")
    }

    /**
     * 变形词条里的说明文字，如
     * `该词为 simultaneous 的变形，中文释义参考 simultaneous（adj.）：`。
     * 列表只有两行，这种说明会把真正的中文释义挤没，展示前必须丢掉。
     */
    private val XREF_NOTE = Regex("(该词为|指的是|即)[^：:]{0,80}?(参考|释义取自|变形)[^：:]{0,80}?[：:]")

    /** 开头的 `abrupt的变形` / `abaci的复数` 这类只有词形、没有中文的说明 */
    private val CROSS_REF_PREFIX = Regex(
        "^[A-Za-z][A-Za-z'’\\-.]{0,40}?\\s*的\\s*" +
            "(变形|复数形式|复数|过去式|过去分词|现在分词|第三人称单数|比较级|最高级|过去式或过去分词)\\s*"
    )

    /** 释义里是否夹着「某某的变形…参考…」这类说明（生词本里的旧数据要不要重写，用它判断） */
    fun hasFormNote(meaning: String): Boolean =
        XREF_NOTE.containsMatchIn(meaning) || CROSS_REF_PREFIX.containsMatchIn(meaning.trim())

    /** 丢掉变形词条的说明文字与词形引用，只留中文释义 */
    private fun stripFormNote(s0: String): String {
        var s = s0
        XREF_NOTE.find(s)?.let { if (it.range.first <= 80) s = s.substring(it.range.last + 1).trimStart() }
        return CROSS_REF_PREFIX.replace(s, "").trimStart()
    }

    /**
     * 去掉「单词 / [音标] / 词性」前缀，只留中文释义，供列表简洁展示。
     *
     * 变形词条存下来可能是
     * `simultaneously [音标] adv. simultaneous的变形 该词为…参考…：adj. 同时…`，
     * 所以这里剥两轮：先剥词性，再剥变形说明（说明后面往往还跟着真正释义的词性）。
     */
    fun cleanMeaning(word: String, meaning: String): String {
        var s = normalize(meaning)
        if (s.isEmpty()) return s

        if (word.isNotBlank() && s.startsWith(word, ignoreCase = true)) {
            s = s.substring(word.length).trimStart()
        }
        s = dropLeadingBrackets(s)
        var prev: String
        do {
            prev = s
            // 可能有多段词性，如 "n. & vt. 解释"
            while (true) {
                val m = POS_REGEX.find(s)
                if (m == null || m.range.first > 0) break
                s = s.substring(m.range.last + 1).trimStart()
            }
            s = stripFormNote(s)
        } while (s != prev)
        return s.trimStart(':', '：', '-', '—', '·', ' ').trim()
    }

    private fun dropLeadingBrackets(s0: String): String {
        var s = s0
        while (s.isNotEmpty()) {
            val close = OPEN_BRACKETS[s[0]] ?: break
            val i = s.indexOf(close)
            if (i <= 0) break
            s = s.substring(i + 1).trimStart()
        }
        return s
    }

    /**
     * 把句子按目标单词切成若干片段，用于在复习页高亮原句中的该词。
     * @return (片段文本, 是否是目标单词)
     */
    // ---------------------------------------------------------------------
    // 词性分层解析（复习页「详细解析」用）
    // ---------------------------------------------------------------------

    /** 词典 HTML 里的词性标记：`<span class="pos wordGroup">adv.</span>`、`<span class="pos">短语:</span>` */
    private val POS_SPAN = Regex(
        "<span[^>]*class=\"pos[^\"]*\"[^>]*>(.*?)</span>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 词典 HTML 里的释义标记：`<span class="def wordGroup">作为替代；顶替</span>` */
    private val DEF_SPAN = Regex(
        "<span[^>]*class=\"def[^\"]*\"[^>]*>(.*?)</span>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 中文小标题（短语 / 习语 / 用法 …），等价于一个词性分组 */
    private val POS_CN = Regex("(短语|习语|词组|谚语|用法|辨析|同义词|反义词|例句|变体|比较)\\s*[:：]")

    /** `abound 的过去式` / `abaci 的复数` 这类「只见词形、没有中文」的交叉引用 */
    private val CROSS_REF = Regex(
        "^([A-Za-z][A-Za-z'’\\-.]{0,40}?)\\s*的\\s*" +
            "(变形|复数形式|复数|过去式|过去分词|现在分词|第三人称单数|比较级|最高级|过去式或过去分词)$"
    )

    /** `= Ashur` / `＝Chaldean` 这类「见某某」 */
    private val SEE_ALSO = Regex("^[=＝]\\s*([A-Za-z][A-Za-z'’\\-.]{0,40})$")

    private val HAS_CJK = Regex("[\\u4e00-\\u9fff]")

    /** 任意 HTML 标签 */
    private val TAG_REGEX = Regex("(?s)<[^>]+>")

    fun hasChinese(s: String): Boolean = HAS_CJK.containsMatchIn(s)

    private fun cleanDef(s: String): String =
        normalize(TAG_REGEX.replace(s, " ")).trim(':', '：', '-', '—', '·', ' ', ';', '；')

    /**
     * 从词典 HTML 解析出「词性 → 释义列表」，供分层展示。
     *
     * 21 世纪大英汉词典的结构形如：
     * ```
     * <h4>instead <span class="phonetic">[in'sted]</span></h4>
     * <ul><li><span class="pos">adv.</span><ul>
     *        <li><span class="def">作为替代；顶替</span></li>
     *        <li><span class="def">反而；却</span></li>
     *     </ul></li>
     *     <li><span class="pos">短语:</span><ul>
     *        <li><span class="phrase">instead of</span>代替；而不是</li>
     *     </ul></li></ul>
     * ```
     * 因此按 `pos` 出现的位置切片，片内所有 `def` 即为该词性的释义。
     */
    fun parseDefinitions(html: String): List<PosDefs> {
        if (html.isBlank()) return emptyList()
        // 丢掉 <head> 与 <h4> 标题（标题里是单词 + 音标，不属于释义）
        val content = html
            .substringAfter("</h4>", html)
            .replace(Regex("(?s)<head>.*?</head>"), " ")

        val posMatches = POS_SPAN.findAll(content).toList()
        if (posMatches.isEmpty()) {
            val defs = collectDefs(content)
            return if (defs.isEmpty()) emptyList() else listOf(PosDefs("", defs))
        }

        val out = ArrayList<PosDefs>(posMatches.size)
        posMatches.forEachIndexed { i, m ->
            val start = m.range.last + 1
            val end = if (i + 1 < posMatches.size) posMatches[i + 1].range.first else content.length
            val pos = cleanDef(m.groupValues[1]).let { if (it.isEmpty()) "" else withoutColon(it) }
            out.add(PosDefs(pos, collectDefs(content.substring(start, end))))
        }
        return out.filter { it.pos.isNotBlank() || it.defs.isNotEmpty() }
    }

    private fun withoutColon(s: String): String = s.trimEnd(':', '：').trim()

    private fun collectDefs(segment: String): List<String> {
        val spans = DEF_SPAN.findAll(segment)
            .map { cleanDef(it.groupValues[1]) }
            .filter { it.isNotEmpty() }
            .toList()
        // def span 里全是英文（如短语的 "instead of"）时，改用整段去标签文本
        if (spans.isNotEmpty() && spans.any { hasChinese(it) }) return spans
        val whole = cleanDef(segment)
        return if (whole.isNotEmpty()) listOf(whole) else spans
    }

    /**
     * 把扁平的释义文本（生词本里存的那串 `word [音标] adv. 释义…`）按词性切成若干层。
     *
     * 生词本里存的是 HTML 去标签后的文本，多个词性被空格连在一起，
     * 这里按词性标记重新切开，供复习页分层展示；解析失败时整段作为一层返回。
     */
    fun splitByPos(meaning: String): List<PosDefs> {
        val s = normalize(meaning)
        if (s.isEmpty()) return emptyList()

        // 词性标记：英文缩略 或 中文小标题
        data class Hit(val key: String, val start: Int, val end: Int)
        val hits = ArrayList<Hit>()
        POS_REGEX.findAll(s).forEach { hits.add(Hit(it.groupValues[1], it.range.first, it.range.last)) }
        POS_CN.findAll(s).forEach { hits.add(Hit(it.groupValues[1] + ":", it.range.first, it.range.last)) }
        hits.sortBy { it.start }

        if (hits.isEmpty()) {
            val t = cleanDef(s)
            return if (t.isEmpty()) emptyList() else listOf(PosDefs("", listOf(t)))
        }

        val out = ArrayList<PosDefs>(hits.size)
        hits.forEachIndexed { i, h ->
            val start = h.end + 1
            val end = if (i + 1 < hits.size) hits[i + 1].start else s.length
            if (start >= end) return@forEachIndexed
            val body = cleanDef(s.substring(start, end))
            if (body.isNotEmpty()) out.add(PosDefs(withoutColon(h.key), listOf(body)))
        }
        // 词性标记前的前缀（如单词本身）丢弃；若一个都没解析出来则整段兜底
        return out.ifEmpty {
            val t = cleanDef(s)
            if (t.isEmpty()) emptyList() else listOf(PosDefs("", listOf(t)))
        }
    }

    /**
     * 该词条是否为「只有词形、没有中文释义」的交叉引用（如 `abruptly → adv. abrupt的变形`）。
     * @return 需要参考的源词；不是交叉引用则返回 null
     */
    fun crossRefTarget(html: String): String? =
        crossRefTargetOf(parseDefinitions(html).flatMap { it.defs })

    /** 同 [crossRefTarget]，但输入是生词本里存的扁平释义文本 */
    fun crossRefTargetOfText(text: String): String? =
        crossRefTargetOf(splitByPos(text).flatMap { it.defs })

    /** 该条释义是否只是「某某的变形 / 见某某」，不含中文含义 */
    fun isCrossReference(def: String): Boolean =
        CROSS_REF.matches(def.trim()) || SEE_ALSO.matches(def.trim())

    /**
     * 从词典 HTML 生成一行式释义文本（保留词性），形如 `adv. 作为替代；顶替 adj. 相反的`。
     * 供写库使用：保证存进生词本的释义一定带中文，`partOfSpeech` / `cleanMeaning`
     * 也能照常从这串文本里取词性、去前缀。
     */
    fun meaningText(html: String): String =
        parseDefinitions(html)
            .flatMap { g ->
                g.defs.filter { !isCrossReference(it) }
                    .map { d -> if (g.pos.isBlank()) d else "${g.pos} $d" }
            }
            .joinToString(" ")
            .trim()

    private fun crossRefTargetOf(all: List<String>): String? {
        val defs = all.filter { it.isNotBlank() }
        if (defs.isEmpty()) return null
        // 只要已有真正的中文释义，就不需要补
        val hasRealMeaning = defs.any { d ->
            hasChinese(d) && !CROSS_REF.matches(d) && !SEE_ALSO.matches(d)
        }
        if (hasRealMeaning) return null
        for (d in defs) {
            CROSS_REF.find(d)?.let { return it.groupValues[1].trim() }
            SEE_ALSO.find(d)?.let { return it.groupValues[1].trim() }
        }
        return null
    }

    fun splitByWord(sentence: String, word: String): List<Pair<String, Boolean>> {
        if (sentence.isBlank()) return emptyList()
        if (word.isBlank()) return listOf(sentence to false)

        // 先精确匹配，再退化为「前缀 + 任意后缀」以覆盖复数/变形
        val exact = Regex("(?i)\\b${Regex.escape(word)}\\b")
        val loose = Regex("(?i)\\b${Regex.escape(word)}\\w*")
        val m = exact.find(sentence) ?: loose.find(sentence) ?: return listOf(sentence to false)

        val out = ArrayList<Pair<String, Boolean>>(3)
        if (m.range.first > 0) out.add(sentence.substring(0, m.range.first) to false)
        out.add(m.value to true)
        if (m.range.last + 1 < sentence.length) {
            out.add(sentence.substring(m.range.last + 1) to false)
        }
        return out
    }
}
