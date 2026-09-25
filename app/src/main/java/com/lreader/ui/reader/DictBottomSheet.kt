package com.lreader.ui.reader

import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lreader.model.DictEntry
import com.lreader.model.TranslationResult

/**
 * 查词结果底部弹层。
 * 含：发音、加入生词本、整句翻译。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictBottomSheet(
    word: String,
    sentence: String,
    entry: DictEntry?,
    done: Boolean,
    /** 主词典是否已下载：未下载时「查不到」要明说原因，而不是误导成「未收录」 */
    dictInstalled: Boolean = true,
    sourceBook: String,
    inVocab: Boolean,
    onSpeak: () -> Unit,
    onAddVocab: () -> Unit,
    onTranslateSentence: () -> Unit,
    translating: Boolean,
    translation: TranslationResult?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // 整个弹层是一个滚动面：释义可能很长（多词性词条动辄十几屏），
        // 下方的「原文句子 + 译文」必须能一路滑到底，故不给固定 maxHeight。
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            // 标题行
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(word, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        entry?.phonetic?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        entry?.levelName?.let {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    it,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                // 发音
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = "发音")
                }
                // 加入生词本
                IconButton(onClick = onAddVocab, enabled = !inVocab) {
                    Icon(
                        if (inVocab) Icons.Filled.Check else Icons.Filled.Add,
                        contentDescription = "加入生词本",
                        tint = if (inVocab) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 收藏状态 + 来源书目
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inVocab) {
                    Text(
                        "已在生词本",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                if (sourceBook.isNotBlank()) {
                    Text(
                        "来自《$sourceBook》",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 词形还原命中：用户查的词词典没直接收录，必须说清释义来自哪个词条
            entry?.formOf?.takeIf { it.isNotBlank() }?.let { root ->
                Text(
                    "未收录「$word」，以下为词根「$root」的释义",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
                )
            }

            Spacer(Modifier.height(6.dp))
            Divider()
            Spacer(Modifier.height(6.dp))

            // 释义区
            when {
                !done -> Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                entry == null || entry.html.isBlank() -> Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (dictInstalled) "未收录该单词" else "本地词典未下载",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!dictInstalled) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "请到「设置 → 本地词典」下载词典（约 115MB），" +
                                "下载完成后即可离线查词，且无次数限制。",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> DictHtmlView(
                    html = entry.html,
                    modifier = Modifier.fillMaxWidth(),
                    autoHeight = true
                )
            }

            // 整句翻译区
            if (sentence.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Divider()
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("原文句子", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        if (translation == null) {
                            TextButton(onClick = onTranslateSentence, enabled = !translating) {
                                Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (translating) "翻译中…" else "翻译", fontSize = 12.sp)
                            }
                        }
                    }
                    Text(sentence, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (translation != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("译文", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        if (translation.success) {
                            Text(
                                translation.translatedText,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                translation.errorMessage ?: "翻译失败",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 长按句子 → 翻译弹层（阅读页长按正文即弹出，并自动开始翻译）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceSheet(
    sentence: String,
    translating: Boolean,
    translation: TranslationResult?,
    onTranslate: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("句子翻译", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(10.dp))
            Text(sentence, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onTranslate,
                enabled = !translating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (translating) "翻译中…" else "翻译成中文")
            }

            if (translation != null) {
                Spacer(Modifier.height(14.dp))
                Divider()
                Spacer(Modifier.height(10.dp))
                if (translation.success) {
                    Text(
                        translation.translatedText,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "引擎：${translation.engine}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        translation.errorMessage ?: "翻译失败",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 词典 HTML 渲染视图。
 * 独立出来是为了让复习页「更多释义」也能复用同一套样式，避免两处各写一份。
 *
 * [autoHeight] = true 时，WebView 会按**内容实际高度**撑开，自身不再滚动，
 * 手势交给外层 Compose 滚动容器 —— 否则长短不一的词条会被固定高度裁掉，
 * 弹层下方的「原文句子 / 译文」滑不到底（旧版就是限定 340dp 导致的）。
 */
@Composable
fun DictHtmlView(
    html: String,
    modifier: Modifier = Modifier,
    autoHeight: Boolean = false
) {
    val density = LocalDensity.current
    var contentPx by remember(html) { mutableStateOf(0) }

    val finalModifier = if (autoHeight && contentPx > 0) {
        modifier.height(with(density) { contentPx.toDp() })
    } else {
        modifier
    }

    AndroidView(
        modifier = finalModifier,
        factory = { ctx ->
            object : WebView(ctx) {
                // 词典正文不需要交互：不消费触摸事件，滚动手势留给外层弹层
                override fun onTouchEvent(event: MotionEvent?): Boolean = false
            }.apply {
                /**
                 * 高度**只增不减**。WebView 自身不接收滚动（见上），一旦量小了，
                 * 被裁掉的一段既看不到也滑不到 —— conversion 这类多义项词条就是这样被截断的。
                 * 排版与字体渲染都是异步的，单次 contentHeight 常常偏小，故分几档补测取最大值。
                 */
                fun growTo(px: Int) {
                    if (px > 0) {
                        val withSlack = px + HEIGHT_SLACK_PX
                        if (withSlack > contentPx) contentPx = withSlack
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        MEASURE_DELAYS_MS.forEach { delayMs ->
                            view.postDelayed({
                                if (!view.isAttachedToWindow) return@postDelayed
                                growTo(view.contentHeight)
                                // 视口宽度/缩放会干扰 contentHeight，再用 DOM 真实高度兜一层
                                view.evaluateJavascript(JS_DOC_HEIGHT) { raw ->
                                    val css = raw?.trim('"', ' ', '\n')?.toFloatOrNull()
                                    // CSS px → 物理 px 的换算比就是屏幕密度（getScale 已废弃）
                                    val dens = view.resources.displayMetrics.density
                                    if (css != null && css > 0f && dens > 0f) {
                                        growTo((css * dens).toInt() + 2)
                                    }
                                }
                            }, delayMs)
                        }
                    }
                }
                settings.javaScriptEnabled = true   // 仅用于读取 DOM 高度，页面本身不含脚本
                settings.defaultTextEncodingName = "utf-8"
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                isVerticalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                isClickable = false
                isLongClickable = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                loadDataWithBaseURL(
                    null,
                    WRAP_HTML.replace("{{BODY}}", html),
                    "text/html", "utf-8", null
                )
            }
        }
    )
}

/** 补测时点（毫秒）：覆盖排版完成、字体加载、文本重排几个阶段 */
private val MEASURE_DELAYS_MS = longArrayOf(0L, 120L, 360L, 800L)

/** 额外留白，避免最后一行下缘被裁掉 */
private const val HEIGHT_SLACK_PX = 10

/** 文档真实高度（CSS 像素） */
private const val JS_DOC_HEIGHT =
    "(function(){var b=document.body,d=document.documentElement;" +
        "return Math.ceil(Math.max(b.scrollHeight,b.offsetHeight,d.clientHeight," +
        "d.scrollHeight,d.offsetHeight));})()"

private val WRAP_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
  body { margin: 6px 14px; font-family: -apple-system, "Noto Sans CJK SC", sans-serif;
         font-size: 15px; line-height: 1.6; color: #222; }
  h4 { margin: 4px 0; font-size: 18px; }
  .phonetic { color: #8b4513; font-weight: normal; }
  .pos { display:block; margin-top:6px; color: #8b008b; font-weight: bold;
         border-bottom: 1px dotted #aaa; }
  li { margin-bottom: 3px; }
  ul { padding-left: 22px; }
  b, strong { color: #1E6F5C; }
  hr.xref { border: none; border-top: 1px dashed #ccc; margin: 10px 0 6px; }
  .xref { color: #666; font-size: 13px; margin-bottom: 4px; }
</style>
</head>
<body>
{{BODY}}
</body>
</html>
""".trimIndent()
