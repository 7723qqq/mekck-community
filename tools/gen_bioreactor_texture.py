#!/usr/bin/env python3
"""绘制生物反应炉的 256×256 材质图集。

背景
----
几何由 Blender 产出（见 tools/README.md 的资产管线），每个面按所属材质做
盒式投影，UV 落在本图集的对应格子里。格位表由 Blender 侧写进
``tools/bioreactor_atlas.json``，本脚本只读不猜——两边共用一张表，避免
UV 落进一格空白。

图集格式（由 ``blender_export_true_mek_bioreactor.py`` 写出）::

    {"size": 256.0, "inset": 2.0,
     "materials": {"MAT_Mek_SlateSteel": [x0, y0, x1, y1], ...}}

矩形而非均分格位：多数材质是 64×64，警示条纹与终端屏是 128×128，
均分网格表达不了。未登记的格子填洋红，一旦某个材质没画就会在游戏里直接暴露。

纯标准库（json / math / os / struct / zlib），不引 Pillow。

用法
----
    python tools/gen_bioreactor_texture.py
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


class Canvas:
    """RGBA 画布；写 PNG 用标准库 zlib + struct，不引第三方依赖。"""

    def __init__(self, size):
        self.size = size
        self.px = bytearray(size * size * 4)

    def set(self, x, y, rgb, a=255):
        if not (0 <= x < self.size and 0 <= y < self.size) or a <= 0:
            return
        o = (y * self.size + x) * 4
        if a >= 255:
            self.px[o], self.px[o + 1], self.px[o + 2], self.px[o + 3] = rgb[0], rgb[1], rgb[2], 255
            return
        t = a / 255.0
        inv = 1.0 - t
        for i in range(3):
            self.px[o + i] = max(0, min(255, int(rgb[i] * t + self.px[o + i] * inv)))
        self.px[o + 3] = max(0, min(255, int(255 * t + self.px[o + 3] * inv)))

    def rect(self, x0, y0, x1, y1, rgb, a=255):
        for y in range(int(y0), int(y1)):
            for x in range(int(x0), int(x1)):
                self.set(x, y, rgb, a)

    def rect_outline(self, x0, y0, x1, y1, rgb, w=1, a=255):
        """描边画在矩形**内侧**（与 PIL 的 outline 语义一致）。"""
        for i in range(w):
            self.rect(x0 + i, y0 + i, x1 - i + 1, y0 + i + 1, rgb, a)
            self.rect(x0 + i, y1 - i, x1 - i + 1, y1 - i + 1, rgb, a)
            self.rect(x0 + i, y0 + i, x0 + i + 1, y1 - i + 1, rgb, a)
            self.rect(x1 - i, y0 + i, x1 - i + 1, y1 - i + 1, rgb, a)

    def line(self, x0, y0, x1, y1, rgb, w=1, a=255):
        x0, y0, x1, y1 = int(x0), int(y0), int(x1), int(y1)
        dx, dy = abs(x1 - x0), abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx - dy
        while True:
            for ox in range(w):
                for oy in range(w):
                    self.set(x0 + ox, y0 + oy, rgb, a)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 > -dy:
                err -= dy
                x0 += sx
            if e2 < dx:
                err += dx
                y0 += sy

    def polygon_fill(self, pts, rgb, a=255):
        if len(pts) < 3:
            return
        ys = [p[1] for p in pts]
        for y in range(max(0, min(ys)), min(self.size, max(ys) + 1)):
            xs = []
            n = len(pts)
            for i in range(n):
                ax, ay = pts[i]
                bx, by = pts[(i + 1) % n]
                if (ay <= y < by) or (by <= y < ay):
                    xs.append(ax + (y - ay) * (bx - ax) / (by - ay))
            xs.sort()
            for i in range(0, len(xs) - 1, 2):
                self.rect(xs[i], y, xs[i + 1] + 1, y + 1, rgb, a)

    def ellipse_fill(self, cx, cy, r, rgb, a=255, outline=None, ow=1):
        for y in range(int(cy - r) - 1, int(cy + r) + 2):
            for x in range(int(cx - r) - 1, int(cx + r) + 2):
                d = math.hypot(x - cx, y - cy)
                if outline is not None and r - ow < d <= r + ow:
                    self.set(x, y, outline, 255)
                elif d <= r:
                    self.set(x, y, rgb, a)

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
# 每个 painter 收 (canvas, ox, oy)：ox/oy 是该格在图集里的左上角绝对坐标，
# 格内用局部坐标书写。
def _slatesteel(c, ox, oy):
    for ly in range(64):
        for lx in range(64):
            v = 52 + ((lx ^ ly) & 3)
            c.set(ox + lx, oy + ly, (v, v + 4, v + 10))
    c.rect_outline(ox, oy, ox + 63, oy + 63, (75, 82, 95), 2)
    c.rect_outline(ox + 2, oy + 2, ox + 61, oy + 61, (38, 42, 48), 1)


def _gunmetaldark(c, ox, oy):
    for ly in range(64):
        for lx in range(64):
            v = 28 + (((ox + lx) * 3 + (oy + ly) * 7) % 5)
            c.set(ox + lx, oy + ly, (v, v + 2, v + 5))
    c.rect_outline(ox, oy, ox + 63, oy + 63, (42, 46, 54), 2)


def _alloylight(c, ox, oy):
    for ly in range(64):
        for lx in range(64):
            v = 95 + (((ox + lx) ^ ((oy + ly) * 2)) % 6)
            c.set(ox + lx, oy + ly, (v - 2, v + 2, v + 8))
    c.rect_outline(ox, oy, ox + 63, oy + 63, (125, 134, 148), 2)
    c.rect_outline(ox + 2, oy + 2, ox + 61, oy + 61, (75, 82, 94), 1)


def _brightinox(c, ox, oy):
    for ly in range(64):
        for lx in range(64):
            v = 165 + (((ox + lx) + (oy + ly) * 2) % 8)
            c.set(ox + lx, oy + ly, (v - 5, v, v + 6))
    c.rect_outline(ox, oy, ox + 63, oy + 63, (205, 212, 222), 2)
    # 螺栓头的坐标沿用图集绝对位置（bx/by 就是 192+ 那一带的全局值）
    for bx, by in ((208, 16), (240, 16), (208, 48), (240, 48)):
        c.ellipse_fill(bx, by, 6, (130, 136, 146), outline=(220, 228, 238), ow=1)
        c.ellipse_fill(bx, by, 2, (80, 85, 95))


def _blastglass(c, ox, oy):
    # 深暗复合铅玻璃 + 中心生物辉光；中心在格内 (32,32)
    for ly in range(64):
        for lx in range(64):
            dist = math.hypot(lx - 32, ly - 32)
            glow = max(0.0, 1.0 - dist / 34.0)
            c.set(ox + lx, oy + ly, (int(15 + 35 * glow), int(45 + 140 * glow), int(60 + 110 * glow)))
    # 加强筋网格
    for lx in range(0, 64, 10):
        c.line(ox + lx, oy, ox + lx, oy + 63, (20, 60, 70))
    for ly in range(0, 64, 10):
        c.line(ox, oy + ly, ox + 63, oy + ly, (20, 60, 70))
    # 45° 双道反光带
    for i in range(-20, 80):
        gx = ox + i
        gy = oy + (i - 10)
        if ox + 4 <= gx <= ox + 60 and oy + 4 <= gy <= oy + 60:
            c.line(gx, gy, gx + 4, gy, (160, 240, 255), 1, 200)
    for i in range(-20, 80):
        gx = ox + i
        gy = oy + (i + 8)
        if ox + 4 <= gx <= ox + 60 and oy + 4 <= gy <= oy + 60:
            c.line(gx, gy, gx + 6, gy, (210, 250, 255), 2, 230)
    c.rect_outline(ox, oy, ox + 63, oy + 63, (22, 25, 30), 3)
    c.rect_outline(ox + 3, oy + 3, ox + 60, oy + 60, (45, 52, 60), 1)


def _bioslurry(c, ox, oy):
    for ly in range(64):
        for lx in range(64):
            dist = math.hypot(lx - 32, ly - 32)
            val = max(0, min(255, int(220 - dist * 2.5)))
            c.set(ox + lx, oy + ly, (int(20 + val * 0.15), val, int(60 + val * 0.25)))


def _cyanglow(c, ox, oy):
    c.rect(ox, oy, ox + 64, oy + 64, (0, 240, 255))
    c.rect(ox + 4, oy + 4, ox + 60, oy + 60, (80, 250, 255))
    c.rect(ox + 10, oy + 10, ox + 54, oy + 54, (180, 255, 255))


def _portflange(c, ox, oy):
    c.rect(ox, oy, ox + 64, oy + 64, (36, 40, 48))
    c.rect_outline(ox, oy, ox + 63, oy + 63, (70, 78, 90), 2)
    c.polygon_fill([(ox + 20, oy + 6), (ox + 44, oy + 6), (ox + 58, oy + 20),
                    (ox + 58, oy + 44), (ox + 44, oy + 58), (ox + 20, oy + 58),
                    (ox + 6, oy + 44), (ox + 6, oy + 20)], (24, 27, 32))
    c.rect_outline(ox + 22, oy + 22, ox + 42, oy + 42, (0, 240, 255), 2)
    c.rect(ox + 24, oy + 24, ox + 40, oy + 40, (180, 255, 255))


def _hazardstripes(c, ox, oy):
    c.rect(ox, oy, ox + 128, oy + 128, (235, 185, 20))
    for i in range(-128, 256, 16):
        c.polygon_fill([(ox + i, oy), (ox + i + 8, oy),
                        (ox + i + 8 + 128, oy + 128), (ox + i + 128, oy + 128)],
                       (28, 30, 34))
    c.rect_outline(ox, oy, ox + 127, oy + 127, (40, 44, 50), 2)


def _screenterminal(c, ox, oy):
    c.rect(ox, oy, ox + 128, oy + 128, (16, 20, 26))
    c.rect_outline(ox, oy, ox + 127, oy + 127, (0, 210, 240), 2)
    c.rect(ox + 2, oy + 2, ox + 125, oy + 18, (24, 32, 42))
    c.rect(ox + 6, oy + 6, ox + 32, oy + 14, (0, 220, 255))
    c.line(ox + 2, oy + 18, ox + 125, oy + 18, (0, 180, 220))
    c.rect(ox + 8, oy + 26, ox + 118, oy + 38, (20, 25, 32))
    c.rect_outline(ox + 8, oy + 26, ox + 118, oy + 38, (50, 60, 75), 1)
    c.rect(ox + 10, oy + 28, ox + 100, oy + 36, (0, 230, 130))
    c.rect(ox + 8, oy + 44, ox + 118, oy + 56, (20, 25, 32))
    c.rect_outline(ox + 8, oy + 44, ox + 118, oy + 56, (50, 60, 75), 1)
    c.rect(ox + 10, oy + 46, ox + 82, oy + 54, (0, 200, 255))
    pts = [(ox + 8, oy + 82), (ox + 22, oy + 82), (ox + 28, oy + 68),
           (ox + 34, oy + 96), (ox + 40, oy + 76), (ox + 46, oy + 82),
           (ox + 62, oy + 82), (ox + 68, oy + 64), (ox + 74, oy + 100),
           (ox + 80, oy + 82), (ox + 118, oy + 82)]
    for i in range(len(pts) - 1):
        c.line(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], (0, 245, 255), 2)
    for bx in (8, 46, 84):
        c.rect(ox + bx, oy + 102, ox + bx + 34, oy + 120, (30, 42, 54))
        c.rect_outline(ox + bx, oy + 102, ox + bx + 34, oy + 120, (0, 210, 240), 1)
    c.rect(ox + 84, oy + 102, ox + 118, oy + 120, (0, 180, 120))
    c.rect_outline(ox + 84, oy + 102, ox + 118, oy + 120, (0, 255, 180), 1)


# 材质 -> (画法, 该画法预期的格子边长)。预期边长不是装饰：画法内部是按
# 64/128 逐像素写死的，格位尺寸一变就会静默画错位置/画到隔壁格去。
PAINTERS = {
    "MAT_Mek_SlateSteel": (_slatesteel, 64),
    "MAT_Mek_GunmetalDark": (_gunmetaldark, 64),
    "MAT_Mek_AlloyLight": (_alloylight, 64),
    "MAT_Mek_BrightInox": (_brightinox, 64),
    "MAT_Mek_BlastGlass": (_blastglass, 64),
    "MAT_Mek_BioSlurry": (_bioslurry, 64),
    "MAT_Mek_CyanGlow": (_cyanglow, 64),
    "MAT_Mek_PortFlange": (_portflange, 64),
    "MAT_Mek_HazardStripes": (_hazardstripes, 128),
    "MAT_Mek_ScreenTerminal": (_screenterminal, 128),
}


def main():
    with open(ATLAS_JSON, "r", encoding="utf-8") as fh:
        atlas = json.load(fh)
    size = int(atlas["size"])
    materials = atlas["materials"]

    missing = sorted(set(materials) - set(PAINTERS))
    if missing:
        raise SystemExit(
            "ATLAS 里这些材质没有画法（本脚本会在游戏里留洋红空块）:\n  "
            + "\n  ".join(missing))
    unpainted = sorted(set(PAINTERS) - set(materials))
    if unpainted:
        print("注意: 有画法但 ATLAS 没登记，将不绘制: %s" % ", ".join(unpainted))

    # 格子边长必须与画法的预期一致，否则画法里的硬编码坐标会画错位置
    wrong = []
    for name, rect in sorted(materials.items()):
        x0, y0, x1, y1 = rect
        want = PAINTERS[name][1]
        if (x1 - x0, y1 - y0) != (want, want):
            wrong.append("%s: ATLAS 给的是 %dx%d，画法按 %dx%d 写的"
                         % (name, x1 - x0, y1 - y0, want, want))
        if not (0 <= x0 < x1 <= size and 0 <= y0 < y1 <= size):
            wrong.append("%s: 矩形 %s 越出 %dx%d 图集" % (name, rect, size, size))
    if wrong:
        raise SystemExit("ATLAS 与画法不匹配:\n  " + "\n  ".join(wrong))

    canvas = Canvas(size)
    canvas.rect(0, 0, size, size, UNPAINTED)
    for name, rect in sorted(materials.items()):
        x0, y0, x1, y1 = rect
        PAINTERS[name][0](canvas, x0, y0)

    # 覆盖检查：任何残留洋红都说明有格子没画到
    leftover = sum(
        1 for o in range(0, size * size * 4, 4)
        if canvas.px[o] == 255 and canvas.px[o + 1] == 0 and canvas.px[o + 2] == 255)
    if leftover:
        raise SystemExit("图集里还有 %d 个洋红像素未覆盖，检查上面各 painter 的覆盖范围" % leftover)

    os.makedirs(os.path.dirname(TEXTURE), exist_ok=True)
    tmp = TEXTURE + ".part"
    with open(tmp, "wb") as fh:
        fh.write(canvas.to_png())
    os.replace(tmp, TEXTURE)

    print("图集: %d×%d，%d 种材质，inset=%s"
          % (size, size, len(materials), atlas.get("inset")))
    for name, rect in sorted(materials.items()):
        print("  %-26s %s" % (name, rect))
    print("输出: %s (%d bytes)" % (TEXTURE, os.path.getsize(TEXTURE)))


if __name__ == "__main__":
    main()
