package cn.ism.mekck.client;
import cn.ism.mekck.UniversalCuttingMachine;

import cn.ism.mekck.block.BioreactorBlock;
import cn.ism.mekck.block.PlantingCuttingFactoryBlock;
import cn.ism.mekck.block.PlantingCuttingStationBlock;
import cn.ism.mekck.client.MekCkOutlineRenderer;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.util.MekCkMultiblock;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Map;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeHasBounding;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import static cn.ism.mekck.UniversalCuttingMachine.MOD_ID;
import static cn.ism.mekck.registry.MekCkEffects.ETERNAL_FREEZE_EFFECT;
import static cn.ism.mekck.registry.MekCkEffects.FROZEN_EFFECT;

/**
 * 客户端世界侧事件：手持机器时的放置预览（半透明模型 / 包围盒线框）与其它模组机器的轮廓高亮。
 *
 * <p>本类原为 {@code UniversalCuttingMachine} 的内嵌事件订阅类；注册中枢拆分时
 * 升格为顶层类（去掉 {@code static} 修饰，方法体逐字未改）。</p>
 */
@Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientWorldEvents {

    /** 会话级：首次手持机器出现预览时提示一次配置开关说明。 */
    private static boolean previewTipShown = false;
    private static final org.slf4j.Logger OUTLINE_LOGGER =
            org.slf4j.LoggerFactory.getLogger("MekCK.Outline");

    private ClientWorldEvents() {
    }

    /** 冰封视觉：有「冰冻」或「永冻」效果的实体渲染一个与实体同大的半透明冰壳（仅视觉效果）。 */
    @SubscribeEvent
    @SuppressWarnings("rawtypes")
    public static void onPostRenderLiving(RenderLivingEvent.Post event) {
        LivingEntity entity = event.getEntity();
        int ticks = -1;
        MobEffectInstance frozen = entity.getEffect(FROZEN_EFFECT.get());
        if (frozen != null) ticks = frozen.getDuration();
        MobEffectInstance eternal = entity.getEffect(ETERNAL_FREEZE_EFFECT.get());
        if (eternal != null) ticks = Math.max(ticks, eternal.getDuration());
        if (ticks >= 0) {
            cn.ism.mekck.client.FrozenIceRender.render(entity,
                    event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), ticks);
        }
    }

    /** 本模组的单方块机器：除多方块（种植切配站/种植切配工厂/生物反应堆）外，mekck 命名空间下
     * 的所有基础机器与工厂版本均属之（含巧克力大炮/坚果爆炒机等非工厂机器，及未来新增机器）。 */
    private static boolean isOurSingleBlockMachine(net.minecraft.world.level.block.Block block) {
        if (block instanceof cn.ism.mekck.block.PlantingCuttingStationBlock
                || block instanceof cn.ism.mekck.block.PlantingCuttingFactoryBlock
                || block instanceof cn.ism.mekck.block.BioreactorBlock) {
            return false; // 多方块（处理器前面已按多方块路径处理，此处兜底）
        }
        net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
        return id != null && id.getNamespace().equals(UniversalCuttingMachine.MOD_ID);
    }

    /** 机器判定缓存（每方块类型一次）。 */
    /**
     * 多方块机器“占地面积”轮廓：把最底层（minY）的每个方块当作完整方块，
     * 只画与地面接触的底边（跳过被相邻块挡住的内部边），不画垂直棱与顶面，
     * 视觉上就是机器占地的投影轮廓。
     */
    private static void renderGroundOutline(com.mojang.blaze3d.vertex.PoseStack poseStack,
                                            com.mojang.blaze3d.vertex.VertexConsumer lines,
                                            java.util.List<net.minecraft.core.BlockPos> positions,
                                            Vec3 view, net.minecraft.core.BlockPos target) {
        if (positions.isEmpty()) return;
        int minY = Integer.MAX_VALUE;
        for (net.minecraft.core.BlockPos p1 : positions) {
            minY = Math.min(minY, p1.getY());
        }
        java.util.Set<Long> ground = new java.util.HashSet<>();
        for (net.minecraft.core.BlockPos p1 : positions) {
            if (p1.getY() == minY) {
                ground.add(((long) p1.getX() << 32) | (p1.getZ() & 0xFFFFFFFFL));
            }
        }
        if (ground.isEmpty()) return;
        // 与模型线框同一坐标系：translate(target - view)，画相对 target 的局部坐标，
        // 避免对 RenderHighlight poseStack 空间做假设导致白线错位。
        poseStack.pushPose();
        poseStack.translate(target.getX() - view.x, target.getY() - view.y, target.getZ() - view.z);
        org.joml.Matrix4f m = poseStack.last().pose();
        float y = minY - target.getY();
        for (long k : ground) {
            int x = (int) (k >> 32);
            int z = (int) (k & 0xFFFFFFFFL);
            int lx = x - target.getX();
            int lz = z - target.getZ();
            groundEdge(lines, m, ground, lx, y, lz, lx + 1, y, lz);          // 北
            groundEdge(lines, m, ground, lx + 1, y, lz, lx + 1, y, lz + 1);  // 东
            groundEdge(lines, m, ground, lx + 1, y, lz + 1, lx, y, lz + 1);  // 南
            groundEdge(lines, m, ground, lx, y, lz + 1, lx, y, lz);          // 西
        }
        poseStack.popPose();
    }

    /** 画一条底边；若相邻方向有同层块（内部边）则跳过。 */
    private static void groundEdge(com.mojang.blaze3d.vertex.VertexConsumer lines, org.joml.Matrix4f m,
                                   java.util.Set<Long> ground, int x0, float y0, int z0, int x1, float y1, int z1) {
        boolean hidden;
        if (z0 == z1) {
            int nx = (x0 + x1) / 2;
            hidden = ground.contains(((long) nx << 32) | ((long) (z0 - 1) & 0xFFFFFFFFL))
                    || ground.contains(((long) nx << 32) | ((long) (z0 + 1) & 0xFFFFFFFFL));
        } else {
            int nz = (z0 + z1) / 2;
            hidden = ground.contains(((long) (x0 - 1) << 32) | (nz & 0xFFFFFFFFL))
                    || ground.contains(((long) (x0 + 1) << 32) | (nz & 0xFFFFFFFFL));
        }
        if (hidden) return;
        lines.vertex(m, x0, y0, z0).color(1.0F, 1.0F, 1.0F, 0.9F).normal(0.0F, 1.0F, 0.0F).endVertex();
        lines.vertex(m, x1, y1, z1).color(1.0F, 1.0F, 1.0F, 0.9F).normal(0.0F, 1.0F, 0.0F).endVertex();
    }

    private static final java.util.Map<net.minecraft.world.level.block.Block, Boolean> OTHER_MOD_MACHINE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 其他模组的单方块机器判定：模组方块 + 有方块实体 + 有朝向 + (有运行状态属性 或 可存能量)。 */
    private static boolean isOtherModMachine(net.minecraft.world.level.block.Block block,
                                             net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
        if (id == null || id.getNamespace().equals("minecraft")) {
            return false;
        }
        Boolean cached = OTHER_MOD_MACHINE_CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            boolean hasFacing = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                    || state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
            boolean hasRunningState = hasRunningStateProperty(state);
            boolean hasEnergy = probesEnergy(block, state);
            // 通用机器：有朝向 + （运行状态 或 可存能量）
            result = hasFacing && (hasRunningState || hasEnergy);
            if (!result && isMekanismFamily(id)) {
                // Mekanism 系列容器类方块（能量立方 / 流体储罐 / 化学品储罐 / 箱柜等）：
                // 部分无朝向属性（如基础流体储罐只有 active），部分无 FE 能量能力（如化学品储罐），
                // 因此对 Mekanism 系放宽为：有方块实体 + （有朝向 或 运行状态 或 任意存储能力）。
                result = hasFacing || hasRunningState || probesStorage(block, state);
            }
        } catch (Throwable ignored) {
            result = false;
        }
        OTHER_MOD_MACHINE_CACHE.put(block, result);
        return result;
    }

    /** 运行状态类属性：active / lit / powered / working / on。 */
    private static boolean hasRunningStateProperty(net.minecraft.world.level.block.state.BlockState state) {
        for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
            String n = prop.getName();
            if ("active".equals(n) || "lit".equals(n) || "powered".equals(n)
                    || "working".equals(n) || "on".equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** 是否为 Mekanism 系列命名空间（mekanism / mekanism_extras / mekmm 等）。 */
    private static boolean isMekanismFamily(net.minecraft.resources.ResourceLocation id) {
        String ns = id.getNamespace();
        return ns.equals("mekanism") || ns.startsWith("mekanism") || ns.equals("mekmm");
    }

    /** 试探方块实体是否暴露任意存储能力（能量 / 流体 / 物品），用于 Mekanism 容器类方块判定。 */
    private static boolean probesStorage(net.minecraft.world.level.block.Block block,
                                         net.minecraft.world.level.block.state.BlockState state) {
        if (!(block instanceof net.minecraft.world.level.block.EntityBlock eb)) {
            return false;
        }
        net.minecraft.world.level.block.entity.BlockEntity be = null;
        try {
            be = eb.newBlockEntity(net.minecraft.core.BlockPos.ZERO, state);
            if (be == null) {
                return false;
            }
            return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).resolve().isPresent()
                    || be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER).resolve().isPresent()
                    || be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).resolve().isPresent();
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (be != null) {
                try {
                    be.setRemoved();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 试探方块实体是否暴露 FE 能量能力（创建一次并立即移除，全程 try/catch；按方块类型缓存）。 */
    private static boolean probesEnergy(net.minecraft.world.level.block.Block block,
                                        net.minecraft.world.level.block.state.BlockState state) {
        try {
            if (block instanceof net.minecraft.world.level.block.EntityBlock eb) {
                net.minecraft.world.level.block.entity.BlockEntity be =
                        eb.newBlockEntity(net.minecraft.core.BlockPos.ZERO, state);
                if (be != null) {
                    try {
                        boolean has = be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                                .resolve().isPresent();
                        return has;
                    } finally {
                        be.setRemoved();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @SubscribeEvent
    public static void onRenderHighlight(net.minecraftforge.client.event.RenderHighlightEvent.Block event) {
        // 装了独立模组「机器放置预览」（machinepreview）时，预览由它接管，
        // 本模组自动关闭以免两层叠加（形状已在构造期登记给它）。
        if (cn.ism.mekck.compat.MachinePreviewCompat.isLoaded()) {
            return;
        }
        // 与原版方块选择框同事件渲染（Mekanism 放置线框同款方案），必然可见
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.gameMode == null || mc.screen != null) {
            return;
        }
        // 主手/副手任一持有方块物品即触发预览（记录持有手与物品，用于构造与真实放置一致的上下文）
        BlockItem blockItem = null;
        net.minecraft.world.InteractionHand heldHand = net.minecraft.world.InteractionHand.MAIN_HAND;
        net.minecraft.world.item.ItemStack heldStack = net.minecraft.world.item.ItemStack.EMPTY;
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            net.minecraft.world.item.ItemStack held = mc.player.getItemInHand(hand);
            if (!held.isEmpty() && held.getItem() instanceof BlockItem bi) {
                blockItem = bi;
                heldHand = hand;
                heldStack = held;
                break;
            }
        }
        if (blockItem == null) {
            return;
        }
        net.minecraft.world.level.block.Block block = blockItem.getBlock();
        // 必须命中方块表面（排除 MISS/实体命中），否则 getBlockPos 无意义
        net.minecraft.world.phys.BlockHitResult bhr = event.getTarget();
        if (bhr.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return;
        }
        net.minecraft.core.BlockPos clicked = bhr.getBlockPos();
        net.minecraft.world.level.block.state.BlockState clickedState = mc.level.getBlockState(clicked);
        net.minecraft.core.BlockPos target = clickedState.canBeReplaced() ? clicked : clicked.relative(bhr.getDirection());
        if (!mc.level.getBlockState(target).canBeReplaced()) {
            return;
        }

        // 朝向：直接复用方块自身的真实放置规则（Block.getStateForPlacement），
        // 构造与真实放置一致的 BlockPlaceContext（同世界/玩家/手/物品/点击面/点击位置）。
        // 这样预览状态 = 玩家在当前位置实际放置后的状态，消除手写朝向算法（含此前
        // mekmm 6 向机器的 getOpposite 补偿）与真实放置不一致导致的水平 180° 反向；
        // 对特殊放置规则（如 mekmm 自研放置逻辑）也保留其真实行为。
        net.minecraft.world.level.block.state.BlockState placeState = block.defaultBlockState();
        try {
            net.minecraft.world.item.context.BlockPlaceContext ctx = new net.minecraft.world.item.context.BlockPlaceContext(
                    mc.level, mc.player, heldHand, heldStack, bhr);
            net.minecraft.world.level.block.state.BlockState placement = block.getStateForPlacement(ctx);
            if (placement != null) {
                placeState = placement;
            }
        } catch (Throwable t) {
            // 回退：默认状态（通常朝北），不崩溃；不修改其他模组/原版放置行为
            placeState = block.defaultBlockState();
        }

        // 绑定方块位置：本模组机器用内置形状；Mekanism/mekmm 机器读 AttributeHasBounding；
        // 单方块机器（无绑定位置）也显示预览，与多方块分别由配置控制
        List<net.minecraft.core.BlockPos> boundingPositions;
        boolean multiblock = false;
        if (block instanceof PlantingCuttingStationBlock || block instanceof PlantingCuttingFactoryBlock) {
            boundingPositions = MekCkMultiblock.getBoundingPositions(target, placeState, MekCkMultiblock.SHAPE_2_TALL);
            multiblock = true;
        } else if (block instanceof BioreactorBlock) {
            boundingPositions = MekCkMultiblock.getBoundingPositions(target, placeState, MekCkMultiblock.SHAPE_3X3X3);
            multiblock = true;
        } else {
            AttributeHasBounding bounding = Attribute.get(placeState, AttributeHasBounding.class);
            if (bounding != null) {
                boundingPositions = bounding.getPositions(target, placeState).toList();
                if (!boundingPositions.isEmpty()) {
                    multiblock = true;
                }
            } else {
                boundingPositions = java.util.List.of();
            }
        }

        if (multiblock) {
            // 多方块预览开关
            if (!cn.ism.mekck.config.MekckConfig.isMultiblockPreviewEnabled()) {
                return;
            }
            // 可放置校验：所有绑定方块位置必须可替换
            for (net.minecraft.core.BlockPos p : boundingPositions) {
                if (p.getY() < mc.level.getMinBuildHeight() || p.getY() >= mc.level.getMaxBuildHeight()) {
                    return;
                }
                net.minecraft.world.level.block.state.BlockState s = mc.level.getBlockState(p);
                if (!s.isAir() && !s.canBeReplaced()) {
                    return;
                }
            }
        } else if (isOurSingleBlockMachine(block)) {
            // 单方块预览：仅本模组含工厂版本的系列，配置开关
            if (!cn.ism.mekck.config.MekckConfig.isSingleBlockPreviewEnabled()) {
                return;
            }
        } else if (cn.ism.mekck.config.MekckConfig.isOtherModsMachinePreviewEnabled()
                && isOtherModMachine(block, placeState)) {
            // 其他模组的机器（机器特征判定），配置开关
            // 通过
        } else {
            return;
        }

        // 轮廓体积 = 目标格 + 全部绑定格（画整体包围盒）
        java.util.List<net.minecraft.core.BlockPos> outlinePositions = new java.util.ArrayList<>(boundingPositions);
        if (!outlinePositions.contains(target)) {
            outlinePositions.add(target);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (net.minecraft.core.BlockPos p : outlinePositions) {
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        // 真实模型线框描边（回退：整体包围盒 12 棱）——顶点必须 endVertex 才会写入缓冲
        Vec3 view = event.getCamera().getPosition();
        com.mojang.blaze3d.vertex.VertexConsumer lines = event.getMultiBufferSource()
                .getBuffer(net.minecraft.client.renderer.RenderType.lines());
        // 多方块机器：额外画“占地面积”轮廓（最底层方块底面外沿，白色亮线，不画与地面不接触的棱）
        if (multiblock) {
            renderGroundOutline(event.getPoseStack(), lines, boundingPositions, view, target);
        }
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(target.getX() - view.x, target.getY() - view.y, target.getZ() - view.z);
        // 半透明机器模型预览（配置开关，可与线框同时启用）。
        // 大型风力发电机：塔身/叶片由 mekmm 的 BER(BlockEntityRenderer) 渲染，BakedModel 只是占位 →
        // 走原生 BER 预览（不透明但部件正确，半透明对 BER 引擎层不可行）。其余机器走纯 BakedModel 半透明。
        if (cn.ism.mekck.config.MekckConfig.isTranslucentModelPreviewEnabled()) {
            if (MekCkOutlineRenderer.isBerPreviewBlock(placeState)) {
                // 风力发电机：BER 替代（BakedModel 仅占位）
                MekCkOutlineRenderer.renderBerPreview(poseStack, event.getMultiBufferSource(),
                        mc.level, placeState, target,
                        net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
            } else {
                MekCkOutlineRenderer.renderTranslucentModel(poseStack, event.getMultiBufferSource(),
                        mc.level, placeState,
                        net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                // 生物反应堆（多层结构）/ 能量立方（内部旋转体）：在模型之外叠加 BER 渲染
                if (MekCkOutlineRenderer.needsBerOverlay(placeState)) {
                    MekCkOutlineRenderer.renderBerPreview(poseStack, event.getMultiBufferSource(),
                            mc.level, placeState, target,
                            net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                }
            }
        }
        // 模型逐边线框（开关 wireframe_preview，默认关）
        boolean drewModel = false;
        if (cn.ism.mekck.config.MekckConfig.isWireframePreviewEnabled()) {
            com.mojang.blaze3d.vertex.VertexConsumer wireLines = event.getMultiBufferSource()
                    .getBuffer(net.minecraft.client.renderer.RenderType.lines());
            drewModel = MekCkOutlineRenderer.renderModelWireframe(poseStack, wireLines, mc.level, placeState);
        }
        // 包围盒线框（开关 box_preview，默认开）：机器模型顶点的彩虹包围盒，粗细 box_line_width
        if (cn.ism.mekck.config.MekckConfig.isBoxPreviewEnabled()) {
            float[] mb = MekCkOutlineRenderer.getModelBounds(mc.level, placeState);
            if (mb != null) {
                com.mojang.blaze3d.vertex.VertexConsumer boxLines = event.getMultiBufferSource()
                        .getBuffer(net.minecraft.client.renderer.RenderType.debugQuads());
                MekCkOutlineRenderer.renderBoxThick(poseStack, boxLines,
                        Math.min(mb[0], 0.0F), 0.0F, Math.min(mb[2], 0.0F),
                        Math.max(mb[3], 1.0F), Math.max(mb[4], 1.0F), Math.max(mb[5], 1.0F));
            }
        }
        poseStack.popPose();
        // 多方块机器（生物反应堆 2×2×3、mekmm 大型机等）模型通常只覆盖控制器/base 层，
        // 必须叠加整体结构包围盒，否则上层结构无任何轮廓；单方块机器仅当模型渲染失败
        // 且未启用包围盒线框时才画 1 格结构盒（避免与 box_preview 的模型盒重叠成“小盒”）。
        if ((!drewModel && !cn.ism.mekck.config.MekckConfig.isBoxPreviewEnabled()) || multiblock) {
            poseStack.pushPose();
            poseStack.translate(minX - view.x, minY - view.y, minZ - view.z);
            com.mojang.blaze3d.vertex.VertexConsumer boxLines = event.getMultiBufferSource()
                    .getBuffer(net.minecraft.client.renderer.RenderType.lines());
            MekCkOutlineRenderer.renderBox(poseStack, boxLines,
                    0, 0, 0,
                    maxX - minX + 1.0, maxY - minY + 1.0, maxZ - minZ + 1.0);
            poseStack.popPose();
        }
        // 替换原版方块选择框（本位置显示我们的模型/包围盒线框）
        event.setCanceled(true);
        // 首次预览提示：2 种预览功能可在配置文件 placement_preview 区分别开关
        if (!previewTipShown) {
            previewTipShown = true;
            if (mc.player != null) {
                // 玩家可见文案 → 语言键。这条原先是硬编码中文，躲过了只扫 client/ 的
                // i18n 护栏整整五轮：它一直住在**主类里的客户端事件内部类**中，
                // 而那个目录不在扫描范围内。事件类搬进 client/ 后当场被抓住 ——
                // 拆分顺带补上了一条护栏的盲区。
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.mekck.placement_preview_tip"),
                        false);
            }
        }
        if (OUTLINE_LOGGER.isDebugEnabled()) {
            OUTLINE_LOGGER.debug("[mekck-outline] 渲染轮廓 @{} block={} box={},{},{} ~ {},{},{}",
                    target, ForgeRegistries.BLOCKS.getKey(block),
                    minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    // [mekck-bb] 诊断：玩家右键点击绑定方块时，记录客户端是否已收到主块坐标（receivedCoords）。
    // 若日志显示 hit 的是 mekanism:bounding_block 但 mainPos=null，说明客户端同步仍未成功。
    //
    // 常驻 INFO 会让每次右键绑定块都往日志里写一行（本模组机器多为多块结构，右键即刷屏），
    // 因此降为 DEBUG：需要排查时把 logger "mekck.BoundingDiag" 调到 DEBUG 即可
    // （log4j2.xml 里加 <Logger name="mekck.BoundingDiag" level="debug"/>），诊断能力原样保留。
    private static final org.apache.logging.log4j.Logger BB_DIAG =
            org.apache.logging.log4j.LogManager.getLogger("mekck.BoundingDiag");

    @SubscribeEvent
    public static void onRightClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof mekanism.common.block.BlockBounding)) {
            return;
        }
        net.minecraft.core.BlockPos hit = event.getPos();
        mekanism.common.tile.TileEntityBoundingBlock t =
                mekanism.common.util.WorldUtils.getTileEntity(mekanism.common.tile.TileEntityBoundingBlock.class, event.getLevel(), hit);
        net.minecraft.core.BlockPos main = mekanism.common.block.BlockBounding.getMainBlockPos(event.getLevel(), hit);
        BB_DIAG.debug("[mekck-bb] 右键绑定块 {} → tile存在={}, receivedCoords={}, mainPos={}",
                hit, t != null, t != null && t.hasReceivedCoords(), main);
    }
}

