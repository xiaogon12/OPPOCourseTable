package com.liyan.coursetable;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 课前提醒。
 *
 * 方案：AlarmManager 精确闹钟（setExactAndAllowWhileIdle）
 *   · 「上课前 N 分钟」属于用户显式设置的提醒，可以申请精确闹钟；
 *   · 触发后由 BroadcastReceiver 发通知，并顺手安排下一次；
 *   · 开机 / 应用更新后由 Receiver 重新排一次。
 *
 * 每次只排「下一次」，不做批量，避免被系统判定为滥用后台。
 */
public final class Reminder {

    private static final String TAG = "CourseTable";
    public static final String CHANNEL_ID = "course_reminder";
    private static final int REQ_NORMAL = 2001;
    private static final int REQ_TEST = 2002;

    public static final String EXTRA_TEST = "test";

    private Reminder() {
    }

    /** 一次即将到来的课 */
    public static class Occ {
        public Course course;
        public Calendar start;
        public long triggerAt;
    }

    // ---------------------------------------------------------- 通知渠道

    public static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
            if (ch == null) {
                ch = new NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("上课前的提醒通知");
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 220, 160, 220});
                ch.setShowBadge(true);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            } else {
                ch.setImportance(NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
        }
    }

    // ---------------------------------------------------------- 通知权限

    /** 应用层通知总开关是否打开（关着的话通知一条都发不出来） */
    public static boolean notificationsEnabled(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= 24) {
                return nm.areNotificationsEnabled();
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    /** 跳到本应用的通知设置页，让用户手动打开开关 */
    public static void openNotificationSettings(Context c) {
        try {
            Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("android.provider.extra.APP_PACKAGE", c.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(android.net.Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------- 排程

    /** 计算下一次需要提醒的时间；没有则返回 null */
    public static Occ next(Context c) {
        Store store = Store.get(c);
        AppConfig cfg = store.config;
        TimeTable tt = new TimeTable(cfg);

        Calendar now = Calendar.getInstance();
        for (int d = 0; d < 15; d++) {
            Calendar day = (Calendar) now.clone();
            day.add(Calendar.DAY_OF_MONTH, d);
            int dow = Weeks.dowOf(day);
            int week = Weeks.weekOf(cfg, day);
            List<Course> list = store.ofDay(dow, week);
            Occ best = null;
            for (Course co : list) {
                int startMin = tt.start(co.startPeriod);
                Calendar st = (Calendar) day.clone();
                st.set(Calendar.HOUR_OF_DAY, (startMin / 60) % 24);
                st.set(Calendar.MINUTE, startMin % 60);
                st.set(Calendar.SECOND, 0);
                st.set(Calendar.MILLISECOND, 0);
                if (!st.after(now)) {
                    continue;
                }
                if (best == null || st.before(best.start)) {
                    Occ o = new Occ();
                    o.course = co;
                    o.start = st;
                    o.triggerAt = st.getTimeInMillis() - cfg.reminderMinutes * 60000L;
                    if (o.triggerAt <= now.getTimeInMillis()) {
                        o.triggerAt = now.getTimeInMillis() + 1000L;
                    }
                    best = o;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * 重新安排提醒（每次设置变化 / 打开 App 都要调）。
     *
     * <p>首选：把课程写进系统日历，让日历 Provider 去排 EVENT_REMINDER 闹钟
     * —— 系统应用排的闹钟在息屏下也会被投递，而且到点会把 App 拉起来发通知。
     * <p>没有日历权限时退回应用闹钟（实测息屏下会被 ColorOS 拦截，只在亮屏可靠）。
     */
    public static void schedule(Context c) {
        if (CalendarSync.hasPermission(c)) {
            int n = CalendarSync.sync(c);
            if (n >= 0) {
                cancelAlarm(c);
                Log.i(TAG, "提醒走系统日历，共 " + n + " 条");
                return;
            }
        }
        scheduleByAlarm(c);
    }

    /** 改走日历后把自家闹钟撤掉，避免重复提醒 */
    private static void cancelAlarm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pending(c, REQ_NORMAL, false));
            }
        } catch (Exception ignored) {
        }
    }

    /** AlarmManager 兜底排程 */
    private static void scheduleByAlarm(Context c) {
        try {
            ensureChannel(c);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            PendingIntent pi = pending(c, REQ_NORMAL, false);
            am.cancel(pi);

            AppConfig cfg = Store.get(c).config;
            if (!cfg.reminderOn) {
                Log.i(TAG, "提醒已关闭");
                return;
            }
            Occ o = next(c);
            if (o == null) {
                Log.i(TAG, "没有可提醒的课程");
                return;
            }
            // 用 setAlarmClock 而不是 setExactAndAllowWhileIdle：
            // ColorOS 手表版的 BmPowerManager 会拦截第三方普通 wakeup 闹钟
            // （日志 forbin thirdapp set wakeup alarm），但 setAlarmClock 是
            // 「用户闹钟」语义，享有系统闹钟同级的唤醒优先级，不在拦截名单。
            Intent show = new Intent(c, MainActivity.class);
            int sflags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sflags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent showPi = PendingIntent.getActivity(c, 3002, show, sflags);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(o.triggerAt, showPi), pi);
            Log.i(TAG, "下一次提醒（应用闹钟）：" + fmt(o.triggerAt) + " -> " + o.course.name);
        } catch (Exception e) {
            Log.w(TAG, "排程失败: " + e);
        }
    }


    /** 10 秒后测试一条通知 */
    public static void test(Context c) {
        ensureChannel(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(c, REQ_TEST, true);
        long at = System.currentTimeMillis() + 10000L;
        Intent show = new Intent(c, MainActivity.class);
        int sflags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sflags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent showPi = PendingIntent.getActivity(c, 3003, show, sflags);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
    }

    private static PendingIntent pending(Context c, int req, boolean test) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.putExtra(EXTRA_TEST, test);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, req, i, flags);
    }

    // ---------------------------------------------------------- 发通知

    public static void notify(Context c, String title, String text) {
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(c, 3001, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CHANNEL_ID);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setVibrate(new long[]{0, 220, 160, 220})
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        try {
            nm.notify(CHANNEL_ID.hashCode() & 0xFFFF, b.build());
        } catch (Exception e) {
            Log.w(TAG, "通知失败: " + e);
        }
    }

    // ---------------------------------------------------------- 文案

    public static String nextInfo(Context c) {
        AppConfig cfg = Store.get(c).config;
        if (!cfg.reminderOn) {
            return "提醒已关闭。";
        }
        Occ o = next(c);
        if (o == null) {
            return "未来两周没有找到课程。";
        }
        TimeTable tt = new TimeTable(cfg);
        int startMin = tt.start(o.course.startPeriod);
        String via = CalendarSync.hasPermission(c)
                ? "由系统日历提醒 · 已写入 " + CalendarSync.lastCount(c) + " 条（App 不用常驻后台）"
                : "未授权日历，暂用应用闹钟（息屏可能不响）";
        return "下一次课程：" + o.course.name + "\n"
                + String.format(Locale.CHINA, "%d月%d日 %s %s", o.start.get(Calendar.MONTH) + 1,
                o.start.get(Calendar.DAY_OF_MONTH), Weeks.DOW_SHORT[Weeks.dowOf(o.start)],
                TimeTable.hhmm(startMin))
                + "\n提前 " + cfg.reminderMinutes + " 分钟提醒\n" + via;
    }

    public static String describe(Context c, Course co) {
        StringBuilder sb = new StringBuilder(co.name);
        if (!co.room.isEmpty()) {
            sb.append(" · ").append(co.room);
        }
        return sb.toString();
    }

    private static String fmt(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(Locale.CHINA, "%02d-%02d %02d:%02d",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }
}
