/*
 * 课程表 · OPPO Watch X2
 * Copyright (c) 2026 xiaogon12
 * https://github.com/xiaogon12/OPPOCourseTable
 *
 * 许可：CC BY-NC-SA 4.0（署名—非商业性使用—相同方式共享）
 *   · 可以免费用、随意改、原样或改版再发布
 *   · 不可以商用、盈利，不可以移除本署名后重新发布
 *   · 改版发布必须沿用同一许可
 * 完整条款见仓库根目录 LICENSE。
 */
package com.liyan.coursetable.phone.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 大圆角是这套 UI 的主调：卡片 24dp、弹层 30dp、小控件 12~14dp。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val Danger = Color(0xFFE5484D)
private val DangerSoftDark = Color(0xFF3A1A1C)
private val DangerSoftLight = Color(0xFFFBE9E9)

/** 描边色为 0 的主题（极夜黑 / 克莱因蓝）用分隔色兜底，避免完全看不见边界。 */
private fun Palette.border(): Color = if (cardBorder.alpha == 0f) divider else cardBorder

@Composable
fun CourseTableTheme(palette: Palette, content: @Composable () -> Unit) {
    val scheme = if (palette.light) {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.text,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.card,
            onSurfaceVariant = palette.textDim,
            surfaceTint = palette.accent,
            outline = palette.divider,
            outlineVariant = palette.border(),
            error = Danger,
            onError = Color.White,
            errorContainer = DangerSoftLight,
            onErrorContainer = Danger,
            scrim = Color(0x99000000),
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.text,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.card,
            onSurfaceVariant = palette.textDim,
            surfaceTint = palette.accent,
            outline = palette.divider,
            outlineVariant = palette.border(),
            error = Danger,
            onError = Color.White,
            errorContainer = DangerSoftDark,
            onErrorContainer = Color(0xFFFF9EA1),
            scrim = Color(0x99000000),
        )
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
    }
}
