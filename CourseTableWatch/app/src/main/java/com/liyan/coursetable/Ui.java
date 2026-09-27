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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 界面小工具：圆角背景、文本、间距 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable bg(Context c, int color, float radiusDp) {
        return bg(c, color, radiusDp, 0, 0);
    }

    public static GradientDrawable bg(Context c, int color, float radiusDp,
                                      float strokeDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) {
            d.setStroke(dp(c, strokeDp), strokeColor);
        }
        return d;
    }

    /** 渐变圆角背景（高亮卡片用，比纯色更有质感） */
    public static GradientDrawable gradientBg(Context c, int colorFrom, int colorTo, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{colorFrom, colorTo});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static TextView tv(Context c, CharSequence text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        t.setIncludeFontPadding(false);
        return t;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View spacer(Context c, float hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, hDp)));
        return v;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpw(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    /**
     * 统一的滚动容器。
     *
     * 关键点：手表上必须让 ScrollView 自己吃焦点、并屏蔽子控件抢焦点，
     * 否则某个可点击子项拿到焦点时会把列表滚到那个位置（表现为「自己滚到底」）。
     * 这是 Phase 1 就在旧布局里踩过的坑。
     */
    public static android.widget.ScrollView scroll(Context c) {
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFocusable(true);
        sv.setFocusableInTouchMode(true);
        sv.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        return sv;
    }

    /** 让容器里的可点击子控件不参与焦点竞争 */
    public static void blockFocus(ViewGroup vg) {
        vg.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        vg.setFocusable(false);
    }

    /** 让一个 View 有点按反馈（在手表上表现为轻微缩放） */
    public static void pressable(final View v) {
        v.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.955f).scaleY(0.955f).setDuration(70).start();
                        break;
                    case android.view.MotionEvent.ACTION_CANCEL:
                    case android.view.MotionEvent.ACTION_UP:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(180)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                                .start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** 顶部文字（给圆形表盘留边距） */
    public static TextView caption(Context c, CharSequence text, float sp, int color) {
        TextView t = tv(c, text, sp, color, false);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }
}
