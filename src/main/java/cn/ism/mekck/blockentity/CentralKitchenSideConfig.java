package cn.ism.mekck.blockentity;

import net.minecraft.core.Direction;

/**
 * 中央厨房的<b>物品 / 流体 / 气体侧配</b>读写子系统：三组独立 {@code SideMode[6]} 数组的
 * 取值与写入（下标 = {@link Direction#ordinal()}）。
 *
 * <h3>为什么要从 {@code CentralKitchenBlockEntity} 里独立出来</h3>
 * 侧配是「外部自动化怎么与本机交换物品 / 流体 / 气体」的配置面，与 BE 的加工逻辑正交：
 * 读侧配不参与 tick 判定，写侧配只是改一个数组项再 {@code setChanged()}。拆出后，
 * 六个访问器不再夹在 NBT 段与流体自动 IO 之间。
 *
 * <h3>搬运边界与可见性</h3>
 * 三个数组仍需留在 BE 上：{@code saveAdditional}/{@code load} 用它们做编解码，
 * {@code ItemSideConfig}/{@code FluidSideConfig}/{@code GasSideConfig} 是存档契约；
 * {@code getCapability}（读气体侧配）与 {@code tickFluidIO}（读流体侧配）也直接读数组。
 * 因此数组不搬，本类只做「按下标读写 + 触发 {@code setChanged()}」，
 * 为此把三个数组由 {@code private} 放宽为包级可见（仅此三处，见报告）。
 * BE 保留同签名的公开访问器（{@code CentralKitchenMenu}、{@code SideConfigPacket} 仍按原名调用）。
 *
 * <p>本类<b>不持有任何自己的状态</b>：数组仍归 {@code be} 所有。</p>
 */
public final class CentralKitchenSideConfig {

    private final CentralKitchenBlockEntity be;

    public CentralKitchenSideConfig(CentralKitchenBlockEntity be) {
        this.be = be;
    }

    void setItemSideMode(Direction dir, cn.ism.mekck.SideMode mode) {
        if (dir == null) return;
        be.itemSideConfig[dir.ordinal()] = mode;
        be.setChanged();
    }

    cn.ism.mekck.SideMode getItemSideMode(Direction dir) {
        return be.itemSideConfig[dir.ordinal()];
    }

    void setFluidSideMode(Direction dir, cn.ism.mekck.SideMode mode) {
        if (dir == null) return;
        be.fluidSideConfig[dir.ordinal()] = mode;
        be.setChanged();
    }

    cn.ism.mekck.SideMode getFluidSideMode(Direction dir) {
        return be.fluidSideConfig[dir.ordinal()];
    }

    void setGasSideMode(Direction dir, cn.ism.mekck.SideMode mode) {
        if (dir == null) return;
        be.gasSideConfig[dir.ordinal()] = mode;
        be.setChanged();
    }

    cn.ism.mekck.SideMode getGasSideMode(Direction dir) {
        return be.gasSideConfig[dir.ordinal()];
    }
}
