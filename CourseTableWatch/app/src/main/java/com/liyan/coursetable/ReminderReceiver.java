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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 闹钟触发：发通知 + 安排下一次 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean test = intent != null && intent.getBooleanExtra(Reminder.EXTRA_TEST, false);
        try {
            if (test) {
                Reminder.notify(context, "测试提醒",
                        "这是一条测试通知，点开可以看到课程表。");
                return;
            }
            AppConfig cfg = Store.get(context).config;
            if (!cfg.reminderOn) {
                Log.i("CourseTable", "收到闹钟但提醒已关闭");
                return;
            }
            Reminder.Occ o = Reminder.next(context);
            if (o == null) {
                return;
            }
            String title = cfg.reminderMinutes + " 分钟后上课";
            String text = Reminder.describe(context, o.course);
            Reminder.notify(context, title, text);
            Log.i("CourseTable", "已提醒：" + title + " " + text);
        } catch (Exception e) {
            Log.w("CourseTable", "处理提醒失败: " + e);
        } finally {
            // 无论成功与否都重排下一次
            Reminder.schedule(context);
        }
    }
}
