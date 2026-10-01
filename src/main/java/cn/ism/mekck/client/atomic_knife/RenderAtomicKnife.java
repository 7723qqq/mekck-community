package cn.ism.mekck.client.atomic_knife;

import cn.ism.mekck.client.atomic_knife.ModelAtomicKnife;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mekanism.client.render.item.MekanismISTER;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 原子刀物品渲染器（由 part2 1.21.1 移植到 1.20.1；MekanismISTER API 一致）。
 */
public class RenderAtomicKnife extends MekanismISTER {

    public static final RenderAtomicKnife RENDERER = new RenderAtomicKnife();
    private ModelAtomicKnife atomicKnife;

    @Override
    public void onResourceManagerReload(@NotNull ResourceManager resourceManager) {
        atomicKnife = new ModelAtomicKnife(getEntityModels());
    }

    @Override
    public void renderByItem(@NotNull ItemStack stack, @NotNull ItemDisplayContext displayContext, @NotNull PoseStack poseStack,
                             @NotNull MultiBufferSource bufferSource, int light, int overlayLight) {
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.ZP.rotationDegrees(180));
        atomicKnife.render(poseStack, bufferSource, light, overlayLight, stack.hasFoil());
        poseStack.popPose();
    }
}
