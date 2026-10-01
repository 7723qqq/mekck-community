#!/usr/bin/env python3
"""Blender 导出的 bioreactor.obj 后处理：规整分组名 + 绕序修正 + 3层局部高度验证。

背景
----
1. bpy.ops.wm.obj_export 写出的 g 名会被 Blender 加 uniquifier 后缀，
   例如 bioreactor_layer0_bioreactor_layer0.001。BioreactorRenderer 按固定名字取分组，
   所以导出后统一规范为 bioreactor_layer{0,1,2}。
2. 绕序修正与自检：每个三角形的有向法线必须与 OBJ 声明的 vn 同向（逆时针正向）。
   MC 的 rendertype_solid 开启了背面剔除，本脚本自动翻转极少数切片缝隙反向面。
3. 3 层规范校验：
   - 每层局部 Y 属于 [0.0, 1.0]（供 BioreactorRenderer 的 translate(0, i, 0) 叠加和逐层取光照）；
   - 水平足迹 X, Z 属于 [-1.5, 1.5]（完全契合 3×3×3 多方块结构）。
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
OBJ = os.path.join(PROJECT, "src", "main", "resources", "assets",
                   "mekck", "models", "mesh", "bioreactor.obj")

GROUP_RE = re.compile(r"bioreactor_layer(\d+)(?!\d)")
TOL = 1e-3
EXPECTED_GROUPS = ["bioreactor_layer0", "bioreactor_layer1", "bioreactor_layer2"]


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def dot3(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def main():
    if not os.path.exists(OBJ):
        raise SystemExit("找不到 " + OBJ + "，请先从 Blender 导出")

    verts, normals = [], []
    layer_verts = {}
    current_group = "default"
    raw_lines = []

    with open(OBJ, "r", encoding="utf-8") as fh:
        for line in fh:
            stripped = line.rstrip("\r\n")
            raw_lines.append(stripped)
            parts = stripped.split()
            if not parts:
                continue
            if parts[0] == "g":
                m = GROUP_RE.findall(stripped)
                # 解析不出层号就必须停：原实现里 current_group 会静默沿用上一行，
                # 顶点就被算到隔壁组里去了，而导出看起来一切正常。
                if not m:
                    raise SystemExit("无法从分组名中解析层号: " + stripped)
                current_group = "bioreactor_layer%s" % m[-1]
            elif parts[0] == "v":
                v = tuple(float(x) for x in parts[1:4])
                verts.append(v)
                layer_verts.setdefault(current_group, []).append(v)
            elif parts[0] == "vn":
                normals.append(tuple(float(x) for x in parts[1:4]))

    # ── 逐行处理输出，自动规范化分组并修正反向面 ─────────────────────────────
    out_lines = []
    fixed_faces = 0
    total_faces = 0
    total_tris = 0
    reversed_tris = 0
    degenerate = 0
    no_normal_faces = 0

    for line in raw_lines:
        parts = line.split()
        if not parts:
            out_lines.append(line)
            continue
        if parts[0] == "g":
            m = GROUP_RE.findall(line)
            if not m:
                raise SystemExit("无法从分组名中解析层号: " + line)
            out_lines.append("g bioreactor_layer%s" % m[-1])
            continue
        elif parts[0] == "f":
            total_faces += 1
            face_toks = parts[1:]
            idx = [tuple(int(t) for t in tok.split("/")) for tok in face_toks]
            # OBJ 允许 f -1 -2 -3 这种相对索引；直接拿去 verts[i-1] 会绕回列表末尾，
            # 静默画错面。Blender 不这么发，但别指望输入永远干净。
            if any(tok[0] < 0 for tok in idx):
                raise SystemExit("OBJ 用了相对（负）顶点索引，本脚本不支持: " + line)

            # 绕序判据只能是 OBJ 声明的 vn。面若没带 vn，就无法判定朝向——
            # 拿常数 (0,1,0) 当参考会把所有竖直面算成"零面积"甚至误翻。
            has_n = len(idx[0]) > 2 and idx[0][2] > 0
            if not has_n:
                no_normal_faces += 1

            a = verts[idx[0][0] - 1]
            b = verts[idx[1][0] - 1]
            c = verts[idx[2][0] - 1]
            n = cross(sub(b, a), sub(c, a))

            if has_n:
                declared = normals[idx[0][2] - 1]
                if dot3(n, declared) < -1e-6:
                    # 反向，自动翻转顶点顺序
                    face_toks = face_toks[::-1]
                    idx = idx[::-1]
                    fixed_faces += 1

                for k in range(1, len(idx) - 1):
                    total_tris += 1
                    tri_a = verts[idx[0][0] - 1]
                    tri_b = verts[idx[k][0] - 1]
                    tri_c = verts[idx[k + 1][0] - 1]
                    tri_n = cross(sub(tri_b, tri_a), sub(tri_c, tri_a))
                    tri_dot = dot3(tri_n, declared)
                    if abs(tri_dot) < 1e-9:
                        degenerate += 1
                    elif tri_dot < 0:
                        reversed_tris += 1
            else:
                total_tris += len(idx) - 2

            out_lines.append("f " + " ".join(face_toks))
        else:
            out_lines.append(line)

    # ── 逐层局部高度校验 [0.0, 1.0] ────────────────────────────────────
    bad_layers = []
    missing_layers = []
    for layer in EXPECTED_GROUPS:
        if layer not in layer_verts:
            missing_layers.append(layer)
            continue
        l_vs = layer_verts[layer]
        min_y = min(v[1] for v in l_vs)
        max_y = max(v[1] for v in l_vs)
        print("层 %s: 顶点数=%d, Y 范围=[%.6f, %.6f]" % (layer, len(l_vs), min_y, max_y))
        if min_y < -TOL or max_y > 1.0 + TOL:
            print("  !! %s Y 范围超出 [0, 1] 约束！" % layer)
            bad_layers.append(layer)

    # ── 全局水平足迹校验 ───────────────────────────────────────────────
    min_x = min(v[0] for v in verts)
    max_x = max(v[0] for v in verts)
    min_z = min(v[2] for v in verts)
    max_z = max(v[2] for v in verts)
    print("全局水平足迹: X=[%.3f, %.3f], Z=[%.3f, %.3f]（3x3 上限 [-1.5, 1.5]）"
          % (min_x, max_x, min_z, max_z))
    if min_x < -1.5 - TOL or max_x > 1.5 + TOL or min_z < -1.5 - TOL or max_z > 1.5 + TOL:
        print("  !! 水平足迹越出 [-1.5, 1.5]，会超出 3x3x3 多方块体积")
        bad_footprint = True
    else:
        bad_footprint = False

    groups = sorted({ln[2:] for ln in out_lines if ln.startswith("g ")})
    print("规整后分组: %s" % groups)
    print("总计: 顶点=%d, 法线=%d, 面=%d, 三角形=%d" % (len(verts), len(normals), total_faces, total_tris))
    print("绕序自检: 修正了 %d 个反向面，残留 %d 反向 / %d 零面积三角形"
          % (fixed_faces, reversed_tris, degenerate))
    if no_normal_faces:
        print("         另有 %d 个面未声明 vn，无法判定绕序（未参与修正）" % no_normal_faces)

    problems = []
    if reversed_tris:
        problems.append("%d 个反向三角形" % reversed_tris)
    if bad_layers:
        problems.append("层 Y 越界: %s" % ", ".join(bad_layers))
    if missing_layers:
        problems.append("缺失层: %s" % ", ".join(missing_layers))
    if bad_footprint:
        problems.append("水平足迹越出 [-1.5, 1.5]")

    # 先校验后落盘：不合格就不动现有 OBJ，避免拿半成品覆盖掉能用的网格
    if problems:
        print("\n未通过，未写入 %s：%s" % (OBJ, "；".join(problems)))
        sys.exit(1)

    with open(OBJ, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(out_lines) + "\n")
    print("已规整写入: %s" % OBJ)


if __name__ == "__main__":
    main()
