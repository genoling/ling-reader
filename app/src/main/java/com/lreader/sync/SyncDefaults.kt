package com.lreader.sync

import android.content.Context
import com.lreader.R

/**
 * 内置的同步仓库默认值：让用户**点一下就能生成同步码**，不用填任何内容。
 *
 * 仓库是作者自己的私有仓库（一个仓库服务一小圈人）：每人一个目录 `users/<同步 id>/`，
 * 数据经 [SyncCrypto] 端到端加密，同一个仓库里的其他人也读不到明文。
 *
 * ⚠️ [token] 由构建时注入（`local.properties` 的 `sync.token`，**不进版本库**，
 * 见 `app/build.gradle.kts` 的 `syncToken` → `resValue("string", "sync_token")`），
 * 但终究在 APK 里 —— 拿到 APK 的人可以读写这个仓库（能删、读到的只有密文）。
 * 因此请用只授权该仓库、权限仅 Contents 读写的 fine-grained token，泄露了随时吊销。
 */
object SyncDefaults {

    const val OWNER = "genoling"
    const val REPO = "ling-reader-sync"
    const val BRANCH = "main"

    /** 构建时注入的仓库令牌；空串 = 本次构建没带（设置页会退化成手填 token 的对话框） */
    fun token(context: Context): String =
        context.getString(R.string.sync_token).trim()

    /** 是否可以直接「一键生成」（带内置令牌构建时为 true） */
    fun ready(context: Context): Boolean = token(context).isNotBlank()

    /** 仓库地址，UI 上给用户看 */
    val repoLabel: String get() = "$OWNER/$REPO"
}
