package com.lreader.export

import com.lreader.model.VocabWord

/**
 * 生词本导入（独立模块，与 [VocabExporter] 对称）。
 *
 * 支持：
 *  - 本 App 导出的 CSV（带 BOM、字段用双引号包裹）
 *  - 其他来源的 CSV（按表头名匹配，顺序可不同、可缺列）
 *  - 只有一列单词的纯词表（每行一个词）
 *
 * 只依赖 CSV 文本，不接触数据库；去重与落库由 [com.lreader.data.VocabRepository.importAll] 负责。
 */
object VocabImporter {

    data class Result(val words: List<VocabWord>, val error: String? = null)

    /** 表头别名 → 标准字段名 */
    private val ALIASES = mapOf(
        "word" to "word", "单词" to "word", "词" to "word",
        "phonetic" to "phonetic", "音标" to "phonetic", "ipa" to "phonetic",
        "pos" to "pos", "词性" to "pos", "part_of_speech" to "pos",
        "meaning" to "meaning", "释义" to "meaning", "translation" to "meaning", "含义" to "meaning",
        "sentence" to "sentence", "例句" to "sentence", "原句" to "sentence", "context" to "sentence",
        "source_book" to "source_book", "sourcebook" to "source_book", "来源" to "source_book", "book" to "source_book",
        "level" to "level", "级别" to "level",
        "add_date" to "add_date", "adddate" to "add_date", "添加时间" to "add_date",
        "repetition" to "repetition", "复习次数" to "repetition",
        "interval_days" to "interval_days", "interval" to "interval_days", "间隔" to "interval_days",
        "easiness" to "easiness", "ef" to "easiness", "难度系数" to "easiness",
        "grade" to "grade", "评分" to "grade",
        "next_review" to "next_review", "nextreview" to "next_review", "下次复习" to "next_review"
    )

    fun fromCsv(text: String): Result {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return Result(emptyList(), "文件是空的")

        // 表头行：能识别出至少一个已知字段才算表头，否则整份当成「每行一个单词」
        val header = rows.first().map { it.trim().lowercase() }
        val mapped = header.map { ALIASES[it] }
        val hasHeader = mapped.count { it != null } >= 2

        val col = HashMap<String, Int>()
        val dataRows: List<List<String>>
        if (hasHeader) {
            mapped.forEachIndexed { i, name -> if (name != null && name !in col) col[name] = i }
            dataRows = rows.drop(1)
        } else {
            col["word"] = 0
            dataRows = rows
        }
        if ("word" !in col) return Result(emptyList(), "找不到单词列（表头需包含 word/单词）")

        val out = ArrayList<VocabWord>(dataRows.size)
        dataRows.forEach { r ->
            fun cell(name: String): String = col[name]?.let { r.getOrNull(it) }?.trim().orEmpty()
            val word = cell("word").trim()
            if (word.isEmpty()) return@forEach

            val phonetic = cell("phonetic")
            val pos = cell("pos")
            val clean = cell("meaning")
            // 还原成 App 内部使用的释义格式：word [音标] 词性 中文释义
            val meaning = buildString {
                append(word)
                if (phonetic.isNotBlank()) append(" [").append(phonetic).append(']')
                if (pos.isNotBlank()) append(' ').append(pos)
                if (clean.isNotBlank()) append(' ').append(clean)
            }

            out.add(
                VocabWord(
                    word = word,
                    meaning = meaning,
                    phonetic = phonetic,
                    sentence = cell("sentence"),
                    sourceBook = cell("source_book"),
                    level = cell("level"),
                    addDate = cell("add_date").toLongOrNull() ?: System.currentTimeMillis(),
                    repetition = cell("repetition").toIntOrNull() ?: 0,
                    interval = cell("interval_days").toIntOrNull() ?: 0,
                    easiness = cell("easiness").toDoubleOrNull() ?: 2.5,
                    grade = cell("grade").toIntOrNull() ?: 0,
                    nextReview = cell("next_review").toLongOrNull() ?: System.currentTimeMillis()
                )
            )
        }
        if (out.isEmpty()) return Result(emptyList(), "没有解析到有效单词")
        return Result(out)
    }

    /** 标准 CSV 解析：支持双引号包裹、字段内逗号/换行、"" 转义、BOM */
    private fun parseCsv(raw: String): List<List<String>> {
        val s = raw.removePrefix("\uFEFF")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < s.length && s[i + 1] == '"' -> {
                        cell.append('"'); i++
                    }

                    c == '"' -> inQuotes = false
                    else -> cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row.add(cell.toString()); cell.clear()
                    }

                    '\r' -> Unit
                    '\n' -> {
                        row.add(cell.toString()); cell.clear()
                        if (row.any { it.isNotBlank() }) rows.add(row)
                        row = ArrayList()
                    }

                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }
}
