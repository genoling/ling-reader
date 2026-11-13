package com.lreader.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lreader.data.DownloadSource
import com.lreader.data.UpdateController

/**
 * 启动时的「发现新版本」弹窗。
 *
 * 三个按钮的语义（按用户确认的交互）：
 *  - 「立即更新」：下载 + 拉起系统安装界面，下载进度就地显示；
 *  - 「本次不更新」：只忽略这一个版本号，下个 / 下下个版本仍会提示；
 *  - 「以后不提醒」：永久关掉自动检查（设置页可重新打开）。
 *
 * 版本说明（notes）已在 [com.lreader.data.UpdateRepository] 里压成纯文本，这里直接显示。
 *
 * 两个实现细节（都是实机验证过的坑）：
 *  1. 不用 `AlertDialog`：Material3 1.1.2 的正文槽在内容变高（下载时插入进度条）时高度不重测，
 *     实测新增节点被压成 0 高度，界面上只有按钮区变化；
 *  2. 用 `key(downloading, error)` 包住 Dialog：Compose 1.4 的 Dialog 窗口尺寸只在首次测量时确定，
 *     内容变高不会自动撑开窗口，进度条会挤掉底部按钮 —— key 变化时重建窗口就能重新测量。
 */
@Composable
fun UpdatePromptDialog(update: UpdateController) {
    // 直接读 controller 的 Compose 状态：任一变化都会重跑本函数
    val info = update.prompt ?: return
    val downloading = update.downloading
    val progress = update.progress
    val speedText = DownloadSource.speedText(update.speed)
    val error = update.error

    key(downloading, error != null) {
        Dialog(onDismissRequest = { update.hidePrompt() }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.widthIn(min = 280.dp, max = 420.dp).padding(22.dp)) {
                    Text(
                        "发现新版本 ${info.version}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "当前版本 v${update.versionName}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(info.notes, fontSize = 13.sp, lineHeight = 19.sp)
                    }

                    if (downloading) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "正在下载…" + if (speedText.isEmpty()) "" else " $speedText",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        // 下载前要并发测速挑最快的源，这期间拿不到总大小（progress 恒为 0）：
                        // 用不确定进度条转起来，免得看起来像卡在 0%。
                        // 两种模式都不带百分比文字，保持弹窗高度一致（高度一变窗口就要重测）。
                        if (progress > 0f) {
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (downloading) {
                            TextButton(onClick = { update.cancelDownload() }) {
                                Text("取消下载", fontSize = 13.sp)
                            }
                        } else {
                            TextButton(onClick = { update.skipThisVersion() }) {
                                Text("本次不更新", fontSize = 13.sp)
                            }
                            TextButton(onClick = { update.disableAutoCheck() }) {
                                Text("以后不提醒", fontSize = 13.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { update.downloadAndInstall() }) {
                                Text("立即更新", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
