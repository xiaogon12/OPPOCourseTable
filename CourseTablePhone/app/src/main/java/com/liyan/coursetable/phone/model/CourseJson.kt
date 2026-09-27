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
package com.liyan.coursetable.phone.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 解析失败时抛出，带出行号 / 列号，方便直接在粘贴框里定位。 */
class JsonParseException(
    val detail: String,
    val line: Int = 0,
    val column: Int = 0,
    val snippet: String = "",
) : Exception(detail)

/** 一次解析的完整结果。 */
data class ParsedTable(
    val tableName: String,
    val courses: List<Course>,
    /** JSON 里带了设置就是「重置后覆盖」的完整设置；没带则为 null，表示沿用手机上的现有设置。 */
    val settings: Settings?,
    val skipped: Int,
    val warnings: List<String>,
    /** 规范化后的 JSON，用于保存 / 推送 */
    val normalizedJson: String,
)

/**
 * `course.json` 的解析与导出。
 *
 * 目标是与手表端 `Store.java` **行为一致**：
 *  - 规范字段优先，同时容忍一批别名；
 *  - `day = 0` / `from = 0` 这类「没有固定排课」的条目直接跳过，不塞进周一；
 *  - 导入设置时先 `resetTimeSettings()` 再按 JSON 里明确给出的字段覆盖；
 *  - `periodTimes` 可以反推节数 / 每节时长 / 课间。
 */
object CourseJson {

    // ------------------------------------------------------------ 入口

    /**
     * 解析用户粘贴的文本。
     *
     * @param base 手机上当前的设置，用于「JSON 里没写设置」时兜底。
     * @param applySettings 是否为「用户主动导入」。
     *   true = 设置先整体回默认再被 JSON 覆盖（与手表端导入行为一致）；
     *   false = 只取课程，完全不动设置（启动时从本地文件恢复用这个）。
     */
    @Throws(JsonParseException::class)
    fun parse(rawInput: String, base: Settings, applySettings: Boolean = true): ParsedTable {
        val raw = rawInput.trim()
        if (raw.isEmpty()) throw JsonParseException("内容为空，没东西可解析")

        val warnings = mutableListOf<String>()
        val text = sanitize(raw, warnings)

        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            // 常见于 AI 输出里混进全角标点或尾随逗号，尝试修一次
            val fixed = repair(text)
            if (fixed != text) {
                try {
                    val r = JSONObject(fixed)
                    warnings += "已自动修正全角标点 / 多余逗号"
                    r
                } catch (_: JSONException) {
                    throw locate(raw, e)
                }
            } else {
                throw locate(raw, e)
            }
        }

        // 允许再包一层：{"table": {...}, "settings": {...}}
        val outer = root.optJSONObject("table") ?: root.optJSONObject("data") ?: root

        val coursesArr = arr(outer, "courses", "lessons", "课程")
            ?: throw JsonParseException("找不到 courses 数组（顶层键有：${root.keys().asSequence().joinToString(", ")}）")

        val settings = if (applySettings) buildSettings(outer, root, base, warnings) else null

        val totalPeriods = (settings ?: base).totalPeriods
        val totalWeeks = (settings ?: base).totalWeeks
        val timeTable = TimeTable(settings ?: base)

        val courses = mutableListOf<Course>()
        var skipped = 0
        var badDay = 0
        var badPeriod = 0

