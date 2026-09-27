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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/**
 * 双向滑动容器：
 *   · 左右滑 -> 今天 / 明天
 *   · 课表页在顶部时向下滑 -> 拉开「我的」页面
 *   · 「我的」滚到底之后再向上滑 -> 收回课表（也可以用底部的「课表」把手）
 *
 * 手势判定只用「主导轴」：一开始就往某个方向倾斜的那一轴吃掉整个手势，
 * 另一轴完全不响应，避免和列表滚动打架。
 */
public class SwipeHost extends FrameLayout {

    public interface Listener {
        /** dir = +1 表示「往左滑」（下一天），-1 表示「往右滑」（前一天） */
        void onShiftDay(int dir);

        /** 这个方向还能不能换天；不能时右滑会退化成「退出应用」 */
        boolean canShiftDay(int dir);

        /** 在「今天」页面继续往右滑 = 退出应用 */
        void onExitSwipe();

        void onMineOpened();

        void onMineClosed();
    }

    private static final int AXIS_NONE = 0;
    private static final int AXIS_H = 1;
    private static final int AXIS_V = 2;
    private static final int AXIS_BLOCKED = -1;

    private View panelCourse;
    private View panelMine;
    private ScrollView scrollCourse;
    private ScrollView scrollMine;

    private Listener listener;

    private final int touchSlop;
    private int axis = AXIS_NONE;
    private float downX;
    private float downY;
    private float dxTotal;
    private float startProgress;

    /** 0 = 课表，1 = 我的 */
    private float progress = 0f;
    private boolean animating = false;

    public SwipeHost(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(true);
    }

    public void setup(View course, View mine, ScrollView courseScroll, ScrollView mineScroll, Listener l) {
        this.panelCourse = course;
        this.panelMine = mine;
        this.scrollCourse = courseScroll;
        this.scrollMine = mineScroll;
        this.listener = l;
        addView(course, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(mine, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        apply(0f);
    }

    public boolean isMineOpen() {
        return progress > 0.5f;
    }

    /** 外部（我的页面里的返回按钮）调用 */
    public void openMine(boolean open, boolean animate) {
        setOpen(open, animate);
    }

    // ---------------------------------------------------------- 布局

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        apply(progress);
    }

    private void apply(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        if (panelMine != null) {
            int h = getHeight();
            panelMine.setTranslationY(-h + progress * h);
        }
        if (panelCourse != null) {
            panelCourse.setAlpha(1f - 0.45f * progress);
            panelCourse.setTranslationY(-Ui.dp(getContext(), 12) * progress);
        }
    }

    private void setOpen(boolean open, boolean animate) {
        if (!animate) {
            animating = false;
            apply(open ? 1f : 0f);
            notifyState();
            return;
        }
        animating = true;
        ValueAnimator va = ValueAnimator.ofFloat(progress, open ? 1f : 0f);
        va.setDuration(220);
        va.setInterpolator(new DecelerateInterpolator(1.6f));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                apply(((Float) a.getAnimatedValue()).floatValue());
            }
        });
        va.start();
        animating = false;
        notifyState();
    }

    private boolean notifyTarget = false;

    private void notifyState() {
        boolean open = progress > 0.5f;
        if (open == notifyTarget) {
            return;
        }
        notifyTarget = open;
        if (listener == null) {
            return;
        }
        if (open) {
            listener.onMineOpened();
        } else {
            listener.onMineClosed();
        }
    }

    // ---------------------------------------------------------- 手势

    private boolean canPullDown() {
        return scrollCourse == null || scrollCourse.getScrollY() <= 0;
    }

    /**
     * 只有「我的」已经滚到底、实在没得滚了，上滑才当作「返回课表」。
     * 否则上滑会先被 ScrollView 用来滚动内容——不然「我的」下半部分的设置项
     * 永远划不出来（上滑全被当成返回手势吃掉了）。
     */
    private boolean canPullUp() {
        return scrollMine == null || scrollMine.getScrollY() >= maxScroll(scrollMine) - 2;
    }

    private static int maxScroll(ScrollView sv) {
        if (sv.getChildCount() == 0) {
            return 0;
        }
        View child = sv.getChildAt(0);
        int pad = sv.getPaddingTop() + sv.getPaddingBottom();
        return Math.max(0, child.getMeasuredHeight() + pad - sv.getHeight());
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dxTotal = 0;
                axis = AXIS_NONE;
                animating = false;
                return false;

            case MotionEvent.ACTION_MOVE:
                if (axis == AXIS_BLOCKED) {
                    return false;
                }
                if (axis == AXIS_NONE) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (Math.abs(dx) <= touchSlop && Math.abs(dy) <= touchSlop) {
                        return false;
                    }
                    if (Math.abs(dx) > Math.abs(dy) * 1.15f) {
                        // 横向：只有停在课表页时才换天
                        axis = (progress < 0.02f) ? AXIS_H : AXIS_BLOCKED;
                    } else if (Math.abs(dy) > Math.abs(dx) * 1.15f) {
                        boolean down = dy > 0;
                        if (down && progress < 0.5f && canPullDown()) {
                            axis = AXIS_V;
                        } else if (!down && progress > 0.5f && canPullUp()) {
                            axis = AXIS_V;
                        } else {
                            axis = AXIS_BLOCKED;
                        }
                    }
                }
                if (axis == AXIS_H || axis == AXIS_V) {
                    startProgress = progress;
                    return true;
                }
                return false;

            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (axis == AXIS_H) {
                    dxTotal = ev.getX() - downX;
                } else if (axis == AXIS_V) {
                    float dy = ev.getY() - downY;
                    int h = Math.max(1, getHeight());
                    apply(startProgress + dy / h);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (axis == AXIS_H) {
                    int w = Math.max(1, getWidth());
                    float threshold = Math.max(touchSlop * 2f, w * 0.14f);
                    boolean right = dxTotal >= threshold;
                    boolean left = dxTotal <= -threshold;
                    if (listener != null && right) {
                        // 右滑：能回「今天」就回，已经在「今天」就退出应用
                        if (listener.canShiftDay(-1)) {
                            listener.onShiftDay(-1);
                        } else {
                            listener.onExitSwipe();
                        }
                    } else if (listener != null && left && listener.canShiftDay(1)) {
                        listener.onShiftDay(1);
                    }
                } else if (axis == AXIS_V) {
                    setOpen(progress >= 0.5f, true);
                }
                axis = AXIS_NONE;
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (axis == AXIS_V) {
                    setOpen(progress >= 0.5f, true);
                }
                axis = AXIS_NONE;
                return true;

            default:
                return super.onTouchEvent(ev);
        }
    }
}
