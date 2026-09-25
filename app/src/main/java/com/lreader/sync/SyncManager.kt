package com.lreader.sync

import android.content.Context
import android.util.Log
import com.lreader.data.BookRepository
import com.lreader.data.SettingsStore
import com.lreader.data.VocabRepository
import com.lreader.export.VocabExporter
import kotlinx.coroutines.delay

/**
 * 同步调度：把设置里的同步码、生词本、阅读进度和 [SyncEngine] 串起来。
 *
 * 无状态；调用方负责线程（除 [autoSync] / [awaitInitialSync] 外都是**同步阻塞**的，必须在 IO 线程调用）。
 */
object SyncManager {

    private const val TAG = "SyncManager"

    /** 启动时的自动同步是否还在跑（阅读页可先等它一会儿，保证生词高亮一致） */
    @Volatile
    var syncing: Boolean = false
        private set

    /** 最近一次自动同步的错误（null = 正常），设置页会展示 */
    @Volatile
    var lastError: String? = null
        private set

    /** 当前同步配置；未配置返回 null */
    fun config(context: Context): SyncConfig? =
        SyncCode.decode(SettingsStore(context).syncCode)

    fun isConfigured(context: Context): Boolean = config(context) != null

    /**
     * 同步**生词本 + 阅读进度**（两个云端小文件）。
     *
     * @param push false = 只拉取不推送
     * @throws SyncException 未配置 / 网络 / 权限 / 口令错误
     */
    fun sync(context: Context, push: Boolean = true): SyncEngine.Result {
        val settings = SettingsStore(context)
        val cfg = SyncCode.decode(settings.syncCode)
            ?: throw SyncException("还没有配置同步码")
        val backend = GithubBackend(cfg.owner, cfg.repo, cfg.branch, cfg.token)

        // ---- 生词本（加密 CSV）----
        val vocabRepo = VocabRepository(context)
        val vocabResult = SyncEngine.run(
            backend = backend,
            path = VocabSync.pathOf(cfg.syncId),
            passphrase = cfg.passphrase,
            syncId = cfg.syncId,
            local = vocabRepo.allForSync(),
            idOf = { it.uid },
            updatedOf = { it.updatedAt },
            parse = { VocabExporter.parseCsv(it) },
            render = { VocabExporter.toCsv(it) },
            apply = { vocabRepo.applyMerged(it) },
            push = push,
            message = "sync vocab"
        )

        // ---- 阅读进度（加密 JSON：章节 + 百分比，跨设备不传页码）----
        val bookRepo = BookRepository(context)
        val localProgress = bookRepo.load().mapNotNull { b ->
            val key = ProgressSync.keyOf(b.title)
            if (key.isBlank()) {
                null
            } else {
                ProgressEntry(key, b.title, b.lastChapterIndex, b.lastPercent, b.progressUpdatedAt)
            }
        }
        val progressResult = SyncEngine.run(
            backend = backend,
            path = ProgressSync.pathOf(cfg.syncId),
            passphrase = cfg.passphrase,
            syncId = cfg.syncId,
            local = localProgress,
            idOf = { it.key },
            updatedOf = { it.updatedAt },
            parse = { ProgressSync.parse(it) },
            render = { ProgressSync.toJson(it) },
            apply = { items -> applyProgress(bookRepo, items) },
            push = push,
            message = "sync progress"
        )

        settings.lastSyncAt = System.currentTimeMillis()
        return SyncEngine.Result(
            total = vocabResult.total,
            applied = vocabResult.applied + progressResult.applied,
            uploaded = vocabResult.uploaded && progressResult.uploaded,
            changedLocally = vocabResult.changedLocally || progressResult.changedLocally,
            conflictRetried = vocabResult.conflictRetried || progressResult.conflictRetried
        )
    }

    /** 把合并后的进度写回 `bookshelf.json`；页码置 0，让阅读页按百分比定位 */
    private fun applyProgress(bookRepo: BookRepository, items: List<ProgressEntry>): Int {
        val list = bookRepo.load()
        var changed = 0
        items.forEach { e ->
            val i = list.indexOfFirst { ProgressSync.keyOf(it.title) == e.key }
            if (i >= 0) {
                val b = list[i]
                val same = b.lastChapterIndex == e.chapterIndex &&
                    b.lastPercent == e.percent &&
                    b.progressUpdatedAt == e.updatedAt
                if (!same) {
                    list[i] = b.copy(
                        lastChapterIndex = e.chapterIndex,
                        lastPercent = e.percent,
                        lastScrollY = 0,
                        progressUpdatedAt = e.updatedAt
                    )
                    changed++
                }
            }
        }
        if (changed > 0) bookRepo.save(list)
        return changed
    }

    /**
     * 启动时的静默同步：失败只记录（设置页可见），不打扰用户。
     */
    suspend fun autoSync(context: Context) {
        if (!isConfigured(context)) return
        if (!SettingsStore(context).syncAuto) return
        syncing = true
        lastError = null
        try {
            sync(context)
            Log.i(TAG, "自动同步完成")
        } catch (e: Exception) {
            lastError = e.message ?: "未知错误"
            Log.w(TAG, "自动同步失败：$lastError")
        } finally {
            syncing = false
        }
    }

    /**
     * 等启动同步结束（最多 [maxWaitMs]）。
     *
     * 阅读页在读取生词高亮之前调用：否则刚启动就点进书时，生词高亮可能用的是同步前的旧数据。
     */
    suspend fun awaitInitialSync(maxWaitMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (syncing && System.currentTimeMillis() < deadline) delay(150)
    }

    /** 生成本机新的同步配置（首次在"主设备"上使用） */
    fun newConfig(owner: String, repo: String, branch: String, token: String): SyncConfig =
        SyncConfig(
            owner = owner.trim(),
            repo = repo.trim(),
            branch = branch.trim().ifBlank { "main" },
            syncId = SyncCrypto.newSyncId(),
            token = token.trim(),
            passphrase = SyncCrypto.newPassphrase()
        )
}
