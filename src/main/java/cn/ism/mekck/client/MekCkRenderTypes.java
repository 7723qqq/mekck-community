package cn.ism.mekck.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * 冰封视觉专用渲染类型：仿 iceandfire IafRenderType.getIce——
 * beacon beam shader + 半透明 + 纹理（frosted_ice 系列），用于渲染实体冰封外壳。
 */
public abstract class MekCkRenderTypes extends RenderType {

    /**
     * 冰封外壳的 4 个贴图档位（越接近解除裂痕越多，与 iceandfire 一致）。
     *
     * <p><b>全部指向原版 {@code minecraft:textures/block/frosted_ice.png}</b>。
     * 此前这里写的是 {@code frosted_ice_0..3} 四张不同文件，而：</p>
     * <ul>
     *   <li>本仓 {@code assets/mekck/textures/**}（438 个文件）里<b>没有</b>任何
     *       {@code frosted_ice*}；</li>
     *   <li>原版 1.20.1 也只有 {@code minecraft:textures/block/frosted_ice.png} 一张，
     *       不存在 {@code _0.._3}（那是 iceandfire 1.20+ 自带的）。</li>
     * </ul>
     * 于是那一档分级从来就没有对应资源，净效果是冰封外壳贴图缺失（紫黑格）。
     * 4 档共用原版那张仍然保留了按 tick 选档的接口形状，将来真要提供分级贴图时
     * 只需替换这 4 个常量，调用方不用动。</p>
     */
    private static final ResourceLocation[] ICE_TEXTURES = {
            new ResourceLocation("minecraft", "textures/block/frosted_ice.png"),
            new ResourceLocation("minecraft", "textures/block/frosted_ice.png"),
            new ResourceLocation("minecraft", "textures/block/frosted_ice.png"),
            new ResourceLocation("minecraft", "textures/block/frosted_ice.png")
    };

    /** 按贴图档位缓存的 {@link RenderType}。首次使用时填充，之后恒定复用。 */
    private static final RenderType[] ICE_LEVELS = new RenderType[ICE_TEXTURES.length];

    /** 按贴图缓存的 OBJ 渲染类型，避免每帧重复创建 RenderType 造成内存泄露。 */
    private static final java.util.Map<ResourceLocation, RenderType> OBJ_SOLID_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<ResourceLocation, RenderType> OBJ_CUTOUT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public MekCkRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                            boolean hasCrumbling, boolean sortOnTranslate, Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, hasCrumbling, sortOnTranslate, setupState, clearState);
    }

    /**
     * 冰封外壳专用渲染类型 —— 4 张贴图 = 4 个 static final 实例。
     */
    public static RenderType getIce(int index) {
        int clamped = Math.floorMod(index, ICE_LEVELS.length);
        RenderType cached = ICE_LEVELS[clamped];
        if (cached == null) {
            cached = create("mekck_ice_" + clamped, DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS, 256, false, true,
                    iceCompositeState(ICE_TEXTURES[clamped]));
            ICE_LEVELS[clamped] = cached;
        }
        return cached;
    }

    private static CompositeState iceCompositeState(ResourceLocation texture) {
        return CompositeState.builder()
                .setShaderState(RenderType.RENDERTYPE_BEACON_BEAM_SHADER)
                .setTextureState(new TextureStateShard(texture, false, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .createCompositeState(true);
    }

    /**
     * 规范化贴图路径：无论调用方传入 "block/xxx" 还是 "textures/block/xxx.png"，
     * 统一规范化为完整资源路径（以 "textures/" 开头、".png" 结尾），避免 TextureManager 丢贴图变成紫黑方块。
     */
    public static ResourceLocation normalizeTexture(ResourceLocation loc) {
        if (loc == null) {
            return null;
        }
        String path = loc.getPath();
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        return new ResourceLocation(loc.getNamespace(), path);
    }

    /**
     * OBJ 外部网格专用渲染类型（不透明 + 背面剔除）。
     *
     * <p><b>关键设计与修复</b>：
     * <ul>
     *   <li>图元类型必须为 {@link VertexFormat.Mode#TRIANGLES}：
     *       {@link cn.ism.mekck.client.mesh.ObjMesh} 解析面数据输出的是 3 顶点三角形流。
     *       若用 QUADS 则 Blaze3D 会按 4 顶点划分 primitive，导致所有面错位扯烂。</li>
     *   <li>Shader 使用 {@code RENDERTYPE_ENTITY_SOLID_SHADER}：
     *       与 {@link DefaultVertexFormat#NEW_ENTITY} 的顶点属性表（Position, Color, UV0, UV1, UV2, Normal）
     *       严格一致（原版 rendertype_solid 缺少 UV1 且 stride 仅为 32 字节，与 NEW_ENTITY 的 36 字节 stride
     *       严重错位，导致逐顶点偏移失真）。</li>
     *   <li>开启背面剔除 {@code CULL}。</li>
     * </ul>
     * </p>
     */
    public static RenderType objSolid(ResourceLocation texture) {
        ResourceLocation normalized = normalizeTexture(texture);
        return OBJ_SOLID_CACHE.computeIfAbsent(normalized, loc -> {
            TextureStateShard textureState = new TextureStateShard(loc, false, false);
            CompositeState state = CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_SOLID_SHADER)
                    .setTextureState(textureState)
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true);
            return create("mekck_obj_solid", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 256, false, true, state);
        });
    }

    /**
     * OBJ 外部网格专用渲染类型（Cutout 镂空 + 双面渲染不剔除）。
     *
     * <p>适用于包含观察窗内部、空心管道、反应釜液柱等需要双面可见与 alpha 镂空的复杂多方块结构。</p>
     */
    public static RenderType objCutoutNoCull(ResourceLocation texture) {
        ResourceLocation normalized = normalizeTexture(texture);
        return OBJ_CUTOUT_CACHE.computeIfAbsent(normalized, loc -> {
            TextureStateShard textureState = new TextureStateShard(loc, false, false);
            CompositeState state = CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                    .setTextureState(textureState)
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true);
            return create("mekck_obj_cutout_no_cull", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 256, false, true, state);
        });
    }
}
