package cn.ism.mekck.menu;

import cn.ism.mekck.SideMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 所有机器菜单统一实现的侧面配置访问接口，供可复用的侧面配置窗口使用。
 */
public interface ISideConfigurableMenu {
    SideMode getSideMode(Direction direction);

    /**
     * 流体侧面配置（独立于物品侧配）。未实现流体侧配的机器返回 NONE。
     */
    default SideMode getFluidSideMode(Direction direction) {
        return SideMode.NONE;
    }

    /**
     * 气体侧面配置（独立于物品/流体侧配）。未实现气体侧配的机器返回 NONE。
     */
    default SideMode getGasSideMode(Direction direction) {
        return SideMode.NONE;
    }

    /**
     * 气体是否提供「弹出」模式。默认 false：
     * 当前接入气体侧配的机器（种植切配工厂）只有单一营养液储罐（营养液 = 原料），
     * 无法区分原料与产物，禁用弹出以免把原料推出去。
     * 将来加入「有气体产物」的机器时，在该机器菜单重写为 true 即可启用。
     */
    default boolean supportsGasPush() {
        return false;
    }

    BlockPos getBlockPos();

    /**
     * 机器是否具有存储空间（决定侧面配置是否提供“抽取（至存储空间）”模式）。
     */
    default boolean supportsStoragePull() {
        return false;
    }
}