#!/usr/bin/env python3
"""绘制生物反应堆的 128×128 材质图集。

背景
----
几何由 Blender 产出（见 tools/README.md 的资产管线），每个面按所属材质做
盒式投影，UV 落在本图集的对应格子里。格位表由 Blender 侧写进
``tools/bioreactor_atlas.json``，本脚本只读不猜——两边共用一张表，避免
UV 落进一格空白。

图集布局：8×8 格，每格 16px。每格画一种材质（面板缝 / 铆钉 / 拉丝渐变 /
通风格栅），面 UV 内缩 0.5px 防止 mipmap 串到邻格。空白格填洋红，一旦某个
材质没画就会在游戏里直接暴露。

配色取自参考图的游戏内实机截图：米白机身、深灰底座、橙环、绿辉光、黄指示灯。

用法
----
    python tools/gen_bioreactor_texture.py

只依赖标准库（json / math / os / struct / zlib），与 tools/ 下其他脚本一致。
"""

import json
import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ATLAS_JSON = os.path.join(HERE, "bioreactor_atlas.json")
TEXTURE = os.path.join(PROJECT, "src", "main", "resources", "assets",
                       "mekck", "textures", "block", "mekck", "bioreactor", "bioreactor.png")

UNPAINTED = (255, 0, 255)      # 洋红 = 这一格漏画了
CORNER_MARK = (0, 0, 0)        # 每格右下角的 1px 角标，方便肉眼核对格位


