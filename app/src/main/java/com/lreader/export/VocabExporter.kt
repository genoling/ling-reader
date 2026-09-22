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
                fmtDate(w.nextReview)
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
            })
        }
        root.put("words", arr)
        return root.toString(2)
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
        "add_date", "repetition", "interval_days", "easiness", "grade", "next_review"
    )

    /** 导出格式版本；追加字段时 +1，删除/改名需另行说明 */
    const val SCHEMA_VERSION = 1
}
