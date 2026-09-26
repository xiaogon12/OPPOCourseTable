package com.liyan.coursetable.phone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- 容器

/** 统一的面板容器：大圆角 + 细描边 + 柔和层次。 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Int = 16,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(p.surface)
            .border(1.dp, if (p.cardBorder.alpha == 0f) p.divider else p.cardBorder, RoundedCornerShape(24.dp))
            .padding(padding.dp),
    ) {
        content()
    }
}

@Composable
fun PanelTitle(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(
        text = text,
        color = p.textFaint,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = modifier.padding(bottom = 10.dp),
    )
}

@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(p.divider),
    )
}

// ---------------------------------------------------------------- 文本行

@Composable
fun KeyValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val p = LocalPalette.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.textDim, fontSize = 13.sp)
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            color = valueColor ?: p.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(if (warn) Color(0xFFE5A13D) else p.textFaint),
        )
        Spacer(Modifier.size(8.dp))
        Text(text, color = if (warn) Color(0xFFE5A13D) else p.textDim, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

// ---------------------------------------------------------------- 控件

/** 紧凑的加减器，避免拉出系统键盘挡住内容。 */
@Composable
fun Stepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = 999,
    step: Int = 1,
    display: (Int) -> String = { it.toString() },
) {
    val p = LocalPalette.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(p.chip)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", value - step >= min) { onValueChange((value - step).coerceIn(min, max)) }
        Text(
            text = display(value),
            color = p.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 52.dp).padding(horizontal = 4.dp),
        )
        StepButton("+", value + step <= max) { onValueChange((value + step).coerceIn(min, max)) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (p.light) Color(0x14000000) else Color(0x1AFFFFFF))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) p.chipText else p.textFaint,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 主按钮 / 次按钮。
 * `onClick` 刻意放在最后一个参数，这样可以写成 `ActionButton("文本") { ... }`。
 */
@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val bg = when {
        !enabled -> p.chip
        primary -> p.accent
        else -> p.chip
    }
    val fg = when {
        !enabled -> p.textFaint
        primary -> p.onAccent
        else -> p.chipText
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 可选中标签，用于主题色卡 / 快捷选项。 */
@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
) {
    val p = LocalPalette.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) p.accentSoft else p.chip)
            .border(
                1.dp,
                if (selected) p.accent else Color.Transparent,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (dotColor != null) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.size(7.dp))
        }
        Text(
            text,
            color = if (selected) p.text else p.chipText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 课程色点，颜色按课名散列，与手表端一致。 */
@Composable
fun ColorDot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

// ---------------------------------------------------------------- 系统交互
fun copyToClipboard(context: Context, text: String, label: String = "课程表 JSON") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    toast(context, "已复制到剪贴板")
}

fun readClipboard(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context).toString()
}

fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
