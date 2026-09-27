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

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

/**
 * 课表页。
 *
 * <p>列表按时间正序排（{@link Store#ofDay} 已排序），所以「上完的课」天然就在最上面，
 * 不需要重排数组；我们做的只是：
 * <ul>
 *   <li>把已经上完的卡压暗（alpha 0.5），当背景板；</li>
 *   <li>把「焦点课程」滚到屏幕正中并高亮。</li>
 * </ul>
 *
 * <p><b>焦点规则（用户要求）：</b>
 * <pre>
 *   正在上的课 · 没过半   -> 焦点是它，徽章「进行中」
 *   正在上的课 · 已过半   -> 焦点让给下一节（徽章「即将开始」），
 *                            自己退到上方标「即将下课」
 *   正在上的课 · 过半且没有下一节（当天最后一节）
 *                        -> 焦点仍是它（居中），但徽章改「即将下课」
 *   没课在上的课间 / 还没开始 -> 焦点是「下一节」，徽章「即将开始」
 *   今天全上完             -> 不高亮，列表回到顶部
 * </pre>
 *
 * <p><b>刷新策略：</b>{@code MainActivity} 每 30 秒调一次 {@link #refresh()}。
 * 只有「会影响画面的东西」变了才重建列表（见 {@link #signature}），
 * 而且焦点没变时**不重新滚动** —— 否则用户正翻着看，30 秒一到列表会被拽回去。
 */
public class CoursePage extends FrameLayout {

    private static final int STATE_NONE = 0;
    private static final int STATE_RUNNING = 1;
    private static final int STATE_NEXT = 2;
    private static final int STATE_ENDED = 3;
    private static final int STATE_FREE = 4;
    /** 正在上、但已经过半（让位给下一节），或当天最后一节已过半 */
    private static final int STATE_ENDING = 5;

    private static final float ALPHA_FINISHED = 0.50f;
    private static final float ALPHA_ENDING = 0.78f;

    private final Host host;

    private LinearLayout headerBox;
    private LinearLayout listBox;
    private ScrollView scroll;
    private View handle;

    private int dayOffset = 0;

    // ---- 刷新 / 居中 状态 ----
    /** 上一次画面的指纹；相同就跳过重建 */
    private String lastSig = null;
    /** 顶部标题对应的「日期 + 偏移」，跨天时用来重建标题 */
    private int lastDayKey = -1;
    /** listBox 的原始顶部内边距（内容不足一屏时会临时加大） */
    private int padTopBase = 0;
    /** 焦点卡片，居中时用 */
    private View[] cards = new View[0];
    /** 有「待完成的居中断」时为 true（首帧没量好高度时会留着） */
    private boolean needCenter = false;
    private int lastFocus = Integer.MIN_VALUE;
    private int lastCenterDay = Integer.MIN_VALUE;

    public CoursePage(Host host) {
        super(host.ctx());
        this.host = host;
        build();
    }

    public ScrollView scrollView() {
        return scroll;
    }

    public int dayOffset() {
        return dayOffset;
    }

    public void setDayOffset(int off) {
        int v = Math.max(0, Math.min(1, off));   // 0 = 今天，1 = 明天
        if (v == dayOffset) {
            return;
        }
        dayOffset = v;
        refresh();      // refresh 内部会按日期键决定要不要重建顶部标题
    }

    // ---------------------------------------------------------- 结构