        for (i in 0 until coursesArr.length()) {
            val o = coursesArr.optJSONObject(i)
            if (o == null) {
                skipped++
                continue
            }
            val name = str(o, "name", "n", "课程名", "课程名称", "title")?.trim().orEmpty()
            if (name.isEmpty()) {
                skipped++
                continue
            }

            val day = int(o, "day", "dayOfWeek", "d", "星期", "weekday")
            if (day != null && day !in 1..7) {
                // 与手表端一致：day = 0 表示没有固定排课（网课 / 实践课），跳过而不是硬塞周一
                badDay++
                skipped++
                continue
            }
            var from = int(o, "from", "startPeriod", "sp", "起始节")
            if (from != null && from < 1) {
                badPeriod++
                skipped++
                continue
            }
            var to = int(o, "to", "endPeriod", "ep", "结束节")
            if (to != null && to < 1) to = null

            if (from == null) {
                // 没给节次就按开始时间反查，与手表端 Store.nearestPeriod 一致
                val m = parseHhmm(str(o, "startTime", "开始时间"))
                from = if (m >= 0) nearestPeriod(timeTable, m, useStart = true) else 1
            }
            if (to == null) {
                val m = parseHhmm(str(o, "endTime", "结束时间"))
                to = if (m >= 0) nearestPeriod(timeTable, m - 1, useStart = false) else from
            }
            val f = (from ?: 1).coerceAtLeast(1)
            var t = (to ?: f).coerceAtLeast(f)

            val weeks = parseWeeks(o, warnings, name)
            val maxW = weeks.maxOrNull()
            if (maxW != null && maxW > totalWeeks) {
                warnings += "「$name」的周次到第 $maxW 周，超过总周数 $totalWeeks"
            }
            if (t > totalPeriods) {
                warnings += "「$name」的结束节次 $t 超过总节数 $totalPeriods，手表中会被截断"
                t = totalPeriods.coerceAtLeast(f)
            }

            courses += Course(
                name = name,
                teacher = str(o, "teacher", "t", "老师", "教师", "任课教师")?.trim().orEmpty(),
                room = str(o, "room", "r", "教室", "地点", "location")?.trim().orEmpty(),
                className = str(o, "class", "className", "c", "班级")?.trim().orEmpty(),
                day = day ?: 1,
                from = f,
                to = t,
                weeks = weeks,
            )
        }

        if (badDay > 0) warnings += "$badDay 条没有固定上课星期（day 为 0 或超出 1~7），已跳过"
        if (badPeriod > 0) warnings += "$badPeriod 条没有固定上课节次（from 小于 1），已跳过"
        if (courses.isEmpty()) {
            throw JsonParseException("courses 里没有解析出任何课程，检查 name / day / from / to 字段")
        }

        // 同一时间撞课
        val clash = mutableListOf<String>()
        val grouped = courses.groupBy { it.day }
        for ((day, list) in grouped) {
            val sorted = list.sortedBy { it.from }
            for (k in 0 until sorted.size - 1) {
                val a = sorted[k]
                val b = sorted[k + 1]
                if (b.from <= a.to) clash += "${WEEKDAY_FULL.getOrElse(day - 1) { "周$day" }}「${a.name}」与「${b.name}」"
            }
        }
        if (clash.isNotEmpty()) {
            warnings += "有课程时间重叠：" + clash.take(3).joinToString("、") +
                if (clash.size > 3) " 等 ${clash.size} 处" else ""
        }

