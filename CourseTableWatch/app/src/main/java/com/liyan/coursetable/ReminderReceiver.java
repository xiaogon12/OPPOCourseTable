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
