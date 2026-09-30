"""生物反应堆建模 —— Blender 内的完整可复现构建脚本。

参考图
------
Deliver Us The Moon 风格的月球基地生物反应堆。实机特写给出的关键信息：

* 白色球体机身，**赤道有一圈细橙环**；
* **绿色辉光视窗嵌在方形凹框里，且不止一块**——特写里左右两侧各有一块，
  说明是四面对称分布，不是只朝正面一块；
* 顶部遮阳板由 4 根黑色曲臂撑起，底面泛绿光；
* 分层圆鼓底座，各层带橙色装饰环；
* 侧置控制台（正面视角看在观者左手侧）。

坐标与朝向
----------
脚本全程按 **Minecraft 朝向**建模：Y 轴向上，X/Z 为水平，**主方块在底部正中**，
整机铺满 3×3×3（x/z ∈ [-1.5, 1.5]，y ∈ [0, 3]）。Blender 是 Z 轴向上，所以建模
阶段用「Blender z = MC y、Blender x = MC x、Blender y = -MC z」，最后统一
旋正并导出，视窗因此落在 MC 的 -Z（正北），即方块 FACING=NORCH 时的正面。

运行
----
在 Blender 的 Scripting 工作区执行，或从 MCP 调 ``execute_blender_code``：

    exec(open("D:/mc/mod/mekck/tools/blender_bioreactor_build.py").read())

导出后仍需跑 ``tools/finalize_bioreactor_obj.py`` 规整分组名。
"""

import bpy
import json
import math
import os
from mathutils import Matrix, Vector

PROJECT = "D:/mc/mod/mekck"
ATLAS_JSON = os.path.join(PROJECT, "tools", "bioreactor_atlas.json")
OBJ_OUT = os.path.join(PROJECT, "src", "main", "resources", "assets",
                       "mekck", "models", "mesh", "bioreactor.obj")

# 材质 → 8×8 图集格位。UV 投影与贴图脚本共用这张表。
ATLAS = {
    "BODY_WHITE": (0, 0), "BODY_PANEL": (2, 0), "DARK": (4, 0),     "DARK_RIB": (6, 0),
    "DARK_METAL": (1, 2), "ORANGE": (3, 2),    "GREEN_GLOW": (5, 2), "SCREEN": (7, 2),
    "VENT": (0, 4),      "LAMP": (2, 4),      "CYAN_DASH": (4, 4),  "BODY_WHITE_ALT": (6, 4),
    "CANOPY_GLOW": (6, 2),
}
CELL, TSIZE, INSET = 16, 128, 0.5

PALETTE = {
    "BODY_WHITE":     ((0.894, 0.894, 0.878, 1), 0.0, 0.25, 0.55),
    "BODY_WHITE_ALT": ((0.867, 0.867, 0.847, 1), 0.0, 0.25, 0.55),
    "BODY_PANEL":     ((0.784, 0.784, 0.753, 1), 0.0, 0.20, 0.60),
    "DARK":           ((0.188, 0.204, 0.235, 1), 0.0, 0.55, 0.62),
    "DARK_RIB":       ((0.149, 0.165, 0.196, 1), 0.0, 0.55, 0.65),
    "DARK_METAL":     ((0.369, 0.392, 0.431, 1), 0.0, 0.55, 0.42),
    "ORANGE":         ((0.910, 0.510, 0.118, 1), 0.0, 0.10, 0.45),
    "GREEN_GLOW":     ((0.298, 0.878, 0.478, 1), 2.2, 0.00, 0.30),
    "SCREEN":         ((0.047, 0.361, 0.235, 1), 1.4, 0.00, 0.25),
    "VENT":           ((0.157, 0.173, 0.196, 1), 0.0, 0.30, 0.70),
    "LAMP":           ((0.941, 0.753, 0.125, 1), 1.8, 0.00, 0.35),
    "CYAN_DASH":      ((0.345, 0.878, 0.933, 1), 1.8, 0.00, 0.35),
    "CANOPY_GLOW":    ((0.129, 0.400, 0.239, 1), 0.9, 0.00, 0.40),
}

