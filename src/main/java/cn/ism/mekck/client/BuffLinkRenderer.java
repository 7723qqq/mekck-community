package cn.ism.mekck.client;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.buff.BuffLinkIndex;
import cn.ism.mekck.config.MekckConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * F10 攻击增益连线的客户端渲染器（拍板：走 {@link RenderLevelStageEvent} 全局 handler，非逐机器 BER）。
 *
 * <p>每帧在 AFTER_TRANSLUCENT_BLOCKS 阶段遍历 {@link BuffLinkIndex} 中的 buff 连线，
 * 从目标机器顶面中心 → buff 源顶面中心（Q7a：{@code pos + (0.5, 1.0, 0.5)}），
 * 画一条长方体彩虹光束（§F39 起：抬升不再在本类叠加，由 {@code renderConnectionBeam}
 * 内部把中心线上提恰好一个半高 ⇒ 光束底沿贴机器顶面但不重叠），
 * 复用 {@link MekCkOutlineRenderer#renderConnectionBeam}（{@code RenderType.debugQuads}）。</p>
 *
 * <p>仅在客户端加载（{@code value = Dist.CLIENT}），专用服务器不会类加载本类。</p>
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BuffLinkRenderer {

    private BuffLinkRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!MekckConfig.isBuffConnectionRenderEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        Map<BlockPos, BlockPos> links = BuffLinkIndex.links();
        if (links.isEmpty()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        // 1.20.1 的 RenderLevelStageEvent 不暴露 buffer source getter，自行取全局渲染缓冲
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer buf = buffers.getBuffer(RenderType.debugQuads());
        ps.pushPose();
        // 世界坐标 → 相机相对坐标（与放置预览同源：先按 -相机 平移，再用绝对方块坐标绘制）
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (Map.Entry<BlockPos, BlockPos> e : links.entrySet()) {
            BlockPos tgt = e.getKey();
            BlockPos src = e.getValue();
            // 任一端未加载则跳过（防抖 / 跨 chunk 边缘不留残影）
            if (!level.isLoaded(tgt) || !level.isLoaded(src)) {
                continue;
            }
            // 顶面中心 y = blockY + 1.0（Q7a）；§F39：去掉旧 BEAM_LIFT=0.25 悬空抬升，
            // 贴顶面不重叠的落点由 renderConnectionBeam 内部上提半高实现。
            MekCkOutlineRenderer.renderConnectionBeam(ps, buf,
                    tgt.getX() + 0.5D, tgt.getY() + 1.0D, tgt.getZ() + 0.5D,
                    src.getX() + 0.5D, src.getY() + 1.0D, src.getZ() + 0.5D);
        }
        ps.popPose();
        buffers.endBatch(RenderType.debugQuads());
    }

    /** 登出 / 换世界：清空连线索引，避免跨存档残留坐标。 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        BuffLinkIndex.clear();
    }
}
