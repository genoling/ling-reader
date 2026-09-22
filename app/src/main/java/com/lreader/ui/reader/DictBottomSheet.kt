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
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // 等排版完成再量高度：contentHeight 是屏幕像素
                        view.post {
                            val h = view.contentHeight
                            if (h > 0 && h != contentPx) contentPx = h
                        }
                    }
                }
                settings.javaScriptEnabled = false
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
