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

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liyan.coursetable.phone.sync.SyncServer

private val OK_GREEN = Color(0xFF3FBF6B)
private val WARN_AMBER = Color(0xFFE5A13D)

@Composable
fun SyncScreen(state: AppState, onGrantBluetooth: () -> Unit = {}) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var showFallback by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { state.refreshBonded() }

    val server = state.server
    val addresses = remember(server.running, server.servedCount) { SyncServer.localAddresses() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        // ------------------------------------------------ 权限（读不到设备基本都是这个原因）
        if (!state.btPermissionOk) {
            Panel {
                PanelTitle("先给蓝牙权限")
                Hint(
                    "手机还没给「课程表」蓝牙权限，所以一台已配对设备都读不到 —— " +
                        "看起来像蓝牙坏了，其实只是权限没给。",
                    warn = true,
                )
                Spacer(Modifier.height(10.dp))
                ActionButton("授予蓝牙权限", primary = true, modifier = Modifier.fillMaxWidth()) {
                    onGrantBluetooth()
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ------------------------------------------------ 蓝牙推送
        Panel {
            PanelTitle("同步到手表 · 蓝牙直连")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.push)
                Spacer(Modifier.width(9.dp))
                Text(
                    text = when (val s = state.push) {
                        is PushState.Idle ->
                            if (state.lastPushAt.isBlank()) "准备就绪" else "上次推送 $state.lastPushAt"
                        is PushState.Sending -> "正在推送到手表…"
                        is PushState.Ok -> "推送成功"
                        is PushState.Fail -> "推送失败"
                    },
                    color = p.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            when (val s = state.push) {
                is PushState.Ok -> {
                    Spacer(Modifier.height(8.dp))
                    Hint("手表已收到 ${s.courses} 门课，共 ${sizeText(s.bytes)}。手表上一会儿就会刷新。")
                }
                is PushState.Fail -> {
                    Spacer(Modifier.height(8.dp))
                    Hint(s.message, warn = true)
                }
                else -> Unit
            }

            Spacer(Modifier.height(14.dp))
            ThinDivider()
            Spacer(Modifier.height(6.dp))

            // 目标设备
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showPicker = true }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("目标设备", color = p.textFaint, fontSize = 11.5.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.watchName.ifBlank { "点这里选择手表" },
                        color = p.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.watchAddress.isNotBlank()) {
                        Text(
                            state.watchAddress,
                            color = p.textFaint,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Text("›", color = p.textFaint, fontSize = 18.sp)
            }

            if (state.bonded.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Hint(
                    if (state.btPermissionOk) {
                        "没读到已配对设备。先确认手机蓝牙开着，并且和手表配对过。"
                    } else {
                        "没有蓝牙权限，读不到已配对设备。点上面的「授予蓝牙权限」。"
                    },
                    warn = true,
                )
                Spacer(Modifier.height(8.dp))
                ActionButton("重新读取配对设备") { state.refreshBonded() }
            }

            Spacer(Modifier.height(14.dp))
            ActionButton(
                text = when (state.push) {
                    is PushState.Sending -> "推送中…"
                    else -> "推送到手表（${state.courses.size} 门课）"
                },
                primary = true,
                enabled = state.push !is PushState.Sending && state.courses.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { state.pushToWatch() },
            )
            Spacer(Modifier.height(6.dp))
            Hint("点上面之前，手表上要已经停在「正在等待手机推送…」那个界面。")
        }

        Spacer(Modifier.height(12.dp))

        // ------------------------------------------------ 手表上怎么操作
        Panel {
            PanelTitle("手表上怎么操作")
            Step(1, "手表打开课程表 App → 「我的」→「从手机接收」")
            Step(2, "手表再点「开始等待手机推送」，停在等待界面别退出")
            Step(3, "回到手机，点上面的「推送到手表」")
            Spacer(Modifier.height(8.dp))
            Hint(
                "手机和手表本来就是经典蓝牙配对的，所以不用开 Wi-Fi、不用开热点、" +
                    "不用输 IP 也不用配对码。",
            )
        }

        Spacer(Modifier.height(12.dp))

        // ------------------------------------------------ 将推送什么
        Panel {
            PanelTitle("将要推送的内容")
            KeyValue("课表名", state.settings.tableName.ifBlank { "（未命名）" })
            KeyValue("课程数", "${state.courses.size} 门")
            KeyValue("周次", state.weekSpan?.let { "$it 周" } ?: "每周")
            KeyValue(
                "学期",
                if (state.settings.startDate.isBlank()) "未设置开学日期"
                else "${state.settings.startDate} 起 · ${state.settings.totalWeeks} 周",
            )
            KeyValue(
                "作息",
                "${state.settings.periodCount.joinToString("/")} 节 · " +
                    "每节 ${state.settings.periodMinutes} 分 · " +
                    "课间 ${state.settings.breakMinutes}/${state.settings.bigBreakMinutes}",
            )
            KeyValue("提醒", if (state.settings.reminderOn) "课前 ${state.settings.reminderMinutes} 分钟" else "关闭")
            KeyValue("主题", Palettes.byId(state.settings.themeId).name)
            Spacer(Modifier.height(6.dp))
            Text(
                "负载 ${state.payload().toByteArray().size} 字节",
                color = p.textFaint,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ------------------------------------------------ 日志
        if (state.btLog.isNotEmpty()) {
            Panel {
                PanelTitle("日志")
                for (line in state.btLog.take(10)) {
                    Text(
                        line,
                        color = p.textDim,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ------------------------------------------------ 备用通道
        Panel {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showFallback = !showFallback },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelTitle("备用通道 · 局域网直连", Modifier.weight(1f))
                Text(if (showFallback) "收起" else "展开", color = p.textFaint, fontSize = 11.5.sp)
            }
            if (!showFallback) {
                Hint("蓝牙连不上时用。手表连上手机热点，手表主动来拉，手机这边等着。")
            } else {
                Text(
                    "手机在局域网里起一个小 HTTP 服务，手表连上后主动来拉同一份数据。",
                    color = p.textDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                if (!server.running) {
                    ActionButton("开始等待（端口 ${server.port}）", primary = true) {
                        val err = server.start()
                        if (err != null) toast(context, err)
                    }
                } else {
                    if (addresses.isEmpty()) {
                        Hint("没检测到局域网地址，先连上 Wi-Fi 或打开手机热点。", warn = true)
                    } else {
                        for (a in addresses) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "http://$a:${server.port}",
                                    color = p.text,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                                ActionButton("复制") {
                                    copyToClipboard(context, "http://$a:${server.port}", "同步地址")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("配对码 ", color = p.textFaint, fontSize = 11.5.sp)
                        Text(
                            server.code,
                            color = p.accent,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton("停止", modifier = Modifier.weight(1f)) { server.stop() }
                        ActionButton("换配对码", modifier = Modifier.weight(1f)) { server.regenerateCode() }
                    }
                    if (server.log.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        for (e in server.log.take(6)) {
                            Text(
                                "${e.time}  ${e.text}",
                                color = if (e.ok) p.textDim else WARN_AMBER,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }

    KeepScreenOn(server.running)

    if (showPicker) {
        DevicePicker(state, onGrantBluetooth) { showPicker = false }
    }
}

// ---------------------------------------------------------------- 设备选择

@Composable
private fun DevicePicker(state: AppState, onGrantBluetooth: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text("选择手表", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (state.bonded.isEmpty()) {
                    Text(
                        if (state.btPermissionOk) {
                            "读不到已配对设备。先在系统设置里把手机和手表蓝牙配对好，再回来。"
                        } else {
                            "还没有蓝牙权限，所以读不到已配对设备。先授予权限。"
                        },
                        color = p.textDim,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                } else {
                    Text(
                        "下面都是手机已配对的蓝牙设备，选手表那个（耳机、音箱别选）。",
                        color = p.textFaint,
                        fontSize = 11.5.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (d in state.bonded) {
                        val selected = d.address == state.watchAddress
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) p.accentSoft else Color.Transparent)
                                .clickable {
                                    state.chooseWatch(d)
                                    onDismiss()
                                }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    d.name,
                                    color = p.text,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (d.looksLikeWatch) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    d.address,
                                    color = p.textFaint,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            when {
                                d.looksLikeWatch -> Text("像是手表", color = p.accent, fontSize = 10.5.sp)
                                d.isAudio -> Text("耳机/音箱", color = p.textFaint, fontSize = 10.5.sp)
                            }
                            if (selected) {
                                Spacer(Modifier.width(6.dp))
                                Text("✓", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.btPermissionOk) {
                ActionButton("重新读取") { state.refreshBonded() }
            } else {
                ActionButton("授予蓝牙权限", primary = true) { onGrantBluetooth() }
            }
        },
        dismissButton = { ActionButton("关闭") { onDismiss() } },
    )
}

// ---------------------------------------------------------------- 小件

@Composable
private fun StatusDot(push: PushState) {
    val p = LocalPalette.current
    val color = when (push) {
        is PushState.Idle -> p.textFaint
        is PushState.Sending -> p.accent
        is PushState.Ok -> OK_GREEN
        is PushState.Fail -> WARN_AMBER
    }
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Box(
        Modifier
            .size(11.dp)
            .alpha(if (push is PushState.Sending) alpha else 1f)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun Step(index: Int, text: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(p.chip),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", color = p.chipText, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(9.dp))
        Text(text, color = p.textDim, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = (view.context as? Activity)?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (enabled) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/** 人看得懂的体积（直接甩原始字节数读起来费劲） */
private fun sizeText(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.CHINA, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1048576.0)
}
