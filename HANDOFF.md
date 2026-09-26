# 交接文档 · 课程表项目

> 写于 2026-09-26 20:50。用途：把「手表端课程表 App」的现状、平台事实、数据契约完整交给**新任务**，
> 新任务的目标是**从零开发手机端 App**（连手表传课表 + 全部设置搬上手机 + UI 要精致流畅）。
> 新任务开始时，先读这一份，不要重新翻旧对话。

---

## 0. 一句话现状

**两端都已完成并装在真机上，蓝牙传课表端到端跑通（2026-09-26 实测 13 门课 / 5847 字节）。**

| | 手表端 | 手机端 |
| --- | --- | --- |
| 源码 | `...\CourseTableWatch` | `...\CourseTablePhone` |
| 构建 | `python tools/build.py --install`（无 Gradle，约 10 秒） | `./gradlew :app:assembleRelease`（Gradle 9.8 + AGP 9.4.1） |
| 版本 | v0.7 / versionCode 7 / APK 72.7 KB | v0.7.0 / versionCode 1 / APK 1.35 MB（release） |
| 产物 | `CourseTableWatch\build\apk\CourseTableWatch-debug.apk` | `CourseTablePhone\app\build\outputs\apk\release\app-release.apk` |
| 规模 | 22 个 Java 文件（删掉导入模块后） | Kotlin + Compose，约 20 个源文件 |
| 旧日志 | `...\.workbuddy\memory\2026-09-2*.md` | 同左 |

**APK 本身不用带进新任务**（那只是产物）。新任务真正需要的是下面三样：**数据契约、手表端源码、已验证的平台事实**。

---

## 1. 目标设备与平台事实

### 已实测确认（有真机日志）

* 手表 **OPPO Watch X2（OWW251）**，ColorOS Watch 16.0.0，**Android 11 / API 30 / armeabi-v7a**
* **它是标准 Android，不是 Wear OS** —— 普通 APK `adb install` 直接装，不用 Wear 那套
* 屏幕 **466×466 px @320 dpi，圆形**（`ro.oplus.wear.screen.round=true`），1dp = 2px
* 手机端：**Android 11**
* `targetSdk 29 + requestLegacyExternalStorage="true"` → 可以直读写 `/sdcard/`（Android 11 只对 target ≥ 30 强制分区存储）
* 系统日历 Provider 可正常读写（`_id=1`、`calendar_access_level=700`、LOCAL 账户）
* 蓝牙：ON
* **Wi-Fi：`dumpsys wifi` 报 disabled，`ip link` 里没有 wlan0**（是没硬件还是被关，未确认）

### 手表的网络路径（新任务的关键前提）

```
25: tun0    inet 192.168.0.205/0 scope global tun0
```

手表没有 wlan0，但有一个 `tun0` 全局路由，配套进程是
**`com.heytap.wearable.bluetooth.net.proxy`** —— 也就是说：

> **手表是靠蓝牙共享手机的连接上网的，它和手机不在同一个 Wi-Fi 局域网。**

这条决定了传输方案怎么选（见 §4）。

### 已经撞过的墙（别再试）

| 试过的东西 | 结果 |
| --- | --- |
| 第三方 App 自己排 `AlarmManager` wakeup 闹钟 | **息屏 + 电量均衡时被 ROM 拦截**，日志 `WearFrw [BmPowerManager] forbin thirdapp(...) set wakeup alarm`。已改为借系统日历 Provider 排闹钟 |
| `setAlarmClock` / deviceidle 电池白名单 | 无效，同上 |
| ~~系统日历被锁死~~ | **这条是误判，已推翻**。系统日历完全可用，现在提醒就是走它 |
| root / 改系统时间 | `adbd cannot run as root in production builds`；`date` 命令不支持 `-s`。所以留了 `Now.java` 调试时间钩子 |

### 已解决的难点：息屏提醒（结论，别重做）