        val table = CourseTable(settings ?: base, courses)
        return ParsedTable(
            tableName = settings?.tableName?.takeIf { it.isNotBlank() }
                ?: str(outer, "name", "tableName", "课表名")?.trim().orEmpty(),
            courses = courses,
            settings = settings,
            skipped = skipped,
            warnings = warnings.distinct(),
            normalizedJson = exportJson(table),
        )
    }

    // ------------------------------------------------------------ 设置

    /** 只有 JSON 里确实带了设置字段时才返回非 null。 */
    private fun buildSettings(
        outer: JSONObject,
        root: JSONObject,
        base: Settings,
        warnings: MutableList<String>,
    ): Settings? {
        val settingKeys = listOf(
            "startDate", "start_date", "开始日期", "name", "tableName", "课表名",
            "totalWeeks", "weeksTotal", "总周数", "dt", "periodMinutes", "每节时长",
            "breakMinutes", "课间", "bigBreakMinutes", "大课间",
            "pc", "periodCounts", "periodCount", "节数",
            "ps", "periodStarts", "sectionStart", "首节时间",
            "periodTimes", "times", "每节时间",
        )
        val hasAny = settingKeys.any { outer.has(it) } ||
            outer.has("settings") || root.has("settings")
        if (!hasAny) return null

        // 手表端每次导入都会先把时间设置整体回默认（防止上一次导入的残留值作祟）。
        // 手机端再收紧一点：只有 JSON 里**真的带了作息时间配置**时才回默认，
        // 否则用户手调的作息会被一份只写了课程名的 JSON 悄悄清掉。
        val timeKeys = listOf(
            "pc", "periodCounts", "periodCount", "节数",
            "ps", "periodStarts", "sectionStart", "首节时间",
            "periodTimes", "times", "每节时间",
            "dt", "periodMinutes", "每节时长",
            "breakMinutes", "课间", "bigBreakMinutes", "大课间",
        )
        val hasTimeConfig = timeKeys.any { outer.has(it) }
        var s = if (hasTimeConfig) base.resetTimeSettings() else base

        str(outer, "startDate", "start_date", "开始日期")?.let {
            s = s.copy(startDate = it.trim())
        }
        str(outer, "name", "tableName", "课表名")?.let {
            s = s.copy(tableName = it.trim())
        }
        int(outer, "totalWeeks", "weeksTotal", "总周数")?.takeIf { it > 0 }?.let {
            s = s.copy(totalWeeks = it)
        }
        int(outer, "dt", "periodMinutes", "每节时长")?.takeIf { it > 0 }?.let {
            s = s.copy(periodMinutes = it, sameLength = true)
        }
        int(outer, "breakMinutes", "课间")?.takeIf { it >= 0 }?.let {
            s = s.copy(breakMinutes = it)
        }
        int(outer, "bigBreakMinutes", "大课间")?.takeIf { it >= 0 }?.let {
            s = s.copy(bigBreakMinutes = it)
        }

        val pc = arr(outer, "pc", "periodCounts", "periodCount", "节数")
        val ps = arr(outer, "ps", "periodStarts", "sectionStart", "首节时间")
        val pt = arr(outer, "periodTimes", "times", "每节时间")

        var times: IntArray? = null
        if (pt != null && pt.length() > 0) {
            times = parsePeriodTimes(pt)
            if (times != null && times.size >= 2) {
                if (pc == null || pc.length() < 3) {
                    s = s.copy(periodCount = inferCounts(times).toList())
                }
                s = fitFromPeriodTimes(s, times)
            }
        }
        if (pc != null && pc.length() >= 3) {
            s = s.copy(
                periodCount = listOf(
                    pc.optInt(0, 4).coerceAtLeast(0),
                    pc.optInt(1, 4).coerceAtLeast(0),
                    pc.optInt(2, 0).coerceAtLeast(0),
                ),
            )
        }
        if (ps != null && ps.length() >= 3) {
            val starts = s.sectionStart.toMutableList()
            for (i in 0..2) {
                val m = parseHhmm(ps.optString(i, ""))
                if (m >= 0) starts[i] = m
            }
            s = s.copy(sectionStart = starts)
        } else if (times != null && pc != null && pc.length() >= 3) {
            val starts = s.sectionStart.toMutableList()
            starts[0] = times[0]
            val i1 = s.periodCountAt(0)
            if (i1 * 2 < times.size) starts[1] = times[i1 * 2]
            val i2 = i1 + s.periodCountAt(1)
            if (i2 * 2 < times.size) starts[2] = times[i2 * 2]
            s = s.copy(sectionStart = starts)
        }

        // 手机端额外带的设置（手表端后续版本会读，旧版本忽略）
        outer.optJSONObject("settings")?.let { o ->
            int(o, "weekOffset", "周次修正")?.let { s = s.copy(weekOffset = it) }
            bool(o, "sameLength", "节时长一致")?.let { s = s.copy(sameLength = it) }
            bool(o, "reminderOn", "提醒开关")?.let { s = s.copy(reminderOn = it) }
            int(o, "reminderMinutes", "提前分钟")?.let { s = s.copy(reminderMinutes = it) }
            str(o, "themeId", "主题")?.takeIf { it.isNotBlank() }?.let { s = s.copy(themeId = it) }
            arr(o, "perPeriodMinutes", "各节时长")?.let { a ->
                val list = (0 until a.length()).mapNotNull { a.optInt(it, -1).takeIf { v -> v > 0 } }
                if (list.isNotEmpty()) s = s.copy(perPeriodMinutes = list)
            }
        }

        s = s.ensurePerPeriod()

        if (s.startDate.isNotBlank() && parseDate(s.startDate) == null) {
            warnings += "startDate「${s.startDate}」不是合法日期，请按 yyyy-MM-dd 填写"
        } else if (s.startDate.isNotBlank() && !s.startDateIsMonday) {
            warnings += "startDate「${s.startDate}」不是周一，周次计算会整体偏移"
        }
        if (s.totalPeriods <= 0) {
            warnings += "节数为 0，课表会是空的"
        }
        return s
    }

    // ------------------------------------------------------------ 导出

    /** 与手表端 `Store.exportJson()` 同构的规范 course.json。 */
    fun exportJson(table: CourseTable): String {
        val s = table.settings
        return try {
            val root = JSONObject()
            root.put("name", s.tableName)
            root.put("startDate", s.startDate)
            root.put("totalWeeks", s.totalWeeks)
            root.put("pc", JSONArray(s.periodCount))
            root.put("ps", JSONArray(s.periodCount.indices.map { hhmm(s.sectionStartAt(it)) }))
            root.put("dt", s.periodMinutes)
            root.put("breakMinutes", s.breakMinutes)
            root.put("bigBreakMinutes", s.bigBreakMinutes)

            val tt = TimeTable(s)
            val pt = JSONArray()
            for (p in 1..tt.count) {
                pt.put(JSONObject().apply {
                    put("period", p)
                    put("start", hhmm(tt.start(p)))
                    put("end", hhmm(tt.end(p)))
                })
            }
            root.put("periodTimes", pt)

            val cs = JSONArray()
            for (c in table.courses) {
                cs.put(JSONObject().apply {
                    put("name", c.name)
                    put("teacher", c.teacher)
                    put("room", c.room)
                    put("class", c.className)
                    put("day", c.day)
                    put("from", c.from)
                    put("to", c.to)
                    put("weeks", JSONArray(c.weeks))
                })
            }
            root.put("courses", cs)
            root.toString(2)
        } catch (e: Exception) {
            "{}"
        }
    }

    /**
     * 推送给手表的完整负载。
     *
     * 顶层就是规范 `course.json`（旧版手表端只读顶层，天然兼容），
     * 额外挂一个 `settings` 对象承载课表之外的设置项（周次修正 / 提醒 / 主题等）。
     *
     * `_via` / `_license` 是出处水印：数据流本身带着项目来源，
     * 抹掉界面上署名也没法把这份数据说成自己的。手表端按字符串键精确取值，
     * 这两个字段会被忽略，不影响导入。
     */
    fun buildPayload(table: CourseTable): String {
        val s = table.settings
        val root = JSONObject(exportJson(table))
        root.put("protocol", PROTOCOL)
        root.put(
            "generatedAt",
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        )
        root.put("_via", VIA)
        root.put("_license", LICENSE_NAME)
        root.put("settings", JSONObject().apply {
            put("weekOffset", s.weekOffset)
            put("sameLength", s.sameLength)
            put("perPeriodMinutes", JSONArray(s.ensurePerPeriod().perPeriodMinutes))
            put("reminderOn", s.reminderOn)
            put("reminderMinutes", s.reminderMinutes)
            put("themeId", s.themeId)
        })
        return root.toString(2)
    }

    const val PROTOCOL = 1

    /** 出处水印，见 [buildPayload] */
    const val VIA = "xiaogon12/OPPOCourseTable"
    const val LICENSE_NAME = "CC BY-NC-SA 4.0"

    // ------------------------------------------------------------ 解析细节

    /** 去掉 ``` 围栏、前后废话，只留最外层 JSON。 */
    private fun sanitize(raw: String, warnings: MutableList<String>): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            t = t.substringAfter('\n', t)
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
            t = t.trim()
            warnings += "已去掉 Markdown 代码围栏"
        }
        val first = t.indexOf('{')
        val last = t.lastIndexOf('}')
        if (first > 0 && last > first) {
            t = t.substring(first, last + 1)
            warnings += "已忽略 JSON 前后的多余文字"
        }
        return t
    }

    /** 全角标点 / 尾随逗号宽松修复，只在首次解析失败后尝试。 */
    private fun repair(t: String): String {
        var s = t
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\uFF0C', ',')
            .replace('\uFF1A', ':')
            .replace('\u3001', ',')
            .replace('\uFF1B', ';')
        s = Regex(",\\s*([}\\]])").replace(s, "$1")
        return s
    }

    private fun locate(text: String, e: JSONException): JsonParseException {
        val msg = e.message ?: "JSON 解析失败"
        val m = Regex("at character (\\d+)").find(msg)
        if (m == null) {
            return JsonParseException(msg.substringBefore(" of ").take(200))
        }
        val pos = (m.groupValues[1].toIntOrNull() ?: 0).coerceIn(0, text.length)
        var line = 1
        var lastNl = -1
        for (i in 0 until pos) {
            if (text[i] == '\n') {
                line++
                lastNl = i
            }
        }
        val col = pos - lastNl
        val lineText = text.split('\n').getOrNull(line - 1)?.trim().orEmpty()
        val friendly = when {
            msg.startsWith("Unterminated") -> "有字符串没有闭合（缺一个结尾引号）"
            msg.contains("End of input") -> "JSON 没有写完，像是被截断了"
            msg.startsWith("Expected") -> "这里缺少预期的符号（逗号、冒号或引号）"
            msg.startsWith("Unterminated array") -> "数组没有闭合（缺 ]）"
            msg.startsWith("Unterminated object") -> "对象没有闭合（缺 }）"
            else -> msg.substringBefore(" at character").take(200)
        }
        return JsonParseException(friendly, line, col, lineText.take(140))
    }

    /** 找一个时间上最接近的节次，对齐手表端 `Store.nearestPeriod` */
    private fun nearestPeriod(tt: TimeTable, minutes: Int, useStart: Boolean): Int {
        var best = 1
        var bestDiff = Int.MAX_VALUE
        for (p in 1..tt.count) {
            val v = if (useStart) tt.start(p) else tt.end(p)
            val diff = kotlin.math.abs(v - minutes)
            if (diff < bestDiff) {
                bestDiff = diff
                best = p
            }
        }
        return best
    }

    private fun parseWeeks(o: JSONObject, warnings: MutableList<String>, name: String): List<Int> {
        val raw = objGet(o, "weeks", "w", "周次")
        when (raw) {
            is JSONArray -> {
                val out = (0 until raw.length())
                    .mapNotNull { i -> toInt(raw.opt(i)) }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()
                return out
            }
            is String -> return parseWeekString(raw)
        }
        val ws = int(o, "weekStart", "startWeek")
        val we = int(o, "weekEnd", "endWeek")
        if (ws != null && ws > 0) {
            val end = (we ?: ws).coerceAtLeast(ws)
            if (end > 60) warnings += "「$name」的周次范围异常（$ws-$end）"
            return (ws..end).toList()
        }
        return emptyList()
    }

    /** "1-8,10,12" -> [1..8,10,12] */
    private fun parseWeekString(s: String): List<Int> {
        val out = mutableListOf<Int>()
        for (part in s.split(Regex("[,，、\\s]+"))) {
            if (part.isEmpty()) continue
            val dash = part.indexOf('-')
            if (dash > 0) {
                val a = part.substring(0, dash).replace(Regex("[^0-9]"), "").toIntOrNull() ?: continue
                val b = part.substring(dash + 1).replace(Regex("[^0-9]"), "").toIntOrNull() ?: continue
                for (i in minOf(a, b)..maxOf(a, b)) out += i
            } else {
                val digits = part.replace(Regex("[^0-9]"), "")
                if (digits.isNotEmpty()) out += digits.toInt()
            }
        }
        return out.distinct().sorted()
    }

    /** periodTimes -> [start1,end1,start2,end2,...] */
    private fun parsePeriodTimes(a: JSONArray): IntArray? {
        val n = a.length()
        val r = IntArray(n * 2)
        var bad = 0
        for (i in 0 until n) {
            var s: String? = null
            var e: String? = null
            when (val v = a.opt(i)) {
                is JSONArray -> {
                    s = v.optString(0, null)
                    e = v.optString(1, null)
                }
                is JSONObject -> {
                    s = str(v, "start", "s", "开始")
                    e = str(v, "end", "e", "结束")
                }
                is String -> {
                    val parts = v.split(Regex("[-~]"))
                    if (parts.size >= 2) {
                        s = parts[0]
                        e = parts[1]
                    }
                }
            }
            val sm = parseHhmm(s)
            val em = parseHhmm(e)
            if (sm < 0 || em < 0) {
                bad++
                r[i * 2] = -1
                r[i * 2 + 1] = -1
            } else {
                r[i * 2] = sm
                r[i * 2 + 1] = em
            }
        }
        return if (bad == n) null else r
    }

    /** 用每节时间反推「各段首节时间 / 每节时长 / 课间 / 大课间」，对齐 Store.fitFromPeriodTimes */
    private fun fitFromPeriodTimes(cfg: Settings, t: IntArray): Settings {
        val n = t.size / 2
        if (n == 0 || t[0] < 0) return cfg
        var s = cfg
        val starts = s.sectionStart.toMutableList()

        starts[0] = t[0]
        val step = s.periodCountAt(0)
        if (step < n && t[step * 2] >= 0) starts[1] = t[step * 2]
        val step2 = step + s.periodCountAt(1)
        if (step2 < n && t[step2 * 2] >= 0) starts[2] = t[step2 * 2]
        s = s.copy(sectionStart = starts)

        val dur = t[1] - t[0]
        if (dur > 0) s = s.copy(periodMinutes = dur, sameLength = true)

        // 课间只在同一段内推算，跨段间隔（午休 / 晚饭）会算出离谱的大课间
        var minGap = Int.MAX_VALUE
        var maxGap = 0
        for (i in 1 until n) {
            if (s.sectionOfPeriod(i + 1) != s.sectionOfPeriod(i)) continue
            if (t[i * 2] < 0 || t[(i - 1) * 2 + 1] < 0) continue
            val gap = t[i * 2] - t[(i - 1) * 2 + 1]
            if (gap < 0) continue
            minGap = minOf(minGap, gap)
            maxGap = maxOf(maxGap, gap)
        }
        if (minGap != Int.MAX_VALUE) {
            s = s.copy(breakMinutes = minGap, bigBreakMinutes = minOf(60, maxOf(maxGap, minGap)))
        }
        return s
    }

    /** 按「间隔突然变大」把节次切成上午 / 下午 / 晚上，对齐 Store.inferCounts */
    private fun inferCounts(t: IntArray): IntArray {
        val n = t.size / 2
        if (n <= 0) return intArrayOf(4, 4, 0)
        val gaps = IntArray(maxOf(0, n - 1))
        for (i in 0 until n - 1) {
            gaps[i] = maxOf(0, t[(i + 1) * 2] - t[i * 2 + 1])
        }
        if (gaps.isEmpty()) return intArrayOf(n, 0, 0)
        val median = gaps.sorted()[gaps.size / 2]
        val counts = IntArray(3)
        var sec = 0
        counts[0] = 1
        for (g in gaps) {
            if (g > median + 5 && sec < 2) sec++
            counts[sec]++
        }
        return counts
    }

    // ------------------------------------------------------------ 取值工具

    private fun objGet(o: JSONObject, vararg keys: String): Any? {
        for (k in keys) {
            if (o.has(k)) {
                val v = o.opt(k)
                return if (v == JSONObject.NULL) null else v
            }
        }
        val lower = keys.map { it.lowercase() }.toSet()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k.lowercase() in lower) {
                val v = o.opt(k)
                return if (v == JSONObject.NULL) null else v
            }
        }
        return null
    }

    private fun str(o: JSONObject, vararg keys: String): String? {
        val v = objGet(o, *keys) ?: return null
        return if (v is String) v else v.toString()
    }

    private fun int(o: JSONObject, vararg keys: String): Int? = toInt(objGet(o, *keys))

    private fun toInt(v: Any?): Int? = when (v) {
        null -> null
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt() ?: v.trim().toIntOrNull()
        else -> null
    }

    private fun bool(o: JSONObject, vararg keys: String): Boolean? = when (val v = objGet(o, *keys)) {
        null -> null
        is Boolean -> v
        is String -> v.equals("true", true) || v == "1"
        is Number -> v.toInt() != 0
        else -> null
    }

    private fun arr(o: JSONObject, vararg keys: String): JSONArray? = objGet(o, *keys) as? JSONArray
}
