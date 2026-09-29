#!/usr/bin/env python3
"""把生物反应炉的 3 层 block model JSON 烘焙为单个 OBJ 网格。

背景
----
vanilla baked model 的元素坐标被限制在 [-16, 32]（跨度 48px），而反应堆高 48px，
因此资产被切成 3 个 16px 层模型，由 BioreactorRenderer 用 translate(0, i, 0) 堆叠。
本脚本把这 3 层合并成一个 OBJ，从而绕开该限制——几何本身完全不变。

用法
----
    python tools/convert_bioreactor_obj.py

输出
----
    src/main/resources/assets/mekck/models/mesh/bioreactor.obj
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
SRC = os.path.join(PROJECT, "src", "main", "resources", "assets", "mekck")
MODEL_DIR = os.path.join(SRC, "models", "block", "mekck", "bioreactor")
OUT = os.path.join(SRC, "models", "mesh", "bioreactor.obj")

TEXTURE_SIZE = 128.0          # bioreactor.png 是 128x128
LAYERS = ["bioreactor_layer0", "bioreactor_layer1", "bioreactor_layer2"]

# MC 1.20.1 FaceInfo 的逐面顶点顺序与 uv 角分配——**逐字照抄，不做几何推导**。
#
# 为什么不推导：曾用「把顶点投影到观察者 2D 平面，a 最小为左、b 最小为上」来分配
# uv 矩形四角，推论前提是「(u0,v0) 是面的左上角」。但 MC 的约定**并非视角一致**：
# (u0,v0) 只在 up 面是左上角。照那个前提做，338 个面里有 285 个的 uv 与原 JSON 不符
# （整块面板镜像），而这类错误在"几何未变所以截图应一致"的验收下极难肉眼归因。
# 直接抄表最稳，也让这条约定本身可以被单元测试逐角复核。
#
# 每项是 (x, y, z) 各自取 from(min) 还是 to(max)，顺序即 MC 的 v0..v3。
FACE_INFO = {
    "down":  ([("min", "min", "max"), ("min", "min", "min"),
              ("max", "min", "min"), ("max", "min", "max")], (0, -1, 0)),
    "up":    ([("min", "max", "min"), ("min", "max", "max"),
              ("max", "max", "max"), ("max", "max", "min")], (0, 1, 0)),
    "north": ([("max", "max", "min"), ("max", "min", "min"),
              ("min", "min", "min"), ("min", "max", "min")], (0, 0, -1)),
    "south": ([("min", "max", "max"), ("min", "min", "max"),
              ("max", "min", "max"), ("max", "max", "max")], (0, 0, 1)),
    "west":  ([("min", "max", "min"), ("min", "min", "min"),
              ("min", "min", "max"), ("min", "max", "max")], (-1, 0, 0)),
    "east":  ([("max", "max", "max"), ("max", "min", "max"),
              ("max", "min", "min"), ("max", "max", "min")], (1, 0, 0)),
}

# MC 的 BlockFaceUV 把 v0..v3 依次分到 uv 矩形的这四个角：(u0,v0) (u0,v1) (u1,v1) (u1,v0)
UV_BY_INDEX = ((0, 0), (0, 1), (1, 1), (1, 0))


def face_vertices(face_name, lo, hi):
    """按 MC 的面顺序取 4 个顶点，并返回该面法线。"""
    selectors, normal = FACE_INFO[face_name]
    out = []
    for sx, sy, sz in selectors:
        out.append((hi[0] if sx == "max" else lo[0],
                    hi[1] if sy == "max" else lo[1],
                    hi[2] if sz == "max" else lo[2]))
    return out, normal


def face_uvs(uv_rect):
    """把 uv 矩形的四个角按 MC 的顺序分给 v0..v3。"""
    u0, v0, u1, v1 = uv_rect
    return [(u0 if i == 0 else u1, v0 if j == 0 else v1) for i, j in UV_BY_INDEX]


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def main():
    positions = []     # (x, y, z) in block units
    texcoords = []     # (u, v) standard OBJ convention, v up
    normals = []
    faces = []         # (group, [(pos_idx, uv_idx, normal_idx), ...])

    total_boxes = 0
    total_face_records = 0

    for name in LAYERS:
        path = os.path.join(MODEL_DIR, name + ".json")
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)

        for element in data["elements"]:
            total_boxes += 1
            lo = tuple(float(c) for c in element["from"])
            hi = tuple(float(c) for c in element["to"])
            # 保持每层原有的 y[0,16] 局部坐标，不在这里做层间平移：
            # 层间的堆叠（translate(0, i, 0)）仍由渲染器按分组施加，
            # 这样每层才能各自取 pos.above(i) 的光照，与原实现完全一致。

            for face_name, face in element.get("faces", {}).items():
                if face_name not in FACE_INFO:
                    raise SystemExit("未处理的面朝向: " + face_name)
                total_face_records += 1

                vertices_px, normal = face_vertices(face_name, lo, hi)
                uv_px = face_uvs([float(c) for c in face["uv"]])

                pos_idx, uv_idx, nrm_idx = [], [], []
                for corner_px, (u_px, v_px) in zip(vertices_px, uv_px):
                    pos_idx.append(len(positions))
                    positions.append((corner_px[0] / 16.0, corner_px[1] / 16.0, corner_px[2] / 16.0))
                    uv_idx.append(len(texcoords))
                    # OBJ 写标准约定（v 向上）；ObjMesh.parse 会再翻成 MC 约定，
                    # 两次翻转后等于 v_px/128，与原 JSON 渲染一致。
                    texcoords.append((u_px / TEXTURE_SIZE, 1.0 - v_px / TEXTURE_SIZE))
                nrm_idx = len(normals)
                normals.append(tuple(float(c) for c in normal))

                # 扇形三角化：0-1-2 与 0-2-3
                faces.append((name, [
                    (pos_idx[0], uv_idx[0], nrm_idx),
                    (pos_idx[1], uv_idx[1], nrm_idx),
                    (pos_idx[2], uv_idx[2], nrm_idx),
                ]))
                faces.append((name, [
                    (pos_idx[0], uv_idx[0], nrm_idx),
                    (pos_idx[2], uv_idx[2], nrm_idx),
                    (pos_idx[3], uv_idx[3], nrm_idx),
                ]))

    # ---- 自检：三角形绕序必须与声明法线同向，否则会被背面剔除整个吞掉 ----
    # 退化（from == to 造成的零面积）与真正反向要分开报：前者是源资产的问题，
    # 后者是本表的顺序写错了，混在一起会让人往错的方向查。
    reversed_faces = 0
    degenerate_faces = 0
    for _, tri in faces:
        a = positions[tri[0][0]]
        b = positions[tri[1][0]]
        c = positions[tri[2][0]]
        n = cross(sub(b, a), sub(c, a))
        declared = normals[tri[0][2]]
        dot = sum(x * y for x, y in zip(n, declared))
        if dot == 0:
            degenerate_faces += 1
        elif dot < 0:
            reversed_faces += 1
    if degenerate_faces:
        print("警告: %d/%d 个三角形为零面积（源资产中存在 from == to 的 box）"
              % (degenerate_faces, len(faces)))
    if reversed_faces:
        raise SystemExit("绕序自检失败：%d/%d 个三角形与声明法线反向，FACE_INFO 顺序有误"
                         % (reversed_faces, len(faces)))

    # 期望值按输入的面记录数推导，而不是「box 数 × 6」：
    # 资产里确有 box 省略了不可见面（实测 338 条面记录 / 58 个 box）。
    expected = total_face_records * 2
    if len(faces) != expected:
        raise SystemExit("三角形数不符：得到 %d，期望 %d" % (len(faces), expected))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("# 由 tools/convert_bioreactor_obj.py 从 bioreactor_layer0/1/2 生成\n")
        fh.write("# 几何与原 3 层模型完全一致；UV 已从像素换算为 0..1\n")
        fh.write("# 注意：直接编辑本文件会在下次生成时丢失，请改脚本。\n\n")
        for x, y, z in positions:
            fh.write("v %.6f %.6f %.6f\n" % (x, y, z))
        fh.write("\n")
        for u, v in texcoords:
            fh.write("vt %.6f %.6f\n" % (u, v))
        fh.write("\n")
        for nx, ny, nz in normals:
            fh.write("vn %.1f %.1f %.1f\n" % (nx, ny, nz))
        fh.write("\n")
        current = None
        for group, tri in faces:
            if group != current:
                fh.write("g %s\n" % group)
                current = group
            # OBJ 索引是 1-based：内部用 0-based 累加，输出时必须 +1，
            # 否则索引 0 会被解析器当成越界（resolve 返回 -1）而整面丢弃。
            idx = " ".join("%d/%d/%d" % (p + 1, t + 1, n + 1) for p, t, n in tri)
            fh.write("f %s\n" % idx)

    print("box 元素: %d" % total_boxes)
    print("面记录: %d（平均每 box %.2f 个面）" % (total_face_records, total_face_records / total_boxes))
    print("顶点: %d  UV: %d  法线: %d" % (len(positions), len(texcoords), len(normals)))
    print("三角形: %d (期望 %d)" % (len(faces), expected))
    print("绕序自检: 全部通过")
    print("输出: %s" % OUT)


if __name__ == "__main__":
    main()
