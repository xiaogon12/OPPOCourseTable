package com.liyan.coursetable;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.CalendarAlerts;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * 把课程写进系统日历，让「日历 Provider」替我们排提醒闹钟。
 *
 * <p><b>为什么要这么绕：</b>ColorOS 手表版在「息屏 + 电量均衡」下会直接拒绝第三方 App
 * 自设的 wakeup 闹钟（日志 {@code forbin thirdapp(...) set wakeup alarm}），
 * 加电池白名单、换 setAlarmClock 都无效。但系统日历 Provider
 * （{@code com.android.providers.calendar}）是系统应用，它排的
 * {@code android.intent.action.EVENT_REMINDER} 闹钟会被正常投递。
 *
 * <p><b>实测通过的完整链路：</b>
 * <ol>
 *   <li>本类写入 事件 + Reminders 行 + CalendarAlerts 行；</li>
 *   <li>对 Reminders 行做一次 UPDATE —— 这是让 Provider 调
 *       {@code scheduleNextAlarm()} 重排闹钟的有效触发点（只 insert 不会触发）；</li>
 *   <li>Provider 排下 RTC_WAKEUP 闹钟；</li>
 *   <li>到点系统投递 {@code EVENT_REMINDER} 广播，并把 App 进程拉起来；</li>
 *   <li>{@link EventReminderReceiver} 收到后发通知。</li>
 * </ol>
 *
 * <p>App 不需要常驻后台，也不依赖自身闹钟（那条路在息屏时是废的）。
 */
public final class CalendarSync {

    private static final String TAG = "CourseTable";

    /** 写进 description 的标记，用来区分用户自己的日程 */
    public static final String MARK = "#ctw#";

    /**
     * 往前写多少天。
     * 每次打开 App 都会重新计算并覆盖这段窗口，所以只要两周内开过一次 App，
     * 提醒就不会断。窗口太长会让日历里堆太多课程事件，两周是折中值。
     */
    private static final int SYNC_DAYS = 14;

    private static final String PREFS = "ctw_calendar";
    private static final String KEY_IDS = "event_ids";

    /** 上一次同步的时间戳，用来防抖（onCreate + onResume 会连着调两次） */
    private static long sLastSyncAt = 0L;
    private static String sLastSig = null;
    private static final long MIN_INTERVAL = 15000L;

    /** 影响日历内容的设置指纹，用来判断「设置有没有变」 */
    private static String signature(AppConfig cfg) {
        return cfg.reminderOn + "|" + cfg.reminderMinutes + "|" + cfg.startDate + "|" + cfg.totalWeeks
                + "|" + cfg.weekOffset
                + "|" + cfg.periodCount[0] + "," + cfg.periodCount[1] + "," + cfg.periodCount[2]
                + "|" + cfg.sectionStart[0] + "," + cfg.sectionStart[1] + "," + cfg.sectionStart[2]
                + "|" + cfg.periodMinutes + "|" + cfg.breakMinutes + "|" + cfg.bigBreakMinutes;
    }

    private CalendarSync() {
    }

    // ---------------------------------------------------------- 权限

