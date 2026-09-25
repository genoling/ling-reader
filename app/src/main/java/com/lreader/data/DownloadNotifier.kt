package com.lreader.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lreader.MainActivity

/**
 * 下载进度通知：让用户**离开 App 也能在状态栏看到进度、一键取消**。
 *
 * 设计要点：
 *  - 只用普通通知，不启用前台服务：下载协程活在 Application 进程里，退到后台照常继续；
 *    通知只是「镜子」，不承担保持进程的义务（进程被系统回收则下载中断，下次进入会自动清掉半成品）。
 *  - 「取消」按钮走 [DownloadCancelReceiver] 广播 → [DownloadCenter]，见那里的说明。
 *  - Android 13+ 需 `POST_NOTIFICATIONS` 运行时权限；**未授权时通知静默失败但下载照常**，
 *    故这里所有通知操作都吞掉异常（不能因为弹不出通知就把下载搞挂）。
 *  - 通知 id 由资源 id 哈希而来：同一个资源反复下载复用同一条通知，不会刷屏。
 */
object DownloadNotifier {

    private const val CHANNEL_ID = "downloads"
    /** 下载中断/完成后通知停留时间，让用户有机会瞄一眼结果 */
    private const val LINGER_MS = 4000L

    /** 每条通知的 id（抹掉符号位，避免与其它通知撞负数 id） */
    private fun notifyId(id: String): Int = id.hashCode() and 0x7fffffff

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "资源下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "词典 / 语音引擎 / 应用更新的下载进度" }
        )
    }

    /** 开始下载：不确定进度条的占位通知（拿不到总大小时也显示得出来） */
    fun start(context: Context, id: String, title: String) {
        notify(context, id, builder(context, id, title, "正在下载…", progress = null, ongoing = true).build())
    }

    /** 进度更新，[progress] 为 0~1 */
    fun progress(context: Context, id: String, title: String, progress: Float) {
        val pct = (progress * 100).toInt().coerceIn(0, 99)
        notify(
            context, id,
            builder(context, id, title, "已下载 $pct%", progress = progress, ongoing = true).build()
        )
    }

    /** 下载完成：去掉取消按钮与进度条，稍后自动消失 */
    fun finish(context: Context, id: String, title: String, text: String) {
        notify(
            context, id,
            builder(context, id, title, text, progress = null, ongoing = false)
                .setAutoCancel(true)
                .setTimeoutAfter(LINGER_MS)
                .build()
        )
    }

    /** 下载失败 / 被取消：保留一句话原因，稍后自动消失 */
    fun failed(context: Context, id: String, title: String, text: String) {
        finish(context, id, title, text)
    }

    /** 直接收掉通知（用户点了取消、或任务被删除时） */
    fun dismiss(context: Context, id: String) {
        try {
            NotificationManagerCompat.from(context).cancel(notifyId(id))
        } catch (e: Exception) {
            // 通知服务不可用（极少数 ROM）时忽略
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun builder(
        context: Context,
        id: String,
        title: String,
        text: String,
        progress: Float?,
        ongoing: Boolean
    ): NotificationCompat.Builder {
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setSilent(true)
            .setContentIntent(openApp(context))

        if (progress != null) {
            b.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
        } else if (ongoing) {
            b.setProgress(0, 0, true)   // 不确定进度（总大小未知）
        } else {
            b.setProgress(0, 0, false)  // 结束后彻底去掉进度条
        }

        if (ongoing) b.addAction(0, "取消", cancelIntent(context, id))
        return b
    }

    /** 点通知回到 App（设置页里有下载入口） */
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = DownloadCancelReceiver.ACTION_CANCEL
            putExtra(DownloadCancelReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, notifyId(id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notify(context: Context, id: String, notification: Notification) {
        ensureChannel(context)
        try {
            NotificationManagerCompat.from(context).notify(notifyId(id), notification)
        } catch (e: SecurityException) {
            // Android 13+ 用户拒绝了通知权限：下载继续，只是看不到进度
        } catch (e: Exception) {
            // 部分 ROM 的通知限制，忽略
        }
    }
}
