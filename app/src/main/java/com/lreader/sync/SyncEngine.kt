package com.lreader.sync

/**
 * 通用同步引擎：一个"云端加密小文件"的双向合并（生词本、阅读进度共用）。
 *
 * 一轮 = 下载 → 解密 → 解析 → 合并 → 写回本地 → 渲染 → 加密上传。
 * 上传带 `sha` 乐观锁；两端同时同步会冲突，自动重拉重并（最多 [MAX_ROUNDS] 轮）。
 *
 * 与具体数据无关：主键、时间戳、解析、渲染、写回都由调用方以 lambda 注入。
 */
object SyncEngine {

    private const val MAX_ROUNDS = 3

    data class Result(
        /** 合并后的记录数 */
        val total: Int,
        /** 写回本地的条数 */
        val applied: Int,
        /** 是否成功上传 */
        val uploaded: Boolean,
        /** 本地是否被改动（UI 据此刷新） */
        val changedLocally: Boolean,
        /** 是否发生过乐观锁冲突（已自动重试） */
        val conflictRetried: Boolean = false
    )

    fun <T> run(
        backend: SyncBackend,
        path: String,
        passphrase: String,
        syncId: String,
        local: List<T>,
        idOf: (T) -> String,
        updatedOf: (T) -> Long,
        parse: (String) -> List<T>,
        render: (List<T>) -> String,
        apply: (List<T>) -> Int,
        push: Boolean = true,
        message: String = "sync"
    ): Result {
        var retried = false
        repeat(MAX_ROUNDS) {
            val remote = backend.download(path)
            var sha: String? = null
            val remoteItems: List<T> = if (remote == null) {
                emptyList()
            } else {
                sha = remote.sha
                val plain = SyncCrypto.decrypt(remote.content, passphrase, syncId)
                    ?: throw SyncException("同步码不正确，无法解密云端数据")
                parse(plain)
            }

            val merged = merge(local, remoteItems, idOf, updatedOf)
            val toApply = changes(local, merged, idOf, updatedOf)
            val applied = if (toApply.isEmpty()) 0 else apply(toApply)

            if (!push) {
                return Result(merged.size, applied, false, applied > 0, retried)
            }

            val encrypted = SyncCrypto.encrypt(render(merged), passphrase, syncId)
            try {
                backend.upload(path, encrypted, sha, "$message (${merged.size} items)")
                return Result(merged.size, applied, true, applied > 0, retried)
            } catch (e: ConflictException) {
                // 别的设备刚提交过：重新拉下来合并一遍
                retried = true
            }
        }
        throw SyncException("云端持续冲突，请稍后重试")
    }

    /** 按主键合并，同一主键取"更新时间更晚"的一条（删除也是一条修改） */
    fun <T> merge(
        local: List<T>,
        remote: List<T>,
        idOf: (T) -> String,
        updatedOf: (T) -> Long
    ): List<T> {
        val byId = LinkedHashMap<String, T>()
        local.forEach { v -> idOf(v).takeIf { it.isNotBlank() }?.let { byId[it] = v } }
        remote.forEach { r ->
            val id = idOf(r)
            if (id.isBlank()) return@forEach
            val l = byId[id]
            if (l == null || updatedOf(r) > updatedOf(l)) byId[id] = r
        }
        return byId.values.toList()
    }

    /** 合并结果里相对本地有变化的那些（才需要写回本地） */
    fun <T> changes(
        local: List<T>,
        merged: List<T>,
        idOf: (T) -> String,
        updatedOf: (T) -> Long
    ): List<T> {
        val localById = local.associateBy { idOf(it) }
        return merged.filter { m ->
            val l = localById[idOf(m)]
            l == null || updatedOf(l) != updatedOf(m)
        }
    }
}
