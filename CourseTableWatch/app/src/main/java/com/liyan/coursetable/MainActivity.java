package com.liyan.coursetable;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面。
 *
 * 结构：
 *   FrameLayout
 *     ├─ SwipeHost       课表页（下） / 我的页（上，默认收起）
 *     └─ overlay         全屏弹层栈（导入、设置、二维码…）
 *
 * 手势：
 *   左右滑 -> 昨天 / 今天 / 明天
 *   课表页向下滑 -> 拉开「我的」
 *   「我的」向上滑 -> 收回课表
 */
public class MainActivity extends Activity implements Host {

    private static final long REFRESH_MS = 30 * 1000L;
    private static final int REQ_STORAGE = 100;

    private FrameLayout root;
    private SwipeHost swipe;
    private FrameLayout overlay;
    private CoursePage coursePage;
    private MinePage minePage;

    private Palette palette;
    private TimeTable tt;

    private TextView toastView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private final List<SheetFrame> stack = new ArrayList<>();

    // ---------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppConfig cfg = Store.get(this).config;
        palette = Palette.byId(cfg.themeId);
        tt = new TimeTable(cfg);

        // 调试用时间偏移（正常从表盘/菜单启动时没有这个参数，偏移为 0，走真实时间）：
        //   adb shell am force-stop com.liyan.coursetable
        //   adb shell am start -n com.liyan.coursetable/.MainActivity --ei now_offset <分钟>
        if (getIntent() != null) {
            int off = getIntent().getIntExtra("now_offset", 0);
            if (off != 0) {
                Now.setOffsetMinutes(off);
                Log.i("CourseTable", "调试时间偏移 " + off + " 分钟");
            }
        }

        getWindow().setBackgroundDrawable(new ColorDrawable(palette.bg));
        buildUi();

        Reminder.ensureChannel(this);
        Reminder.schedule(this);

