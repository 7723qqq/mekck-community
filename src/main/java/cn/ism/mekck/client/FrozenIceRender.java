package cn.ism.mekck.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * 实体冰封视觉：在实体上渲染一个与实体同大的半透明冰壳（frosted_ice 贴图，仅视觉效果），
 * 挂接在客户端 RenderLivingEvent.Post（见 UniversalCuttingMachine.ClientWorldEvents）。
 * 渲染逻辑仿 iceandfire RenderFrozenState，冰壳略大于实体包围盒（宽 +0.25、高 +0.325）。
 */
public final class FrozenIceRender {

    private FrozenIceRender() {
    }

    /**
     * 按剩余冰冻 tick 选<b>贴图档位</b>（越接近解除裂痕越多），与冰火一致。
     *
     * <p>返回的是 <b>0..3 的下标</b>而不是 {@link ResourceLocation}：贴图本体
     * 收在 {@link MekCkRenderTypes} 里，与它按档位缓存的 {@code RenderType} 一一对应。
     * 这样调用方不再需要持有 {@code ResourceLocation}，也就无从再写出
     * 「单参 {@code new ResourceLocation(String)} 被解析成
     * {@code namespace="textures/block"} + {@code path="frosted_ice_0.png"}」
     * 那种实际请求 {@code textures/block/textures/block/...} 的错（见该类注释）。</p>
     */
    private static int getIceLevel(int ticksFrozen) {
        if (ticksFrozen < 100) {
            if (ticksFrozen < 50) {
                if (ticksFrozen < 20) {
                    return 3;
                }
                return 2;
            }
            return 1;
        }
        return 0;
    }

    public static void render(LivingEntity entity, PoseStack poseStack, MultiBufferSource buffer, int light, int frozenTicks) {
        float expand = 0.125F;
        float expandY = 0.325F;
        float w = entity.getBbWidth() / 2.0F;
        float h = entity.getBbHeight();
        AABB box = new AABB(-w - expand, 0, -w - expand, w + expand, h + expandY, w + expand);
        poseStack.pushPose();
        renderBox(box, poseStack, buffer, light, 255, frozenTicks);
        poseStack.popPose();
    }

    private static void renderBox(AABB box, PoseStack poseStack, MultiBufferSource buffer, int light, int alpha, int frozenTicks) {
        RenderType renderType = MekCkRenderTypes.getIce(getIceLevel(frozenTicks));
        VertexConsumer vertex = buffer.getBuffer(renderType);
        Matrix4f matrix = poseStack.last().pose();

        float maxX = (float) box.maxX, minX = (float) box.minX;
        float maxY = (float) box.maxY, minY = (float) box.minY;
        float maxZ = (float) box.maxZ, minZ = (float) box.minZ;

        float maxU, maxV, minU, minV;

        // X+
        maxU = maxZ - minZ; maxV = maxY - minY; minU = minZ - maxZ; minV = minY - maxY;
        vertex.vertex(matrix, maxX, minY, minZ).color(255, 255, 255, alpha).uv(minU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(1.0F, 0.0F, 0.0F).endVertex();
        vertex.vertex(matrix, maxX, maxY, minZ).color(255, 255, 255, alpha).uv(minU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(1.0F, 0.0F, 0.0F).endVertex();
        vertex.vertex(matrix, maxX, maxY, maxZ).color(255, 255, 255, alpha).uv(maxU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(1.0F, 0.0F, 0.0F).endVertex();
        vertex.vertex(matrix, maxX, minY, maxZ).color(255, 255, 255, alpha).uv(maxU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(1.0F, 0.0F, 0.0F).endVertex();

        // X-
        vertex.vertex(matrix, minX, minY, maxZ).color(255, 255, 255, alpha).uv(minU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(-1.0F, 0.0F, 0.0F).endVertex();
        vertex.vertex(matrix, minX, maxY, maxZ).color(255, 255, 255, alpha).uv(minU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(-1.0F, 0.0F, 0.0F).endVertex();
        vertex.vertex(matrix, minX, maxY, minZ).color(255, 255, 255, alpha).uv(maxU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(-1.0F, 0.0F, 0.0F).endVertex();
        vertex.vertex(matrix, minX, minY, minZ).color(255, 255, 255, alpha).uv(maxU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(-1.0F, 0.0F, 0.0F).endVertex();

        // Z-
        maxU = maxX - minX; maxV = maxY - minY; minU = minX - maxX; minV = minY - maxY;
        vertex.vertex(matrix, minX, minY, minZ).color(255, 255, 255, alpha).uv(minU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, -1.0F).endVertex();
        vertex.vertex(matrix, minX, maxY, minZ).color(255, 255, 255, alpha).uv(minU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, -1.0F).endVertex();
        vertex.vertex(matrix, maxX, maxY, minZ).color(255, 255, 255, alpha).uv(maxU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, -1.0F).endVertex();
        vertex.vertex(matrix, maxX, minY, minZ).color(255, 255, 255, alpha).uv(maxU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, -1.0F).endVertex();

        // Z+
        vertex.vertex(matrix, maxX, minY, maxZ).color(255, 255, 255, alpha).uv(minU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, 1.0F).endVertex();
        vertex.vertex(matrix, maxX, maxY, maxZ).color(255, 255, 255, alpha).uv(minU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, 1.0F).endVertex();
        vertex.vertex(matrix, minX, maxY, maxZ).color(255, 255, 255, alpha).uv(maxU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, 1.0F).endVertex();
        vertex.vertex(matrix, minX, minY, maxZ).color(255, 255, 255, alpha).uv(maxU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 0.0F, 1.0F).endVertex();

        // Y+
        maxU = maxZ - minZ; maxV = maxX - minX; minU = minZ - maxZ; minV = minX - maxX;
        vertex.vertex(matrix, maxX, maxY, maxZ).color(255, 255, 255, alpha).uv(minU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 1.0F, 0.0F).endVertex();
        vertex.vertex(matrix, maxX, maxY, minZ).color(255, 255, 255, alpha).uv(maxU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 1.0F, 0.0F).endVertex();
        vertex.vertex(matrix, minX, maxY, minZ).color(255, 255, 255, alpha).uv(maxU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 1.0F, 0.0F).endVertex();
        vertex.vertex(matrix, minX, maxY, maxZ).color(255, 255, 255, alpha).uv(minU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 1.0F, 0.0F).endVertex();

        // Y-
        vertex.vertex(matrix, minX, minY, maxZ).color(255, 255, 255, alpha).uv(minU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, -1.0F, 0.0F).endVertex();
        vertex.vertex(matrix, minX, minY, minZ).color(255, 255, 255, alpha).uv(maxU, minV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, -1.0F, 0.0F).endVertex();
        vertex.vertex(matrix, maxX, minY, minZ).color(255, 255, 255, alpha).uv(maxU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, -1.0F, 0.0F).endVertex();
        vertex.vertex(matrix, maxX, minY, maxZ).color(255, 255, 255, alpha).uv(minU, maxV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, -1.0F, 0.0F).endVertex();
    }
}
