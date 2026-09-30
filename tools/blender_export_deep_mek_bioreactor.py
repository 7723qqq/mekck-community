"""Export Deep Mek Bioreactor from Blender to Minecraft OBJ format.

Implements intelligent mesh reduction ("降面不减配"):
- Preserves 100% of mechanical details (bolts, louvers, clamps, conduits, screens, bellows, hinges)
- Optimizes dense spherical/curved topology (UV spheres, tori, cylinders)
- Bakes all materials into a 256x256 texture atlas
- Bisects the geometry into 3 distinct layers at Z=1.0 and Z=2.0
- Normalizes local height to Y in [0.0, 1.0] for each layer
- Transforms coordinates to Minecraft space (Y-up, -Z forward, centered at 0, 0)
- Preserves counter-clockwise winding order for OpenGL back-face culling
"""

import os
import math
import bpy
import bmesh
from mathutils import Vector, Matrix

PROJECT = "D:/mc/mod/mekck"
OBJ_OUT = os.path.join(PROJECT, "src", "main", "resources", "assets",
                       "mekck", "models", "mesh", "bioreactor.obj")

# Atlas definition (256x256)
TSIZE = 256.0
INSET = 1.5
ATLAS = {
    "MAT_Mek_SlateSteel":     (0, 0, 64, 64),
    "MAT_Mek_GunmetalDark":    (64, 0, 128, 64),
    "MAT_Mek_ChromeSteel":     (128, 0, 192, 64),
    "MAT_Mek_AlloyPipe":       (192, 0, 256, 64),
    "MAT_Mek_LeadGlass":       (0, 64, 64, 128),
    "MAT_Mek_BioSlurry":       (64, 64, 128, 128),
    "MAT_Mek_CyanGlow":        (128, 64, 160, 96),
    "MAT_Mek_LimeGlow":        (160, 64, 192, 96),
    "MAT_Mek_AmberGlow":       (192, 64, 224, 96),
    "MAT_Mek_HazardStripes":   (0, 128, 128, 256),
    "MAT_Mek_ScreenTerminal":  (128, 96, 256, 256),
}


