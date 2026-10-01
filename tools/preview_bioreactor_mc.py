#!/usr/bin/env python3
"""离线预览生物反应堆在游戏里的实际观感（纯标准库软件光栅化）。

为什么需要它
------------
游戏里的画面由三件事决定：OBJ 的几何、UV 采到贴图的哪个像素、以及
``ObjMeshRenderer.shadeOf`` 的**逐面平明暗**（上 1.0 / 南北 0.8 / 东西 0.6 /
下 0.5）——因为 ``MekCkRenderTypes.objSolid`` 走的是 ``rendertype_solid``，
只看 lightmap、不做法线漫反射。也就是说 Blender 里用 Cycles/EEVEE 渲染出来
的样子**不等于**游戏里的样子。

本脚本按同一套规则直接光栅化 OBJ + 贴图，产出一张与游戏内一致的预览图。
它同时是 UV 的验收工具：只要 UV 落错格子，预览里立刻会出现洋红空块
（贴图未绘制区就是洋红），不需要启动客户端。

用法
----
    python tools/preview_bioreactor_mc.py [--view front|hero|side|top] [--size 900]
"""

import math
import os
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
OBJ = os.path.join(PROJECT, "src", "main", "resources", "assets",
                   "mekck", "models", "mesh", "bioreactor.obj")
TEXTURE = os.path.join(PROJECT, "src", "main", "resources", "assets",
                       "mekck", "textures", "block", "mekck", "bioreactor", "bioreactor.png")
OUT_DIR = os.path.join(PROJECT, ".blender-preview")

BG = (24, 27, 32)

# 相机方向（由物体指向相机）。模型正面（视窗所在）是 MC -Z，所以「正面」视角的
# 相机要放在 -Z 一侧；控制台在 MC +X，从 -Z 看过去正好落在观者左手边（对齐参考图）。
# z 分量为正 = 相机在机器上方。
VIEWS = {
    "front": (0.00, 0.35, -0.93),   # 正面：看视窗与控制台
    "hero":  (0.55, 0.50, -0.67),   # 3/4 俯视英雄视角
    "side":  (0.95, 0.25, 0.15),    # 侧面：Mek 八角接口
    "back":  (0.00, 0.25, 0.96),    # 背面：排污口与后视窗
    "top":   (0.01, 0.999, 0.01),   # 顶视：俯视压力盖与吊耳
}


# ────────────────────────────── PNG 读写 ──────────────────────────────
def read_png(path):
    data = open(path, "rb").read()
    pos, idat, w = 8, b"", None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", chunk[:10])
            if depth != 8 or ctype != 6:
                raise SystemExit("只支持 8bit RGBA PNG")
        elif typ == b"IDAT":
            idat += chunk
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * 4
    out = bytearray(w * stride)
    prev = bytearray(stride)
    i = 0
    for y in range(h):
        f = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        if f == 1:
            for x in range(4, stride):
                line[x] = (line[x] + line[x - 4]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                c = prev[x - 4] if x >= 4 else 0
                b = prev[x]
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, out


def write_png(path, w, h, px):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)
        raw += px[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    open(path, "wb").write(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 6))
        + chunk(b"IEND", b""))


# ────────────────────────────── 读取 OBJ ──────────────────────────────
def read_obj(path):
    raw_verts, uvs, norms, tris = [], [], [], []
    verts = []
    v_map = {}
    group = None
    LAYER_OFFSETS = {
        "bioreactor_layer0": 0.0,
        "bioreactor_layer1": 1.0,
        "bioreactor_layer2": 2.0,
    }
    for line in open(path, encoding="utf-8"):
        p = line.split()
        if not p:
            continue
        if p[0] == "v":
            raw_verts.append((float(p[1]), float(p[2]), float(p[3])))
        elif p[0] == "vt":
            uvs.append((float(p[1]), float(p[2])))
        elif p[0] == "vn":
            norms.append((float(p[1]), float(p[2]), float(p[3])))
        elif p[0] == "g":
            group = p[1]
        elif p[0] == "f":
            y_off = LAYER_OFFSETS.get(group, 0.0)
            idx = [tuple(int(t) for t in tok.split("/")) for tok in p[1:]]
            remapped_idx = []
            for tok in idx:
                vi, vti, vni = tok
                key = (vi, y_off)
                if key not in v_map:
                    orig_v = raw_verts[vi - 1]
                    verts.append((orig_v[0], orig_v[1] + y_off, orig_v[2]))
                    v_map[key] = len(verts)
                remapped_idx.append((v_map[key], vti, vni))
            for k in range(1, len(remapped_idx) - 1):          # 扇形三角化，与 ObjMesh 一致
                tris.append((group, (remapped_idx[0], remapped_idx[k], remapped_idx[k + 1])))
    return verts, uvs, norms, tris


