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
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * 把课程写进系统日历，提醒交给系统日历来发。
 *
 * <p><b>为什么要这么绕：</b>ColorOS 手表版在「息屏 + 电量均衡」下会直接拒绝第三方 App
 * 自设的 wakeup 闹钟（日志 {@code forbin thirdapp(...) set wakeup alarm}），
 * 加电池白名单、换 setAlarmClock 都无效——息屏时 App 自己的闹钟根本投不出来。
 * 系统日历是系统应用，不受这条限制，所以真正会响的是它。
 *
 * <p><b>链路上两个要点：</b>
 * <ol>
 *   <li>除了事件本身，还要写一条 Reminders 行（提前 N 分钟）和一条 CalendarAlerts 行
 *       （{@code alarmTime = 开课时间 - 提前量}）——系统日历按它们决定什么时候提醒；</li>
 *   <li>写完对 Reminders 行做一次 UPDATE，把 Provider 的重排逻辑叫起来。</li>
 * </ol>
 *
 * <p>App 不需要常驻后台，也不依赖自身闹钟。
 */
public final class CalendarSync {

    private static final String TAG = "CourseTable";

    /** 旧版写进 description 的标记（保留只为清理历史遗留事件） */
    public static final String MARK = "#ctw#";

    /**
     * 事件表里标记「这条是本 App 写的」的列。
     *
     * <p>现在靠它来认领 / 清理事件，而不是靠 SharedPreferences 里记的 id 列表：
     * 重装、清除数据都会把 id 列表抹掉，而日历里的事件还在——那样每节课就会
     * 在日历里越堆越多（各自带着不同年代的提醒时间），提醒自然就乱了。
     */
    private static final String COL_CUSTOM_PKG = "customAppPackage";

    /**
     * 往前写多少天。
     * 每次打开 App 都会重新计算并覆盖这段窗口，所以只要两周内开过一次 App，
     * 提醒就不会断。窗口太长会让日历里堆太多课程事件，两周是折中值。
     */
    private static final int SYNC_DAYS = 14;

    private static final String PREFS = "ctw_calendar";
    private static final String KEY_IDS = "event_ids";
    private static final String KEY_LAST_AT = "last_at";
    private static final String KEY_LAST_SIG = "last_sig";
    private static final String KEY_VER = "app_ver";

