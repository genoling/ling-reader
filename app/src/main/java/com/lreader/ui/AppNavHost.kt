package com.lreader.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lreader.ui.magazine.MagazineScreen
import com.lreader.ui.reader.ReaderScreen
import com.lreader.ui.settings.SettingsScreen
import com.lreader.ui.shelf.ShelfScreen
import com.lreader.ui.study.StudyScreen

object Routes {
    const val SHELF = "shelf"
    const val STUDY = "study"          // 背单词（生词本 + 复习）
    const val MAGAZINE = "magazine"    // 外刊杂志（占位）
    const val SETTINGS = "settings"
    const val REVIEW = "review"
    const val READER = "reader"

    fun reader(bookId: String) = "reader?bookId=${encode(bookId)}"
}

/** 底部导航的四个 Tab */
private enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    SHELF(Routes.SHELF, "书架", Icons.Filled.LibraryBooks),
    STUDY(Routes.STUDY, "背单词", Icons.Filled.Style),
    MAGAZINE(Routes.MAGAZINE, "外刊杂志", Icons.Filled.Newspaper),
    SETTINGS(Routes.SETTINGS, "设置", Icons.Filled.Tune)
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val showBar = BottomTab.values().any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    BottomTab.values().forEach { tab ->
                        val selected = tab.route == currentRoute
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, fontSize = 10.sp) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.SHELF,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.SHELF) {
                ShelfScreen(onOpenBook = { bookId -> nav.navigate(Routes.reader(bookId)) })
            }

            composable(Routes.STUDY) {
                StudyScreen()
            }

            composable(Routes.MAGAZINE) {
                MagazineScreen()
            }

            composable(Routes.SETTINGS) {
                SettingsScreen()
            }

            composable(
                route = "reader?bookId={bookId}",
                arguments = listOf(navArgument("bookId") {
                    type = NavType.StringType
                    defaultValue = ""
                })
            ) { backEntry ->
                ReaderScreen(
                    bookId = decode(backEntry.arguments?.getString("bookId").orEmpty()),
                    onBack = { nav.popBackStack() }
                )
            }

        }
    }
}

/** Tab 之间切换：保留各 Tab 的滚动位置，且不堆栈 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(Routes.SHELF) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
private fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