def shade_of(n):
    """复刻 ObjMeshRenderer.shadeOf：MC 逐面烘焙明暗，还原成 0..1 系数。"""
    if n[1] > 0.5:
        return 1.0
    if n[1] < -0.5:
        return 0.5
    return 0.8 if abs(n[2]) >= abs(n[0]) else 0.6


# ────────────────────────────── 光栅化 ──────────────────────────────
def render(verts, uvs, norms, tris, tex, tex_w, tex_h, view, size):
    cd = VIEWS[view]
    ln = math.sqrt(sum(c * c for c in cd))
    cam = [c / ln for c in cd]                       # 物体 -> 相机
    fwd = [-c for c in cam]
    world_up = (0.0, 1.0, 0.0)
    r = (fwd[1] * world_up[2] - fwd[2] * world_up[1],
         fwd[2] * world_up[0] - fwd[0] * world_up[2],
         fwd[0] * world_up[1] - fwd[1] * world_up[0])
    rl = math.sqrt(sum(c * c for c in r)) or 1.0
    r = [c / rl for c in r]
    u = (r[1] * fwd[2] - r[2] * fwd[1],
         r[2] * fwd[0] - r[0] * fwd[2],
         r[0] * fwd[1] - r[1] * fwd[0])

    def dot(a, b):
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    # 投影到相机平面并自适应取景
    proj = [(dot(p, r), dot(p, u), dot(p, fwd)) for p in verts]
    xs = [q[0] for q in proj]
    ys = [q[1] for q in proj]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    margin = 0.06
    span = max(maxx - minx, maxy - miny) * (1.0 + 2 * margin)
    scale = size / span
    ox = (size - (maxx - minx) * scale) / 2.0
    oy = (size - (maxy - miny) * scale) / 2.0
    cx = (minx + maxx) / 2.0
    cy = (miny + maxy) / 2.0
    scr = [((q[0] - cx) * scale + size / 2.0,
            size / 2.0 - (q[1] - cy) * scale, q[2]) for q in proj]

    fb = bytearray(size * size * 4)
    for y in range(size):
        for x in range(size):
            o = (y * size + x) * 4
            fb[o], fb[o + 1], fb[o + 2], fb[o + 3] = BG[0], BG[1], BG[2], 255
    zbuf = [1e30] * (size * size)
    # 统计量：用来证明"确实画出了东西"。否则一个整体绕序反了的 OBJ 会
    # 全被背面剔除掉，洋红计数为 0，UV 覆盖检查就会空过并报"无洋红空块"。
    stats = {"culled": 0, "front": 0, "degenerate": 0, "painted": 0}

    for _grp, (i0, i1, i2) in tris:
        p0, p1, p2 = scr[i0[0] - 1], scr[i1[0] - 1], scr[i2[0] - 1]
        n = norms[i0[2] - 1]
        facing = n[0] * cam[0] + n[1] * cam[1] + n[2] * cam[2]
        if facing <= 0.0:
            stats["culled"] += 1
            continue                                  # 背面剔除
        shade = shade_of(n)
        t0, t1, t2 = uvs[i0[1] - 1], uvs[i1[1] - 1], uvs[i2[1] - 1]
        area = (p1[0] - p0[0]) * (p2[1] - p0[1]) - (p1[1] - p0[1]) * (p2[0] - p0[0])
        if abs(area) < 1e-9:
            stats["degenerate"] += 1
            continue
        stats["front"] += 1
        painted_here = False
        x0 = max(0, int(min(p0[0], p1[0], p2[0])))
        x1 = min(size - 1, int(max(p0[0], p1[0], p2[0])) + 1)
        y0 = max(0, int(min(p0[1], p1[1], p2[1])))
        y1 = min(size - 1, int(max(p0[1], p1[1], p2[1])) + 1)
        for py in range(y0, y1 + 1):
            fy = py + 0.5
            for px in range(x0, x1 + 1):
                fx = px + 0.5
                w0 = ((p1[0] - fx) * (p2[1] - fy) - (p1[1] - fy) * (p2[0] - fx)) / area
                w1 = ((p2[0] - fx) * (p0[1] - fy) - (p2[1] - fy) * (p0[0] - fx)) / area
                w2 = 1.0 - w0 - w1
                if w0 < 0 or w1 < 0 or w2 < 0:
                    continue
                z = w0 * p0[2] + w1 * p1[2] + w2 * p2[2]
                zi = py * size + px
                # depth = dot(p, fwd)，fwd 由相机指向物体 → 值越小越近。
                # 初值 -1e30 配 `>=` 跳过，即保留**最近**的表面。
                if z >= zbuf[zi]:
                    continue
                # UV 线性插值；MC 侧 v 原点在左上，OBJ 是左下，故翻转
                uu = (w0 * t0[0] + w1 * t1[0] + w2 * t2[0]) * tex_w
                vv = (1.0 - (w0 * t0[1] + w1 * t1[1] + w2 * t2[1])) * tex_h
                tx = min(tex_w - 1, max(0, int(uu)))
                ty = min(tex_h - 1, max(0, int(vv)))
                to = (ty * tex_w + tx) * 4
                o = zi * 4
                for c in range(3):
                    fb[o + c] = min(255, int(tex[to + c] * shade))
                fb[o + 3] = 255
                zbuf[zi] = z
                painted_here = True
        if painted_here:
            stats["painted"] += 1
    return fb, stats


