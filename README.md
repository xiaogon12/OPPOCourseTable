# 课程表 · OPPO Watch X2

把手表当课程表用。手机 App 里粘一份 AI 生成的 JSON，点一下推到手表，
之后两块屏上就是同一份课表。

- **`CourseTableWatch/`** 手表端 · 装在 OPPO Watch X2 上（ColorOS Watch，标准 Android 11，不是 Wear OS）。
- **`CourseTablePhone/`** 手机端 · 用来导入课表和改所有设置，改完推给手表。

没有账号，没有服务端，**不联网**。课表只在你自己这两台设备之间通过蓝牙走。

![手机端课表](docs/screenshots/phone-courses.png)

## 下载安装

到 **[Releases](https://github.com/xiaogon12/OPPOCourseTable/releases/latest)** 下载：

| 文件 | 装到哪 | 怎么装 |
| --- | --- | --- |
| `app-release.apk` | 手机（Android 8.0 以上） | 传进手机，**点开就装**（提示「未知来源」时允许一次） |
| `CourseTableWatch.apk` | 手表（OPPO Watch X2） | 手表上没有能点开 APK 的安装界面，用数据线连电脑 `adb install CourseTableWatch.apk` |

手表上也能拿到下载地址：**手表 →「我的」→「从手机接收」→「显示下载二维码（全屏）」**，用手机扫一下。

## 怎么用

1. 手机打开 App →「课表」→「批量导入 JSON」→ **复制提示词**
2. 把提示词和你的课表（教务系统复制的文字、截图转文字都行）一起丢给任意 AI，拿回一段 JSON
3. 粘回 App，确认预览没问题 →「覆盖应用」
4. 手表 →「我的」→「从手机接收」→「开始等待手机推送」
   手机 →「同步」→「推送到手表」

课表和**全部设置**（学期周数、作息时间、课前提醒、配色）都在手机端改，
推一次全部同步过去，不用在手表上戳。

## 自己编译

```bash
# 手机端（JDK 17+ 和 Android SDK）
cd CourseTablePhone
./gradlew :app:assembleRelease

# 手表端（只要 Android SDK，约 10 秒）
cd CourseTableWatch
python tools/build.py --install
```

> 两头都请构建 release，不要用 debug 包。

## 声明

- **MIT 许可**，全文见下方。随便用、随便改、随便再发布。
- **不联网、不收集任何信息、不上传任何数据。** 手机端要蓝牙权限只是为了和手表直连传 JSON，
  没有统计 SDK、没有崩溃上报、没有广告。
- 个人项目，**和 OPPO / ColorOS 官方无关**，也不是 Wear OS 应用。
  只在 OPPO Watch X2（OWW251，ColorOS Watch 16.0.0）上实测过。

## 许可 · MIT License

```
MIT License

Copyright (c) 2026 xiaogon12

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
