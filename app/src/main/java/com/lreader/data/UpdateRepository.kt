package com.lreader.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** 线上最新版本信息（来自 GitHub Release） */
data class ReleaseInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
    val sizeBytes: Long
)

/**
 * 版本信息 + 一键更新。
 *
 * - 检查更新：GitHub Release API（失败回退 ghproxy 镜像）；
 * - 下载安装包：Release 附件（失败回退 ghproxy 镜像），下载完交给系统安装器
 *   （复用 `TtsInstaller` 的 FileProvider + ACTION_VIEW，**用户确认后才真正安装**）。
 */
class UpdateRepository(private val context: Context) {

    companion object {
        const val REPO = "genoling/ling-reader"

        private val API_URLS = listOf(
            "https://api.github.com/repos/$REPO/releases/latest",
            "https://ghproxy.net/https://api.github.com/repos/$REPO/releases/latest"
        )

        /** 逐段比较版本号：`1.10.0` > `1.9.2` */
        fun isNewer(latest: String, current: String): Boolean {
            fun parts(s: String) = Regex("\\d+").findAll(s).map { it.value.toIntOrNull() ?: 0 }.toList()
            val a = parts(latest)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 读取自身包信息（API 33 起用 PackageInfoFlags，避免 deprecated 告警） */
    private fun selfPackageInfo(): android.content.pm.PackageInfo? = try {
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
    } catch (e: Exception) {
        null
    }

    /** 当前安装包的版本名（如 `1.2.0`） */
    fun currentVersionName(): String = selfPackageInfo()?.versionName ?: "?"

    /** 当前安装包的 versionCode */
    fun currentVersionCode(): Long {
        val info = selfPackageInfo() ?: return 0L
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /** 查询线上最新版本 */
    suspend fun check(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (url in API_URLS) {
            try {
                val text = get(url) ?: continue
                val o = JSONObject(text)
                val tag = o.optString("tag_name").ifBlank { return@withContext Result.failure(IllegalStateException("没有可用的版本信息")) }
                val assets = o.optJSONArray("assets")
                var apkUrl = ""
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url")
                            size = a.optLong("size")
                            break
                        }
                    }
                }
                return@withContext Result.success(
                    ReleaseInfo(
                        version = tag,
                        notes = o.optString("body").trim(),
                        apkUrl = apkUrl,
                        pageUrl = o.optString("html_url"),
                        sizeBytes = size
                    )
                )
            } catch (e: Exception) {
                lastError = e
            }
        }
        Result.failure(lastError ?: IllegalStateException("检查更新失败，请检查网络"))
    }

    /**
     * 下载最新版安装包到 `cacheDir`。
     * @param onProgress 0~1，**主线程**回调
     */
    suspend fun download(info: ReleaseInfo, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            if (info.apkUrl.isBlank()) return@withContext null
            val target = File(context.cacheDir, "update-${info.version}.apk")
            val tmp = File(context.cacheDir, "update-${info.version}.apk.part")
            val urls = listOf(info.apkUrl, "https://ghproxy.net/" + info.apkUrl)
            for (url in urls) {
                var ok = false
                try {
                    val request = Request.Builder().url(url).header("User-Agent", "LingReader").build()
                    ok = http.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        val body = resp.body ?: return@use false
                        val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                        body.byteStream().use { input ->
                            FileOutputStream(tmp).use { out ->
                                val buf = ByteArray(1 shl 16)
                                var read = 0L
                                var last = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    read += n
                                    val now = System.currentTimeMillis()
                                    if (now - last >= 200) {
                                        last = now
                                        if (total > 0) {
                                            val p = (read.toFloat() / total).coerceIn(0f, 0.99f)
                                            main.post { onProgress(p) }
                                        }
                                    }
                                }
                                out.flush()
                                out.fd.sync()
                            }
                        }
                        tmp.length() >= 1024
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    tmp.delete()
                }
                if (ok) {
                    if (target.exists()) target.delete()
                    if (tmp.renameTo(target)) {
                        main.post { onProgress(1f) }
                        return@withContext target
                    }
                    tmp.delete()
                }
            }
            null
        }

    private fun get(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", "LingReader").build()
        http.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }
}
