package com.lreader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 界面配色方案（集中管理）。
 *
 * 想调色只需改这里：新增一个枚举值即可在设置里出现新选项，
 * 页面代码一律通过 MaterialTheme.colorScheme 取色，不需要改动。
 */
enum class ThemePreset(
    val label: String,
    val accent: Color,
    val accentContainer: Color,
    val background: Color,
    val surface: Color
) {
    PINE("松绿", Color(0xFF1E6F5C), Color(0xFFDCEBE5), Color(0xFFF2F2F7), Color(0xFFFFFFFF)),
    INDIGO("靛蓝", Color(0xFF2F6FED), Color(0xFFE1EAFD), Color(0xFFF3F4F8), Color(0xFFFFFFFF)),
    AMBER("暖橙", Color(0xFFC8732B), Color(0xFFF7E7D8), Color(0xFFF7F4EF), Color(0xFFFFFFFF)),
    GRAPHITE("石墨", Color(0xFF4A4A4F), Color(0xFFE6E6EA), Color(0xFFF2F2F4), Color(0xFFFFFFFF));

    companion object {
        fun of(index: Int): ThemePreset = values().getOrElse(index) { PINE }
    }
}

/** 让设置页可以即时切换全局配色 */
object AppThemeState {
    var preset: ThemePreset by mutableStateOf(ThemePreset.PINE)

    fun apply(index: Int) {
        preset = ThemePreset.of(index)
    }
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val p = AppThemeState.preset
    val scheme = lightColorScheme(
        primary = p.accent,
        onPrimary = Color.White,
        primaryContainer = p.accentContainer,
        onPrimaryContainer = p.accent,
        secondary = Color(0xFF6C6C70),
        onSecondary = Color.White,
        // 不定义这两项会回落到 M3 默认紫（底部导航选中态、FilledTonalButton 都会变紫）
        secondaryContainer = p.accentContainer,
        onSecondaryContainer = p.accent,
        tertiary = p.accent,
        onTertiary = Color.White,
        tertiaryContainer = p.accentContainer,
        onTertiaryContainer = p.accent,
        background = p.background,
        onBackground = Color(0xFF1C1C1E),
        surface = p.surface,
        onSurface = Color(0xFF1C1C1E),
        surfaceVariant = Color(0xFFE9E9EE),
        onSurfaceVariant = Color(0xFF6C6C70),
        outline = Color(0xFFD1D1D6),
        outlineVariant = Color(0xFFE5E5EA),
        error = Color(0xFFFF3B30),
        onError = Color.White
    )
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}