    public static boolean hasPermission(Context c) {
        try {
            return c.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                    == PackageManager.PERMISSION_GRANTED
                    && c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------- 找可写日历

    /** 找一个能写的日历；找不到返回 -1 */
    public static long findWritableCalendar(Context c) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(Calendars.CONTENT_URI,
                    new String[]{Calendars._ID, Calendars.CALENDAR_ACCESS_LEVEL,
                            Calendars.CALENDAR_DISPLAY_NAME},
                    null, null, Calendars._ID + " ASC");
            long first = -1;
            while (cur != null && cur.moveToNext()) {
                long id = cur.getLong(0);
                long access = cur.getLong(1);
                if (first < 0) {
                    first = id;
                }
                // CAL_ACCESS_CONTRIBUTOR(500) 及以上才能写
                if (access >= 500) {
                    Log.i(TAG, "日历同步: 选中日历 " + id + " " + cur.getString(2));
                    return id;
                }
            }
            if (first > 0) {
                Log.i(TAG, "日历同步: 没有 access>=500 的日历，退回第一个 " + first);
                return first;
            }
        } catch (Exception e) {
            Log.w(TAG, "日历同步: 查询日历失败 " + e);
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        return -1;
    }

    // ---------------------------------------------------------- 写入 / 清理

    /**
     * 重新同步课程到日历。
     *
     * @return 写入的事件条数；-1 表示没有权限 / 没有可用日历
     */
    public static synchronized int sync(Context c) {
        if (!hasPermission(c)) {
            Log.i(TAG, "日历同步: 没有日历权限，跳过");
            return -1;
        }
        AppConfig cfg = Store.get(c).config;
        // 防抖：设置没变、又刚刚同步过，就不重复清写日历。
        // （onCreate 与 onResume 会连着各调一次）
        long nowAt = System.currentTimeMillis();
        String sig = signature(cfg);
        if (sig.equals(sLastSig) && nowAt - sLastSyncAt < MIN_INTERVAL) {
            Log.i(TAG, "日历同步: 设置没变且刚同步过，跳过");
            return lastCount(c);
        }
        sLastSig = sig;
        sLastSyncAt = nowAt;
        clear(c);
        if (!cfg.reminderOn) {
            Log.i(TAG, "日历同步: 提醒已关闭，只做了清理");
            return 0;
        }
        long calId = findWritableCalendar(c);
        if (calId < 0) {
            return -1;
        }

        TimeTable tt = new TimeTable(cfg);
        Store store = Store.get(c);
        Calendar now = Calendar.getInstance();
        String tz = TimeZone.getDefault().getID();
        List<Long> ids = new ArrayList<>();
        List<Long> reminderIds = new ArrayList<>();

        for (int d = 0; d < SYNC_DAYS; d++) {
            Calendar day = (Calendar) now.clone();
            day.add(Calendar.DAY_OF_MONTH, d);
            int week = Weeks.weekOf(cfg, day);
            List<Course> list = store.ofDay(Weeks.dowOf(day), week);
            for (Course co : list) {
                long start = at(day, tt.start(co.startPeriod));
                long end = at(day, tt.end(co.endPeriod));
                if (end <= now.getTimeInMillis()) {
                    continue;   // 已经上完的不写
                }
                long[] r = writeOne(c, calId, co, tt, start, end, tz, cfg.reminderMinutes);
                if (r != null && r[0] > 0) {
                    ids.add(r[0]);
                    reminderIds.add(r[1]);
                }
            }
        }

        // 关键一步：对每条 Reminders 行做一次 UPDATE，
        // 触发 Provider 的 scheduleNextAlarm() 重排 EVENT_REMINDER 闹钟。
        ContentResolver cr = c.getContentResolver();
        for (Long rid : reminderIds) {
            if (rid == null || rid <= 0) {
                continue;
            }
            try {
                ContentValues u = new ContentValues();
                u.put(Reminders.MINUTES, cfg.reminderMinutes);
                cr.update(ContentUris.withAppendedId(Reminders.CONTENT_URI, rid), u, null, null);
            } catch (Exception e) {
                Log.w(TAG, "日历同步: 触发重排失败 " + e);
            }
        }

        saveIds(c, ids);
        Log.i(TAG, "日历同步: 共写入 " + ids.size() + " 条课程提醒（未来 " + SYNC_DAYS + " 天）");
        return ids.size();
    }

    /** 这个事件是不是本 App 写进去的 */
    public static boolean isOurs(Context c, long eventId) {
        if (eventId <= 0) {
            return false;
        }
        for (Long id : loadIds(c)) {
            if (id != null && id == eventId) {
                return true;
            }
        }
        return false;
    }

    /** 忽略防抖，强制同步一次（界面上点「重新同步」时用） */
    public static int syncNow(Context c) {
        sLastSyncAt = 0L;
        return sync(c);
    }

    /** 写一条课程事件，返回 {eventId, reminderId}；失败返回 null */
    private static long[] writeOne(Context c, long calId, Course co, TimeTable tt,
                                   long start, long end, String tz, int minutes) {
        ContentResolver cr = c.getContentResolver();
        try {
            ContentValues v = new ContentValues();
            v.put(Events.CALENDAR_ID, calId);
            v.put(Events.TITLE, co.name);
            // 备注写成给人看的正常文案，不再塞 #ctw# 之类的标记
            v.put(Events.DESCRIPTION, caption(co, tt));
            if (!co.room.isEmpty()) {
                v.put(Events.EVENT_LOCATION, co.room);
            }
            // 用 customAppPackage 标记「这条是本 App 写的」。
            // 这个字段日历界面不显示，所以备注里看不到任何多余字符。
            v.put("customAppPackage", c.getPackageName());
            v.put(Events.DTSTART, start);
            v.put(Events.DTEND, end);
            v.put(Events.EVENT_TIMEZONE, tz);
            v.put(Events.HAS_ALARM, 1);
            Uri uri = cr.insert(Events.CONTENT_URI, v);
            if (uri == null) {
                Log.w(TAG, "日历同步: 插入事件返回 null -> " + co.name);
                return null;
            }
            long eventId = Long.parseLong(uri.getLastPathSegment());

            ContentValues r = new ContentValues();
            r.put(Reminders.EVENT_ID, eventId);
            r.put(Reminders.MINUTES, Math.max(0, minutes));
            r.put(Reminders.METHOD, Reminders.METHOD_ALERT);
            Uri rUri = cr.insert(Reminders.CONTENT_URI, r);
            long reminderId = rUri == null ? -1 : Long.parseLong(rUri.getLastPathSegment());

            // CalendarAlerts 行：Provider 靠它决定下一次闹钟排在哪，
            // 而它并**不会**为第三方插入的事件自动生成，得我们自己补。
            try {
                ContentValues a = new ContentValues();
                a.put(CalendarAlerts.EVENT_ID, eventId);
                a.put(CalendarAlerts.ALARM_TIME, start - Math.max(0, minutes) * 60000L);
                a.put(CalendarAlerts.MINUTES, Math.max(0, minutes));
                a.put(CalendarAlerts.STATE, CalendarAlerts.STATE_SCHEDULED);
                a.put(CalendarAlerts.BEGIN, start);
                a.put(CalendarAlerts.END, end);
                cr.insert(CalendarAlerts.CONTENT_URI, a);
            } catch (Exception e) {
                Log.w(TAG, "日历同步: alert 行插入失败（不影响通知内容） " + e);
            }
            return new long[]{eventId, reminderId};
        } catch (Exception e) {
            Log.w(TAG, "日历同步: 写入失败 " + co.name + " -> " + e);
            return null;
        }
    }

    /** 删掉上一次同步写进去的事件 */
    public static synchronized void clear(Context c) {
        if (!hasPermission(c)) {
            return;
        }
        ContentResolver cr = c.getContentResolver();
        for (Long id : loadIds(c)) {
            try {
                cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null);
            } catch (Exception ignored) {
            }
        }
        saveIds(c, new ArrayList<Long>());
        // 兜底：按标记删（prefs 丢了也能清干净）
        try {
            cr.delete(Events.CONTENT_URI, Events.DESCRIPTION + " LIKE ?",
                    new String[]{MARK + "%"});
        } catch (Exception e) {
            Log.w(TAG, "日历同步: 兜底清理失败 " + e);
        }
    }

