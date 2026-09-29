package cn.ism.mekck.client.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * 把 {@link ObjMesh} 写进 {@link VertexConsumer}。
 *
 * <h2>顶点色承载逐面明暗</h2>
 * OBJ 不携带顶点色，但顶点色仍必须写：它承担 vanilla baked 模型里由 {@code BakedQuad}
 * 预先烘焙的那份逐面明暗（上 1.0 / 南北 0.8 / 东西 0.6 / 下 0.5），见 {@link #shadeOf}。
 * 之所以要还原它，是因为配套的 {@code MekCkRenderTypes.objSolid} 用的是
 * {@code rendertype_solid}——该 shader 只看 lightmap、不做法线漫反射。
 * 若将来要按组染色，应扩展 {@link #renderGroup} 的参数，而不是改动
 * {@link ObjMesh} 的顶点布局。
 *
 * <h2>分组即骨骼</h2>
 * 每个分组单独绘制，调用方可在两次调用之间对 {@link PoseStack} 施加各自的变换，
 * 从而让 OBJ 的 {@code g} 分组对应 geo 骨骼、动画逻辑得以平移。
 */
public final class ObjMeshRenderer {

    private ObjMeshRenderer() {
    }

    /**
     * 绘制单个分组。分组不存在时静默跳过——缺一个零件不该让整台机器不渲染。
     */
    public static void renderGroup(ObjMesh mesh, String group, PoseStack poseStack,
                                   MultiBufferSource buffers, int light, int overlay, RenderType type) {
        if (mesh == null) {
            return;
        }
        float[] data = mesh.group(group);
        if (data == null || data.length == 0) {
            return;
        }
        emit(data, poseStack, buffers.getBuffer(type), light, overlay);
    }

    /** 按分组顺序绘制全部分组，用于静态整体模型。 */
    public static void renderAll(ObjMesh mesh, PoseStack poseStack, MultiBufferSource buffers,
                                 int light, int overlay, RenderType type) {
        if (mesh == null || !mesh.isAvailable()) {
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(type);
        for (String name : mesh.groupNames()) {
            float[] data = mesh.group(name);
            if (data != null && data.length > 0) {
                emit(data, poseStack, consumer, light, overlay);
            }
        }
    }

    private static void emit(float[] data, PoseStack poseStack, VertexConsumer consumer,
                             int light, int overlay) {
        PoseStack.Pose pose = poseStack.last();
        for (int i = 0; i + ObjMesh.STRIDE <= data.length; i += ObjMesh.STRIDE) {
            float nx = data[i + 5];
            float ny = data[i + 6];
            float nz = data[i + 7];
            int shade = shadeOf(nx, ny, nz);
            consumer.vertex(pose.pose(), data[i], data[i + 1], data[i + 2])
                    .color(shade, shade, shade, 0xFF)
                    .uv(data[i + 3], data[i + 4])
                    .overlayCoords(overlay)
                    .uv2(light)
                    .normal(pose.normal(), nx, ny, nz)
                    .endVertex();
        }
    }

    /**
     * 逐面明暗，还原 vanilla {@code BakedQuad} 烘焙进顶点色的那一份。
     *
     * <p>MC 对 baked 方块模型是按<b>模型空间</b>面朝向预先烘焙一个固定明暗再乘进顶点色的：
     * 上 1.0、南北 0.8、东西 0.6、下 0.5。本方法用模型空间法线（非变换后的世界法线）
     * 还原同一张表，这样改用 OBJ 之后画面与原来一致。</p>
     *
     * <p>本 RenderType 用的是 {@code rendertype_solid}（只看 lightmap、不做法线漫反射），
     * 所以明暗必须由顶点色承担；用 entity shader 则会换成另一套方向光结果。</p>
     */
    private static int shadeOf(float nx, float ny, float nz) {
        float shade;
        if (ny > 0.5f) {
            shade = 1.0f;                 // up
        } else if (ny < -0.5f) {
            shade = 0.5f;                 // down
        } else if (Math.abs(nz) >= Math.abs(nx)) {
            shade = 0.8f;                 // north / south
        } else {
            shade = 0.6f;                 // east / west
        }
        return Math.round(shade * 255.0f);
    }
}
