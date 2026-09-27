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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liyan.coursetable.phone.BuildConfig
import com.liyan.coursetable.phone.model.SECTION_NAMES
import com.liyan.coursetable.phone.model.Settings
import com.liyan.coursetable.phone.model.TimeTable
import com.liyan.coursetable.phone.model.formatDate
import com.liyan.coursetable.phone.model.hhmm
import com.liyan.coursetable.phone.model.parseDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@Composable
fun SettingsScreen(state: AppState) {
    val p = LocalPalette.current
    val s = state.settings
    val context = LocalContext.current

    var dateDialog by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableStateOf(-1) }
    var confirmClear by remember { mutableStateOf(false) }
    val tt = remember(s) { TimeTable(s) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        // ---------------------------------------------------- 课表
        Panel {
            PanelTitle("课表")
            OutlinedTextField(
                value = s.tableName,
                onValueChange = { state.updateSettings(s.copy(tableName = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("给这张课表起个名字", fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- 学期
        Panel {
            PanelTitle("学期")
            RowSetting("开学第一周周一", hint = "课表从这一周的周一开始算") {
                SelectChip(
                    text = s.startDate.ifBlank { "未设置" },
                    selected = s.startDate.isNotBlank(),
                    onClick = { dateDialog = true },
                )
            }
            ThinDivider()
            RowSetting("总周数") {
                Stepper(s.totalWeeks, { state.updateSettings(s.copy(totalWeeks = it)) }, min = 1, max = 40) {
                    "$it 周"
                }
            }
            ThinDivider()
            RowSetting("周次修正", hint = "整体把「今天在第几周」往前后挪") {
                Stepper(s.weekOffset, { state.updateSettings(s.copy(weekOffset = it)) }, min = -20, max = 20) {
                    if (it > 0) "+$it" else "$it"
                }
            }
            if (s.startDate.isNotBlank() && !s.startDateIsMonday) {
                Spacer(Modifier.height(6.dp))
                Hint("这个日期不是周一，周次会整体偏移一天，建议改成周一。", warn = true)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- 作息时间
        Panel {
            PanelTitle("作息时间")
            for (i in 0..2) {
                RowSetting("${SECTION_NAMES[i]}节数") {
                    Stepper(s.periodCountAt(i), { v ->
                        val list = s.periodCount.toMutableList()
                        while (list.size < 3) list += 0
                        list[i] = v
                        state.updateSettings(s.copy(periodCount = list).ensurePerPeriod())
                    }, min = 0, max = 10) { "$it 节" }
                }
                RowSetting("${SECTION_NAMES[i]}第一节") {
                    SelectChip(
                        text = hhmm(s.sectionStartAt(i)),
                        selected = true,
                        onClick = { timeDialog = i },
                    )
                }
                if (i < 2) ThinDivider()
            }

            Spacer(Modifier.height(10.dp))
            ThinDivider()
            Spacer(Modifier.height(6.dp))

            RowSetting("每节课时长") {
                Stepper(s.periodMinutes, { state.updateSettings(s.copy(periodMinutes = it)) }, min = 20, max = 120, step = 5) {
                    "$it 分"
                }
            }
            ThinDivider()
            RowSetting("课间休息") {
                Stepper(s.breakMinutes, { state.updateSettings(s.copy(breakMinutes = it)) }, min = 0, max = 45, step = 5) {
                    "$it 分"
                }
            }
            ThinDivider()
            RowSetting("大课间", hint = "每上完 2 节放一次") {
                Stepper(s.bigBreakMinutes, { state.updateSettings(s.copy(bigBreakMinutes = it)) }, min = 0, max = 60, step = 5) {
                    "$it 分"
                }
            }

            if (tt.count > 0) {
                Spacer(Modifier.height(12.dp))
                ThinDivider()
                Spacer(Modifier.height(10.dp))
                Text("推算出的节次时间", color = p.textFaint, fontSize = 11.5.sp)
                Spacer(Modifier.height(6.dp))
                for (row in (1..tt.count).toList().chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (period in row) {
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "$period",
                                    color = p.accent,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(16.dp),
                                )
                                Text(
                                    "${hhmm(tt.start(period))}-${hhmm(tt.end(period))}",
                                    color = p.textDim,
                                    fontSize = 11.5.sp,
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(5.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- 提醒
        Panel {
            PanelTitle("课前提醒")
            RowSetting("开启课前提醒", hint = "手表会写进系统日历") {
                Switch(
                    checked = s.reminderOn,
                    onCheckedChange = { state.updateSettings(s.copy(reminderOn = it)) },
                )
            }
            if (s.reminderOn) {
                ThinDivider()
                RowSetting("提前多久") {
                    Stepper(s.reminderMinutes, { state.updateSettings(s.copy(reminderMinutes = it)) }, min = 1, max = 120, step = 5) {
                        "$it 分钟"
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- 外观
        Panel {
            PanelTitle("外观")
            Text(
                "配色与手表端完全一致，同一门课在两台设备上色条颜色相同。",
                color = p.textDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            for (row in Palettes.ALL.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (pal in row) {
                        SelectChip(
                            text = pal.name,
                            selected = pal.id == s.themeId,
                            onClick = { state.updateSettings(s.copy(themeId = pal.id)) },
                            modifier = Modifier.weight(1f),
                            dotColor = pal.accent,
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- 数据
        Panel {
            PanelTitle("数据")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ActionButton("复制当前 JSON", modifier = Modifier.weight(1f)) {
                    copyToClipboard(context, state.currentJson())
                }
                ActionButton("导出到文件", modifier = Modifier.weight(1f)) {
                    val f = runCatching { state.store.exportToExternal(state.table) }.getOrNull()
                    toast(context, if (f != null) "已导出到 ${f.parent}" else "导出失败")
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ActionButton("恢复内置示例") {
                    val sample = runCatching { state.store.readAsset("sample_course.json") }.getOrDefault("")
                    runCatching {
                        com.liyan.coursetable.phone.model.CourseJson.parse(sample, state.settings)
                    }.getOrNull()?.let {
                        state.applyImport(it)
                        toast(context, "已恢复内置示例")
                    }
                }
                ActionButton("清空课表") { confirmClear = true }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "当前：${state.courses.size} 门课 · ${s.totalPeriods} 节/天 · ${s.totalWeeks} 周",
                color = p.textFaint,
                fontSize = 11.sp,
            )
        }

        // ---------------------------------------------------- 关于
        Spacer(Modifier.height(12.dp))
        AboutPanel()
    }

    if (dateDialog) {
        DateDialog(
            initial = s.startDate,
            onDismiss = { dateDialog = false },
            onConfirm = {
                state.updateSettings(s.copy(startDate = it))
                dateDialog = false
            },
        )
    }

    if (timeDialog in 0..2) {
        TimeDialog(
            title = "${SECTION_NAMES[timeDialog]}第一节开始时间",
            initial = s.sectionStartAt(timeDialog),
            onDismiss = { timeDialog = -1 },
            onConfirm = { minutes ->
                val list = s.sectionStart.toMutableList()
                while (list.size < 3) list += Settings().sectionStartAt(list.size)
                list[timeDialog] = minutes
                state.updateSettings(s.copy(sectionStart = list))
                timeDialog = -1
            },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清空课表？",
            message = "会删掉手机端保存的课程和课表名。手表上的课表不受影响，需要单独重推。",
            confirmText = "清空",
            onDismiss = { confirmClear = false },
            onConfirm = {
                state.clearTable()
                confirmClear = false
                toast(context, "已清空")
            },
        )
    }
}

// ---------------------------------------------------------------- 小组件

/**
 * 「关于 / 声明」面板。
 *
 * 开源软件该把四件事说清楚：这是什么、谁写的、代码在哪、数据去哪了。
 * 最后一条对这类要蓝牙、要读课表的 App 尤其重要 —— 所以直接把「不联网」写出来，
 * 而不是留给用户去猜。
 *
 * 这里的作者署名和底部的出处水印**是有意留在界面上的**：
 * 许可要求保留署名，谁要把它换成自己的名字，就得动代码而不是换个图标。
 */
@Composable
private fun AboutPanel() {
    val p = LocalPalette.current
    val context = LocalContext.current

    Panel {
        PanelTitle("关于")
        KeyValue("应用", "课程表 v${BuildConfig.VERSION_NAME}")
        KeyValue("作者", AppState.AUTHOR)
        KeyValue("许可证", AppState.LICENSE_NAME, valueColor = p.accent)
        Spacer(Modifier.height(4.dp))
        Hint(
            "开源软件（${AppState.LICENSE_NAME}）。免费用、随便改、可以再发布，" +
                "但不能拿去卖钱，也不能删掉作者署名当成自己的作品。",
        )
        Hint(
            "不联网、不收集任何信息、不上传任何数据：" +
                "课表只在你自己的手机和手表之间通过蓝牙传输。",
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton("复制开源地址", modifier = Modifier.weight(1f)) {
                copyToClipboard(context, AppState.REPO_URL, "开源仓库地址")
            }
            ActionButton("打开仓库", primary = true, modifier = Modifier.weight(1f)) {
                openOrCopy(context, AppState.REPO_URL, "开源仓库地址")
            }
        }
        Spacer(Modifier.height(8.dp))
        ActionButton("查看许可条款", modifier = Modifier.fillMaxWidth()) {
            openOrCopy(context, AppState.LICENSE_URL, "许可条款地址")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            AppState.REPO_URL,
            color = p.textFaint,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        // 出处水印：界面上留着，抹掉它需要改代码
        Text(
            AppState.WATERMARK,
            color = p.textFaint,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 打开链接；没浏览器就把地址塞进剪贴板，别让这一下点了没反应。 */
private fun openOrCopy(context: android.content.Context, url: String, label: String) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) copyToClipboard(context, url, label)
}

@Composable
private fun RowSetting(
    label: String,
    hint: String? = null,
    control: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.text, fontSize = 13.5.sp)
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(hint, color = p.textFaint, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        control()
    }
}

@Composable
private fun TimeDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val p = LocalPalette.current
    var minutes by remember { mutableStateOf(initial.coerceIn(0, 23 * 60 + 55)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text(title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                RowSetting("小时") {
                    Stepper(minutes / 60, { minutes = it * 60 + minutes % 60 }, min = 0, max = 23) { "$it 时" }
                }
                RowSetting("分钟") {
                    Stepper(minutes % 60, { minutes = (minutes / 60) * 60 + it }, min = 0, max = 55, step = 5) {
                        "%02d 分".format(it)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "= ${hhmm(minutes)}",
                    color = p.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = { ActionButton("确定", primary = true) { onConfirm(minutes) } },
        dismissButton = { ActionButton("取消") { onDismiss() } },
    )
}

@Composable
private fun DateDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(initial) }
    val parsed = parseDate(text)
    val today = LocalDate.now()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text("开学第一周的周一", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("yyyy-MM-dd", fontSize = 13.sp) },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        text.isBlank() -> "留空表示暂不设置"
                        parsed == null -> "日期格式不对"
                        parsed.dayOfWeek == DayOfWeek.MONDAY -> "✓ ${formatDate(parsed)} 是周一"
                        else -> "⚠ 这天是周${parsed.dayOfWeek.value}，不是周一"
                    },
                    color = when {
                        text.isBlank() -> p.textFaint
                        parsed == null -> androidx.compose.material3.MaterialTheme.colorScheme.error
                        parsed.dayOfWeek == DayOfWeek.MONDAY -> p.accent
                        else -> androidx.compose.material3.MaterialTheme.colorScheme.error
                    },
                    fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("今天那周", false, {
                        text = formatDate(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                    })
                    SelectChip("上周一", false, {
                        text = formatDate(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1))
                    })
                    SelectChip("下周一", false, {
                        text = formatDate(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))
                    })
                }
            }
        },
        confirmButton = { ActionButton("确定", primary = true) { onConfirm(text.trim()) } },
        dismissButton = { ActionButton("取消") { onDismiss() } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text(title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = p.textDim, fontSize = 13.sp, lineHeight = 19.sp) },
        confirmButton = {
            ActionButton(confirmText, primary = true) { onConfirm() }
        },
        dismissButton = { ActionButton("取消") { onDismiss() } },
    )
}
