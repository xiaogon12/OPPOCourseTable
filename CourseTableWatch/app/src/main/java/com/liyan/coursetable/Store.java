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
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 课程 + 设置的总仓库，同时负责 JSON 的解析与落盘。
 *
 * 课表数据只有一个来源：<b>手机端 App 通过蓝牙推过来</b>
 * （见 {@link BtReceive}）。手表上不再有「拷 course.json 进来再导入」那条老路。
 *
 * 落盘位置：
 *   1. App 内部目录 /files/course.json           <- 唯一事实源，重启后从这里恢复
 *   2. /sdcard/CourseTableWatch/course.json      <- 同步写一份，方便导出 / 排查
 *   3. <外部私有目录>/course.json                 <- 没有存储权限时的备选
 */
public final class Store {

    private static final String TAG = "CourseTable";
    public static final String DIR_NAME = "CourseTableWatch";
    public static final String FILE_NAME = "course.json";

    private static Store instance;

    public AppConfig config;
    public final List<Course> courses = new ArrayList<>();
    /** 课程数据来源描述，用于「我的」里显示 */
    public String source = "内置示例";
    public boolean imported = false;

    private Store() {
    }

    public static synchronized Store get(Context ctx) {
        if (instance == null) {
            instance = new Store();
            instance.config = AppConfig.load(ctx);
            instance.loadCourses(ctx);
        }
        return instance;
    }

    // ---------------------------------------------------------- 路径

    /** 推荐的导入目录：/sdcard/CourseTableWatch */
    public static File sharedDir() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                return new File(ext, DIR_NAME);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static File sharedFile() {
        File d = sharedDir();
        return d == null ? null : new File(d, FILE_NAME);
    }

    public static File appExtFile(Context ctx) {
        File d = ctx.getExternalFilesDir(null);
        return d == null ? null : new File(d, FILE_NAME);
    }

