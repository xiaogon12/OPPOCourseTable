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
手表 UI 调试小工具（比截图可靠：截图在 OPPO 手表上会返回缓存帧）。

用法：
    python tools/ui.py                 # 打印当前界面的可点击/有文字节点
    python tools/ui.py tap "导入课程表"  # 按文字点击（点击整个可点击祖先的中心）
    python tools/ui.py swipe 380 233 80 233 250
    python tools/ui.py key WAKEUP
"""

import re
import subprocess
import time
import sys
import xml.etree.ElementTree as ET

def _find_adb():
    """自动探测 adb：PATH -> ANDROID_HOME/ANDROID_SDK_ROOT -> %LOCALAPPDATA%\\Android\\Sdk"""
    import os
    import shutil as _sh
    hit = _sh.which("adb")
    if hit:
        return hit
    roots = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(key)
        if v:
            roots.append(v)
    local = os.environ.get("LOCALAPPDATA")
    if local:
        roots.append(os.path.join(local, "Android", "Sdk"))
    for r in roots:
        p = os.path.join(r, "platform-tools", "adb.exe")
        if os.path.exists(p):
            return p
    return "adb"


ADB = _find_adb()
PKG = "com.liyan.coursetable"


def sh(*args, binary=False):
    p = subprocess.run([ADB] + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def dump():
    sh("shell", "uiautomator", "dump", "/data/local/tmp/ui.xml")
    raw = sh("shell", "cat", "/data/local/tmp/ui.xml")
    i = raw.find("<?xml")
    if i < 0:
        raise SystemExit("拿不到界面树：\n" + raw[:500])
    return ET.fromstring(raw[i:])


def parse_bounds(b):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    return x1, y1, x2, y2


def walk(node, out, depth=0):
    out.append((depth, node))
    for c in node:
        walk(c, out, depth + 1)


def find_path(node, target, path=(), exact=False):
    """返回从根到第一个匹配 target 的节点的路径

    exact=True 时要求 text 完全相等；否则只要包含即可。
    """
    txt = node.get("text") or ""
    if (txt == target) if exact else (target in txt):
        return path + (node,)
    for c in node:
        r = find_path(c, target, path + (node,), exact)
        if r:
            return r
    return None


def first_scrollable(path):
    for n in path:
        if n.get("scrollable") == "true":
            return n
    return None


def biggest_scrollable(root):
    best = None
    best_area = -1
    items = []
    walk(root, items)
    for _, n in items:
        if n.get("scrollable") != "true":
            continue
        b = parse_bounds(n.get("bounds"))
        if not b:
            continue
        area = (b[2] - b[0]) * (b[3] - b[1])
        if area > best_area:
            best_area = area
            best = n
    return best


def swipe_down_up(sv, up=True):
    b = parse_bounds(sv.get("bounds"))
    if not b:
        return False
    cx = (b[0] + b[2]) // 2
    if up:      # 内容往上走（看下面的内容）
        y1, y2 = b[3] - 40, b[1] + 40
    else:
        y1, y2 = b[1] + 40, b[3] - 40
    sh("shell", "input", "swipe", str(cx), str(y1), str(cx), str(y2), "260")
    time.sleep(0.7)
    return True


def ensure_visible(target, tries=6, exact=False):
    """目标不在屏幕内时，在它所在的 ScrollView 里滑到可见为止"""
    for _ in range(tries):
        root = dump()
        path = find_path(root, target, exact=exact)
        if path:
            b = parse_bounds(path[-1].get("bounds"))
            if b:
                cy = (b[1] + b[3]) // 2
                if 0 <= b[1] and b[3] <= 466:
                    return path
            sv = first_scrollable(path)
            if sv is None:
                return path
            swipe_down_up(sv, up=not (b and b[1] < 0))
            continue
        # 目标还不在树里（在滚动区外），滑一下再找
        sv = biggest_scrollable(root)
        if sv is None:
            return None
        swipe_down_up(sv, up=True)
    return None


def describe(node):
    cls = (node.get("class") or "").split(".")[-1]
    return cls


def main():
    args = sys.argv[1:]
    if not args:
        root = dump()
        items = []
        walk(root, items)
        for depth, n in items:
            t = n.get("text") or ""
            cls = describe(n)
            clickable = n.get("clickable") == "true"
            scrollable = n.get("scrollable") == "true"
            if not t and not clickable and not scrollable:
                continue
            b = parse_bounds(n.get("bounds"))
            center = "" if not b else f"({(b[0] + b[2]) // 2},{(b[1] + b[3]) // 2})"
            flag = "".join(["C" if clickable else "-", "S" if scrollable else "-"])
            print(f'{"  " * depth}{flag} {cls:12s} {center:12s} {t}')
        return

    cmd = args[0]
    if cmd == "tap":
        target = args[1]
        # 先精确匹配（避免 "课表" 误命中 "课表开始的第一天"），失败再退回包含匹配
        path = ensure_visible(target, exact=True) or ensure_visible(target, exact=False)
        if path is None:
            print(f"没找到 / 滚不到：{target}")
            sys.exit(1)
        chosen = path[-1]
        for n in reversed(path):
            if n.get("clickable") == "true":
                chosen = n
                break
        b = parse_bounds(chosen.get("bounds"))
        x = (b[0] + b[2]) // 2
        y = (b[1] + b[3]) // 2
        print(f"tap '{(path[-1].get('text') or '')}' -> ({x},{y})  bounds={chosen.get('bounds')}")
        sh("shell", "input", "tap", str(x), str(y))
    elif cmd == "find":
        root = dump()
        path = find_path(root, args[1])
        if not path:
            print("没找到")
            sys.exit(1)
        for n in path:
            print(f"  {describe(n):14s} {n.get('bounds')}  {n.get('text')}")
    elif cmd == "swipe":
        print("swipe " + " ".join(args[1:]))
        sh("shell", "input", "swipe", *args[1:])
    elif cmd == "key":
        sh("shell", "input", "keyevent", "KEYCODE_" + args[1])
    elif cmd == "log":
        print(sh("logcat", "-d", "-v", "brief"))


if __name__ == "__main__":
    main()
