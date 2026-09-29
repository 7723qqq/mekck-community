package cn.ism.mekck.client;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.BioreactorBlock;
import cn.ism.mekck.blockentity.BioreactorBlockEntity;
import cn.ism.mekck.client.mesh.ObjMesh;
import cn.ism.mekck.client.mesh.ObjMeshLoader;
import cn.ism.mekck.client.mesh.ObjMeshRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 生物反应堆多方块模型渲染器。
 *
 * <p>几何高 48px，超出 vanilla 模型元素坐标限制（[-16, 32]）。原先的绕法是把资产
 * 切成 3 个 16px 层 JSON 模型（bioreactor_layer0/1/2）再叠加绘制；现在改为
 * <b>一个 OBJ 网格</b>（{@code models/mesh/bioreactor.obj}，由
 * {@code tools/convert_bioreactor_obj.py} 从那 3 个 JSON 烘焙而来，<b>几何完全未变</b>），
 * 层结构保留为 OBJ 的三个 {@code g} 分组，层间堆叠由本渲染器施加。</p>
 *
 * <p>之所以保留逐层平移与逐层光照，而不是把 OBJ 当一个整体直接画：原实现对每层
 * 用 {@code pos.above(i)} 取光照，方块遮挡时上下层亮度不同。合成一个整体就只能用
 * 单一光照值，视觉会与改造前有出入。</p>
 *
 * <p>主方块位于 2×2 足迹的西南角；模型绕足迹中心 (1, 0, 1) 按方块朝向旋转，
 * 32×32 足迹绕其中心旋转后与自身重合，绑定方块布局无需随朝向变化。</p>
 */
public final class BioreactorRenderer implements BlockEntityRenderer<BioreactorBlockEntity> {

    /** 层数（每层 16px 高）；与 OBJ 中的分组数一致。 */
    private static final int LAYER_COUNT = 3;

    private static final ResourceLocation MESH =
            new ResourceLocation(UniversalCuttingMachine.MOD_ID, "models/mesh/bioreactor.obj");

    private static final RenderType RENDER_TYPE = MekCkRenderTypes.objSolid(
            new ResourceLocation(UniversalCuttingMachine.MOD_ID, "block/mekck/bioreactor/bioreactor"));

    /** 各层在 OBJ 中的分组名；顺序与层的堆叠顺序一致。 */
    private static final List<String> LAYER_GROUPS =
            List.of("bioreactor_layer0", "bioreactor_layer1", "bioreactor_layer2");

    public BioreactorRenderer(BlockEntityRendererProvider.Context context) {
        // 无需持有 context；网格由 ObjMeshLoader 自行读取与缓存
    }

    @Override
    public void render(BioreactorBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int combinedLight, int combinedOverlay) {
        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }
        ObjMesh mesh = ObjMeshLoader.load(MESH);
        if (!mesh.isAvailable()) {
            return; // 网格缺失：只画 chunk 渲染的方块本体，不崩
        }

        BlockState state = blockEntity.getBlockState();
        Direction facing = state.hasProperty(BioreactorBlock.FACING)
                ? state.getValue(BioreactorBlock.FACING)
                : Direction.NORTH;
        // 与 blockstate y= 旋转一致的换算：EAST=90°、SOUTH=180°、WEST=270°（俯视顺时针 = 绕 +Y 的负向 = Axis.YN 正向）
        float angle = switch (facing) {
            case EAST -> 90.0f;
            case SOUTH -> 180.0f;
            case WEST -> 270.0f;
            default -> 0.0f;
        };

        BlockPos pos = blockEntity.getBlockPos();

        poseStack.pushPose();
        // 绕 2×2 足迹中心（主方块原点的 (1, 0, 1) 处）旋转，足迹旋转后不变
        poseStack.translate(1.0, 0.0, 1.0);
        poseStack.mulPose(Axis.YN.rotationDegrees(angle));
        poseStack.translate(-1.0, 0.0, -1.0);
        for (int i = 0; i < LAYER_GROUPS.size(); i++) {
            String group = LAYER_GROUPS.get(i);
            if (!mesh.hasGroup(group)) {
                continue; // 缺一层不该让整台机器不渲染
            }
            poseStack.pushPose();
            poseStack.translate(0.0, i, 0.0);
            int light = LevelRenderer.getLightColor(level, state, pos.above(i));
            ObjMeshRenderer.renderGroup(mesh, group, poseStack, bufferSource,
                    light, combinedOverlay, RENDER_TYPE);
            poseStack.popPose();
        }
        poseStack.popPose();
    }
}
