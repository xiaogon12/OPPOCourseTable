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
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * App 的全部设置项。
 *
 * 时间模型（三段式）：
 *   section 0 = 上午，1 = 下午，2 = 晚上
 *   每段的「第一节开始时间」分别配置，段内节次由「每节时长 + 课间 + 大课间」推算。
 *   「两节课是一大节课」：段内每上完 2 节就是一个大课间。
 */
public class AppConfig {

    public static final String[] SECTION_NAMES = {"上午", "下午", "晚上"};
    /** 各段第一节的「节次编号」由节数推算，这里只是默认值 */
    public static final int[] DEFAULT_START = {8 * 60, 14 * 60, 19 * 60};

    private static final String PREF = "coursetable_cfg";

    // ---------------------------------------------------------- 字段

    /** 课表开始的第一天（第一周周一），yyyy-MM-dd；空表示未设置 */
    public String startDate = "";
    /** 本学期总周数 */
    public int totalWeeks = 18;
    /** 周数手动修正（在自动算出来的周数上加减），默认 0 */
    public int weekOffset = 0;
    /** 上午 / 下午 / 晚上 的节数 */
    public int[] periodCount = {4, 4, 0};
    /** 三段各自第一节的开始时间（当天 0 点起的分钟数） */
    public int[] sectionStart = {DEFAULT_START[0], DEFAULT_START[1], DEFAULT_START[2]};

    /** 是否每节课时长相同 */
    public boolean sameLength = true;
    /** 每节课时长（分钟） */
    public int periodMinutes = 45;
    /** 单节时长各自设置时使用（长度 = 总节数，按节次顺序） */
    public int[] perPeriodMinutes = new int[0];

    /** 课间休息（分钟） */
    public int breakMinutes = 10;
    /** 大课间休息（分钟），默认 20 */
    public int bigBreakMinutes = 20;

    /** 课前提醒开关 */
    public boolean reminderOn = true;
    /** 提前多少分钟提醒 */
    public int reminderMinutes = 20;

    /** UI 风格 id */
    public String themeId = "ink";

    /** 课表名字（导入 JSON 里的 name） */
    public String tableName = "";

    // ---------------------------------------------------------- 计算

    /**
     * 把「课表时间设置」整体恢复成默认值。
     *
     * 导入 JSON 时先调这个、再用 JSON 里明确给出的字段覆盖。
     * 因为 JSON 里这些字段都是可选的，如果只「有就覆盖、没有就跳过」，
     * 上一次导入残留的值（典型的是把大课间改成 120）会悄悄生效，
     * 算出 11:40-13:20 这种离谱的节次时间。
     */
    public void resetTimeSettings() {
        sameLength = true;
        periodMinutes = 45;
        breakMinutes = 10;
        bigBreakMinutes = 20;
        periodCount = new int[]{4, 4, 0};
        sectionStart = new int[]{DEFAULT_START[0], DEFAULT_START[1], DEFAULT_START[2]};
        perPeriodMinutes = new int[0];
        ensurePerPeriod();
    }

    /** 总节数 */
    public int totalPeriods() {
        return periodCount[0] + periodCount[1] + periodCount[2];
    }

    /** 某个 section 的第一节是第几节（1 起） */
    public int sectionFirstPeriod(int section) {
        int n = 1;
        for (int i = 0; i < section; i++) {
            n += periodCount[i];
        }
        return n;
    }

    /** 第 period 节（1 起）属于哪个 section，越界返回 2 */
    public int sectionOfPeriod(int period) {
        int acc = 0;
        for (int s = 0; s < 3; s++) {
            acc += periodCount[s];
            if (period <= acc) {
                return s;
            }
        }
        return 2;
    }

    /** 第 period 节（1 起）在该 section 内是第几节（1 起） */
    public int indexInSection(int period) {
        int s = sectionOfPeriod(period);
        return period - sectionFirstPeriod(s) + 1;
    }

    public int minutesOfPeriod(int period) {
        if (sameLength) {
            return periodMinutes;
        }
        int idx = period - 1;
        if (perPeriodMinutes != null && idx >= 0 && idx < perPeriodMinutes.length) {
            return perPeriodMinutes[idx];
        }
        return periodMinutes;
    }

    public void ensurePerPeriod() {
        int total = totalPeriods();
        if (perPeriodMinutes == null || perPeriodMinutes.length != total) {
            int[] old = perPeriodMinutes;
            int[] next = new int[total];
            for (int i = 0; i < total; i++) {
                if (old != null && i < old.length && old[i] > 0) {
                    next[i] = old[i];
                } else {
                    next[i] = periodMinutes;
                }
            }
            perPeriodMinutes = next;
        }
    }

    // ---------------------------------------------------------- 存取

