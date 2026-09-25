package com.lreader.model

/**
 * 书架中的一本书
 */
data class Book(
    val id: String,
    val title: String,
    val filePath: String,
    val format: BookFormat,
    val coverPath: String? = null,
    val lastChapterIndex: Int = 0,
    val lastScrollY: Int = 0,
    /**
     * 章内进度百分比（0f~1f）。
     * 跨设备同步阅读进度只能用它 —— 页码依赖字号与屏幕，换设备后没有意义。
     */
    val lastPercent: Float = 0f,
    /** 进度最后修改时间（毫秒），跨设备合并时取更新的那条 */
    val progressUpdatedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis()
)

enum class BookFormat {
    TXT, EPUB, FB2, HTML, UNKNOWN;

    companion object {
        fun fromFileName(name: String): BookFormat =
            when (name.substringAfterLast('.', "").lowercase()) {
                "txt" -> TXT
                "epub" -> EPUB
                "fb2" -> FB2
                "html", "htm", "xhtml" -> HTML
                else -> UNKNOWN
            }

        /** 文件选择器支持的 MIME */
        val mimeTypes = arrayOf(
            "text/plain", "application/epub+zip", "application/xhtml+xml",
            "text/html", "application/octet-stream", "*/*"
        )
    }
}

/**
 * 章节里的一张插图（目前只有 EPUB 会用到）。
 *
 * 与 [Chapter.content] 中的 `\uFFFC` 占位符**按顺序一一对应**；
 * 阅读页把它渲染成一页图片（见 `ReaderPage.Image`）。
 */
data class ChapterImage(
    /** 已释放到本地的图片路径；提取失败时为空串（正文只显示「［图片］」占位） */
    val path: String,
    /** 原始 html 的 alt 文本 */
    val alt: String = ""
)

/**
 * 一个章节
 */
data class Chapter(
    val index: Int,
    val title: String,
    val content: String,
    /** 插图列表，与 [content] 中的 `\uFFFC` 占位符按顺序对应（无图时为空） */
    val images: List<ChapterImage> = emptyList(),
    /**
     * 目录（EPUB `toc.ncx` / `nav.xhtml`）里的标题。
     * 电子书自带的 `<title>` 经常被截断（如 `The selfish case for helping Ukraine has`），
     * 目录标题才是完整的文章名。
     */
    val tocTitle: String? = null,
    /** 目录层级：0 = 顶层栏目（The world this week / Leaders…），1 = 文章 */
    val tocLevel: Int = 0,
    /** 在压缩包内的文件路径（EPUB 用；用于把目录锚点映射回章节号） */
    val sourcePath: String? = null
)

/**
 * 章节目录的一项（来自 EPUB 的 `toc.ncx` / `nav.xhtml`）。
 *
 * @param chapterIndex 对应 [Chapter.index]（spine 顺序），点击直接跳章
 * @param level 层级：0 = 顶层栏目（The world this week / Leaders…），1 = 具体文章
 */
data class TocEntry(
    val title: String,
    val chapterIndex: Int,
    val level: Int = 0
)

/**
 * 词典查询结果
 */
data class DictEntry(
    val word: String,
    val html: String,
    val dictionaryName: String,
    /** 该词所属的分级（CET4/CET6/…），可能为空 */
    val level: String? = null,
    val levelName: String? = null,
    val phonetic: String? = null,
    /**
     * 释义取自哪个词条。查 `receptiveness` 时词典只收了 `receptive`，
     * 这里即为 `receptive`；UI 必须明说「未收录，以下为词根释义」，
     * 否则用户会以为查错了词。
     */
    val formOf: String? = null
)

/**
 * 生词本词条
 */
data class VocabWord(
    val id: Long = 0,
    val word: String,
    val meaning: String,
    val phonetic: String = "",
    /** 出现该词的原文句子 */
    val sentence: String = "",
    /** 来源书名 */
    val sourceBook: String = "",
    val level: String = "",
    val addDate: Long = System.currentTimeMillis(),

    // SM-2 复习参数
    val repetition: Int = 0,
    val interval: Int = 0,
    val easiness: Double = 2.5,
    val grade: Int = 0,
    val nextReview: Long = System.currentTimeMillis(),

    // ---- 跨设备同步元数据（见 sync 包）----
    /**
     * 跨设备唯一 id。`id` 是各设备自己的自增主键，两台设备会撞车，
     * 合并时一律以 [uid] 为主键。老数据升级时自动补 UUID。
     */
    val uid: String = "",
    /** 最后修改时间（毫秒）；合并时同一 [uid] 取更晚的一条 */
    val updatedAt: Long = 0,
    /**
     * 软删除。删除必须是"标记 + 同步"，否则 A 设备删掉的词
     * 会被 B 设备的下一次上传又同步回来。
     */
    val deleted: Boolean = false
)

/**
 * 复习结果评级
 */
enum class ReviewGrade(val value: Int) {
    FORGOT(0),      // 不认识
    VAGUE(3),       // 模糊
    KNOWN(5)        // 认识
}

/**
 * 翻译结果
 */
data class TranslationResult(
    val sourceText: String,
    val translatedText: String,
    val engine: String,
    val success: Boolean,
    val errorMessage: String? = null
)

/**
 * 分级词汇配置（阅读时高亮哪些级别）
 */
data class LevelConfig(
    val level: String,
    val displayName: String,
    val enabled: Boolean,
    val color: Int
)
