package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile;
import mekanism.api.math.FloatingLong;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import cn.ism.mekck.registry.MekCkFactories;

/**
 * 切菜机容器（Mek 体系版）—— 第四轮从自研 {@code AbstractContainerMenu} 换成
 * {@link MekanismTileContainer}。
 *
 * <h3>换掉之后作废的东西</h3>
 * <ul>
 *   <li><b>手写菜单下标</b>：旧实现靠 {@code MACHINE_SLOT_COUNT = 5} 与
 *       {@code powerSlotIndex = slots.size()} 拼槽位，并专门为「能源槽落进玩家槽分支
 *       导致 shift-click 自我合并、数量翻倍」写了一段补偿
 *       （旧注释：拿 handler 的 {@code SLOT_POWER=5} 当菜单下标会指向玩家背包第 0 格）。
 *       换成 {@code IInventorySlot} 对象引用后，下标本身消失了，补偿逻辑一并作废。</li>
 *   <li><b>三个构造器变一个</b>：旧类有 {@code (int, Inventory, FriendlyByteBuf)} /
 *       {@code (int, Inventory, BE)} / {@code (int, Inventory, BE, ContainerData)} 三个入口，
 *       以及一整页 {@code ContainerData} 索引常量。现在没有 {@code FriendlyByteBuf} 入口
 *       （Mek 的容器工厂是 {@code (int, Inventory, TILE)}），也没有 {@code ContainerData}
 *       —— 进度 / 能量 / 升级数不再走同步整数，客户端直接读 tile 的网络同步状态。</li>
 *   <li><b>自研升级槽</b>（{@code UpgradeSlot} + 隐藏坐标 + 升级页切换）：由
 *       {@code MekanismTileContainer.getUpgradeSlot()} 取代。</li>
 *   <li><b>自研侧配</b>：由 Mek 的 {@code GuiSideConfigurationTab} 取代。</li>
 * </ul>
 */
public final class UniversalCuttingMachineMenu extends MekanismTileContainer<UniversalCuttingMachineTile> {

    public UniversalCuttingMachineMenu(int containerId, Inventory inventory,
                                       UniversalCuttingMachineTile tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    private static ContainerTypeRegistryObject<UniversalCuttingMachineMenu> resolveContainer(
            UniversalCuttingMachineTile tile) {
        if (tile == null) {
            // 走到这里说明方块的 BlockType 描述没绑对 tile：显式报错好过把 null 传给父类。
            throw new IllegalStateException(
                    "切菜机容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<UniversalCuttingMachineMenu> container =
                MekCkFactories.MACHINE_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("切菜机容器尚未注册（MACHINE_CONTAINER == null）");
        }
        return container;
    }

    // ── 给 GUI 读的转发 ──────────────────────────────────────────────────

    /** 进度条分子：0..24（屏幕侧除以 24.0，与旧实现同口径）。 */
    public int getProgress() {
        UniversalCuttingMachineTile tile = getTileEntity();
        return tile == null ? 0 : tile.getProgress();
    }

    /** 存量能量（FE）。{@code FloatingLong} 继承 {@code Number}，用 {@code doubleValue()} 取值。 */
    public double getEnergy() {
        UniversalCuttingMachineTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyStored().doubleValue();
    }

    /** 容量上限（FE）。 */
    public double getMaxEnergy() {
        return UniversalCuttingMachineTile.ENERGY_CAPACITY;
    }

    /** 速度卡张数。 */
    public int getSpeedUpgradeCount() {
        UniversalCuttingMachineTile tile = getTileEntity();
        return tile == null ? 0 : tile.getSpeedUpgradeCount();
    }

    /** 能量（节电）卡张数。 */
    public int getEnergyUpgradeCount() {
        UniversalCuttingMachineTile tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyUpgradeCount();
    }

    /** 是否装了创造卡（满电 + 秒切 + 零耗电）。 */
    public boolean hasCreativeUpgrade() {
        UniversalCuttingMachineTile tile = getTileEntity();
        return tile != null && tile.hasCreativeUpgrade();
    }

    // ── 订单（AE2 / ME 终端下单面板读）──────────────────────────────────

    public ResourceLocation getOrderRecipeId() {
        UniversalCuttingMachineTile tile = getTileEntity();
        return tile == null ? null : tile.getOrderRecipeId();
    }

    public int getOrderQuantity() {
        UniversalCuttingMachineTile tile = getTileEntity();
        return tile == null ? 0 : tile.getOrderQuantity();
    }

    public int getOrderCompleted() {
        UniversalCuttingMachineTile tile = getTileEntity();
        return tile == null ? 0 : tile.getOrderCompleted();
    }

    public boolean isMeOrderEnabled() {
        UniversalCuttingMachineTile tile = getTileEntity();
        return tile != null && tile.isMeOrderEnabled();
    }
}
