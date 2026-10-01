package cn.ism.mekck.menu;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.GrillBlockEntity;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import cn.ism.mekck.registry.MekCkFactories;

/**
 * 电力烧烤架容器（Mek 体系版）。
 *
 * <h3>为什么整个类只剩一个构造器 + 几个转发</h3>
 * {@code MekanismTileContainer.addSlots()} 已把全部槽位装配做完
 * （玩家背包 / 快捷栏 / 副手 → 升级槽与升级输出槽 → 遍历
 * {@code tile.getInventorySlots(null)} 逐个 {@code createContainerSlot()}）。
 * 槽位数量与坐标全由 tile 侧的 {@code getInitialInventory} 决定
 * （输入 / 输出 / 创造升级 / 能源槽都由那里排出），<b>所以本类一行槽位代码都不该有</b>。
 *
 * <h3>被删掉的那一大截</h3>
 * <ul>
 *   <li><b>手写菜单下标</b>：旧实现靠 {@code MACHINE_SLOT_COUNT = 5} 与
 *       {@code powerSlotIndex = slots.size()} 拼槽位，还专门为「能源槽落进玩家槽分支
 *       导致 shift-click 自我合并、数量翻倍」写了一段补偿。换成
 *       {@code IInventorySlot} 对象引用之后，下标本身消失了，这些补偿逻辑一并作废。</li>
 *   <li><b>三个构造器变一个</b>：旧类有 {@code (int, Inventory, FriendlyByteBuf)} /
 *       {@code (int, Inventory, BE)} / {@code (int, Inventory, BE, ContainerData)} 三个入口，
 *       以及一整页 {@code ContainerData} 索引常量。现在没有 {@code FriendlyByteBuf} 入口
 *       （Mek 的容器工厂是 {@code (int, Inventory, TILE)}，tile 由
 *       {@code NetworkHooks.openScreen} 经 {@code TileEntityMekanism.openGui} 直接传过来），
 *       也没有 {@code ContainerData}（进度 / 温度不再走同步整数，客户端直接读 tile 的
 *       网络同步状态，见 {@code GrillBlockEntity.addContainerTrackers}）。</li>
 *   <li><b>自研升级槽</b>（{@code UpgradeSlot} + {@code IVirtualSlot} + 隐藏坐标）：
 *       由 {@code MekanismTileContainer.getUpgradeSlot()/getUpgradeOutputSlot()} 取代，
 *       那两个虚拟槽由 Mek 自己按升级 tab 的开合摆放。</li>
 *   <li><b>自研侧配</b>（{@code ISideConfigurableMenu}）：由 Mek 的
 *       {@code GuiSideConfigurationTab} + {@code TileComponentConfig} 取代。</li>
 * </ul>
 */
public final class GrillMenu extends MekanismTileContainer<GrillBlockEntity> {

    public GrillMenu(int containerId, Inventory inventory, GrillBlockEntity tile) {
        super(resolveContainer(tile), containerId, inventory, tile);
    }

    private static ContainerTypeRegistryObject<GrillMenu> resolveContainer(GrillBlockEntity tile) {
        if (tile == null) {
            // 走到这里说明方块的 BlockType 描述没绑对 tile：显式报错好过把 null 传给父类。
            throw new IllegalStateException(
                    "电力烧烤架容器拿不到 tile：BlockTypeTile 的 tile Supplier 被过早求值，"
                            + "或方块与 tile 类型不匹配。");
        }
        ContainerTypeRegistryObject<GrillMenu> container = MekCkFactories.GRILL_CONTAINER;
        if (container == null) {
            throw new IllegalStateException("电力烧烤架容器尚未注册（GRILL_CONTAINER == null）");
        }
        return container;
    }

    // ── 给 GUI 读的转发 ──────────────────────────────────────────────────

    /**
     * 进度条分子：0..24（屏幕侧除以 24.0）。
     *
     * <p>分母走 {@code tile.getWorkCycle()} 而不是写死 200：装速度卡后批次变短，
     * 进度条必须跟着变短才有正确的「越快越满」手感。而 {@code getWorkCycle()}
     * 在客户端读的是同步镜像（见 {@code GrillBlockEntity.addContainerTrackers}），
     * 不会与真实批次长度漂移。</p>
     */
    public int getProgress() {
        GrillBlockEntity tile = getTileEntity();
        if (tile == null) {
            return 0;
        }
        int cycle = tile.getWorkCycle();
        return cycle <= 0 ? 0 : tile.getWorkProgress() * 24 / cycle;
    }

    /** 存量能量（FE）。能量容器由 Mek 自己同步，客户端读到的是权威值。 */
    public int getEnergy() {
        GrillBlockEntity tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getEnergy().intValue();
    }

    /** 容量上限（FE）。 */
    public int getMaxEnergy() {
        GrillBlockEntity tile = getTileEntity();
        return tile == null ? 0 : tile.getEnergyContainer().getMaxEnergy().intValue();
    }

    /** 机身温度（单位 0.01 ℃）—— 屏幕侧除以 100.0。 */
    public int getTemperature() {
        GrillBlockEntity tile = getTileEntity();
        return tile == null ? 0 : (int) Math.round((tile.getTemperature() - 273.15) * 100.0);
    }

    public BlockPos getBlockPos() {
        GrillBlockEntity tile = getTileEntity();
        return tile == null ? BlockPos.ZERO : tile.getBlockPos();
    }

    /** 本机 tile —— 下单面板的「本机可做配方 / 可做份数」按它算。 */
    public GrillBlockEntity getMachine() {
        return getTileEntity();
    }
}
