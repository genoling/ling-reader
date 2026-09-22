package com.lreader.ui.reader

import androidx.compose.ui.graphics.Color

/**
 * 阅读页配色方案（独立模块）。
 *
 * 背景取自参考阅读页的纸纹底色（实测约 #E7E6D8），统一放在这里，
 * 阅读页 / 详情弹层 / 页码都从这里取色，避免各处写死颜色。
 */
enum class ReadingTheme(
    val label: String,
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val isDark: Boolean
) {
    /** 纸白 */
    PAPER("纸白", Color(0xFFFAF9F4), Color(0xFF2A2A26), Color(0xFF8A8A80), false),

    /** 米黄（默认，对齐参考图） */
    CREAM("米黄", Color(0xFFEDEBDC), Color(0xFF2E2E28), Color(0xFF8C8A7C), false),

    /** 夜间 */
    NIGHT("夜间", Color(0xFF1E1E1C), Color(0xFFC9C7BE), Color(0xFF7C7A72), true);

    /** 分级词汇高亮色在浅底上可读，夜间统一提亮 */
    fun levelColor(argb: Int): Color =
        if (!isDark) Color(argb) else lighten(Color(argb))

    private fun lighten(c: Color): Color = Color(
        red = (c.red + (1f - c.red) * 0.35f),
        green = (c.green + (1f - c.green) * 0.35f),
        blue = (c.blue + (1f - c.blue) * 0.35f),
        alpha = 1f
    )

    companion object {
        fun of(index: Int): ReadingTheme = values().getOrElse(index) { CREAM }
    }
}
