package com.lreader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lreader.model.ReviewGrade
import com.lreader.model.VocabWord

/**
 * 生词本仓库。SQLite 持久化 + SM-2 间隔重复算法。
 */
class VocabRepository(context: Context) {

    private val helper = Helper(context.applicationContext)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "vocab.db", null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE vocab (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMNS.joinToString(", ") + ", UNIQUE(word, sentence))"
            )
            createIndexes(db)
        }

        /**
         * ⚠️ 升级必须保留用户数据：绝不 DROP / 重建表。
         *
         * 采用「缺列补列」策略：以后新增字段只要追加到 [COLUMNS]，
         * 老用户升级时自动 ALTER TABLE ADD COLUMN，生词与复习进度都不会丢。
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            ensureSchema(db)
        }

        /** 降级安装（如从新版 APK 回滚到旧版）同样不清库，否则会丢数据 */
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            ensureSchema(db)
        }

        private fun ensureSchema(db: SQLiteDatabase) {
            val existing = HashSet<String>()
            db.rawQuery("PRAGMA table_info(vocab)", null).use { c ->
                while (c.moveToNext()) existing.add(c.getString(1))
            }
            if (existing.isEmpty()) {
                // 表确实不存在（异常情况）才新建
                onCreate(db)
                return
            }
            COLUMNS.forEach { spec ->
                val name = spec.substringBefore(' ')
                if (name !in existing) {
                    db.execSQL("ALTER TABLE vocab ADD COLUMN $spec")
                }
            }
            createIndexes(db)
            backfillSyncColumns(db)
        }

        private fun createIndexes(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_next ON vocab(next_review)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_word ON vocab(word)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_uid ON vocab(uid)")
        }

        /**
         * 给老数据补 `uid` / `updated_at` / `deleted`。
         *
         * 必须在补列之后跑：ALTER TABLE 加列后老行为 NULL，
         * 而同步合并以 `uid` 为主键、以 `deleted = 0` 过滤，空值会导致数据"消失"或被误判成同一条。
         */
        private fun backfillSyncColumns(db: SQLiteDatabase) {
            val ids = ArrayList<Long>()
            db.rawQuery("SELECT id FROM vocab WHERE uid IS NULL OR uid = ''", null).use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
            if (ids.isEmpty()) {
                // 已回填过：只把缺失的 deleted 补成 0（例如从更早版本升上来）
                db.execSQL("UPDATE vocab SET deleted = 0 WHERE deleted IS NULL")
                return
            }
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                ids.forEach { id ->
                    db.execSQL(
                        "UPDATE vocab SET uid = ?, updated_at = ?, deleted = 0 WHERE id = ?",
                        arrayOf(java.util.UUID.randomUUID().toString(), now, id)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    companion object {
        /**
         * 3：新增 `last_review_day`（每日复习刷新用）；
         * 4：新增同步元数据 `uid` / `updated_at` / `deleted`（升级后自动回填 UUID）。
         * 一律靠 ensureSchema 非破坏性补列。
         */
        private const val DB_VERSION = 4

        /**
         * 表结构唯一来源。新增字段直接追加，升级会自动补列。
         * 注意：ALTER TABLE ADD COLUMN 不能加 NOT NULL，故后续新列要有默认值或可空。
         */
        private val COLUMNS = listOf(
            "word TEXT NOT NULL",
            "meaning TEXT",
            "phonetic TEXT",
            "sentence TEXT",
            "source_book TEXT",
            "level TEXT",
            "add_date INTEGER",
            "repetition INTEGER DEFAULT 0",
            "interval_days INTEGER DEFAULT 0",
            "easiness REAL DEFAULT 2.5",
            "grade INTEGER DEFAULT 0",
            "next_review INTEGER",
            "last_review_day INTEGER DEFAULT 0",
            "uid TEXT",
            "updated_at INTEGER DEFAULT 0",
            "deleted INTEGER DEFAULT 0"
        )
    }

    // ------------------------------------------------------------------
    // 增删查
    // ------------------------------------------------------------------

    /**
     * 是否已在生词本（按单词，忽略句子；**大小写不敏感**）。
     *
     * 正文里 `The / the`、`Arthur / arthur` 属于同一个词，若按大小写严格比较，
     * 既会重复收藏，也会导致「生词红色高亮」匹配不上（曾出现过该 bug）。
     */
    fun contains(word: String): Boolean = findId(word) != null

    /** 生词 id（大小写不敏感），未收藏返回 null */
    fun findId(word: String): Long? {
        val w = word.trim()
        if (w.isEmpty()) return null
        helper.readableDatabase.rawQuery(
            "SELECT id FROM vocab WHERE word = ? COLLATE NOCASE AND deleted = 0 LIMIT 1", arrayOf(w)
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    /** 添加生词，已存在则不重复添加 */
    fun add(w: VocabWord): Boolean {
        if (contains(w.word)) return false
        val cv = ContentValues().apply {
            put("word", w.word)
            put("meaning", w.meaning)
            put("phonetic", w.phonetic)
            put("sentence", w.sentence)
            put("source_book", w.sourceBook)
            put("level", w.level)
            put("add_date", System.currentTimeMillis())
            put("repetition", 0)
            put("interval_days", 0)
            put("easiness", 2.5)
            put("grade", 0)
            put("next_review", System.currentTimeMillis())
            val now = System.currentTimeMillis()
            put("uid", java.util.UUID.randomUUID().toString())
            put("updated_at", now)
            put("deleted", 0)
        }
        return helper.writableDatabase.insert("vocab", null, cv) > 0
    }

    /**
     * 批量导入（单个事务，几千条也很快）。
     * 已存在的词（忽略大小写）跳过；带上 SM-2 参数便于从备份完整还原。
     * @return 新增数 to 跳过数
     */
    fun importAll(words: List<VocabWord>): Pair<Int, Int> {
        if (words.isEmpty()) return 0 to 0
        val db = helper.writableDatabase
        var added = 0
        var skipped = 0
        db.beginTransaction()
        try {
            words.forEach { w ->
                val word = w.word.trim()
                if (word.isEmpty() || contains(word)) {
                    skipped++
                    return@forEach
                }
                val cv = ContentValues().apply {
                    put("word", word)
                    put("meaning", w.meaning)
                    put("phonetic", w.phonetic)
                    put("sentence", w.sentence)
                    put("source_book", w.sourceBook)
                    put("level", w.level)
                    put("add_date", if (w.addDate > 0) w.addDate else System.currentTimeMillis())
                    put("repetition", w.repetition)
                    put("interval_days", w.interval)
                    put("easiness", w.easiness)
                    put("grade", w.grade)
                    put("next_review", if (w.nextReview > 0) w.nextReview else System.currentTimeMillis())
                    put("uid", w.uid.ifBlank { java.util.UUID.randomUUID().toString() })
                    put("updated_at", if (w.updatedAt > 0) w.updatedAt else System.currentTimeMillis())
                    put("deleted", if (w.deleted) 1 else 0)
                }
                if (db.insertWithOnConflict("vocab", null, cv, SQLiteDatabase.CONFLICT_IGNORE) > 0) {
                    added++
                } else {
                    skipped++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added to skipped
    }

    /**
     * 删除（**软删除**）。
     *
     * 跨设备同步时必须把"删除"也当成一条修改传播出去，
     * 否则在 A 设备删掉的词会被 B 设备的下一次上传原样同步回来。
     */
    fun remove(id: Long) {
        softDelete("id = ?", arrayOf(id.toString()))
    }

    fun removeByWord(word: String) {
        softDelete("word = ? COLLATE NOCASE AND deleted = 0", arrayOf(word.trim()))
    }

    private fun softDelete(where: String, args: Array<String>) {
        val cv = ContentValues().apply {
            put("deleted", 1)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("vocab", cv, where, args)
    }

    /**
     * 补写音标。
     * 用于修补历史数据（早期版本没解析音标就写库了），只 UPDATE 已有列，不改表结构。
     */
    fun updatePhonetic(id: Long, phonetic: String) {
        if (phonetic.isBlank()) return
        val cv = ContentValues().apply {
            put("phonetic", phonetic)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("vocab", cv, "id = ?", arrayOf(id.toString()))
    }

    /**
     * 补写释义。
     * 用于修补历史数据：早期版本把「abrupt的变形」这类**没有中文**的指向性说明
     * 当成了释义存库，导致副词等变形词在生词本里看不到中文含义。
     */
    fun updateMeaning(id: Long, meaning: String) {
        if (meaning.isBlank()) return
        val cv = ContentValues().apply {
            put("meaning", meaning)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("vocab", cv, "id = ?", arrayOf(id.toString()))
    }

    /** 全部生词（按添加时间倒序） */
    fun all(): List<VocabWord> {
        val list = ArrayList<VocabWord>()
        helper.readableDatabase.rawQuery(
            "SELECT * FROM vocab WHERE deleted = 0 ORDER BY add_date DESC", null
        ).use { c -> while (c.moveToNext()) list.add(read(c)) }
        return list
    }

    fun count(): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM vocab WHERE deleted = 0", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 今天的 0 点（毫秒） */
    fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * 今天还**没背过**的词。
     *
     * 每天 0 点自动刷新：不管 SM-2 间隔到没到，当天没背过的词都会重新进队列；
     * 提交过评级的词当天不再出现，第二天再次全部出现。
     */
    fun todayPending(shuffle: Boolean = false): List<VocabWord> {
        val list = ArrayList<VocabWord>()
        val order = if (shuffle) "RANDOM()" else "next_review ASC"
        helper.readableDatabase.rawQuery(
            "SELECT * FROM vocab WHERE deleted = 0 AND (last_review_day IS NULL OR last_review_day < ?) ORDER BY $order",
            arrayOf(startOfToday().toString())
        ).use { c -> while (c.moveToNext()) list.add(read(c)) }
        return list
    }

    /** 今天需要复习的 */
    fun dueToday(): List<VocabWord> = todayPending(false)

    /** 今日新增数量 */
    fun addedToday(): Int {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM vocab WHERE deleted = 0 AND add_date >= ?", arrayOf(start.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun read(c: android.database.Cursor): VocabWord = VocabWord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        word = c.getString(c.getColumnIndexOrThrow("word")) ?: "",
        meaning = c.getString(c.getColumnIndexOrThrow("meaning")) ?: "",
        phonetic = c.getString(c.getColumnIndexOrThrow("phonetic")) ?: "",
        sentence = c.getString(c.getColumnIndexOrThrow("sentence")) ?: "",
        sourceBook = c.getString(c.getColumnIndexOrThrow("source_book")) ?: "",
        level = c.getString(c.getColumnIndexOrThrow("level")) ?: "",
        addDate = c.getLong(c.getColumnIndexOrThrow("add_date")),
        repetition = c.getInt(c.getColumnIndexOrThrow("repetition")),
        interval = c.getInt(c.getColumnIndexOrThrow("interval_days")),
        easiness = c.getDouble(c.getColumnIndexOrThrow("easiness")),
        grade = c.getInt(c.getColumnIndexOrThrow("grade")),
        nextReview = c.getLong(c.getColumnIndexOrThrow("next_review")),
        uid = c.getString(c.getColumnIndexOrThrow("uid")) ?: "",
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        deleted = c.getInt(c.getColumnIndexOrThrow("deleted")) != 0
    )

    // ------------------------------------------------------------------
    // SM-2 复习算法
    // ------------------------------------------------------------------

    /**
     * 提交一次复习结果，更新间隔与下次复习时间。
     *
     * SM-2 规则：
     *  - grade < 3（忘掉）: repetition 归零, interval 归 1 天
     *  - grade >= 3: repetition += 1
     *      rep == 1 -> interval = 1
     *      rep == 2 -> interval = 6
     *      rep >= 3 -> interval = round(interval * easiness)
     *  - easiness 调整: EF' = EF + (0.1 - (5-grade)*(0.08 + (5-grade)*0.02))，下限 1.3
     */
    fun review(w: VocabWord, grade: ReviewGrade): VocabWord {
        var rep = w.repetition
        var interval = w.interval
        var ef = w.easiness
        val g = grade.value

        if (g < 3) {
            rep = 0
            interval = 1
        } else {
            rep += 1
            interval = when (rep) {
                1 -> 1
                2 -> 6
                else -> Math.round(interval * ef).toInt().coerceAtLeast(1)
            }
        }

        ef += 0.1 - (5 - g) * (0.08 + (5 - g) * 0.02)
        if (ef < 1.3) ef = 1.3

        val nextReview = System.currentTimeMillis() +
            interval.toLong() * 24 * 60 * 60 * 1000

        val cv = ContentValues().apply {
            put("repetition", rep)
            put("interval_days", interval)
            put("easiness", ef)
            put("grade", g)
            put("next_review", nextReview)
            // 记录「今天已背过」，当天不再出现在复习队列
            put("last_review_day", startOfToday())
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("vocab", cv, "id = ?", arrayOf(w.id.toString()))

        return w.copy(
            repetition = rep,
            interval = interval,
            easiness = ef,
            grade = g,
            nextReview = nextReview
        )
    }

    /** 清空（软删除：删除也会随同步传播到其它设备） */
    fun clear() {
        softDelete("deleted = 0", emptyArray())
    }

    // ------------------------------------------------------------------
    // 跨设备同步（见 sync 包）
    // ------------------------------------------------------------------

    /** 供同步使用：**包含已软删除**的记录（删除必须上传，否则别的设备会把它同步回来） */
    fun allForSync(): List<VocabWord> {
        val list = ArrayList<VocabWord>()
        helper.readableDatabase.rawQuery("SELECT * FROM vocab", null).use { c ->
            while (c.moveToNext()) list.add(read(c))
        }
        return list
    }

    /**
     * 把合并结果写回本地（按 [VocabWord.uid] 匹配：有则更新，无则插入）。
     *
     * 不碰本地自增 `id`；`UNIQUE(word, sentence)` 撞车时忽略该条（本地已有同词同句）。
     * @return 写入条数
     */
    fun applyMerged(words: List<VocabWord>): Int {
        if (words.isEmpty()) return 0
        val db = helper.writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            words.forEach { w ->
                if (w.uid.isBlank()) return@forEach
                val cv = ContentValues().apply {
                    put("word", w.word)
                    put("meaning", w.meaning)
                    put("phonetic", w.phonetic)
                    put("sentence", w.sentence)
                    put("source_book", w.sourceBook)
                    put("level", w.level)
                    put("add_date", w.addDate)
                    put("repetition", w.repetition)
                    put("interval_days", w.interval)
                    put("easiness", w.easiness)
                    put("grade", w.grade)
                    put("next_review", w.nextReview)
                    put("uid", w.uid)
                    put("updated_at", w.updatedAt)
                    put("deleted", if (w.deleted) 1 else 0)
                }
                val updated = db.update("vocab", cv, "uid = ?", arrayOf(w.uid))
                if (updated > 0) {
                    n += updated
                } else if (db.insertWithOnConflict("vocab", null, cv, SQLiteDatabase.CONFLICT_IGNORE) > 0) {
                    n++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }
}
