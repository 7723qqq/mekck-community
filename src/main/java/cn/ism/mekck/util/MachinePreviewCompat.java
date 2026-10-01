package cn.ism.mekck.util;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import cn.ism.mekck.registry.MekCkFactories;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

/**
 * 与独立模组 **机器放置预览**（machinepreview）的可选联动。
 *
 * <p>背景：本模组自带的放置预览功能**保留不移除**（未装 machinepreview 时照常工作）。
 * 一旦装了 machinepreview，则由它接管渲染，本模组的预览**自动关闭**，避免两层预览互相叠加。</p>
 *
 * <p>联动方式：把本模组的多方块机器形状登记给 machinepreview（它不认识任何具体模组，
 * 靠登记表得知「这台机器占几格」）。未装 machinepreview 时直接短路，不做任何事。</p>
 *
 * <p>与 GuideMECompat 同一套路：不引用对方任何类，全部经反射，未安装即静默跳过。</p>
 */
public final class MachinePreviewCompat {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "machinepreview";
    private static final String API_CLASS = "cn.ism.machinepreview.api.MachinePreviewApi";

    private static Boolean loaded;

    private MachinePreviewCompat() {
    }

    /** machinepreview 是否已安装。 */
    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(MOD_ID);
        }
        return loaded;
    }

    /**
     * 把本模组的机器登记给 machinepreview。
     *
     * <p>登记内容：mekck 命名空间下全部方块按机器处理（单方块预览）；
     * 另有三台多方块机器的尺寸 —— 种植切配站 1x2x1、种植切配工厂 1x2x1、生物反应堆 2x3x2，
     * 与 {@link cn.ism.mekck.util.MekCkMultiblock} 里的形状常量保持一致。</p>
     *
     * <p>调用时机：{@code FMLCommonSetupEvent}（所有方块注册完成之后）。§F33：早先误在本模组构造器里调用，
     * 那时 {@code DeferredRegister.register(bus)} 只是挂上注册事件监听、注册尚未 fire，
     * {@code *_BLOCK.get()} 会抛 "Registry Object not present" 使 setup 从该处中断、后续多方块尺寸全漏登记。</p>
     */
    public static void setup() {
        if (!isLoaded()) {
            return;
        }
        try {
            Class<?> api = Class.forName(API_CLASS);
            Method namespace = api.getMethod("registerMachineNamespace", String.class);
            Method shape = api.getMethod("registerMultiblockSize", Block.class, int.class, int.class, int.class);

            namespace.invoke(null, cn.ism.mekck.UniversalCuttingMachine.MOD_ID);

            // 种植切配站：1x2x1
            shape.invoke(null, cn.ism.mekck.registry.MekCkFactories.PLANTING_CUTTING_STATION_BLOCK.get(), 1, 2, 1);
            // 种植切配工厂：每个等级一台（按等级存放在 Map 里），形状同为 1x2x1
            int factoryCount = 0;
            for (var entry : cn.ism.mekck.registry.MekCkFactories.PLANTING_CUTTING_FACTORY_BLOCKS.entrySet()) {
                shape.invoke(null, entry.getValue().get(), 1, 2, 1);
                factoryCount++;
            }
            // 生物反应堆：2x3x2（主方块位于底层西南角）
            shape.invoke(null, cn.ism.mekck.registry.MekCkStandaloneMachines.BIOREACTOR_BLOCK.get(), 2, 3, 2);

            LOGGER.info("[mekck] registered multiblock shapes to machinepreview: 1 station + {} factories + 1 bioreactor; mekck's own preview is now disabled", factoryCount);
        } catch (Throwable t) {
            LOGGER.warn("[mekck] machinepreview registration failed: {}", t.toString());
        }
    }
}
