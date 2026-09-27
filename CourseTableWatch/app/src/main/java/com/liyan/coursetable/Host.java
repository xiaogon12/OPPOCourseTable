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
package com.liyan.coursetable;

import android.content.Context;
import android.view.View;

/** 页面（课表页 / 我的页）与主界面之间的约定 */
public interface Host {

    Context ctx();

    Palette pal();

    AppConfig cfg();

    Store store();

    /** 由当前设置推算出来的作息表 */
    TimeTable timeTable();

    /**
     * 打开一个全屏弹层。
     * 如果栈顶弹层的 tag 相同，则原地更新标题与内容（用于弹层内部改设置后自我刷新）。
     */
    void showSheet(String tag, String title, View body);

    /** 无标题栏的全屏弹层（二维码用），body 铺满整屏 */
    void showOverlay(String tag, View body);

    /** 关闭最上面的弹层 */
    void closeSheet();

    /**
     * 设置被修改了。
     *
     * @param rebuildUi true 表示需要连配色一起重建（换风格），false 只刷新数据
     */
    void configChanged(boolean rebuildUi);

    /** 左右滑动换天：dir = +1 下一天，-1 上一天 */
    void shiftDay(int dir);

    void openMine(boolean open);

    /** 申请日历权限（课前提醒要靠它写进系统日历，息屏才会响） */
    void requestCalendar();

    void toast(String msg);
}
