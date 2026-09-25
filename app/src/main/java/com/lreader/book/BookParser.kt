package com.lreader.book

import com.lreader.model.Book
import com.lreader.model.BookFormat
import com.lreader.model.Chapter
import com.lreader.model.ChapterImage
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 书籍解析：TXT / EPUB / FB2 / HTML。
 */
object BookParser {

    /** 正文里的插图占位符（U+FFFC OBJECT REPLACEMENT CHARACTER） */
    const val IMG_MARK = "\uFFFC"

    /** OPF manifest 里的 `<item …>` 标签 */
    private val ITEM_REGEX = Regex("""<item\b[^>]*>""")

    /**
     * `<img src="…">` 与 SVG 包裹的 `<image xlink:href="…">`（EPUB 常见两种写法）。
     *
     * 必须**连结尾的 `>` 一起匹配掉**：否则替换后只剩 `class="te_head_image"/>` 这类属性碎片，
     * 去标签那步看不到开头的 `<`，碎片就会被当正文显示出来（The Economist 的 `te_head_image` 踩过）。
     * 属性值引号兼容单/双引号。
     */
    private val IMG_TAG_REGEX = Regex(
        """<(?:img|image)\b[^>]*?(?:src|xlink:href)\s*=\s*['"]([^'"]+)['"][^>]*>""",
        RegexOption.IGNORE_CASE
    )

