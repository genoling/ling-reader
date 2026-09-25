package com.lreader.data

import android.content.Context
import com.lreader.book.BookParser
import com.lreader.model.Book
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 书架数据仓库。使用 JSON 文件持久化（无需引入 Room，保持依赖最小）。
 * 存储在 filesDir/bookshelf.json。
 */
class BookRepository(private val context: Context) {

    private val shelfFile: File get() = File(context.filesDir, "bookshelf.json")
    private val booksDir: File get() = File(context.filesDir, "books").apply { if (!exists()) mkdirs() }

    fun booksDir(): File = booksDir

    fun load(): MutableList<Book> {
        if (!shelfFile.exists()) return mutableListOf()
        return try {
            val text = shelfFile.readText(Charsets.UTF_8)
            val arr = JSONArray(text)
            val list = mutableListOf<Book>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Book(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        filePath = o.optString("filePath"),
                        format = com.lreader.model.BookFormat.valueOf(
                            o.optString("format", "UNKNOWN")
                        ),
                        coverPath = o.optString("coverPath").takeIf { it.isNotBlank() },
                        lastChapterIndex = o.optInt("lastChapterIndex", 0),
                        lastScrollY = o.optInt("lastScrollY", 0),
                        lastPercent = o.optDouble("lastPercent", 0.0).toFloat(),
                        progressUpdatedAt = o.optLong("progressUpdatedAt", 0),
                        addedAt = o.optLong("addedAt", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    fun save(books: List<Book>) {
        try {
            val arr = JSONArray()
            books.forEach { b ->
                val o = JSONObject()
                o.put("id", b.id)
                o.put("title", b.title)
                o.put("filePath", b.filePath)
                o.put("format", b.format.name)
                b.coverPath?.let { o.put("coverPath", it) }
                o.put("lastChapterIndex", b.lastChapterIndex)
                o.put("lastScrollY", b.lastScrollY)
                o.put("lastPercent", b.lastPercent.toDouble())
                o.put("progressUpdatedAt", b.progressUpdatedAt)
                o.put("addedAt", b.addedAt)
                arr.put(o)
            }
            shelfFile.writeText(arr.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 封面图目录（`filesDir/covers`） */
    fun coversDir(): File = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    /**
     * 确保书籍封面已提取（仅 EPUB 有封面）。
     *
     * 首次打开书架时对老书按需生成：EPUB → `covers/<id>.img`，并回写 `bookshelf.json`。
     * 已有封面文件时直接返回原对象，不做任何 IO。
     */
    fun ensureCover(book: Book): Book {
        val existing = book.coverPath
        if (!existing.isNullOrBlank() && File(existing).exists()) return book
        if (book.format != com.lreader.model.BookFormat.EPUB) return book
        val target = File(coversDir(), safeName(book.id) + ".img")
        if (!target.exists() && !BookParser.extractCover(File(book.filePath), target)) return book
        if (!target.exists()) return book

        val updated = book.copy(coverPath = target.absolutePath)
        val list = load()
        val i = list.indexOfFirst { it.id == book.id }
        if (i >= 0) {
            list[i] = updated
            save(list)
        }
        return updated
    }

    /** 把路径转成安全的文件名（封面文件用） */
    private fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)

    /**
     * 导入一个外部文件到应用私有目录，并返回 Book。
     * 若同名文件已存在则自动改名，避免覆盖。
     */
    fun importFile(source: File): Book? {
        if (!source.exists() || !source.isFile) return null
        val fmt = com.lreader.model.BookFormat.fromFileName(source.name)
        if (fmt == com.lreader.model.BookFormat.UNKNOWN) return null

        val target = uniqueTarget(source.name)
        return try {
            source.copyTo(target, overwrite = false)
            BookParser.makeBook(target)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun uniqueTarget(name: String): File {
        var f = File(booksDir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(booksDir, "${base}_$i.$ext")
            i++
        }
        return f
    }

    fun delete(book: Book) {
        try {
            val f = File(book.filePath)
            if (f.exists() && f.absolutePath.startsWith(booksDir.absolutePath)) f.delete()
            // 顺带清掉这本书的派生文件：封面图 + 插图缓存目录（books/.images/<书名>/）
            book.coverPath?.let { p -> runCatching { File(p).takeIf { it.exists() }?.delete() } }
            val imgDir = File(booksDir, ".images/" + f.nameWithoutExtension.take(60))
            if (imgDir.exists()) imgDir.deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
