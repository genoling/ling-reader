package com.lreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lreader.data.SettingsStore
import com.lreader.data.VocabRepository
import com.lreader.data.YoudaoWordbook
import com.lreader.export.YoudaoXmlExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页「导出到有道单词本」分组：把**本地生词本 + 有道账号里的单词本**合并成一份 XML，
 * 供有道翻译电脑版「设置 → 批量导入」导入。
 *
 * 为什么需要合并：手机 App 与 PC 客户端各有一批词（用户实测两边不重合），
 * 只导本地会漏掉账号里的词，只导云端又漏掉本地收藏 —— 所以导出时两边一起拉、按单词去重。
 */
@Composable
fun YoudaoExportSection(settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { VocabRepository(context) }

    var busy by remember { mutableStateOf(false) }
    var localCount by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        localCount = withContext(Dispatchers.IO) { repo.count() }
    }

    val logged = YoudaoWordbook.looksLoggedIn(settings.youdaoCookie)

    Card {
        Column(Modifier.padding(14.dp)) {
            Text(
                "生成一份「有道单词本 XML」，在「有道翻译电脑版 → 设置 → 批量导入」里选它即可导入。\n" +
                    "导出内容 = 本地背单词的生词 + 已登录有道账号里的单词本；两边都有的词只导出一次" +
                    "（按单词去重），所以导入后 PC 端能同时拿到两处的全部词汇，" +
                    "并归到「${YoudaoXmlExporter.GROUP}」分组。\n" +
                    "想连账号里的词一起导出，先在上一组「同步到有道生词本」里登录。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            Text("本地生词：$localCount 个", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                if (logged) "有道账号：已登录，导出时会一并拉取账号里的词"
                else "有道账号：未登录，现在导出只会包含本地生词",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        message = null
                        failed = false
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    // Cookie 现读一次：用户可能刚在 WebView 里登录完
                                    val cookie = YoudaoWordbook.readCookie()
                                        .ifBlank { settings.youdaoCookie }
                                    val local = repo.all()
                                    val cloud = if (YoudaoWordbook.looksLoggedIn(cookie)) {
                                        YoudaoWordbook.allWords(cookie).map {
                                            YoudaoXmlExporter.CloudWord(it.word, it.phonetic, it.trans)
                                        }
                                    } else {
                                        emptyList()
                                    }
                                    val merged = YoudaoXmlExporter.merge(local, cloud)
                                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                                    merged to YoudaoXmlExporter.writeTo(File(dir, "exports"), merged.xml)
                                }
                            }
                            busy = false
                            r.fold(
                                onSuccess = { (m, file) ->
                                    localCount = m.localCount
                                    message = buildString {
                                        append("已导出 ").append(m.total).append(" 词（本地 ")
                                        append(m.localCount).append(" + 有道账号 ")
                                        append(m.cloudCount).append("，去重后云端独有 ")
                                        append(m.fromCloudOnly).append("）")
                                        if (m.cloudCount == 0) append("，未登录有道，只含本地生词")
                                        append("\n文件：").append(file.absolutePath)
                                    }
                                },
                                onFailure = { e ->
                                    failed = true
                                    message = "导出失败：${e.message ?: "未知错误"}"
                                }
                            )
                        }
                    },
                    enabled = !busy
                ) {
                    Text(if (busy) "导出中…" else "导出 XML", fontSize = 12.sp)
                }
                Text("导出后在手机里找 exports 目录取文件", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