    private val ALT_REGEX = Regex("""\balt\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)

    /** 兜底：整行只剩属性碎片时删掉整行（如残留的 `class="te_head_image"/>`） */
    private val ATTR_LEFTOVER_REGEX = Regex(
        """(?m)^[ \t]*(?:[A-Za-z_:][-A-Za-z0-9_:.]*\s*=\s*['"][^'"]*['"]\s*)+/?>[ \t]*$\n?"""
    )

    private val IMG_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

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

                val imageDir = imageDirFor(file)
                var index = 0
                for (idref in spine) {
                    val href = manifest[idref] ?: continue
                    val entryPath = joinPath(opfDir, href)
                    val entry = zip.getEntry(entryPath) ?: continue
                    val html = zip.getInputStream(entry)
                        .use { it.readBytes().toString(Charsets.UTF_8) }

                    // 插图：按出现顺序提取到本地，正文里用占位符占位（下标一一对应）
                    val images = ArrayList<ChapterImage>()
                    val chapterDir = entryPath.substringBeforeLast('/', "")
                    val text = htmlToText(html) { src, alt ->
                        val imgEntry = resolveRelative(chapterDir, src)
                        images.add(
                            ChapterImage(
                                path = extractImage(zip, imgEntry, imageDir).orEmpty(),
                                alt = alt
                            )
                        )
                    }
                    if (text.isBlank()) continue
                    // 章节内没有标题时，用「书名 · 第 N 章」兜底
                    val fallback = titleMeta?.takeIf { it.isNotBlank() }
                        ?.let { "$it · 第 ${index + 1} 章" }
                        ?: "第 ${index + 1} 章"
                    val title = extractTitle(html).ifBlank { fallback }
                    chapters.add(Chapter(index, title, text, images))
                    index++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return chapters
    }

    /**
     * 提取 EPUB 封面图，写入 [targetFile]（覆盖已存在的）。
     *
     * 兼容两种写法：
     *  1. EPUB2：`<meta name="cover" content="封面 item 的 id"/>` → 再去 manifest 找 href；
     *  2. EPUB3：`<item properties="cover-image" href="…"/>`；
     * 再兜底找「文件名里含 cover」的图片资源。
     *
     * @return 成功写入返回 true（文件 < 1KB 视为无效封面）
     */
    fun extractCover(file: File, targetFile: File): Boolean {
        if (BookFormat.fromFileName(file.name) != BookFormat.EPUB) return false
        return try {
            ZipFile(file).use { zip ->
                val opfPath = findOpfPath(zip) ?: return false
                val opf = zip.getInputStream(zip.getEntry(opfPath))
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                val opfDir = opfPath.substringBeforeLast('/', "")
                val href = findCoverHref(opf) ?: return false
                val entryPath = joinPath(opfDir, href)
                val entry = zip.getEntry(entryPath)
                    ?: zip.getEntry(urlDecode(entryPath))
                    ?: return false
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (bytes.size < 1024) return false
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(bytes)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 按优先级找封面资源路径：EPUB2 meta → EPUB3 properties → 文件名含 cover 的图片 */
    private fun findCoverHref(opf: String): String? {
        val items = ITEM_REGEX.findAll(opf).map { it.value }.toList()

        val metaId = Regex("""<meta[^>]*name\s*=\s*"cover"[^>]*content\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]*content\s*=\s*"([^"]+)"[^>]*name\s*=\s*"cover"""", RegexOption.IGNORE_CASE)
                .find(opf)?.groupValues?.get(1)
        if (metaId != null) {
            items.firstOrNull { attr(it, "id") == metaId }?.let { tag ->
                attr(tag, "href")?.let { return it }
            }
        }

        items.firstOrNull { it.contains("cover-image", ignoreCase = true) }?.let { tag ->
            attr(tag, "href")?.let { return it }
        }

        val imgExt = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp")
        items.firstOrNull { tag ->
            val href = attr(tag, "href")?.lowercase() ?: return@firstOrNull false
            val media = attr(tag, "media-type") ?: ""
            (media.startsWith("image/") || imgExt.any { href.endsWith(it) }) &&
                href.contains("cover")
        }?.let { tag -> attr(tag, "href")?.let { return it } }

        return null
    }

    private fun urlDecode(path: String): String = try {
        java.net.URLDecoder.decode(path, "UTF-8")
    } catch (e: Exception) {
        path
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

    /**
     * 插图落盘目录：`books/.images/<书名>/`。
     * 放在书目录下（而不是 filesDir 根），删书时可以顺手连图片一起清掉。
     */
    private fun imageDirFor(bookFile: File): File =
        File(bookFile.parentFile, ".images/" + bookFile.nameWithoutExtension.take(60))

    /**
     * 把 zip 里的图片释放到 [dir]（**幂等**：文件已存在就直接复用，避免每次打开书都重新解压）。
     * @return 本地绝对路径；不是图片 / entry 不存在 / 写盘失败时返回 null
     */
    private fun extractImage(zip: ZipFile, entryPath: String, dir: File): String? {
        return try {
            val ext = entryPath.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty() || ext !in IMG_EXTS) {
                null
            } else {
                val entry = zip.getEntry(entryPath) ?: zip.getEntry(urlDecode(entryPath))
                if (entry == null) {
                    null
                } else {
                    val safe = entryPath.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
                    val target = File(dir, safe)
                    if (!target.exists() || target.length() == 0L) {
                        if (!dir.exists()) dir.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                    target.takeIf { it.length() > 0 }?.absolutePath
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 把 `../images/a.jpg` 这类相对路径解析成 zip 内的完整路径 */
    private fun resolveRelative(baseDir: String, href: String): String {
        val clean = urlDecode(href.substringBefore('#').substringBefore('?'))
        if (clean.startsWith("/")) return clean.trimStart('/')
        val parts = if (baseDir.isBlank()) mutableListOf() else baseDir.split("/").toMutableList()
        for (seg in clean.split("/")) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
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

    /**
     * HTML → 纯文本。
     *
     * @param onImage 非空时**保留插图**：把 `<img src>` / `<image xlink:href>` 换成 [IMG_MARK] 占位符，
     *   并按出现顺序回调 (原始相对路径, alt)，由调用方提取图片；为空则插图被直接丢弃（旧行为）。
     */
    fun htmlToText(html: String, onImage: ((String, String) -> Unit)? = null): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        if (onImage != null) {
            s = IMG_TAG_REGEX.replace(s) { m ->
                val alt = ALT_REGEX.find(m.value)?.groupValues?.get(1).orEmpty()
                onImage(m.groupValues[1], alt)
                "\n$IMG_MARK\n"
            }
        }
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p>"), "\n\n")
        s = s.replace(Regex("(?i)</div>"), "\n")
        s = s.replace(Regex("(?i)</h[1-6]>"), "\n\n")
        s = s.replace(Regex("(?i)<title>(.*?)</title>"), "\n\n$1\n\n")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = s.replace(ATTR_LEFTOVER_REGEX, "")
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
