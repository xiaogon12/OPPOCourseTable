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
package com.liyan.coursetable.phone

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.liyan.coursetable.phone.data.Store
import com.liyan.coursetable.phone.ui.AppRoot
import com.liyan.coursetable.phone.ui.AppState
import com.liyan.coursetable.phone.ui.CourseTableTheme
import com.liyan.coursetable.phone.ui.Palettes

class MainActivity : ComponentActivity() {

    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = AppState(applicationContext, Store(this))
        setContent {
            val palette = Palettes.byId(state.settings.themeId)
            SystemBarAppearance(lightPalette = palette.light)
            CourseTableTheme(palette) {
                AppRoot(state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置里改完蓝牙开关或权限，回到前台就重读一遍配对列表
        state.refreshBonded()
    }

    override fun onDestroy() {
        state.server.stop()
        super.onDestroy()
    }
}

/** 让状态栏 / 导航栏图标的明暗跟着主题走（浅色主题用深色图标，反之亦然）。 */
@Composable
private fun SystemBarAppearance(lightPalette: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = lightPalette
            controller.isAppearanceLightNavigationBars = lightPalette
        }
    }
}