    public static AppConfig load(Context ctx) {
        AppConfig c = new AppConfig();
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        c.startDate = sp.getString("startDate", "");
        c.totalWeeks = sp.getInt("totalWeeks", 18);
        c.weekOffset = sp.getInt("weekOffset", 0);
        c.periodCount[0] = sp.getInt("pc0", 4);
        c.periodCount[1] = sp.getInt("pc1", 4);
        c.periodCount[2] = sp.getInt("pc2", 0);
        c.sectionStart[0] = sp.getInt("ss0", DEFAULT_START[0]);
        c.sectionStart[1] = sp.getInt("ss1", DEFAULT_START[1]);
        c.sectionStart[2] = sp.getInt("ss2", DEFAULT_START[2]);
        c.sameLength = sp.getBoolean("sameLength", true);
        c.periodMinutes = sp.getInt("periodMinutes", 45);
        c.breakMinutes = sp.getInt("breakMinutes", 10);
        c.bigBreakMinutes = sp.getInt("bigBreakMinutes", 20);
        //  sanity 钳制：历史版本曾把「大课间」错算成 120（跨午休的间隔被当成了大课间），
        //  这种离谱值会在 prefs 里一直残留，读到就拉回默认。
        if (c.periodMinutes < 15 || c.periodMinutes > 120) {
            c.periodMinutes = 45;
        }
        if (c.breakMinutes < 0 || c.breakMinutes > 45) {
            c.breakMinutes = 10;
        }
        if (c.bigBreakMinutes < 0 || c.bigBreakMinutes > 60) {
            c.bigBreakMinutes = 20;
        }
        c.reminderOn = sp.getBoolean("reminderOn", true);
        c.reminderMinutes = sp.getInt("reminderMinutes", 20);
        c.themeId = sp.getString("themeId", "ink");
        c.tableName = sp.getString("tableName", "");

        String ppm = sp.getString("perPeriodMinutes", "");
        if (!TextUtils.isEmpty(ppm)) {
            String[] parts = ppm.split(",");
            c.perPeriodMinutes = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    c.perPeriodMinutes[i] = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException e) {
                    c.perPeriodMinutes[i] = c.periodMinutes;
                }
            }
        }
        c.ensurePerPeriod();
        return c;
    }

    public void save(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        e.putString("startDate", nz(startDate));
        e.putInt("totalWeeks", totalWeeks);
        e.putInt("weekOffset", weekOffset);
        e.putInt("pc0", periodCount[0]);
        e.putInt("pc1", periodCount[1]);
        e.putInt("pc2", periodCount[2]);
        e.putInt("ss0", sectionStart[0]);
        e.putInt("ss1", sectionStart[1]);
        e.putInt("ss2", sectionStart[2]);
        e.putBoolean("sameLength", sameLength);
        e.putInt("periodMinutes", periodMinutes);
        e.putInt("breakMinutes", breakMinutes);
        e.putInt("bigBreakMinutes", bigBreakMinutes);
        e.putBoolean("reminderOn", reminderOn);
        e.putInt("reminderMinutes", reminderMinutes);
        e.putString("themeId", themeId);
        e.putString("tableName", nz(tableName));
        StringBuilder sb = new StringBuilder();
        ensurePerPeriod();
        for (int i = 0; i < perPeriodMinutes.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(perPeriodMinutes[i]);
        }
        e.putString("perPeriodMinutes", sb.toString());
        e.apply();
    }

    public AppConfig copy() {
        AppConfig c = new AppConfig();
        c.startDate = startDate;
        c.totalWeeks = totalWeeks;
        c.weekOffset = weekOffset;
        c.periodCount = periodCount.clone();
        c.sectionStart = sectionStart.clone();
        c.sameLength = sameLength;
        c.periodMinutes = periodMinutes;
        c.breakMinutes = breakMinutes;
        c.bigBreakMinutes = bigBreakMinutes;
        c.reminderOn = reminderOn;
        c.reminderMinutes = reminderMinutes;
        c.themeId = themeId;
        c.tableName = tableName;
        ensurePerPeriod();
        c.perPeriodMinutes = perPeriodMinutes.clone();
        return c;
    }

    public void applyFrom(AppConfig o) {
        startDate = o.startDate;
        totalWeeks = o.totalWeeks;
        weekOffset = o.weekOffset;
        periodCount = o.periodCount.clone();
        sectionStart = o.sectionStart.clone();
        sameLength = o.sameLength;
        periodMinutes = o.periodMinutes;
        breakMinutes = o.breakMinutes;
        bigBreakMinutes = o.bigBreakMinutes;
        // 之前漏了这几个字段：用 copy() 编辑再 applyFrom() 回来时，
        // 提醒开关 / 提前分钟 / 主题会被悄悄丢掉。
        reminderOn = o.reminderOn;
        reminderMinutes = o.reminderMinutes;
        themeId = o.themeId;
        tableName = o.tableName;
        perPeriodMinutes = o.perPeriodMinutes == null ? new int[0] : o.perPeriodMinutes.clone();
        ensurePerPeriod();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 分钟数 -> "HH:mm" */
    public static String hhmm(int minutes) {
        int h = (minutes / 60) % 24;
        int m = minutes % 60;
        return String.format(java.util.Locale.CHINA, "%02d:%02d", h, m);
    }

    /** "HH:mm" -> 分钟数，失败返回 -1 */
    public static int parseHhmm(String s) {
        if (s == null) {
            return -1;
        }
        String[] p = s.trim().split(":");
        if (p.length < 2) {
            return -1;
        }
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
