package com.lreader.export

import com.lreader.analysis.WordAnalysis
import com.lreader.model.VocabWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生词本导出（独立模块）。
 *
 * 只依赖 [VocabWord] 与 [WordAnalysis]，不直接接触 SQLite，
 * 因此导出格式的演进与数据库表结构解耦：以后换存储引擎也只需改 [com.lreader.data.VocabRepository]。
 * 表头/字段名一旦发布视为对外契约，追加字段可以，改名需谨慎。
 */
object VocabExporter {

    enum class Format(val ext: String, val mime: String, val label: String) {
        CSV("csv", "text/csv", "CSV（Excel 可直接打开）"),
        JSON("json", "application/json", "JSON（含全部 SM-2 字段）")
    }

    /** 建议文件名，如 lreader_vocab_20260922.csv */
    fun fileName(format: Format): String {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "lreader_vocab_$stamp.${format.ext}"
    }

    fun render(words: List<VocabWord>, format: Format): String = when (format) {
        Format.CSV -> toCsv(words)
        Format.JSON -> toJson(words)
    }

    /** 落盘到指定目录并返回文件；目录不存在时自动创建。 */
    fun writeTo(dir: File, words: List<VocabWord>, format: Format): File {
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, fileName(format))
        f.writeText(render(words, format), Charsets.UTF_8)
        return f
    }

    /**
     * CSV 带 UTF-8 BOM，避免 Excel 打开中文乱码。
     */
    fun toCsv(words: List<VocabWord>): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append(CSV_HEADER.joinToString(",")).append("\r\n")
        words.forEach { w ->
            val row = listOf(
                w.word,
                w.phonetic,
                WordAnalysis.partOfSpeech(w.meaning),
                WordAnalysis.cleanMeaning(w.word, w.meaning),
                w.sentence,
                w.sourceBook,
                w.level,
                fmtDate(w.addDate),
                w.repetition.toString(),
                w.interval.toString(),
                formatEf(w.easiness),
                w.grade.toString(),
                fmtDate(w.nextReview),
                // ---- 跨设备同步元数据（旧版 CSV 没有这三列，导入时按 word+sentence 补稳定 uid）----
                w.uid,
                w.updatedAt.toString(),
                if (w.deleted) "1" else "0"
            )
            sb.append(row.joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    fun toJson(words: List<VocabWord>): String {
        val root = JSONObject()
        root.put("app", "lreader")
        root.put("schema", SCHEMA_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("count", words.size)
        val arr = JSONArray()
        words.forEach { w ->
            arr.put(JSONObject().apply {
                put("word", w.word)
                put("phonetic", w.phonetic)
                put("pos", WordAnalysis.partOfSpeech(w.meaning))
                put("meaning", WordAnalysis.cleanMeaning(w.word, w.meaning))
                put("meaningRaw", w.meaning)
                put("sentence", w.sentence)
                put("sourceBook", w.sourceBook)
                put("level", w.level)
                put("addDate", w.addDate)
                put("repetition", w.repetition)
                put("intervalDays", w.interval)
                put("easiness", w.easiness)
                put("grade", w.grade)
                put("nextReview", w.nextReview)
                put("uid", w.uid)
                put("updatedAt", w.updatedAt)
                put("deleted", w.deleted)
            })
        }
        root.put("words", arr)
        return root.toString(2)
    }

    // ------------------------------------------------------------------
    // 导入 / 同步：CSV → VocabWord
    // ------------------------------------------------------------------

    /**
     * 解析 CSV：**兼容旧版 13 列**，按表头名取列（不依赖列顺序），未知列自动忽略。
     *
     * 缺少 `uid` 时用 `word + sentence` 算出**稳定 uid** —— 两台设备导入同一份旧 CSV
     * 会得到相同的 uid，合并时不会重复成两条。
     */
    fun parseCsv(text: String): List<VocabWord> {
        val rows = splitCsv(text.removePrefix("\uFEFF"))
        if (rows.size < 2) return emptyList()
        val idx = HashMap<String, Int>()
        rows.first().forEachIndexed { i, name ->
            val key = name.trim().lowercase()
            if (key.isNotEmpty()) idx.putIfAbsent(key, i)
        }

        fun col(cols: List<String>, name: String): String =
            idx[name]?.let { cols.getOrNull(it)?.trim().orEmpty() } ?: ""

        val out = ArrayList<VocabWord>(rows.size - 1)
        rows.drop(1).forEach { cols ->
            val word = col(cols, "word")
            if (word.isEmpty()) return@forEach
            val sentence = col(cols, "sentence")
            out.add(
                VocabWord(
                    word = word,
                    meaning = col(cols, "meaning"),
                    phonetic = col(cols, "phonetic"),
                    sentence = sentence,
                    sourceBook = col(cols, "source_book"),
                    level = col(cols, "level"),
                    addDate = parseDateOr(col(cols, "add_date"), System.currentTimeMillis()),
                    repetition = col(cols, "repetition").toIntOrNull() ?: 0,
                    interval = col(cols, "interval_days").toIntOrNull() ?: 0,
                    easiness = col(cols, "easiness").toDoubleOrNull() ?: 2.5,
                    grade = col(cols, "grade").toIntOrNull() ?: 0,
                    nextReview = parseDateOr(col(cols, "next_review"), System.currentTimeMillis()),
                    uid = col(cols, "uid").ifBlank { stableUid(word, sentence) },
                    updatedAt = col(cols, "updated_at").toLongOrNull()
                        ?: parseDateOr(col(cols, "add_date"), 0L),
                    deleted = col(cols, "deleted") == "1"
                )
            )
        }
        return out
    }

    /** 旧版 CSV 的日期是 `yyyy-MM-dd HH:mm`，同步列是毫秒时间戳；都解析不出来时用 [fallback] */
    private fun parseDateOr(raw: String, fallback: Long): Long {
        if (raw.isBlank()) return fallback
        raw.toLongOrNull()?.let { return it }
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(raw)?.time ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /** 旧 CSV 没有 uid 时的稳定替代：同一个 (word, sentence) 在任何设备上算出的 uid 都相同 */
    private fun stableUid(word: String, sentence: String): String {
        val h = java.security.MessageDigest.getInstance("MD5")
            .digest("$word|$sentence".toByteArray(Charsets.UTF_8))
        return h.joinToString("") { "%02x".format(it) }
    }

    /** 最小 CSV 解析：支持引号包裹、`""` 转义、字段内逗号与换行、CRLF */
    private fun splitCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        sb.append('"')
                        i++
                    }

                    c == '"' -> inQuotes = false
                    else -> sb.append(c)
                }

                c == '"' -> inQuotes = true
                c == ',' -> {
                    row.add(sb.toString())
                    sb.setLength(0)
                }

                c == '\r' -> Unit
                c == '\n' -> {
                    row.add(sb.toString())
                    sb.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = ArrayList()
                }

                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) {
            row.add(sb.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }

    private fun formatEf(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun csvCell(raw: String): String {
        val v = raw.replace("\r", " ").replace("\n", " ")
        return "\"" + v.replace("\"", "\"\"") + "\""
    }

    private val CSV_HEADER = listOf(
        "word", "phonetic", "pos", "meaning", "sentence", "source_book", "level",
        "add_date", "repetition", "interval_days", "easiness", "grade", "next_review",
        "uid", "updated_at", "deleted"
    )

    /** 导出格式版本；2 = 追加跨设备同步列 uid / updated_at / deleted */
    const val SCHEMA_VERSION = 2
}
