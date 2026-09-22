package com.lreader.ui.study

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lreader.ui.vocab.ReviewScreen
import com.lreader.ui.vocab.VocabScreen

/**
 * 「背单词」Tab：内部二级切换「生词本 / 复习」。
 * 两个子页都以 embedded 模式渲染（各自不带顶栏），避免出现双标题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen() {
    var subTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("背单词", fontWeight = FontWeight.Bold) })
                TabRow(selectedTabIndex = subTab) {
                    Tab(
                        selected = subTab == 0,
                        onClick = { subTab = 0 },
                        text = { Text("生词本", fontSize = 14.sp) }
                    )
                    Tab(
                        selected = subTab == 1,
                        onClick = { subTab = 1 },
                        text = { Text("复习", fontSize = 14.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (subTab == 0) {
                VocabScreen(onStartReview = { subTab = 1 }, embedded = true)
            } else {
                ReviewScreen(onBack = { subTab = 0 }, embedded = true)
            }
        }
    }
}
