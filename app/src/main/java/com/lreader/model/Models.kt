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
 * 一个章节
 */
data class Chapter(
    val index: Int,
    val title: String,
    val content: String
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
    val phonetic: String? = null
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
    val nextReview: Long = System.currentTimeMillis()
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
