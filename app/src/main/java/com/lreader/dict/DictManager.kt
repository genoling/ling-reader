package com.lreader.dict

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lreader.data.DownloadCenter
import com.lreader.data.DownloadNotifier
import com.lreader.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 词典资源的安装管理器（**进程级单例**）。
 *
 * 职责：
 *  1. 记录每个资源是否已安装（`filesDir/<fileName>` 是否存在）；
 *  2. 内置资源（`assetName != null`）：首次使用时从 APK `assets/` 释放到 `filesDir`；
 *  3. 下载型资源（`assetName == null`，即 115MB 主词典）：从 [DictCatalog] 描述的
 *     下载源（默认 GitHub Release）按需下载，带进度、可取消、可删除、下载后校验；
 *  4. 状态变化通过 [onChanged]（主线程）通知 UI。
 *
 * 所有落盘都走 `<文件名>.part` 中转，**只有校验通过才 rename 成正式文件**，
 * 因此「文件存在」与「文件可用」等价，进程被杀死也不会留下半个数据库。
 *
 * 旧版本升级上来的用户无需重新下载：v1.0.10 及以前 `DictDatabase` 释放的目标路径
 * 就是 `filesDir/dict_en_zh.db`，与本类的 [DictResource.fileName] 完全一致。
 */
class DictManager private constructor(private val context: Context) {

    /** 单个资源的安装状态（不可变快照，供 Compose 读取） */
    data class Status(
        val installed: Boolean = false,
        val downloading: Boolean = false,
        val progress: Float = 0f,
        val error: String? = null
    )

    companion object {
        private const val TAG = "DictManager"
        private const val PART_SUFFIX = ".part"

        @Volatile
        private var instance: DictManager? = null

        fun get(context: Context): DictManager =
            instance ?: synchronized(this) {
                instance ?: DictManager(context.applicationContext).also { instance = it }
            }
    }

    private val settings = SettingsStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val states = LinkedHashMap<String, Status>()

    /** 词典之外额外注册的下载资源（如语音引擎 APK），见 [register] */
    private val extra = LinkedHashMap<String, DictResource>()
    private val main = Handler(Looper.getMainLooper())

