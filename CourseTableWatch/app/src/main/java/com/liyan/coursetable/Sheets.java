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
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;

/** 全部弹层内容 */
public final class Sheets {

    public interface Setter {
        void set(int value);
    }

    private Sheets() {
    }

    // ---------------------------------------------------------- 通用

    /** 「从手机接收」弹层的 tag。MainActivity 靠它决定等待期间保持屏幕常亮 */
    public static final String TAG_RECEIVE = "receive";

    /** 全屏二维码浮层的 tag。MainActivity 靠它把屏幕调到最亮 */
    public static final String TAG_QR = "qr";

    private static LinearLayout body(Context c) {
        return body(c, false);
    }

    /**
     * @param stopBtOnLeave true 表示这块内容一离开屏幕（点「关闭」/ 返回键 /
     *                      被别的弹层顶掉）就停掉正在等待的蓝牙监听，
     *                      否则它会一直占着 SDP 通道直到 90 秒超时。
     */
    private static LinearLayout body(Context c, boolean stopBtOnLeave) {
        LinearLayout l;
        if (stopBtOnLeave) {
            l = new StopBtOnLeave(c);
        } else {
            l = Ui.column(c);
        }
        l.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        l.setPadding(Ui.dp(c, 22), 0, Ui.dp(c, 22), Ui.dp(c, 6));
        return l;
    }

    /** 离开屏幕就停掉蓝牙监听的容器 */
    private static final class StopBtOnLeave extends LinearLayout {
        StopBtOnLeave(Context c) {
            super(c);
            setOrientation(VERTICAL);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            BtReceive.stopCurrent();
        }
    }

    private static Runnable apply(final Host h, final Setter s, final int value, final Runnable rebuild) {
        return new Runnable() {
            @Override
            public void run() {
                s.set(value);
                h.configChanged(false);
                if (rebuild != null) {
                    rebuild.run();
                }
            }
        };
    }

    private static View info(Context c, Palette p, String text) {
        TextView t = Ui.tv(c, text, 9.5f, p.textFaint, false);
        t.setPadding(Ui.dp(c, 4), Ui.dp(c, 5), Ui.dp(c, 4), 0);
        t.setLineSpacing(0, 1.2f);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private static View dots(Context c, Palette p) {
        LinearLayout r = Ui.row(c);
        int[] colors = {p.accent, p.card, p.bg};
        for (int col : colors) {
            View v = new View(c);
            v.setBackground(Ui.bg(c, col, 10, 1, Palette.a(p.textFaint, 0.35f)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 11), Ui.dp(c, 11));
            lp.leftMargin = Ui.dp(c, 3);
            r.addView(v, lp);
        }
        return r;
    }

    // ---------------------------------------------------------- 从手机接收（蓝牙）

    /**
     * 蓝牙直连：手表当服务端等手机把课表推过来。
     *
     * 手机和手表本来就是经典蓝牙配对的，所以这里不需要 Wi-Fi、不需要热点、
     * 不需要 IP、也不需要配对码 —— 点一下等着就行。
     *
     * 注意：手表上原来那条「把 course.json 拷进 /sdcard 再导入」的老路已经删掉了，
     * 现在课表只有一个来源：手机端 App 推过来。
     */
    public static void receiveSheet(final Host h) {
        final Context c = h.ctx();
        final Palette p = h.pal();
        LinearLayout b = body(c);

        b.addView(Widgets.sectionLabel(c, p, "① 手机端 App"));
        b.addView(info(c, p, "课表由手机端 App 推过来。还没有的话，"
                + "用手机扫下面这个二维码下载（开源免费，无需账号）。"));
        b.addView(Ui.spacer(c, 6));
        b.addView(Widgets.button(c, p, "显示下载二维码（全屏）", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                h.showOverlay(TAG_QR, qrOverlay(h, "扫码下载手机端 App",
                        repoUrl(c) + "\n轻触屏幕返回", R.drawable.qr_download));
            }
        }));
        b.addView(Ui.spacer(c, 4));
        b.addView(info(c, p, "嫌扫码麻烦的话，手机浏览器直接打开：\n" + repoUrl(c)));

