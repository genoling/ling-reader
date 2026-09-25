package com.lreader.ui.admin

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lreader.sync.GithubBackend
import com.lreader.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 管理员面板（设置页输入密码 8848 后进入）。
 *
 * 两件事：
 *  1. **本机数据**：列出各数据库 / 目录的大小，可逐个删除（不可恢复，删除前二次确认）
 *  2. **云端用户**：列出同步仓库里 `users/` 下的所有用户目录，可整体删除某位用户的数据
 *
 * 注意：云端看到的是**密文**，只能看到目录名与大小，看不到任何内容 —— 加密口令只在用户设备上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var localItems by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    var cloudUsers by remember { mutableStateOf<List<GithubBackend.Entry>?>(null) }
    var cloudNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun refreshLocal() {
        localItems = LocalData.list(context)
    }

    fun loadCloud() {
        val cfg = SyncManager.config(context)
        if (cfg == null) {
            cloudNote = "尚未配置同步码，无法读取云端用户列表"
            cloudUsers = emptyList()
            return
        }
        busy = true
        cloudNote = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    GithubBackend(cfg.owner, cfg.repo, cfg.branch, cfg.token).list("users")
                }
            }
            busy = false
            r.fold(
                onSuccess = { cloudUsers = it.filter { e -> e.isDir } },
                onFailure = {
                    cloudUsers = emptyList()
                    cloudNote = "读取失败：${it.message}"
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        refreshLocal()
        loadCloud()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理员", fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "⚠️ 这里的操作不可恢复。删除数据库会清空对应数据（词典删掉后可在「本地词典」区重新下载）。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )

            // ---------------- 本机数据 ----------------
            Text("本机数据", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Card {
                Column(Modifier.padding(12.dp)) {
                    localItems.forEachIndexed { i, item ->
                        if (i > 0) Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.label, fontSize = 13.sp)
                                Text(
                                    item.detail + " · " + fmtSize(item.bytes),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.deletable) {
                                TextButton(
                                    onClick = {
                                        message = null
                                        pending = {
                                            val ok = LocalData.delete(item)
                                            refreshLocal()
                                            message = if (ok) {
                                                "已删除：${item.label}（部分改动需重启 App 生效）"
                                            } else {
                                                "删除失败：${item.label}"
                                            }
                                        }
                                    }
                                ) { Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                            } else {
                                Text("只读", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // ---------------- 云端用户 ----------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("云端用户", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { loadCloud() }) { Text("刷新", fontSize = 12.sp) }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            cloudNote?.let { Hint(it) }
            Card {
                Column(Modifier.padding(12.dp)) {
                    val users = cloudUsers
                    if (users.isNullOrEmpty()) {
                        Text(
                            "没有读到用户目录（可能还没有人同步过）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        users.forEachIndexed { i, e ->
                            if (i > 0) Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, fontSize = 13.sp)
                                    Text(
                                        "users/${e.name}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        val cfg = SyncManager.config(context)
                                        if (cfg == null) {
                                            message = "未配置同步码"
                                        } else {
                                            pending = {
                                                scope.launch {
                                                    busy = true
                                                    val r = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            GithubBackend(
                                                                cfg.owner, cfg.repo, cfg.branch, cfg.token
                                                            ).deleteRecursively("users/${e.name}")
                                                        }
                                                    }
                                                    busy = false
                                                    message = r.fold(
                                                        onSuccess = { "已删除该用户的云端数据（$it 个文件）" },
                                                        onFailure = { "删除失败：${it.message}" }
                                                    )
                                                    loadCloud()
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Text("删除用户", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            message?.let { Hint(it) }
            Spacer(Modifier.height(12.dp))
        }
    }

    // 二次确认：所有删除都走这里
    val action = pending
    if (action != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("确认删除？", fontSize = 16.sp) },
            text = { Text("该操作不可恢复。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        action()
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 管理员可以看到的本机数据项 */
private data class LocalItem(
    val label: String,
    /** 展示用的路径或说明 */
    val detail: String,
    val bytes: Long,
    val deletable: Boolean,
    val path: File?
)

private object LocalData {

    /** 本机数据库与目录清单（词典仅展示大小，删除入口在「本地词典」区） */
    fun list(ctx: Context): List<LocalItem> {
        val files = ctx.filesDir
        return listOf(
            item("生词本数据库", ctx.getDatabasePath("vocab.db"), deletable = true),
            item("书架数据", File(files, "bookshelf.json"), deletable = true),
            item("应用设置", File(files, "settings.json"), deletable = true),
            item("书籍文件", File(files, "books"), deletable = true),
            item("封面缓存", File(files, "covers"), deletable = true),
            item("章节插图缓存", File(files, "books/.images"), deletable = true),
            item("主词典（21世纪大英汉）", File(files, "dict_en_zh.db"), deletable = false),
            item("补充词典（ECDICT）", File(files, "ecdict.db"), deletable = false),
            item("分级词库", File(files, "levels.db"), deletable = false)
        )
    }

    private fun item(label: String, path: File, deletable: Boolean): LocalItem = LocalItem(
        label = label,
        // 去掉应用包名前缀，显示成 files/… 这样既完整又不啰嗦
        detail = path.absolutePath.substringAfter("/com.lreader", path.absolutePath),
        bytes = sizeOf(path),
        deletable = deletable,
        path = path
    )

    private fun sizeOf(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 删除（目录递归；SQLite 连同 -journal / -wal 一起删） */
    fun delete(item: LocalItem): Boolean {
        val f = item.path ?: return false
        if (!f.exists()) return true
        return try {
            val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
            // SQLite 的临时文件
            listOf("-journal", "-wal", "-shm").forEach { suffix ->
                val extra = File(f.absolutePath + suffix)
                if (extra.exists()) extra.delete()
            }
            ok || !f.exists()
        } catch (e: Exception) {
            false
        }
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
