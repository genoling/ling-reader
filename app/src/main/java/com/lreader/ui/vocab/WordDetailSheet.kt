package com.lreader.ui.vocab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lreader.model.DictEntry
import com.lreader.ui.reader.DictHtmlView

/**
 * 生词详情弹层：单词 + 音标 + 级别标签 + 完整词典释义（可滚动到任意长词条）+ 原文句子。
 *
 * 生词本与复习页共用同一套呈现，避免两处各写一份、样式走形。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailSheet(
    word: String,
    phonetic: String,
    level: String,
    sentence: String,
    /** 原句译文（null / 空 = 还没翻译或翻译失败） */
    translation: String?,
    translating: Boolean,
    entry: DictEntry?,
    loading: Boolean,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(word, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (phonetic.isNotBlank()) {
                            Text(
                                phonetic,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (level.isNotBlank()) LevelBadge(level)
                    }
                }
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = "发音")
                }
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            when {
                loading -> Box(
                    Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                entry == null || entry.html.isBlank() -> Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (entry == null) "本地词典未下载" else "未收录该单词",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }

                else -> DictHtmlView(
                    html = entry.html,
                    modifier = Modifier.fillMaxWidth(),
                    autoHeight = true
                )
            }

            if (sentence.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text("原文句子", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    sentence,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text("译文", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        translating -> "翻译中…"
                        !translation.isNullOrBlank() -> translation
                        else -> "（暂时取不到译文，请检查网络后重进）"
                    },
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 考试级别小标签（CET4 / IELTS / TOEFL…） */
@Composable
private fun LevelBadge(level: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Text(
            level,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.primary
        )
    }
}
