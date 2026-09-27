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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liyan.coursetable.phone.model.Course
import com.liyan.coursetable.phone.model.TimeTable
import com.liyan.coursetable.phone.model.WEEKDAY_SHORT
import com.liyan.coursetable.phone.model.formatWeeks

/**
 * 课表数据页：按星期分组列出每一门课，点进去就能改所有字段。
 * 这里刻意不做周网格总览 —— 那是手机端课程表软件该干的事，这个 App 的职责是把数据做对、推过去。
 */
@Composable
fun CourseListScreen(state: AppState, onOpenImport: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val tt = state.timeTable

    var editingIndex by remember { mutableStateOf(-1) }
    var creating by remember { mutableStateOf(false) }

    // 分组 + 排序只在课表真的换了之后算一次。
    // 原先这几行是直接写在组合里的，于是每次重组（点一下、开关一个弹层）
    // 都要 withIndex + 7 次 filter + 7 次 sorted，纯属白做。
    val groups = remember(state.courses) {
        (1..7).map { day ->
            DayGroup(
                day = day,
                items = state.courses.withIndex()
                    .filter { it.value.day == day }
                    .sortedWith(compareBy({ it.value.from }, { it.value.to })),
            )
        }.filter { it.items.isNotEmpty() }
    }

    // 用 LazyColumn 而不是 Column + verticalScroll：
    // 后者会把所有课程一次性全部组合出来，课多了（比如 40 门）进页面就明显卡一下。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item(key = "head") {
            Panel {
                PanelTitle("课表数据")
                Text(
                    state.settings.tableName.ifBlank { "未命名课表" },
                    color = p.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append("${state.courses.size} 门课")
                        state.weekSpan?.let { append(" · 第 $it 周") }
                        append(" · 每天 ${state.settings.totalPeriods} 节")
                    },
                    color = p.textDim,
                    fontSize = 11.5.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionButton("批量导入 JSON", modifier = Modifier.weight(1f)) { onOpenImport() }
                    ActionButton("新增课程", primary = true, modifier = Modifier.weight(1f)) {
                        creating = true
                    }
                }
                if (!state.imported) {
                    Spacer(Modifier.height(8.dp))
                    Hint("现在显示的是内置示例，导入你自己的 JSON 后会覆盖掉。")
                }
            }
        }

        if (groups.isEmpty()) {
            item(key = "empty") {
                Spacer(Modifier.height(12.dp))
                Panel {
                    PanelTitle("还没有课程")
                    Hint("点上面的「批量导入 JSON」，或者「新增课程」手动加一门。")
                }
            }
        }

        items(groups, key = { "day${it.day}" }) { g ->
            Spacer(Modifier.fillMaxWidth().height(14.dp))
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "周${WEEKDAY_SHORT[g.day - 1]}",
                    color = p.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text("${g.items.size} 门", color = p.textFaint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))

            // 一天一个面板：同一天最多也就几门课，整块组合没有压力，
            // 而按「天」做 key 之后，别的天变化不会牵连这一天重组。
            Panel(padding = 6) {
                g.items.forEachIndexed { i, entry ->
                    CourseRow(entry.value, tt) { editingIndex = entry.index }
                    if (i < g.items.size - 1) {
                        Box(Modifier.padding(start = 14.dp)) { ThinDivider() }
                    }
                }
            }
        }
    }

    if (creating) {
        CourseEditSheet(
            initial = null,
            index = -1,
            state = state,
            onDismiss = { creating = false },
            onSaved = { toast(context, it) },
        )
    }

    val idx = editingIndex
    if (idx >= 0 && idx < state.courses.size) {
        CourseEditSheet(
            initial = state.courses[idx],
            index = idx,
            state = state,
            onDismiss = { editingIndex = -1 },
            onSaved = { toast(context, it) },
        )
    }
}

/** 一天的分组。[items] 里的 index 是它在 `state.courses` 里的下标（编辑时按它回写）。 */
@Immutable
private class DayGroup(val day: Int, val items: List<IndexedValue<Course>>)

@Composable
private fun CourseRow(course: Course, tt: TimeTable, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.barColor(course.name)),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                course.name,
                color = p.text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("第 ${course.from}-${course.to} 节 · ${tt.range(course.from, course.to)}")
                    if (course.room.isNotBlank()) append(" · ${course.room}")
                    if (course.teacher.isNotBlank()) append(" · ${course.teacher}")
                },
                color = p.textDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatWeeks(course.weeks),
            color = p.textFaint,
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.width(4.dp))
        Text("›", color = p.textFaint, fontSize = 18.sp)
    }
}