链路：`CalendarSync` 写入 Events + Reminders + CalendarAlerts 三类行
→ **对 Reminders 行做一次 `UPDATE`**（只 insert 不会触发重排，这是最关键的一步）
→ Provider 调 `scheduleNextAlarm()` 排下 `RTC_WAKEUP`
→ 到点系统投递 `android.intent.action.EVENT_REMINDER`
→ `EventReminderReceiver`（静态注册，Manifest 里必须带 `android:scheme="content"`）发通知。
熄屏状态下实测能响。App 不需要常驻后台。

---

## 2. 手表端源码地图

`app/src/main/java/com/liyan/coursetable/`

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `MainActivity.java` | 556 | 主界面骨架、手势、弹层栈、权限、`configChanged` |
| `Store.java` | 779 | **课程 + 设置的仓库 & JSON 解析/落盘**（契约在这里） |
| `Sheets.java` | 735 | 所有设置弹层（导入、提醒、作息时间、主题…） |
| `CoursePage.java` | 588 | 课表页（焦点居中/过半让位） |
| `CalendarSync.java` | 396 | 写系统日历排提醒 |
| `Reminder.java` | 325 | 提醒总调度（日历优先，退回旧闹钟） |
| `AppConfig.java` | 288 | **全部设置项**（见 §3.2） |
| `SwipeHost.java` | 263 | 上下页手势宿主 |
| `Widgets.java` / `MinePage.java` | 219 / 211 | 通用控件 / 「我的」页 |
| `Ui.java` / `Palette.java` | 143 / 137 | 布局工具 / 配色与主题 |
| `TimeTable.java` | 96 | 由设置推算整张作息表 |
| `Course.java` | 110 | 一门课 |
| `Weeks.java` | 94 | 周次/星期换算 |
| `EventReminderReceiver.java` | 92 | 收日历提醒广播 → 发通知 |
| `QrView.java` | ~100 | 整数倍放大的锐利二维码。构造改成 `QrView(c, resId)`，默认画 `R.drawable.qr_download` |
| `MiniSwitch.java` / `Json.java` / `Host.java` / `Now.java` / `BootReceiver.java` / `ReminderReceiver.java` | 19~75 | 开关、JSON 取值、宿主契约、调试时间、开机重置、旧闹钟接收 |

`tools/`：`build.py`（构建+安装）、`gen_qr.py`（生成**手机端 App 下载二维码** `qr_download.png` + 地址字符串 `R.string.downloads_url`，构建第 0 步自动跑，依赖 `segno`）、`ui.py`（截图/点按辅助）、`test_course.json`

---

## 3. 两个 App 之间的接口

这是**唯一的接口**，手机端按这个来做就不会对不上。

### 3.1 课表数据 `course.json`（规范字段）

```json
{
  "name": "2026 秋 课程表",
  "startDate": "2026-09-01",
  "totalWeeks": 18,
  "pc": [4, 4, 0],
  "ps": ["08:00", "14:00", "19:00"],
  "dt": 45,
  "breakMinutes": 10,
  "bigBreakMinutes": 20,
  "periodTimes": [{"period": 1, "start": "08:00", "end": "08:45"}],
  "courses": [
    {"name": "高等数学", "teacher": "张三", "room": "教学楼101",
     "class": "新能源1班", "day": 1, "from": 1, "to": 2, "weeks": [1,2,3]}
  ]
}
```

| 字段 | 含义 |
| --- | --- |
| `startDate` | 第一周的**周一**日期，`YYYY-MM-DD` |
| `totalWeeks` | 学期总周数 |
| `pc` | 上午/下午/晚上三段各自的节数 |
| `ps` | 三段各自第一节的开始时间 |
| `dt` | 每节课时长（分钟） |
| `breakMinutes` / `bigBreakMinutes` | 小课间 / 每 2 节后的大课间 |
| `periodTimes` | 可选。给了具体每节时间会反推 `ps` / `dt` / 课间 |
| `courses[].day` | 周一=1 … 周日=7（**`0` 或其他非法值 = 没有固定排课，手表端会跳过**） |
| `courses[].from` / `to` | 起止节次，1 起，上午→下午→晚上连续编号 |
| `courses[].weeks` | 有课的周次数组；空数组 = 每周 |

