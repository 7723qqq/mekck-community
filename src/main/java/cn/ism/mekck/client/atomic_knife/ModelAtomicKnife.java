package cn.ism.mekck.client.atomic_knife;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import mekanism.client.model.MekanismJavaModel;
import mekanism.client.model.ModelPartData;
import mekanism.client.render.MekanismRenderType;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 原子刀模型（由 part2 1.21.1 移植到 1.20.1；
 * 1.20.1 的 MekanismJavaModel#renderToBuffer 使用 RGBA 浮点参数，其余 API 一致）。
 */
public class ModelAtomicKnife extends MekanismJavaModel {

    public static final ModelLayerLocation KNIFE_LAYER = new ModelLayerLocation(new ResourceLocation("mekck", "atomic_knife"), "main");
    private static final ResourceLocation KNIFE_TEXTURE = new ResourceLocation("mekck", "render/atomic_knife.png");

    private static final ModelPartData TOP_R1 = new ModelPartData("top_r1", CubeListBuilder.create()
          .texOffs(4, 13).addBox(-1.0F, -3.5F, -0.5F, 2.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)),
          PartPose.offsetAndRotation(1.0F, -24.0F, 0.0F, 0.0F, 0.0F, -0.9599F));

    private static final ModelPartData BLADE = new ModelPartData("blade", CubeListBuilder.create()
          .texOffs(4, 18).addBox(-2.5F, -14.5F, -0.5F, 2.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
          .texOffs(10, 15).addBox(-1.0F, -16.5F, -0.5F, 2.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
          .texOffs(12, 0).addBox(-2.5F, -25.5F, 0.0F, 1.0F, 11.0F, 0.0F, new CubeDeformation(0.0F))
          .texOffs(14, 19).addBox(-1.5F, -17.5F, 0.0F, 1.0F, 3.0F, 0.0F, new CubeDeformation(0.0F)),
          PartPose.offset(0.0F, 29.0F, 0.0F), TOP_R1);

    private static final ModelPartData BACK_BODY_R1 = new ModelPartData("back_body_r1", CubeListBuilder.create()
          .texOffs(0, 0).addBox(-1.5F, -2.0F, -1.5F, 3.0F, 4.0F, 3.0F, new CubeDeformation(0.0F)),
          PartPose.offsetAndRotation(1.0F, -20.5F, 0.0F, 0.0F, -0.7854F, 0.0F));

    private static final ModelPartData BLADE_BACK = new ModelPartData("blade_back", CubeListBuilder.create()
          .texOffs(16, 15).addBox(1.5F, -18.0F, -0.5F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F))
          .texOffs(14, 0).addBox(-0.5F, -16.0F, -1.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
          .texOffs(10, 19).addBox(0.5F, -19.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
          .texOffs(0, 20).addBox(-0.5F, -23.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
          .texOffs(14, 4).addBox(0.5F, -24.0F, -1.0F, 1.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)),
          PartPose.offset(0.0F, 29.0F, 0.0F), BACK_BODY_R1);

    private static final ModelPartData CONNECTOR = new ModelPartData("connector", CubeListBuilder.create()
          .texOffs(0, 7).addBox(-1.5F, -1.5F, -1.5F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
          .texOffs(14, 8).addBox(0.5F, -2.5F, -1.0F, 1.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)),
          PartPose.offsetAndRotation(0.0F, 15.5F, 0.0F, 0.0F, 0.0F, 0.7854F));

    private static final ModelPartData BB_MAIN = new ModelPartData("bb_main", CubeListBuilder.create()
          .texOffs(0, 13).addBox(-0.5F, -6.0F, -0.5F, 1.0F, 6.0F, 1.0F, new CubeDeformation(0.0F))
          .texOffs(12, 11).addBox(-1.0F, -8.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)),
          PartPose.offset(0.0F, 24.0F, 0.0F));

    private final RenderType BLADE_RENDER_TYPE = MekanismRenderType.BLADE.apply(KNIFE_TEXTURE);
    private final RenderType RENDER_TYPE = renderType(KNIFE_TEXTURE);
    private final List<ModelPart> parts;
    private final ModelPart bladePart;

    public ModelAtomicKnife(EntityModelSet entityModelSet) {
        super(RenderType::entitySolid);
        ModelPart root = entityModelSet.bakeLayer(KNIFE_LAYER);
        parts = getRenderableParts(root, BLADE, BLADE_BACK, CONNECTOR, BB_MAIN);
        bladePart = BLADE.getFromRoot(root);
    }

    public static LayerDefinition createLayerDefinition() {
        return createLayerDefinition(32, 32, BLADE, BLADE_BACK, CONNECTOR, BB_MAIN);
    }

    public void render(@NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int light, int overlayLight, boolean hasEffect) {
        renderToBuffer(poseStack, getVertexConsumer(bufferSource, RENDER_TYPE, hasEffect), light, overlayLight, 1, 1, 1, 1);
        if (bladePart != null) {
            bladePart.render(poseStack, getVertexConsumer(bufferSource, BLADE_RENDER_TYPE, hasEffect),
                    LightTexture.FULL_BRIGHT, overlayLight, 1, 1, 1, 0.75F);
        }
    }

    @Override
    public void renderToBuffer(@NotNull PoseStack poseStack, @NotNull VertexConsumer vertexConsumer, int light, int overlayLight,
          float red, float green, float blue, float alpha) {
        renderPartsToBuffer(parts, poseStack, vertexConsumer, light, overlayLight, red, green, blue, alpha);
    }
}
