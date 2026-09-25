package com.lreader.data

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * GitHub Release 附件的**多镜像测速回退**（词典 / 语音引擎 / 应用更新共用）。
 *
 * 背景：附件的直链是 `https://github.com/<repo>/releases/download/<tag>/<file>`，
 * 国内直连常被限速到 100 KB/s 上下；各类 gh 代理镜像则时快时慢、还会随时失效
 * （实测同一时刻：直连 131 KB/s、ghproxy.net 89 KB/s、gh-proxy.com 234 KB/s、ghfast.top 231 KB/s）。
 * 所以下载前先**并发试拉一小段**给每个候选源打分，挑当下最快的那个正式下载，失败再依次回退。
 *
 * 注意：镜像只是把 github 直链前面接上自己的前缀（`<镜像> + 原链接`），
 * 用户自定义下载源（[com.lreader.dict.DictCatalog.sourceOf]）不展开，按原样使用。
 */
object DownloadSource {

    /** 公开的 gh 代理镜像前缀（顺序仅作并列时的兜底，实际按测速结果排） */
    private val MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/"
    )

    /** 测速试拉的字节数：太小会被 TCP 慢启动误导，太大拖慢「开始下载」的观感 */
    private const val PROBE_BYTES = 256 * 1024L

    /** 单个候选源的测速上限（并发执行，整体等待 ≈ 这个值） */
    private const val PROBE_TIMEOUT_MS = 5_000L

    /** 人读速度文本（`1.2 MB/s` / `340 KB/s`）；0 返回空串 */
    fun speedText(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1L shl 20 -> "%.1f MB/s".format(bytesPerSecond / 1048576.0)
        bytesPerSecond >= 1L shl 10 -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
        bytesPerSecond > 0 -> "$bytesPerSecond B/s"
        else -> ""
    }

    /** 把直链展开成「直链 + 各镜像」；非 github 链接（自定义源 / 第三方）原样返回 */
    fun candidates(url: String): List<String> =
        if (url.contains("://github.com/")) listOf(url) + MIRRORS.map { it + url } else listOf(url)

    /**
     * 并发测速并返回**按速度降序**的候选列表（速度为 0 的排在最后，保持原相对顺序）。
     * 全部不可用时仍返回原顺序 —— 让正式下载再试一遍，避免测速抖动直接判死。
     */
    fun rank(client: OkHttpClient, url: String, onLog: (String) -> Unit = {}): List<String> {
        val list = candidates(url)
        if (list.size == 1) return list

        val scores = ConcurrentHashMap<String, Long>()
        val probeClient = client.newBuilder()
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val latch = CountDownLatch(list.size)
        list.forEach { u ->
            val request = Request.Builder()
                .url(u)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .header("User-Agent", "LingReader")
                .build()
            probeClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    scores[u] = 0L
                    latch.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r -> scores[u] = if (r.isSuccessful) measure(r) else 0L }
                    latch.countDown()
                }
            })
        }
        latch.await(PROBE_TIMEOUT_MS + 1500, TimeUnit.MILLISECONDS)

        val ranked = list.sortedByDescending { scores[it] ?: 0L }
        ranked.forEach { u ->
            val s = scores[u] ?: 0L
            onLog(if (s > 0) "镜像测速 ${speedText(s)}：$u" else "镜像不可用：$u")
        }
        return ranked
    }

    /** 读满 [PROBE_BYTES] 后返回字节/秒（0 = 不可用） */
    private fun measure(response: Response): Long {
        val body = response.body ?: return 0L
        var read = 0L
        val start = System.currentTimeMillis()
        body.byteStream().use { input ->
            val buf = ByteArray(1 shl 15)
            while (read < PROBE_BYTES) {
                val n = input.read(buf)
                if (n <= 0) break
                read += n
            }
        }
        val ms = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        return if (read <= 0L) 0L else read * 1000 / ms
    }
}
