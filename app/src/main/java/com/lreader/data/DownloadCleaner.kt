package com.lreader.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 清理**没下完的临时文件**。
 *
 * 所有下载都先写 `<正式文件名>.part`，只有校验通过才改名成正式文件 ——
 * 正常结束（成功 / 失败 / 取消）时下载方会自己删 `.part`，但**进程被杀 / 崩溃 / 系统回收**
 * 就来不及删，会在内部存储里留下几十 MB 的半个词典。
 *
 * 因此每次进程启动（见 `LRreaderApp`）扫一遍 filesDir 与 cacheDir，把 `.part` 清掉：
 * 这时不可能有正在进行的下载，删除是安全的。
 */
object DownloadCleaner {

    private const val TAG = "DownloadCleaner"
    private const val PART_SUFFIX = ".part"

    /** @return 清掉的字节数（用于日志） */
    fun cleanStaleParts(context: Context): Long {
        var freed = 0L
        var count = 0
        listOfNotNull(context.filesDir, context.cacheDir).forEach { dir ->
            val parts = dir.listFiles { f: File -> f.isFile && f.name.endsWith(PART_SUFFIX) } ?: return@forEach
            parts.forEach { p ->
                freed += p.length()
                if (p.delete()) count++
            }
        }
        if (count > 0) Log.i(TAG, "清理未完成的下载 $count 个，释放 ${freed / 1024} KB")
        return freed
    }
}
