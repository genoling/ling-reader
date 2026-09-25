package com.lreader.book

import com.lreader.model.Book
import com.lreader.model.BookFormat
import com.lreader.model.Chapter
import com.lreader.model.ChapterImage
import com.lreader.model.TocEntry
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 书籍解析：TXT / EPUB / FB2 / HTML。
 */
object BookParser {

    /** 正文里的插图占位符（U+FFFC OBJECT REPLACEMENT CHARACTER） */
    const val IMG_MARK = "\uFFFC"

    /**
     * 块类型标记（控制字符，正文里不可能出现）。
     *
     * EPUB 的 `<h1>/<h2>/<h3>` 是排版层级，拍平成纯文本后就丢了；带上标记后
     * 阅读页能按层级渲染不同字号（标题大、正文小），与纸质/官方阅读器的观感一致。
     */
    const val MARK_TITLE: Char = '\u0001'    // h1
    const val MARK_SUBTITLE: Char = '\u0002' // h2
    const val MARK_SECTION: Char = '\u0003'  // h3
    const val MARK_MINOR: Char = '\u0004'    // h4~h6
    const val MARK_QUOTE: Char = '\u0005'    // blockquote

    /**
     * 内部超链接（EPUB 的目录页 / 栏目页大量使用）。
     *
     * 编码为 `MARK_LINK 目标路径 MARK_LINK_TEXT 链接文字 MARK_LINK_END`，
     * 目标路径是 zip 内路径（已去 `#anchor`），阅读页据此跳到对应章节。
     */
    const val MARK_LINK: Char = '\u0006'
    const val MARK_LINK_TEXT: Char = '\u0007'
    const val MARK_LINK_END: Char = '\u0008'

    /** 列表项前缀（`<li>`）：目录页就是一堆列表项，之前会被挤成一行 */
    const val BULLET = "• "

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

                // 目录：标题 + 层级（href → 文件名，去 fragment），用于章节命名与目录面板
                val tocNodes = parseToc(zip, opfContent, opfDir)
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
                    val text = htmlToText(
                        html,
                        onImage = { src, alt ->
                            val imgEntry = resolveRelative(chapterDir, src)
                            images.add(
                                ChapterImage(
                                    path = extractImage(zip, imgEntry, imageDir).orEmpty(),
                                    alt = alt
                                )
                            )
                        },
                        // 内部链接（目录页 / 栏目页）→ 记下目标文件，阅读页可点击跳章
                        onLink = { link ->
                            when {
                                link.startsWith("http", true) ||
                                    link.startsWith("mailto:", true) -> null

                                else -> {
                                    val clean = link.substringBefore('#')
                                    if (clean.isBlank()) entryPath
                                    else resolveRelative(chapterDir, clean)
                                }
                            }
                        }
                    )
                    if (text.isBlank()) continue
                    // 章节内没有标题时，用「书名 · 第 N 章」兜底
                    val fallback = titleMeta?.takeIf { it.isNotBlank() }
                        ?.let { "$it · 第 ${index + 1} 章" }
                        ?: "第 ${index + 1} 章"
                    // 同一文件可能同时被栏目与文章两个目录项引用，取最深的（= 具体文章名）
                    val toc = tocNodes.filter { it.path == entryPath }.maxByOrNull { it.level }
                    // 标题优先级：目录标题（最完整）→ html `<title>` →「书名 · 第 N 章」
                    val title = toc?.title?.takeIf { it.isNotBlank() }
                        ?: extractTitle(html).ifBlank { fallback }
                    chapters.add(
                        Chapter(
                            index = index,
                            title = title,
                            content = text,
                            images = images,
                            sourcePath = entryPath,
                            tocTitle = toc?.title,
                            tocLevel = toc?.level ?: 0
                        )
                    )
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

