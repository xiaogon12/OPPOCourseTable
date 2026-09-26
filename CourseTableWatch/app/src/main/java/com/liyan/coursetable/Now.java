package com.liyan.coursetable;

import java.util.Calendar;

/**
 * 全 App 唯一的「现在」。
 *
 * <p>课表页的焦点逻辑（正在上 / 马上要上 / 已上完）完全由当前时刻决定，
 * 但一个功能不可能正好在有人看的时候处于上课时段 —— 手表又没 root，
 * {@code adb shell date -s} 改不了系统时间。
 *
 * <p>所以留一个调试口子：用 {@code am start} 带一个分钟偏移把「现在」挪走，
 * 就能在任何点钟验证课表页。正式启动（从表盘/菜单进入）不带参数，偏移为 0，
 * 行为与真实时间完全一致。
 *
 * <pre>
 *   adb shell am force-stop com.liyan.coursetable
 *   adb shell am start -n com.liyan.coursetable/.MainActivity --ei now_offset 1234
 * </pre>
 */
public final class Now {

    /** 调试用的时间偏移（分钟），仅由 {@link MainActivity} 在读到 intent 参数时设置 */
    private static int offsetMinutes = 0;

    private Now() {
    }

    public static void setOffsetMinutes(int m) {
        offsetMinutes = m;
    }

    public static int offsetMinutes() {
        return offsetMinutes;
    }

    /** 当前的「现在」 */
    public static Calendar get() {
        Calendar c = Calendar.getInstance();
        if (offsetMinutes != 0) {
            c.add(Calendar.MINUTE, offsetMinutes);
        }
        return c;
    }

    public static long millis() {
        return get().getTimeInMillis();
    }
}
