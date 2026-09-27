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
package com.liyan.coursetable.phone.data

import android.content.Context
import com.liyan.coursetable.phone.model.CourseJson
import com.liyan.coursetable.phone.model.CourseTable
import com.liyan.coursetable.phone.model.Defaults
import com.liyan.coursetable.phone.model.Settings
import java.io.File

/**
 * 本地仓库：设置存 SharedPreferences，课程存内部文件 `course.json`。
 *
 * 设置项的 key 与手表端 `AppConfig` 完全同名，方便两边对照排查。
 *
 * 谁是设置的「事实源」取决于文件长什么样，见 [loadTable]：
 * 日常保存写出的是不含 `settings` 的 `exportJson()`，此时 prefs 说了算；
 * 只有从手表那边原件取回来的 `course.json` 才带 `settings`，那种情况
 * 让文件里的设置也生效 —— 也就是一次「从手表恢复」。
 */
class Store(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val courseFile = File(appContext.filesDir, "course.json")

    // ------------------------------------------------------------ 设置

    fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            startDate = sp.getString("startDate", "") ?: "",
            totalWeeks = sp.getInt("totalWeeks", d.totalWeeks),
            weekOffset = sp.getInt("weekOffset", 0),
            periodCount = listOf(
                sp.getInt("pc0", d.periodCount[0]),
                sp.getInt("pc1", d.periodCount[1]),
                sp.getInt("pc2", d.periodCount[2]),
            ),
            sectionStart = listOf(
                sp.getInt("ss0", Defaults.SECTION_START[0]),
                sp.getInt("ss1", Defaults.SECTION_START[1]),
                sp.getInt("ss2", Defaults.SECTION_START[2]),
            ),
            sameLength = sp.getBoolean("sameLength", true),
            periodMinutes = sp.getInt("periodMinutes", d.periodMinutes),
            perPeriodMinutes = sp.getString("perPeriodMinutes", "")
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?: emptyList(),
            breakMinutes = sp.getInt("breakMinutes", d.breakMinutes),
            bigBreakMinutes = sp.getInt("bigBreakMinutes", d.bigBreakMinutes),
            reminderOn = sp.getBoolean("reminderOn", true),
            reminderMinutes = sp.getInt("reminderMinutes", d.reminderMinutes),
            themeId = sp.getString("themeId", d.themeId) ?: d.themeId,
            tableName = sp.getString("tableName", "") ?: "",
        ).ensurePerPeriod()
    }

    fun saveSettings(s: Settings) {
        val e = sp.edit()
        e.putString("startDate", s.startDate)
        e.putInt("totalWeeks", s.totalWeeks)
        e.putInt("weekOffset", s.weekOffset)
        e.putInt("pc0", s.periodCount.getOrElse(0) { 0 })
        e.putInt("pc1", s.periodCount.getOrElse(1) { 0 })
        e.putInt("pc2", s.periodCount.getOrElse(2) { 0 })
        e.putInt("ss0", s.sectionStartAt(0))
        e.putInt("ss1", s.sectionStartAt(1))
        e.putInt("ss2", s.sectionStartAt(2))
        e.putBoolean("sameLength", s.sameLength)
        e.putInt("periodMinutes", s.periodMinutes)
        e.putInt("breakMinutes", s.breakMinutes)
        e.putInt("bigBreakMinutes", s.bigBreakMinutes)
        e.putBoolean("reminderOn", s.reminderOn)
        e.putInt("reminderMinutes", s.reminderMinutes)
        e.putString("themeId", s.themeId)
        e.putString("tableName", s.tableName)
        e.putString("perPeriodMinutes", s.ensurePerPeriod().perPeriodMinutes.joinToString(","))
        e.apply()
    }

    // ------------------------------------------------------------ 同步目标

    /** 记住要推送的手表（经典蓝牙地址），下次不用再选 */
    fun loadWatchAddress(): String = sp.getString("watchAddress", "") ?: ""
    fun loadWatchName(): String = sp.getString("watchName", "") ?: ""

    fun saveWatch(device: com.liyan.coursetable.phone.sync.BtSync.BondedDevice) {
        sp.edit().putString("watchAddress", device.address).putString("watchName", device.name).apply()
    }

    /** 记下来的设备已经不在配对列表里了（解绑 / 换手表），清掉避免选到不存在的目标 */
    fun clearWatch() {
        sp.edit().remove("watchAddress").remove("watchName").apply()
    }

    // ------------------------------------------------------------ 课程

    /**
     * 启动时恢复课程；文件坏了就退回内置示例，示例也坏了才返回空。
     *
     * 关于 [CourseJson.parse] 的 `applySettings`：日常保存写出的是 `exportJson()`
     * （**不含** `settings`），只有「手机推给手表的负载」才带 `settings` 对象。
     * 所以这里按「文件里有没有 settings」决定要不要连设置一起生效 ——
     * 带上 settings 的文件只可能是从手表那边拿回来的 `course.json`，
     * 这种情况让设置也生效，等于一次「从手表恢复」；而日常保存的文件没有 settings，
     * 就绝不会反过来把用户手调的作息 / 主题冲掉。
     */
    fun loadTable(): CourseTable {
        val s = loadSettings()
        readCourseFile()?.let { text ->
            try {
                val carriesSettings = runCatching {
                    org.json.JSONObject(text).has("settings")
                }.getOrDefault(false)
                val parsed = CourseJson.parse(text, s, applySettings = carriesSettings)
                val applied = parsed.settings ?: s
                if (parsed.settings != null) {
                    // 落盘一份，免得下次又从头推一遍
                    saveSettings(applied)
                }
                return CourseTable(applied, parsed.courses)
            } catch (_: Exception) {
                // 落盘内容损坏，继续走示例
            }
        }
        return try {
            val p = CourseJson.parse(readAsset(SAMPLE_ASSET), s, applySettings = false)
            CourseTable(s, p.courses)
        } catch (_: Exception) {
            CourseTable(s, emptyList())
        }
    }

    /** 用户点「应用」时调用：设置 + 课程一起落盘。 */
    fun saveTable(table: CourseTable) {
        saveSettings(table.settings)
        try {
            courseFile.writeText(CourseJson.exportJson(table), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun clearTable() {
        try {
            courseFile.delete()
        } catch (_: Exception) {
        }
        sp.edit()
            .remove("tableName")
            .remove("startDate")
            .remove("totalWeeks")
            .remove("weekOffset")
            .apply()
    }

    /** 是否用的是内置示例（还没有导入过自己的课表） */
    val hasImported: Boolean get() = courseFile.isFile

    private fun readCourseFile(): String? = try {
        if (courseFile.isFile) courseFile.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------ 资源

    fun readAsset(name: String): String =
        appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 导出到手机共享存储，方便用数据线 / 其他 App 取走 */
    fun exportToExternal(table: CourseTable): File {
        val dir = File(
            appContext.getExternalFilesDir(null) ?: appContext.filesDir,
            "CourseTablePhone",
        )
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "course.json")
        f.writeText(CourseJson.exportJson(table), Charsets.UTF_8)
        return f
    }

    companion object {
        private const val PREF = "coursetable_phone_cfg"
        private const val SAMPLE_ASSET = "sample_course.json"
    }
}
