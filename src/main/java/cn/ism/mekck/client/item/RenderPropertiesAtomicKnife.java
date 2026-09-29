package cn.ism.mekck.client.item;

import cn.ism.mekck.client.render.item.gear.RenderAtomicKnife;
import mekanism.client.render.RenderPropertiesProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * 原子刀的客户端专属渲染属性（专用服务器安全隔离层）。
 * 仅应被 Dist.CLIENT 加载：ItemAtomicKnife.initializeClient 仅引用本类，
 * Forge RuntimeDistCleaner 在 DEDICATED_SERVER 上会移除对 @OnlyIn(CLIENT) 类的引用，
 * 避免服务器解析 RenderPropertiesProvider（内部引用客户端渲染类）而崩溃。
 */
@OnlyIn(Dist.CLIENT)
public final class RenderPropertiesAtomicKnife {

    private static RenderPropertiesAtomicKnife INSTANCE;

    private final RenderPropertiesProvider.MekRenderProperties properties;

    private RenderPropertiesAtomicKnife() {
        this.properties = new RenderPropertiesProvider.MekRenderProperties(RenderAtomicKnife.RENDERER);
    }

    public static RenderPropertiesAtomicKnife get() {
        if (INSTANCE == null) {
            INSTANCE = new RenderPropertiesAtomicKnife();
        }
        return INSTANCE;
    }

    public void accept(@NotNull Consumer<IClientItemExtensions> consumer) {
        consumer.accept(properties);
    }
}
