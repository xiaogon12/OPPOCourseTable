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

    private static final String TAG = "CourseTable";

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
                Log.i(TAG, "收到闹钟但提醒已关闭");
                return;
            }
            // 文案在排闹钟时就写进 intent 了：晚点响也不会张冠李戴
            String head = intent == null ? null : intent.getStringExtra(Reminder.EXTRA_HEAD);
            String text = intent == null ? null : intent.getStringExtra(Reminder.EXTRA_TEXT);
            String key = intent == null ? null : intent.getStringExtra(Reminder.EXTRA_KEY);
            if (head == null || text == null) {
                // 老版本留下的闹钟没带文案，退回「现算一次」
                Reminder.Occ o = Reminder.next(context);
                if (o == null) {
                    return;
                }
                head = o.head;
                text = o.text;
                key = o.key;
            }
            if (!Reminder.claim(context, key)) {
                return;
            }
            Reminder.notify(context, head, text, key);
            Log.i(TAG, "已提醒：" + head + " / " + text);
        } catch (Exception e) {
            Log.w(TAG, "处理提醒失败: " + e);
        } finally {
            // 无论成功与否都重排下一次
            Reminder.schedule(context);
        }
    }
}
