package com.lreader.sync

/**
 * 远端文件：内容 + 版本 sha。
 *
 * `sha` 是"乐观锁"凭据 —— 写回时带上它，若期间别人改过文件，服务端会拒绝（见 [ConflictException]），
 * 调用方重新拉取合并即可。这正是双向合并需要的语义，无需自建版本号。
 */
data class RemoteFile(val content: String, val sha: String?)

/** 同步后端：一个"云端小文件仓库"。当前实现：[GithubBackend]。 */
interface SyncBackend {

    /** 供 UI 展示的后端描述，如 `github:genoling/lr-sync` */
    val label: String

    /** 下载文件；不存在返回 null */
    fun download(path: String): RemoteFile?

    /**
     * 上传（覆盖）文件。
     *
     * @param expectedSha 非空时作为乐观锁；远端已被改动会抛 [ConflictException]
     */
    fun upload(path: String, content: String, expectedSha: String?, message: String)
}

/** 乐观锁冲突：远端在我们读取之后被改过（两端同时同步） */
class ConflictException(message: String) : Exception(message)

/** 同步过程中的可读错误（网络、权限、口令等），UI 直接展示 message */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
