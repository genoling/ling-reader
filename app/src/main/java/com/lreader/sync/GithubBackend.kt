package com.lreader.sync

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub Contents API 后端（**私有仓库**）。
 *
 * 一个仓库可以服务一小圈人：每人一个目录 `users/<同步id>/...`，互相看不到对方数据。
 * 读用 GET（顺带拿到 `sha`），写用 PUT 时带上 `sha` 做**乐观锁** —— 两端同时同步会返回
 * 409/422，对应 [ConflictException]，调用方重新拉取合并即可（[VocabSync.run] 已自动重试）。
 *
 * 需要的 token：fine-grained PAT，权限只需 **Contents: Read and write**，
 * 且限定到这一个仓库 —— 即使泄露也只影响这一个仓库，随时可吊销。
 */
class GithubBackend(
    private val owner: String,
    private val repo: String,
    private val branch: String = "main",
    private val token: String
) : SyncBackend {

    override val label: String = "github:$owner/$repo"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authed(builder: Request.Builder): Request.Builder = builder
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun contentsUrl(path: String) =
        "https://api.github.com/repos/$owner/$repo/contents/$path"

    override fun download(path: String): RemoteFile? {
        val req = authed(Request.Builder().url("${contentsUrl(path)}?ref=$branch")).get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> null
                    !resp.isSuccessful ->
                        throw SyncException("云端读取失败（HTTP ${resp.code}）")

                    else -> {
                        val body = JSONObject(resp.body?.string().orEmpty())
                        // Contents API 的 content 是**带换行的 base64**
                        val raw = body.optString("content").replace("\n", "").replace("\r", "")
                        val text = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
                        RemoteFile(text, body.optString("sha").takeIf { it.isNotBlank() })
                    }
                }
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncException("网络异常：${e.message}", e)
        }
    }

    override fun upload(path: String, content: String, expectedSha: String?, message: String) {
        val json = JSONObject().apply {
            put("message", message)
            put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            put("branch", branch)
            if (!expectedSha.isNullOrBlank()) put("sha", expectedSha)
        }
        val req = authed(
            Request.Builder().url(contentsUrl(path))
                .put(json.toString().toRequestBody(JSON))
        ).build()
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> Unit
                    // 409：sha 对不上；422：远端已存在但没带 sha —— 都算冲突，重新拉取即可
                    resp.code == 409 || resp.code == 422 ->
                        throw ConflictException("云端已被其它设备修改")

                    resp.code == 401 || resp.code == 403 ->
                        throw SyncException("token 无权限（HTTP ${resp.code}），请确认 Contents 读写权限")

                    else -> throw SyncException("云端写入失败（HTTP ${resp.code}）")
                }
            }
        } catch (e: ConflictException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncException("网络异常：${e.message}", e)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }

    // ------------------------------------------------------------------
    // 管理员功能（列目录 / 删除）
    // ------------------------------------------------------------------

    /** 云端目录项（管理员面板列用户目录用） */
    data class Entry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val sha: String
    )

    /** 列出目录内容（path 为空 = 仓库根目录）；不存在或不是目录时返回空表 */
    fun list(path: String): List<Entry> {
        val url = if (path.isBlank()) {
            "https://api.github.com/repos/$owner/$repo/contents?ref=$branch"
        } else {
            "${contentsUrl(path)}?ref=$branch"
        }
        val req = authed(Request.Builder().url(url)).get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> emptyList()
                    !resp.isSuccessful -> throw SyncException("云端读取失败（HTTP ${resp.code}）")

                    else -> {
                        val text = resp.body?.string().orEmpty().trim()
                        if (!text.startsWith("[")) {
                            emptyList()   // 目标是一个文件而不是目录
                        } else {
                            val arr = JSONArray(text)
                            (0 until arr.length()).map { i ->
                                val o = arr.getJSONObject(i)
                                Entry(
                                    name = o.optString("name"),
                                    path = o.optString("path"),
                                    isDir = o.optString("type") == "dir",
                                    size = o.optLong("size", 0L),
                                    sha = o.optString("sha")
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncException("网络异常：${e.message}", e)
        }
    }

    /** 删除文件（Contents API 要求带 `sha`） */
    fun delete(path: String, sha: String, message: String) {
        val json = JSONObject().apply {
            put("message", message)
            put("sha", sha)
            put("branch", branch)
        }
        val req = authed(
            Request.Builder().url(contentsUrl(path))
                .delete(json.toString().toRequestBody(JSON))
        ).build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw SyncException("云端删除失败（HTTP ${resp.code}）")
                }
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncException("网络异常：${e.message}", e)
        }
    }

    /** 递归删除目录下的所有文件（管理员删除某个用户的数据） */
    fun deleteRecursively(path: String): Int {
        var deleted = 0
        list(path).forEach { entry ->
            deleted += if (entry.isDir) {
                deleteRecursively(entry.path)
            } else {
                delete(entry.path, entry.sha, "admin: delete ${entry.path}")
                1
            }
        }
        return deleted
    }
}
