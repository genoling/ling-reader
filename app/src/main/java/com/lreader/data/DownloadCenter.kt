package com.lreader.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 正在进行的下载任务登记处 —— 让**状态栏通知里的「取消」按钮**能找到对应的下载。
 *
 * 通知的取消动作是一个 [DownloadCancelReceiver] 广播（可能发生在 App 处于后台时），
 * 广播里拿不到 Compose 的状态、也拿不到协程 Job，所以下载发起方在这里登记一个
 * 「怎么取消我」的回调，取消广播按 id 取出来调用即可。
 *
 * 只登记**正在下载中**的任务：开始下载时 [register]，结束（成功 / 失败 / 取消）时
 * 必须 [unregister]，否则用户会点到一个已经结束的任务。
 */
object DownloadCenter {

    private val cancellers = ConcurrentHashMap<String, () -> Unit>()

    fun register(id: String, cancel: () -> Unit) {
        cancellers[id] = cancel
    }

    fun unregister(id: String) {
        cancellers.remove(id)
    }

    /** 按 id 取消（没有该任务则什么都不做，例如通知点晚了） */
    fun cancel(id: String) {
        cancellers[id]?.invoke()
    }
}
