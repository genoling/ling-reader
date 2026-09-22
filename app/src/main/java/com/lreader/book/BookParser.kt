package com.lreader.book

import com.lreader.model.Book
import com.lreader.model.BookFormat
import com.lreader.model.Chapter
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 书籍解析：TXT / EPUB / FB2 / HTML。
 */
object BookParser {

    fun makeBook(file: File): Book {
        val fmt = BookFormat.fromFileName(file.name)
        return Book(
            id = file.absolutePath,
            title = file.nameWithoutExtension,
            filePath = file.absolutePath,
            format = fmt
        )
    }

    fun loadChapters(book: Book, maxCharsPerChapter: Int = 8000): List<Chapter> {
        val file = File(book.filePath)
        if (!file.exists()) return emptyList()
        return when (book.format) {
            BookFormat.TXT -> loadTxt(file, maxCharsPerChapter)
            BookFormat.EPUB -> loadEpub(file)
            BookFormat.FB2 -> loadFb2(file)
            BookFormat.HTML -> loadHtmlFile(file)
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------
    // TXT
    // ------------------------------------------------------------------

    private fun loadTxt(file: File, maxChars: Int): List<Chapter> {
        val text = try {
            readTextAutoEncoding(file)
        } catch (e: Exception) {
            return emptyList()
        }
        if (text.isEmpty()) return emptyList()

        val chapters = ArrayList<Chapter>()
        val chapterRegex = Regex(
            "(?m)^\\s*(第[零一二三四五六七八九十百千0-9]+[章节回卷].*|Chapter\\s+\\d+.*|CHAPTER\\s+\\d+.*)$"
        )
        val matches = chapterRegex.findAll(text).toList()

        if (matches.size >= 3) {
            matches.forEachIndexed { i, m ->
                val start = m.range.first
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
                val content = text.substring(start, end).trim()
                if (content.isNotEmpty()) {
                    val title = m.value.trim().take(50).ifBlank { "第 ${i + 1} 章" }
                    chapters.add(Chapter(i, title, content))
                }
            }
        } else {
            var idx = 0
            var pos = 0
            while (pos < text.length) {
                val end = minOf(pos + maxChars, text.length)
                val content = text.substring(pos, end)
                val title = content.lineSequence()
                    .firstOrNull { it.isNotBlank() }?.trim()?.take(40)
                    ?: "第 ${idx + 1} 节"
                chapters.add(Chapter(idx, title, content))
                idx++
                pos = end
            }
        }
        return chapters
    }

    fun readTextAutoEncoding(file: File): String {
        val bytes = file.readBytes()
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        return if (bad > utf8.length / 1000 + 5) {
            try {
                String(bytes, charset("GBK"))
            } catch (e: Exception) {
                utf8
            }
        } else {
            utf8
        }
    }

    // ------------------------------------------------------------------
    // EPUB
    // ------------------------------------------------------------------

    private fun loadEpub(file: File): List<Chapter> {
        val chapters = ArrayList<Chapter>()
        try {
            ZipFile(file).use { zip ->
                val opfPath = findOpfPath(zip) ?: return emptyList()
                val opfContent = zip.getInputStream(zip.getEntry(opfPath))
                    .use { it.readBytes().toString(Charsets.UTF_8) }

                // 书名
                val titleMeta = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL)
                    .find(opfContent)?.groupValues?.get(1)?.trim()

                val manifest = parseManifest(opfContent)
                val spine = parseSpine(opfContent)
                val opfDir = opfPath.substringBeforeLast('/', "")

                var index = 0
                for (idref in spine) {
                    val href = manifest[idref] ?: continue
                    val entryPath = joinPath(opfDir, href)
                    val entry = zip.getEntry(entryPath) ?: continue
                    val html = zip.getInputStream(entry)
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                    val text = htmlToText(html)
                    if (text.isBlank()) continue
                    // 章节内没有标题时，用「书名 · 第 N 章」兜底
                    val fallback = titleMeta?.takeIf { it.isNotBlank() }
                        ?.let { "$it · 第 ${index + 1} 章" }
                        ?: "第 ${index + 1} 章"
                    val title = extractTitle(html).ifBlank { fallback }
                    chapters.add(Chapter(index, title, text))
                    index++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return chapters
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        val xml = zip.getInputStream(container).use { it.readBytes().toString(Charsets.UTF_8) }
        return Regex("""full-path\s*=\s*"([^"]+)"""").find(xml)?.groupValues?.get(1)
    }

    private fun parseManifest(opf: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in Regex("""<item\b[^>]*>""").findAll(opf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            val media = attr(tag, "media-type") ?: ""
            if (media.contains("xhtml") || media.contains("html") ||
                href.endsWith(".xhtml") || href.endsWith(".html") || href.endsWith(".htm")
            ) map[id] = href
        }
        return map
    }

    private fun parseSpine(opf: String): List<String> {
        val list = ArrayList<String>()
        for (m in Regex("""<itemref\b[^>]*>""").findAll(opf)) {
            attr(m.value, "idref")?.let { list.add(it) }
        }
        return list
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun joinPath(dir: String, href: String): String {
        val cleanHref = href.substringBefore('#')
        return if (dir.isEmpty()) cleanHref else "$dir/$cleanHref"
    }

    // ------------------------------------------------------------------
    // FB2
    // ------------------------------------------------------------------

    private fun loadFb2(file: File): List<Chapter> {
        val xml = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return emptyList()
        }
        val chapters = ArrayList<Chapter>()
        val sections = Regex("""<section\b[^>]*>(.*?)</section>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).toList()

        if (sections.isEmpty()) {
            val text = htmlToText(xml)
            if (text.isNotBlank()) chapters.add(Chapter(0, "正文", text))
            return chapters
        }

        sections.forEachIndexed { i, m ->
            val body = m.groupValues[1]
            val text = htmlToText(body)
            if (text.isBlank()) return@forEachIndexed
            val title = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim()
                ?.take(50)
                ?.ifBlank { null } ?: "第 ${i + 1} 节"
            chapters.add(Chapter(chapters.size, title, text))
        }
        return chapters
    }

    // ------------------------------------------------------------------
    // HTML
    // ------------------------------------------------------------------

    private fun loadHtmlFile(file: File): List<Chapter> {
        val html = try {
            readTextAutoEncoding(file)
        } catch (e: Exception) {
            return emptyList()
        }
        val text = htmlToText(html)
        if (text.isBlank()) return emptyList()
        val title = extractTitle(html).ifBlank { file.nameWithoutExtension }
        return listOf(Chapter(0, title, text))
    }

    // ------------------------------------------------------------------
    // HTML -> 纯文本
    // ------------------------------------------------------------------

    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p>"), "\n\n")
        s = s.replace(Regex("(?i)</div>"), "\n")
        s = s.replace(Regex("(?i)</h[1-6]>"), "\n\n")
        s = s.replace(Regex("(?i)<title>(.*?)</title>"), "\n\n$1\n\n")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = s.replace("&nbsp;", " ")
        s = s.replace("&lt;", "<").replace("&gt;", ">")
        s = s.replace("&amp;", "&").replace("&quot;", "\"")
        s = s.replace("&#39;", "'")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun extractTitle(html: String): String {
        Regex("(?is)<title>(.*?)</title>").find(html)?.let {
            return it.groupValues[1].trim()
        }
        Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)?.let {
            val t = it.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            if (t.isNotEmpty()) return t
        }
        return ""
    }
}
