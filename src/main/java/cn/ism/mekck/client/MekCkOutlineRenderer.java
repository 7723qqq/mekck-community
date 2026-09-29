package cn.ism.mekck.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/**
 * 多方块机器放置轮廓预览（真实模型线框描边）：
 * <ul>
 *   <li>读取机器 BakedModel 的 BakedQuad（BLOCK 顶点格式 stride=8），把每个四边形的 4 条边以淡蓝线框
 *       （0.75, 0.88, 1.0, alpha 0.55）画出 → 显示机器真实模型形状的描边；</li>
 *   <li>顶点坐标做 0..16 / 0..1 空间自动归一化（旧版"坐标空间不确定"的修复）；</li>
 *   <li>模型无 quad（如无 ModelData 的 Mekanism 系机器）时由调用方回退到包围盒 {@link #renderBox}；</li>
 *   <li>顶点必须以 {@code .endVertex()} 结束，否则不会被写入缓冲（历史不可见根因）。</li>
 * </ul>
 */
public final class MekCkOutlineRenderer {

    /** 包围盒线框粗细（倍数，由配置 box_line_width 设置；1.0 = 基础细带）。 */
    private static float boxLineWidth = 1.0F;
    /** 包围盒宽带的基础半宽（格）。 */
    private static final float BOX_BASE_HALF_WIDTH = 0.012F;



    /** 线框透明度。 */
    private static final float LINE_ALPHA = 0.55f;

    private MekCkOutlineRenderer() {
    }

