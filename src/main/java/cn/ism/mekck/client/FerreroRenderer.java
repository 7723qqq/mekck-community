package cn.ism.mekck.client;

import cn.ism.mekck.entity.FerreroEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;

/**
 * 费列罗巧克力实体渲染器：以物品形态渲染（原版投掷物同款），
 * 整体缩放到 0.75 倍，接近冰块实体的视觉体积。
 */
public class FerreroRenderer extends ThrownItemRenderer<FerreroEntity> {

    /** 物品缩放比例。 */
    public static final float SCALE = 0.75F;

    public FerreroRenderer(EntityRendererProvider.Context context) {
        super(context, SCALE, true);
    }
}