`Store.java` 还兼容一堆别名（`n` / `课程名` / `weekStart` / `"1-8,10"` 字符串等），
手机端**只用上面的规范字段**即可，不用管别名。

导入位置优先级：`/sdcard/CourseTableWatch/course.json` → 该目录下任意 `*.json`
→ `Documents/` → `Download/` → 存储根 → App 外部私有目录。

### 3.2 设置项全清单（手机端要能改的就是这些）

`AppConfig.java` 里一共这些，全部存在 `SharedPreferences`：

| 字段 | 默认 | 含义 |
| --- | --- | --- |
| `startDate` | `""` | 开学第一周周一 |
| `totalWeeks` | 18 | 总周数 |
| `weekOffset` | 0 | 手动周次修正（当前是第几周的偏移） |
| `periodCount` `[3]` | `{4,4,0}` | 上/下午/晚 节数 |
| `sectionStart` `[3]` | 08:00 / 14:00 / 19:00 | 各段首节时间（分钟） |
| `sameLength` | true | 是否所有节一样长 |
| `periodMinutes` | 45 | 统一每节时长 |
| `perPeriodMinutes` `[]` | 空 | 各节单独时长（`sameLength=false` 时用） |
| `breakMinutes` | 10 | 课间 |
| `bigBreakMinutes` | 20 | 每 2 节后的大课间 |
| `reminderOn` | true | 课前提醒开关 |
| `reminderMinutes` | 20 | 提前多少分钟提醒 |
| `themeId` | `"ink"` | 主题 |
| `tableName` | `""` | 课表名 |

### 3.3 手表端**没有**的东西（手机端可以补）

* 没有"课程编辑表单"——课程只能靠导入 JSON
* `weekOffset` 只在小范围里改
* 主题只有少数几个预置

---

## 4. 手机端 App 的架构选项

### 4.1 传输方案（**新任务第一件事就是验证这个**）

> **已拍板：优先做方案 A（蓝牙直连）。** 理由：用户要把这个 App **做成开源软件**，
> 走云端中转意味着课表数据要过第三方服务器 + 需要自建后端，跟开源的定位冲突。
> A 不通时再退到 B（届时后端也必须是可以自部署的）。

| 方案 | 做法 | 优点 | 风险 |
| --- | --- | --- | --- |
| **A. 走蓝牙代理直连**（首选，待验证） | 手机起一个 HTTP 服务（`0.0.0.0:8737`），手表作为客户端去连它 | 零后端、零账号、复用已有蓝牙链路，体验最好（手机点一下就同步） | **手表和手机不在同一 Wi-Fi**，`tun0` 可能只是纯 NAT 出口，手机自身地址不一定可达 → **必须先验证** |
| **B. 云端中转** | 手机上传 → 手表下载，配对码绑定 | 只要有网就一定能用，最稳 | 需要后端（可用 `cloud-service` 托管方案）和配对逻辑 |
| **C. 蓝牙 BLE 自定义 GATT** | 手机做 GATT Server 广播，手表做 central 连接读写 | 不依赖网络，最"直连" | 要写 GATT 协议 + 按 MTU 分片，工作量最大 |

**验证结果（2026-09-26 21:20 实测，手表 adb 直连状态下）：**

| 测试项 | 命令 | 结果 |
| --- | --- | --- |
| 蓝牙网络代理开关 | `settings get global bluetooth_net_proxy_on` | `1`（开着） |
| 手表 → 外网（域名） | `curl -m 12 http://www.baidu.com` | `000` 超时 |
| 手表 → 外网（纯 IP，排除 DNS） | `curl -m 12 http://223.5.5.5` | `000` 超时 |
| 手表 → 电脑局域网 `172.19.118.148:8737` | `curl -m 12 http://…/probe.json` | `000` 超时 |
| 电脑自测同端口 | `curl http://172.19.118.148:8737/probe.json` | `200` 正常 |
| 手表 → tun0 网关 `192.168.0.1` | `ping -c 2` | 100% 丢包 |
| 手表 Wi-Fi 硬件 | `cmd wifi set-wifi-enabled enabled` + `list-scan-results` | **可开、能扫到 5 个 SSID（全 WPA2-PSK）** |