    // ---------------------------------------------------------- 工具

    private static String caption(Course co, TimeTable tt) {
        StringBuilder sb = new StringBuilder(tt.rangeOfCourse(co));
        if (!co.room.isEmpty()) {
            sb.append(" · ").append(co.room);
        }
        if (!co.teacher.isEmpty()) {
            sb.append(" · ").append(co.teacher);
        }
        return sb.toString();
    }

    private static long at(Calendar day, int minutes) {
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, (minutes / 60) % 24);
        c.set(Calendar.MINUTE, minutes % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static void saveIds(Context c, List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_IDS, sb.toString()).apply();
    }

    private static List<Long> loadIds(Context c) {
        List<Long> out = new ArrayList<>();
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_IDS, "");
        if (TextUtils.isEmpty(s)) {
            return out;
        }
        for (String p : s.split(",")) {
            try {
                out.add(Long.parseLong(p.trim()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 已写入的条数（给界面显示用） */
    public static int lastCount(Context c) {
        return loadIds(c).size();
    }

    /**
     * 写一条「2 分钟后开始、1 分钟后提醒」的日历事件，用来验证整条链路。
     * 熄屏等 1 分钟，正常的话会收到一条课程提醒通知。
     */
    public static String testEvent(Context c) {
        if (!hasPermission(c)) {
            return "没有日历权限";
        }
        long calId = findWritableCalendar(c);
        if (calId < 0) {
            return "找不到可写的日历";
        }
        try {
            Calendar now = Calendar.getInstance();
            long start = now.getTimeInMillis() + 2 * 60 * 1000L;
            long end = start + 30 * 60 * 1000L;
            Course co = new Course();
            co.name = "测试课程提醒";
            co.room = "A座302";
            long[] r = writeOne(c, calId, co, new TimeTable(Store.get(c).config),
                    start, end, TimeZone.getDefault().getID(), 1);
            if (r == null) {
                return "写入失败";
            }
            // 触发 Provider 重排闹钟
            ContentValues u = new ContentValues();
            u.put(Reminders.MINUTES, 1);
            c.getContentResolver().update(
                    ContentUris.withAppendedId(Reminders.CONTENT_URI, r[1]), u, null, null);
            return "已写入，1 分钟后提醒（可以熄屏试）";
        } catch (Exception e) {
            Log.w(TAG, "测试事件写入失败 " + e);
            return "写入失败：" + e.getMessage();
        }
    }
}
