package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.machine.grinding.GrindingFactoryTile;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;

/**
 * 研磨工厂容器（Mek 体系版）—— 阶段 3 Task 1。
 *
 * <h3>为什么整个类只剩一个构造器</h3>
 * {@code MekanismTileContainer.addSlots()} 已经把全部槽位装配做完：
 * 玩家背包 / 快捷栏 / 副手 → 升级槽与升级输出槽 → 遍历
 * {@code tile.getInventorySlots(null)} 逐个 {@code createContainerSlot()}。
 * 槽位数量与坐标全由 tile 侧的 {@code getInitialInventory} 决定，
 * 所以本类一行槽位代码都不该有。
 *
 * <h3>被删掉的那一大截下标逻辑</h3>
 * 旧实现靠 {@code speedUpgradeSlot = 2 * inputSlots} 这类<b>手写下标</b>拼槽位，
 * 并在 {@link #quickMoveStack} 里重复同样的算术（还与真正的处理器槽数各算一遍）。
 * 换成 {@code IInventorySlot} 对象引用之后，下标本身消失了：
 * 想遍历输入槽就 {@code tile.getInputSlots()}，不会因为排布不连续而漏算。
 *
 * <h3>三个构造器变一个</h3>
 * 旧类有 {@code (int, Inventory, FriendlyByteBuf)} / {@code (int, Inventory, BE)} /
 * {@code (int, Inventory, BE, ContainerData)} 三个入口，以及一整页
 * {@code ContainerData} 索引常量（11 项）。现在没有 {@code FriendlyByteBuf} 入口——
 * Mek 的容器工厂是 {@code (int, Inventory, TILE)}；
 * 也没有 {@code ContainerData}——进度/能量/红石/升级数不再走同步整数，
 * 客户端直接读 tile 的网络同步状态。
 */
public final class GrindingFactoryMenu extends MekanismTileContainer<GrindingFactoryTile> {

    /**
     * 构造器签名必须与 Mek 的容器工厂一致：{@code (int, Inventory, TILE)}。
     *
     * <p>父类<b>要求</b>容器类型非空（{@code MekanismContainer} 用它做
     * {@code IContainerTracker} 的身份标识），所以这里从注册表取回。
     * 12 个等级共用一个容器类型 {@code mekck:grinding_factory}——与旧实现一致，
     * 也让 {@link #resolveContainer} 不必按等级分叉。</p>
     */
    public GrindingFactoryMenu(int containerId, Inventory inventory, GrindingFactoryTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    private static ContainerTypeRegistryObject<GrindingFactoryMenu> resolveContainer(GrindingFactoryTile tile) {
        if (tile == null) {
            // 走到这里说明方块的 BlockType 描述没绑对 tile：显式报错好过把 null 传给父类。
            throw new IllegalStateException(
                    "研磨工厂容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<GrindingFactoryMenu> container = UniversalCuttingMachine.GRINDING_FACTORY_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("研磨工厂容器尚未注册（GRINDING_FACTORY_CONTAINER == null）");
        }
        return container;
    }

    // ── 给 GUI 读的转发 ──────────────────────────────────────────────────

    /**
     * 进度条：0.0~1.0。
     *
     * <p>旧实现是 {@code progress * 24 / max}（把 200 tick 的进度映射到 24 格刻度），
     * Mek 的 {@code ProgressType.SMALL_RIGHT} 内部按 0~1 的比例画，所以这里直接给比例。
     * 分母走 {@code tile.getTicksPerWorkCycle()} 而不是写死 200：装速度卡后批次变短，
     * 进度条必须跟着变短才有正确的「越快越满」手感。</p>
     */
    public double getProgressRatio() {
        GrindingFactoryTile tile = getTileEntity();
        if (tile == null) {
            return 0;
        }
        return tile.getWorkProgress() / (double) tile.getTicksPerWorkCycle();
    }

    public boolean isBusy() {
        GrindingFactoryTile tile = getTileEntity();
        return tile != null && tile.isBusy();
    }

    /** 存量能量（FE）。{@code FloatingLong} 继承 {@code Number}，用 {@code doubleValue()} 取值。 */
    public double getEnergy() {
        GrindingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getEnergy().doubleValue();
    }

    /** 容量上限（FE）。 */
    public double getMaxEnergy() {
        GrindingFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getMaxEnergy().doubleValue();
    }
}
