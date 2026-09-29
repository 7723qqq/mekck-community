package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.machine.grill.GrillFactoryTile;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;

/**
 * 烧烤工厂容器（Mek 体系版）—— 阶段 3 Task 3。
 *
 * <h3>为什么整个类只剩一个构造器</h3>
 * 与切菜 / 研磨 / 种植切配同款：{@code MekanismTileContainer.addSlots()} 已把全部槽位装配做完
 * （玩家背包 / 快捷栏 / 副手 → 升级槽与升级输出槽 → 遍历
 * {@code tile.getInventorySlots(null)} 逐个 {@code createContainerSlot()}）。
 * 槽位数量与坐标全由 tile 侧的 {@code getInitialInventory} 决定
 * （输入/输出方阵 + 3 个调味料槽都由那里排出），
 * <b>所以本类一行槽位代码都不该有</b>。
 *
 * <h3>被删掉的那一大截下标逻辑</h3>
 * 旧实现靠 {@code speedUpgradeSlot = 2 * inputSlots} 与
 * {@code seasoningStart = powerSlot + 1} 这类<b>手写下标</b>拼槽位，
 * 并用 {@code 2 * inputSlots + (hasStackUpgrade ? 3 : 2)} 这类三元式推偏移——
 * 调味料槽插在 {@code 2N} 之后、能源槽之前，存储区又在调味料之后，
 * 任何一处顺序调整都要重算这四行。换成 {@code IInventorySlot} 对象引用之后，
 * 下标本身消失了，这些补偿逻辑一并作废：想遍历输入槽就 {@code tile.getInputSlots()}，
 * 不会因为排布不连续而漏算。
 *
 * <h3>三个构造器变一个</h3>
 * 旧类有 {@code (int, Inventory, FriendlyByteBuf)} / {@code (int, Inventory, BE)} /
 * {@code (int, Inventory, BE, ContainerData)} 三个入口，以及一整页
 * {@code ContainerData} 索引常量（{@code DATA_PROGRESS}、{@code DATA_TEMPERATURE}、
 * {@code DATA_AUTO_DISTRIBUTE} 等 14 项）。现在：
 * <ul>
 *   <li>没有 {@code FriendlyByteBuf} 入口——Mek 的容器工厂是
 *       {@code (int, Inventory, TILE)}，tile 由 {@code NetworkHooks.openScreen} 经
 *       {@code TileEntityMekanism.openGui} 直接传过来；</li>
 *   <li>没有 {@code ContainerData}——进度 / 能量 / 红石 / 调味料启用位图不再走 14 个同步整数，
 *       客户端直接读 tile 的网络同步状态。</li>
 * </ul>
 */
public final class GrillFactoryMenu extends MekanismTileContainer<GrillFactoryTile> {

    public GrillFactoryMenu(int containerId, Inventory inventory, GrillFactoryTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    private static ContainerTypeRegistryObject<GrillFactoryMenu> resolveContainer(GrillFactoryTile tile) {
        if (tile == null) {
            // 走到这里说明方块的 BlockType 描述没绑对 tile：显式报错好过把 null 传给父类。
            throw new IllegalStateException(
                    "烧烤工厂容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<GrillFactoryMenu> container = UniversalCuttingMachine.GRILL_FACTORY_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("烧烤工厂容器尚未注册（GRILL_FACTORY_CONTAINER == null）");
        }
        return container;
    }

    // ── 给 GUI 读的转发 ──────────────────────────────────────────────────

    /**
     * 进度条：0.0~1.0。
     *
     * <p>旧实现是 {@code progress * 24 / max}，Mek 的 {@code ProgressType.SMALL_RIGHT}
     * 内部按 0~1 的比例画，所以这里直接给比例。分母走
     * {@code tile.getTicksPerWorkCycle()} 而不是写死 200：装速度卡后批次变短，
     * 进度条必须跟着变短才有正确的「越快越满」手感。</p>
     */
    public double getProgressRatio() {
        GrillFactoryTile tile = getTileEntity();
        if (tile == null) {
            return 0;
        }
        return tile.getWorkProgress() / (double) tile.getTicksPerWorkCycle();
    }

    public boolean isBusy() {
        GrillFactoryTile tile = getTileEntity();
        return tile != null && tile.isBusy();
    }

    /** 存量能量（FE）。 */
    public double getEnergy() {
        GrillFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getEnergy().doubleValue();
    }

    /** 容量上限（FE）。 */
    public double getMaxEnergy() {
        GrillFactoryTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getMaxEnergy().doubleValue();
    }

    /**
     * 第 {@code index} 个调味料槽是否启用自动调味。
     *
     * <p>走 tile 而非同步整数：执行器的位标志写进 {@code saveAdditional}，
     * 而 {@code saveAdditional} 会被 {@code getUpdateTag} 复用，
     * 方块更新包已经把这一位带到客户端了。</p>
     */
    public boolean isSeasoningEnabled(int index) {
        GrillFactoryTile tile = getTileEntity();
        return tile != null && tile.isSeasoningEnabled(index);
    }
}
