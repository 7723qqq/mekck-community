package cn.ism.mekck.factory;

import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;

/**
 * MekCK 工厂容器（Mek 体系版）。
 *
 * <h3>为什么几乎不用写代码</h3>
 * Mek 的 {@code MekanismTileContainer.addSlots()} 已经自动完成全部槽位装配
 * （反编译实测）：
 * <ol>
 *   <li>玩家背包 / 快捷栏 / 副手（{@code MekanismContainer.addSlots()}）；</li>
 *   <li>升级槽与升级输出槽 —— 当 {@code tile.supportsUpgrades()} 为真时，
 *       从 {@code tile.getComponent().getUpgradeSlot()} 各建一个
 *       {@code VirtualInventoryContainerSlot} 挂上；</li>
 *   <li>tile 自身的全部槽位 —— 遍历 {@code tile.getInventorySlots(null)}，
 *       逐个 {@code slot.createContainerSlot()} 后 {@code addSlot}。</li>
 * </ol>
 * 因此本类只需把容器类型与 tile 交给父类；槽位数量由
 * {@link TileEntityMekCkFactory#getInitialInventory} 决定的 tile 侧定义。
 *
 * <h3>升级支持</h3>
 * {@code supportsUpgrades()} 由 {@code TileEntityMekanism} 依据方块属性
 * {@code AttributeUpgradeable} 判定。本类对应等级若声明了该属性，
 * 升级槽会自动出现在 GUI 中——无需在此手写。
 */
public class MekCkFactoryMenu extends MekanismTileContainer<MekCkFactoryTile> {

    /**
     * 构造器签名必须与 Mek 的容器工厂一致：{@code (int, Inventory, TILE)}。
     *
     * <p>反编译实测：{@code ContainerTypeDeferredRegister.register(String, Class)} 内部生成的
     * 工厂 lambda 签名为 {@code (int, Inventory, TileEntityMekanism)}，用 {@code invokedynamic}
     * 把自身的 {@code ContainerTypeRegistryObject} 自引用闭包进去 —— 因此子类 Menu
     * <b>不需要</b>接收容器类型参数。</p>
     *
     * <p>但父类构造器<b>要求</b>容器类型非空，做法与 Extras 一致
     * （{@code ExtraAdvancedFactoryContainer} 在构造器里传自己包的静态字段）：
     * 这里从注册表按 tile 的等级取回对应的容器对象。</p>
     */
    public MekCkFactoryMenu(int containerId, Inventory inv, MekCkFactoryTile tile) {
        super(resolveContainer(tile), containerId, inv, tile);
        // 诊断：容器侧最终挂了多少槽位。与 tile 侧 getInitialInventory 的日志对照，
        // 可定位槽位是在 tile 侧没建、还是在容器侧没被装配。
        org.slf4j.LoggerFactory.getLogger("mekck/factory").info(
                "[MekCkFactory] Menu 构造完成: containerId={} 远程={} slots={} 玩家背包起={}",
                containerId, tile.getLevel() != null && tile.getLevel().isClientSide(),
                this.slots.size(), this.slots.isEmpty() ? -1 : this.slots.get(0).index);
    }

    /**
     * 解析容器类型：tile 的家族/等级在构造期字段可能为 null（见 tile 侧注释），
     * 故这里做兜底并显式报错，而不是把 null 传给父类。
     */
    private static ContainerTypeRegistryObject<MekCkFactoryMenu> resolveContainer(MekCkFactoryTile tile) {
        MekCkFactoryType type = tile.getFactoryType();
        MekCkFactoryTier tier = tile.getTier();
        if (type == null || tier == null) {
            throw new IllegalStateException(
                    "MekCkFactoryMenu 无法解析容器：type=" + type + " tier=" + tier
                            + "（tile 未正确绑定方块？block=" + tile.getBlockType() + "）");
        }
        ContainerTypeRegistryObject<MekCkFactoryMenu> c = MekCkFactoryRegistration.getContainer(type, tier);
        if (c == null) {
            throw new IllegalStateException("MekCkFactoryMenu 容器未注册：type=" + type + " tier=" + tier);
        }
        return c;
    }
}
