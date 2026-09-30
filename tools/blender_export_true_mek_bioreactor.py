"""Export True Mek Bioreactor from Blender to Minecraft OBJ format.

- Sourced from collection `COL_Bioreactor_TrueMek`
- High-contrast 256x256 authentic Mekanism texture atlas mapping
- Bisects into 3 layers at Z=1.0 and Z=2.0 (bioreactor_layer0, bioreactor_layer1, bioreactor_layer2)
- Normalizes local height to Y in [0.0, 1.0] for each layer
- Transforms coordinates: x_mc = -x_bl, y_mc = z_bl - z_offset, z_mc = y_bl (det = +1)
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

# 256x256 Atlas definition
TSIZE = 256.0
INSET = 2.0
ATLAS = {
    "MAT_Mek_SlateSteel":     (0, 0, 64, 64),
    "MAT_Mek_GunmetalDark":    (64, 0, 128, 64),
    "MAT_Mek_AlloyLight":     (128, 0, 192, 64),
    "MAT_Mek_BrightInox":     (192, 0, 256, 64),
    "MAT_Mek_BlastGlass":     (0, 64, 64, 128),
    "MAT_Mek_BioSlurry":       (64, 64, 128, 128),
    "MAT_Mek_CyanGlow":        (128, 64, 192, 128),
    "MAT_Mek_PortFlange":      (192, 64, 256, 128),
    "MAT_Mek_HazardStripes":   (0, 128, 128, 256),
    "MAT_Mek_ScreenTerminal":  (128, 128, 256, 256),
}

def export_true_mek():
    print("=== 开始 True Mek 生物反应堆导出 ===")
    src_col = bpy.data.collections.get("COL_Bioreactor_TrueMek")
    if not src_col:
        raise RuntimeError("未找到源集合 COL_Bioreactor_TrueMek！")

    # 1. 清理临时对象
    for name in ["COL_Export_Temp"]:
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
            c.data.transform(o.matrix_world)
            c.matrix_world = Matrix.Identity(4)
            temp_col.objects.link(c)
            clones.append(c)

    print(f"复制了 {len(clones)} 个组件")

    # 3. UV 盒式投影至图集
    for o in clones:
        me = o.data
        while me.uv_layers:
            me.uv_layers.remove(me.uv_layers[0])
        uvl = me.uv_layers.new(name="UVMap")
        mat_names = [m.name for m in me.materials if m]

        is_screen = "DisplayFace" in o.name or "Monitor" in o.name
        is_hazard = "HazardDoor" in o.name or "Hazard" in o.name
        is_port_flange = "Port" in o.name and "Flange" in o.name

        for poly in me.polygons:
            mid = mat_names[poly.material_index] if (mat_names and poly.material_index < len(mat_names)) else "MAT_Mek_SlateSteel"
            
            if is_screen:
                mid = "MAT_Mek_ScreenTerminal"
            elif is_hazard and abs(poly.normal.y + 1.0) < 0.3:
                mid = "MAT_Mek_HazardStripes"
            elif is_port_flange:
                mid = "MAT_Mek_PortFlange"

            x0, y0, x1, y1 = ATLAS.get(mid, ATLAS["MAT_Mek_SlateSteel"])
            u_min = x0 + INSET
            u_max = x1 - INSET
            v_min = y0 + INSET
            v_max = y1 - INSET

            verts_co = [me.vertices[i].co for i in poly.vertices]

            if is_screen:
                xs = [v.x for v in verts_co]
                zs = [v.z for v in verts_co]
                min_x, max_x = min(xs), max(xs)
                min_z, max_z = min(zs), max(zs)
                dx = max_x - min_x
                dz = max_z - min_z
                for li, v in zip(poly.loop_indices, verts_co):
                    fu = 0.5 if dx < 1e-6 else (v.x - min_x) / dx
                    fv = 0.5 if dz < 1e-6 else (max_z - v.z) / dz
                    px = u_min + (u_max - u_min) * fu
                    py = v_min + (v_max - v_min) * fv
                    uvl.data[li].uv = (px / TSIZE, 1.0 - py / TSIZE)
                continue

            if is_hazard and abs(poly.normal.y + 1.0) < 0.3:
                xs = [v.x for v in verts_co]
                zs = [v.z for v in verts_co]
                min_x, max_x = min(xs), max(xs)
                min_z, max_z = min(zs), max(zs)
                dx = max_x - min_x
                dz = max_z - min_z
                for li, v in zip(poly.loop_indices, verts_co):
                    fu = 0.5 if dx < 1e-6 else (v.x - min_x) / dx
                    fv = 0.5 if dz < 1e-6 else (max_z - v.z) / dz
                    px = u_min + (u_max - u_min) * fu
                    py = v_min + (v_max - v_min) * fv
                    uvl.data[li].uv = (px / TSIZE, 1.0 - py / TSIZE)
                continue

            n = poly.normal
            ax = max(range(3), key=lambda i: abs(n[i]))
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

    # 4. 合并网格
    bpy.ops.object.select_all(action='DESELECT')
    for o in clones:
        o.select_set(True)
    bpy.context.view_layer.objects.active = clones[0]
    bpy.ops.object.join()
    unified = bpy.context.view_layer.objects.active
    unified.name = "TEMP_Unified_TrueMek"

    # 5. 切片为 3 层
    layers = []
    for layer_idx in range(3):
        bm = bmesh.new()
        bm.from_mesh(unified.data)
        
        if layer_idx == 0:
            geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
            bmesh.ops.bisect_plane(bm, geom=geom, plane_co=(0, 0, 1.0), plane_no=(0, 0, 1.0),
                                  clear_outer=True, clear_inner=False)
            z_offset = 0.0
            for v in bm.verts:
                v.co.z = max(0.0, min(1.0, v.co.z))
        elif layer_idx == 1:
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
            geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
            bmesh.ops.bisect_plane(bm, geom=geom, plane_co=(0, 0, 2.0), plane_no=(0, 0, 1.0),
                                  clear_outer=False, clear_inner=True)
            z_offset = 2.0
            for v in bm.verts:
                v.co.z = max(2.0, min(3.0, v.co.z))

        # 6. 坐标变换至 Minecraft
        for v in bm.verts:
            x_bl = v.co.x
            y_bl = v.co.y
            z_bl = v.co.z
            v.co.x = -x_bl
            v.co.y = z_bl - z_offset
            v.co.z = y_bl

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

    # 7. 导出 OBJ
    os.makedirs(os.path.dirname(OBJ_OUT), exist_ok=True)
    bpy.ops.object.select_all(action='DESELECT')
    for o in layers:
        o.select_set(True)
    bpy.context.view_layer.objects.active = layers[0]

    bpy.ops.wm.obj_export(filepath=OBJ_OUT, export_selected_objects=True,
                          export_object_groups=True, export_materials=False,
                          export_uv=True, export_normals=True,
                          export_triangulated_mesh=True,
                          forward_axis='Y', up_axis='Z', apply_modifiers=True)

    print(f"成功导出 OBJ 至: {OBJ_OUT}")

    # 清理临时对象
    bpy.data.objects.remove(unified, do_unlink=True)
    for o in layers:
        bpy.data.objects.remove(o, do_unlink=True)
    bpy.data.collections.remove(temp_col)

if __name__ == "__main__":
    export_true_mek()