    /** 当前安装的版本名，用来判断「是不是换了新包」 */
    private static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "-";
        }
    }

    /** 上一次同步的时间戳，用来防抖（onCreate + onResume 会连着调两次） */
    private static long sLastSyncAt = 0L;
    private static String sLastSig = null;
    private static final long MIN_INTERVAL = 15000L;

    /**
     * 跨进程的免重写窗口。
     *
     * <p>每次打开 App 都删掉再重写几十条日历事件，是要跑上百次 ContentResolver
     * 调用的，实测 1.26 秒——放在启动路径上就是开屏黑屏一两秒。
     * 而写进去的是「未来 14 天」的窗口，内容没变就没必要重写，
     * 所以数据没变时只按这个间隔兜底重刷一次。
     */
    private static final long PERSIST_INTERVAL = 12 * 60 * 60 * 1000L;

    /** 影响日历内容的设置指纹，用来判断「设置有没有变」 */
    private static String signature(AppConfig cfg) {
        return cfg.reminderOn + "|" + cfg.reminderMinutes + "|" + cfg.startDate + "|" + cfg.totalWeeks
                + "|" + cfg.weekOffset
                + "|" + cfg.periodCount[0] + "," + cfg.periodCount[1] + "," + cfg.periodCount[2]
                + "|" + cfg.sectionStart[0] + "," + cfg.sectionStart[1] + "," + cfg.sectionStart[2]
                + "|" + cfg.periodMinutes + "|" + cfg.breakMinutes + "|" + cfg.bigBreakMinutes;
    }

    /**
     * 课表本身的指纹。
     * 设置没变但手机推了一份新课表过来时，日历也必须跟着重写，所以要把课程也算进来。
     */
    private static String dataSig(Context c) {
        List<Course> list = Store.get(c).snapshot();
        StringBuilder sb = new StringBuilder();
        for (Course co : list) {
            sb.append(co.dayOfWeek).append(',').append(co.startPeriod).append(',')
                    .append(co.endPeriod).append(',').append(Arrays.hashCode(co.weeks)).append(',')
                    .append(co.name).append('|');
        }
        return list.size() + "#" + Integer.toHexString(sb.toString().hashCode());
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
        // 防抖，两层：
        //   1. 内存里 15 秒 —— onCreate 与 onResume 会连着各调一次；
        //   2. 落盘 12 小时 —— 冷启动时新进程的内存计数是空的，光靠第 1 层
        //      会导致每次打开 App 都把日历整个重写一遍（实测 1.26 秒）。
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sLastSyncAt == 0L) {
            sLastSyncAt = sp.getLong(KEY_LAST_AT, 0L);
            sLastSig = sp.getString(KEY_LAST_SIG, null);
        }
        long nowAt = System.currentTimeMillis();
        String sig = signature(cfg) + "||" + dataSig(c);
        // 版本一变就无条件重写一次日历：重装 / 升级会丢掉本地那份事件 id 记录，
        // 旧事件只能靠这次重写清掉；否则它们会一直躺在日历里，各带一个
        // 过期年代的提醒时间。正常打开 App 时版本没变，还是走下面的防抖。
        String ver = appVersion(c);
        String lastVer = sp.getString(KEY_VER, "");
        if (!ver.equals(lastVer)) {
            Log.i(TAG, "日历同步: 版本 " + (lastVer.isEmpty() ? "未知" : lastVer) + " -> " + ver + "，强制重写");
            sLastSig = null;
            sLastSyncAt = 0L;
        }
        sp.edit().putString(KEY_VER, ver).apply();
        if (sig.equals(sLastSig) && lastCount(c) > 0) {
            long gap = sLastSyncAt == 0L ? Long.MAX_VALUE : nowAt - sLastSyncAt;
            if (gap < MIN_INTERVAL) {
                Log.i(TAG, "日历同步: 设置没变且刚同步过，跳过");
                return lastCount(c);
            }
            if (gap < PERSIST_INTERVAL) {
                Log.i(TAG, "日历同步: 数据没变，跳过重写（" + (gap / 60000) + " 分钟前已同步）");
                return lastCount(c);
            }
        }
        sLastSig = sig;
        sLastSyncAt = nowAt;
        sp.edit().putString(KEY_LAST_SIG, sig).putLong(KEY_LAST_AT, nowAt).apply();
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
        // 后台线程读，用快照——课表随时可能被手机推过来整体替换
        List<Course> snapshot = store.snapshot();
        Calendar now = Calendar.getInstance();
        String tz = TimeZone.getDefault().getID();
        List<Long> ids = new ArrayList<>();
        List<Long> reminderIds = new ArrayList<>();

        for (int d = 0; d < SYNC_DAYS; d++) {
            Calendar day = (Calendar) now.clone();
            day.add(Calendar.DAY_OF_MONTH, d);
            int week = Weeks.weekOf(cfg, day);
            List<Course> list = store.ofDayOf(snapshot, Weeks.dowOf(day), week);
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
        // 记录里没有就回查标记：重装 / 清除数据后记录会丢，
        // 但事件还在日历里，不认领的话这些老事件就再也不会提醒（也清不掉）。
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(
                    ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                    new String[]{COL_CUSTOM_PKG}, null, null, null);
            return cur != null && cur.moveToFirst()
                    && c.getPackageName().equals(cur.getString(0));
        } catch (Exception e) {
            return false;
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
    }

    /** 忽略防抖，强制同步一次（界面上点「重新同步」时用） */
    public static int syncNow(Context c) {
        // -1 而不是 0：0 是「新进程、还没读过落盘记录」的标记，会被 sync() 重新加载覆盖掉
        sLastSyncAt = -1L;
        sLastSig = null;
        return sync(c);
    }

    /** 写一条课程事件，返回 {eventId, reminderId}；失败返回 null */
    private static long[] writeOne(Context c, long calId, Course co, TimeTable tt,
                                   long start, long end, String tz, int minutes) {
        return writeRaw(c, calId, co.name, caption(co, tt), co.room, start, end, tz, minutes);
    }

    /**
     * 写「事件 + 提醒行 + alert 行」。
     *
     * <p>每条都带 {@code customAppPackage} 标记——清理旧事件、判断某条提醒是不是
     * 我们的，都靠这个标记，不再依赖本地记录（记录会因重装 / 清数据丢失）。
     */
    private static long[] writeRaw(Context c, long calId, String title, String desc,
                                   String room, long start, long end, String tz, int minutes) {
        ContentResolver cr = c.getContentResolver();
        try {
            ContentValues v = new ContentValues();
            v.put(Events.CALENDAR_ID, calId);
            v.put(Events.TITLE, title);
            // 备注写成给人看的正常文案，不再塞 #ctw# 之类的标记
            v.put(Events.DESCRIPTION, desc);
            if (room != null && !room.isEmpty()) {
                v.put(Events.EVENT_LOCATION, room);
            }
            // 用 customAppPackage 标记「这条是本 App 写的」。
            // 这个字段日历界面不显示，所以备注里看不到任何多余字符。
            v.put(COL_CUSTOM_PKG, c.getPackageName());
            v.put(Events.DTSTART, start);
            v.put(Events.DTEND, end);
            v.put(Events.EVENT_TIMEZONE, tz);
            v.put(Events.HAS_ALARM, 1);
            Uri uri = cr.insert(Events.CONTENT_URI, v);
            if (uri == null) {
                Log.w(TAG, "日历同步: 插入事件返回 null -> " + title);
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
            Log.w(TAG, "日历同步: 写入失败 " + title + " -> " + e);
            return null;
        }
    }

    /** 删掉本 App 写进日历的事件——不只是上一次同步的那些 */
    public static synchronized void clear(Context c) {
        if (!hasPermission(c)) {
            return;
        }
        ContentResolver cr = c.getContentResolver();
        // 1) 按记下的 id 删（最直接）
        for (Long id : loadIds(c)) {
            try {
                cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null);
            } catch (Exception ignored) {
            }
        }
        saveIds(c, new ArrayList<Long>());
        // 2) 按标记删。这一步是必需的，不是兜底：重装 / 清除数据会丢掉上面那份
        //    id 记录，但事件还留在日历里。只靠 id 就会越积越多——同一节课在日历
        //    里出现两三条，每条带着不同年代的提醒时间，用户看到的提醒自然不对。
        try {
            int n = cr.delete(Events.CONTENT_URI, COL_CUSTOM_PKG + "=?",
                    new String[]{c.getPackageName()});
            if (n > 0) {
                Log.i(TAG, "日历同步: 按标记清掉 " + n + " 条历史事件");
            }
        } catch (Exception e) {
            Log.w(TAG, "日历同步: 按标记清理失败 " + e);
        }
        // 3) 更早的版本把 #ctw# 写在备注里，顺手一起清
        try {
            cr.delete(Events.CONTENT_URI, Events.DESCRIPTION + " LIKE ?",
                    new String[]{MARK + "%"});
        } catch (Exception ignored) {
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
            long start = System.currentTimeMillis() + 2 * 60 * 1000L;
            long end = start + 30 * 60 * 1000L;
            long[] r = writeRaw(c, calId, "测试课程提醒", "1 分钟后开始 · 提醒链路自检",
                    "A座302", start, end, TimeZone.getDefault().getID(), 1);
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

    /**
     * 按当前设置的提前量写一条自检事件：从现在起「提前量 + 1」分钟后开始，
     * 于是提醒应该在 <b>1 分钟后</b> 到，通知标题会写明「还有 N 分钟上课」。
     *
     * <p>用它来判断「提前多少分钟」到底有没有生效——如果提醒是在
     * 「提前量 + 1」分钟后（也就是开课时）才到，那就是提前量没起作用。
     * 这条事件带 App 标记，下次同步会被自动清掉。
     */
    public static String testLeadEvent(Context c) {
        if (!hasPermission(c)) {
            return "没有日历权限";
        }
        long calId = findWritableCalendar(c);
        if (calId < 0) {
            return "找不到可写的日历";
        }
        try {
            AppConfig cfg = Store.get(c).config;
            int lead = Math.max(1, cfg.reminderMinutes);
            long start = System.currentTimeMillis() + (lead + 1) * 60000L;
            long end = start + 45 * 60000L;
            long[] r = writeRaw(c, calId, "提醒链路自检",
                    "开课时间就是现在起的 " + (lead + 1) + " 分钟后 · 提前 " + lead + " 分钟提醒",
                    null, start, end, TimeZone.getDefault().getID(), lead);
            if (r == null) {
                return "写入失败";
            }
            ContentValues u = new ContentValues();
            u.put(Reminders.MINUTES, lead);
            c.getContentResolver().update(
                    ContentUris.withAppendedId(Reminders.CONTENT_URI, r[1]), u, null, null);
            Log.i(TAG, "自检事件已写入 event=" + r[0] + "，提醒应在 1 分钟后到达");
            return "1 分钟后应收到「还有 " + lead + " 分钟上课」";
        } catch (Exception e) {
            Log.w(TAG, "自检事件写入失败 " + e);
            return "写入失败：" + e.getMessage();
        }
    }
}
