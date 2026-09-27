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

/**
 * 配色方案（UI 风格）。
 *
 * 每套方案都是「一组语义色」，界面所有颜色都从这里取，
 * 切换风格时只需要重建视图，不需要改任何布局逻辑。
 */
public final class Palette {

    public final String id;
    public final String name;
    public final boolean light;

    /** 页面底色 */
    public final int bg;
    /** 面板 / 弹层底色 */
    public final int surface;
    /** 普通卡片底色 */
    public final int card;
    /** 卡片描边（0 表示不描边） */
    public final int cardBorder;

    public final int text;
    public final int textDim;
    public final int textFaint;

    /** 主强调色 */
    public final int accent;
    /** 强调色上的文字色 */
    public final int onAccent;
    /** 强调色的浅底（用于「下一节课」这类弱高亮） */
    public final int accentSoft;

    /** 小标签（chip）底色与文字 */
    public final int chip;
    public final int chipText;

    public final int divider;

    private Palette(String id, String name, boolean light,
                    int bg, int surface, int card, int cardBorder,
                    int text, int textDim, int textFaint,
                    int accent, int onAccent, int accentSoft,
                    int chip, int chipText, int divider) {
        this.id = id;
        this.name = name;
        this.light = light;
        this.bg = bg;
        this.surface = surface;
        this.card = card;
        this.cardBorder = cardBorder;
        this.text = text;
        this.textDim = textDim;
        this.textFaint = textFaint;
        this.accent = accent;
        this.onAccent = onAccent;
        this.accentSoft = accentSoft;
        this.chip = chip;
        this.chipText = chipText;
        this.divider = divider;
    }

    /** 给颜色加透明度，f = 0..1 */
    public static int a(int color, float f) {
        int alpha = Math.round(255 * Math.max(0f, Math.min(1f, f)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** 混合两个颜色，t = 0 返回 a，t = 1 返回 b */
    public static int mix(int a, int b, float t) {
        float u = 1f - Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (((int) (aa * u + ba * t)) << 24)
                | (((int) (ar * u + br * t)) << 16)
                | (((int) (ag * u + bg * t)) << 8)
                | ((int) (ab * u + bb * t));
    }

    /** 每门课左侧色条：按课程名散列出一个色相，再按明暗主题调饱和度 */
    public int barColor(String key) {
        if (key == null) {
            key = "";
        }
        float hue = Math.abs(key.hashCode() % 360);
        float sat = light ? 0.42f : 0.50f;
        float val = light ? 0.78f : 1.00f;
        return android.graphics.Color.HSVToColor(new float[]{hue, sat, val});
    }

    public static final Palette INK = new Palette(
            "ink", "极夜黑", false,
            0xFF0A0A0C, 0xFF141418, 0xFF1C1C22, 0x00000000,
            0xFFF3F3F6, 0xFF9A9AA4, 0xFF6A6A74,
            0xFF5B8DEF, 0xFFFFFFFF, 0xFF1A2740,
            0xFF26262E, 0xFFBFC0CC, 0xFF26262E);

    public static final Palette KLEIN = new Palette(
            "klein", "克莱因蓝", false,
            0xFF060D1C, 0xFF0D1830, 0xFF13203A, 0x00000000,
            0xFFE9F1FF, 0xFF8AA0C6, 0xFF5E7396,
            0xFF4C86FF, 0xFFFFFFFF, 0xFF152A4E,
            0xFF1B2B4A, 0xFFA8BFE4, 0xFF1B2B4A);

    public static final Palette CLAUDE = new Palette(
            "claude", "Claude 橙白", true,
            0xFFF0EEE6, 0xFFFAF9F5, 0xFFFFFFFF, 0xFFE7E3D9,
            0xFF1F1E1B, 0xFF78756C, 0xFFA39F94,
            0xFFD97757, 0xFFFFFFFF, 0xFFF8ECE6,
            0xFFEDE9DF, 0xFF5F5C53, 0xFFE7E3D9);

    public static final Palette FOREST = new Palette(
            "forest", "森野绿", true,
            0xFFEDF3EC, 0xFFF8FBF7, 0xFFFFFFFF, 0xFFDDE8DC,
            0xFF17251A, 0xFF6B7A6E, 0xFF9AAA9E,
            0xFF3F8F5B, 0xFFFFFFFF, 0xFFE6F1E9,
            0xFFE4EBE3, 0xFF4F5C52, 0xFFDDE8DC);

    public static final Palette MORANDI = new Palette(
            "morandi", "莫兰迪紫", true,
            0xFFF2EFF5, 0xFFFAF8FC, 0xFFFFFFFF, 0xFFE6E0ED,
            0xFF241F2C, 0xFF77717F, 0xFFA59EAE,
            0xFF8B7BA8, 0xFFFFFFFF, 0xFFEFE9F5,
            0xFFEAE4F0, 0xFF584F66, 0xFFE6E0ED);

    public static final Palette[] ALL = {INK, KLEIN, CLAUDE, FOREST, MORANDI};

    public static Palette byId(String id) {
        for (Palette p : ALL) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return INK;
    }
}
