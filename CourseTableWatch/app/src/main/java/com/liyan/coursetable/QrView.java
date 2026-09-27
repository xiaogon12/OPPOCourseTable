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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * 二维码绘制。
 *
 * 资源图片是「1 像素 = 1 模块」的无损位图，这里按整数倍放大绘制
 * （关闭抗锯齿与插值），这样在方形像素的手表屏上边缘绝对锐利，
 * 手机才好扫。四周自动补 2 个模块的静默区。
 */
public class QrView extends View {

    private static final int QUIET = 2;

    private Bitmap src;
    private int modules;
    private boolean roundSafe = true;
    private final Paint paint = new Paint();
    private final Paint white = new Paint();

    public QrView(Context context) {
        this(context, R.drawable.qr_download);
    }

    public QrView(Context context, int resId) {
        super(context);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
        white.setColor(0xFFFFFFFF);
        white.setStyle(Paint.Style.FILL);
        load(resId);
    }

    /** 圆屏时把可用区域限制在外接圆的内接正方形内，否则四角会被圆弧裁掉 */
    public void setRoundSafe(boolean v) {
        roundSafe = v;
        invalidate();
    }

    private void load(int resId) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            src = BitmapFactory.decodeResource(getResources(), resId, o);
            if (src != null) {
                modules = src.getWidth();
            }
        } catch (Exception ignored) {
        }
    }

    public int modules() {
        return modules;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (src == null || modules <= 0) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        int avail = Math.min(w, h);
        if (roundSafe) {
            // 圆的内接正方形边长 = 直径 / sqrt(2) ≈ 329px（466 直径）
            avail = Math.round(avail / 1.4142f);
        }

        // 选最大的整数倍缩放，同时保证四周留出静默区（手机好识别）
        int scale = 1;
        for (int s = 12; s >= 1; s--) {
            int quiet = Math.max(3, s / 2);
            if (s * modules + 2 * quiet <= avail) {
                scale = s;
                break;
            }
        }
        int quiet = Math.max(3, scale / 2);
        int size = modules * scale + 2 * quiet;
        int left = (w - size) / 2;
        int top = (h - size) / 2;

        canvas.drawRect(left, top, left + size, top + size, white);
        Rect dst = new Rect(left + quiet, top + quiet,
                left + quiet + modules * scale, top + quiet + modules * scale);
        canvas.drawBitmap(src, new Rect(0, 0, modules, modules), dst, paint);
    }
}
