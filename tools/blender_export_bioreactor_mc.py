"""把 Blender 里的生物反应堆模型导出成 MC 用的 OBJ。

处理对象
--------
场景 ``COL_Bioreactor_Final`` 里的整机网格（排除 ``GEO_Studio_Floor`` 影棚地板）。
本脚本**不重建几何**，只做导出前的四件事：

1. **剔除影棚件** —— ``GEO_Studio_Floor`` 是 9×9 的拍摄地板，不属于机器。
2. **曲线转网格** —— 4 根导管是 CURVE，OBJ 导不了。
3. **降面** —— 原模型 12104 面。MC 的方块模型没有 LOD，渲染器每帧逐顶点
   重建全部顶点数据，一台机器约 2.4 万三角形时放十几台就明显掉帧。降到
   ~2 千面，肉眼几乎看不出差别。
4. **UV 盒式投影到材质图集** —— 按每个物体的主材质分格，整面拉伸铺满该格。

朝向
----
Blender 是 Z 轴向上、MC 是 Y 轴向上，且 **MC 的 -Z（正北）才是正面**
（控制台所在侧）。单靠 Rx(-90°) 会把正面甩到 +Z，所以这里用
``Ry(180) ∘ Rx(-90)``：Blender +Z → MC +Y、Blender -Y → MC -Z。
两者都是旋转，行列式为 +1，**不会翻转三角形绕序**。

输出
----
``assets/mekck/models/mesh/bioreactor.obj``，按高度分 3 个 ``g`` 分组
（每组 1 block 高，对应运行时 ``pos.above(i)`` 取光照）。
另外写出 ``tools/bioreactor_atlas.json`` 记录材质→格位，供贴图脚本共用。

运行
----
在 Blender 的 Scripting 工作区执行，或从 MCP 调 ``execute_blender_code``：

    exec(open("D:/mc/mod/mekck/tools/blender_export_bioreactor_mc.py").read())
"""

import json
import math
import os

import bpy
from mathutils import Matrix, Vector

PROJECT = "D:/mc/mod/mekck"
ATLAS_JSON = os.path.join(PROJECT, "tools", "bioreactor_atlas.json")
OBJ_OUT = os.path.join(PROJECT, "src", "main", "resources", "assets",
                       "mekck", "models", "mesh", "bioreactor.obj")

CELL, TSIZE, INSET = 16, 128, 0.5
BAND_COUNT = 3
MACHINE_HEIGHT = 3.0        # 3×3×3

# 材质 → 图集格位。8 种材质铺在 2 行 4 列上，UV 覆盖 u 全宽、v 上 3/8，
# 足够触发 tools/ 里的贴图覆盖闸门（原资产只用了 1.6% 的贴图）。
ATLAS = {
    "MAT_Alterra_White":     (0, 0),
    "MAT_Dark_Metal":        (2, 0),
    "MAT_Alterra_Orange":    (4, 0),
    "MAT_Bio_Glass":         (6, 0),
    "MAT_Bio_Fluid_Core":    (0, 2),
    "MAT_Status_Led":        (2, 2),
    "MAT_Screen_UI":         (4, 2),
    "MAT_Hatch_Door":        (6, 2),
}

# 降面目标（面数）。原值 → 目标：
#   CapsuleBody 6144、Bio_Fluid_Core 3072、DomeOrangeTorus 1536
# 三者占全机 89%，是唯一需要动刀的地方；其余零件面数本来就不高，
# 逐个按 DECIMATE_ALL 里给的比例压到 ~2 千面。
DECIMATE = {
    "GEO_Reactor_CapsuleBody":      512,
    "GEO_Bio_Fluid_Core":           256,
    "GEO_Reactor_DomeOrangeTorus":  256,
}
# 这几个是 24 段的圆盘，压到 32 面就够读出圆形了
DISC_TO_32 = {
    "GEO_Ceiling_Flange", "GEO_Ceiling_InnerDarkRim", "GEO_Pedestal_Base",
    "GEO_Pedestal_CradleCollar", "GEO_Pedestal_OrangeTrim",
    "GEO_Base_FloorPlate", "GEO_Base_FloorRing",
}
PIPE_TARGET = 160