**结论：蓝牙网络代理这条链路当前「完全不通」**，`tun0`（192.168.0.205）接口在但不转发任何流量。
外网也不通，所以不是「能上网但访问不到局域网」这种细粒度问题，而是整条代理没在工作。
它依赖手机侧代理进程常驻 + 手机当时有网，作为课表同步的传输层**不可靠**。

**改为方案 A′（Wi-Fi 局域网直连），并把「手机热点」作为推荐链路：**

| 子方案 | 判断 |
| --- | --- |
| 手表连**校园网 Wi-Fi** | ⚠️ 两个坑：① 校园网常要 portal / 802.1X 认证，而 11285 明确写「Wi-Fi 不支持连接需要认证的网络」；② 校园网普遍开 AP 隔离，同网段设备互相不可达 |
| **手机开热点 + 手表连热点** | ✅ **推荐**。必然同一网段、无认证、无隔离。课表只有几十 KB，热点开 10 秒即可 |
| 导出 `course.json` + 数据线 ADB push | ✅ 兜底，100% 可行，但要插线 |

**关键：三种链路在代码上是同一套** —— 手机起 HTTP 服务，手表主动来拉。换链路不用改代码。
所以方案 A 的客户端代码（`SyncServer`）继续保留，蓝牙那条以后活了也不用重写。

**协议（手机做服务端、手表做客户端，已实现于 `CourseTablePhone/.../sync/SyncServer.kt`）：**

```
GET /info                -> 服务信息（不校验配对码）
GET /sync?code=XXXXXX    -> 返回完整负载 JSON
GET /pull?code=XXXXXX    -> /sync 别名
```

方向必须是「手表主动拉」：11282 明确写「OPPO Watch 不支持应用在后台运行」，
手表端不可能常驻监听，只能由用户在手表上点「从手机接收」时发起一次前台短连接。

### 4.2 技术栈（手机端必须换路子）

**手机端不能用现在这套"手写 aapt2 / javac / d8"。**
理由是想要「圆滑丝滑」就得用 **Jetpack Compose + Material 3**（声明式动画、大圆角、动态取色），
而 Compose 有一堆 AndroidX 依赖和编译器插件，手写构建链根本走不通。

推荐：**Kotlin + Jetpack Compose (Material 3) + Gradle**

* 本机已有：JDK（`C:\Program Files\Android\Android Studio\jbr`）、Android SDK（build-tools 36.0.0 / platforms）
* 需要准备：Gradle（联网下载即可）
* `minSdk 26` / `targetSdk 34`（手机是 Android 11 = API 30）
* 注意：**手机端和手表端建议放两个独立工程**，通过 `course.json` 契约解耦，不要合成一个多模块工程

### 4.3 UI 方向（用户明确要求「很好看、圆滑丝滑」）

* 大圆角（20~28dp）、柔和的层次阴影、留白充足
* 课程卡片用**课程色条**做视觉锚点（手表端已有按课名取色的逻辑，`Palette.barColor()`）
* 动效克制但要顺：列表项进出用 `AnimatedVisibility` + `animateItemPlacement`，不要花哨
* 参考用户偏好：**精简不冗余、不要多余的个性动画、操作要迅速直接**（这是他在手表端反复强调的）
* 主界面建议就是「本周课表」——一屏能看清，编辑走底部弹层

### 4.4 手机端首期功能（已按用户答复定稿）

**课表数据的入口只有一条：粘贴 AI 生成的 JSON**（用户明确只选了这一项，
**不做**手动录入表单，也**不做**拍照识别）。所以流程是：

```
手表/手机显示提示词  ->  丢给 AI  ->  拿回 JSON  ->  粘进手机 App
  ->  解析并预览（这门课放星期几第几节、共几门课、几周）
  ->  确认无误  ->  一键推送到手表
```

