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

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 手表端没有的默认值，集中放这里，避免默认参数引用 companion 成员。 */
object Defaults {
    val SECTION_START = listOf(8 * 60, 14 * 60, 19 * 60)
    val PERIOD_COUNT = listOf(4, 4, 0)
    const val TOTAL_WEEKS = 18
    const val PERIOD_MINUTES = 45
    const val BREAK_MINUTES = 10
    const val BIG_BREAK_MINUTES = 20
    const val REMINDER_MINUTES = 20
    const val THEME_ID = "ink"
}

val SECTION_NAMES = listOf("上午", "下午", "晚上")
val WEEKDAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")
val WEEKDAY_FULL = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 一门课。字段与手表端 `Course.java` 及 `course.json` 规范一一对应。
 *
 * `day` 用 1..7 表示周一到周日；手表端约定 `day = 0` 或 `from = 0` 表示「没有固定排课」，
 * 这类条目在解析时被丢弃（不落到 [CourseTable.courses] 里）。
 */
data class Course(
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    val className: String = "",
    val day: Int = 1,
    /** 起始节次，1 起 */
    val from: Int = 1,
    /** 结束节次，含 */
    val to: Int = 1,
    /** 有课的周次；空列表 = 每周都上 */
    val weeks: List<Int> = emptyList(),
) {
    fun hasWeek(week: Int): Boolean = weeks.isEmpty() || weeks.contains(week)

    /** 占用几节课 */
    val span: Int get() = (to - from + 1).coerceAtLeast(1)

    /** 周次的紧凑描述，例如 "1-8,10,12" */
    val weeksLabel: String get() = formatWeeks(weeks)
}

/** [1,2,3,4,8] -> "1-4,8"；空 -> "每周" */
fun formatWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return "每周"
    val sorted = weeks.distinct().sorted()
    val parts = mutableListOf<String>()
    var start = sorted[0]
    var prev = sorted[0]
    for (i in 1 until sorted.size) {
        val v = sorted[i]
        if (v == prev + 1) {
            prev = v
            continue
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        start = v
        prev = v
    }
    parts += if (start == prev) "$start" else "$start-$prev"
    return parts.joinToString(",")
}

/**
 * App 的全部设置项，字段与手表端 `AppConfig.java` 完全对齐。
 * 手机端是这些设置的**编辑端**，最终会随课表一起推给手表。
 */
data class Settings(
    /** 开学第一周的周一，yyyy-MM-dd；空 = 未设置 */
    val startDate: String = "",
    val totalWeeks: Int = Defaults.TOTAL_WEEKS,
    /** 手动周次修正 */
    val weekOffset: Int = 0,
    /** 上午 / 下午 / 晚上 各自的节数 */
    val periodCount: List<Int> = Defaults.PERIOD_COUNT,
    /** 三段各自第一节的开始时间（当天 0 点起的分钟数） */
    val sectionStart: List<Int> = Defaults.SECTION_START,
    val sameLength: Boolean = true,
    val periodMinutes: Int = Defaults.PERIOD_MINUTES,
    /** `sameLength = false` 时按节次顺序使用，长度 = 总节数 */
    val perPeriodMinutes: List<Int> = emptyList(),
    val breakMinutes: Int = Defaults.BREAK_MINUTES,
    val bigBreakMinutes: Int = Defaults.BIG_BREAK_MINUTES,
    val reminderOn: Boolean = true,
    val reminderMinutes: Int = Defaults.REMINDER_MINUTES,
    val themeId: String = Defaults.THEME_ID,
    /** 课表名（导入 JSON 里的 name） */
    val tableName: String = "",
) {
    val totalPeriods: Int get() = periodCount.sum()

    fun periodCountAt(i: Int): Int = periodCount.getOrElse(i) { 0 }

    fun sectionStartAt(i: Int): Int = sectionStart.getOrElse(i) { Defaults.SECTION_START[i] }

    fun sectionFirstPeriod(section: Int): Int {
        var n = 1
        for (i in 0 until section) n += periodCountAt(i)
        return n
    }

    fun sectionOfPeriod(period: Int): Int {
        var acc = 0
        for (s in 0..2) {
            acc += periodCountAt(s)
            if (period <= acc) return s
        }
        return 2
    }

    fun minutesOfPeriod(period: Int): Int {
        if (sameLength) return periodMinutes
        return perPeriodMinutes.getOrNull(period - 1) ?: periodMinutes
    }

    /** 时间设置整体恢复默认。导入 JSON 前调用，避免上一次导入的值残留。 */
    fun resetTimeSettings(): Settings = copy(
        sameLength = true,
        periodMinutes = Defaults.PERIOD_MINUTES,
        breakMinutes = Defaults.BREAK_MINUTES,
        bigBreakMinutes = Defaults.BIG_BREAK_MINUTES,
        periodCount = Defaults.PERIOD_COUNT,
        sectionStart = Defaults.SECTION_START,
        perPeriodMinutes = emptyList(),
    )

    /** 把 perPeriodMinutes 补齐到总节数长度（对齐 AppConfig.ensurePerPeriod） */
    fun ensurePerPeriod(): Settings {
        val total = totalPeriods
        if (perPeriodMinutes.size == total) return this
        val next = List(total) { i ->
            perPeriodMinutes.getOrNull(i)?.takeIf { it > 0 } ?: periodMinutes
        }
        return copy(perPeriodMinutes = next)
    }

    val startDateOrNull: LocalDate? get() = parseDate(startDate)

    /** 某一天属于第几周（含 weekOffset）；未设置开学日期时返回 null */
    fun weekOf(date: LocalDate): Int? {
        val monday = startDateOrNull ?: return null
        val days = ChronoUnit.DAYS.between(monday, date)
        return Math.floorDiv(days, 7L).toInt() + 1 + weekOffset
    }

    /** 第 week 周的周一 */
    fun mondayOfWeek(week: Int): LocalDate? =
        startDateOrNull?.plusWeeks((week - 1).toLong())

    /** 开学日期是否落在周一 */
    val startDateIsMonday: Boolean
        get() = startDateOrNull?.dayOfWeek?.value == 1

    companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
    }
}