# ── 整机关键尺寸（参考图量取，见 docs 里的 Reference Analysis）──
SPH_CZ, SPH_RX, SPH_RZ = 1.9375, 0.894, 0.6875     # 球心高 / 水平半径 / 垂直半径
VISOR_Z = 1.760                                     # 视窗中心（球心略下方）
CANOPY_R = 1.500
# 参考图里这些是「线」不是「带」：遮阳板实测约占总高 3.8%，赤道橙环约 0.6%，
# 中盘橙环约 1.2%。总高 3 block，故下面几个厚度都换算成 block 单位。
CANOPY_Z1 = 2.969
CANOPY_Z0 = CANOPY_Z1 - 0.113                      # 0.11 ≈ 总高的 3.8%
CANOPY_GLOW_R = 1.150
EQUATOR_HALF = 0.010                                # 赤道橙环半厚 → 全厚 0.02
FLANGE_RING_H = 0.040


# ────────────────────────────── 场景准备 ──────────────────────────────
def clear_scene():
    for ob in list(bpy.data.objects):
        bpy.data.objects.remove(ob, do_unlink=True)
    for coll in list(bpy.data.collections):
        bpy.data.collections.remove(coll)
    for blocks in (bpy.data.meshes, bpy.data.materials, bpy.data.cameras,
                   bpy.data.lights, bpy.data.images):
        for d in list(blocks):
            if getattr(d, "users", 0) == 0 or True:
                try:
                    blocks.remove(d)
                except Exception:
                    pass


def make_materials():
    for name, (col, emit, metal, rough) in PALETTE.items():
        m = bpy.data.materials.new(name)
        m.use_nodes = True
        b = m.node_tree.nodes["Principled BSDF"]
        b.inputs["Base Color"].default_value = col
        b.inputs["Roughness"].default_value = rough
        b.inputs["Metallic"].default_value = metal
        if emit > 0:
            b.inputs["Emission Color"].default_value = col
            b.inputs["Emission Strength"].default_value = emit
        m.diffuse_color = col


COLLS = {}


def make_collections():
    root = bpy.data.collections.new("COL_bioreactor")
    bpy.context.scene.collection.children.link(root)
    COLLS["root"] = root
    for name in ("COL_base", "COL_sphere", "COL_canopy", "COL_console"):
        c = bpy.data.collections.new(name)
        root.children.link(c)
        COLLS[name] = c


def put(ob, coll, mat):
    for c in list(ob.users_collection):
        c.objects.unlink(ob)
    COLLS[coll].objects.link(ob)
    ob.data.materials.clear()
    ob.data.materials.append(bpy.data.materials[mat])
    return ob


def cyl(name, r, z0, z1, mat, coll, x=0.0, y=0.0, verts=16):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=r, depth=z1 - z0,
                                        location=(x, y, (z0 + z1) / 2.0))
    ob = bpy.context.object
    ob.name = name
    return put(ob, coll, mat)


def ellipsoid(name, center, radii, mat, coll, seg=12, ring=6):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=ring, radius=1.0,
                                         location=center)
    ob = bpy.context.object
    ob.name = name
    ob.scale = radii
    return put(ob, coll, mat)