首期功能清单：

1. **导入**：粘贴 JSON 的输入框 + 实时解析 + **预览确认页**（解析失败要明确指出哪一行/哪个字段错）
2. **课表总览**：周视图，左右切周，配色跟手表端一致（按课名取色）
3. **一键推送到手表** ← 传输通了之后接上
4. **全部设置**（对齐 §3.2）：作息时间、学期、提醒、主题、课表名
5. 顺手提供「导出 / 复制当前 JSON」，方便备份和二次编辑

**暂不做**（后续想加再说）：课程编辑表单、拍照识别、账号体系。

### 4.5 开源定位（用户明确要求：做成开源软件）

这条会反过来约束很多设计决策，务必遵守：

* **不做账号体系、不做登录**，手机与手表一对一
* **不依赖任何私有后端 / 私有 API key**，不能让别人想用得先找你要个 token
* **数据不出本机**（课表只在手机 ↔ 手表之间传），这也是选蓝牙直连的原因
* 需要准备好：`README.md`（含手表端 + 手机端的完整上手步骤）、`LICENSE`、
  数据格式说明（§3.1 直接搬过去）、截图
* 手机端和手表端建议**同一个仓库、两个目录**，方便别人一次 clone 就能用

---

## 5. 手表端要配合改的地方（✅ 已完成 2026-09-26）

最终实现的是**经典蓝牙 RFCOMM 直连**（比原计划的三条方案都省事）：

| 项 | 实现 |
| --- | --- |
| 服务端 | 新增 `BtReceive.java`：手表起 `BluetoothServerSocket`（`listenUsingRfcommWithServiceRecord`），前台等 90 秒 |
| 入口 | `MinePage.java` 顶部新增「从手机接收」行 |
| 弹层 | `Sheets.receiveSheet()` / `startWaiting()` / `waitingBody()` / `resultBody()`：等待中 → 结果页（可重试） |
| 应用数据 | `Store.applyJson(json, ctx, true)`，即设置与课程一起生效 |
| 收尾 | `configChanged(true)` + `Reminder.schedule(c)`，界面与提醒自动重排 |
| 清理 | `MainActivity.closeSheet()` / `closeAllSheets()` 里调 `BtReceive.stopCurrent()`，弹层关掉就不占着蓝牙 |
| 自动收工 | `Sheets.StopBtOnLeave` 容器：内容一 `onDetachedFromWindow` 就 `stopCurrent()`。这条覆盖了「活动被系统销毁 / 被顶掉」这类**没走** `closeSheet` 的路径 |
| 常亮 | 等待期间给窗口加 `FLAG_KEEP_SCREEN_ON`（`MainActivity.applyOverlayBrightness()` 里按栈顶 tag 判断），屏幕不会黑掉导致看不到状态 |
| 体检 | `BtReceive.bondedCount()`：一台已配对设备都没有就直接提示去系统设置配对，不让用户白等 90 秒 |
| 兜底 | 安全监听建不起来时退回 `listenUsingInsecureRfcommWithServiceRecord`，正好对上手机端第 2 条连接策略 |
| 权限 | Manifest 加 `BLUETOOTH` / `BLUETOOTH_ADMIN`（手表 targetSdk 29，安装即授予，不用运行时申请） |
| **下载入口** | `Sheets.receiveSheet()` 第 ① 段新增「显示下载二维码」按钮，扫出来是 GitHub Releases 页。二维码与界面文字**同一个来源**：`gen_qr.py` 的 `DOWNLOADS_URL` → 生成 PNG + `R.string.downloads_url` |
| **删掉老路** | 手表端「导入课程表」整块删除：`MinePage` 那一行、`Sheets.importSheet()` / `promptSheet()` / `pathBox()`、`Store.importFromFile()` / `writeSample()` / `readRaw()` / `SAMPLE` 常量。课表现在只有「手机推过来」一个来源 |
| 版本 | 两端都是 **0.7**。手表端版本号唯一来源是 `tools/build.py` 的 `VERSION_CODE` / `VERSION_NAME` —— aapt2 link 的参数会覆盖清单里的同名属性，改版本只改 build.py（清单里也同步一份只为看着一致） |