    public static File internalFile(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    // ---------------------------------------------------------- 课程查询

    /** 某一天、某一周的课程，按开始节次升序 */
    public List<Course> ofDay(int dayOfWeek, int week) {
        return ofDayOf(snapshot(), dayOfWeek, week);
    }

    /**
     * 课表快照。
     *
     * <p>课表只在主线程被整体替换（蓝牙推送），后台线程（日历同步）读的时候
     * 拿一份副本，避免读到一半列表被换掉。
     */
    public List<Course> snapshot() {
        return new ArrayList<>(courses);
    }

    /** 在给定快照上按天查询（遍历副本，不碰实时的 {@link #courses}） */
    public List<Course> ofDayOf(List<Course> snapshot, int dayOfWeek, int week) {
        List<Course> r = new ArrayList<>();
        for (Course c : snapshot) {
            if (c.dayOfWeek == dayOfWeek && c.hasWeek(week)) {
                r.add(c);
            }
        }
        Collections.sort(r, new Comparator<Course>() {
            @Override
            public int compare(Course a, Course b) {
                return (a.startPeriod - b.startPeriod) * 100 + (a.endPeriod - b.endPeriod);
            }
        });
        return r;
    }

    // ---------------------------------------------------------- 落盘

    /** 把当前课程 + 设置写到内部目录 */
    public void saveCourses(Context ctx) {
        try {
            String json = exportJson();
            File f = internalFile(ctx);
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write(json);
            w.close();
            config.save(ctx);
        } catch (Exception e) {
            Log.w(TAG, "保存课程失败: " + e);
        }
    }

    private void loadCourses(Context ctx) {
        // 1) 内部副本
        String json = readFile(internalFile(ctx));
        if (json != null) {
            try {
                applyJson(json, ctx, false);
                imported = true;
                source = "已导入（本地保存）";
                return;
            } catch (Exception e) {
                Log.w(TAG, "内部课程文件损坏: " + e);
            }
        }
        // 2) 内置示例
        try {
            InputStream in = ctx.getResources().openRawResource(R.raw.default_course);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            applyJson(sb.toString(), ctx, false);
            source = "内置示例";
        } catch (Exception e) {
            Log.w(TAG, "读取内置示例失败: " + e);
        }
    }

    // ---------------------------------------------------------- 导入

    /** 导入结果 */
    public static class Result {
        public int count;
        /** 被跳过的条目数（没有排课时间的，例如智慧树网课 day=0 / from=0） */
        public int skipped;
        public String message;
    }
    /** 解析一段 JSON 文本并应用 */
    public Result applyJson(String json, Context ctx, boolean persist) throws Exception {
        JSONObject root = new JSONObject(json);
        AppConfig cfg = config;

        // 只有「用户主动导入」（persist=true）才允许 JSON 改动设置。
        // 启动时从内部副本恢复（persist=false）绝不动设置——
        // SharedPreferences 才是设置的唯一事实源，否则用户重启 App 后
        // 手动改的开始日期 / 周数 / 节数 / 时间会被 JSON 里的旧值冲掉。
        if (persist) {
            // 时间设置全部先回到默认，再由下面 JSON 里明确给出的字段覆盖。
            // 否则上一次导入留下的值会残留（例如大课间 120 → 3-4 节算成 11:40-13:20）。
            cfg.resetTimeSettings();

            String sd = Json.str(root, "startDate", "start_date", "开始日期");
            if (sd != null) {
                cfg.startDate = sd.trim();
            }
            String nm = Json.str(root, "name", "tableName", "课表名");
            if (nm != null) {
                cfg.tableName = nm.trim();
            }
            Integer tw = Json.integer(root, "totalWeeks", "weeksTotal", "总周数");
            if (tw != null && tw > 0) {
                cfg.totalWeeks = tw;
            }
            Integer dt = Json.integer(root, "dt", "periodMinutes", "每节时长");
            if (dt != null && dt > 0) {
                cfg.periodMinutes = dt;
                cfg.sameLength = true;
            }
            Integer brk = Json.integer(root, "breakMinutes", "课间");
            if (brk != null && brk >= 0) {
                cfg.breakMinutes = brk;
            }
            Integer big = Json.integer(root, "bigBreakMinutes", "大课间");
            if (big != null && big >= 0) {
                cfg.bigBreakMinutes = big;
            }

            // 节数 / 各段首节时间
            JSONArray pc = Json.array(root, "pc", "periodCounts", "periodCount", "节数");
            JSONArray ps = Json.array(root, "ps", "periodStarts", "sectionStart", "首节时间");
            JSONArray pt = Json.array(root, "periodTimes", "times", "每节时间");

            int[] times = null;
            if (pt != null && pt.length() > 0) {
                times = parsePeriodTimes(pt);
                if (times != null && times.length >= 2) {
                    if (pc == null || pc.length() < 3) {
                        int[] counts = inferCounts(times);
                        cfg.periodCount = counts;
                    }
                    fitFromPeriodTimes(cfg, times);
                }
            }
            if (pc != null && pc.length() >= 3) {
                cfg.periodCount = new int[]{
                        Math.max(0, pc.optInt(0, 4)),
                        Math.max(0, pc.optInt(1, 4)),
                        Math.max(0, pc.optInt(2, 0))};
            }
            if (ps != null && ps.length() >= 3) {
                for (int i = 0; i < 3; i++) {
                    int m = AppConfig.parseHhmm(ps.optString(i, ""));
                    if (m >= 0) {
                        cfg.sectionStart[i] = m;
                    }
                }
            } else if (times != null && pc != null && pc.length() >= 3) {
                // 只给了每节时间，取各段第一节
                cfg.sectionStart[0] = times[0];
                int i1 = cfg.periodCount[0];
                if (i1 * 2 < times.length) {
                    cfg.sectionStart[1] = times[i1 * 2];
                }
                int i2 = i1 + cfg.periodCount[1];
                if (i2 * 2 < times.length) {
                    cfg.sectionStart[2] = times[i2 * 2];
                }
            }
            cfg.ensurePerPeriod();
        }

        // 课程
        JSONArray cs = Json.array(root, "courses", "lessons", "课程");
        if (cs == null) {
            throw new Exception("找不到 courses 数组");
        }
        TimeTable tt = new TimeTable(cfg);
        List<Course> parsed = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i);
            if (o == null) {
                skipped++;
                continue;
            }
            Course c = parseCourse(o, tt);
            // parseCourse 返回 null = 这门课没有固定排课（如网课 day=0），直接跳过。
            // 不能 clamp 成周一第 1 节，否则会多出一门幽灵课程。
            if (c == null || c.name.isEmpty()) {
                skipped++;
                continue;
            }
            parsed.add(c);
        }
        if (parsed.isEmpty()) {
            throw new Exception("courses 里没有解析出任何课程（检查 name / day / from / to 字段）");
        }