def cuboid(name, x0, x1, y0, y1, z0, z1, mat, coll, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1.0,
                                    location=((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2))
    ob = bpy.context.object
    ob.name = name
    ob.scale = (x1 - x0, y1 - y0, z1 - z0)
    if bevel > 0:
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
        m = ob.modifiers.new("bev", 'BEVEL')
        m.width = bevel
        m.segments = 1
        m.limit_method = 'ANGLE'
        m.angle_limit = math.radians(30)
        bpy.ops.object.modifier_apply(modifier=m.name)
    return put(ob, coll, mat)


# ────────────────────────────── 建模 ──────────────────────────────
def sphere_front_y(x, z):
    """球体在 (x, z) 处朝 +Y 的表面高度（视窗朝 +Y，导出后成为 MC -Z 正面）。"""
    k = 1.0 - (x / SPH_RX) ** 2 - ((z - SPH_CZ) / SPH_RZ) ** 2
    return SPH_RX * math.sqrt(k) if k > 0 else 0.0


def build_base():
    # 地台：八棱柱浅盘，带一圈防滑踏板
    cyl("SM_floor_plate", 1.380, 0.000, 0.260, "DARK", "COL_base", verts=8)
    for i in range(8):
        a = i * math.pi / 4
        t = cuboid("SM_floor_tread_%d" % i, -0.05, 0.05, -0.15, 0.15, 0.260, 0.290,
                   "DARK_METAL", "COL_base")
        t.location = (math.cos(a) * 1.20, math.sin(a) * 1.20, 0.275)
        t.rotation_euler = (0, 0, a)

    # 底鼓 + 上下压条
    cyl("SM_lower_drum", 0.750, 0.375, 0.875, "DARK_RIB", "COL_base")
    cyl("SM_drum_trim_lo", 0.782, 0.375, 0.460, "DARK_METAL", "COL_base")
    cyl("SM_drum_trim_hi", 0.782, 0.790, 0.875, "DARK_METAL", "COL_base")

    # 中盘 + 橙色装饰环（半径略大露出一圈）
    cyl("SM_mid_flange", 0.962, 0.812, 1.188, "DARK_METAL", "COL_base")
    cyl("SM_orange_ring", 0.994, 1.188 - FLANGE_RING_H, 1.188, "ORANGE", "COL_base")
    cyl("SM_flange_trim", 0.980, 1.188 - 0.022, 1.188, "DARK", "COL_base")

    # 颈环 + 顶缘绿色虚线灯带
    cyl("SM_neck", 0.694, 1.125, 1.313, "DARK", "COL_base")
    for i in range(8):
        a = i * math.pi / 4 + math.pi / 8
        d = cuboid("SM_neck_dash_%d" % i, -0.012, 0.012, -0.034, 0.034, 1.285, 1.320,
                   "GREEN_GLOW", "COL_base")
        d.location = (math.cos(a) * 0.700, math.sin(a) * 0.700, 1.302)
        d.rotation_euler = (0, 0, a)


def build_sphere():
    ellipsoid("SM_sphere", (0, 0, SPH_CZ), (SPH_RX, SPH_RX, SPH_RZ),
              "BODY_WHITE", "COL_sphere", seg=16, ring=8)
    # 赤道细橙环 —— 实机特写里环绕球体的那一圈
    cyl("SM_sphere_equator_ring", SPH_RX * 1.008, SPH_CZ - EQUATOR_HALF,
        SPH_CZ + EQUATOR_HALF, "ORANGE", "COL_sphere", verts=16)

    # 四面视窗：实机特写显示左右两侧各有一块绿窗，故按四面对称布置。
    # 做成「方形凹框 + 绿色镜片」两层椭球，避免平面片在球面上悬空。
    # 注意镜片必须**探出球面**：埋进去就完全看不见（球面把它整个包住）。
    for k in range(4):
        a = k * math.pi / 2
        ca, sa = math.cos(a), math.sin(a)
        fy = sphere_front_y(0.0, VISOR_Z)
        bez = ellipsoid("SM_visor_housing_%d" % k,
                        (0, fy - 0.030, VISOR_Z),
                        (0.4625, 0.0720, 0.2500), "DARK", "COL_sphere")
        bez.rotation_euler = (0, 0, a)
        lens = ellipsoid("SM_visor_lens_%d" % k,
                         (0, fy + 0.028, VISOR_Z),
                         (0.3250, 0.0480, 0.1900), "GREEN_GLOW", "COL_sphere")
        lens.rotation_euler = (0, 0, a)
        # 球心正上方两条青色短灯（只做朝 +Y 那一面，其余三面转过去就是四向）
        z = SPH_CZ + 0.311 * SPH_RZ
        for s, sx in enumerate((-0.281, 0.281)):
            y = sphere_front_y(sx, z)
            d = cuboid("SM_cyan_dash_%d_%d" % (k, s), -0.075, 0.075, -0.028, 0.028,
                       -0.022, 0.022, "CYAN_DASH", "COL_sphere")
            d.location = (sx * ca - (y - 0.028) * sa,
                          sx * sa + (y - 0.028) * ca, z)
            d.rotation_euler = (0, 0, a)


def build_canopy():
    # 遮阳板：主盘 + 下缘压边 + 底部绿色辉光盘
    cyl("SM_canopy", CANOPY_R, CANOPY_Z0 + 0.018, CANOPY_Z1, "DARK", "COL_canopy", verts=16)
    cyl("SM_canopy_lip", 1.478, CANOPY_Z0, CANOPY_Z0 + 0.024, "DARK_METAL", "COL_canopy", verts=16)
    cyl("SM_canopy_glow", CANOPY_GLOW_R, CANOPY_Z0 - 0.030, CANOPY_Z0 - 0.004,
        "CANOPY_GLOW", "COL_canopy", verts=16)

    # 4 根曲臂：对角布置，3 段递进外张，末端收在遮阳板 r=0.74R 处（对齐参考图）
    a0, b0 = Vector((0.73125, 0.0, 2.21875)), Vector((1.10625, 0.0, CANOPY_Z0 - 0.012))
    for az in (45, 135, 225, 315):
        a = math.radians(az)
        ca, sa = math.cos(a), math.sin(a)
        pts = []
        for t in (0.0, 0.34, 0.67, 1.0):
            r = a0.x + (b0.x - a0.x) * t
            z = a0.z + (b0.z - a0.z) * t
            bow = 1.0 + 0.07 * math.sin(math.pi * t)
            pts.append(Vector((r * bow * ca, r * bow * sa, z)))
        for i in range(3):
            p, q = pts[i], pts[i + 1]
            d = q - p
            bpy.ops.mesh.primitive_cylinder_add(vertices=8, radius=0.06875,
                                                depth=d.length, location=(p + q) / 2)
            ob = bpy.context.object
            ob.name = "SM_arm_%d_seg%d" % (az, i)
            ob.rotation_mode = 'QUATERNION'
            ob.rotation_quaternion = d.to_track_quat('Z', 'Y')
            put(ob, "COL_canopy", "DARK")


def build_console():
    # 机器正面朝 MC -Z，观察者站在 -Z 往 +Z 看：观察者右手 = -X、左手 = +X。
    # 参考图里控制台在观者左手侧，故放 MC +X。
    cuboid("SM_console_foot", 0.875, 1.500, -0.280, 0.280, 0.400, 0.560,
           "DARK_METAL", "COL_console", bevel=0.030)
    cuboid("SM_console_post", 0.950, 1.325, -0.225, 0.225, 0.560, 1.180,
           "DARK", "COL_console", bevel=0.025)
    body = cuboid("SM_console_body", 0.900, 1.450, -0.260, 0.260, 1.180, 1.950,
                  "DARK", "COL_console", bevel=0.035)
    cuboid("SM_console_screen", 0.950, 1.400, 0.235, 0.270, 1.300, 1.880,
           "SCREEN", "COL_console")
    cuboid("SM_console_lamp", 1.100, 1.360, 0.235, 0.270, 1.220, 1.280,
           "LAMP", "COL_console")
    cuboid("SM_console_vent", 0.930, 1.420, 0.225, 0.245, 1.320, 1.860,
           "VENT", "COL_console")
    # 屏体整体后仰 12°
    piv = Vector((0, 0, 1.18))
    ca, sa = math.cos(math.radians(-12)), math.sin(math.radians(-12))
    for ob in bpy.data.objects:
        if ob.type == 'MESH' and ob.name.startswith("SM_console") and ob.location.z > 1.0:
            d = ob.location - piv
            ob.location = piv + Vector((d.x, d.y * ca - d.z * sa, d.y * sa + d.z * ca))
            ob.rotation_euler = (math.radians(-12), 0, 0)
    bpy.ops.object.select_all(action='DESELECT')
    for ob in bpy.data.objects:
        if ob.type == 'MESH' and ob.name.startswith("SM_console"):
            ob.select_set(True)
    bpy.context.view_layer.objects.active = body
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)


