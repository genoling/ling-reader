package com.lreader.ui.settings

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lreader.data.SettingsStore
import com.lreader.data.VocabRepository
import com.lreader.data.YoudaoWordbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「有道生词本」分组：WebView 登录有道 → 自动取 Cookie → 批量同步生词。
 *
 * 用**用户自己的账号**（WebView 登录的是谁，就同步到谁的生词本），因此天然区分不同用户。
 * 有道生词本是账号级云同步的，同步后手机上的有道 APP 会自动可见。
 */
@Composable
fun YoudaoSection(settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { VocabRepository(context) }

    var cookie by remember { mutableStateOf(settings.youdaoCookie) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }

    // 目标单词本：SettingsStore 的属性不是 Compose state，读它不会触发重组，所以镜像一份（改选时两边都写）
    var targetBookId by remember { mutableStateOf(settings.youdaoBookId) }
    var targetBookName by remember { mutableStateOf(settings.youdaoBookName) }
    var books by remember { mutableStateOf<List<YoudaoWordbook.Book>>(emptyList()) }
    var showBooks by remember { mutableStateOf(false) }

    // 登录后拉一次账号里的单词本列表，供「同步到单词本」下拉选择
    LaunchedEffect(cookie) {
        books = if (YoudaoWordbook.looksLoggedIn(cookie))
            withContext(Dispatchers.IO) { YoudaoWordbook.listBooks(cookie) } else emptyList()
    }

    Card {
        Column(Modifier.padding(14.dp)) {
            Text(
                "把 LingReader 收集的生词加入「你自己的」有道账号生词本：先在下方登录有道，" +
                    "登录的是哪个账号就同步到哪个账号的生词本（因此不同用户互不影响）。" +
                    "有道生词本是账号云同步的，同步完成后手机上的有道 APP 会自动出现这些词。" +
                    "⚠️ 词默认加进「无标签」本，而有些客户端（如有道翻译电脑版）只列具名单词本，" +
                    "那样就会「看不到」——可在下面选一个具名单词本再同步。" +
                    "（使用有道网页的内部接口，若哪天失效请反馈。）",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            val logged = YoudaoWordbook.looksLoggedIn(cookie)
            Text(
                if (logged) "已登录有道" else "未登录有道",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (settings.lastYoudaoAt > 0) {
                Text(
                    "上次同步 ${fmtYoudaoTime(settings.lastYoudaoAt)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (logged) {
                val label = when {
                    targetBookId.isEmpty() || targetBookId == YoudaoWordbook.DEFAULT_BOOK_ID ->
                        "无标签（默认）"
                    else -> books.firstOrNull { it.id == targetBookId }?.name
                        ?: targetBookName.ifBlank { "未知单词本" }
                }
                Box {
                    TextButton(
                        onClick = { showBooks = true },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) {
                        Text("同步到单词本：$label ▾", fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = showBooks, onDismissRequest = { showBooks = false }) {
                        books.forEach { b ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (b.id == YoudaoWordbook.DEFAULT_BOOK_ID) "${b.name}（默认）"
                                        else b.name,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    settings.youdaoBookId = b.id
                                    settings.youdaoBookName = b.name
                                    targetBookId = b.id
                                    targetBookName = b.name
                                    showBooks = false
                                    message = "已选「${b.name}」，点「同步到有道」会把生词归到这个本"
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { showLogin = true }, enabled = !busy) {
                    Text(if (logged) "重新登录" else "登录有道", fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (busy) return@Button
                        // 重新读一次 Cookie：用户可能在 WebView 里刚登录完
                        val ck = YoudaoWordbook.readCookie().ifBlank { settings.youdaoCookie }
                        cookie = ck
                        if (!YoudaoWordbook.looksLoggedIn(ck)) {
                            message = "请先点「登录有道」，登录后再同步"
                            return@Button
                        }
                        busy = true
                        message = null
                        progress = 0 to 0
                        scope.launch {
                            val words = withContext(Dispatchers.IO) {
                                repo.all().map { it.word }.filter { it.isNotBlank() }.distinct()
                            }
                            if (words.isEmpty()) {
                                busy = false
                                message = "生词本是空的，先去阅读里收藏几个词吧"
                                return@launch
                            }
                            val r = withContext(Dispatchers.IO) {
                                YoudaoWordbook.syncAll(ck, words, targetBookId) { d, t -> progress = d to t }
                            }
                            busy = false
                            if (r.error == YoudaoWordbook.NOT_LOGGED_IN) {
                                message = "登录已失效，请点「重新登录」后再同步"
                            } else {
                                // 只有确实加成功过才记「上次同步时间」，避免全失败也显示同步过
                                if (r.ok > 0) settings.lastYoudaoAt = System.currentTimeMillis()
                                message = buildString {
                                    append("同步完成：成功 ").append(r.ok).append(" 个")
                                    if (r.fail > 0) append("，失败 ").append(r.fail).append(" 个（").append(r.error).append("）")
                                }
                            }
                        }
                    },
                    enabled = !busy
                ) {
                    Text(
                        if (busy) "同步中 ${progress.first}/${progress.second}" else "同步到有道",
                        fontSize = 12.sp
                    )
                }
                if (logged) {
                    TextButton(
                        onClick = {
                            settings.youdaoCookie = ""
                            settings.youdaoAccount = ""
                            cookie = ""
                            runCatching { CookieManager.getInstance().removeAllCookies(null) }
                            message = "已退出有道登录"
                        }
                    ) { Text("退出登录", fontSize = 12.sp) }
                }
            }
            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showLogin) {
        YoudaoLoginDialog(
            onDismiss = { showLogin = false },
            onLoggedIn = { ck ->
                settings.youdaoCookie = ck
                cookie = ck
                showLogin = false
                message = "已登录有道，可以点「同步到有道」了"
            }
        )
    }
}

/**
 * WebView 登录弹层：加载有道词典首页，用户在页面里登录；
 * 登录成功（出现 `DICT_*` 登录 Cookie）后自动读回 Cookie 并回调。
 */
@Composable
private fun YoudaoLoginDialog(onDismiss: () -> Unit, onLoggedIn: (String) -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "登录有道（登录后自动返回）",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val ck = YoudaoWordbook.readCookie()
                        if (YoudaoWordbook.looksLoggedIn(ck)) onLoggedIn(ck) else onDismiss()
                    }) { Text("完成") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // 用桌面 UA：移动 UA 下有道网页只剩「下载 APP」推广页，没有登录入口
                            settings.userAgentString =
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    // 登录成功后页面会跳回有道域名，此时能读到登录 Cookie
                                    val ck = YoudaoWordbook.readCookie()
                                    if (YoudaoWordbook.looksLoggedIn(ck)) {
                                        post { onLoggedIn(ck) }
                                    }
                                }
                            }
                            loadUrl(YoudaoWordbook.LOGIN_URL)
                        }
                    }
                )
            }
        }
    }
}

private fun fmtYoudaoTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
