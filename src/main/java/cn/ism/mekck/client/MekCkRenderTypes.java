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

    public MekCkRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                            boolean hasCrumbling, boolean sortOnTranslate, Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, hasCrumbling, sortOnTranslate, setupState, clearState);
    }

    /**
     * 冰封外壳专用渲染类型 —— <b>4 张贴图 = 4 个 {@code static final} 实例</b>。
     *
     * <h3>为什么必须缓存（本轮修掉的无界堆增长）</h3>
     * 原实现每调用一次就 {@code new TextureStateShard} + {@code createCompositeState}
     * + {@code RenderType.create} 造出<b>一个全新的 {@link RenderType} 对象</b>
     * （{@code create} 只 new、<b>不入任何缓存</b>）。而
     * {@link #getIce} 走的是 {@code RenderLivingEvent.Post} 的<b>每帧</b>路径：
     * 一个被冰封的实体 60 fps ⇒ <b>每分钟 3600 个</b> {@code RenderType}
     * + {@code CompositeState} + 若干状态闭包，且永远不会被回收。
     *
     * <p>还有一个更隐蔽的后果：{@code MultiBufferSource.BufferSource.getBuffer(renderType)}
     * 以 {@code RenderType} <b>实例</b>作 map key ⇒ 每帧都命中不到已有条目，
     * 于是每帧新建一个 {@code ByteBufferBuilder} + {@code ByteBuffer}，
     * 而本路径从不 {@code endBatch(mekck_ice)}。</p>
     *
     * <p>同包的 {@link #objSolid} 早就被正确做成按贴图缓存的静态字段
     * （见 {@code BioreactorRenderer} 的用法），{@code getIce} 是唯一的例外。</p>
     *
     * @param index 0..3 的贴图档位；越界会夹到 0..3，<b>不会</b>返回 null
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
     * OBJ 外部网格专用渲染类型。
     *
     * <p><b>为什么用 block shader 而不是 entity shader</b>：本类型是为替换方块
     * （原 vanilla baked model）渲染而存在的，必须与 {@link RenderType#solid()} 行为一致。
     * 两者的光照模型不同：{@code rendertype_solid} 直接用 lightmap，不看法线；
     * {@code rendertype_entity_solid} 则按法线做漫反射。用 entity shader 会让原本由
     * {@code BakedQuad} 烘焙的逐面明暗（up 1.0 / 南北 0.8 / 东西 0.6 / down 0.5）消失，
     * 换成一套不同的方向光结果，视觉明显变暗。
     * 相应地，逐面明暗改由 {@code ObjMeshRenderer} 写入顶点色来还原。</p>
     *
     * <p>与 {@link #solid()} 的差别仅在于绑定传入的自定义纹理，而不是方块图集。</p>
     *
     * <p>不透明 + 背面剔除：OBJ 网格按逆时针绕序导出，符合 MC 默认正面判定。
     * 若某个模型需要显示内部结构，需另加一个关闭剔除的变体，而不是改这里。</p>
     */
    public static RenderType objSolid(ResourceLocation texture) {
        TextureStateShard textureState = new TextureStateShard(texture, false, false);
        CompositeState state = CompositeState.builder()
                .setShaderState(RenderType.RENDERTYPE_SOLID_SHADER)
                .setTextureState(textureState)
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .createCompositeState(true);
        return create("mekck_obj_solid", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true, state);
    }
}