def main():
    view = "hero"
    size = 900
    args = sys.argv[1:]
    for i, a in enumerate(args):
        if a == "--view" and i + 1 < len(args):
            view = args[i + 1]
        if a == "--size" and i + 1 < len(args):
            size = int(args[i + 1])
    if view not in VIEWS:
        raise SystemExit("未知视角 %r，可选: %s" % (view, ", ".join(VIEWS)))

    verts, uvs, norms, tris = read_obj(OBJ)
    tw, th, tex = read_png(TEXTURE)
    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, "mc_%s.png" % view)
    fb, stats = render(verts, uvs, norms, tris, tex, tw, th, view, size)
    write_png(out, size, size, fb)

    magenta = 0
    for i in range(0, len(fb), 4):
        if fb[i] > 200 and fb[i + 1] < 60 and fb[i + 2] > 200:
            magenta += 1
    print("视角: %s   输出: %s" % (view, out))
    print("顶点 %d  UV %d  法线 %d  三角形 %d" % (len(verts), len(uvs), len(norms), len(tris)))
    print("贴图 %d×%d" % (tw, th))
    print("三角形去向: 正面 %d / 背面剔除 %d / 退化 %d / 实际着色 %d"
          % (stats["front"], stats["culled"], stats["degenerate"], stats["painted"]))

    # 一个三角形都没着色时，洋红计数必然是 0，"无洋红空块" 是一句空话。
    if stats["painted"] == 0:
        print("\n!! 没有任何三角形被着色 —— UV 覆盖检查无从谈起，本次验收不通过。")
        print("   三角形 %d 个，背面剔除掉 %d 个。最可能的原因是 OBJ 整体绕序反了"
              % (len(tris), stats["culled"]))
        print("   （本脚本有背面剔除；MC 侧的 objCutoutNoCull 没有，所以游戏里可能看着正常、")
        print("    但法线方向是错的，受光会不对）。")
        sys.exit(1)
    if magenta:
        print("警告: 预览里有 %d 个洋红像素 —— 有 UV 落进贴图未绘制区" % magenta)
    else:
        print("UV 覆盖检查: 无洋红空块（已着色 %d 个三角形）" % stats["painted"])


if __name__ == "__main__":
    main()
