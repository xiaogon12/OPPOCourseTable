#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成手表端要用的二维码资源（构建的第 0 步，由 tools/build.py 自动调用）。

现在只有一张：**手机端 App 的下载二维码**，扫出来指向 GitHub Releases 页。

> 手表上原来那张「提示词二维码」已经删掉了 —— 课表现在只从手机端 App
> 推过来，给 AI 的提示词在手机 App 的「批量导入 JSON」页里，手表上不需要。

手表是圆形屏幕，二维码最大只能用内接正方形（约 466 / 1.414 ≈ 330px），
所以这里输出「1 像素 = 1 模块」的无损位图，交给 App 端的 QrView 按整数倍
放大绘制（关闭抗锯齿与插值），边缘绝对锐利，手机才好扫。

输出：
  build/genres/res/drawable-nodpi/qr_download.png   下载二维码（1 像素 / 模块，无静默区）
  build/genres/res/values/gen_qr.xml                下载地址，供界面显示（唯一来源）
"""

import shutil
import sys
from pathlib import Path

import segno

ROOT = Path(__file__).resolve().parent.parent
GEN_RES = ROOT / "build" / "genres" / "res"

# ------------------------------------------------------------------ 下载地址
# 二维码和手表界面上显示的那行地址都来自这里。换仓库名 / 改发布页只改这一处，
# 重新构建即可，不会出现「二维码与文字对不上」的情况。
DOWNLOADS_URL = "https://github.com/xiaogon12/OPPOCourseTable/releases/latest"

# 圆屏内接正方形（466 / 1.414），用来评估每个模块能分到多少像素
AVAILABLE_PX = 330


def xml_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main():
    # 先整个重建：上一次生成、这一次不再需要的资源（例如已删掉的
    # qr_prompt.png / prompt.txt）如果留在包里，会变成除不掉的幽灵资源。
    shutil.rmtree(GEN_RES, ignore_errors=True)

    # 纯 ASCII 短链接，用不着 L 级纠错；M 级在这个尺寸下更抗污损
    qr = segno.make(DOWNLOADS_URL, error="m", boost_error=False)
    n = qr.symbol_size(scale=1, border=0)[0]

    print("=" * 68)
    print("生成手机端 App 下载二维码")
    print("=" * 68)
    print(f"  地址     : {DOWNLOADS_URL}")
    print(f"  QR 版本  : {qr.version}  (ECC = M)")
    print(f"  模块数   : {n} x {n}")
    print(f"  每模块   : {AVAILABLE_PX / n:.1f}px（可用宽度 ~{AVAILABLE_PX}px）")
    if n > 61:
        print("  [警告] 模块数偏多，手表上可能不好扫，换一个更短的地址")
    print("=" * 68)

    (GEN_RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    (GEN_RES / "values").mkdir(parents=True, exist_ok=True)

    png = GEN_RES / "drawable-nodpi" / "qr_download.png"
    qr.save(str(png), scale=1, border=0, dark="#000000", light="#ffffff")
    print(f"  已写出 : {png.relative_to(ROOT)}  ({png.stat().st_size} bytes)")

    vals = GEN_RES / "values" / "gen_qr.xml"
    vals.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- 由 tools/gen_qr.py 生成，不要手改 -->\n"
        "<resources>\n"
        '    <string name="downloads_url" translatable="false">'
        + xml_escape(DOWNLOADS_URL)
        + "</string>\n"
        "</resources>\n",
        encoding="utf-8",
    )
    print(f"  已写出 : {vals.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