    /**
     * 解析 EPUB 目录：`href`（去 fragment、相对 zip 根）→ (标题, 层级)。
     *
     * 依次尝试：EPUB2 的 `toc.ncx`（`<navPoint>` 嵌套 = 层级）→ EPUB3 的 `nav.xhtml`
     * （`<ol>` 嵌套 = 层级）。取不到就返回空列表。
     *
     * 同一章节文件常被**父子两个目录项同时引用**（父 = 栏目、子 = 具体文章），
     * 因此这里保留全部节点（含层级），由调用方选最深的那条给章节命名。
     */
    private fun parseToc(
        zip: ZipFile,
        opf: String,
        opfDir: String
    ): List<TocNode> {
        val out = ArrayList<TocNode>()
        val seen = HashSet<String>()

        // ---- EPUB2：toc.ncx ----
        var ncxHref: String? = null
        for (m in ITEM_REGEX.findAll(opf)) {
            if ((attr(m.value, "media-type") ?: "").contains("dtbncx", ignoreCase = true)) {
                ncxHref = attr(m.value, "href")
                break
            }
        }
        val ncxEntry = ncxHref?.let { zip.getEntry(joinPath(opfDir, it)) }
            ?: zip.getEntry(joinPath(opfDir, "toc.ncx"))
        if (ncxEntry != null) {
            val ncx = readEntry(zip, ncxEntry).orEmpty()
            val ncxDir = ncxEntry.name.substringBeforeLast('/', "")
            var depth = 0
            var pending: String? = null
            TOC_TOKEN_REGEX.findAll(ncx).forEach { tk ->
                val raw = tk.value
                when {
                    raw.startsWith("</") -> depth = (depth - 1).coerceAtLeast(0)
                    raw.startsWith("<navPoint", ignoreCase = true) -> depth++
                    tk.groupValues[1].isNotBlank() -> pending = tk.groupValues[1]
                    tk.groupValues[2].isNotBlank() -> {
                        val title = pending
                        // href 可能带锚点（`xxx.html#politics`），配章节时必须去掉
                        val path = resolveRelative(ncxDir, tk.groupValues[2].substringBefore('#'))
                        val level = (depth - 1).coerceAtLeast(0)
                        if (!title.isNullOrBlank() && seen.add("$path|$level")) {
                            out.add(TocNode(title, path, level))
                        }
                        pending = null
                    }
                }
            }
        }

        // ---- EPUB3：nav.xhtml（ncx 没解析出东西时才用）----
        if (out.isEmpty()) {
            val navHref = ITEM_REGEX.findAll(opf)
                .firstOrNull { (attr(it.value, "properties") ?: "").contains("nav", ignoreCase = true) }
                ?.let { attr(it.value, "href") }
            val navEntry = navHref?.let { zip.getEntry(joinPath(opfDir, it)) }
            if (navEntry != null) {
                val nav = readEntry(zip, navEntry).orEmpty()
                val navDir = navEntry.name.substringBeforeLast('/', "")
                var depth = 0
                NAV_TOKEN_REGEX.findAll(nav).forEach { tk ->
                    val raw = tk.value
                    when {
                        raw.startsWith("</") -> depth = (depth - 1).coerceAtLeast(0)
                        raw.startsWith("<ol", true) || raw.startsWith("<ul", true) -> depth++
                        raw.startsWith("<a", true) -> {
                            val href = attr(raw, "href") ?: return@forEach
                            val title = stripTags(raw.substringAfter('>'))
                            val path = resolveRelative(navDir, href.substringBefore('#'))
                            val level = (depth - 1).coerceAtLeast(0)
                            if (title.isNotBlank() && seen.add("$path|$level")) {
                                out.add(TocNode(title, path, level))
                            }
                        }
                    }
                }
            }
        }

        return out
    }

    /** 目录节点：章节文件路径（zip 内、已去锚点）+ 标题 + 层级（0 = 顶层栏目） */
    data class TocNode(val title: String, val path: String, val level: Int)

