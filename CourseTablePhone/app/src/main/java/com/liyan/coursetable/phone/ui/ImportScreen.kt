package com.liyan.coursetable.phone.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liyan.coursetable.phone.model.CourseJson
import com.liyan.coursetable.phone.model.JsonParseException
import com.liyan.coursetable.phone.model.ParsedTable
import com.liyan.coursetable.phone.model.WEEKDAY_SHORT
import com.liyan.coursetable.phone.model.formatWeeks
import kotlinx.coroutines.delay

private sealed interface ParseState {
    data object Empty : ParseState
    data object Busy : ParseState
    data class Ok(val parsed: ParsedTable) : ParseState
    data class Fail(val error: JsonParseException) : ParseState
}

/**
 * 批量导入：粘贴 AI 生成的 JSON，实时解析 + 预览，确认后整表覆盖。
 * 单门课的增删改在课表页做，这里只负责批量入口。
 */
@Composable
fun ImportScreen(state: AppState, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<ParseState>(ParseState.Empty) }
    var showPrompt by remember { mutableStateOf(false) }
    val prompt by remember {
        mutableStateOf(runCatching { state.store.readAsset("prompt.txt") }.getOrDefault(""))
    }

    // 边输入边解析，带点防抖，免得一个字一个错
    LaunchedEffect(input) {
        if (input.isBlank()) {
            parsed = ParseState.Empty
            return@LaunchedEffect
        }
        parsed = ParseState.Busy
        delay(220)
        parsed = try {
            ParseState.Ok(CourseJson.parse(input, state.settings))
        } catch (e: JsonParseException) {
            ParseState.Fail(e)
        } catch (e: Exception) {
            ParseState.Fail(JsonParseException(e.message ?: "解析失败"))
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton("← 返回") { onClose() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("批量导入课表", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("粘贴 AI 生成的 JSON", color = p.textFaint, fontSize = 11.sp)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            // -------------------------------------------- 第 1 步
            Panel {
                PanelTitle("第 1 步 · 让 AI 生成 JSON")
                Text(
                    "复制下面的提示词，连同你的课表（截图转文字、教务系统复制都行）一起丢给任意 AI，" +
                        "它会返回一段标准 JSON。",
                    color = p.textDim,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("复制提示词", primary = true) {
                        if (prompt.isBlank()) {
                            toast(context, "提示词资源读取失败")
                        } else {
                            copyToClipboard(context, prompt, "课表 JSON 提示词")
                        }
                    }
                    ActionButton("看提示词") { showPrompt = true }
                }
            }

            Spacer(Modifier.height(12.dp))

            // -------------------------------------------- 第 2 步
            Panel {
                PanelTitle("第 2 步 · 把 JSON 粘到这里")
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("{\n  \"name\": \"2026 秋 课程表\",\n  \"courses\": [ ... ]\n}", fontSize = 12.sp)
                    },
                    minLines = 8,
                    maxLines = 14,
                    shape = RoundedCornerShape(16.dp),
                    textStyle = TextStyle(fontSize = 12.sp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("从剪贴板粘贴") {
                        val t = readClipboard(context)
                        if (t.isBlank()) toast(context, "剪贴板是空的") else input = t
                    }
                    ActionButton("清空") { input = "" }
                    ActionButton("填入示例") {
                        input = runCatching { state.store.readAsset("sample_course.json") }
                            .getOrDefault("")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (val s = parsed) {
                ParseState.Empty -> Unit
                ParseState.Busy -> Panel { PanelTitle("解析中…") }
                is ParseState.Fail -> ErrorPanel(s.error)
                is ParseState.Ok -> ResultPanel(s.parsed)
            }

            if (parsed is ParseState.Ok) {
                val ok = (parsed as ParseState.Ok).parsed
                Spacer(Modifier.height(14.dp))
                ActionButton(
                    text = "覆盖应用（${ok.courses.size} 门课）",
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.applyImport(ok)
                    toast(context, "已应用 ${ok.courses.size} 门课")
                    onClose()
                }
                Spacer(Modifier.height(6.dp))
                Hint("这会把手机上的课表和设置整体替换掉。手表上的内容要另外推送。")
            }
        }
    }

    if (showPrompt) {
        PromptDialog(prompt) { showPrompt = false }
    }
}

@Composable
private fun PromptDialog(prompt: String, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text("发给 AI 的提示词", color = p.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    prompt,
                    color = p.textDim,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            ActionButton("复制", primary = true) {
                copyToClipboard(context, prompt, "课表 JSON 提示词")
                onDismiss()
            }
        },
        dismissButton = { ActionButton("关闭") { onDismiss() } },
    )
}

@Composable
private fun ErrorPanel(error: JsonParseException) {
    val p = LocalPalette.current
    Panel {
        PanelTitle("解析失败")
        Text(error.detail, color = p.text, fontSize = 13.sp, lineHeight = 19.sp)
        if (error.line > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "第 ${error.line} 行，第 ${error.column} 列",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (error.snippet.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    error.snippet,
                    color = p.textDim,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Hint("把这段报错连着原 JSON 一起发回给 AI，让它重出一版，通常一次就能修好。")
    }
}

@Composable
private fun ResultPanel(parsed: ParsedTable) {
    val p = LocalPalette.current
    Panel {
        PanelTitle("解析结果")
        KeyValue("课程数", "${parsed.courses.size} 门")
        parsed.settings?.let { s ->
            if (s.tableName.isNotBlank()) KeyValue("课表名", s.tableName)
            if (s.startDate.isNotBlank()) {
                KeyValue(
                    "开学第一周",
                    s.startDate + if (s.startDateIsMonday) "（周一）" else "（不是周一）",
                )
            }
            KeyValue("总周数", "${s.totalWeeks} 周")
            KeyValue("作息", "${s.periodCount.joinToString("/")} 节 · 每节 ${s.periodMinutes} 分钟")
        }
        val span = parsed.courses.flatMap { it.weeks }.distinct().sorted()
        KeyValue("出现过的周次", if (span.isEmpty()) "每周" else formatWeeks(span))
        if (parsed.skipped > 0) KeyValue("已跳过", "${parsed.skipped} 条")

        if (parsed.settings == null) {
            Spacer(Modifier.height(8.dp))
            Hint("这段 JSON 里没有作息时间配置，手机上的设置保持不变。")
        }

        Spacer(Modifier.height(10.dp))
        Text("每天课程数", color = p.textFaint, fontSize = 11.5.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in 1..7) {
                val n = parsed.courses.count { it.day == d }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(WEEKDAY_SHORT[d - 1], color = p.textFaint, fontSize = 10.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        n.toString(),
                        color = if (n > 0) p.text else p.textFaint,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        if (parsed.warnings.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ThinDivider()
            Spacer(Modifier.height(8.dp))
            parsed.warnings.forEach { Hint(it, warn = true) }
        }

        if (parsed.courses.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ThinDivider()
            Spacer(Modifier.height(8.dp))
            Text("前几门课", color = p.textFaint, fontSize = 11.5.sp)
            Spacer(Modifier.height(4.dp))
            parsed.courses.take(6).forEach { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorDot(p.barColor(c.name), 8.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        c.name,
                        color = p.text,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "周${WEEKDAY_SHORT.getOrElse(c.day - 1) { "?" }} ${c.from}-${c.to}节",
                        color = p.textDim,
                        fontSize = 11.sp,
                    )
                }
            }
            if (parsed.courses.size > 6) {
                Spacer(Modifier.height(4.dp))
                Text("… 还有 ${parsed.courses.size - 6} 门", color = p.textFaint, fontSize = 11.sp)
            }
        }
    }
}