        requestStorage();
        handleDebugIntent(getIntent());
    }

    /**
     * 把「直接打开接收页」也做成一个 adb 参数，和上面的 now_offset 一样只服务调试：
     *   adb shell am start -n com.liyan.coursetable/.MainActivity --ez receive true
     *
     * 为什么需要它：这台手表充电时会被 OPPO 的 HeySecurity 全屏悬浮窗盖住，
     * 触摸事件进不来，自动化没法点「从手机接收」。走 am start 就不受悬浮窗影响。
     * 正常从表盘/菜单启动时没有这个参数，流程完全不变。
     */
    private void handleDebugIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("receive", false)) {
            return;
        }
        intent.removeExtra("receive");
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                Sheets.receiveNow(MainActivity.this);
            }
        }, 350);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDebugIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
        Reminder.schedule(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshTask);
    }

    private void tick() {
        if (coursePage != null) {
            coursePage.refresh();
        }
    }

    // ---------------------------------------------------------- 界面搭建

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(palette.bg);

        swipe = new SwipeHost(this);
        coursePage = new CoursePage(this);
        minePage = new MinePage(this);
        swipe.setup(coursePage, minePage, coursePage.scrollView(), minePage.scrollView(),
                createSwipeListener());
        root.addView(swipe, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new FrameLayout(this);
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private SwipeHost.Listener createSwipeListener() {
        return new SwipeHost.Listener() {
            @Override
            public void onShiftDay(int dir) {
                shiftDay(dir);
            }

            @Override
            public boolean canShiftDay(int dir) {
                if (coursePage == null) {
                    return false;
                }
                int next = coursePage.dayOffset() + dir;
                return next >= 0 && next <= 1;
            }

            @Override
            public void onExitSwipe() {
                exitBySwipe();
            }

            @Override
            public void onMineOpened() {
                minePage.refresh();
            }

            @Override
            public void onMineClosed() {
            }
        };
    }

    /** 在「今天」页面继续右滑 = 退出应用（直接退，不做滑出动画，要的就是快） */
    private void exitBySwipe() {
        if (!stack.isEmpty()) {
            closeSheet();
            return;
        }
        if (swipe != null && swipe.isMineOpen()) {
            swipe.openMine(false, true);
            return;
        }
        finish();
    }

    private void rebuildPages() {
        int off = coursePage == null ? 0 : coursePage.dayOffset();
        boolean mineOpen = swipe != null && swipe.isMineOpen();
        root.removeView(swipe);

        swipe = new SwipeHost(this);
        coursePage = new CoursePage(this);
        minePage = new MinePage(this);
        swipe.setup(coursePage, minePage, coursePage.scrollView(), minePage.scrollView(),
                createSwipeListener());
        coursePage.setDayOffset(off);
        root.addView(swipe, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (mineOpen) {
            swipe.openMine(true, false);
        }
    }

    // ---------------------------------------------------------- Host

    @Override
    public Context ctx() {
        return this;
    }

    @Override
    public Palette pal() {
        return palette;
    }

    @Override
    public AppConfig cfg() {
        return Store.get(this).config;
    }

    @Override
    public Store store() {
        return Store.get(this);
    }

    @Override
    public TimeTable timeTable() {
        if (tt == null) {
            tt = new TimeTable(cfg());
        }
        return tt;
    }

    @Override
    public void configChanged(boolean rebuildUi) {
        Store.get(this).config.save(this);
        cfg().ensurePerPeriod();
        tt = new TimeTable(cfg());
        if (rebuildUi) {
            palette = Palette.byId(cfg().themeId);
            getWindow().setBackgroundDrawable(new ColorDrawable(palette.bg));
            root.setBackgroundColor(palette.bg);
            closeAllSheets();
            rebuildPages();
        } else {
            if (coursePage != null) {
                coursePage.refresh();
            }
            if (minePage != null) {
                minePage.refresh();
            }
        }
        if (coursePage != null) {
            coursePage.refresh();
        }
    }

    @Override
    public void shiftDay(int dir) {
        if (coursePage == null) {
            return;
        }
        final int next = coursePage.dayOffset() + dir;
        if (next < 0 || next > 1) {
            return;   // 只有「今天 / 明天」两页
        }
        // 直接切换，不加过渡动画——用户明确要「迅速」，位移+回弹那套有顿挫感
        coursePage.setDayOffset(next);
    }

    @Override
    public void openMine(boolean open) {
        if (swipe != null) {
            swipe.openMine(open, true);
        }
    }

    @Override
    public void toast(String msg) {
        if (msg == null || msg.isEmpty()) {
            return;
        }
        if (toastView != null) {
            root.removeView(toastView);
        }
        toastView = Ui.tv(this, msg, 10.f, palette.onAccent, false);
        toastView.setGravity(Gravity.CENTER);
        toastView.setBackground(Ui.bg(this, Palette.a(palette.accent, 0.96f), 15));
        toastView.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        toastView.setMaxLines(4);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Ui.dp(this, 26);
        lp.leftMargin = Ui.dp(this, 30);
        lp.rightMargin = Ui.dp(this, 30);
        root.addView(toastView, lp);
        toastView.setAlpha(0f);
        toastView.animate().alpha(1f).setDuration(120).withEndAction(new Runnable() {
            @Override
            public void run() {
                final TextView v = toastView;
                if (v == null) {
                    return;
                }
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                root.removeView(v);
                                if (toastView == v) {
                                    toastView = null;
                                }
                            }
                        }).start();
                    }
                }, 2200);
            }
        }).start();
    }

    // ---------------------------------------------------------- 弹层

    /** 只用来标记弹层的 tag 与根 View */
    private static class SheetFrame extends FrameLayout {
        final String tag;

        SheetFrame(Context c, String tag) {
            super(c);
            this.tag = tag;
        }
    }

    @Override
    public void showSheet(String tag, String title, View body) {
        if (!stack.isEmpty() && stack.get(stack.size() - 1).tag.equals(tag)) {
            fillSheet(stack.get(stack.size() - 1), title, body, true);
            return;
        }
        SheetFrame f = new SheetFrame(this, tag);
        fillSheet(f, title, body, true);
        pushSheet(f);
    }

    @Override
    public void showOverlay(String tag, View body) {
        if (!stack.isEmpty() && stack.get(stack.size() - 1).tag.equals(tag)) {
            fillSheet(stack.get(stack.size() - 1), null, body, false);
            return;
        }
        SheetFrame f = new SheetFrame(this, tag);
        fillSheet(f, null, body, false);
        pushSheet(f);
    }

    private void fillSheet(SheetFrame f, String title, View body, boolean chrome) {
        // 原地刷新时先记下滚动位置——否则在弹层底部改个值，整个弹层重建后
        // 会跳回第一行，用户还得重新往下划。
        final int oldScrollY = findScrollY(f);
        f.removeAllViews();
        f.setBackgroundColor(palette.surface);
        f.setClickable(true);

        if (!chrome) {
            f.addView(body, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        LinearLayout col = Ui.column(this);
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (title != null) {
            TextView t = Ui.tv(this, title, 12.5f, palette.text, true);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = Ui.dp(this, 20);
            col.addView(t, tp);
        }

        final ScrollView sv = Ui.scroll(this);
        sv.addView(body);
        if (body instanceof ViewGroup) {
            Ui.blockFocus((ViewGroup) body);
        }
        if (oldScrollY > 0) {
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.scrollTo(0, oldScrollY);
                }
            });
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = Ui.dp(this, 10);
        col.addView(sv, sp);

        LinearLayout btnBox = Ui.row(this);
        btnBox.setGravity(Gravity.CENTER);
        // 只有「这一层弹层下面没有别的弹层」时才是「关闭」。
        // 注意 fillSheet 会在 pushSheet 之前调用，原地刷新时 f 已经在栈里，
        // 所以不能只判断 stack.isEmpty()，否则原地刷新会把「关闭」错标成「返回」。
        boolean isBottom = stack.isEmpty()
                || (stack.size() == 1 && stack.get(0) == f);
        TextView close = Ui.tv(this, isBottom ? "关闭" : "返回", 11, palette.text, false);
        close.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6));
        close.setBackground(Ui.bg(this, Palette.a(palette.textFaint, 0.16f), 16));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
            }
        });
        Ui.pressable(close);
        btnBox.addView(close);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(this, 8);
        bp.bottomMargin = Ui.dp(this, 12);
        col.addView(btnBox, bp);

        f.addView(col);
    }

    /** 在弹层里找第一个 ScrollView 的滚动位置，没有则 0 */
    private static int findScrollY(View v) {
        if (v instanceof ScrollView) {
            return v.getScrollY();
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                int y = findScrollY(vg.getChildAt(i));
                if (y > 0) {
                    return y;
                }
            }
        }
        return 0;
    }

    private void pushSheet(SheetFrame f) {
        stack.add(f);
        overlay.removeAllViews();
        overlay.addView(f, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setVisibility(View.VISIBLE);
        applyOverlayBrightness();
        // 进场：轻微上浮 + 淡入
        f.setTranslationY(Ui.dp(this, 26));
        f.setAlpha(0f);
        f.animate().translationY(0f).alpha(1f).setDuration(170).start();
    }

    @Override
    public void closeSheet() {
        // 弹层关掉就别再占着蓝牙监听端口了
        BtReceive.stopCurrent();
        if (stack.isEmpty()) {
            return;
        }
        final SheetFrame top = stack.remove(stack.size() - 1);
        top.animate().translationY(Ui.dp(this, 26)).alpha(0f).setDuration(130)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        overlay.removeView(top);
                        if (stack.isEmpty()) {
                            overlay.setVisibility(View.GONE);
                        } else {
                            SheetFrame next = stack.get(stack.size() - 1);
                            overlay.removeAllViews();
                            overlay.addView(next, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                        }
                        applyOverlayBrightness();
                    }
                }).start();
    }

    private void closeAllSheets() {
        BtReceive.stopCurrent();
        stack.clear();
        overlay.removeAllViews();
        overlay.setVisibility(View.GONE);
        applyOverlayBrightness();
    }

    @Override
    public void onBackPressed() {
        if (!stack.isEmpty()) {
            closeSheet();
        } else if (swipe != null && swipe.isMineOpen()) {
            swipe.openMine(false, true);
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 二维码弹层：屏幕调到最亮并保持常亮，方便手机扫。
     * 「从手机接收」也保持常亮 —— 用户放下手表去点手机的这段时间里，
     * 屏幕一黑就看不到等待状态，也容易被系统当成「不在前台」。
     */
    private void applyOverlayBrightness() {
        String top = stack.isEmpty() ? null : stack.get(stack.size() - 1).tag;
        boolean qr = "qr".equals(top);
        boolean keep = qr || Sheets.TAG_RECEIVE.equals(top);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = qr ? 1.0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        if (keep) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------------------------------------------------------- 存储权限

    private void requestStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        // 日历权限：课前提醒靠它写进系统日历（息屏也能响）
        if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_CALENDAR);
            need.add(Manifest.permission.WRITE_CALENDAR);
        }
        if (need.isEmpty()) {
            return;
        }
        try {
            requestPermissions(need.toArray(new String[0]), REQ_STORAGE);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void requestCalendar() {
        if (CalendarSync.hasPermission(this)) {
            int n = CalendarSync.syncNow(this);
            toast(n >= 0 ? "已写入 " + n + " 条课程提醒到系统日历" : "同步失败：找不到可写的日历");
            return;
        }
        requestStorage();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_STORAGE) {
            // 权限结果回来后重排提醒：拿到日历权限就改走系统日历
            Reminder.schedule(this);
        }
    }
}
