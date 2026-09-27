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
package com.liyan.coursetable.phone.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.liyan.coursetable.phone.data.Store
import com.liyan.coursetable.phone.model.Course
import com.liyan.coursetable.phone.model.CourseJson
import com.liyan.coursetable.phone.model.CourseTable
import com.liyan.coursetable.phone.model.ParsedTable
import com.liyan.coursetable.phone.model.Settings
import com.liyan.coursetable.phone.model.TimeTable
import com.liyan.coursetable.phone.sync.BtSync
import com.liyan.coursetable.phone.sync.SyncServer
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Tab(val label: String) {
    Sync("同步"),
    Data("课表"),
    Settings("设置"),
}

/** 蓝牙推送的状态机 */
sealed interface PushState {
    data object Idle : PushState
    data object Sending : PushState
    data class Ok(val message: String, val courses: Int, val bytes: Int) : PushState
    data class Fail(val message: String) : PushState
}

/**
 * 全应用状态。只有一个 Activity 一屏主界面，用不上 ViewModel，
 * 一个持有 Compose 状态的对象就够了，也省掉一层依赖。
 */
class AppState(private val app: Context, val store: Store) {

    /**
     * 启动时只读一次磁盘：设置和课程都来自同一个 [Store.loadTable] 结果。
     *
     * 之前这里是两句分开的初始化 —— `settings = store.loadSettings()` 加
     * `courses = store.loadTable().courses`。Kotlin 的属性按**声明顺序**求值，
     * 于是 `settings` 先拿到 prefs 里的旧值，`loadTable()` 随后才把负载里的
     * `settings` 写进 prefs。后果是「从手表恢复」只恢复了课程，
     * 主题 / 作息这些设置要等下次启动才生效。
     */
    private val initial: CourseTable = store.loadTable()

    var settings by mutableStateOf(initial.settings)
        private set

    var courses by mutableStateOf(initial.courses)
        private set

    var tab by mutableStateOf(Tab.Sync)

    /** 已导入过自己的课表（否则显示的是内置示例） */
    var imported by mutableStateOf(store.hasImported)
        private set

    // ------------------------------------------------------------ 蓝牙目标

    var watchAddress by mutableStateOf(store.loadWatchAddress())
        private set

    var watchName by mutableStateOf(store.loadWatchName())
        private set

    var bonded by mutableStateOf<List<BtSync.BondedDevice>>(emptyList())
        private set

    /** 读配对设备用的后台线程与主线程回写点（见 refreshBonded） */
    private val main = Handler(Looper.getMainLooper())
    private var btRefreshing = false
    private var lastBtRefreshAt = 0L

    /** 蓝牙运行时权限（Android 12+ 的 BLUETOOTH_CONNECT）是否已给 */
    var btPermissionOk by mutableStateOf(BtSync.missingPermissions(app).isEmpty())
        private set

    var push by mutableStateOf<PushState>(PushState.Idle)
        private set

    var lastPushAt by mutableStateOf("")
        private set

    val btLog = mutableStateListOf<String>()

    /** 局域网备用通道（手表连手机热点时用） */
    val server = SyncServer()

    val today: LocalDate get() = LocalDate.now()

    val currentWeek: Int? get() = settings.weekOf(today)

    val table: CourseTable get() = CourseTable(settings, courses)

    val timeTable: TimeTable by lazy { TimeTable(settings) }

    val weekSpan: IntRange?
        get() {
            val all = courses.flatMap { it.weeks }.distinct().sorted()
            return if (all.isEmpty()) null else all.first()..all.last()
        }

    init {
        server.setProviders(payload = { payload() }, info = { infoJson() })
    }

    // ------------------------------------------------------------ 课表数据

    fun updateSettings(new: Settings) {
        settings = new
        store.saveSettings(new)
    }

    fun applyImport(parsed: ParsedTable) {
        val s = parsed.settings ?: settings
        settings = s
        courses = parsed.courses
        store.saveTable(CourseTable(s, parsed.courses))
        imported = true
    }

    fun addCourse(course: Course) {
        val next = courses + course
        courses = next
        persistCourses(next)
    }

    fun updateCourse(index: Int, course: Course) {
        if (index !in courses.indices) return
        val next = courses.toMutableList()
        next[index] = course
        courses = next
        persistCourses(next)
    }

    fun deleteCourse(index: Int) {
        if (index !in courses.indices) return
        val next = courses.toMutableList()
        next.removeAt(index)
        courses = next
        persistCourses(next)
    }

    private fun persistCourses(list: List<Course>) {
        store.saveTable(CourseTable(settings, list))
        imported = true
    }

    fun clearTable() {
        store.clearTable()
        settings = store.loadSettings().copy(tableName = "", startDate = "")
        courses = emptyList()
        imported = false
    }

    fun loadSample() {
        val sample = runCatching { store.readAsset("sample_course.json") }.getOrDefault("")
        runCatching { CourseJson.parse(sample, settings) }.getOrNull()?.let { applyImport(it) }
    }

    // ------------------------------------------------------------ 蓝牙推送