    /**
     * 在 target 本地坐标（调用方已平移 target - 相机）绘制 placeState 模型的四边形描边。
     *
     * @return 是否成功画出了模型线框（有 quad）；无 quad 时返回 false 供调用方回退。
     */
    /**
     * 通过 BlockEntityRenderer 渲染半透明预览（适用于模型走 BER 的机器，如 mekmm 大型风力发电机：
     * 塔身/叶片由 BER 渲染，方块 BakedModel 只是占位 → BakedModel 路径取不到部件）。
     * 做法：为预览临时创建 BlockEntity（不入世界），取其实体渲染器并用“强制半透明”的
     * MultiBufferSource 渲染，从而得到真实部件形状的半透明画面。
     * 返回 true 表示已由 BER 渲染（调用方不必再走 BakedModel 路径）。
     */
    /** 该方块是否应走 BlockEntityRenderer 预览（部件由 BER 渲染、BakedModel 仅占位的机器）。 */
    public static boolean isBerPreviewBlock(BlockState state) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) return false;
        return "mekmm".equals(id.getNamespace()) && "large_wind_generator".equals(id.getPath());
    }

    /**
     * 是否需要在半透明模型之外“叠加” BER 渲染：
     * - 生物反应堆（mekck:bioreactor）：方块模型只有底层（layer0），完整 2×2×3 结构由 BioreactorRenderer 叠加 3 层；
     * - 通用机械能量立方（mekanism:*_energy_cube）：外壳由 BakedModel 提供，内部旋转立方体由 RenderEnergyCube 绘制。
     */
    public static boolean needsBerOverlay(BlockState state) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) return false;
        if ("mekck".equals(id.getNamespace()) && "bioreactor".equals(id.getPath())) return true;
        return "mekanism".equals(id.getNamespace()) && id.getPath().contains("energy_cube");
    }

    /**
     * 针对走 BlockEntityRenderer 渲染的机器（如 mekmm 大型风力发电机，塔身/叶片由 BER 绘制，
     * 方块 BakedModel 只是占位）的原生 BER 预览：用 BER 自己选择的 RenderType（不强制透明）渲染，
     * 得到正确部件几何。返回值 true 表示已由 BER 渲染（调用方不再走 BakedModel 路径）。
     * 注意：BER 内部 RenderType 多为 solid/cutout，无法做成干净半透明——预览为不透明但部件正确。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean renderBerPreview(PoseStack poseStack,
                                           net.minecraft.client.renderer.MultiBufferSource mbs,
                                           Level level, BlockState state,
                                           net.minecraft.core.BlockPos pos, int light) {
        net.minecraft.world.level.block.entity.BlockEntity be = null;
        try {
            if (state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
                be = entityBlock.newBlockEntity(pos, state);
            }
        } catch (Throwable ignored) {
            return false;
        }
        if (be == null) return false;
        // §F26：能量立方的临时 BE 储能为 0 会让 RenderEnergyCube.shouldRender 直接 false（内部旋转核不画）
        // → 取/调 BER 前先灌满储能（全反射、静默失败；只碰临时 BE，不碰世界真实方块）。
        cn.ism.mekck.util.EnergyCubePreviewUtil.seedTempEnergyIfCube(be);
        try {
            be.setLevel(level); // 临时 BE 补 world 上下文，避免 BER 内 getLevel() NPE
            net.minecraft.client.renderer.blockentity.BlockEntityRenderer<?> ber =
                    Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
            if (ber == null) return false;
            ((net.minecraft.client.renderer.blockentity.BlockEntityRenderer) ber).render(be, 0.0F,
                    poseStack, mbs, light,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            return true;
        } catch (Throwable t) {
            PREVIEW_LOGGER.debug("[mekck-preview] BER 预览渲染失败 block={} err={}",
                    net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()), t.toString());
            return false;
        }
    }

    private static final org.slf4j.Logger PREVIEW_LOGGER = org.slf4j.LoggerFactory.getLogger("mekck-preview");

    // 放置预览是**每帧**渲染的路径：复用两块只属于客户端渲染线程的临时对象，
    // 避免每帧新建 ArrayList、以及每个 quad 新建一个顶点采集器（客户端渲染单线程，复用安全）。
    private static final java.util.List<BakedQuad> QUAD_SCRATCH = new java.util.ArrayList<>();
    private static final WireFrameCollector WIRE_COLLECTOR = new WireFrameCollector();
    /** 单位姿态（只用于读取 {@code last()}，从不 push/pop）：避免每次调用 new 一个 PoseStack。 */
    private static final com.mojang.blaze3d.vertex.PoseStack IDENTITY_POSE = new com.mojang.blaze3d.vertex.PoseStack();

    /**
     * 半透明机器模型预览（配置开关 translucent_model_preview）。
     * 在放置预览中渲染半透明的真实模型（可透过），可与线框同时启用。
     * 调用前 PoseStack 应已 translate(target - view)（与线框同坐标系）。
     */
    public static void renderTranslucentModel(PoseStack poseStack,
                                              net.minecraft.client.renderer.MultiBufferSource mbs,
                                              Level level, BlockState state, int light) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getBlockModel(state);
        if (model == null) return;
        // §F28：Mekanism 能量立方走 IDynamicBakedModel，几何分组（frame/leds/ports）靠自定义 ModelProperty
        // 决定画不画：默认 3 参 getQuads 被强制塞 ModelData.EMPTY ⇒ 6 面全 INACTIVE ⇒ 端口盖板（ports）
        // 一个不画、只剩框架。这里对能量立方主动伪造一份「六面全 ACTIVE_UNLIT」的 ModelData，改走 5 参版。
        net.minecraftforge.client.model.data.ModelData previewData = buildEnergyCubePreviewModelData(state);
        net.minecraftforge.client.model.IDynamicBakedModel dyn =
                previewData != null && model instanceof net.minecraftforge.client.model.IDynamicBakedModel d ? d : null;
        // 手动遍历 quad 并 putBulkData 到半透明 buffer（readExistingColor=false 强制白色）。
        // 放弃 renderModel：它对需要 ModelData 的 custom BakedModel（mekmm solar、mekck 工厂等）
        // 会静默渲染错误/空输出；手动 quad 与 getModelBounds 同源，普通/OBJ/custom 模型均可靠。
        VertexConsumer raw = mbs.getBuffer(net.minecraft.client.renderer.RenderType.translucent());
        VertexConsumer wrapped = new AlphaVertexConsumer(raw, cn.ism.mekck.config.MekckConfig.getTranslucentModelAlpha());
        RandomSource rand = RandomSource.create(0);
        for (Direction side : cn.ism.mekck.util.Directions.VALUES) {
            for (BakedQuad q : dyn != null ? dyn.getQuads(state, side, rand, previewData, null)
                    : model.getQuads(state, side, rand)) {
                putQuadTranslucent(wrapped, poseStack, q, light);
            }
        }
        for (BakedQuad q : dyn != null ? dyn.getQuads(state, null, rand, previewData, null)
                : model.getQuads(state, null, rand)) {
            putQuadTranslucent(wrapped, poseStack, q, light);
        }
    }

    /** §F28：反射探测结果缓存（预览每帧调用，只探一次；失败则永久静默降级为现状）。 */
    @SuppressWarnings("rawtypes")
    private static net.minecraftforge.client.model.data.ModelProperty sideStateProp;
    private static Object sideStateActiveUnlit;
    private static boolean sideStateProbeFailed;

    /**
     * 为 Mekanism 能量立方预览伪造「六面全 ACTIVE_UNLIT」的 ModelData（等价刚放下一台、默认自动收/发电）；
     * 非能量立方或反射失败返回 null（调用方回退默认 3 参 getQuads，不崩、不影响其它机器预览）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraftforge.client.model.data.ModelData buildEnergyCubePreviewModelData(BlockState state) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null || !"mekanism".equals(id.getNamespace()) || !id.getPath().contains("energy_cube")) {
            return null; // 沿用 needsBerOverlay 同款判定，不新增路径
        }
        try {
            if (sideStateProp == null || sideStateActiveUnlit == null) {
                if (sideStateProbeFailed) return null;
                Class<?> tile = Class.forName("mekanism.common.tile.TileEntityEnergyCube");
                sideStateProp = (net.minecraftforge.client.model.data.ModelProperty) tile
                        .getField("SIDE_STATE_PROPERTY").get(null);
                Class<?> enumCls = Class.forName("mekanism.common.tile.TileEntityEnergyCube$CubeSideState");
                for (Object constant : enumCls.getEnumConstants()) {
                    if ("ACTIVE_UNLIT".equals(((Enum<?>) constant).name())) {
                        sideStateActiveUnlit = constant;
                        break;
                    }
                }
                if (sideStateProp == null || sideStateActiveUnlit == null) {
                    sideStateProbeFailed = true;
                    return null;
                }
            }
            // CubeSideState[6]，EnumUtils.SIDES 顺序全填 ACTIVE_UNLIT（ports 绘制只看每面状态非 INACTIVE）
            Object sideStates = java.lang.reflect.Array.newInstance(sideStateActiveUnlit.getClass(), 6);
            for (int i = 0; i < 6; i++) {
                java.lang.reflect.Array.set(sideStates, i, sideStateActiveUnlit);
            }
            return net.minecraftforge.client.model.data.ModelData.builder()
                    .with((net.minecraftforge.client.model.data.ModelProperty) sideStateProp, sideStates)
                    .build();
        } catch (Throwable t) {
            sideStateProbeFailed = true; // 未装 Mekanism/类改名 → 静默退回现状（只有框架），不反复探测
            return null;
        }
    }

    /** 单个 quad 的半透明写入（异常仅跳过该 quad）。readExistingColor=false 强制白色。 */
    private static void putQuadTranslucent(VertexConsumer wrapped, PoseStack poseStack, BakedQuad q, int light) {
        try {
            // 顶点有限性防御：NaN/Inf 顶点会让渲染丢弃整条 quad
            int[] vd = q.getVertices();
            if (vd != null) {
                for (int i = 0; i < vd.length; i += 8) {
                    float x = Float.intBitsToFloat(vd[i]);
                    float y = Float.intBitsToFloat(vd[i + 1]);
                    float z = Float.intBitsToFloat(vd[i + 2]);
                    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                        return;
                    }
                }
            }
            wrapped.putBulkData(poseStack.last(), q, 1.0F, 1.0F, 1.0F, 1.0F,
                    light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, false);
        } catch (Throwable ignored) {
            // 单个 quad 失败不影响其余 quad
        }
    }

    /** 把写入的顶点 alpha 乘以配置透明度的 VertexConsumer 包装（1.20.1 真实抽象方法透传）。 */
    private static final class AlphaVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float alpha;
        AlphaVertexConsumer(VertexConsumer delegate, float alpha) {
            this.delegate = delegate;
            this.alpha = alpha;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) { return delegate.vertex(x, y, z); }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return delegate.color(r, g, b, (int) (a * alpha)); }
        @Override public VertexConsumer uv(float u, float v) { return delegate.uv(u, v); }
        @Override public VertexConsumer overlayCoords(int u, int v) { return delegate.overlayCoords(u, v); }
        @Override public VertexConsumer uv2(int u, int v) { return delegate.uv2(u, v); }
        @Override public VertexConsumer normal(float x, float y, float z) { return delegate.normal(x, y, z); }
        @Override public void endVertex() { delegate.endVertex(); }
        @Override public void defaultColor(int r, int g, int b, int a) { delegate.defaultColor(r, g, b, a); }
        @Override public void unsetDefaultColor() { delegate.unsetDefaultColor(); }
    }

    /** 采集模型 quad 顶点的轴对齐范围（格单位，相对 target 原点）。返回 [minX,minY,minZ,maxX,maxY,maxZ] 或 null。 */
    public static float[] getModelBounds(Level level, BlockState state) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getBlockModel(state);
        if (model == null) return null;
        RandomSource rand = RandomSource.create(0);
        // 直接把 quad 顶点累加进边界即可（原先每个 quad 都要 new 一个 WireFrameCollector + 单位 PoseStack，
        // 再由 take() 返回 float[][]; 单位姿态下两者数值完全一致）。本方法在**每帧**的放置预览里调用，
        // 一个中等模型原先要产生上百次小对象分配。
        float[] bounds = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (Direction side : cn.ism.mekck.util.Directions.VALUES) {
            for (BakedQuad q : model.getQuads(state, side, rand)) {
                if (accumulateQuadBounds(q, bounds)) any = true;
            }
        }
        for (BakedQuad q : model.getQuads(state, null, rand)) {
            if (accumulateQuadBounds(q, bounds)) any = true;
        }
        return any ? bounds : null;
    }

    /**
     * 把单个 quad 的顶点累加进边界数组（minX,minY,minZ,maxX,maxY,maxZ）。
     * 顶点少于 4 个时返回 false —— 与原先 {@code WireFrameCollector.take()} 返回 null 的判定一致。
     */
    private static boolean accumulateQuadBounds(BakedQuad q, float[] bounds) {
        int[] vd = q.getVertices();
        if (vd == null || vd.length < 32) return false; // 4 顶点 × 8 int
        for (int i = 0; i < 32; i += 8) {
            float x = Float.intBitsToFloat(vd[i]);
            float y = Float.intBitsToFloat(vd[i + 1]);
            float z = Float.intBitsToFloat(vd[i + 2]);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) continue;
            if (x < bounds[0]) bounds[0] = x;
            if (y < bounds[1]) bounds[1] = y;
            if (z < bounds[2]) bounds[2] = z;
            if (x > bounds[3]) bounds[3] = x;
            if (y > bounds[4]) bounds[4] = y;
            if (z > bounds[5]) bounds[5] = z;
        }
        return true;
    }

    public static boolean renderModelWireframe(PoseStack poseStack, VertexConsumer lines,
                                               Level level, BlockState state) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getBlockModel(state);
        if (model == null) {
            return false;
        }
        RandomSource rand = RandomSource.create(0);
        Matrix4f m = poseStack.last().pose();
        // 复用 quad 缓冲（本方法在放置预览里**每帧**调用；客户端渲染线程单线程，复用安全）
        java.util.List<BakedQuad> quads = QUAD_SCRATCH;
        quads.clear();
        for (Direction side : cn.ism.mekck.util.Directions.VALUES) {
            quads.addAll(model.getQuads(state, side, rand));
        }
        quads.addAll(model.getQuads(state, null, rand));
        if (quads.isEmpty()) {
            return false;
        }
        // 顶点采集走 vanilla putBulkData（单位姿态）——与 Mekanism Quad 构造同路径，
        // 由 putBulkData 内置换算得到方块单位坐标，避免手动缩放出错。
        // 顶点坐标系说明（均相对 target 原点，格单位）：
        //  - MekCK/Mekanism 普通机器（FaceBakery）：0..1；
        //  - mekmm 普通机器（forge:composite 的 elements 不经 /16）：0..16（如玻璃 [4,16,1]~[12,26,1]，
        //    max≈1.625 格），逐边渲染即真实模型轮廓；
        //  - mekmm 大型机 OBJ 叶片（ObjModel.bake 不缩放）：x∈[-0.74,1.74] z∈[3.53,3.84]（远离 target）。
        // 判定依据：只有顶点“明显远离单块”才退化为塔身延伸柱盒（大型机叶片 z 3.5 格 / x 负向越界）；
        // 0..16 的普通复合机器逐边正常显示（此前 span>1.5 会误伤这类模型 → 回退包围盒）。
        com.mojang.blaze3d.vertex.PoseStack.Pose identity = IDENTITY_POSE.last();
        WireFrameCollector collector = WIRE_COLLECTOR;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        boolean finite = true;
        for (BakedQuad q : quads) {
            collector.reset(); // 复用同一个采集器：take() 的返回值在循环内立即被消费
            collector.putBulkData(identity, q, 1.0F, 1.0F, 1.0F, 1.0F, 0,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, true);
            float[][] pos = collector.take();
            if (pos == null) {
                continue;
            }
            for (float[] p1 : pos) {
                // 非有限值防御：跳过异常顶点，避免 NaN/Inf 进入几何
                if (!Float.isFinite(p1[0]) || !Float.isFinite(p1[1]) || !Float.isFinite(p1[2])) {
                    finite = false;
                    continue;
                }
                minX = Math.min(minX, p1[0]); minY = Math.min(minY, p1[1]); minZ = Math.min(minZ, p1[2]);
                maxX = Math.max(maxX, p1[0]); maxY = Math.max(maxY, p1[1]); maxZ = Math.max(maxZ, p1[2]);
            }
            any = true;
        }
        if (!any || !finite) {
            // 无可用顶点或几何非有限：交由调用方回退结构包围盒，不绘制错误线框
            return false;
        }
        // 仅“明显远离单块”才柱盒：min < -1.0 或 max > 2.0（格单位）。
        // mekmm 大型机（elements 格单位，min≈[-1,-1,-1] max≈[2,2,2]）逐边显示真实模型；
        // 仅 OBJ 大型机（wind 叶片 z 3.84）柱盒。普通复合机器（0..16，max≈1.625）逐边。
        boolean farOut = minX < -1.0F || minY < -1.0F || minZ < -1.0F
                || maxX > 2.0F || maxY > 2.0F || maxZ > 2.0F;
        if (farOut) {
            // “塔身延伸柱盒”：基于真实顶点范围（格单位，相对 target），从地面延伸到模型最高点/最远深度。
            // 不叠加任何固定 180° 旋转——朝向由调用方传入的真实 placeState 决定。
            float x0 = Math.min(minX, 0.0F);
            float y0 = 0.0F;
            float z0 = Math.min(minZ, 0.0F);
            float x1 = Math.max(maxX, 1.0F);
            float y1 = Math.max(maxY, 1.0F);
            float z1 = Math.max(maxZ, 1.0F);
            renderBox(poseStack, lines, x0, y0, z0, x1, y1, z1);
            return true;
        }
        // 正常单块模型：逐边彩虹线框
        float hueBase = rainbowHueBase();
        int edgeIdx = 0;
        for (BakedQuad q : quads) {
            collector.reset(); // 复用同一个采集器：take() 的返回值在循环内立即被消费
            collector.putBulkData(identity, q, 1.0F, 1.0F, 1.0F, 1.0F, 0,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, true);
            float[][] pos = collector.take();
            if (pos == null) {
                continue;
            }
            for (int e = 0; e < 4; e++) {
                float[] a = pos[e];
                float[] b = pos[(e + 1) % 4];
                edgeRainbow(lines, m, a[0], a[1], a[2], b[0], b[1], b[2], hueBase, edgeIdx++);
            }
        }
        return true;
    }

    /**
     * 以本地坐标绘制包围盒 12 条棱（不断变色的彩虹色，回退用）。调用前 PoseStack 应已平移 (min - 相机)。
     */
    public static void renderBox(PoseStack poseStack, VertexConsumer lines,
                                 double x0, double y0, double z0,
                                 double x1, double y1, double z1) {
        Matrix4f m = poseStack.last().pose();
        float hueBase = rainbowHueBase();
        int idx = 0;
        edge(lines, m, x0, y0, z0, x1, y0, z0, idx++);
        edge(lines, m, x1, y0, z0, x1, y0, z1, idx++);
        edge(lines, m, x1, y0, z1, x0, y0, z1, idx++);
        edge(lines, m, x0, y0, z1, x0, y0, z0, idx++);
        edge(lines, m, x0, y1, z0, x1, y1, z0, idx++);
        edge(lines, m, x1, y1, z0, x1, y1, z1, idx++);
        edge(lines, m, x1, y1, z1, x0, y1, z1, idx++);
        edge(lines, m, x0, y1, z1, x0, y1, z0, idx++);
        edge(lines, m, x0, y0, z0, x0, y1, z0, idx++);
        edge(lines, m, x1, y0, z0, x1, y1, z0, idx++);
        edge(lines, m, x1, y0, z1, x1, y1, z1, idx++);
        edge(lines, m, x0, y0, z1, x0, y1, z1, idx++);
    }

    /**
     * 采集 putBulkData 写入的 4 个顶点（方块单位坐标），其余顶点通道忽略。
     * 与 Mekanism 的 BakedQuadUnpacker 思路一致，坐标空间由 vanilla 换算保证。
     */
    private static final class WireFrameCollector implements com.mojang.blaze3d.vertex.VertexConsumer {

        private final float[][] captured = new float[4][3];
        private int idx = 0;

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer vertex(double x, double y, double z) {
            if (idx < 4) {
                captured[idx][0] = (float) x;
                captured[idx][1] = (float) y;
                captured[idx][2] = (float) z;
            }
            idx++;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer color(float r, float g, float b, float a) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }

        /** 复用前复位（顶点数组本身会复用，take() 的结果必须立即消费）。 */
        public void reset() {
            idx = 0;
        }

        /** @return 4 个顶点坐标（方块单位），无顶点时 null。 */
        public float[][] take() {
            return idx >= 4 ? captured : null;
        }
    }

    /** 不断变色的彩虹棱（模型线框，alpha 0.55）。 */
    /** 包围盒宽带棱（四边形带，用于 box_preview 加粗；宽 = BOX_BASE_HALF_WIDTH * boxLineWidth）。 */
    private static void edgeThick(VertexConsumer c, Matrix4f m,
                                  double x0, double y0, double z0,
                                  double x1, double y1, double z1,
                                  float hueBase, int edgeIdx) {
        float[] rgb = hsvToRgb((hueBase + edgeIdx * 137.508f) % 360f, 0.85f, 1.0f);
        float r = rgb[0], g = rgb[1], b = rgb[2], a = 0.45F;
        float dx = (float) (x1 - x0), dy = (float) (y1 - y0), dz = (float) (z1 - z0);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6F) return;
        float ux = dy * 0 - dz * 1, uy = dz * 0 - dx * 0, uz = dx * 1 - dy * 0;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (ul < 1.0e-4F) {
            ux = 0; uy = dz; uz = -dy;
            ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (ul < 1.0e-4F) return;
        }
        float hw = BOX_BASE_HALF_WIDTH * boxLineWidth;
        ux = ux / ul * hw; uy = uy / ul * hw; uz = uz / ul * hw;
        c.vertex(m, (float) x0 + ux, (float) y0 + uy, (float) z0 + uz).color(r, g, b, a).normal(0, 1, 0).endVertex();
        c.vertex(m, (float) x0 - ux, (float) y0 - uy, (float) z0 - uz).color(r, g, b, a).normal(0, 1, 0).endVertex();
        c.vertex(m, (float) x1 - ux, (float) y1 - uy, (float) z1 - uz).color(r, g, b, a).normal(0, 1, 0).endVertex();
        c.vertex(m, (float) x1 + ux, (float) y1 + uy, (float) z1 + uz).color(r, g, b, a).normal(0, 1, 0).endVertex();
    }

    /** 包围盒线框（彩虹、可按配置加粗）12 棱。调用前 PoseStack 应已平移。 */
    public static void renderBoxThick(PoseStack poseStack, VertexConsumer c,
                                      double x0, double y0, double z0,
                                      double x1, double y1, double z1) {
        boxLineWidth = cn.ism.mekck.config.MekckConfig.getBoxLineWidth();
        Matrix4f m = poseStack.last().pose();
        float hueBase = rainbowHueBase();
        int idx = 0;
        edgeThick(c, m, x0, y0, z0, x1, y0, z0, hueBase, idx++);
        edgeThick(c, m, x1, y0, z0, x1, y0, z1, hueBase, idx++);
        edgeThick(c, m, x1, y0, z1, x0, y0, z1, hueBase, idx++);
        edgeThick(c, m, x0, y0, z1, x0, y0, z0, hueBase, idx++);
        edgeThick(c, m, x0, y1, z0, x1, y1, z0, hueBase, idx++);
        edgeThick(c, m, x1, y1, z0, x1, y1, z1, hueBase, idx++);
        edgeThick(c, m, x1, y1, z1, x0, y1, z1, hueBase, idx++);
        edgeThick(c, m, x0, y1, z1, x0, y1, z0, hueBase, idx++);
        edgeThick(c, m, x0, y0, z0, x0, y1, z0, hueBase, idx++);
        edgeThick(c, m, x1, y0, z0, x1, y1, z0, hueBase, idx++);
        edgeThick(c, m, x1, y0, z1, x1, y1, z1, hueBase, idx++);
        edgeThick(c, m, x0, y0, z1, x0, y1, z1, hueBase, idx++);
    }

    /**
     * F10 buff 连线：两点之间画一条**长方体彩虹光束**（截面为正方形：水平半宽 u + 竖直半高 v，
     * 每段 4 侧面 + 首尾端帽，由 {@code RenderType.debugQuads} 缓冲；固定 7 段、色相 360°/7 均分
     * ⇒ 无论长短同时呈现七彩色）。
     * 取代早期「单片 ribbon 面片」画法——后者只沿水平横向展开、零厚度，贴顶面时视觉上是一枚扁平色带。
     * 调用前 {@code poseStack} 应已按 -相机 平移，坐标为世界绝对坐标。
     */
    public static void renderConnectionBeam(PoseStack poseStack, VertexConsumer c,
                                            double x0, double y0, double z0,
                                            double x1, double y1, double z1) {
        boxLineWidth = cn.ism.mekck.config.MekckConfig.getBoxLineWidth();
        Matrix4f m = poseStack.last().pose();
        float hueBase = rainbowHueBase();
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4) return;
        // §F39（用户口径：贴着机器顶面但不重叠）：传入的 y 是机器顶面（blockY+1.0），
        // 把中心线整体上提一个半高 ⇒ 光束底沿恰好落在顶面上，既不悬空也不插入方块。
        double half = BEAM_BASE_HALF_WIDTH * boxLineWidth;
        y0 += half;
        y1 += half;
        int seg = BEAM_SEGMENTS;                     // 固定 7 段，整条光束同时铺满七彩色
        double sx = dx / seg, sy = dy / seg, sz = dz / seg;
        double ax = x0, ay = y0, az = z0;
        for (int i = 0; i < seg; i++) {
            double bx = ax + sx, by = ay + sy, bz = az + sz;
            beamIsLast = (i == seg - 1);   // 端帽只画首尾
            beamSegment(c, m, ax, ay, az, bx, by, bz, hueBase, i);
            ax = bx; ay = by; az = bz;
        }
    }

    /** 长方体光束固定段数 = 同时显示的色段数（七彩虹）。 */
    private static final int BEAM_SEGMENTS = 7;

    /**
     * buff 光束的基础截面半宽（格）——**不复用包围盒线框的 {@link #BOX_BASE_HALF_WIDTH}**：
     * 线框量级（0.012）乘 boxLineWidth 后总宽仅 ~0.036 格，所谓「长方体光束」细成线，
     * 实机反馈（§F37 后用户截图）远看只剩空心线框轮廓、毫无立体梁感 ⇒
     * 给光束独立基础值 0.06（线框的 5 倍），默认倍率 1.5 下总宽 ~0.18 格（≈ 1/5.5 方块），
     * 仍随 box_line_width（0.5~3.0）可调。
     */
    private static final float BEAM_BASE_HALF_WIDTH = 0.06F;

    /** 长方体光束的一段：截面半宽/半高 = BEAM_BASE_HALF_WIDTH × boxLineWidth（正方形截面），4 侧面 + 首尾端帽。 */
    private static void beamSegment(VertexConsumer c, Matrix4f m,
                                    double x0, double y0, double z0,
                                    double x1, double y1, double z1,
                                    float hueBase, int segIdx) {
        float[] rgb = hsvToRgb((hueBase + segIdx * (360f / BEAM_SEGMENTS)) % 360f, 0.85f, 1.0f);
        // §F40（用户口径：让它半透明）：0.9 近乎实心 ⇒ 降为 0.5；debugQuads 无剔除双面可见，
        // 半透后能透视到梁内背面（略增体积感，属预期）。
        float r = rgb[0], g = rgb[1], b = rgb[2], a = 0.5F;
        float dx = (float) (x1 - x0), dy = (float) (y1 - y0), dz = (float) (z1 - z0);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6F) return;
        // 截面两轴：u = 水平横轴（d × 世界上向量，恒垂直于线且无竖直分量），v = 世界上向量 × u（与 u 正交）；
        // 近竖直退化时 u 回退 (1,0,0)。hw = 半宽，hv = 半高（与宽同调，乘同一配置倍率）。
        float ux = dy, uy = 0.0F, uz = -dx;
        float ul = (float) Math.sqrt(ux * ux + uz * uz);
        if (ul < 1.0e-4F) {
            ux = 1.0F; uy = 0.0F; uz = 0.0F;
            ul = 1.0F;
        }
        ux = ux / ul; uz = uz / ul;
        float hw = BEAM_BASE_HALF_WIDTH * boxLineWidth;
        float hv = hw;
        // 8 角点：底/顶 × 左/右 × 前/后（前 = 段起点端，后 = 段终点端）
        double blx = x0 - ux * hw, bly = y0 - hv, blz = z0 - uz * hw;
        double brx = x0 + ux * hw, bry = y0 - hv, brz = z0 + uz * hw;
        double tlx = x0 - ux * hw, tly = y0 + hv, tlz = z0 - uz * hw;
        double trx = x0 + ux * hw, tpy = y0 + hv, trz = z0 + uz * hw;
        double blx1 = x1 - ux * hw, bly1 = y1 - hv, blz1 = z1 - uz * hw;
        double brx1 = x1 + ux * hw, bry1 = y1 - hv, brz1 = z1 + uz * hw;
        double tlx1 = x1 - ux * hw, tly1 = y1 + hv, tlz1 = z1 - uz * hw;
        double trx1 = x1 + ux * hw, try1 = y1 + hv, trz1 = z1 + uz * hw;
        // 沿轴 4 纵面（顶面最亮、底面最暗 ⇒ 立体感）。§F39 补左右两面：
        // 旧实现此处画的是「起/末端面截面」而非左右纵面 ⇒ 长方体 6 面缺左右 2 面，
        // 且截面在段接缝处互相重叠藏在梁内不可见，整条光束侧面看是空的（用户实机反馈）。
        beamQuad(c, m, tlx, tly, tlz, trx, tpy, trz, trx1, try1, trz1, tlx1, tly1, tlz1, r, g, b, a, 1.0F);
        beamQuad(c, m, blx1, bly1, blz1, brx1, bry1, brz1, brx, bry, brz, blx, bly, blz, r, g, b, a, 0.45F);
        beamQuad(c, m, blx, bly, blz, tlx, tly, tlz, tlx1, tly1, tlz1, blx1, bly1, blz1, r, g, b, a, 0.75F);
        beamQuad(c, m, brx, bry, brz, trx, tpy, trz, trx1, try1, trz1, brx1, bry1, brz1, r, g, b, a, 0.75F);
        // 仅首尾两段补端帽，封住光束两端
        if (segIdx == 0) {
            beamQuad(c, m, blx, bly, blz, brx, bry, brz, trx, tpy, trz, tlx, tly, tlz, r, g, b, a, 0.6F);
        }
        if (beamIsLast) {
            beamQuad(c, m, brx1, bry1, brz1, blx1, bly1, blz1, tlx1, tly1, tlz1, trx1, try1, trz1, r, g, b, a, 0.6F);
        }
    }

    /** 当前段是否为末段（端帽只画首尾；由 renderConnectionBeam 在分段循环内写入）。 */
    private static boolean beamIsLast = true;

    /** 长方体光束的一个四边形面，按面亮度系数 shade 调 RGB（debugQuads 无剔除，双面可见，绕序不敏感）。 */
    private static void beamQuad(VertexConsumer c, Matrix4f m,
                                 double ax, double ay, double az,
                                 double bx, double by, double bz,
                                 double cx, double cy, double cz,
                                 double dxv, double dyv, double dzv,
                                 float r, float g, float b, float a, float shade) {
        float rr = r * shade, gg = g * shade, bb = b * shade;
        c.vertex(m, (float) ax, (float) ay, (float) az).color(rr, gg, bb, a).normal(0, 1, 0).endVertex();
        c.vertex(m, (float) bx, (float) by, (float) bz).color(rr, gg, bb, a).normal(0, 1, 0).endVertex();
        c.vertex(m, (float) cx, (float) cy, (float) cz).color(rr, gg, bb, a).normal(0, 1, 0).endVertex();
        c.vertex(m, (float) dxv, (float) dyv, (float) dzv).color(rr, gg, bb, a).normal(0, 1, 0).endVertex();
    }

    private static void edgeRainbow(VertexConsumer c, Matrix4f m,
                                    double x0, double y0, double z0,
                                    double x1, double y1, double z1,
                                    float hueBase, int edgeIdx) {
        // 原版细线：GL_LINES 两个顶点（未加粗）
        float[] rgb = hsvToRgb((hueBase + edgeIdx * 137.508f) % 360f, 0.85f, 1.0f);
        c.vertex(m, (float) x0, (float) y0, (float) z0).color(rgb[0], rgb[1], rgb[2], 0.55F).normal(0.0F, 0.0F, 1.0F).endVertex();
        c.vertex(m, (float) x1, (float) y1, (float) z1).color(rgb[0], rgb[1], rgb[2], 0.55F).normal(0.0F, 0.0F, 1.0F).endVertex();
    }

    /** 包围盒用的彩虹棱（回退，alpha 0.45，原版细线）。 */
    private static void edge(VertexConsumer c, Matrix4f m,
                             double x0, double y0, double z0,
                             double x1, double y1, double z1,
                             int idx) {
        float hueBase = rainbowHueBase();
        float[] rgb = hsvToRgb((hueBase + idx * 137.508f) % 360f, 0.85f, 1.0f);
        c.vertex(m, (float) x0, (float) y0, (float) z0).color(rgb[0], rgb[1], rgb[2], 0.45F).normal(0.0F, 0.0F, 1.0F).endVertex();
        c.vertex(m, (float) x1, (float) y1, (float) z1).color(rgb[0], rgb[1], rgb[2], 0.45F).normal(0.0F, 0.0F, 1.0F).endVertex();
    }

    /** 随游戏时间流动的彩虹色相（每 tick 前进 4°，约 4.5 秒一圈）。 */
    private static float rainbowHueBase() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        float tick = (mc.level != null ? mc.level.getGameTime() : 0) + mc.getFrameTime();
        return (tick * 4.0F) % 360.0F;
    }

    /** HSV → RGB（0..1）。 */
    private static float[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1.0f - Math.abs((h / 60.0f) % 2.0f - 1.0f));
        float m = v - c;
        float r;
        float g;
        float b;
        if (h < 60) {
            r = c;
            g = x;
            b = 0;
        } else if (h < 120) {
            r = x;
            g = c;
            b = 0;
        } else if (h < 180) {
            r = 0;
            g = c;
            b = x;
        } else if (h < 240) {
            r = 0;
            g = x;
            b = c;
        } else if (h < 300) {
            r = x;
            g = 0;
            b = c;
        } else {
            r = c;
            g = 0;
            b = x;
        }
        return new float[]{r + m, g + m, b + m};
    }
}