# 整机面数预算。倒角应用后零件会明显膨胀，靠这个上限统一收口：
# MC 渲染器每帧逐顶点重建顶点流，7 千面（1.4 万三角形）放十几台就明显掉帧，
# 2 千面（约 4 千三角形）肉眼看不出差别。
FACE_BUDGET = 2000

EXCLUDE_PREFIX = ("GEO_Studio",)
MACHINE_PREFIX = "GEO_"


# ────────────────────────────── 准备 ──────────────────────────────
def machine_meshes():
    out = []
    for ob in bpy.data.objects:
        if ob.type != 'MESH' or not ob.name.startswith(MACHINE_PREFIX):
            continue
        if ob.name.startswith(EXCLUDE_PREFIX):
            continue
        out.append(ob)
    return out


def convert_curves():
    """4 根导管是 CURVE，OBJ 导不了，先转网格。"""
    curves = [o for o in bpy.data.objects
              if o.type == 'CURVE' and o.name.startswith(MACHINE_PREFIX)]
    if not curves:
        return
    bpy.ops.object.select_all(action='DESELECT')
    for c in curves:
        c.select_set(True)
    bpy.context.view_layer.objects.active = curves[0]
    bpy.ops.object.convert(target='MESH')
    bpy.context.view_layer.update()
    for c in curves:
        if c.type == 'MESH':
            print("  曲线转网格: %-26s %d 面" % (c.name, len(c.data.polygons)))