    /**
     * 目录节点 → 可直接跳转的 [TocEntry]（供阅读页目录面板使用）。
     *
     * 用 [Chapter.sourcePath] 建立「文件 → 章节号」映射，与 [loadEpub] 的过滤规则天然一致；
     * txt / fb2 没有路径，返回空表，由 UI 回退成章节列表。
     */
    fun loadToc(book: Book, chapters: List<Chapter>): List<TocEntry> {
        val pathToIndex = chapters.mapNotNull { c -> c.sourcePath?.let { it to c.index } }.toMap()
        if (pathToIndex.isEmpty()) return emptyList()
        var zip: ZipFile? = null
        return try {
            val z = ZipFile(File(book.filePath))
            zip = z
            val opfPath = findOpfPath(z).orEmpty()
            if (opfPath.isEmpty()) {
                emptyList()
            } else {
                val opf = z.getInputStream(z.getEntry(opfPath))
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                parseToc(z, opf, opfPath.substringBeforeLast('/', ""))
                    .mapNotNull { n -> pathToIndex[n.path]?.let { TocEntry(n.title, it, n.level) } }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { zip?.close() }
        }
    }

    private fun readEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): String? = try {
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]+>"), "").trim()

    /** `<a href="…">文字</a>`：内部链接（目录页 / 栏目页） */
    private val ANCHOR_REGEX = Regex(
        """<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** ncx 的解析令牌：`<text>` 给标题、`<content src>` 给目标，`navPoint` 开合算层级 */
    private val TOC_TOKEN_REGEX = Regex(
        """<navPoint\b|</navPoint>|<text>\s*(.*?)\s*</text>|<content[^>]*\bsrc\s*=\s*"([^"]+)"""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** nav.xhtml 的解析令牌：`<ol>/<ul>` 开合算层级，`<a href>文本` 给条目 */
    private val NAV_TOKEN_REGEX = Regex(
        """<(?:ol|ul)\b|</(?:ol|ul)>|<a\b[^>]*>[^<]*""",
        RegexOption.IGNORE_CASE
    )

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
    fun htmlToText(
        html: String,
        onImage: ((String, String) -> Unit)? = null,
        /** 内部链接：返回该 href 在 zip 内的路径（外部链接 / 无法解析返回 null） */
        onLink: ((String) -> String?)? = null
    ): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        // `<head>` 里的 `<title>` 会被当正文提取（封面章于是只剩一个单词 "Cover"、广告页首行是文件名）
        s = s.replace(Regex("(?is)<head\\b[^>]*>.*?</head>"), "")
        if (onImage != null) {
            s = IMG_TAG_REGEX.replace(s) { m ->
                val alt = ALT_REGEX.find(m.value)?.groupValues?.get(1).orEmpty()
                onImage(m.groupValues[1], alt)
                "\n$IMG_MARK\n"
            }
        }
        // 列表：每项独占一行 + 项目符号（EPUB 目录页/栏目页靠它排版）
        s = s.replace(Regex("(?i)<li\\b[^>]*>"), "\n$BULLET")
        s = s.replace(Regex("(?i)</li>"), "\n")
        s = s.replace(Regex("(?i)</?(?:ul|ol)\\b[^>]*>"), "\n")
        if (onLink != null) {
            s = ANCHOR_REGEX.replace(s) { m ->
                val label = m.groupValues[2].replace(Regex("(?s)<[^>]+>"), "").trim()
                val target = onLink(m.groupValues[1])
                if (target.isNullOrBlank() || label.isEmpty()) {
                    label
                } else {
                    "$MARK_LINK$target$MARK_LINK_TEXT$label$MARK_LINK_END"
                }
            }
        }
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p>"), "\n\n")
        s = s.replace(Regex("(?i)</div>"), "\n")
        // 标题层级：起始标签前插入块标记，供阅读页分级排版（标记会被 UI 解析后去掉）
        s = s.replace(Regex("(?i)<h1\\b[^>]*>"), "\n$MARK_TITLE")
        s = s.replace(Regex("(?i)<h2\\b[^>]*>"), "\n$MARK_SUBTITLE")
        s = s.replace(Regex("(?i)<h3\\b[^>]*>"), "\n$MARK_SECTION")
        s = s.replace(Regex("(?i)<h[456]\\b[^>]*>"), "\n$MARK_MINOR")
        s = s.replace(Regex("(?i)<blockquote\\b[^>]*>"), "\n$MARK_QUOTE")
        s = s.replace(Regex("(?i)</h[1-6]>"), "\n\n")
        s = s.replace(Regex("(?i)</blockquote>"), "\n\n")
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