class Canvas:
    """RGBA 画布；写 PNG 用标准库 zlib + struct，不引第三方依赖。"""

    def __init__(self, size):
        self.size = size
        self.px = bytearray(size * size * 4)

    def set(self, x, y, rgb):
        if not (0 <= x < self.size and 0 <= y < self.size):
            return
        o = (y * self.size + x) * 4
        self.px[o], self.px[o + 1], self.px[o + 2], self.px[o + 3] = rgb[0], rgb[1], rgb[2], 255

    def rect(self, x0, y0, x1, y1, rgb):
        for y in range(int(y0), int(y1)):
            for x in range(int(x0), int(x1)):
                self.set(x, y, rgb)

    def hline(self, x0, x1, y, rgb):
        self.rect(x0, y, x1, y + 1, rgb)

    def vline(self, x, y0, y1, rgb):
        self.rect(x, y0, x + 1, y1, rgb)

    def vgrad(self, x0, y0, x1, y1, top, bottom):
        h = max(1, int(y1) - int(y0))
        for i in range(h):
            t = i / max(1, h - 1)
            rgb = tuple(int(round(top[c] + (bottom[c] - top[c]) * t)) for c in range(3))
            self.rect(x0, y0 + i, x1, y0 + i + 1, rgb)

    def noise(self, x0, y0, x1, y1, amount, seed):
        """确定性伪随机点噪：同一 seed 每次结果一致，贴图不会在版本间漂移。"""
        s = seed
        for y in range(int(y0), int(y1)):
            for x in range(int(x0), int(x1)):
                s = (s * 1103515245 + 12345) & 0x7FFFFFFF
                d = ((s >> 16) % (2 * amount + 1)) - amount
                o = (y * self.size + x) * 4
                self.set(x, y, (max(0, min(255, self.px[o] + d)),
                                max(0, min(255, self.px[o + 1] + d)),
                                max(0, min(255, self.px[o + 2] + d))))

    def to_png(self):
        raw = bytearray()
        stride = self.size * 4
        for y in range(self.size):
            raw.append(0)                                  # filter 0 (None)
            raw += self.px[y * stride:(y + 1) * stride]

        def chunk(tag, data):
            return (struct.pack(">I", len(data)) + tag + data
                    + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

        return (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", self.size, self.size, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
                + chunk(b"IEND", b""))


# ─────────────────────────── 各材质的画法 ───────────────────────────
def _rivets(c, ox, oy, rgb, size, inset):
    for cx, cy in ((inset, inset), (CELL - inset - size, inset),
                   (inset, CELL - inset - size), (CELL - inset - size, CELL - inset - size)):
        c.rect(ox + cx, oy + cy, ox + cx + size, oy + cy + size, rgb)


def paint(c, name, ox, oy):
    if name == "BODY_WHITE":
        c.vgrad(ox, oy, ox + CELL, oy + CELL, (236, 236, 230), (206, 206, 198))
        c.hline(ox, ox + CELL, oy + 7, (188, 188, 180))
        c.hline(ox, ox + CELL, oy + 8, (222, 222, 214))
        c.noise(ox, oy, ox + CELL, oy + CELL, 2, 0x1234)
        _rivets(c, ox, oy, (176, 176, 166), 1, 2)
    elif name == "BODY_WHITE_ALT":
        c.vgrad(ox, oy, ox + CELL, oy + CELL, (224, 224, 218), (194, 194, 186))
        c.noise(ox, oy, ox + CELL, oy + CELL, 2, 0x5678)
        _rivets(c, ox, oy, (168, 168, 158), 1, 3)
    elif name == "BODY_PANEL":
        c.rect(ox, oy, ox + CELL, oy + CELL, (198, 198, 190))
        c.rect(ox + 1, oy + 1, ox + CELL - 1, oy + CELL - 1, (214, 214, 206))
        c.rect(ox + 4, oy + 4, ox + CELL - 4, oy + CELL - 4, (190, 190, 182))
        c.noise(ox, oy, ox + CELL, oy + CELL, 1, 0x9ABC)
    elif name == "DARK":
        c.rect(ox, oy, ox + CELL, oy + CELL, (48, 52, 60))
        c.noise(ox, oy, ox + CELL, oy + CELL, 3, 0x2468)
        _rivets(c, ox, oy, (68, 73, 82), 1, 2)
    elif name == "DARK_RIB":
        c.rect(ox, oy, ox + CELL, oy + CELL, (38, 42, 50))
        for i in range(0, CELL, 3):
            c.hline(ox, ox + CELL, oy + i, (58, 63, 72))
            c.hline(ox, ox + CELL, oy + i + 1, (26, 29, 35))
    elif name == "DARK_METAL":
        c.vgrad(ox, oy, ox + CELL, oy + CELL, (94, 100, 110), (58, 63, 72))
        c.noise(ox, oy, ox + CELL, oy + CELL, 2, 0xBEEF)
        c.hline(ox, ox + CELL, oy + 2, (124, 131, 142))
    elif name == "ORANGE":
        c.vgrad(ox, oy, ox + CELL, oy + CELL, (240, 150, 64), (176, 88, 14))
        c.hline(ox, ox + CELL, oy + 3, (250, 196, 130))
    elif name == "GREEN_GLOW":
        # 中心亮、边缘暗：被拉伸到任意面尺寸时都读作「发光核」
        for y in range(CELL):
            for x in range(CELL):
                dx, dy = (x - 7.5) / 7.5, (y - 7.5) / 7.5
                t = 1.0 - min(1.0, math.sqrt(dx * dx + dy * dy))
                c.set(ox + x, oy + y, (int(28 + 62 * t), int(146 + 82 * t), int(86 + 62 * t)))
        c.hline(ox, ox + CELL, oy + 6, (152, 244, 188))
    elif name == "CANOPY_GLOW":
        # 遮阳板底面整片受光面很大，用高饱和绿在 MC 里会刺眼，压暗一档
        for y in range(CELL):
            for x in range(CELL):
                dx, dy = (x - 7.5) / 7.5, (y - 7.5) / 7.5
                t = 1.0 - min(1.0, math.sqrt(dx * dx + dy * dy))
                c.set(ox + x, oy + y, (int(16 + 34 * t), int(74 + 48 * t), int(48 + 34 * t)))
        c.hline(ox, ox + CELL, oy + 6, (96, 176, 128))
    elif name == "SCREEN":
        c.rect(ox, oy, ox + CELL, oy + CELL, (8, 26, 18))
        c.rect(ox + 2, oy + 3, ox + CELL - 2, oy + CELL - 4, (20, 78, 52))
        c.rect(ox + 4, oy + 5, ox + CELL - 5, oy + 9, (56, 190, 118))
        for y in range(oy, oy + CELL, 2):        # 扫描线
            c.rect(ox, y, ox + CELL, y + 1, (11, 34, 23))
    elif name == "VENT":
        c.rect(ox, oy, ox + CELL, oy + CELL, (40, 44, 50))
        for i in range(1, CELL - 1, 3):
            c.rect(ox + 1, oy + i, ox + CELL - 1, oy + i + 2, (20, 22, 26))
    elif name == "LAMP":
        c.rect(ox, oy, ox + CELL, oy + CELL, (38, 42, 50))
        c.rect(ox + 5, oy + 4, ox + CELL - 5, oy + CELL - 4, (232, 182, 30))
        c.rect(ox + 7, oy + 6, ox + CELL - 7, oy + CELL - 6, (255, 242, 164))
    elif name == "CYAN_DASH":
        c.rect(ox, oy, ox + CELL, oy + CELL, (44, 48, 56))
        c.rect(ox + 2, oy + 5, ox + 7, oy + 8, (86, 222, 236))
        c.rect(ox + 9, oy + 5, ox + CELL - 2, oy + 8, (86, 222, 236))
    else:
        raise SystemExit("未实现的材质: " + name)


def main():
    with open(ATLAS_JSON, "r", encoding="utf-8") as fh:
        atlas = json.load(fh)
    size = int(atlas["size"])
    global CELL
    CELL = int(atlas["cell"])
    materials = atlas["materials"]

    canvas = Canvas(size)
    canvas.rect(0, 0, size, size, UNPAINTED)
    for name, (col, row) in materials.items():
        paint(canvas, name, col * CELL, row * CELL)
        canvas.set(col * CELL + CELL - 1, row * CELL + CELL - 1, CORNER_MARK)

    os.makedirs(os.path.dirname(TEXTURE), exist_ok=True)
    with open(TEXTURE, "wb") as fh:
        fh.write(canvas.to_png())

    print("图集: %d×%d，%d×%d 格，每格 %dpx" % (size, size, size // CELL, size // CELL, CELL))
    print("材质 %d 种: %s" % (len(materials), ", ".join(sorted(materials))))
    for name, (col, row) in sorted(materials.items(), key=lambda kv: (kv[1], kv[0])):
        print("  (%d,%d) %s" % (col, row, name))
    print("输出: %s (%d bytes)" % (TEXTURE, os.path.getsize(TEXTURE)))


if __name__ == "__main__":
    main()