/** 分钟数 -> "HH:mm" */
fun hhmm(minutes: Int): String =
    String.format(Locale.CHINA, "%02d:%02d", (minutes / 60) % 24, minutes % 60)

/** "HH:mm" -> 分钟数，失败返回 -1 */
fun parseHhmm(text: String?): Int {
    if (text.isNullOrBlank()) return -1
    val parts = text.trim().split(":")
    if (parts.size < 2) return -1
    val h = parts[0].trim().toIntOrNull() ?: return -1
    val m = parts[1].trim().take(2).toIntOrNull() ?: return -1
    if (h !in 0..23 || m !in 0..59) return -1
    return h * 60 + m
}

fun parseDate(text: String?): LocalDate? {
    if (text.isNullOrBlank()) return null
    val t = text.trim().replace('/', '-').replace('.', '-')
    return try {
        LocalDate.parse(t, Settings.ISO)
    } catch (_: Exception) {
        try {
            val p = t.split("-")
            if (p.size == 3) LocalDate.of(p[0].toInt(), p[1].toInt(), p[2].toInt()) else null
        } catch (_: Exception) {
            null
        }
    }
}

fun formatDate(d: LocalDate): String = d.format(Settings.ISO)

/**
 * 由设置推算出来的整张作息表，与手表端 `TimeTable.java` 算法逐行一致：
 * 段内从首节时间起算，每节之间加课间，每上完 2 节换成大课间。
 */
class TimeTable(private val s: Settings) {

    private val starts: IntArray
    private val ends: IntArray

    init {
        val total = s.totalPeriods.coerceAtLeast(1)
        starts = IntArray(total)
        ends = IntArray(total)
        var period = 1
        for (sec in 0..2) {
            val count = s.periodCountAt(sec)
            var cursor = s.sectionStartAt(sec)
            for (k in 0 until count) {
                if (period > total) break
                val dur = s.minutesOfPeriod(period)
                starts[period - 1] = cursor
                ends[period - 1] = cursor + dur
                val gap = if ((k + 1) % 2 == 0) s.bigBreakMinutes else s.breakMinutes
                cursor = ends[period - 1] + gap
                period++
            }
        }
        // 与手表端一致：未被填充的节次按上一节兜底
        for (i in 0 until total) {
            if (starts[i] == 0 && ends[i] == 0) {
                starts[i] = if (i > 0) ends[i - 1] + s.breakMinutes else s.sectionStartAt(0)
                ends[i] = starts[i] + s.minutesOfPeriod(i + 1)
            }
        }
    }

    val count: Int get() = starts.size

    fun start(period: Int): Int = starts[index(period)]

    fun end(period: Int): Int = ends[index(period)]

    fun range(from: Int, to: Int): String = "${hhmm(start(from))}-${hhmm(end(to))}"

    private fun index(period: Int): Int = (period - 1).coerceIn(0, starts.size - 1)
}

/** 一张课表 = 设置 + 课程列表 */
data class CourseTable(
    val settings: Settings = Settings(),
    val courses: List<Course> = emptyList(),
) {
    fun ofDay(day: Int, week: Int): List<Course> =
        courses.filter { it.day == day && it.hasWeek(week) }
            .sortedWith(compareBy({ it.from }, { it.to }))

    /** 所有课里出现过的周次范围，用于概览 */
    val weekSpan: IntRange?
        get() {
            val all = courses.flatMap { it.weeks }
            return if (all.isEmpty()) null else all.min()..all.max()
        }
}