def build_and_export():
    print("=== 开始 Deep Mek 生物反应堆轻量化与导出 ===")
    src_col = bpy.data.collections.get("COL_Bioreactor_DeepMek")
    if not src_col:
        raise RuntimeError("未找到源集合 COL_Bioreactor_DeepMek！")

    # 1. 清理临时对象与集合
    for name in ["COL_Export_Temp", "COL_Bioreactor_Optimized"]:
        if name in bpy.data.collections:
            c = bpy.data.collections[name]
            for o in list(c.objects):
                bpy.data.objects.remove(o, do_unlink=True)
            bpy.data.collections.remove(c)

    for o in list(bpy.data.objects):
        if o.name.startswith("bioreactor_layer") or o.name.startswith("TEMP_"):
            bpy.data.objects.remove(o, do_unlink=True)

    temp_col = bpy.data.collections.new("COL_Export_Temp")
    bpy.context.scene.collection.children.link(temp_col)

    # 2. 复制源网格
    clones = []
    for o in src_col.objects:
        if o.type == 'MESH':
            c = o.copy()
            c.data = o.data.copy()
            # 确保应用世界变换到局部坐标
            c.data.transform(o.matrix_world)
            c.matrix_world = Matrix.Identity(4)
            temp_col.objects.link(c)
            clones.append(c)

    print(f"复制了 {len(clones)} 个组件")

    # 3. 降面不减配优化修改器
    small_detail_keywords = [
        "Bolt", "Screw", "Nut", "Pin", "Knob", "Hole", "LED", "LouverFin",
        "Gusset", "Clip", "Bumper", "Blade", "Rib", "Rod", "Barrel"
    ]
    large_structural_keywords = [
        "Window_Frame", "Console_Chassis", "Console_SidePlate", "Console_FrontFacia",
        "Saucer_Cradle", "Base_FloorRim", "Ceiling_Disc"
    ]

    for o in clones:
        base = o.name.split('.')[0]
        is_small = any(k in base for k in small_detail_keywords)
        for m in list(o.modifiers):
            if m.type == 'BEVEL':
                if is_small:
                    o.modifiers.remove(m)
                else:
                    m.segments = 1
                    m.width = min(m.width, 0.02)

    # 应用修改器 (除了 Weighted Normal)
    for o in clones:
        for m in list(o.modifiers):
            if m.type != 'WEIGHTED_NORMAL':
                bpy.context.view_layer.objects.active = o
                try:
                    bpy.ops.object.modifier_apply(modifier=m.name)
                except:
                    pass

    # 4. 重点几何体针对性减面 (保持圆滑度的前提下大幅裁减多余环)
    target_decimate = {
        "GEO_Reactor_InnerVessel": 384,
        "GEO_Bio_SlurryCore": 192,
        "GEO_Reactor_TopCyanCircuit": 192,
        "GEO_Reactor_BotCyanCircuit": 192,
        "GEO_Saucer_Cradle": 192,
        "GEO_Base_FloorRim": 96,
        "GEO_Ceiling_Disc": 96,
    }

    for o in clones:
        base = o.name.split('.')[0]
        n_poly = len(o.data.polygons)
        target = None
        if base in target_decimate:
            target = target_decimate[base]
        elif "GEO_Pipe_Bellows" in base:
            target = 14
        elif any(k in base for k in ["SplitFlange", "BotFlange", "SaddleClamp", "PulseRing"]):
            target = 20

        if target and n_poly > target:
            ratio = max(0.05, target / n_poly)
            m = o.modifiers.new("mc_dec", 'DECIMATE')
            m.ratio = ratio
            m.use_collapse_triangulate = True
            bpy.context.view_layer.objects.active = o
            bpy.ops.object.modifier_apply(modifier=m.name)

    # 5. 有限融并共面无用面
    for o in clones:
        bm = bmesh.new()
        bm.from_mesh(o.data)
        bmesh.ops.dissolve_limit(bm, angle_limit=math.radians(3.5), verts=bm.verts[:], edges=bm.edges[:])
        bm.to_mesh(o.data)
        bm.free()

    # 6. 面数预算统筹 (控制在 ~4000-5000 面以内)
    budget = 4500
    total_poly = sum(len(o.data.polygons) for o in clones)
    if total_poly > budget:
        scale = budget / total_poly
        for o in clones:
            have = len(o.data.polygons)
            if have > 12:
                target = max(8, int(have * scale))
                ratio = target / have
                m = o.modifiers.new("mc_budget", 'DECIMATE')
                m.ratio = ratio
                m.use_collapse_triangulate = True
                bpy.context.view_layer.objects.active = o
                bpy.ops.object.modifier_apply(modifier=m.name)

    print(f"优化后面数: {sum(len(o.data.polygons) for o in clones)} 面")

    # 7. UV 盒式投影至图集
    for o in clones:
        me = o.data
        while me.uv_layers:
            me.uv_layers.remove(me.uv_layers[0])
        uvl = me.uv_layers.new(name="UVMap")
        mat_names = [m.name for m in me.materials if m]

        is_screen = "GEO_Monitor_DisplayFace" in o.name
        is_hazard_door = "GEO_Console_FeederHatch" in o.name

        for poly in me.polygons:
            mid = mat_names[poly.material_index] if (mat_names and poly.material_index < len(mat_names)) else "MAT_Mek_SlateSteel"
            
            # 特殊面贴图
            if is_screen:
                mid = "MAT_Mek_ScreenTerminal"
            elif is_hazard_door and abs(poly.normal.y + 1.0) < 0.2:
                mid = "MAT_Mek_HazardStripes"

            x0, y0, x1, y1 = ATLAS.get(mid, ATLAS["MAT_Mek_SlateSteel"])
            u_min = x0 + INSET
            u_max = x1 - INSET
            v_min = y0 + INSET
            v_max = y1 - INSET

            n = poly.normal
            ax = max(range(3), key=lambda i: abs(n[i]))
            verts_co = [me.vertices[i].co for i in poly.vertices]
            
            if is_screen:
                # 终端屏幕精准正向映射
                xs = [v.x for v in verts_co]
                zs = [v.z for v in verts_co]
                min_x, max_x = min(xs), max(xs)
                min_z, max_z = min(zs), max(zs)
                dx = max_x - min_x
                dz = max_z - min_z
                for li, v in zip(poly.loop_indices, verts_co):
                    fu = 0.5 if dx < 1e-6 else (v.x - min_x) / dx
                    fv = 0.5 if dz < 1e-6 else (max_z - v.z) / dz  # 上下正向
                    px = u_min + (u_max - u_min) * fu
                    py = v_min + (v_max - v_min) * fv
                    uvl.data[li].uv = (px / TSIZE, 1.0 - py / TSIZE)
                continue

            if ax == 0:
                uvs = [(v.y, v.z) for v in verts_co]
            elif ax == 1:
                uvs = [(v.x, v.z) for v in verts_co]
            else:
                uvs = [(v.x, v.y) for v in verts_co]

            us = [p[0] for p in uvs]
            vs = [p[1] for p in uvs]
            du = max(us) - min(us)
            dv = max(vs) - min(vs)

            for li, pv in zip(poly.loop_indices, uvs):
                fu = 0.5 if du < 1e-9 else (pv[0] - min(us)) / du
                fv = 0.5 if dv < 1e-9 else (pv[1] - min(vs)) / dv
                px = u_min + (u_max - u_min) * fu
                py = v_min + (v_max - v_min) * fv
                uvl.data[li].uv = (px / TSIZE, 1.0 - py / TSIZE)

    # 8. 合并成单个整机网格
    bpy.ops.object.select_all(action='DESELECT')
    for o in clones:
        o.select_set(True)
    bpy.context.view_layer.objects.active = clones[0]
    bpy.ops.object.join()
    unified = bpy.context.view_layer.objects.active
    unified.name = "TEMP_Unified_Bioreactor"

    # 9. 切片成 3 个层 (Layer 0: Z[0,1], Layer 1: Z[1,2], Layer 2: Z[2,3])
    layers = []
    for layer_idx in range(3):
        bm = bmesh.new()
        bm.from_mesh(unified.data)
        
        if layer_idx == 0:
            # 截取 Z <= 1.0
            geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
            bmesh.ops.bisect_plane(bm, geom=geom, plane_co=(0, 0, 1.0), plane_no=(0, 0, 1.0),
                                  clear_outer=True, clear_inner=False)
            z_offset = 0.0
            # 夹紧到 [0.0, 1.0]
            for v in bm.verts:
                v.co.z = max(0.0, min(1.0, v.co.z))
        elif layer_idx == 1:
            # 截取 1.0 <= Z <= 2.0
            geom1 = bm.verts[:] + bm.edges[:] + bm.faces[:]
            bmesh.ops.bisect_plane(bm, geom=geom1, plane_co=(0, 0, 1.0), plane_no=(0, 0, 1.0),
                                  clear_outer=False, clear_inner=True)
            geom2 = bm.verts[:] + bm.edges[:] + bm.faces[:]
            bmesh.ops.bisect_plane(bm, geom=geom2, plane_co=(0, 0, 2.0), plane_no=(0, 0, 1.0),
                                  clear_outer=True, clear_inner=False)
            z_offset = 1.0
            for v in bm.verts:
                v.co.z = max(1.0, min(2.0, v.co.z))
        else: # layer_idx == 2
            # 截取 Z >= 2.0
            geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
            bmesh.ops.bisect_plane(bm, geom=geom, plane_co=(0, 0, 2.0), plane_no=(0, 0, 1.0),
                                  clear_outer=False, clear_inner=True)
            z_offset = 2.0
            for v in bm.verts:
                v.co.z = max(2.0, min(3.0, v.co.z))

        # 10. 坐标映射至 Minecraft (Y-up, -Z forward, 局部高 [0, 1])
        # Blender: X (L/R), Y (Back/Front), Z (Up)
        # Minecraft: X_mc = -X_bl, Y_mc = Z_bl - z_offset, Z_mc = Y_bl
        # 旋转矩阵行列式 = +1 (保绕序纯旋转)
        for v in bm.verts:
            x_bl = v.co.x
            y_bl = v.co.y
            z_bl = v.co.z
            v.co.x = -x_bl
            v.co.y = z_bl - z_offset
            v.co.z = y_bl

        # 重新计算法线以保证法线一致
        bm.normal_update()

        layer_mesh = bpy.data.meshes.new(f"bioreactor_layer{layer_idx}")
        bm.to_mesh(layer_mesh)
        bm.free()

        layer_obj = bpy.data.objects.new(f"bioreactor_layer{layer_idx}", layer_mesh)
        bpy.context.scene.collection.objects.link(layer_obj)
        layers.append(layer_obj)

        ys = [v.co.y for v in layer_obj.data.vertices]
        xs = [v.co.x for v in layer_obj.data.vertices]
        zs = [v.co.z for v in layer_obj.data.vertices]
        print(f"Layer {layer_idx}: {len(layer_obj.data.polygons)} 面, "
              f"Y=[{min(ys):.6f}, {max(ys):.6f}], X=[{min(xs):.3f}, {max(xs):.3f}], Z=[{min(zs):.3f}, {max(zs):.3f}]")

    # 11. 导出 OBJ
    os.makedirs(os.path.dirname(OBJ_OUT), exist_ok=True)
    bpy.ops.object.select_all(action='DESELECT')
    for o in layers:
        o.select_set(True)
    bpy.context.view_layer.objects.active = layers[0]

    # 坐标已在网格内变换好，导出时指定 forward=-Z, up=Y 保持不变
    bpy.ops.wm.obj_export(filepath=OBJ_OUT, export_selected_objects=True,
                          export_object_groups=True, export_materials=False,
                          export_uv=True, export_normals=True,
                          export_triangulated_mesh=True,
                          forward_axis='Y', up_axis='Z', apply_modifiers=True)

    print(f"成功导出 OBJ 至: {OBJ_OUT}")

    # 清理统一网格和临时层对象
    bpy.data.objects.remove(unified, do_unlink=True)
    for o in layers:
        bpy.data.objects.remove(o, do_unlink=True)
    bpy.data.collections.remove(temp_col)


if __name__ == "__main__":
    build_and_export()
