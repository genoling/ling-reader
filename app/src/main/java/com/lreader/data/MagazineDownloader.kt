package com.lreader.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 外刊下载的**进程级单例**（与 [com.lreader.dict.DictManager] 同样的思路）。
 *
 * 以前下载跑在杂志页的 `rememberCoroutineScope()` 里：一切到别的 Tab，杂志页离开组合 →
 * 协程被取消 → 下载就「停」在那里（用户反馈的「切换 UI 下载就停止」就是这个）。
 *
 * 现在下载与界面解耦：只要进程还活着就一直下，页面销毁 / 重建 / 切 Tab 都不影响，
 * 回来还能接着看进度；只有用户点「取消」才会停。
 */
class MagazineDownloader private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: MagazineDownloader? = null

        fun get(context: Context): MagazineDownloader = instance ?: synchronized(this) {
            instance ?: MagazineDownloader(context.applicationContext).also { instance = it }
        }
    }

    private val mags = MagazineRepository(context)
    private val books = BookRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 正在下载的期号；null = 空闲 */
    var downloading by mutableStateOf<String?>(null)
        private set

    /** 当前进度 0~1 */
    var progress by mutableStateOf(0f)
        private set

    /** 一次性提示（完成 / 失败 / 已取消）。界面弹完 snackbar 后置空 */
    var message by mutableStateOf<String?>(null)

    /** 成功加入书架的次数；杂志页据此刷新「已在书架」标记 */
    var shelfRevision by mutableStateOf(0)
        private set

    fun isDownloading(title: String): Boolean = downloading == title

    fun busy(): Boolean = job?.isActive == true

    /** 开始下载并自动入书架（重复调用会被忽略） */
    fun start(mag: Magazine) {
        if (job?.isActive == true) return
        downloading = mag.title
        progress = 0f
        message = null
        job = scope.launch {
            try {
                val file = mags.download(mag) { p -> progress = p }
                if (file == null) {
                    message = "下载失败：${mag.title}（换个网络或稍后重试）"
                } else {
                    // 导入书架（书名 = 文件名，见 MagazineRepository.download 的注释）
                    val imported = withContext(Dispatchers.IO) {
                        val book = books.importFile(file) ?: return@withContext false
                        val list = books.load().toMutableList()
                        if (list.none { it.id == book.id }) list.add(book)
                        books.save(list)
                        books.ensureCover(book)
                        true
                    }
                    file.delete()
                    if (imported) {
                        shelfRevision += 1
                        message = "已加入书架：${mag.title}"
                    } else {
                        message = "导入失败：${mag.title}"
                    }
                }
            } catch (e: CancellationException) {
                message = "已取消下载：${mag.title}"
                throw e
            } finally {
                downloading = null
                progress = 0f
            }
        }
    }

    /** 手动取消：中断协程并丢掉半成品 */
    fun cancel() {
        val title = downloading ?: return
        job?.cancel()
        job = null
        downloading = null
        progress = 0f
        message = "已取消下载：$title"
    }
}