        courses.clear();
        courses.addAll(parsed);
        imported = true;
        source = sharedDir() != null && sharedDir().exists() ? "已导入 " + DIR_NAME + "/" : "已导入";

        if (persist) {
            saveCourses(ctx);
            writeSharedCopy(ctx, json);
        }

        Result r = new Result();
        r.count = courses.size();
        r.skipped = skipped;
        r.message = "导入成功：" + r.count + " 门课"
                + (skipped > 0 ? "（跳过 " + skipped + " 条没有排课时间的）" : "");
        return r;
    }

    /**
     * 从共享目录里的那份副本恢复课表，成功返回门数，失败返回 -1。
     *
     * <p>每次保存都会往 {@code /sdcard/CourseTableWatch/course.json} 回写一份，
     * 所以清数据（{@code pm clear}）、换表之后想快速把课表弄回来时可以用它。
     * 只走 adb，没有界面入口：
     * <pre>adb shell am start -n com.liyan.coursetable/.MainActivity --ez restore true</pre>
     */
    public int restoreFromBackup(Context ctx) {
        File f = sharedFile();
        String json = f == null ? null : readFile(f);
        if (json == null || json.trim().isEmpty()) {
            return -1;
        }
        try {
            // persist=true：连设置一起恢复，等价于一次正常导入
            Result r = applyJson(json, ctx, true);
            return r.count;
        } catch (Exception e) {
            Log.w(TAG, "从副本恢复失败: " + e);
            return -1;
        }
    }

    /** 把 JSON 也留一份到共享目录，方便下次在手表上直接改 */
    private void writeSharedCopy(Context ctx, String json) {        try {
            File dir = sharedDir();
            if (dir == null) {
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(new File(dir, FILE_NAME)), StandardCharsets.UTF_8);
            w.write(json);
            w.close();
        } catch (Exception e) {
            Log.w(TAG, "回写共享目录失败: " + e);
        }
    }
    // ---------------------------------------------------------- JSON 解析细节

    private Course parseCourse(JSONObject o, TimeTable tt) {
        Course c = new Course();
        c.name = nz(Json.str(o, "name", "n", "课程名", "课程名称", "title"));
        c.teacher = nz(Json.str(o, "teacher", "t", "老师", "教师", "任课教师"));
        c.room = nz(Json.str(o, "room", "r", "教室", "地点", "location"));
        c.className = nz(Json.str(o, "class", "className", "c", "班级"));

        Integer day = Json.integer(o, "day", "dayOfWeek", "d", "星期", "weekday");
        // day 给了却不是 1~7（常见的是 `"day": 0` 表示没有固定星期，例如网课/实践课），
        // 这种课没法放到课表上，返回 null 让调用方跳过，而不是硬塞进周一。
        if (day != null && (day < 1 || day > 7)) {
            return null;
        }
        c.dayOfWeek = day == null ? 1 : day;

        Integer from = Json.integer(o, "from", "startPeriod", "sp", "起始节");
        Integer to = Json.integer(o, "to", "endPeriod", "ep", "结束节");
        // 同理：from 给了但不是有效节次（`"from": 0`）说明没有固定上课时间
        if (from != null && from < 1) {
            return null;
        }
        if (to != null && to < 1) {
            to = null;
        }
        if (from == null) {
            // 没给节次，就按开始时间反查
            String st = Json.str(o, "startTime", "开始时间");
            int m = AppConfig.parseHhmm(st);
            if (m >= 0) {
                from = nearestPeriod(tt, m, true);
            }
        }
        if (to == null && from != null) {
            String et = Json.str(o, "endTime", "结束时间");
            int m = AppConfig.parseHhmm(et);
            to = (m >= 0) ? nearestPeriod(tt, m - 1, false) : from;
        }
        c.startPeriod = Math.max(1, from == null ? 1 : from);
        c.endPeriod = Math.max(c.startPeriod, to == null ? c.startPeriod : to);

        c.weeks = parseWeeks(o);
        return c;
    }

    private static int nearestPeriod(TimeTable tt, int minutes, boolean useStart) {
        int best = 1, bestDiff = Integer.MAX_VALUE;
        for (int p = 1; p <= tt.count(); p++) {
            int v = useStart ? tt.start(p) : tt.end(p);
            int diff = Math.abs(v - minutes);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = p;
            }
        }
        return best;
    }

    private static int[] parseWeeks(JSONObject o) {
        Object raw = Json.any(o, "weeks", "w", "周次");
        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            int[] r = new int[a.length()];
            int n = 0;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                int w = toInt(v);
                if (w > 0) {
                    r[n++] = w;
                }
            }
            int[] out = Arrays.copyOf(r, n);
            Arrays.sort(out);
            return out;
        }
        if (raw instanceof String) {
            return parseWeekString((String) raw);
        }
        // weekStart / weekEnd 简化写法
        Integer ws = Json.integer(o, "weekStart", "startWeek");
        Integer we = Json.integer(o, "weekEnd", "endWeek");
        if (ws != null) {
            int end = (we == null) ? ws : we;
            int[] r = new int[Math.max(0, end - ws + 1)];
            for (int i = 0; i < r.length; i++) {
                r[i] = ws + i;
            }
            return r;
        }
        return new int[0];
    }

    /** "1-8,10,12" -> [1..8,10,12] */
    private static int[] parseWeekString(String s) {
        List<Integer> list = new ArrayList<>();
        for (String part : s.split("[,，、\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            int dash = part.indexOf('-');
            try {
                if (dash > 0) {
                    int a = Integer.parseInt(part.substring(0, dash).replaceAll("[^0-9]", ""));
                    int b = Integer.parseInt(part.substring(dash + 1).replaceAll("[^0-9]", ""));
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                        list.add(i);
                    }
                } else {
                    String digits = part.replaceAll("[^0-9]", "");
                    if (!digits.isEmpty()) {
                        list.add(Integer.parseInt(digits));
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        int[] r = new int[list.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = list.get(i);
        }
        Arrays.sort(r);
        return r;
    }

    private static int toInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** periodTimes -> int[]{start1,end1,start2,end2,...} */
    private static int[] parsePeriodTimes(JSONArray a) {
        int n = a.length();
        int[] r = new int[n * 2];
        int bad = 0;
        for (int i = 0; i < n; i++) {
            Object v = a.opt(i);
            String s = null, e = null;
            if (v instanceof JSONArray) {
                JSONArray p = (JSONArray) v;
                s = p.optString(0, null);
                e = p.optString(1, null);
            } else if (v instanceof JSONObject) {
                JSONObject p = (JSONObject) v;
                s = Json.str(p, "start", "s", "开始");
                e = Json.str(p, "end", "e", "结束");
            } else if (v instanceof String) {
                String[] parts = ((String) v).split("[-~]");
                if (parts.length >= 2) {
                    s = parts[0];
                    e = parts[1];
                }
            }
            int sm = AppConfig.parseHhmm(s);
            int em = AppConfig.parseHhmm(e);
            if (sm < 0 || em < 0) {
                bad++;
                r[i * 2] = -1;
                r[i * 2 + 1] = -1;
            } else {
                r[i * 2] = sm;
                r[i * 2 + 1] = em;
            }
        }
        return bad == n ? null : r;
    }

    /** 用每节时间反推「各段首节时间 / 每节时长 / 课间 / 大课间」 */
    private static void fitFromPeriodTimes(AppConfig cfg, int[] t) {
        int n = t.length / 2;
        if (n == 0 || t[0] < 0) {
            return;
        }
        int total = cfg.totalPeriods();
        int step = cfg.periodCount[0];

        cfg.sectionStart[0] = t[0];
        if (step < n && t[step * 2] >= 0) {
            cfg.sectionStart[1] = t[step * 2];
        }
        int step2 = step + cfg.periodCount[1];
        if (step2 < n && t[step2 * 2] >= 0) {
            cfg.sectionStart[2] = t[step2 * 2];
        }

        int dur = t[1] - t[0];
        if (dur > 0) {
            cfg.periodMinutes = dur;
            cfg.sameLength = true;
        }
        // 课间 / 大课间只在「同一段内」推算：跨段间隔（午休、晚饭）动辄 1~2 小时，
        // 算进来就会把大课间错推成 120 分钟。
        int minGap = Integer.MAX_VALUE;
        int maxGapInSection = 0;
        for (int i = 1; i < n; i++) {
            if (cfg.sectionOfPeriod(i + 1) != cfg.sectionOfPeriod(i)) {
                continue;   // 跨段间隔，跳过
            }
            if (t[i * 2] < 0 || t[(i - 1) * 2 + 1] < 0) {
                continue;
            }
            int gap = t[i * 2] - t[(i - 1) * 2 + 1];
            if (gap < 0) {
                continue;
            }
            minGap = Math.min(minGap, gap);
            maxGapInSection = Math.max(maxGapInSection, gap);
        }
        if (minGap != Integer.MAX_VALUE) {
            cfg.breakMinutes = minGap;
            cfg.bigBreakMinutes = Math.min(60, Math.max(maxGapInSection, minGap));
        }
        if (total > 0 && n > total && cfg.periodCount[0] == 4 && cfg.periodCount[2] == 0) {
            // 给出的节次比配置多，按实际数量修正（保守处理，只补足上午/下午）
            cfg.periodCount = new int[]{Math.min(n, 4), Math.max(0, n - 4), 0};
        }
    }

    /** 按「间隔突然变大」把节次切成上午/下午/晚上 */
    private static int[] inferCounts(int[] t) {
        int n = t.length / 2;
        if (n <= 0) {
            return new int[]{4, 4, 0};
        }
        int[] gaps = new int[Math.max(0, n - 1)];
        for (int i = 0; i + 1 < n; i++) {
            int g = t[(i + 1) * 2] - t[i * 2 + 1];
            gaps[i] = Math.max(0, g);
        }
        if (gaps.length == 0) {
            return new int[]{n, 0, 0};
        }
        int[] sorted = gaps.clone();
        Arrays.sort(sorted);
        int median = sorted[sorted.length / 2];
        int[] counts = new int[3];
        int sec = 0;
        counts[0] = 1;
        for (int i = 0; i < gaps.length; i++) {
            if (gaps[i] > median + 5 && sec < 2) {
                sec++;
            }
            counts[sec]++;
        }
        return counts;
    }

    // ---------------------------------------------------------- 导出

    public String exportJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("name", nz(config.tableName));
            root.put("startDate", nz(config.startDate));
            root.put("totalWeeks", config.totalWeeks);
            JSONArray pc = new JSONArray();
            for (int v : config.periodCount) {
                pc.put(v);
            }
            root.put("pc", pc);
            JSONArray ps = new JSONArray();
            for (int v : config.sectionStart) {
                ps.put(AppConfig.hhmm(v));
            }
            root.put("ps", ps);
            root.put("dt", config.periodMinutes);
            root.put("breakMinutes", config.breakMinutes);
            root.put("bigBreakMinutes", config.bigBreakMinutes);

            TimeTable tt = new TimeTable(config);
            JSONArray pt = new JSONArray();
            for (int p = 1; p <= tt.count(); p++) {
                JSONObject o = new JSONObject();
                o.put("period", p);
                o.put("start", TimeTable.hhmm(tt.start(p)));
                o.put("end", TimeTable.hhmm(tt.end(p)));
                pt.put(o);
            }
            root.put("periodTimes", pt);

            JSONArray cs = new JSONArray();
            for (Course c : courses) {
                JSONObject o = new JSONObject();
                o.put("name", nz(c.name));
                o.put("teacher", nz(c.teacher));
                o.put("room", nz(c.room));
                o.put("class", nz(c.className));
                o.put("day", c.dayOfWeek);
                o.put("from", c.startPeriod);
                o.put("to", c.endPeriod);
                JSONArray w = new JSONArray();
                for (int x : c.weeks) {
                    w.put(x);
                }
                o.put("weeks", w);
                cs.put(o);
            }
            root.put("courses", cs);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------- 杂项

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String readFile(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(f);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