    /** 状态变化回调，**主线程**回调；UI 在 DisposableEffect 里注册/注销 */
    var onChanged: (() -> Unit)? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // GitHub Release 下载会 302 到 objects.githubusercontent.com，必须跟随
            .followRedirects(true)
            .build()
    }

    init {
        DictCatalog.resources.forEach { res ->
            states[res.id] = Status(installed = fileReady(res))
        }
    }

    // ------------------------------------------------------------------ 查询

    fun resources(): List<DictResource> = DictCatalog.resources

    /**
     * 注册额外资源（如 `TtsCatalog` 里的语音引擎 APK），使其复用同一套
     * 「下载 / 进度 / 取消 / 校验 / 删除 / 状态回调」机制。启动时调用一次即可。
     */
    fun register(resources: List<DictResource>) {
        resources.forEach { res ->
            extra[res.id] = res
            if (!states.containsKey(res.id)) states[res.id] = Status(installed = fileReady(res))
        }
    }

    fun resourceOf(id: String): DictResource? = DictCatalog.byId(id) ?: extra[id]

    fun statusOf(id: String): Status =
        states[id] ?: resourceOf(id)?.let { Status(installed = fileReady(it)) } ?: Status()

    /** 该资源是否已就绪（可直接打开使用） */
    fun isInstalled(id: String): Boolean = statusOf(id).installed

    fun fileOf(res: DictResource): File = File(context.filesDir, res.fileName)

    /** 已占用磁盘体积（未安装返回 0） */
    fun sizeOnDisk(res: DictResource): Long = fileOf(res).let { if (it.exists()) it.length() else 0L }

    /** 当前生效的下载源前缀（用户自定义优先） */
    fun sourceUrl(): String = DictCatalog.sourceOf(settings.dictSourceBase)

    /** 某资源在当前下载源下的完整地址（设置页展示用） */
    fun urlOf(res: DictResource): String = DictCatalog.urlOf(res, settings.dictSourceBase)

    /** 重新探测所有资源的落盘情况（用户在文件管理器删了文件后用） */
    fun refresh() {
        DictCatalog.resources.forEach { res -> setState(res.id) { it.copy(installed = fileReady(res)) } }
    }

    // -------------------------------------------------------------- 内置释放

    /**
     * 确保资源可用：已安装直接返回；内置资源则从 assets 释放。
     *
     * **下载型资源（主词典）不会在这里联网** —— 返回 false，由设置页引导用户去下载，
     * 避免「进阅读页偷偷下 115MB」。调用方需处理 false。
     *
     * 建议在 IO 线程调用（可能拷贝几十 MB）。
     */
    fun ensureInstalled(res: DictResource): Boolean {
        if (fileReady(res)) {
            if (!statusOf(res.id).installed) setState(res.id) { it.copy(installed = true) }
            return true
        }
        val assetName = res.assetName ?: return false
        val ok = extractAsset(assetName, res)
        setState(res.id) { it.copy(installed = ok) }
        return ok
    }

    fun ensureInstalled(id: String): Boolean =
        DictCatalog.byId(id)?.let { ensureInstalled(it) } ?: false

    /** assets → filesDir（先写 `.part` 再改名，避免半成品被当成已安装） */
    private fun extractAsset(assetName: String, res: DictResource): Boolean {
        val target = fileOf(res)
        val part = File(context.filesDir, res.fileName + PART_SUFFIX)
        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("重命名失败")
            true
        } catch (e: Exception) {
            Log.w(TAG, "释放内置资源 $assetName 失败", e)
            part.delete()
            false
        }
    }

    // ------------------------------------------------------------------ 下载

    /** 开始下载（重复调用会被忽略）；进度与结果通过 [onChanged] + [statusOf] 获取 */
    fun download(res: DictResource) {
        if (jobs[res.id]?.isActive == true) return
        val target = fileOf(res)
        val part = File(context.filesDir, res.fileName + PART_SUFFIX)
        val title = "正在下载 ${res.name}"

        setState(res.id) { it.copy(downloading = true, progress = 0f, error = null) }
        // 状态栏进度通知 + 登记取消入口（通知里的「取消」按钮经 DownloadCancelReceiver 找回来）
        DownloadNotifier.start(context, res.id, title)
        DownloadCenter.register(res.id) { cancel(res) }

        val job = scope.launch {
            try {
                val url = urlOf(res)
                Log.i(TAG, "开始下载 ${res.name}：$url")
                val request = Request.Builder().url(url).build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IOException("响应内容为空")
                    val total = body.contentLength().takeIf { it > 0 } ?: res.sizeBytes
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var read = 0L
                            var lastTick = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                read += n
                                val now = System.currentTimeMillis()
                                if (now - lastTick >= 200) {
                                    lastTick = now
                                    val p = if (total > 0) (read.toFloat() / total) else 0f
                                    setState(res.id) { it.copy(progress = p.coerceIn(0f, 0.99f)) }
                                    // 服务端没给 Content-Length 时保持「不确定进度」的通知，别显示假百分比
                                    if (total > 0) DownloadNotifier.progress(context, res.id, title, p)
                                }
                            }
                            out.flush()
                            out.fd.sync()
                        }
                    }
                }

                val len = part.length()
                if (res.sizeBytes > 0 && len != res.sizeBytes) {
                    throw IOException("文件大小不符：期望 ${res.sizeBytes} 字节，实际 $len 字节")
                }
                if (res.fileName.endsWith(".db") && !isSqlite(part)) {
                    throw IOException("文件不是有效的 SQLite 数据库，下载可能被劫持或损坏")
                }
                if (res.fileName.endsWith(".apk") && !isZip(part)) {
                    throw IOException("文件不是有效的 APK 安装包，下载可能被劫持或损坏")
                }

                // 校验通过才改名：正式文件一旦出现就一定可用（半成品只会是 .part）
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("写入目标文件失败（存储空间不足？）")
                setState(res.id) { Status(installed = true) }
                DownloadNotifier.finish(context, res.id, res.name, "下载完成，可以使用了")
                Log.i(TAG, "下载完成：${res.name}（$len 字节）")
            } catch (e: CancellationException) {
                part.delete()
                setState(res.id) { Status(installed = fileReady(res)) }
                DownloadNotifier.dismiss(context, res.id)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "下载失败：${res.name}", e)
                part.delete()
                val message = friendlyError(e)
                setState(res.id) { Status(installed = fileReady(res), error = message) }
                DownloadNotifier.failed(context, res.id, res.name, "下载失败：$message")
            } finally {
                jobs.remove(res.id)
                DownloadCenter.unregister(res.id)
            }
        }
        jobs[res.id] = job
    }

    /** 取消下载（已下载的部分会被丢弃） */
    fun cancel(res: DictResource) {
        jobs[res.id]?.cancel()
        jobs.remove(res.id)
        DownloadCenter.unregister(res.id)
        DownloadNotifier.dismiss(context, res.id)
        File(context.filesDir, res.fileName + PART_SUFFIX).delete()
        setState(res.id) { Status(installed = fileReady(res)) }
    }

    /** 按 id 取消（状态栏通知的「取消」按钮走这里） */
    fun cancelById(id: String) {
        (DictCatalog.byId(id) ?: extra[id])?.let { cancel(it) }
    }

    /** 删除已安装的**可删除**资源（内置资源返回 false，防止把分级高亮彻底删没） */
    fun remove(res: DictResource): Boolean {
        if (!res.removable) return false
        jobs[res.id]?.cancel()
        jobs.remove(res.id)
        DownloadCenter.unregister(res.id)
        DownloadNotifier.dismiss(context, res.id)
        File(context.filesDir, res.fileName + PART_SUFFIX).delete()
        val ok = fileOf(res).delete()
        setState(res.id) { Status() }
        return ok
    }

    // ------------------------------------------------------------------ 内部

    private fun fileReady(res: DictResource): Boolean {
        val f = fileOf(res)
        return f.exists() && f.length() > 0
    }

    private fun isSqlite(f: File): Boolean = try {
        f.inputStream().use { input ->
            val head = ByteArray(16)
            input.read(head) == 16 && String(head, Charsets.US_ASCII).startsWith("SQLite format 3")
        }
    } catch (e: Exception) {
        false
    }

    /** APK 本质是 ZIP 包，头两字节固定为 `PK`（避免把损坏/被劫持的文件递给系统安装器） */
    private fun isZip(f: File): Boolean = try {
        f.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        }
    } catch (e: Exception) {
        false
    }

    private fun setState(id: String, block: (Status) -> Status) {
        states[id] = block(states[id] ?: Status())
        main.post { onChanged?.invoke() }
    }

    /** 把异常翻译成用户看得懂的一句话 */
    private fun friendlyError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException -> "无法解析下载源域名，请检查网络或 Wi-Fi"
            e is java.net.ConnectException -> "连接下载源失败，请检查网络"
            e is java.net.SocketTimeoutException -> "下载超时，请重试"
            e is javax.net.ssl.SSLException -> "HTTPS 证书校验失败：$msg"
            msg.startsWith("HTTP 404") -> "下载源上找不到该文件（404）：资源尚未发布，或下载源地址不对"
            msg.startsWith("HTTP") -> "服务器返回 $msg"
            msg.startsWith("文件大小不符") -> "$msg（下载不完整，请重试）"
            msg.startsWith("文件不是有效") -> msg
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }
}
