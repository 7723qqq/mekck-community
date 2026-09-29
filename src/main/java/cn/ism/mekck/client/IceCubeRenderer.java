package cn.ism.mekck.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.FallingBlockRenderer;
import net.minecraft.world.entity.item.FallingBlockEntity;

/**
 * 冰块实体渲染器：在原版落块渲染基础上整体缩小模型。
 * 缩放以实体位置为原点（落块渲染的方块底部即实体 y），
 * 因此冰块视觉上仍以底部对齐落点，仅体积变小。
 */
public class IceCubeRenderer extends FallingBlockRenderer {

    /** 模型缩放比例（1.0 = 完整方块）。 */
    public static final float SCALE = 0.6F;

    public IceCubeRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(FallingBlockEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.scale(SCALE, SCALE, SCALE);
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
        poseStack.popPose();
    }
}