    /**
     * 刷新已配对设备。
     *
     * **必须在后台线程做**：`adapter.bondedDevices` 和每个设备的 `name`
     * 都是跨进程（binder）调用，蓝牙栈忙的时候单次能卡几百毫秒。
     * 之前放在主线程，一进前台就掉帧，就是这里的锅。
     *
     * 另外做了 1.5 秒去重：启动时 `onResume()` 和首屏的 `LaunchedEffect`
     * 会各调一次，没必要读两遍。
     *
     * 两种情况会自动替用户选好：
     *  - 还没选过，并且列表里**恰好只有一台**像手表（耳机已经被排除了，所以正常就是 1）；
     *  - 之前记下的那台已经不在配对列表里了（解绑 / 换手表）→ 先清掉再重选，
     *    否则界面会显示一个根本不存在的目标，推送时才报错。
     */
    fun refreshBonded(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (btRefreshing) return
        if (!force && now - lastBtRefreshAt < 1500) return
        btRefreshing = true

        Thread {
            val missing = BtSync.missingPermissions(app)
            // 没权限时 getBondedDevices() 必然读空，**不能据此断定设备已解绑**
            val list = if (missing.isEmpty()) BtSync.bondedDevices(app) else emptyList()
            main.post { applyBonded(missing.isEmpty(), list) }
        }.apply {
            isDaemon = true
            name = "bt-refresh"
            start()
        }
    }

    /** 后台读到的结果回到主线程再写状态（Compose 状态统一在主线程改） */
    private fun applyBonded(permissionOk: Boolean, list: List<BtSync.BondedDevice>) {
        btRefreshing = false
        lastBtRefreshAt = SystemClock.uptimeMillis()
        btPermissionOk = permissionOk
        if (!permissionOk) {
            // 没权限时列表必然是空的，此时清掉用户选好的手表只会让人重新选一遍
            bonded = emptyList()
            return
        }

        bonded = list
        if (list.any { it.address == watchAddress }) return

        if (watchAddress.isNotBlank()) {
            watchAddress = ""
            watchName = ""
            store.clearWatch()
        }
        val auto = list.filter { it.looksLikeWatch }
        if (auto.size == 1) chooseWatch(auto.first())
    }

    fun chooseWatch(device: BtSync.BondedDevice) {
        watchAddress = device.address
        watchName = device.name
        store.saveWatch(device)
    }

    fun clearPush() {
        push = PushState.Idle
    }

    /** 后台线程推送，结果写回 Compose 状态。 */
    fun pushToWatch() {
        if (push is PushState.Sending) return

        val problem = BtSync.readyProblem(app)
        if (problem != null) {
            push = PushState.Fail(problem)
            return
        }
        if (watchAddress.isBlank()) {
            push = PushState.Fail("还没选要推送的手表，先点「选择设备」")
            return
        }
        if (courses.isEmpty()) {
            push = PushState.Fail("手机上一门课都没有，没什么可推的")
            return
        }

        val payload = payload()
        push = PushState.Sending
        addLog("开始推送到 ${watchName.ifBlank { watchAddress }}（${payload.toByteArray().size} 字节）")

        Thread {
            val result = BtSync.push(watchAddress, payload)
            push = if (result.ok) {
                lastPushAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                addLog("推送成功（${result.sentBytes} 字节）")
                PushState.Ok(
                    message = "手表已收到",
                    courses = if (result.receviedCourses >= 0) result.receviedCourses else courses.size,
                    bytes = result.sentBytes,
                )
            } else {
                addLog("推送失败：${result.message}")
                PushState.Fail(result.message)
            }
        }.apply {
            isDaemon = true
            name = "bt-push"
            start()
        }
    }

    private fun addLog(text: String) {
        val t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        btLog.add(0, "$t  $text")
        while (btLog.size > 30) btLog.removeAt(btLog.size - 1)
    }

    // ------------------------------------------------------------ 负载

    fun currentJson(): String = CourseJson.exportJson(table)

    fun payload(): String = CourseJson.buildPayload(table)

    private fun infoJson(): String = buildString {
        append("{")
        append("\"app\":\"CourseTablePhone\",")
        append("\"protocol\":").append(CourseJson.PROTOCOL).append(',')
        append("\"appId\":\"com.liyan.coursetable.phone\",")
        append("\"tableName\":\"").append(settings.tableName.replace("\"", "\\\"")).append("\",")
        append("\"courses\":").append(courses.size).append(',')
        append("\"totalWeeks\":").append(settings.totalWeeks).append(',')
        append("\"totalPeriods\":").append(settings.totalPeriods)
        append("}")
    }

    companion object {
        const val WATCH_PACKAGE = "com.liyan.coursetable"

        /**
         * 开源仓库。**改这里要顺手改另一处**：
         * `CourseTableWatch/tools/gen_qr.py` 的 `DOWNLOADS_URL`
         * （手表上那张下载二维码就是用它生成的）。
         */
        const val REPO_URL = "https://github.com/xiaogon12/OPPOCourseTable"
        const val RELEASES_URL = "$REPO_URL/releases/latest"
        const val LICENSE_NAME = "CC BY-NC-SA 4.0"
        const val LICENSE_URL = "https://creativecommons.org/licenses/by-nc-sa/4.0/"
        const val AUTHOR = "xiaogon12"

        /**
         * 出处水印。
         *
         * 不是装饰 —— 它会被写进三处，目的是让「抹掉出处重新发布」这件事有成本：
         *  1. 打包进 APK 的常量池（`strings` / `aapt2 dump` 能直接搜到）；
         *  2. 显示在「关于」面板上（要删得先改代码，不能只换个图标）；
         *  3. 每次蓝牙推送的 JSON 负载里（`_via` 字段，数据流本身带着来源）。
         *
         * 手表端 `Store.java` 只按字符串键精确取值，多出来的字段会被自然忽略，
         * 所以加这个不会影响和旧版本的兼容。
         */
        const val WATERMARK = "OPPOCourseTable by xiaogon12 · CC BY-NC-SA 4.0"
    }
}
