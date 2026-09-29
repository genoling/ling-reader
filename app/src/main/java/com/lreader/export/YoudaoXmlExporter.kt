package com.lreader.export

import com.lreader.analysis.WordAnalysis
import com.lreader.model.VocabWord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「有道单词本 XML」导出（独立模块，与 [VocabExporter] 平行）。
 *
 * 格式来自实测：这份结构在有道翻译电脑版（11.3.22.0）里**导入成功、显示正常**，
 * 因此字段不要再改 ——
 * ```
 * <?xml version="1.0" encoding="UTF-8"?>
 * <wordbook>
 *   <item>
 *     <word><![CDATA[apple]]></word>
 *     <trans><![CDATA[n. 苹果]]></trans>
 *     <phonetic><![CDATA[ˈæpl]]></phonetic>
 *     <tags><![CDATA[LingReader]]></tags>
 *     <progress>1</progress>
 *   </item>
 * </wordbook>
 * ```
 * `tags` 既是分组名也是归属：导入后这些词会落在同名分组里。
 *
 * 导出的内容是 **本地生词本 ∪ 有道账号生词本**：两边都有的词按单词去重只出现一次，
 * 这样在 PC 端导入一次就能同时拿到两处的全部词汇。
 */
object YoudaoXmlExporter {

    /** 导入后在有道里的分组名（= XML 的 `tags`） */
    const val GROUP = "LingReader"

    /**
     * 有道账号里的词条，由 [com.lreader.data.YoudaoWordbook.allWords] 拉取。
     * 云端只有这三个字段（没有原句 / 复习参数），生成 XML 够用了。
     */
    data class CloudWord(val word: String, val phonetic: String, val trans: String)

    /**
     * 合并结果。
     *
     * @param total 去重后的总词数（= 写进 XML 的词条数）
     * @param localCount 来自本地生词本的词数
     * @param cloudCount 从有道账号拉到的词数（未去重）
     * @param fromCloudOnly 本地没有、只有云端才有的词数
     */
    data class Merged(
        val xml: String,
        val total: Int,
        val localCount: Int,
        val cloudCount: Int,
        val fromCloudOnly: Int
    )

    /** 导出文件名，如 `lreader_youdao_20260929.xml` */
    fun fileName(): String =
        "lreader_youdao_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".xml"

    /** 落盘到指定目录（不存在时自动创建），返回写入的文件 */
    fun writeTo(dir: File, xml: String, name: String = fileName()): File {
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        f.writeText(xml, Charsets.UTF_8)
        return f
    }

    /**
     * 合并两侧词表并生成 XML。
     *
     * 去重键是**小写单词**：本地词优先（用户在阅读里收藏、释义与音标更完整），
     * 云端独有的词随后补上（音标 / 释义用有道自己那份）。词序 = 本地在前、云端补在后。
     */
    fun merge(local: List<VocabWord>, cloud: List<CloudWord>): Merged {
        val seen = HashSet<String>(local.size + cloud.size)
        val rows = ArrayList<Row>(local.size + cloud.size)

        var localCount = 0
        local.forEach { w ->
            val word = w.word.trim()
            val key = word.lowercase()
            if (key.isEmpty() || !seen.add(key)) return@forEach
            localCount++
            rows.add(Row(word, transOfMeaning(w.meaning), w.phonetic.trim()))
        }

        var fromCloudOnly = 0
        cloud.forEach { c ->
            val word = c.word.trim()
            val key = word.lowercase()
            if (key.isEmpty() || !seen.add(key)) return@forEach
            fromCloudOnly++
            rows.add(Row(word, linesOf(c.trans), c.phonetic.trim()))
        }

        return Merged(
            xml = buildXml(rows),
            total = rows.size,
            localCount = localCount,
            cloudCount = cloud.size,
            fromCloudOnly = fromCloudOnly
        )
    }

    /** 一行 `<item>`（就是 XML 里的字段三元组） */
    private data class Row(val word: String, val trans: String, val phonetic: String)

    private fun buildXml(rows: List<Row>): String {
        val sb = StringBuilder(rows.size * 200 + 64)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<wordbook>\n")
        rows.forEach { r ->
            sb.append("<item>\n")
                .append("<word>").append(cdata(r.word)).append("</word>\n")
                .append("<trans>").append(cdata(r.trans)).append("</trans>\n")
                .append("<phonetic>").append(cdata(r.phonetic)).append("</phonetic>\n")
                .append("<tags>").append(cdata(GROUP)).append("</tags>\n")
                .append("<progress>1</progress>\n")
                .append("</item>\n")
        }
        sb.append("</wordbook>\n")
        return sb.toString()
    }

    /** CDATA 包裹；文本里出现 `]]>` 时按 XML 规范拆成两段 CDATA */
    private fun cdata(s: String): String =
        "<![CDATA[" + s.replace("]]>", "]]]]><![CDATA[>") + "]]>"

    /**
     * 生词本里存的是 `word [音标] n. 释义` 这类扁平文本，转成有道惯用的「词性 + 释义」多行格式。
     * 解析不出来时退回原文，保证绝不会导出空释义。
     */
    private fun transOfMeaning(meaning: String): String {
        val out = WordAnalysis.splitByPos(meaning)
            .flatMap { g -> g.defs.map { d -> if (g.pos.isBlank()) d else "${g.pos} $d" } }
            .flatMap { it.split('\n') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (out.isEmpty()) linesOf(meaning) else out.joinToString("\n")
    }

    /** 规范化一段（可能多行的）释义：去 CR、逐行 trim、丢空行 */
    private fun linesOf(raw: String): String =
        raw.replace("\r", "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
}
