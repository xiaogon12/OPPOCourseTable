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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liyan.coursetable.phone.sync.BtSync

/** 三个标签：同步（首页） / 课表数据 / 设置。 */
@Composable
fun AppRoot(state: AppState) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var showImport by remember { mutableStateOf(false) }

    // 蓝牙运行时权限（Android 12+）。没这一步 getBondedDevices() 会抛 SecurityException，
    // 表现就是「一台已配对设备都读不到」。
    val btPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { state.refreshBonded() }
    val askBluetooth = {
        val missing = BtSync.missingPermissions(context)
        if (missing.isEmpty()) state.refreshBonded()
        else btPermission.launch(missing.toTypedArray())
    }

    LaunchedEffect(Unit) {
        if (BtSync.missingPermissions(context).isNotEmpty()) askBluetooth()
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = p.surface, tonalElevation = 0.dp) {
                    for (tab in Tab.entries) {
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { state.tab = tab },
                            icon = {
                                Icon(
                                    imageVector = iconOf(tab),
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = p.accent,
                                selectedTextColor = p.accent,
                                indicatorColor = p.accentSoft,
                                unselectedIconColor = p.textFaint,
                                unselectedTextColor = p.textFaint,
                            ),
                        )
                    }
                }
            },
        ) { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 14.dp),
            ) {
                // 刻意用 when 直接切，不套 AnimatedContent：
                // 过渡动画期间新旧两屏会同时在组合，切到「设置」这种长页面时
                // 等于一帧渲染两屏，实测就是那几帧几百毫秒的顿挫。
                // 标签页切换本来就该是瞬时的。
                when (state.tab) {
                    Tab.Sync -> SyncScreen(state, onGrantBluetooth = askBluetooth)
                    Tab.Data -> CourseListScreen(state) { showImport = true }
                    Tab.Settings -> SettingsScreen(state)
                }
            }
        }

        AnimatedVisibility(
            visible = showImport,
            enter = slideInVertically(tween(240)) { it / 6 } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(200)) { it / 6 } + fadeOut(tween(140)),
        ) {
            ImportScreen(state) { showImport = false }
        }
    }
}

private fun iconOf(tab: Tab): ImageVector = when (tab) {
    Tab.Sync -> Icons.Filled.Refresh
    Tab.Data -> Icons.Filled.DateRange
    Tab.Settings -> Icons.Filled.Settings
}
