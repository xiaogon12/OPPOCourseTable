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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liyan.coursetable.phone.model.Course
import com.liyan.coursetable.phone.model.WEEKDAY_SHORT
import com.liyan.coursetable.phone.model.formatWeeks
import com.liyan.coursetable.phone.model.hhmm

/**
 * 单门课的编辑弹层。课表里的每个字段都能在这里直接改，
 * 这是「手机端直接修改各个数据」的核心入口 —— 手表上做不了这个。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditSheet(
    initial: Course?,
    index: Int,
    state: AppState,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val p = LocalPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val totalWeeks = state.settings.totalWeeks.coerceAtLeast(1)
    val totalPeriods = state.settings.totalPeriods.coerceAtLeast(1)
    val isNew = initial == null

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var teacher by remember { mutableStateOf(initial?.teacher.orEmpty()) }
    var room by remember { mutableStateOf(initial?.room.orEmpty()) }
    var className by remember { mutableStateOf(initial?.className.orEmpty()) }
    var day by remember { mutableStateOf(initial?.day ?: 1) }
    var from by remember { mutableStateOf(initial?.from ?: 1) }
    var to by remember { mutableStateOf(initial?.to ?: initial?.from ?: 2) }
    var limited by remember { mutableStateOf(!initial?.weeks.isNullOrEmpty()) }
    var weeks by remember { mutableStateOf(initial?.weeks.orEmpty().toSet()) }

    val nameOk = name.isNotBlank()
    val weeksOk = !limited || weeks.isNotEmpty()
    val rangeOk = from <= to
    val canSave = nameOk && weeksOk && rangeOk

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = p.surface,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(p.barColor(name.ifBlank { "?" })),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isNew) "新增课程" else "编辑课程",
                        color = p.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!isNew) {
                        Text(
                            "周${WEEKDAY_SHORT.getOrElse(day - 1) { "?" }} · " +
                                "第 $from-$to 节 · ${formatWeeks(weeks.toList())}",
                            color = p.textDim,
                            fontSize = 11.5.sp,
                        )
                    }
                }
                if (!isNew) {
                    ActionButton("删除") {
                        state.deleteCourse(index)
                        onSaved("已删除")
                        onDismiss()
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(16.dp))

                Field("课程名 *", name, { v -> name = v.take(24) }, "例如 高等数学")
                Field("任课教师", teacher, { v -> teacher = v.take(16) }, "可留空")
                Field("上课地点", room, { v -> room = v.take(20) }, "例如 教学楼101")
                Field("教学班", className, { v -> className = v.take(20) }, "可留空")

                Spacer(Modifier.height(6.dp))
                Text("星期", color = p.textFaint, fontSize = 11.5.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                    for (d in 1..7) {
                        val on = d == day
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) p.accent else p.chip)
                                .clickable { day = d },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                WEEKDAY_SHORT[d - 1],
                                color = if (on) p.onAccent else p.chipText,
                                fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("节次", color = p.textFaint, fontSize = 11.5.sp)
                        Text(
                            if (rangeOk) {
                                "第 $from - $to 节 · ${state.timeTable.range(from, to)}"
                            } else {
                                "结束节次不能小于起始节次"
                            },
                            color = if (rangeOk) p.text else androidx.compose.material3.MaterialTheme.colorScheme.error,
                            fontSize = 12.5.sp,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Stepper(from, { v -> from = v; if (to < v) to = v }, min = 1, max = totalPeriods) { "起 $it" }
                        Spacer(Modifier.height(6.dp))
                        Stepper(to, { v -> to = v; if (from > v) from = v }, min = 1, max = totalPeriods) { "止 $it" }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("限定周次", color = p.text, fontSize = 13.5.sp)
                        Text(
                            if (limited) {
                                if (weeks.isEmpty()) "至少要选一周" else formatWeeks(weeks.toList())
                            } else {
                                "每周都上（共 $totalWeeks 周）"
                            },
                            color = p.textFaint,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(checked = limited, onCheckedChange = { limited = it })
                }

                if (limited) {
                    Spacer(Modifier.height(10.dp))
                    for (row in (1..totalWeeks).toList().chunked(6)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            for (w in row) {
                                val on = weeks.contains(w)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1.7f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (on) p.accent else p.chip)
                                        .clickable {
                                            weeks = if (on) weeks - w else weeks + w
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$w",
                                        color = if (on) p.onAccent else p.chipText,
                                        fontSize = 12.sp,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                            for (i in row.size until 6) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectChip("全选", false, { weeks = (1..totalWeeks).toSet() })
                        SelectChip("奇数周", false, { weeks = (1..totalWeeks).filter { it % 2 == 1 }.toSet() })
                        SelectChip("偶数周", false, { weeks = (1..totalWeeks).filter { it % 2 == 0 }.toSet() })
                        SelectChip("清空", false, { weeks = emptySet() })
                    }
                }

                Spacer(Modifier.height(20.dp))
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton("取消", modifier = Modifier.weight(1f)) { onDismiss() }
                ActionButton(
                    text = if (isNew) "添加" else "保存",
                    primary = true,
                    enabled = canSave,
                    modifier = Modifier.weight(1.4f),
                ) {
                    val course = Course(
                        name = name.trim(),
                        teacher = teacher.trim(),
                        room = room.trim(),
                        className = className.trim(),
                        day = day,
                        from = from,
                        to = to,
                        weeks = if (limited) weeks.toList().sorted() else emptyList(),
                    )
                    if (isNew) state.addCourse(course) else state.updateCourse(index, course)
                    onSaved(if (isNew) "已添加" else "已保存")
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(label, color = p.textFaint, fontSize = 11.5.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text(placeholder, fontSize = 12.5.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
        )
    }
}