def apply_decimate(ob, target, label=""):
    """塌边降到目标面数。目标不低于原数的 8%，避免把圆柱压成破面。"""
    me = ob.data
    have = len(me.polygons)
    if have <= target:
        return 0
    floor_n = max(8, have // 8)
    goal = max(target, floor_n)
    ratio = goal / have
    m = ob.modifiers.new("mc_decimate", 'DECIMATE')
    m.ratio = ratio
    m.use_collapse_triangulate = True
    bpy.context.view_layer.objects.active = ob
    bpy.ops.object.modifier_apply(modifier=m.name)
    return have - len(me.polygons)


def apply_all_modifiers():
    """把 Bevel / Solidify 等修改器**烘进网格**。

    必须在降面和烘进分带之前做：分带网格是由源物体的 ``mesh.polygons``
    重建出来的，源物体上的修改器不会跟着过去；而导出时的
    ``apply_modifiers=True`` 只对最终选中对象生效，对已经重建过的分带
    网格无效。顺序错了，倒角和玻璃厚度会整段丢失。
    """
    bpy.ops.object.select_all(action='DESELECT')
    objs = machine_meshes()
    for ob in objs:
        ob.select_set(True)
    if not objs:
        return
    bpy.context.view_layer.objects.active = objs[0]
    before = sum(len(o.data.polygons) for o in objs)
    for ob in objs:
        if ob.modifiers:
            bpy.ops.object.convert(target='MESH')     # 一次性烘掉全部修改器
            break
    bpy.context.view_layer.update()
    after = sum(len(o.data.polygons) for o in machine_meshes())
    print("  应用修改器: %d -> %d 面" % (before, after))


def enforce_budget(budget):
    """把整机压到面数预算内。

    前面的按名降面只管三个大件；倒角应用后其余零件也会膨胀，所以这里按
    **全局比例**统一缩放一次，再做少量迭代收口。早期版本写成「每次砍当前
    最大的那个 15%」，面数分布一平就 60 轮都砍不到预算。
    """
    objs = machine_meshes()
    total = sum(len(o.data.polygons) for o in objs)
    if total <= budget:
        print("  预算: %d 面，未超上限 %d" % (total, budget))
        return total

    scale = budget / total
    print("  预算: %d 面 → %d，按比例 %.3f 缩放" % (total, budget, scale))
    for ob in objs:
        have = len(ob.data.polygons)
        # 8 面以下的面片再压就没有意义了（多半是单片玻璃/贴花）
        if have > 8:
            apply_decimate(ob, max(8, int(have * scale)))

    for _ in range(8):                      # 收口：比例裁剪会受各件下限影响，略有偏差
        objs = machine_meshes()
        total = sum(len(o.data.polygons) for o in objs)
        if total <= budget:
            break
        ob = max(objs, key=lambda o: len(o.data.polygons))
        if len(ob.data.polygons) <= 8:
            break
        apply_decimate(ob, max(8, int(len(ob.data.polygons) * (budget / total))))

    total = sum(len(o.data.polygons) for o in machine_meshes())
    print("  预算收敛: %d 面" % total)
    return total


def decimate_all():
    saved = 0
    for ob in machine_meshes():
        n = len(ob.data.polygons)
        if ob.name in DECIMATE:
            saved += apply_decimate(ob, DECIMATE[ob.name])
        elif ob.name in DISC_TO_32:
            saved += apply_decimate(ob, 32)
        elif ob.name.startswith("GEO_Conduit_Pipe") and n > PIPE_TARGET:
            saved += apply_decimate(ob, PIPE_TARGET)
    if saved:
        print("  降面共减少 %d 面" % saved)
    return saved


# ────────────────────────────── UV ──────────────────────────────
def project_uv():
    """按物体主材质分格做盒式投影：取面法线主轴，整面拉伸铺满该格。

    Blender UV 的 v 原点在下方，贴图行号 0 在上方，所以写入时翻一次；
    运行时 ObjMesh.parse 还会再翻回 MC 的左上原点，两次翻转正好抵消。
    """
    bpy.context.view_layer.update()
    for ob in machine_meshes():
        me = ob.data
        while me.uv_layers:
            me.uv_layers.remove(me.uv_layers[0])
        uvl = me.uv_layers.new(name="UVMap")
        names = [m.name for m in me.materials] if me.materials else []
        # 多材质物体用面归属的那个；单材质物体直接用槽 0
        for poly in me.polygons:
            mid = names[poly.material_index] if names else ""
            col, row = ATLAS.get(mid, ATLAS["MAT_Alterra_White"])
            ox, oy = col * CELL, row * CELL
            n = poly.normal
            ax = max(range(3), key=lambda i: abs(n[i]))
            cs = [me.vertices[i].co for i in poly.vertices]
            if ax == 0:
                uvs = [(v.y, v.z) for v in cs]
            elif ax == 1:
                uvs = [(v.x, v.z) for v in cs]
            else:
                uvs = [(v.x, v.y) for v in cs]
            us = [p[0] for p in uvs]
            vs = [p[1] for p in uvs]
            du, dv = max(us) - min(us), max(vs) - min(vs)
            for li, pv in zip(poly.loop_indices, uvs):
                fu = 0.5 if du < 1e-9 else (pv[0] - min(us)) / du
                fv = 0.5 if dv < 1e-9 else (pv[1] - min(vs)) / dv
                u_px = ox + INSET + (CELL - 2 * INSET) * fu
                v_px = oy + INSET + (CELL - 2 * INSET) * fv
                uvl.data[li].uv = (u_px / TSIZE, 1.0 - v_px / TSIZE)


# ─────────────────── 旋正 / 分带 / 合并 ───────────────────
def to_mc_rotation():
    """Blender(x,y,z) Z-up → MC(x,y,z) Y-up，且 Blender -Y（正面）→ MC -Z。

    先 Rx(-90°)：+Z→+Y、-Y→+Z。再 Ry(180°)：把 +Z 甩到 -Z。
    两次都是旋转，det=+1，三角形绕序不受影响。
    """
    return (Matrix.Rotation(math.radians(180), 4, 'Y')
            @ Matrix.Rotation(math.radians(-90), 4, 'X'))


def merge_into_bands():
    """把源物体按高度分 3 组，每组用 Blender 操作符合并成一个网格。

    **不要手工重建网格**（from_pydata + 偏移索引）：降面后的面里会混进
    退化三角形，from_pydata 重建会让顶点索引和导出器写出的 `f` 行对不上，
    表现为「面引用了不存在的顶点」。这里改成给每组设好矩阵再 join，
    索引由 Blender 自己维护。
    """
    objs = machine_meshes()
    rot = to_mc_rotation()

    bpy.context.view_layer.update()
    lo_x = min(min((ob.matrix_world @ v.co)[i] for v in ob.data.vertices) for ob in objs for i in [0])
    hi_x = max(max((ob.matrix_world @ v.co)[i] for v in ob.data.vertices) for ob in objs for i in [0])
    lo_z = min(min((ob.matrix_world @ v.co)[i] for v in ob.data.vertices) for ob in objs for i in [2])
    hi_z = max(max((ob.matrix_world @ v.co)[i] for v in ob.data.vertices) for ob in objs for i in [2])
    lo_y = min(min((ob.matrix_world @ v.co)[i] for v in ob.data.vertices) for ob in objs for i in [1])
    span_x, span_z = hi_x - lo_x, hi_z - lo_z
    dx = -(lo_x + hi_x) / 2.0
    dz = -(lo_z + hi_z) / 2.0
    dy = -lo_y
    print("  水平跨度 x=%.3f z=%.3f（3 格足迹上限 3.0）%s"
          % (span_x, span_z, "" if max(span_x, span_z) <= 3.0 else "  ⚠ 超出足迹！"))
    print("  平移 dx=%.3f dy=%.3f dz=%.3f" % (dx, dy, dz))

    M = Matrix.Translation((dx, dy, dz)) @ rot

    # 变换后按重心高度分带
    bands = {i: [] for i in range(BAND_COUNT)}
    for ob in objs:
        center = sum((ob.matrix_world @ v.co for v in ob.data.vertices),
                     Vector()) / max(1, len(ob.data.vertices))
        h = (M @ center).y
        bands[min(BAND_COUNT - 1, max(0, int(h)))].append(ob)

    merged = []
    for i in range(BAND_COUNT):
        group = bands[i]
        if not group:
            continue
        bpy.ops.object.select_all(action='DESELECT')
        for ob in group:
            ob.select_set(True)
        bpy.context.view_layer.objects.active = group[0]
        for ob in group:
            ob.matrix_world = M @ ob.matrix_world
        bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
        bpy.ops.object.join()
        ob = bpy.context.view_layer.objects.active
        ob.name = "bioreactor_layer%d" % i
        ob.data.name = ob.name
        merged.append(ob)
        print("  分组%d: %d 个零件合并 -> %d 面" % (i, len(group), len(ob.data.polygons)))
    return merged


def write_band_mesh(b, data, index):
    raise NotImplementedError("改用 merge_into_bands 里的操作符路径")


def export_obj(objs):
    os.makedirs(os.path.dirname(OBJ_OUT), exist_ok=True)
    bpy.ops.object.select_all(action='DESELECT')
    for ob in objs:
        ob.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.wm.obj_export(filepath=OBJ_OUT, export_selected_objects=True,
                          export_object_groups=True, export_materials=False,
                          export_uv=True, export_normals=True,
                          export_triangulated_mesh=False,
                          forward_axis='Y', up_axis='Z', apply_modifiers=True)
    return OBJ_OUT


def main():
    print("== 生物反应堆 MC 导出 ==")
    src = machine_meshes()
    print("源机器网格: %d 个" % len(src))
    convert_curves()
    apply_all_modifiers()
    decimate_all()
    enforce_budget(FACE_BUDGET)
    project_uv()
    # 导出前清掉上一轮的分带对象
    for ob in [o for o in bpy.data.objects if o.name.startswith("bioreactor_layer")]:
        bpy.data.objects.remove(ob, do_unlink=True)

    objs = merge_into_bands()

    with open(ATLAS_JSON, "w", encoding="utf-8") as fh:
        json.dump({"cell": CELL, "size": TSIZE, "inset": INSET, "materials": ATLAS},
                  fh, ensure_ascii=False, indent=2)

    total = sum(len(o.data.polygons) for o in objs)
    lo = Vector((1e9,) * 3)
    hi = Vector((-1e9,) * 3)
    for ob in objs:
        for v in ob.bound_box:
            w = Vector(v)
            for i in range(3):
                lo[i] = min(lo[i], w[i])
                hi[i] = max(hi[i], w[i])
    path = export_obj(objs)
    print("总面数: %d（约 %d 三角形）" % (total, total * 2))
    print("MC 包围盒 min=(%.3f,%.3f,%.3f) max=(%.3f,%.3f,%.3f)"
          % (lo.x, lo.y, lo.z, hi.x, hi.y, hi.z))
    print("导出: %s (%d bytes)" % (path, os.path.getsize(path)))
    print("接着跑: python tools/finalize_bioreactor_obj.py")


main()