**共用 UUID**：`b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e`，服务名 `CourseTableSync`。
改这个 UUID 必须两端同时改，否则连不上。

**帧格式**：`[4 字节大端长度][UTF-8 负载]`，回包同格式（`{"ok":true,"courses":37}`）。

### 为什么不是原计划的方案 A / B / C

- 蓝牙网络代理（`tun0`）实测**完全不转发流量**，见 §4.1，所以不是它。
- 手机和手表本来就是**经典蓝牙已配对**（`Bonded devices: [BR/EDR]`，手表以 `HeadsetClientService` 角色连着手机）。
  于是最自然的做法就是在这条已配对的链路上开一条 RFCOMM 通道，零额外配对、零网络依赖。
- 手表当服务端是因为 11282 写明手表**不支持后台运行**，只能用户主动开一次前台监听。

**未做**：`settings` 子对象里的 `weekOffset` / `reminderOn` / `reminderMinutes` / `themeId` /
`sameLength` / `perPeriodMinutes` —— `Store.applyJson` 目前只读顶层字段，这几个字段手机端会推过去但手表端暂时忽略。
要打通的话在 `Store.applyJson` 的 `persist` 分支里补读 `root.optJSONObject("settings")` 即可。

---

## 6. 已定决策（2026-09-26 用户拍板，新任务不用再问）

| 问题 | 决定 |
| --- | --- |
| 手机 ↔ 手表怎么传 | **必须经典蓝牙 RFCOMM 直连**（用户 2026-09-26 明确表态：蓝牙更稳定，必须做蓝牙）。方案已实现并装表；局域网 HTTP 只留作备用通道 |
| 课表从哪来 | **只做「粘贴 AI 生成的 JSON」**；不做手动录入表单，不做拍照识别 |
| 使用范围 | **只在自己这一块表上用；做成开源软件；不做账号体系** |
| 手机端技术栈 | Kotlin + Jetpack Compose (Material 3) + Gradle（`minSdk 26` / `targetSdk 34`） |
| 工程组织 | 手表端 + 手机端 **同一仓库两个目录**，靠 §3.1 的 `course.json` 契约解耦 |

### 新任务的执行顺序（建议）

1. ~~**验传输**~~ ✅ 已完成：蓝牙网络代理（`tun0`）实测完全不转发 → 改用**经典蓝牙 RFCOMM 直连**（见 §4.1 / §5）
2. 搭手机端 Gradle + Compose 骨架，先跑通一个空壳（确认能编译、能装到手机）
3. 做 JSON 粘贴 + 解析 + 预览
4. ~~接传输~~ ✅ 双方代码都已完成（手机 `BtSync` / 手表 `BtReceive`），**差最后一步：真机跑通一次「手表点接收 → 手机点推送 → 手表刷新」**
5. 补设置页（对齐 §3.2）
6. 打磨 UI（§4.3）与开源配套（§4.5）

---

## 7. 快速上手

```bash
ADB=/c/Users/liyan/AppData/Local/Android/Sdk/platform-tools/adb.exe

# 手表端构建 + 安装（约 10 秒）
cd "C:/Users/liyan/WorkBuddy/2026-09-24-22-58-02/CourseTableWatch"
"C:/Users/liyan/.workbuddy/binaries/python/versions/3.13.12/python.exe" tools/build.py --install

# 看日志
$ADB logcat -v time CourseTable:V AndroidRuntime:E "*:S"

# 截图
$ADB exec-out screencap -p > shot.png

# 课表页"穿越时间"调试（手表没 root，改不了系统时间）
$ADB shell am force-stop com.liyan.coursetable
$ADB shell am start -n com.liyan.coursetable/.MainActivity --ei now_offset 1234

# 同理，跳过「开始等待手机推送」那一下直接进等待状态
# （手表放在充电器上时，OPPO 的充电系统悬浮窗会吃掉所有触摸，只能这样触发）
$ADB shell am start -n com.liyan.coursetable/.MainActivity --ez receive true

# 手机端正式包
cd "C:/Users/liyan/WorkBuddy/2026-09-24-22-58-02/CourseTablePhone"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export GRADLE_USER_HOME="/c/Users/liyan/.workbuddy/binaries/gradle/home"
"/c/Users/liyan/.workbuddy/binaries/gradle/gradle-9.8.0/bin/gradle" :app:assembleRelease
```

