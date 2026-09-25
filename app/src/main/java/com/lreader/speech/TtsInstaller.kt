package com.lreader.speech

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把已下载的语音引擎 APK 交给系统安装器。
 *
 * Android 不允许一个 App 静默安装另一个 App：这里只是把文件通过 FileProvider 递给系统，
 * 真正的安装由系统的「包安装程序」弹窗完成，用户点「安装」后才生效
 * （首次还会要求允许「安装未知来源应用」）。
 */
object TtsInstaller {

    /** @return 成功=已拉起系统安装界面；失败=携带原因（文件缺失 / 没有可处理该 Intent 的应用） */
    fun install(context: Context, apk: File): Result<Unit> = runCatching {
        require(apk.exists() && apk.length() > 0L) { "安装包不存在，请先下载" }
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
