package com.lreader.sync

import com.lreader.export.VocabExporter
import com.lreader.model.VocabWord

/**
 * 生词本跨设备同步（薄封装，真正的合并逻辑在 [SyncEngine]）。
 *
 * 云端文件是**加密后的 CSV**：既能用 Excel 打开（导出时），又满足同步所需的
 * `uid`（跨设备主键）、`updated_at`（冲突取新）、`deleted`（软删除传播）三列。
 */
object VocabSync {

    /** 仓库里的路径约定：每人一个目录 */
    fun pathOf(syncId: String): String = "users/$syncId/vocab.csv.enc"

    /** 按 uid 合并，取 `updatedAt` 更新的（见 [SyncEngine.merge]） */
    fun merge(local: List<VocabWord>, remote: List<VocabWord>): List<VocabWord> =
        SyncEngine.merge(local, remote, { it.uid }, { it.updatedAt })

    /**
     * 跑一轮生词本同步。
     *
     * @param local 本地全量（**含软删除**，见 `VocabRepository.allForSync()`）
     */
    fun run(
        backend: SyncBackend,
        path: String,
        passphrase: String,
        syncId: String,
        local: List<VocabWord>,
        applyLocal: (List<VocabWord>) -> Int,
        push: Boolean = true
    ): SyncEngine.Result = SyncEngine.run(
        backend = backend,
        path = path,
        passphrase = passphrase,
        syncId = syncId,
        local = local,
        idOf = { it.uid },
        updatedOf = { it.updatedAt },
        parse = { VocabExporter.parseCsv(it) },
        render = { VocabExporter.toCsv(it) },
        apply = applyLocal,
        push = push,
        message = "sync vocab"
    )
}
