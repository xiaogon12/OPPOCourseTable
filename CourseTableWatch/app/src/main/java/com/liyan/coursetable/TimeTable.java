package com.liyan.coursetable;

import java.util.Locale;

/**
 * 由设置推算出来的「整张作息时间表」。
 *
 * 推算规则（与需求一致）：
 *   1. 段内从「该段第一节开始时间」起算；
 *   2. 每节课时长 = periodMinutes（或每节单独设置）；
 *   3. 节与节之间插入课间休息；
 *   4. 段内每上完 2 节（第 2、4、6 节后面）换成大课间休息。
 *
 * 例：上午 4 节、8:00 开始、45 分钟一节、课间 10、大课间 20
 *     1) 08:00-08:45  2) 08:55-09:40  [大课间 20]  3) 10:00-10:45  4) 10:55-11:40
 */
public class TimeTable {

    private final int[] starts;
    private final int[] ends;

    public TimeTable(AppConfig cfg) {
        int total = Math.max(1, cfg.totalPeriods());
        starts = new int[total];
        ends = new int[total];

        int period = 1;
        for (int s = 0; s < 3; s++) {
            int count = cfg.periodCount[s];
            int cursor = cfg.sectionStart[s];
            for (int k = 0; k < count && period <= total; k++) {
                int dur = cfg.minutesOfPeriod(period);
                starts[period - 1] = cursor;
                ends[period - 1] = cursor + dur;
                // 段内节间休息：每 2 节后面是大课间
                int gap = ((k + 1) % 2 == 0) ? cfg.bigBreakMinutes : cfg.breakMinutes;
                cursor = ends[period - 1] + gap;
                period++;
            }
            // 兜底：该段没有配置节数时，让后续节的指针不越界
            if (count == 0) {
                continue;
            }
        }
        // 任何未填充的节次（理论上不会发生）按上一节兜底
        for (int i = 0; i < total; i++) {
            if (starts[i] == 0 && ends[i] == 0) {
                if (i > 0) {
                    starts[i] = ends[i - 1] + cfg.breakMinutes;
                } else {
                    starts[i] = cfg.sectionStart[0];
                }
                ends[i] = starts[i] + cfg.minutesOfPeriod(i + 1);
            }
        }
    }

    public int count() {
        return starts.length;
    }

    /** 第 period 节（1 起）的开始分钟数，越界自动夹到边界 */
    public int start(int period) {
        int i = clampIndex(period);
        return starts[i];
    }

    public int end(int period) {
        int i = clampIndex(period);
        return ends[i];
    }

    private int clampIndex(int period) {
        int i = period - 1;
        if (i < 0) {
            i = 0;
        }
        if (i >= starts.length) {
            i = starts.length - 1;
        }
        return i;
    }

    /** "10:10-11:40" */
    public String range(int startPeriod, int endPeriod) {
        return hhmm(start(startPeriod)) + "-" + hhmm(end(endPeriod));
    }

    public String rangeOfCourse(Course c) {
        return range(c.startPeriod, c.endPeriod);
    }

    public static String hhmm(int minutes) {
        return String.format(Locale.CHINA, "%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }
}
