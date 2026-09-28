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

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 课前提醒：两条路一起走。
 *
 * <p><b>主路——写进系统日历</b>（{@link CalendarSync}）：课程 +「提前 N 分钟」
 * 一起写进日历，到点由<b>系统日历</b>（{@code com.heytap.wearable.calendar}）
 * 按这个提前量弹提醒。它是系统应用，息屏照样能提醒——这台表上真正会响的就是它。
 *
 * <p><b>辅路——自己排闹钟</b>（{@link #armAlarms}）：多一层保险，
 * 表亮着的时候会额外响一次，靠 {@link #claim} 去重，不会重复打扰。
 * 息屏时这条路基本指望不上：ColorOS 手表版的 BmPowerManager 会在
 * 「息屏 + 电量均衡」下直接拒投第三方闹钟（日志
 * {@code forbin thirdapp(...) set wakeup alarm} /
 * {@code Forbid delivering pending non wakeup alarm}），
 * 换 setAlarmClock、加电池白名单都没用，属于系统限制。
 *
 * <p>所以：<b>改了「提前 N 分钟」必须重写日历</b>，
 * {@code MainActivity.configChanged()} 里那一次 {@link #schedule} 不能省。
 */
public final class Reminder {

    private static final String TAG = "CourseTable";
    public static final String CHANNEL_ID = "course_reminder";
    /** 一次排多少条提醒（未来几天），每响一次补一次 */
    private static final int SLOTS = 6;
    /** 提醒闹钟的 PendingIntent 请求码起点，每个槽位一个 */
    private static final int REQ_BASE = 2110;
    private static final int REQ_TEST = 2002;

    private static final String PREFS = "ctw_reminder";

    public static final String EXTRA_TEST = "test";
    public static final String EXTRA_HEAD = "head";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_KEY = "key";

    private Reminder() {
    }

    /** 一次即将到来的课 */
    public static class Occ {
        public Course course;
        public Calendar start;
        public long triggerAt;
        /** 通知标题，如「还有 60 分钟上课」 */
        public String head;
        /** 通知正文，如「高等数学 · 08:00-09:40 · A103」 */
        public String text;
        /** 去重用的键：同一节课的提醒只发一次 */
        public String key;
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

    /**
     * 未来 {@code limit} 次该提醒的时刻，按时间正序。
     *
     * <p>一次排一串、而不是只排「下一条」：只排一条的话，任何一次没投递
     * （息屏被系统省电掐掉、进程被杀、用户关机）都会让后面的提醒一起断掉。
     * 排成串，每响一次就补一次，链路自己能接上。
     */
    public static List<Occ> upcoming(Context c, int limit) {
        Store store = Store.get(c);
        AppConfig cfg = store.config;
        TimeTable tt = new TimeTable(cfg);
        List<Occ> out = new ArrayList<>();

        Calendar now = Calendar.getInstance();
        long nowMs = now.getTimeInMillis();
        for (int d = 0; d < 15; d++) {
            Calendar day = (Calendar) now.clone();
            day.add(Calendar.DAY_OF_MONTH, d);
            int dow = Weeks.dowOf(day);
            int week = Weeks.weekOf(cfg, day);
            List<Course> list = store.ofDay(dow, week);
            for (Course co : list) {
                int startMin = tt.start(co.startPeriod);
                Calendar st = (Calendar) day.clone();
                st.set(Calendar.HOUR_OF_DAY, (startMin / 60) % 24);
                st.set(Calendar.MINUTE, startMin % 60);
                st.set(Calendar.SECOND, 0);
                st.set(Calendar.MILLISECOND, 0);
                if (!st.after(now)) {
                    continue;   // 已经开课了，不再为它排提醒
                }
                Occ o = new Occ();
                o.course = co;
                o.start = st;
                o.triggerAt = st.getTimeInMillis() - cfg.reminderMinutes * 60000L;
                if (o.triggerAt <= nowMs) {
                    // 提醒时刻已经过了（典型场景：刚把提前量改大）——
                    // 马上补一条，让用户立刻看到新设置生效，而不是静悄悄什么都不发生
                    o.triggerAt = nowMs + 3000L;
                }
                o.head = "还有 " + cfg.reminderMinutes + " 分钟上课";
                o.text = notifyText(co, tt);
                o.key = keyOf(co, st);
                out.add(o);
                if (out.size() >= limit) {
                    return out;
                }
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<Occ>() {
            @Override
            public int compare(Occ a, Occ b) {
                return Long.compare(a.triggerAt, b.triggerAt);
            }
        });
        return out;
    }

    /** 下一次需要提醒的时刻；没有则返回 null */
    public static Occ next(Context c) {
        List<Occ> list = upcoming(c, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 通知正文：课程名 · 上课时间 · 教室老师 */
    public static String notifyText(Course co, TimeTable tt) {
        StringBuilder sb = new StringBuilder(co.name).append(" · ").append(tt.rangeOfCourse(co));
        String sub = co.subtitle();
        if (sub != null && !sub.isEmpty()) {
            sb.append(" · ").append(sub);
        }
        return sb.toString();
    }

    /** 同一节课的稳定标识，用来去重（同一天、同一时间、同一门课算同一条） */
    private static String keyOf(Course co, Calendar start) {
        return String.format(Locale.CHINA, "%04d%02d%02d%02d%02d-%s",
                start.get(Calendar.YEAR), start.get(Calendar.MONTH) + 1,
                start.get(Calendar.DAY_OF_MONTH), start.get(Calendar.HOUR_OF_DAY),
                start.get(Calendar.MINUTE), co.name);
    }

    /** 串行执行，避免连续改设置时几个线程同时重写日历 */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "reminder-schedule");
                            t.setPriority(Thread.MIN_PRIORITY + 1);
                            return t;
                        }
                    });

    /**
     * 重新安排提醒（每次设置变化 / 打开 App 都要调）。
     *
     * <p>在主线程调用时自动丢到后台线程去做，主线程一步都不等——同步日历
     * 要跑上百次 ContentResolver 调用，实测 1.26 秒，放在主线程上就是黑屏 / 卡顿。
     * 已经在后台线程调用时就地执行（例如 Receiver 里），省一次线程切换。
     *
     * <p>首选：把课程写进系统日历，让日历 Provider 去排 EVENT_REMINDER 闹钟
     * —— 系统应用排的闹钟在息屏下也会被投递，而且到点会把 App 拉起来发通知。
     * <p>没有日历权限时退回应用闹钟（实测息屏下会被 ColorOS 拦截，只在亮屏可靠）。
     */
    public static void schedule(Context c) {
        final Context app = c.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    scheduleNow(app);
                }
            });
        } else {
            scheduleNow(app);
        }
    }

    private static void scheduleNow(Context c) {
        boolean cal = CalendarSync.hasPermission(c);
        Log.i(TAG, "排程: 日历权限=" + cal + "，课表 " + Store.get(c).courses.size() + " 门");
        // 日历是主路：把课程 +「提前 N 分钟」写进去，由系统日历负责到点提醒。
        // 这里必须是「每次设置变了都重写」——不重写的话日历里留着的还是上一次的
        // 提前量（甚至上一次那张课表），用户在表上怎么调设置都不会生效。
        if (cal) {
            int n = CalendarSync.sync(c);
            Log.i(TAG, "日历同步: 写入 " + n + " 条");
        }
        armAlarms(c);
    }

    /**
     * 自己排未来几条提醒（辅助路径，见类注释）。
     *
     * <p>用 {@code setAlarmClock} 而不是普通精确闹钟：普通 wakeup 闹钟在
     * 设置阶段就被 ColorOS 拒掉（{@code forbin thirdapp set wakeup alarm}）；
     * {@code setAlarmClock} 至少能被排进队列、并在系统「下次闹钟」里可见，
     * 表亮着的时候能正常响。
     *
     * <p>一次排 {@link #SLOTS} 条，每条一个独立槽位；响应一次就整体重排一次，
     * 所以某次没投递也不会让后面的提醒一起断掉。
     */
    private static void armAlarms(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            // 先撤掉旧槽位，避免改完设置新旧提醒同时存在
            for (int i = 0; i < SLOTS; i++) {
                am.cancel(pending(c, REQ_BASE + i, false, null));
            }
            AppConfig cfg = Store.get(c).config;
            if (!cfg.reminderOn) {
                Log.i(TAG, "提醒已关闭，已撤掉全部提醒闹钟");
                return;
            }
            ensureChannel(c);
            Intent show = new Intent(c, MainActivity.class);
            int sflags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sflags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent showPi = PendingIntent.getActivity(c, 3002, show, sflags);

            long now = System.currentTimeMillis();
            int slot = 0;
            for (Occ o : upcoming(c, SLOTS)) {
                if (slot >= SLOTS || o.triggerAt <= now) {
                    continue;
                }
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(o.triggerAt, showPi),
                        pending(c, REQ_BASE + slot, false, o));
                Log.i(TAG, "排提醒[" + slot + "] " + fmt(o.triggerAt) + " -> " + o.text);
                slot++;
            }
            Log.i(TAG, "共排下 " + slot + " 条提醒（提前 " + cfg.reminderMinutes + " 分钟）");
        } catch (Exception e) {
            Log.w(TAG, "排提醒失败: " + e);
        }
    }

    /** 10 秒后测试一条通知 */
    public static void test(Context c) {
        ensureChannel(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(c, REQ_TEST, true, null);
        long at = System.currentTimeMillis() + 10000L;
        Intent show = new Intent(c, MainActivity.class);
        int sflags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sflags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent showPi = PendingIntent.getActivity(c, 3003, show, sflags);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
    }

    /** 闹钟携带通知文案，触发时直接用，不再临时重算（晚点响也不会张冠李戴） */
    private static PendingIntent pending(Context c, int req, boolean test, Occ o) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.putExtra(EXTRA_TEST, test);
        if (o != null) {
            i.putExtra(EXTRA_HEAD, o.head);
            i.putExtra(EXTRA_TEXT, o.text);
            i.putExtra(EXTRA_KEY, o.key);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, req, i, flags);
    }

    // ---------------------------------------------------------- 去重

    /**
     * 同一条提醒短时间内只发一次。
     *
     * <p>两条通路（系统日历 App 那条 + 我们自己排的闹钟）有可能前后脚都触发，
     * 这里做一次合并，免得一节课震两次。
     *
     * @return true 表示这次该发；false 表示刚发过、跳过
     */
    public static boolean claim(Context c, String key) {
        if (key == null || key.isEmpty()) {
            return true;
        }
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (key.equals(sp.getString("last_key", ""))
                && now - sp.getLong("last_at", 0L) < 20 * 60 * 1000L) {
            Log.i(TAG, "同一条提醒刚发过，跳过: " + key);
            return false;
        }
        sp.edit().putString("last_key", key).putLong("last_at", now).apply();
        return true;
    }

    // ---------------------------------------------------------- 发通知

    public static void notify(Context c, String title, String text) {
        notify(c, title, text, null);
    }

    /**
     * @param tag 同一条提醒用同一个 tag，后来的会覆盖先前的，不同课程互不覆盖
     */
    public static void notify(Context c, String title, String text, String tag) {
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
            if (tag == null || tag.isEmpty()) {
                nm.notify(CHANNEL_ID.hashCode() & 0xFFFF, b.build());
            } else {
                // 带 tag：不同课程的提醒互不覆盖，同一节课的重复提醒会覆盖自己
                nm.notify(tag, 0x11, b.build());
            }
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
        StringBuilder via = new StringBuilder();
        if (CalendarSync.hasPermission(c)) {
            via.append("已同步进系统日历 ").append(CalendarSync.lastCount(c)).append(" 条，由系统日历提醒");
        } else {
            via.append("未授权日历，只能靠 App 自己的闹钟（息屏可能不响）");
        }
        return "下一次课程：" + o.course.name + "\n"
                + String.format(Locale.CHINA, "%d月%d日 %s %s", o.start.get(Calendar.MONTH) + 1,
                o.start.get(Calendar.DAY_OF_MONTH), Weeks.DOW_SHORT[Weeks.dowOf(o.start)],
                TimeTable.hhmm(startMin))
                + "\n提前 " + cfg.reminderMinutes + " 分钟提醒（" + fmt(o.triggerAt) + "）\n" + via;
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
