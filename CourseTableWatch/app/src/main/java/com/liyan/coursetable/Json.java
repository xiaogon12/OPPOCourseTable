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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 宽松取字段的小工具：同一个含义允许出现多个键名（短键 / 长键 / 中文键），
 * 这样 AI 生成的 JSON 只要语义对得上就能导入。
 */
public final class Json {

    private Json() {
    }

    public static Object any(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                return o.opt(k);
            }
        }
        return null;
    }

    public static String str(JSONObject o, String... keys) {
        Object v = any(o, keys);
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            return s.isEmpty() ? null : s;
        }
        return String.valueOf(v);
    }

    public static Integer integer(JSONObject o, String... keys) {
        Object v = any(o, keys);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            String s = String.valueOf(v).replaceAll("[^0-9-]", "");
            return s.isEmpty() ? null : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static JSONArray array(JSONObject o, String... keys) {
        Object v = any(o, keys);
        return (v instanceof JSONArray) ? (JSONArray) v : null;
    }
}
