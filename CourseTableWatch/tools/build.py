#!/usr/bin/env python
# -*- coding: utf-8 -*-
#
# 课程表 · OPPO Watch X2
# Copyright (c) 2026 xiaogon12
# https://github.com/xiaogon12/OPPOCourseTable
#
# 许可：CC BY-NC-SA 4.0（署名—非商业性使用—相同方式共享）
#   可以免费用、随意改、原样或改版再发布；不可以商用、盈利；
#   不可以移除本署名后重新发布。完整条款见仓库根目录 LICENSE。
#
"""
OPPO Watch 课程表 —— 极简构建脚本

不依赖 Gradle / Android Studio，直接调用 Android SDK 自带的
aapt2 / javac / d8 / zipalign / apksigner 完成打包。

用法（在项目根目录）：
    python tools/build.py              # 构建 debug APK
    python tools/build.py --install    # 构建并安装到手表
    python tools/build.py --clean      # 清理构建产物
    python tools/build.py --logcat     # 安装后跟踪 App 日志
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

# ---------------------------------------------------------------- 路径

ROOT = Path(__file__).resolve().parent.parent
APP_SRC = ROOT / "app" / "src" / "main"
BUILD = ROOT / "build"
GEN = BUILD / "gen"
OBJ = BUILD / "obj"
DEX = BUILD / "dex"
OUT = BUILD / "apk"
GEN_RES = BUILD / "genres" / "res"      # 由脚本生成的资源（二维码等）
KEYSTORE = ROOT / "tools" / "debug.keystore"

PKG = "com.liyan.coursetable"
MIN_SDK = "27"
# targetSdk 保持 29：Android 11 只对 target>=30 的 App 强制分区存储，
# 29 + requestLegacyExternalStorage 才能直接读写 /sdcard/CourseTableWatch/
TARGET_SDK = "29"
# 版本号唯一来源：aapt2 link 的这两个参数会覆盖 AndroidManifest.xml 里的同名属性，
# 所以改版本只改这里（清单里的值只是为了让人看代码时不困惑，保持一致即可）。
VERSION_CODE = "9"
VERSION_NAME = "0.8.0"
APK_NAME = "CourseTableWatch.apk"

ADB_FALLBACK = Path(r"E:\platform-tools\adb.exe")


# ---------------------------------------------------------------- 工具定位

def find_sdk_root():
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(key)
        if v and Path(v).is_dir():
            return Path(v)
    local = os.environ.get("LOCALAPPDATA")
    if local:
        p = Path(local) / "Android" / "Sdk"
        if p.is_dir():
            return p
    raise SystemExit("找不到 Android SDK，请设置 ANDROID_HOME")


def version_key(name):
    return [int(x) for x in re.findall(r"\d+", name)[:3]]


def find_build_tools(sdk):
    bt = sdk / "build-tools"
    if not bt.is_dir():
        raise SystemExit("找不到 build-tools，请在 Android Studio 的 SDK Manager 中安装")
    dirs = [d for d in bt.iterdir() if d.is_dir() and (d / "aapt2.exe").exists()]
    if not dirs:
        raise SystemExit("build-tools 目录为空，请重新安装 Build-Tools")
    dirs.sort(key=lambda d: version_key(d.name))
    return dirs[-1]


def find_platform(sdk):
    pf = sdk / "platforms"
    if not pf.is_dir():
        raise SystemExit("找不到 platforms，请在 SDK Manager 中安装一个 Android SDK Platform")
    dirs = [d for d in pf.iterdir() if d.is_dir() and (d / "android.jar").exists()]
    if not dirs:
        raise SystemExit("platforms 目录下没有可用的 android.jar")
    # 优先选 API 34；否则选最高的
    for d in dirs:
        if d.name.startswith("android-34"):
            return d
    dirs.sort(key=lambda d: version_key(d.name))
    return dirs[-1]


def find_java_home():
    v = os.environ.get("JAVA_HOME")
    if v and (Path(v) / "bin" / "javac.exe").exists():
        return Path(v)
    candidates = [
        Path(r"C:\Program Files\Android\Android Studio\jbr"),
        Path(r"C:\Program Files\Java\jdk-17"),
        Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Android Studio" / "jbr",
    ]
    for c in candidates:
        if (c / "bin" / "javac.exe").exists():
            return c
    raise SystemExit("找不到 JDK。请设置 JAVA_HOME（可用 Android Studio 自带的 jbr）")


def find_adb(sdk):
    p = sdk / "platform-tools" / "adb.exe"
    if p.exists():
        return p
    if ADB_FALLBACK.exists():
        return ADB_FALLBACK
    return Path("adb")


# ---------------------------------------------------------------- 执行

def run(cmd, desc=None, cwd=None):
    if desc:
        print(f"\n>>> {desc}")
    printable = " ".join(f'"{c}"' if " " in str(c) else str(c) for c in cmd)
    print(f"    {printable}")
    proc = subprocess.run([str(c) for c in cmd], cwd=str(cwd) if cwd else None,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    raw = proc.stdout
    try:
        out = raw.decode("utf-8")
    except UnicodeDecodeError:
        # Windows 中文环境下 javac / aapt2 会输出 GBK
        out = raw.decode("gbk", errors="replace")
    if out.strip():
        print("\n".join("      " + line for line in out.strip().splitlines()))
    if proc.returncode != 0:
        print(f"\n[失败] 命令返回码 {proc.returncode}")
        raise SystemExit(proc.returncode)
    return out


def clean():
    for d in (GEN, OBJ, DEX, OUT):
        if d.exists():
            shutil.rmtree(d, ignore_errors=True)
    for f in BUILD.glob("*.zip"):
        f.unlink(missing_ok=True)
    print("已清理 build/")


# ---------------------------------------------------------------- 签名

def ensure_keystore(java_home):
    if KEYSTORE.exists():
        return
    keytool = java_home / "bin" / "keytool.exe"
    print("\n>>> 生成 debug 签名证书")
    run([
        keytool, "-genkeypair", "-v",
        "-keystore", KEYSTORE,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Android Debug,O=Android,C=US",
    ])


# ---------------------------------------------------------------- 水印自检

# 出处水印（见根目录 LICENSE / README「关于套壳」）。故意做成两处冗余：
# classes.dex 的常量池（源码里的字面量）和 resources.arsc 的字符串池（strings.xml）。
WATERMARK_NEEDLE = "xiaogon12"


def check_watermark(apk):
    """确认出处水印真的打进了包里，返回命中的构件名。

    ASCII 串在 arsc 里可能被存成 UTF-8 也可能被存成 UTF-16（整个字符串含非 ASCII
    字符时会切成 UTF-16），所以两种编码都要试。
    """
    needles = [
        WATERMARK_NEEDLE.encode("utf-8"),
        WATERMARK_NEEDLE.encode("utf-16-le"),
    ]
    hits = []
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not (name.endswith(".dex") or name == "resources.arsc"):
                continue
            data = z.read(name)
            if any(n in data for n in needles):
                hits.append(name)
    return hits


# ---------------------------------------------------------------- 主流程

def build():
    sdk = find_sdk_root()
    build_tools = find_build_tools(sdk)
    platform = find_platform(sdk)
    java_home = find_java_home()

    java = java_home / "bin" / "java.exe"
    javac = java_home / "bin" / "javac.exe"
    android_jar = platform / "android.jar"
    aapt2 = build_tools / "aapt2.exe"
    zipalign = build_tools / "zipalign.exe"
    d8_jar = build_tools / "lib" / "d8.jar"
    apksigner_jar = build_tools / "lib" / "apksigner.jar"

    print("=" * 68)
    print("OPPO Watch 课程表 —— 构建")
    print("=" * 68)
    print(f"  SDK          : {sdk}")
    print(f"  Build-Tools  : {build_tools.name}")
    print(f"  Platform     : {platform.name}")
    print(f"  JDK          : {java_home}")
    print("=" * 68)

    for d in (GEN, OBJ, DEX, OUT):
        d.mkdir(parents=True, exist_ok=True)

    # ---------------------------------------------------- 0. 生成资源（下载二维码）
    run([sys.executable, ROOT / "tools" / "gen_qr.py"],
        "0/7 生成手机端 App 下载二维码")

    # ---------------------------------------------------- 1. 编译资源
    res_zip = BUILD / "res.zip"
    run([aapt2, "compile", "--dir", APP_SRC / "res", "-o", res_zip],
        "1/7 aapt2 compile（编译手写资源）")

    genre_zip = BUILD / "genres.zip"
    if GEN_RES.is_dir():
        run([aapt2, "compile", "--dir", GEN_RES, "-o", genre_zip],
            "1/7 aapt2 compile（编译生成资源）")

    # ---------------------------------------------------- 2. 链接资源 + 生成 R.java
    base_apk = BUILD / "base.apk"
    link_cmd = [
        aapt2, "link",
        "-o", base_apk,
        "-I", android_jar,
        "--manifest", APP_SRC / "AndroidManifest.xml",
        "--java", GEN,
        "--min-sdk-version", MIN_SDK,
        "--target-sdk-version", TARGET_SDK,
        "--version-code", VERSION_CODE,
        "--version-name", VERSION_NAME,
        res_zip,
    ]
    if genre_zip.exists():
        link_cmd.append(genre_zip)
    run(link_cmd, "2/7 aapt2 link（链接资源 / 生成 R.java）")

    # ---------------------------------------------------- 3. 编译 Java
    java_files = sorted((APP_SRC / "java").rglob("*.java"))
    r_java = sorted(GEN.rglob("R.java"))
    if not java_files:
        raise SystemExit("没有找到 Java 源文件")
    run([
        javac,
        "--release", "11",
        "-encoding", "UTF-8",
        "-nowarn",
        "-cp", android_jar,
        "-d", OBJ,
        *java_files, *r_java,
    ], f"3/7 javac（编译 Java，共 {len(java_files) + len(r_java)} 个文件）")

    # ---------------------------------------------------- 4. 转 dex
    class_files = sorted(OBJ.rglob("*.class"))
    if not class_files:
        raise SystemExit("javac 没有产出 class 文件")
    run([
        java, "-Xmx2048m", "-cp", d8_jar, "com.android.tools.r8.D8",
        "--release",
        "--min-api", MIN_SDK,
        "--lib", android_jar,
        "--output", DEX,
        *class_files,
    ], f"4/7 d8（{len(class_files)} 个 class -> classes.dex）")

    # ---------------------------------------------------- 5. 合并 dex 到 apk
    print("\n>>> 5/7 打包 classes.dex 到 APK")
    unaligned = BUILD / "unaligned.apk"
    shutil.copyfile(base_apk, unaligned)
    with zipfile.ZipFile(unaligned, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(DEX / "classes.dex", "classes.dex")
    print(f"      已写入 classes.dex（{((DEX / 'classes.dex').stat().st_size) / 1024:.1f} KB）")

    # ---------------------------------------------------- 6. 对齐
    aligned = BUILD / "aligned.apk"
    run([zipalign, "-f", "-p", "4", unaligned, aligned],
        "6/7 zipalign（4 字节对齐）")

    # ---------------------------------------------------- 7. 签名
    ensure_keystore(java_home)
    signed = OUT / APK_NAME
    run([
        java, "-jar", apksigner_jar, "sign",
        "--ks", KEYSTORE,
        "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--ks-key-alias", "androiddebugkey",
        "--out", signed,
        aligned,
    ], "7/7 apksigner（签名）")

    run([java, "-jar", apksigner_jar, "verify", "--print-certs", signed],
        "校验签名")

    hits = check_watermark(signed)
    if hits:
        print(f"\n    水印自检：在 {'、'.join(hits)} 里找到出处标记 「{WATERMARK_NEEDLE}」")
    else:
        print(f"\n    [注意] 水印自检：{signed.name} 里没找到出处标记 "
              f"「{WATERMARK_NEEDLE}」—— 检查 strings.xml 与源码里的署名是否被删掉了")

    size_kb = signed.stat().st_size / 1024
    print("\n" + "=" * 68)
    print(f"构建成功：{signed}")
    print(f"APK 大小：{size_kb:.1f} KB")
    print("=" * 68)
    return signed


# ---------------------------------------------------------------- 设备操作

def install(sdk, apk):
    adb = find_adb(sdk)
    run([adb, "devices"], "检查设备连接")
    print("\n>>> 安装到手表（首次安装）")
    proc = subprocess.run([str(adb), "install", "-r", str(apk)],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = proc.stdout.decode("utf-8", errors="replace")
    print(out.strip())
    if "Success" not in out:
        print("\n[安装失败] 下面是安装器返回的详细错误（不猜测原因）：")
        run([adb, "install", "-r", "-d", str(apk)], "带调试信息重试")
        raise SystemExit(1)
    print("\n安装成功")
    run([adb, "shell", "am", "start", "-n", f"{PKG}/.MainActivity"], "启动 App")


def logcat(sdk):
    adb = find_adb(sdk)
    print("\n>>> 跟踪日志（Ctrl+C 退出）")
    subprocess.run([str(adb), "logcat", "-v", "time",
                    f"{PKG}:V", "AndroidRuntime:E", "*:S"])


def main():
    ap = argparse.ArgumentParser(description="OPPO Watch 课程表构建脚本")
    ap.add_argument("--clean", action="store_true", help="清理构建产物后退出")
    ap.add_argument("--install", action="store_true", help="构建后安装到手表")
    ap.add_argument("--logcat", action="store_true", help="安装后跟踪日志")
    args = ap.parse_args()

    if args.clean:
        clean()
        return

    apk = build()

    if args.install or args.logcat:
        sdk = find_sdk_root()
        install(sdk, apk)
        if args.logcat:
            logcat(sdk)


if __name__ == "__main__":
    main()
