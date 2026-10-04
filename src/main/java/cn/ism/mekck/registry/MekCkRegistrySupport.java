package cn.ism.mekck.registry;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.blockentity.GrillBlockEntity;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.RegistryObject;
import static cn.ism.mekck.UniversalCuttingMachine.MOD_ID;

/**
 * 注册期辅助：按注册名取回他人注册项的视图、以及延迟取回 tile 类型（破 BlockType↔tile 的循环依赖）。
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）。成员文本与拆分前
 * 逐字一致。</p>
 *
 * <p><b>本类不持有任何注册条目，因此无需被触碰。</b>{@link MekCkRegistries#registerAll}
 * 的触碰名单是按「该注册类的代码里是否真的出现 {@code DeferredRegister} / {@code .register(}」
 * 自动判定的（见 {@code TestRegistryInitContract}），本类只有取句柄的方法，判据下天然不在名单里。
 * 这里曾经有一个空的 {@code init()} 与「必须早于注册事件被触碰」的注释，但那与代码相反 ——
 * 本类没有静态初始化器往注册表加东西，留着只会让后来者为一个不存在的注册项去 registerAll
 * 里登记触碰。删掉空方法与那句契约。</p>
 */
public final class MekCkRegistrySupport {

    private MekCkRegistrySupport() {
    }

    static <T, U extends T> RegistryObject<U> registryView(String id, net.minecraftforge.registries.IForgeRegistry<T> registry) {
        return RegistryObject.create(new ResourceLocation(MOD_ID, id), registry);
    }

    /**
     * 取回已注册的烧烤架 tile 类型 —— 给 {@code BlockTypeTile} 的延迟 Supplier 用。
     *
     * <p>必须延迟：{@code TILE_ENTITIES.register(block, ...)} 要求先有方块，而方块的
     * {@code BlockType} 构造时就要 tile 的 Supplier，形成先后依赖。理由同
     * {@link #findFactoryTile}。</p>
     */

    static mekanism.common.registration.impl.TileEntityTypeRegistryObject<GrillBlockEntity> findGrillTile() {
        if (MekCkFactories.GRILL_TILE == null) {
            throw new IllegalStateException("电力烧烤架 tile 尚未注册（BlockTypeTile 的 Supplier 被过早求值）");
        }
        return MekCkFactories.GRILL_TILE;
    }

    /**
     * 按等级取回某家族已注册的 tile 类型 —— 给 {@code BlockTypeTile} 的延迟 Supplier 用。
     *
     * <h3>为什么必须延迟</h3>
     * Mek 的 {@code TILES_REG.register(blockHandle, …)} 要求<b>先有方块</b>，
     * 而方块的 {@code BlockType} 构造时就已经要 tile 的 {@code Supplier} —— 先后依赖成环，
     * 只能靠延迟 Supplier 打破。两个 Supplier 都只在 Mek 真正求值的时刻
     * （放置 / 开 GUI）才被调用，那时注册早已完成。
     *
     * <h3>为什么值得抽成一个方法</h3>
     * 原先 5 个家族各有一份手抄的 {@code findXxxFactoryTile}，正文<b>逐字相同</b>、
     * 只有报错里的中文家族名不同。手抄 5 遍的错误信息本身就是隐患：某天把家族名拼错，
     * 排查的人会被指向错误的模块，而真正出错的是另一处。现在家族名由调用点传入。
     *
     * <p>这里的 {@code null} 检查不是防御性冗余，而是<b>唯一</b>能把「Supplier 被过早求值」
     * 这类注册顺序错误变成可读异常的地方 —— 静默返回 null 会让 Mek 在远端才 NPE，
     * 堆栈指向完全无关的类。</p>
     *
     * @param tiles  该家族的 {@code <tier, TileEntityTypeRegistryObject>} 表
     * @param tier   正在注册的档位
     * @param family 中文家族名，仅用于报错信息
     */

    static <T extends net.minecraft.world.level.block.entity.BlockEntity>
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<T> findFactoryTile(
                    java.util.Map<CuttingMachineFactoryTier,
                            mekanism.common.registration.impl.TileEntityTypeRegistryObject<T>> tiles,
                    CuttingMachineFactoryTier tier,
                    String family) {
        mekanism.common.registration.impl.TileEntityTypeRegistryObject<T> found = tiles.get(tier);
        if (found == null) {
            throw new IllegalStateException(family + " tile 尚未注册：tier=" + tier
                    + "（BlockTypeTile 的 Supplier 被过早求值）");
        }
        return found;
    }

    /**
     * 取回已注册的切菜机 tile 类型 —— 给 {@code BlockTypeTile} 的延迟 Supplier 用。
     *
     * <p>必须延迟：{@code TILES_REG.register(block, …)} 要求先有方块，而方块的
     * {@code BlockType} 构造时就要 tile 的 Supplier，形成先后依赖。理由同
     * {@link #findGrillTile}。</p>
     */

    static mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile> findMachineTile() {
        if (MekCkFactories.MACHINE_TILE == null) {
            throw new IllegalStateException("切菜机 tile 尚未注册（BlockTypeTile 的 Supplier 被过早求值）");
        }
        return MekCkFactories.MACHINE_TILE;
    }

}
