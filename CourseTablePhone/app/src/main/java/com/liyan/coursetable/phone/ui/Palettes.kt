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

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * 配色方案。颜色值与手表端 `Palette.java` **逐位相同**，
 * 所以同一门课在两台设备上色条颜色一致。
 */
class Palette(
    val id: String,
    val name: String,
    val light: Boolean,
    val bg: Color,
    val surface: Color,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val chip: Color,
    val chipText: Color,
    val divider: Color,
) {
    /**
     * 每门课左侧色条：按课程名散列出色相。
     * 用的是 `android.graphics.Color.HSVToColor`，与手表端同一个函数，结果必然一致。
     *
     * 结果按课名缓存：一门课的色条在一次列表滚动里会被请求几十次，
     * 每次都新建 float[] 再算一遍 HSV 是白费（滚动时这就是纯粹的 GC 压力）。
     */
    private val barCache = HashMap<String, Color>()

    fun barColor(key: String): Color = barCache.getOrPut(key) {
        val hue = abs(key.hashCode() % 360).toFloat()
        val sat = if (light) 0.42f else 0.50f
        val v = if (light) 0.78f else 1.00f
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, v)))
    }
}

object Palettes {

    val INK = Palette(
        id = "ink", name = "极夜黑", light = false,
        bg = Color(0xFF0A0A0C), surface = Color(0xFF141418), card = Color(0xFF1C1C22),
        cardBorder = Color(0x00000000),
        text = Color(0xFFF3F3F6), textDim = Color(0xFF9A9AA4), textFaint = Color(0xFF6A6A74),
        accent = Color(0xFF5B8DEF), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFF1A2740),
        chip = Color(0xFF26262E), chipText = Color(0xFFBFC0CC), divider = Color(0xFF26262E),
    )

    val KLEIN = Palette(
        id = "klein", name = "克莱因蓝", light = false,
        bg = Color(0xFF060D1C), surface = Color(0xFF0D1830), card = Color(0xFF13203A),
        cardBorder = Color(0x00000000),
        text = Color(0xFFE9F1FF), textDim = Color(0xFF8AA0C6), textFaint = Color(0xFF5E7396),
        accent = Color(0xFF4C86FF), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFF152A4E),
        chip = Color(0xFF1B2B4A), chipText = Color(0xFFA8BFE4), divider = Color(0xFF1B2B4A),
    )

    val CLAUDE = Palette(
        id = "claude", name = "Claude 橙白", light = true,
        bg = Color(0xFFF0EEE6), surface = Color(0xFFFAF9F5), card = Color(0xFFFFFFFF),
        cardBorder = Color(0xFFE7E3D9),
        text = Color(0xFF1F1E1B), textDim = Color(0xFF78756C), textFaint = Color(0xFFA39F94),
        accent = Color(0xFFD97757), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFFF8ECE6),
        chip = Color(0xFFEDE9DF), chipText = Color(0xFF5F5C53), divider = Color(0xFFE7E3D9),
    )

    val FOREST = Palette(
        id = "forest", name = "森野绿", light = true,
        bg = Color(0xFFEDF3EC), surface = Color(0xFFF8FBF7), card = Color(0xFFFFFFFF),
        cardBorder = Color(0xFFDDE8DC),
        text = Color(0xFF17251A), textDim = Color(0xFF6B7A6E), textFaint = Color(0xFF9AAA9E),
        accent = Color(0xFF3F8F5B), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFFE6F1E9),
        chip = Color(0xFFE4EBE3), chipText = Color(0xFF4F5C52), divider = Color(0xFFDDE8DC),
    )

    val MORANDI = Palette(
        id = "morandi", name = "莫兰迪紫", light = true,
        bg = Color(0xFFF2EFF5), surface = Color(0xFFFAF8FC), card = Color(0xFFFFFFFF),
        cardBorder = Color(0xFFE6E0ED),
        text = Color(0xFF241F2C), textDim = Color(0xFF77717F), textFaint = Color(0xFFA59EAE),
        accent = Color(0xFF8B7BA8), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFFEFE9F5),
        chip = Color(0xFFEAE4F0), chipText = Color(0xFF584F66), divider = Color(0xFFE6E0ED),
    )

    val ALL = listOf(INK, KLEIN, CLAUDE, FOREST, MORANDI)

    fun byId(id: String?): Palette = ALL.firstOrNull { it.id == id } ?: INK
}

val LocalPalette = staticCompositionLocalOf { Palettes.INK }