        b.addView(Widgets.sectionLabel(c, p, "② 手机上推送"));
        b.addView(info(c, p, "手机打开「课程表」→「同步」→ 点「推送到手表」。"
                + "两端本来就是蓝牙配对的，不用开 Wi-Fi，也不用输任何码。"));

        b.addView(Widgets.sectionLabel(c, p, "③ 手表上等待"));
        if (BtReceive.bondedCount() == 0) {
            b.addView(info(c, p, "这台手表还没有任何已配对的设备。\n"
                    + "先到系统「设置 → 蓝牙」里和手机配对，再回来点下面的按钮。"));
        } else {
            b.addView(info(c, p, "点下面的按钮后会停在等待界面（最多 90 秒），"
                    + "这期间屏幕保持常亮，别退出。"));
            b.addView(Ui.spacer(c, 6));
            b.addView(Widgets.button(c, p, "开始等待手机推送", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startWaiting(h);
                }
            }));
        }

        h.showSheet(TAG_RECEIVE, "从手机接收", b);
    }

    /** 手机端 App 的下载地址（单一来源在 tools/gen_qr.py，见那边的 DOWNLOADS_URL） */
    private static String repoUrl(Context c) {
        String s = c.getString(R.string.downloads_url);
        return s == null || s.trim().isEmpty() ? "https://github.com/xiaogon12" : s.trim();
    }

    /**
     * 调试用：跳过「开始等待手机推送」那一下，直接进等待状态。
     * 配合 `am start ... --ez receive true`（手表充电时触摸被系统悬浮窗吃掉，只能这样触发）。
     */
    public static void receiveNow(final Host h) {
        startWaiting(h);
    }

    private static void startWaiting(final Host h) {
        final Context c = h.ctx();
        h.showSheet(TAG_RECEIVE, "从手机接收", waitingBody(h, "正在等待手机推送…"));

        String err = BtReceive.start(new BtReceive.Applier() {
            @Override
            public int apply(String json) throws Exception {
                // persist = true：手机推过来的设置（开学日期 / 周数 / 作息 / 主题）也一起生效
                return h.store().applyJson(json, c, true).count;
            }
        }, new BtReceive.Callback() {
            @Override
            public void onDone(int courses, long bytes) {
                Reminder.schedule(c);
                // 手机可能把主题 / 周数也改了，整体重建一次（重建会关掉所有弹层），
                // 随后再把结果弹层推回去 —— 和「导入课程表」同一条路子。
                h.configChanged(true);
                h.toast(courses > 0 ? "已接收 " + courses + " 门课" : "已接收空课表");
                h.showSheet(TAG_RECEIVE, "从手机接收", resultBody(h, true,
                        courses > 0
                                ? "✔ 已从手机接收 " + courses + " 门课 · " + size(bytes)
                                : "✔ 手机推来的是空课表，手表上的课程已清空"));
            }

            @Override
            public void onError(String message) {
                h.showSheet(TAG_RECEIVE, "从手机接收", resultBody(h, false, "✘ " + message));
            }
        });

        if (err != null) {
            h.showSheet(TAG_RECEIVE, "从手机接收", resultBody(h, false, "✘ " + err));
        }
    }

    private static View waitingBody(final Host h, String title) {
        Context c = h.ctx();
        Palette p = h.pal();
        // body(c, true)：这一页一离开屏幕就自动停掉监听
        //（点「关闭」/ 返回键 / 被别的弹层顶掉，走的都是 detach）
        LinearLayout b = body(c, true);
        b.addView(Widgets.sectionLabel(c, p, title));
        b.addView(info(c, p, "保持这个界面别退出。手机那边点「推送到手表」就会连过来。"));
        b.addView(Ui.spacer(c, 8));
        b.addView(Widgets.button(c, p, "取消等待", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                h.closeSheet();
            }
        }));
        return b;
    }

    private static View resultBody(final Host h, final boolean ok, String text) {
        final Context c = h.ctx();
        final Palette p = h.pal();
        LinearLayout b = body(c);
        b.addView(Widgets.sectionLabel(c, p, ok ? "接收成功" : "没收到"));
        b.addView(info(c, p, text));

        if (ok) {
            // 直接把「手机推过来之后手表认成什么样」摊开给用户看，
            // 省得他还要退回「我的」页再对一遍。
            AppConfig cfg = h.cfg();
            b.addView(Ui.spacer(c, 6));
            b.addView(Widgets.settingRow(c, p, "课表", null,
                    blank(cfg.tableName) ? "—" : cfg.tableName, null));
            b.addView(Widgets.gap(c, 5));
            b.addView(Widgets.settingRow(c, p, "课程", null,
                    h.store().courses.size() + " 门", null));
            b.addView(Widgets.gap(c, 5));
            b.addView(Widgets.settingRow(c, p, "开始上课时间", null,
                    blank(cfg.startDate) ? "未设置" : cfg.startDate, null));
            b.addView(Widgets.gap(c, 5));
            b.addView(Widgets.settingRow(c, p, "本学期总周数", null, cfg.totalWeeks + " 周", null));
        }

        b.addView(Ui.spacer(c, 8));
        b.addView(Widgets.button(c, p, ok ? "完成" : "重试", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ok) {
                    h.closeSheet();
                } else {
                    startWaiting(h);
                }
            }
        }));
        if (!ok) {
            b.addView(Ui.spacer(c, 5));
            b.addView(Widgets.button(c, p, "返回", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    h.closeSheet();
                }
            }));
        }
        return b;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 人看得懂的体积（原来直接甩原始字节数，14 门课是「12xxx 字节」，读起来费劲） */
    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1048576.0);
    }

    /**
     * 全屏二维码（白底、铺满内接正方形，屏幕最亮）。
     *
     * @param title  顶部一行小字
     * @param hint   底部一行小字（一般写「地址」或操作提示）
     * @param resId  预生成的二维码位图（见 tools/gen_qr.py）
     */
    public static View qrOverlay(final Host h, String title, String hint, int resId) {
        final Context c = h.ctx();
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(0xFFFFFFFF);

        QrView qr = new QrView(c, resId);
        FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        qp.gravity = Gravity.CENTER;
        f.addView(qr, qp);

        TextView top = new TextView(c);
        top.setText(title);
        top.setTextColor(0xFF6B6B6B);
        top.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        top.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        tp.topMargin = Ui.dp(c, 10);
        f.addView(top, tp);

        TextView bottom = new TextView(c);
        bottom.setText(hint);
        bottom.setTextColor(0xFF9A9A9A);
        bottom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        bottom.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        bp.bottomMargin = Ui.dp(c, 10);
        f.addView(bottom, bp);

        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                h.closeSheet();
            }
        });
        return f;
    }

    // ---------------------------------------------------------- 开始上课时间

    public static void startDateSheet(final Host h) {
        final Context c = h.ctx();
        final Palette p = h.pal();
        final AppConfig cfg = h.cfg();
        LinearLayout b = body(c);

        Calendar cur = Weeks.parseDate(cfg.startDate);
        if (cur == null) {
            cur = Calendar.getInstance();
            Weeks.zeroTime(cur);
        }
        final Calendar cal = (Calendar) cur.clone();

        final Runnable rebuild = new Runnable() {
            @Override
            public void run() {
                startDateSheet(h);
            }
        };

        b.addView(info(c, p, "课表开始的第一天，不是开学时间。改完立即生效。"));
        b.addView(Ui.spacer(c, 4));

        // 年 / 月 / 日 三个步进器（按钮自己接管，因为改的是 Calendar）
        addDateStepper(b, h, p, "年", String.valueOf(cal.get(Calendar.YEAR)),
                new Setter() {
                    @Override
                    public void set(int delta) {
                        cal.add(Calendar.YEAR, delta);
                        cfg.startDate = Weeks.formatDate(cal);
                    }
                }, rebuild);
        addDateStepper(b, h, p, "月", (cal.get(Calendar.MONTH) + 1) + " 月",
                new Setter() {
                    @Override
                    public void set(int delta) {
                        cal.add(Calendar.MONTH, delta);
                        cfg.startDate = Weeks.formatDate(cal);
                    }
                }, rebuild);
        addDateStepper(b, h, p, "日", cal.get(Calendar.DAY_OF_MONTH) + " 日",
                new Setter() {
                    @Override
                    public void set(int delta) {
                        cal.add(Calendar.DAY_OF_MONTH, delta);
                        cfg.startDate = Weeks.formatDate(cal);
                    }
                }, rebuild);

        b.addView(Ui.spacer(c, 6));
        Calendar mon = Weeks.startMonday(cfg);
        int cur2 = Weeks.currentWeek(cfg);
        b.addView(info(c, p, "已设置：" + (cfg.startDate.isEmpty() ? "未设置" : cfg.startDate)
                + (mon == null ? "" : "\n按所在周的周一计算：" + Weeks.formatDate(mon))
                + (cur2 > 0 ? "\n当前：第 " + cur2 + " 周 / 共 " + cfg.totalWeeks + " 周" : "")));

        b.addView(Ui.spacer(c, 6));
        b.addView(Widgets.button(c, p, "设为今天", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Calendar t = Calendar.getInstance();
                Weeks.zeroTime(t);
                cfg.startDate = Weeks.formatDate(t);
                h.configChanged(false);
                startDateSheet(h);
            }
        }));

        h.showSheet("startdate", "开始上课时间", b);
    }

    /** 生成一行「年/月/日」步进器，回调里给的是 ±1 的增量 */
    private static void addDateStepper(LinearLayout parent, final Host h, Palette p,
                                       String title, String value, final Setter delta,
                                       final Runnable rebuild) {
        Context c = h.ctx();
        LinearLayout row = (LinearLayout) Widgets.stepperRow(c, p, title, value, null, null);
        if (row.getChildCount() >= 4) {
            row.getChildAt(1).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    delta.set(-1);
                    afterChange(h, rebuild);
                }
            });
            row.getChildAt(3).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    delta.set(1);
                    afterChange(h, rebuild);
                }
            });
        }
        parent.addView(row);
        parent.addView(Ui.spacer(parent.getContext(), 5));
    }

    private static void afterChange(Host h, Runnable rebuild) {
        h.configChanged(false);
        if (rebuild != null) {
            rebuild.run();
        }
    }

    // ---------------------------------------------------------- 当前周数

    /**
     * 当前周数 = 按开始日期自动算 + 手动修正（weekOffset）。
     * 在这里调偏移，比如开学第一周没上课、或者中途放假要整体错一周。
     */
    public static void weekSheet(final Host h) {
        final Context c = h.ctx();
        final Palette p = h.pal();
        final AppConfig cfg = h.cfg();
        LinearLayout b = body(c);
        final Runnable rebuild = new Runnable() {
            @Override
            public void run() {
                weekSheet(h);
            }
        };

        int auto = autoWeek(cfg);
        b.addView(info(c, p, auto > 0
                ? "按开始日期自动算是第 " + auto + " 周。不准的话在这里加减修正。"
                : "先在「开始上课时间」里设置课表第一天，才能自动算周数。"));
        b.addView(Ui.spacer(c, 4));

        int cur = Weeks.currentWeek(cfg);
        b.addView(Widgets.stepperRow(c, p, "当前周数", cur > 0 ? "第 " + cur + " 周" : "—",
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.weekOffset = clamp(v, -20, 20);
                    }
                }, cfg.weekOffset - 1, rebuild),
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.weekOffset = clamp(v, -20, 20);
                    }
                }, cfg.weekOffset + 1, rebuild)));
        if (cfg.weekOffset != 0) {
            b.addView(Ui.spacer(c, 5));
            b.addView(Widgets.button(c, p, "恢复自动计算（不加减）", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cfg.weekOffset = 0;
                    h.configChanged(false);
                    weekSheet(h);
                }
            }));
        }
        h.showSheet("week", "当前周数", b);
    }

    /** 不含手动修正的周数 */
    private static int autoWeek(AppConfig cfg) {
        AppConfig tmp = cfg.copy();
        tmp.weekOffset = 0;
        return Weeks.currentWeek(tmp);
    }

    // ---------------------------------------------------------- 总周数

    public static void totalWeeksSheet(final Host h) {
        final Context c = h.ctx();
        final Palette p = h.pal();
        final AppConfig cfg = h.cfg();
        LinearLayout b = body(c);
        final Runnable rebuild = new Runnable() {
            @Override
            public void run() {
                totalWeeksSheet(h);
            }
        };
        b.addView(Widgets.stepperRow(c, p, "本学期总周数", cfg.totalWeeks + " 周",
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.totalWeeks = clamp(v, 1, 30);
                    }
                }, cfg.totalWeeks - 1, rebuild),
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.totalWeeks = clamp(v, 1, 30);
                    }
                }, cfg.totalWeeks + 1, rebuild)));
        int cur = Weeks.currentWeek(cfg);
        b.addView(info(c, p, "当前：第 " + (cur > 0 ? cur : "?") + " 周，共 " + cfg.totalWeeks + " 周。"));
        h.showSheet("weeks", "本学期总周数", b);
    }

    // ---------------------------------------------------------- 节数设置

    public static void periodCountSheet(final Host h) {
        final Context c = h.ctx();
        final Palette p = h.pal();
        final AppConfig cfg = h.cfg();
        LinearLayout b = body(c);
        final Runnable rebuild = new Runnable() {
            @Override
            public void run() {
                periodCountSheet(h);
            }
        };
        b.addView(info(c, p, "一般上午 4 节、下午 4 节。两节课算一大节，所以默认每 2 节后面是大课间。"));
        b.addView(Ui.spacer(c, 4));

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            b.addView(Widgets.stepperRow(c, p, AppConfig.SECTION_NAMES[i] + "节数",
                    cfg.periodCount[i] + " 节",
                    apply(h, new Setter() {
                        @Override
                        public void set(int v) {
                            cfg.periodCount[idx] = clamp(v, 0, 12);
                        }
                    }, cfg.periodCount[i] - 1, rebuild),
                    apply(h, new Setter() {
                        @Override
                        public void set(int v) {
                            cfg.periodCount[idx] = clamp(v, 0, 12);
                        }
                    }, cfg.periodCount[i] + 1, rebuild)));
            b.addView(Ui.spacer(c, 5));
        }

        b.addView(Widgets.sectionLabel(c, p, "推算出来的时间"));
        b.addView(info(c, p, preview(cfg)));
        h.showSheet("count", "课表节数设置", b);
    }

    private static String preview(AppConfig cfg) {
        TimeTable tt = new TimeTable(cfg);
        StringBuilder sb = new StringBuilder();
        int p = 1;
        for (int s = 0; s < 3; s++) {
            int n = cfg.periodCount[s];
            if (n <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(AppConfig.SECTION_NAMES[s]).append("  第").append(p).append('-').append(p + n - 1)
                    .append("节  ").append(TimeTable.hhmm(tt.start(p))).append('-')
                    .append(TimeTable.hhmm(tt.end(p + n - 1)));
            p += n;
        }
        return sb.length() == 0 ? "还没有设置任何节次" : sb.toString();
    }

    // ---------------------------------------------------------- 时间设置

    public static void timeSheet(final Host h) {
        final Context c = h.ctx();
        final Palette p = h.pal();
        final AppConfig cfg = h.cfg();
        LinearLayout b = body(c);
        final Runnable rebuild = new Runnable() {
            @Override
            public void run() {
                timeSheet(h);
            }
        };

        b.addView(Widgets.switchRow(c, p, "每节课时长相同", "关掉可以一节一节单独设",
                cfg.sameLength, new Widgets.BoolCallback() {
                    @Override
                    public void onChanged(boolean value) {
                        cfg.sameLength = value;
                        cfg.ensurePerPeriod();
                        h.configChanged(false);
                        timeSheet(h);
                    }
                }));
        b.addView(Ui.spacer(c, 5));

        if (cfg.sameLength) {
            b.addView(Widgets.stepperRow(c, p, "每节课时长", cfg.periodMinutes + " 分钟",
                    apply(h, new Setter() {
                        @Override
                        public void set(int v) {
                            cfg.periodMinutes = clamp(v, 15, 120);
                            cfg.sameLength = true;
                            cfg.ensurePerPeriod();
                            java.util.Arrays.fill(cfg.perPeriodMinutes, cfg.periodMinutes);
                        }
                    }, cfg.periodMinutes - 5, rebuild),
                    apply(h, new Setter() {
                        @Override
                        public void set(int v) {
                            cfg.periodMinutes = clamp(v, 15, 120);
                            cfg.sameLength = true;
                            cfg.ensurePerPeriod();
                            java.util.Arrays.fill(cfg.perPeriodMinutes, cfg.periodMinutes);
                        }
                    }, cfg.periodMinutes + 5, rebuild)));
        } else {
            b.addView(Widgets.sectionLabel(c, p, "各节时长"));
            cfg.ensurePerPeriod();
            for (int i = 0; i < cfg.perPeriodMinutes.length; i++) {
                final int idx = i;
                b.addView(Widgets.stepperRow(c, p, "第 " + (i + 1) + " 节",
                        cfg.perPeriodMinutes[i] + "′",
                        apply(h, new Setter() {
                            @Override
                            public void set(int v) {
                                cfg.perPeriodMinutes[idx] = clamp(v, 15, 120);
                            }
                        }, cfg.perPeriodMinutes[i] - 5, rebuild),
                        apply(h, new Setter() {
                            @Override
                            public void set(int v) {
                                cfg.perPeriodMinutes[idx] = clamp(v, 15, 120);
                            }
                        }, cfg.perPeriodMinutes[i] + 5, rebuild)));
                b.addView(Ui.spacer(c, 5));
            }
        }

        b.addView(Widgets.stepperRow(c, p, "课间休息", cfg.breakMinutes + " 分钟",
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.breakMinutes = clamp(v, 0, 40);
                    }
                }, cfg.breakMinutes - 5, rebuild),
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.breakMinutes = clamp(v, 0, 40);
                    }
                }, cfg.breakMinutes + 5, rebuild)));
        b.addView(Ui.spacer(c, 5));
        b.addView(Widgets.stepperRow(c, p, "大课间休息", cfg.bigBreakMinutes + " 分钟",
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.bigBreakMinutes = clamp(v, 0, 60);
                    }
                }, cfg.bigBreakMinutes - 5, rebuild),
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.bigBreakMinutes = clamp(v, 0, 60);
                    }
                }, cfg.bigBreakMinutes + 5, rebuild)));
        b.addView(info(c, p, "大课间出现在每 2 节课之后（上午第 2 节后、下午第 6 节后）。"));

        // 各段第一节开始时间
        for (int s = 0; s < 3; s++) {
            final int sec = s;
            if (cfg.periodCount[sec] <= 0) {
                continue;
            }
            int first = cfg.sectionFirstPeriod(sec);
            b.addView(Widgets.sectionLabel(c, p, AppConfig.SECTION_NAMES[sec] + " · 第" + first + "节开始时间"));
            b.addView(Widgets.stepperRow(c, p, "开始时间", TimeTable.hhmm(cfg.sectionStart[sec]),
                    apply(h, new Setter() {
                        @Override
                        public void set(int v) {
                            cfg.sectionStart[sec] = clamp(v, 0, 23 * 60 + 55);
                        }
                    }, cfg.sectionStart[sec] - 5, rebuild),
                    apply(h, new Setter() {
                        @Override
                        public void set(int v) {
                            cfg.sectionStart[sec] = clamp(v, 0, 23 * 60 + 55);
                        }
                    }, cfg.sectionStart[sec] + 5, rebuild)));
        }

        b.addView(Widgets.sectionLabel(c, p, "推算结果"));
        b.addView(info(c, p, preview(cfg)));

        h.showSheet("time", "课表时间设置", b);
    }

    // ---------------------------------------------------------- 提醒

    public static void reminderSheet(final Host h) {
        final Context c = h.ctx();
        final Palette p = h.pal();
        final AppConfig cfg = h.cfg();
        LinearLayout b = body(c);
        final Runnable rebuild = new Runnable() {
            @Override
            public void run() {
                reminderSheet(h);
            }
        };

        // 系统通知总开关没打开时，提醒永远发不出来——放在最上面并给跳转入口
        if (!Reminder.notificationsEnabled(c)) {
            b.addView(Widgets.settingRow(c, p, "通知权限未开启", "点这里去系统设置里打开", "去开启",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Reminder.openNotificationSettings(c);
                        }
                    }));
            b.addView(Ui.spacer(c, 5));
        }

        b.addView(Widgets.switchRow(c, p, "课程提醒", "默认上课前 20 分钟提醒",
                cfg.reminderOn, new Widgets.BoolCallback() {
                    @Override
                    public void onChanged(boolean value) {
                        cfg.reminderOn = value;
                        h.configChanged(false);
                        Reminder.schedule(c);
                        reminderSheet(h);
                    }
                }));
        b.addView(Ui.spacer(c, 5));
        b.addView(Widgets.stepperRow(c, p, "提前分钟数", cfg.reminderMinutes + " 分钟",
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.reminderMinutes = clamp(v, 5, 60);
                    }
                }, cfg.reminderMinutes - 5, rebuild),
                apply(h, new Setter() {
                    @Override
                    public void set(int v) {
                        cfg.reminderMinutes = clamp(v, 5, 60);
                    }
                }, cfg.reminderMinutes + 5, rebuild)));
        b.addView(Ui.spacer(c, 5));
        b.addView(Widgets.settingRow(c, p, "测试提醒", "10 秒后推送一条通知", "现在测",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Reminder.test(c);
                        h.toast("10 秒后提醒");
                    }
                }));

        // 提醒通路：优先走系统日历（系统 Provider 排的闹钟，息屏也投递），
        // 没有日历权限时退回应用闹钟（息屏会被 ColorOS 拦）。
        final boolean calOk = CalendarSync.hasPermission(c);
        b.addView(Ui.spacer(c, 5));
        b.addView(Widgets.settingRow(c, p,
                calOk ? "系统日历提醒" : "开启系统日历提醒",
                calOk ? "已写入 " + CalendarSync.lastCount(c) + " 条 · 息屏也能响"
                        : "授权后由系统日历提醒，App 不用常驻后台",
                calOk ? "重新同步" : "去授权",
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        h.requestCalendar();
                        if (!calOk) {
                            v.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    reminderSheet(h);
                                }
                            }, 1200);
                        }
                    }
                }));
        if (calOk) {
            b.addView(Ui.spacer(c, 5));
            b.addView(Widgets.settingRow(c, p, "验证日历提醒",
                    "写一条 1 分钟后提醒的事件，可以熄屏试", "现在测",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            h.toast(CalendarSync.testEvent(c));
                        }
                    }));
        }

        b.addView(Widgets.sectionLabel(c, p, "下一次提醒"));
        b.addView(info(c, p, Reminder.nextInfo(c)));

        h.showSheet("remind", "课程提醒", b);
    }

    // ---------------------------------------------------------- UI 风格

    public static void themeSheet(final Host h) {
        final Context c = h.ctx();
        final Palette p = h.pal();
        final AppConfig cfg = h.cfg();
        LinearLayout b = body(c);

        b.addView(info(c, p, "点一下立即生效，可以来回切换比较。"));
        b.addView(Ui.spacer(c, 4));

        for (final Palette t : Palette.ALL) {
            boolean sel = t.id.equals(cfg.themeId);
            LinearLayout row = Ui.row(c);
            row.setBackground(Ui.bg(c, sel ? p.accentSoft : p.card, 16,
                    sel ? 1 : (p.cardBorder != 0 ? 1 : 0), sel ? p.accent : p.cardBorder));
            row.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView name = Ui.tv(c, t.name, 11.5f, p.text, sel);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(dots(c, t));
            if (sel) {
                TextView ok = Ui.tv(c, "✓", 11, p.accent, true);
                ok.setPadding(Ui.dp(c, 5), 0, 0, 0);
                row.addView(ok);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cfg.themeId = t.id;
                    h.configChanged(true);
                    themeSheet(h);
                }
            });
            Ui.pressable(row);
            b.addView(row);
            b.addView(Ui.spacer(c, 5));
        }
        h.showSheet("theme", "UI 风格", b);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
