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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 各种「行」控件：设置项、开关、步进器、按钮 */
public final class Widgets {

    public interface BoolCallback {
        void onChanged(boolean value);
    }

    private Widgets() {
    }

    // ---------------------------------------------------------- 容器

    public static LinearLayout card(Context c, Palette p) {
        LinearLayout l = Ui.row(c);
        l.setBackground(Ui.bg(c, p.card, 17, p.cardBorder != 0 ? 1 : 0, p.cardBorder));
        l.setPadding(Ui.dp(c, 11), Ui.dp(c, 9), Ui.dp(c, 11), Ui.dp(c, 9));
        l.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    public static View wrapped(Context c, Palette p, View inner) {
        LinearLayout l = card(c, p);
        l.addView(inner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return l;
    }

    public static TextView sectionLabel(Context c, Palette p, String text) {
        TextView t = Ui.tv(c, text, 9.5f, p.textFaint, false);
        t.setPadding(Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 4));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** 左标题(+副标题) / 右取值 的一行 */
    public static LinearLayout baseRow(Context c, Palette p, String title, String subtitle, String value) {
        LinearLayout row = card(c, p);

        LinearLayout col = Ui.column(c);
        TextView t = Ui.tv(c, title, 11.5f, p.text, false);
        col.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = Ui.tv(c, subtitle, 9f, p.textFaint, false);
            s.setPadding(0, Ui.dp(c, 2), 0, 0);
            s.setMaxLines(1);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null && !value.isEmpty()) {
            // 取值做成 accent 小药丸：一眼能认出「这是当前值」，也比裸文字精致
            TextView v = Ui.tv(c, value, 10f, p.accent, true);
            v.setMaxLines(1);
            v.setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3));
            v.setBackground(Ui.bg(c, p.accentSoft, 11));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(c, 8);
            row.addView(v, lp);
        }
        return row;
    }

    public static View settingRow(Context c, Palette p, String title, String subtitle,
                                  String value, View.OnClickListener click) {
        LinearLayout row = baseRow(c, p, title, subtitle, value);
        if (click != null) {
            row.setOnClickListener(click);
            Ui.pressable(row);
        }
        return row;
    }

    // ---------------------------------------------------------- 开关

    public static View switchRow(Context c, Palette p, String title, String subtitle,
                                 boolean checked, final BoolCallback cb) {
        LinearLayout row = card(c, p);

        LinearLayout col = Ui.column(c);
        col.addView(Ui.tv(c, title, 11.5f, p.text, false));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = Ui.tv(c, subtitle, 9f, p.textFaint, false);
            sub.setPadding(0, Ui.dp(c, 2), 0, 0);
            col.addView(sub);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final MiniSwitch sw = new MiniSwitch(c);
        sw.setColors(p.accent, Palette.a(p.textFaint, 0.35f), p.light ? 0xFFFFFFFF : 0xFFF0F0F4);
        sw.setChecked(checked, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 17));
        lp.leftMargin = Ui.dp(c, 6);
        row.addView(sw, lp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean nv = !sw.isChecked();
                sw.setChecked(nv);
                if (cb != null) {
                    cb.onChanged(nv);
                }
            }
        });
        Ui.pressable(row);
        return row;
    }

    // ---------------------------------------------------------- 步进器

    private static TextView circleBtn(Context c, Palette p, String symbol, boolean enabled) {
        TextView t = Ui.tv(c, symbol, 13, enabled ? p.accent : p.textFaint, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.bg(c, Palette.a(p.accent, enabled ? 0.16f : 0.07f), 20));
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 21), Ui.dp(c, 21)));
        return t;
    }

    public static View stepperRow(Context c, Palette p, String title, String value,
                                  final Runnable onMinus, final Runnable onPlus) {
        LinearLayout row = card(c, p);
        TextView t = Ui.tv(c, title, 11.f, p.text, false);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView minus = circleBtn(c, p, "−", true);
        TextView plus = circleBtn(c, p, "+", true);
        TextView val = Ui.tv(c, value, 11.f, p.text, true);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(Ui.dp(c, 42));

        row.addView(minus);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.leftMargin = Ui.dp(c, 4);
        vp.rightMargin = Ui.dp(c, 4);
        row.addView(val, vp);
        row.addView(plus);

        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onMinus != null) {
                    onMinus.run();
                }
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onPlus != null) {
                    onPlus.run();
                }
            }
        });
        return row;
    }

    /** 纯展示行（点击进下一个弹层） */
    public static View valueRow(Context c, Palette p, String title, String value,
                                View.OnClickListener click) {
        return stepperRowNoBtn(c, p, title, value, click);
    }

    private static View stepperRowNoBtn(Context c, Palette p, String title, String value,
                                        View.OnClickListener click) {
        LinearLayout row = card(c, p);
        TextView t = Ui.tv(c, title, 11.f, p.text, false);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = Ui.tv(c, value, 11.f, p.accent, true);
        row.addView(v);
        if (click != null) {
            row.setOnClickListener(click);
            Ui.pressable(row);
        }
        return row;
    }

    // ---------------------------------------------------------- 按钮

    public static View button(Context c, Palette p, String text, boolean primary,
                              View.OnClickListener click) {
        TextView t = Ui.tv(c, text, 11.5f, primary ? p.onAccent : p.text, primary);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        t.setBackground(primary
                ? Ui.bg(c, p.accent, 18)
                : Ui.bg(c, Palette.a(p.textFaint, 0.16f), 18));
        if (click != null) {
            t.setOnClickListener(click);
            Ui.pressable(t);
        }
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    public static View gap(Context c, float h) {
        return Ui.spacer(c, h);
    }

    public static LinearLayout columnWrap(Context c, boolean scrollable) {
        LinearLayout l = Ui.column(c);
        return l;
    }
}
