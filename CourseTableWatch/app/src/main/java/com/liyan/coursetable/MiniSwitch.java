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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 手表上用的迷你开关（自绘，避免系统 Switch 在圆屏上过大） */
public class MiniSwitch extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int trackOn = 0xFF5B8DEF;
    private int trackOff = 0xFF3A3A42;
    private int knob = 0xFFFFFFFF;

    private boolean checked;
    private float anim;

    public MiniSwitch(Context c) {
        super(c);
    }

    public void setColors(int on, int off, int knobColor) {
        trackOn = on;
        trackOff = off;
        knob = knobColor;
        invalidate();
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean v) {
        setChecked(v, true);
    }

    public void setChecked(boolean v, boolean animate) {
        if (checked == v && anim == (v ? 1f : 0f)) {
            return;
        }
        checked = v;
        if (!animate) {
            anim = v ? 1f : 0f;
            invalidate();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(anim, v ? 1f : 0f);
        a.setDuration(140);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                anim = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float r = h / 2f;
        int trackColor = Palette.mix(trackOff, trackOn, anim);
        paint.setColor(trackColor);
        canvas.drawRoundRect(0, 0, w, h, r, r, paint);

        float knobR = r - Ui.dp(getContext(), 1.5f);
        float cx = knobR + Ui.dp(getContext(), 1.5f) + anim * (w - 2 * knobR - Ui.dp(getContext(), 3));
        paint.setColor(knob);
        canvas.drawCircle(cx, h / 2f, knobR, paint);
    }
}