### 手机端：性能与签名（这一节的结论比代码本身重要）

* **一定要用 release 包判断卡不卡。** 同一个 commit，debug 的 Compose 不做 R8、不做内联：
  99 分位帧时间 **250ms**，而 release 是 **29ms**；掉帧 15 → 2 次，Missed Vsync 9 → 0。
  用户反馈的「好卡」基本都出自这里，不是代码写坏了。
* 真正在代码里修掉的三处（都有注释）：
  1. `refreshBonded()` 挪到后台线程 —— `adapter.bondedDevices` / `device.name` 是 binder 调用，主线程调会掉帧
  2. 去掉标签页的 `AnimatedContent` —— 过渡期间新旧两屏同时在组合
  3. 课表页改 `LazyColumn`，分组排序 `remember(courses)` 缓存一次
* **签名**：`keystore.properties`（`storeFile`/`storePassword`/`keyAlias`/`keyPassword`）+ `release.keystore`
  都在 `.gitignore` 里。没有这个文件时自动退回 debug 签名，别人 clone 也能编译。
  证书 SHA-256：`BD:8D:77:6F:FB:DA:33:4F:EE:75:F9:6D:C2:01:BC:7F:63:1E:6E:36:50:80:D1:0B:7E:3C:02:62:AD:F5:1E:57`
* **换签名必须卸载重装**（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），App 数据会清掉。

### 手机没数据了怎么快速恢复（手机已 root，实测可用）

手机端课程存 `files/course.json`，设置存 `shared_prefs/coursetable_phone_cfg.xml`。
手表端每次保存会在 `/sdcard/CourseTableWatch/course.json` 留一份**完整推送负载**
（含 `settings`，连 `themeId` 都在），所以可以反向灌回去：

```bash
PKG=com.liyan.coursetable.phone
$ADB -s <手机> push prefs.xml /data/local/tmp/ct_prefs.xml
$ADB -s <手机> push payload.json /data/local/tmp/ct_course.json
$ADB -s <手机> shell su -c "
  cp /data/local/tmp/ct_prefs.xml  /data/data/$PKG/shared_prefs/coursetable_phone_cfg.xml &&
  cp /data/local/tmp/ct_course.json /data/data/$PKG/files/course.json &&
  chown 10480:10480 <上面两个文件> && chmod 600 <同上> && restorecon <同上>"
```

* **先 `am force-stop`**，否则 SharedPreferences 还在内存里，写文件不生效
* **merge 而不是覆盖** prefs：里面还有 `watchAddress` / `watchName`，覆盖掉就要重新选设备
* `ps`(HH:mm) → prefs 的 `ss0/ss1/ss2` 要换算成"从 0 点起的分钟数"

### 踩过的工具坑

* `adb` 服务会时不时掉线，重连 `adb start-server` 即可，不影响结论
* `content query --projection` 的分隔符是**冒号**（`_id:title`），用逗号报 `Invalid column`
* `uiautomator dump` 会返回**上一次**的界面树，判断 UI 状态要连 dump 两次
* Windows 中文环境下 `javac`/`aapt2` 输出 GBK，`build.py` 已处理解码

---

## 8. 用户协作约定（重要）

* **不要反复重新导入课表** —— 导入只在用户主动要求或换课表时做；bug 由用户自己在手表上验证
* 被问「是否全部完成」时，**停止操作，直接给状态总结**
* 偏好：**精简不冗余的 UI、不要多余的个性动画、操作要迅速直接**
* 分阶段推进，**每阶段真机验证通过后再进下一阶段**
