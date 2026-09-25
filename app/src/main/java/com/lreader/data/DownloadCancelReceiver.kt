package com.lreader.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 状态栏下载通知里「取消」按钮的落点。
 *
 * 用**静态注册**的广播（见 AndroidManifest）而不是动态注册：App 退到后台时
 * Compose 已经不在前台，动态接收器可能已被注销；静态接收器只要进程活着就能收到。
 * 取消本身不会拉起进程 —— 进程都没了，下载自然也没了，无需处理。
 */
class DownloadCancelReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CANCEL = "com.lreader.action.CANCEL_DOWNLOAD"
        const val EXTRA_ID = "download_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        DownloadCenter.cancel(id)
    }
}
