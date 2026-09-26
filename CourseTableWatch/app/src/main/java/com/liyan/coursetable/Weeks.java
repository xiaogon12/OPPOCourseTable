package com.liyan.coursetable;

import java.util.Calendar;
import java.util.Locale;

/** 周次 / 日期相关的小工具 */
public final class Weeks {

    public static final String[] DOW_SHORT = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    public static final String[] DOW_LONG = {"", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    private Weeks() {
    }

    /** 1 = 周一 ... 7 = 周日 */
    public static int dowOf(Calendar c) {
        int d = c.get(Calendar.DAY_OF_WEEK);
        return (d == Calendar.SUNDAY) ? 7 : d - 1;
    }

    /** 把时间归零到当天 0 点 */
    public static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    /** 解析 "yyyy-MM-dd"，失败返回 null */
    public static Calendar parseDate(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String t = s.trim().replace('/', '-');
        if (t.length() < 8) {
            return null;
        }
        try {
            String[] p = t.split("-");
            int y = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            int d = Integer.parseInt(p[2].trim());
            Calendar c = Calendar.getInstance();
            c.set(y, m - 1, d);
            zeroTime(c);
            c.getTimeInMillis();   // 归一化
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    public static String formatDate(Calendar c) {
        return String.format(Locale.CHINA, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 第一周的周一（配置里 startDate 所在周的周一）；未设置返回 null */
    public static Calendar startMonday(AppConfig cfg) {
        Calendar s = parseDate(cfg.startDate);
        if (s == null) {
            return null;
        }
        int dow = dowOf(s);
        s.add(Calendar.DAY_OF_MONTH, -(dow - 1));   // 回退到周一
        return s;
    }

    /** 当前是第几周；无法计算返回 -1（表示「不按周过滤」） */
    public static int currentWeek(AppConfig cfg) {
        return weekOf(cfg, Calendar.getInstance());
    }

    /** 指定日期是第几周（含用户的手动周数修正 weekOffset）；无法计算返回 -1 */
    public static int weekOf(AppConfig cfg, Calendar date) {
        Calendar s = startMonday(cfg);
        if (s == null) {
            return -1;
        }
        Calendar d = (Calendar) date.clone();
        zeroTime(d);
        long days = Math.round((d.getTimeInMillis() - s.getTimeInMillis()) / 86400000.0);
        if (days < 0) {
            return 1;
        }
        return Math.max(1, (int) (days / 7) + 1 + cfg.weekOffset);
    }

    /** "9月24日 星期四" */
    public static String dateTitle(Calendar c) {
        return String.format(Locale.CHINA, "%d月%d日 %s",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), DOW_LONG[dowOf(c)]);
    }
}