    private void build() {
        removeAllViews();
        Palette p = host.pal();
        setBackgroundColor(p.bg);

        LinearLayout root = Ui.column(host.ctx());
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        headerBox = Ui.column(host.ctx());
        headerBox.setPadding(Ui.dp(host.ctx(), 18), Ui.dp(host.ctx(), 12),
                Ui.dp(host.ctx(), 18), Ui.dp(host.ctx(), 6));
        root.addView(headerBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = Ui.scroll(host.ctx());

        listBox = Ui.column(host.ctx());
        Ui.blockFocus(listBox);
        listBox.setPadding(Ui.dp(host.ctx(), 20), Ui.dp(host.ctx(), 2),
                Ui.dp(host.ctx(), 20), Ui.dp(host.ctx(), 34));
        padTopBase = listBox.getPaddingTop();
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(root);

        // 底部「我的」把手
        handle = buildHandle();
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = Ui.dp(host.ctx(), 6);
        addView(handle, hp);

        refresh();
    }

    private View buildHandle() {
        Palette p = host.pal();
        LinearLayout pill = Ui.row(host.ctx());
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(Ui.dp(host.ctx(), 12), Ui.dp(host.ctx(), 4),
                Ui.dp(host.ctx(), 12), Ui.dp(host.ctx(), 4));
        pill.setBackground(Ui.bg(host.ctx(), Palette.a(p.textFaint, 0.16f), 14));

        TextView arrow = Ui.tv(host.ctx(), "⌄", 11, p.textDim, false);
        TextView label = Ui.tv(host.ctx(), "我的", 10, p.textDim, false);
        label.setPadding(Ui.dp(host.ctx(), 4), 0, 0, 0);
        pill.addView(arrow);
        pill.addView(label);
        pill.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.openMine(true);
            }
        });
        Ui.pressable(pill);
        return pill;
    }

    // ---------------------------------------------------------- 顶部

    /**
     * 顶部只放日期。
     * 不在上面显示「昨天 / 明天」之类的切换项：往左滑就是明天的课，
     * 想回到今天往右滑回去即可（明天的日期本身会变色提示）。
     */
    private void buildHeader() {
        Palette p = host.pal();
        headerBox.removeAllViews();

        Calendar date = Now.get();
        date.add(Calendar.DAY_OF_MONTH, dayOffset);
        boolean tomorrow = (dayOffset > 0);

        TextView title = Ui.caption(host.ctx(), Weeks.dateTitle(date), 13.5f,
                tomorrow ? p.accent : p.text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerBox.addView(title);

        // 日期下面一行小字：第几周（明天则提示是明天）
        int week = Weeks.weekOf(host.cfg(), date);
        String sub = tomorrow ? "明天" : (week > 0 ? "第 " + week + " 周" : "");
        if (!sub.isEmpty()) {
            TextView w = Ui.caption(host.ctx(), sub, 9f, tomorrow ? p.accent : p.textFaint);
            w.setPadding(0, Ui.dp(host.ctx(), 2), 0, 0);
            headerBox.addView(w);
        }
    }

    // ---------------------------------------------------------- 列表

    public void refresh() {
        AppConfig cfg = host.cfg();
        Store store = host.store();
        TimeTable tt = host.timeTable();

        Calendar today = Now.get();
        Calendar date = (Calendar) today.clone();
        date.add(Calendar.DAY_OF_MONTH, dayOffset);

        // 跨天 / 换天时重建顶部标题
        int dayKey = (date.get(Calendar.YEAR) * 1000 + date.get(Calendar.DAY_OF_YEAR)) * 4 + dayOffset;
        if (dayKey != lastDayKey) {
            lastDayKey = dayKey;
            buildHeader();
        }

        int dow = Weeks.dowOf(date);
        int week = Weeks.weekOf(cfg, date);
        List<Course> courses = store.ofDay(dow, week);

        boolean isToday = (dayOffset == 0);
        int nowMinutes = today.get(Calendar.HOUR_OF_DAY) * 60 + today.get(Calendar.MINUTE);

        int headerState = STATE_NONE;   // 顶部状态文案
        int focus = -1;                 // 要居中 + 高亮的卡片下标
        int focusState = STATE_NONE;    // 焦点卡片自己的状态
        int runningIdx = -1;            // 正在上的课
        int finishedMask = 0;           // bit i = 第 i 张卡今天已经上完

        if (courses.isEmpty()) {
            headerState = STATE_FREE;
        } else if (isToday) {
            for (int i = 0; i < courses.size(); i++) {
                Course c = courses.get(i);
                if (nowMinutes >= tt.start(c.startPeriod) && nowMinutes < tt.end(c.endPeriod)) {
                    runningIdx = i;
                    break;
                }
            }

            // 两节连上算一堂课：过半就把焦点让给下一节
            boolean half = false;
            if (runningIdx >= 0) {
                int s = tt.start(courses.get(runningIdx).startPeriod);
                int e = tt.end(courses.get(runningIdx).endPeriod);
                half = (nowMinutes - s) * 2 >= Math.max(1, e - s);
            }

            int next = -1;
            for (int i = 0; i < courses.size(); i++) {
                if (tt.start(courses.get(i).startPeriod) > nowMinutes) {
                    next = i;
                    break;
                }
            }

            if (runningIdx >= 0 && !half) {
                focus = runningIdx;
                focusState = STATE_RUNNING;
                headerState = STATE_RUNNING;
            } else if (next >= 0) {
                focus = next;
                focusState = STATE_NEXT;
                headerState = STATE_NEXT;
            } else if (runningIdx >= 0) {
                // 当天最后一节且已过半：还是它居中，但换成「即将下课」
                focus = runningIdx;
                focusState = STATE_ENDING;
                headerState = STATE_RUNNING;
            } else {
                headerState = STATE_ENDED;
            }

            for (int i = 0; i < courses.size(); i++) {
                if (tt.end(courses.get(i).endPeriod) <= nowMinutes) {
                    finishedMask |= (1 << i);
                }
            }
        }

        // 只有真会影响画面的变化才重建
        String sig = signature(courses, tt, week, headerState, focus, focusState,
                runningIdx, finishedMask);
        if (sig.equals(lastSig)) {
            if (needCenter) {
                centerOn(focus);
            }
            return;
        }
        lastSig = sig;

        listBox.removeAllViews();
        // 重建时把顶部留白复原（居中逻辑可能会临时加大它）
        listBox.setPadding(listBox.getPaddingLeft(), padTopBase,
                listBox.getPaddingRight(), listBox.getPaddingBottom());

        listBox.addView(buildStatus(headerState, courses.size(), week));

        cards = new View[courses.size()];
        for (int i = 0; i < courses.size(); i++) {
            Course c = courses.get(i);
            int cardState = STATE_NONE;
            if (i == focus) {
                cardState = focusState;
            } else if (i == runningIdx) {
                cardState = STATE_ENDING;
            }
            boolean finished = (finishedMask >> i & 1) == 1;
            cards[i] = buildCard(c, tt, cardState, finished);
            listBox.addView(cards[i]);
            if (i < courses.size() - 1) {
                listBox.addView(Ui.spacer(host.ctx(), 8));
            }
        }

        if (courses.isEmpty()) {
            listBox.addView(buildEmpty(week));
        }

        centerOn(focus);
    }

    /**
     * 画面指纹。包含所有会影响呈现的输入：
     * 课程内容、整张作息表的时刻、以及焦点 / 已上完这些时间敏感状态。
     * 不含「当前分钟数」本身，否则每 30 秒都会白重建一次。
     */
    private static String signature(List<Course> courses, TimeTable tt, int week,
                                    int headerState, int focus, int focusState,
                                    int runningIdx, int finishedMask) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(week).append('|').append(headerState).append('|')
                .append(focus).append('|').append(focusState).append('|')
                .append(runningIdx).append('|').append(finishedMask).append('|');
        for (int p = 1; p <= tt.count(); p++) {
            sb.append(tt.start(p)).append('-').append(tt.end(p)).append(',');
        }
        sb.append('|');
        for (Course c : courses) {
            sb.append(c.name).append('\u0001').append(c.room).append('\u0001')
                    .append(c.teacher).append('\u0001').append(c.className).append('\u0001')
                    .append(c.startPeriod).append('-').append(c.endPeriod).append('\u0002');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------- 焦点居中

    /**
     * 把焦点卡片滚到可视区正中。
     *
     * <p>焦点没变就不动 —— {@code MainActivity} 每 30 秒调一次 refresh，
     * 如果每次都强行滚回去，用户正翻着看就被拽走了。
     */
    private void centerOn(final int focus) {
        boolean changed = (focus != lastFocus) || (dayOffset != lastCenterDay);
        lastFocus = focus;
        lastCenterDay = dayOffset;

        if (focus < 0 || focus >= cards.length || cards[focus] == null) {
            // 没有焦点（今天已上完 / 没课）：回到顶部，上完的课本来就在上面
            if (changed) {
                needCenter = false;
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.scrollTo(0, 0);
                    }
                });
            }
            return;
        }
        if (!changed && !needCenter) {
            return;
        }
        needCenter = true;
        centerStep(cards[focus], 0);
    }

    /** attempt 只是防死循环：布局没完成时下一帧再试 */
    private void centerStep(final View target, final int attempt) {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                if (!needCenter || target == null) {
                    return;
                }
                int h = scroll.getHeight();
                int ch = target.getHeight();
                if (h <= 0 || ch <= 0) {
                    // 首帧还没量好高度，隔一帧再来（最多 4 次）
                    if (attempt < 4) {
                        centerStep(target, attempt + 1);
                    }
                    return;
                }

                int max = Math.max(0, listBox.getHeight() - h);
                if (max == 0) {
                    // 内容不足一屏，滚不动：改用顶部留白把焦点卡顶到正中
                    int want = Math.max(0, (h - ch) / 2);
                    int need = want - target.getTop();
                    if (need > 2 && attempt < 4) {
                        listBox.setPadding(listBox.getPaddingLeft(), padTopBase + need,
                                listBox.getPaddingRight(), listBox.getPaddingBottom());
                        centerStep(target, attempt + 1);
                        return;
                    }
                    scroll.scrollTo(0, 0);
                    needCenter = false;
                    return;
                }

                int sy = target.getTop() - (h - ch) / 2;
                if (sy < 0) {
                    sy = 0;
                } else if (sy > max) {
                    sy = max;
                }
                scroll.scrollTo(0, sy);
                needCenter = false;
            }
        });
    }

    // ---------------------------------------------------------- 状态行

    private View buildStatus(int state, int count, int week) {
        Palette p = host.pal();
        String text;
        boolean pill = false;
        switch (state) {
            case STATE_RUNNING:
                text = "正在上课";
                pill = true;
                break;
            case STATE_NEXT:
                text = "下一节课";
                pill = true;
                break;
            case STATE_ENDED:
                text = "今日课程已结束";
                break;
            case STATE_FREE:
                text = "这一天没有课";
                break;
            default:
                text = "共 " + count + " 节课";
                break;
        }
        if (pill) {
            // 状态药丸：居中、accentSoft 底，比普通文字更醒目也更精致
            TextView t = Ui.tv(host.ctx(), text, 10f, p.accent, true);
            t.setPadding(Ui.dp(host.ctx(), 12), Ui.dp(host.ctx(), 4),
                    Ui.dp(host.ctx(), 12), Ui.dp(host.ctx(), 4));
            t.setBackground(Ui.bg(host.ctx(), p.accentSoft, 13));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = Ui.dp(host.ctx(), 8);
            t.setLayoutParams(lp);
            return t;
        }
        TextView t = Ui.caption(host.ctx(), text, 10f, p.textFaint);
        t.setPadding(0, Ui.dp(host.ctx(), 1), 0, Ui.dp(host.ctx(), 8));
        return t;
    }

    private View buildEmpty(int week) {
        Palette p = host.pal();
        TextView t = Ui.tv(host.ctx(),
                week > 0 ? "第 " + week + " 周没有排课" : "还没有课程数据",
                11, p.textFaint, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(host.ctx(), 18), 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    // ---------------------------------------------------------- 卡片

    /** 单张课程卡片 */
    private View buildCard(Course c, TimeTable tt, int state, boolean finished) {
        Palette p = host.pal();

        boolean running = (state == STATE_RUNNING);
        boolean next = (state == STATE_NEXT);
        boolean ending = (state == STATE_ENDING);

        int cardBg = running ? p.accent : (next ? p.accentSoft : p.card);
        int titleColor = running ? p.onAccent : p.text;
        int subColor = running ? Palette.a(p.onAccent, 0.82f) : p.textDim;
        int chipBg = running ? Palette.a(p.onAccent, 0.20f) : (next ? p.accent : p.chip);
        int chipText = running ? p.onAccent : (next ? p.onAccent : p.chipText);
        int timeColor = running ? Palette.a(p.onAccent, 0.85f) : (next ? p.accent : p.textFaint);

        LinearLayout card = Ui.row(host.ctx());
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setPadding(Ui.dp(host.ctx(), 11), Ui.dp(host.ctx(), 9),
                Ui.dp(host.ctx(), 11), Ui.dp(host.ctx(), 9));
        if (running) {
            // 进行中的课：accent 渐变底，比纯色更有层次
            card.setBackground(Ui.gradientBg(host.ctx(), p.accent,
                    Palette.mix(p.accent, 0xFF000000, 0.14f), 17));
        } else {
            int border = next ? 1 : 0;
            int borderColor = next ? Palette.a(p.accent, 0.55f) : (p.cardBorder);
            if (next || p.cardBorder != 0) {
                card.setBackground(Ui.bg(host.ctx(), cardBg, 17, border, borderColor));
            } else {
                card.setBackground(Ui.bg(host.ctx(), cardBg, 17));
            }
        }

        // 课程色条
        View bar = new View(host.ctx());
        int barColor = running ? Palette.a(p.onAccent, 0.45f) : p.barColor(c.name);
        bar.setBackground(Ui.bg(host.ctx(), barColor, 2));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                Ui.dp(host.ctx(), 3), ViewGroup.LayoutParams.MATCH_PARENT);
        bp.rightMargin = Ui.dp(host.ctx(), 9);
        card.addView(bar, bp);

        LinearLayout col = Ui.column(host.ctx());
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 第一行：节次 chip + 时间
        LinearLayout r1 = Ui.row(host.ctx());
        TextView period = Ui.tv(host.ctx(), c.periodShort() + " 节", 9.5f, chipText, false);
        period.setPadding(Ui.dp(host.ctx(), 6), Ui.dp(host.ctx(), 1),
                Ui.dp(host.ctx(), 6), Ui.dp(host.ctx(), 1));
        period.setBackground(Ui.bg(host.ctx(), chipBg, 9));
        r1.addView(period);
        View flex = new View(host.ctx());
        r1.addView(flex, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView time = Ui.tv(host.ctx(), tt.rangeOfCourse(c), 9.5f, timeColor, false);
        r1.addView(time);
        col.addView(r1);

        // 第二行：课程名 + 徽章
        LinearLayout r2 = Ui.row(host.ctx());
        TextView name = Ui.tv(host.ctx(), c.name, 15.5f, titleColor, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r2.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (running || next || ending) {
            String label = running ? "进行中" : (next ? "即将开始" : "即将下课");
            int fg = running ? p.accent : (next ? p.onAccent : p.textFaint);
            int bg = running ? Palette.a(p.onAccent, 0.92f)
                    : (next ? p.accent : Palette.a(p.textFaint, 0.16f));
            TextView badge = Ui.tv(host.ctx(), label, 9, fg, false);
            badge.setPadding(Ui.dp(host.ctx(), 6), Ui.dp(host.ctx(), 1),
                    Ui.dp(host.ctx(), 6), Ui.dp(host.ctx(), 1));
            badge.setBackground(Ui.bg(host.ctx(), bg, 9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(host.ctx(), 5);
            r2.addView(badge, lp);
        }
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2p.topMargin = Ui.dp(host.ctx(), 2);
        col.addView(r2, r2p);

        // 第三行：教室 · 老师 · 班级
        String sub = c.subtitle();
        TextView subTv = Ui.tv(host.ctx(), sub.isEmpty() ? "—" : sub, 10, subColor, false);
        subTv.setMaxLines(1);
        subTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sub2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sub2.topMargin = Ui.dp(host.ctx(), 2);
        col.addView(subTv, sub2);

        if (finished) {
            card.setAlpha(ALPHA_FINISHED);
        } else if (ending) {
            card.setAlpha(ALPHA_ENDING);
        }
        return card;
    }

    /** 供外部（换天动画后）使用 */
    public String currentTitle() {
        Calendar c = Now.get();
        c.add(Calendar.DAY_OF_MONTH, dayOffset);
        return "第" + Weeks.weekOf(host.cfg(), c) + "周";
    }
}
