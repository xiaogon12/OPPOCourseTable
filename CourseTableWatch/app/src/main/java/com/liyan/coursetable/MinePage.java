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

/**
 * 「我的」页面：导入课程表 + 全部设置项。
 *
 * 从课表页向下滑进入；向上滑或点底部把手返回。
 */
public class MinePage extends FrameLayout {

    private final Host host;

    private LinearLayout headerBox;
    private LinearLayout listBox;
    private ScrollView scroll;
    private View handle;

    public MinePage(Host host) {
        super(host.ctx());
        this.host = host;
        build();
    }

    public ScrollView scrollView() {
        return scroll;
    }

    public void rebuild() {
        build();
    }

    private void build() {
        removeAllViews();
        Palette pal = host.pal();
        setBackgroundColor(pal.bg);

        LinearLayout root = Ui.column(host.ctx());
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        headerBox = Ui.column(host.ctx());
        headerBox.setPadding(Ui.dp(host.ctx(), 18), Ui.dp(host.ctx(), 12),
                Ui.dp(host.ctx(), 18), Ui.dp(host.ctx(), 2));
        root.addView(headerBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = Ui.scroll(host.ctx());

        listBox = Ui.column(host.ctx());
        Ui.blockFocus(listBox);
        listBox.setPadding(Ui.dp(host.ctx(), 20), Ui.dp(host.ctx(), 2),
                Ui.dp(host.ctx(), 20), Ui.dp(host.ctx(), 34));
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(root);

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
        pill.addView(Ui.tv(host.ctx(), "⌃", 11, p.textDim, false));
        TextView label = Ui.tv(host.ctx(), "课表", 10, p.textDim, false);
        label.setPadding(Ui.dp(host.ctx(), 4), 0, 0, 0);
        pill.addView(label);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.openMine(false);
            }
        });
        Ui.pressable(pill);
        return pill;
    }

    // ---------------------------------------------------------- 内容

    public void refresh() {
        Palette p = host.pal();
        AppConfig cfg = host.cfg();
        Store store = host.store();

        headerBox.removeAllViews();
        TextView title = Ui.tv(host.ctx(), "我的", 14f, p.text, true);
        title.setGravity(Gravity.CENTER);
        headerBox.addView(title);
        int week = Weeks.currentWeek(cfg);
        String info = (week > 0 ? "第 " + week + " 周" : "周次未设置")
                + " · " + store.courses.size() + " 门课";
        TextView sub = Ui.caption(host.ctx(), info, 9.5f, p.textFaint);
        sub.setPadding(0, Ui.dp(host.ctx(), 1), 0, 0);
        headerBox.addView(sub);

        listBox.removeAllViews();

        // 列表一律不显示「标题下面的小字」（说明文字放到各自弹层里），
        // 只保留右侧的当前值，页面看着干净。

        // 0. 从手机接收（蓝牙直连，唯一的课表来源）
        listBox.addView(Widgets.settingRow(host.ctx(), p, "从手机接收", null, "蓝牙",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Sheets.receiveSheet(host);
                    }
                }));
        listBox.addView(Widgets.gap(host.ctx(), 6));

        // 1. 开始上课时间
        String sd = (cfg.startDate == null || cfg.startDate.isEmpty()) ? "未设置" : cfg.startDate;
        listBox.addView(Widgets.settingRow(host.ctx(), p, "开始上课时间", null, sd,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Sheets.startDateSheet(host);
                    }
                }));
        listBox.addView(Widgets.gap(host.ctx(), 6));

        // 2. 当前周数（可点开手动修正）
        int cur = Weeks.currentWeek(cfg);
        listBox.addView(Widgets.settingRow(host.ctx(), p, "当前周数", null,
                cur > 0 ? "第 " + cur + " 周" : "—",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Sheets.weekSheet(host);
                    }
                }));
        listBox.addView(Widgets.gap(host.ctx(), 6));

        // 3. 本学期总周数
        listBox.addView(Widgets.settingRow(host.ctx(), p, "本学期总周数", null,
                cfg.totalWeeks + " 周",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Sheets.totalWeeksSheet(host);
                    }
                }));
        listBox.addView(Widgets.gap(host.ctx(), 6));

        // 4. 课表节数设置
        listBox.addView(Widgets.settingRow(host.ctx(), p, "课表节数设置", null,
                cfg.periodCount[0] + " · " + cfg.periodCount[1] + " · " + cfg.periodCount[2],
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Sheets.periodCountSheet(host);
                    }
                }));
        listBox.addView(Widgets.gap(host.ctx(), 6));

        // 5. 课表时间设置
        listBox.addView(Widgets.settingRow(host.ctx(), p, "课表时间设置", null,
                cfg.periodMinutes + "′ · 课间" + cfg.breakMinutes + " · 大课间" + cfg.bigBreakMinutes,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Sheets.timeSheet(host);
                    }
                }));
        listBox.addView(Widgets.gap(host.ctx(), 6));

        // 6. 课程提醒（开关 / 提前时间 / 测试都收在这一个模块里）
        listBox.addView(Widgets.settingRow(host.ctx(), p, "课程提醒", null,
                cfg.reminderOn ? "开 · 提前" + cfg.reminderMinutes + "分钟" : "关",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Sheets.reminderSheet(host);
                    }
                }));
        listBox.addView(Widgets.gap(host.ctx(), 6));

        // 7. UI 风格
        listBox.addView(Widgets.settingRow(host.ctx(), p, "UI 风格", null, p.name,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Sheets.themeSheet(host);
                    }
                }));

        // 8. 出处署名。
        // 不是装饰：CC BY-NC-SA 4.0 要求保留作者署名，谁要把它换成自己的名字，
        // 就必须动源码重新编译（反编译改 dex 里的字符串同样绕不过去）。
        // 细节见仓库根目录 LICENSE 与 README「关于套壳」。
        TextView sign = Ui.caption(host.ctx(),
                "© 2026 xiaogon12 · CC BY-NC-SA 4.0", 9f, p.textFaint);
        sign.setPadding(0, Ui.dp(host.ctx(), 14), 0, 0);
        listBox.addView(sign);
    }
}