# ────────────────────── UV / 合并 / 旋正 / 导出 ──────────────────────
def project_uv():
    """盒式投影：按面法线主轴选投影面，整面拉伸铺满所属材质格。"""
    for ob in bpy.data.objects:
        if ob.type != 'MESH' or not ob.name.startswith("SM_"):
            continue
        me = ob.data
        while me.uv_layers:
            me.uv_layers.remove(me.uv_layers[0])
        uvl = me.uv_layers.new(name="UVMap")
        mat = me.materials[0].name if me.materials else "DARK"
        col, row = ATLAS.get(mat, ATLAS["DARK"])
        ox, oy = col * CELL, row * CELL
        for poly in me.polygons:
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
                # 图集格位按 PNG 行号给出（行 0 在图顶），而 Blender UV 的 v 原点在
                # 下方，所以这里要翻一次。运行时 ObjMesh.parse 还会再翻 v 回到 MC 的
                # 左上原点——两次翻转后正好等于 PNG 行号。
                u_px = ox + INSET + (CELL - 2 * INSET) * fu
                v_px = oy + INSET + (CELL - 2 * INSET) * fv
                uvl.data[li].uv = (u_px / TSIZE, 1.0 - v_px / TSIZE)


def split_into_bands():
    """按面重心高度分成 3 个光照带（每带 1 block，对应运行时 pos.above(i) 取光）。"""
    bands = {0: [], 1: [], 2: []}
    for ob in [o for o in bpy.data.objects if o.type == 'MESH' and o.name.startswith("SM_")]:
        zc = sum(ob.location.z for _ in [0]) / 1.0
        zc = sum((ob.matrix_world @ Vector(v)).z for v in ob.bound_box) / 8.0
        bands[min(2, max(0, int(zc)))].append(ob)

    out = []
    # 关键：loc/scale/rotation 是直接赋值的，matrix_world 要等依赖图求值后才更新。
    # 不先 update 就读 ob.matrix_world，拿到的是**上一帧**的矩阵——椭球类部件
    # （球体、视窗框、镜片）会按旧变换烘进 OBJ，尺寸和位置全错。
    bpy.context.view_layer.update()
    for b, objs in bands.items():
        if not objs:
            continue
        # 先定一份全局材质顺序，再按**名字**把各源物体的材质索引映射过去。
        # 直接用 materials.find(name) 会拿源物体自己的槽序当全局索引——各物体的
        # 槽顺序不同，合并后材质会串位（表现为遮阳板顶面糊上辉光贴图）。
        names = []
        for ob in objs:
            for m in ob.data.materials:
                if m.name not in names:
                    names.append(m.name)

        verts, polys, fuv, fmat = [], [], [], []
        vmap = {}
        for ob in objs:
            MW = ob.matrix_world
            me = ob.data
            uvl = me.uv_layers.active
            local = [m.name for m in me.materials]
            off = len(verts)
            for v in me.vertices:
                verts.append(tuple(MW @ v.co))
            for poly in me.polygons:
                corner, cuvs = [], []
                for li in poly.loop_indices:
                    corner.append(off + me.loops[li].vertex_index)
                    cuvs.append(tuple(uvl.data[li].uv))
                polys.append(corner)
                fuv.append(cuvs)
                fmat.append(names.index(local[poly.material_index]))

        nm = bpy.data.meshes.new("bioreactor_layer%d" % b)
        nm.from_pydata(verts, [], polys)
        nm.update()
        for mn in names:
            nm.materials.append(bpy.data.materials[mn])
        lay = nm.uv_layers.new(name="UVMap")
        base = 0
        for i, p in enumerate(nm.polygons):
            p.material_index = fmat[i]
            for k in range(len(p.vertices)):
                lay.data[base + k].uv = fuv[i][k]
            base += len(p.vertices)
        newob = bpy.data.objects.new("bioreactor_layer%d" % b, nm)
        bpy.context.scene.collection.objects.link(newob)
        out.append((b, newob, len(objs)))
    bpy.context.view_layer.update()
    # 自检：逐部件打印世界包围盒，便于核对尺寸/位置是否与设计一致
    import os as _os
    if _os.environ.get("MEKCK_DUMP_BBOX"):
        for ob in sorted([o for o in bpy.data.objects if o.type == 'MESH'], key=lambda o: o.name):
            vs = [ob.matrix_world @ v.co for v in ob.data.vertices]
            lo = [min(v[i] for v in vs) for i in range(3)]
            hi = [max(v[i] for v in vs) for i in range(3)]
            print("    %-26s x[%6.3f,%6.3f] y[%6.3f,%6.3f] z[%6.3f,%6.3f]"
                  % (ob.name, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]))
    for ob in [o for o in bpy.data.objects if o.type == 'MESH' and o.name.startswith("SM_")]:
        bpy.data.objects.remove(ob, do_unlink=True)
    return out


