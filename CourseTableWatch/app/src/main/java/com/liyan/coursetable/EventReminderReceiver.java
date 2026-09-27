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
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract.CalendarAlerts;
import android.text.TextUtils;
import android.util.Log;

/**
 * 接收系统日历 Provider 发出的 {@code android.intent.action.EVENT_REMINDER} 广播。
 *
 * <p>这条路绕开了 ColorOS 对第三方闹钟的拦截：
 * 闹钟是「系统日历 Provider」(com.android.providers.calendar) 这个系统应用排的，
 * 日志里只会出现 {@code [BP_ISSUE]} 警告并被正常投递、还会把 App 进程拉起来；
 * 而第三方 App 自己调 AlarmManager 会被
 * {@code WearFrw [BmPowerManager] forbin thirdapp ... set wakeup alarm} 直接拒绝。
 *
 * <p>该广播在 Android 8.0+ 的「隐式广播例外清单」中，所以可以静态注册。
 * intent 形如：
 * {@code act=android.intent.action.EVENT_REMINDER dat=content://com.android.calendar/<alertTime>}
 */
public class EventReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "CourseTable";

    /** 事件表里标记来源的列（日历界面不显示，所以备注里看不到多余字符） */
    private static final String COL_CUSTOM_PKG = "customAppPackage";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Uri data = intent.getData();
            long alarmTime = data != null ? parseLong(data.getLastPathSegment()) : 0;
            Log.i(TAG, "EVENT_REMINDER 到达: data=" + data + " alarmTime=" + alarmTime);
            if (alarmTime <= 0) {
                return;
            }

            // extras 里的键名各家 ROM 不一，统一回查 alert 表最稳
            String title = null;
            String desc = null;
            long eventId = -1;
            Cursor cur = null;
            try {
                cur = context.getContentResolver().query(CalendarAlerts.CONTENT_URI,
                        new String[]{CalendarAlerts.TITLE, CalendarAlerts.DESCRIPTION,
                                CalendarAlerts.EVENT_ID, COL_CUSTOM_PKG},
                        CalendarAlerts.ALARM_TIME + "=?",
                        new String[]{String.valueOf(alarmTime)}, null);
                if (cur != null && cur.moveToFirst()) {
                    title = cur.getString(0);
                    desc = cur.getString(1);
                    eventId = cur.getLong(2);
                }
            } catch (Exception e) {
                Log.w(TAG, "回查 calendar_alerts 失败（可能没有日历权限）: " + e);
            } finally {
                if (cur != null) {
                    cur.close();
                }
            }

            if (TextUtils.isEmpty(title)) {
                Log.i(TAG, "alert 表里查不到这条提醒，忽略");
                return;
            }

            // 只对本 App 写进去的课程事件发通知，避免把用户自己的日程也推一遍
            if (!CalendarSync.isOurs(context, eventId)) {
                Log.i(TAG, "不是本 App 的日程，忽略: " + title + " (eventId=" + eventId + ")");
                return;
            }

            String text = TextUtils.isEmpty(desc) ? "" : desc;
            Reminder.notify(context, title, text);
            Log.i(TAG, "已发出课程提醒: " + title + " / " + text);
        } catch (Exception e) {
            Log.w(TAG, "处理 EVENT_REMINDER 出错: " + e);
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
