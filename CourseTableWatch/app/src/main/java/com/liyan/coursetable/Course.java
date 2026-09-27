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

import java.util.Locale;

/**
 * 一门课（一个「上课时间块」）。
 *
 * 注意：课程本身不存开始/结束时间，时间由「第几节」+ {@link TimeTable} 推算，
 * 这样改一次时间设置就能影响整张课表。
 */
public class Course {

    public String name = "";
    public String teacher = "";
    public String room = "";
    public String className = "";

    /** 1 = 周一 ... 7 = 周日 */
    public int dayOfWeek = 1;

    /** 起始节次（1 起） */
    public int startPeriod = 1;
    /** 结束节次（1 起），与 startPeriod 相同表示单节 */
    public int endPeriod = 1;

    /** 有课的周次；空数组表示每周都有 */
    public int[] weeks = new int[0];

    public Course() {
    }

    public boolean hasWeek(int week) {
        if (week <= 0) {
            return true;   // 周次未知，全部显示
        }
        if (weeks == null || weeks.length == 0) {
            return true;
        }
        for (int w : weeks) {
            if (w == week) {
                return true;
            }
        }
        return false;
    }

    /** "第3节" / "第1-2节" */
    public String periodLabel() {
        if (endPeriod > startPeriod) {
            return "第" + startPeriod + "-" + endPeriod + "节";
        }
        return "第" + startPeriod + "节";
    }

    /** "1-2" 这种紧凑写法，用于卡片上的角标 */
    public String periodShort() {
        if (endPeriod > startPeriod) {
            return startPeriod + "-" + endPeriod;
        }
        return String.valueOf(startPeriod);
    }

    /** 副标题：教室 · 老师 · 班级（自动跳过空项） */
    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        append(sb, room);
        append(sb, teacher);
        append(sb, className);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.trim().isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("  ·  ");
        }
        sb.append(s.trim());
    }

    public String weeksLabel() {
        if (weeks == null || weeks.length == 0) {
            return "每周";
        }
        if (weeks.length == 1) {
            return "第" + weeks[0] + "周";
        }
        StringBuilder sb = new StringBuilder();
        int runStart = weeks[0];
        int prev = weeks[0];
        for (int i = 1; i <= weeks.length; i++) {
            int cur = (i < weeks.length) ? weeks[i] : Integer.MIN_VALUE;
            if (cur != prev + 1) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(runStart == prev ? String.valueOf(runStart) : runStart + "-" + prev);
                runStart = cur;
            }
            prev = cur;
        }
        return "第" + sb + "周";
    }

    @Override
    public String toString() {
        return String.format(Locale.CHINA, "周%d %s %s %s", dayOfWeek, periodLabel(), name, room);
    }
}