def orient_to_mc(objs):
    """Blender(x,y,z) Z-up → MC(x, z, -y)，再平移铺满 3×3×3。

    视窗建在 Blender +Y，映射后落到 MC -Z，也就是方块 FACING=NORTH 的正面。
    """
    rot = Matrix.Rotation(math.radians(-90), 4, 'X')
    bpy.ops.object.select_all(action='DESELECT')
    for ob in objs:
        ob.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    for ob in objs:
        ob.matrix_world = rot @ ob.matrix_world
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)

    lo = Vector((1e9,) * 3)
    hi = Vector((-1e9,) * 3)
    for ob in objs:
        for v in ob.bound_box:
            w = Vector(v)
            for i in range(3):
                lo[i] = min(lo[i], w[i])
                hi[i] = max(hi[i], w[i])
    shift = Vector((-1.5 - lo.x, -0.0 - lo.y, -1.5 - lo.z))
    for ob in objs:
        ob.location += shift
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
    return shift


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


def write_atlas_json():
    with open(ATLAS_JSON, "w", encoding="utf-8") as fh:
        json.dump({"cell": CELL, "size": TSIZE, "inset": INSET, "materials": ATLAS},
                  fh, ensure_ascii=False, indent=2)


def main():
    clear_scene()
    make_collections()
    make_materials()
    build_base()
    build_sphere()
    build_canopy()
    build_console()
    project_uv()
    bands = split_into_bands()
    objs = [o for _, o, _ in bands]
    shift = orient_to_mc(objs)
    path = export_obj(objs)
    write_atlas_json()

    lo = Vector((1e9,) * 3)
    hi = Vector((-1e9,) * 3)
    faces = 0
    for ob in objs:
        faces += len(ob.data.polygons)
        for v in ob.bound_box:
            w = Vector(v)
            for i in range(3):
                lo[i] = min(lo[i], w[i])
                hi[i] = max(hi[i], w[i])
    for b, ob, src in bands:
        bb = [ob.matrix_world @ Vector(v) for v in ob.bound_box]
        print("  分组%d: %3d 面（来自 %2d 个部件）  y[%.3f, %.3f]"
              % (b, len(ob.data.polygons), src,
                 min(p.y for p in bb), max(p.y for p in bb)))
    print("总面数: %d   平移: %s" % (faces, tuple(round(v, 3) for v in shift)))
    print("MC 包围盒 min=(%.3f,%.3f,%.3f) max=(%.3f,%.3f,%.3f)"
          % (lo.x, lo.y, lo.z, hi.x, hi.y, hi.z))
    print("导出: %s (%d bytes)" % (path, os.path.getsize(path)))
    print("接着跑: python tools/finalize_bioreactor_obj.py")


main()
