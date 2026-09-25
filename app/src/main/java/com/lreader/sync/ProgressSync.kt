package com.lreader.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * 阅读进度：一本书一条记录（章节号 + 章内百分比）。
 *
 * 跨设备主键用**书名**而不是 `Book.id`：后者是本机文件路径的编码，
 * 两台设备导入同一本书时并不相同。跨设备也不传页码 —— 页码依赖字号与屏幕，没有意义。
 */
data class ProgressEntry(
    val key: String,
    val title: String,
    val chapterIndex: Int,
    val percent: Float,
    val updatedAt: Long
)

object ProgressSync {

    fun pathOf(syncId: String): String = "users/$syncId/progress.json.enc"

    /** 书名 → 跨设备稳定的 key（去空格、大小写无关） */
    fun keyOf(title: String): String =
        title.trim().lowercase().replace(Regex("\\s+"), " ")

    fun toJson(items: List<ProgressEntry>): String {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("key", e.key)
                    put("title", e.title)
                    put("chapter", e.chapterIndex)
                    put("percent", e.percent.toDouble())
                    put("updatedAt", e.updatedAt)
                }
            )
        }
        return JSONObject().apply {
            put("schema", 1)
            put("books", arr)
        }.toString(2)
    }

    /** 解析失败（或格式演进）一律返回空表，不影响其它同步内容 */
    fun parse(json: String): List<ProgressEntry> = try {
        val arr = JSONObject(json).optJSONArray("books") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key")
            if (key.isBlank()) {
                null
            } else {
                ProgressEntry(
                    key = key,
                    title = o.optString("title"),
                    chapterIndex = o.optInt("chapter", 0),
                    percent = o.optDouble("percent", 0.0).toFloat(),
                    updatedAt = o.optLong("updatedAt", 0L)
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
