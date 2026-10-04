package cn.ism.mekck.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

/**
 * GuideME（AE2 的前置文档模组）可选联动的安全门面。
 * <p>本类不引用任何 GuideME 类，全部交互经 {@link GuideMECompatImpl}（仅客户端、仅在
 * {@code ModList.isLoaded("guideme")} 为 true 时通过 Class.forName 加载），
 * 未装 GuideME 时主模组完全不受影响。</p>
 */
public final class GuideMECompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "guideme";
    private static final String IMPL_CLASS = "cn.ism.mekck.client.GuideMECompatImpl";

    private static Boolean loaded;
    private static Class<?> impl;

    private GuideMECompat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(MOD_ID);
        }
        return loaded;
    }

    /** 客户端注册 mekck 指南书（在 FMLClientSetupEvent 中调用）。 */
    public static void registerGuidebook() {
        if (!isLoaded()) return;
        try {
            impl = Class.forName(IMPL_CLASS);
            impl.getMethod("registerGuidebook").invoke(null);
        } catch (Throwable t) {
            LOGGER.warn("[mekck] GuideME register failed: {}", t.toString());
        }
    }

    /** 客户端打开 mekck 指南（专用服务器不会调用）。 */
    public static void openGuide() {
        openGuide(ResourceLocation.fromNamespaceAndPath("mekck", "guide"));
    }

    /**
     * 客户端按书 id 打开指南。
     * <p>注意：反射的<b>只是我们自己的客户端类</b>（类名/方法名不会被 reobf 改名），
     * 绝不在这里反射 {@code Minecraft} 等原版类 —— 那些在生产环境是 SRG 名，反射字符串找不到方法。</p>
     */
    public static void openGuide(ResourceLocation guideId) {
        if (!isLoaded()) return;
        try {
            impl = Class.forName(IMPL_CLASS);
            impl.getMethod("openGuide", ResourceLocation.class).invoke(null, guideId);
        } catch (Throwable t) {
            LOGGER.error("[mekck] GuideME open failed for {}", guideId, t);
        }
    }

    /** 客户端 tick：检测按键并打开指南（已改由 GuideME 自带热键处理，保留空实现兼容）。 */
    public static void clientTick() {
        // GuideME 自带全局热键（默认 G）处理长按打开；本模组不再注册自己的按键。
    }
}
