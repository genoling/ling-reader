package com.lreader.sync

import android.util.Base64
import org.json.JSONObject

/**
 * 同步配置：仓库 + token + 同步 id + 口令。
 */
data class SyncConfig(
    val owner: String,
    val repo: String,
    val branch: String,
    /** 用户目录名（仓库里是 `users/<syncId>/`） */
    val syncId: String,
    val token: String,
    /** 加密口令（由它派生出 AES 密钥，云端只有密文） */
    val passphrase: String
) {
    /** 本用户在仓库里的文件路径 */
    val path: String get() = VocabSync.pathOf(syncId)

    val repoLabel: String get() = "$owner/$repo"
}

/**
 * 同步码：把 [SyncConfig] 打包成一个字符串，第二台设备粘贴/扫码即可配对，**用户不用记任何密码**。
 *
 * 形如 `LR1.<base64url>`；`LR1.` 前缀用于粘贴时快速校验格式。
 *
 * ⚠️ 同步码本身就等于凭据（内含 token 与口令），只在自己的设备之间传递，不要发到群里。
 */
object SyncCode {

    private const val PREFIX = "LR1."

    fun encode(c: SyncConfig): String {
        val json = JSONObject().apply {
            put("o", c.owner)
            put("r", c.repo)
            put("b", c.branch)
            put("s", c.syncId)
            put("t", c.token)
            put("p", c.passphrase)
        }
        return PREFIX + Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
    }

    /** 解析同步码；格式不对返回 null（UI 据此提示"同步码无效"） */
    fun decode(code: String): SyncConfig? = try {
        val raw = code.trim()
        if (!raw.startsWith(PREFIX)) {
            null
        } else {
            val json = JSONObject(
                String(
                    Base64.decode(raw.removePrefix(PREFIX), Base64.NO_WRAP or Base64.URL_SAFE),
                    Charsets.UTF_8
                )
            )
            val c = SyncConfig(
                owner = json.getString("o").trim(),
                repo = json.getString("r").trim(),
                branch = json.optString("b", "main").trim().ifBlank { "main" },
                syncId = json.getString("s").trim(),
                token = json.getString("t").trim(),
                passphrase = json.getString("p")
            )
            if (c.owner.isBlank() || c.repo.isBlank() || c.syncId.isBlank() || c.token.isBlank()) {
                null
            } else {
                c
            }
        }
    } catch (e: Exception) {
        null
    }
}
