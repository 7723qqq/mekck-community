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

    public MekCkRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                            boolean hasCrumbling, boolean sortOnTranslate, Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, hasCrumbling, sortOnTranslate, setupState, clearState);
    }

    public static RenderType getIce(ResourceLocation texture) {
        TextureStateShard textureState = new TextureStateShard(texture, false, false);
        CompositeState state = CompositeState.builder()
                .setShaderState(RenderType.RENDERTYPE_BEACON_BEAM_SHADER)
                .setTextureState(textureState)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .createCompositeState(true);
        return create("mekck_ice", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true, state);
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
