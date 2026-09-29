package cn.ism.mekck.menu;

import net.minecraft.world.inventory.Slot;

/**
 * 所有机器菜单统一实现的升级信息访问接口，供可复用的 Mekanism 风格升级窗口使用。
 * 无堆叠升级的单线程机器继承默认实现即可。
 */
public interface IUpgradeMenu {
    int getSpeedUpgradeCount();

    int getEnergyUpgradeCount();

    default int getStackUpgradeCount() {
        return 0;
    }

    default boolean hasStackUpgrade() {
        return false;
    }

    /**
     * 速度/能量升级上限，由配置文件控制。基础机器固定为 8，
     * 工厂类由本模组配置文件按等级控制。
     */
    default int getSpeedUpgradeMax() {
        return 8;
    }

    default int getEnergyUpgradeMax() {
        return 8;
    }

    Slot getSpeedUpgradeSlot();

    Slot getEnergyUpgradeSlot();

    default Slot getStackUpgradeSlot() {
        return null;
    }

    default Slot getGasUpgradeSlot() {
        return null;
    }

    /** 本机器是否有创造升级槽（主界面不常显；仅升级页选中时绘制，同速度/能量）。默认无。 */
    default boolean hasCreativeUpgrade() {
        return false;
    }

    /** 创造升级槽（供升级窗口右侧 selectedSlot 绑定）。默认 null。 */
    default Slot getCreativeUpgradeSlot() {
        return null;
    }

    /** 创造升级已装数量（0/1）。 */
    default int getCreativeUpgradeCount() {
        return 0;
    }

    default int getGasUpgradeCount() {
        return 0;
    }

    /**
     * 升级安装读条进度（0~1）。默认 0（未实现读条的机器不显示进度）。
     */
    default double getUpgradeInstallProgress() {
        return 0.0;
    }

    /**
     * 本机器是否支持速度升级。默认支持；急冻制冰机等速度由其它机制决定的机器返回 false，
     * 升级界面据此隐藏速度升级条目与槽位。
     */
    default boolean supportsSpeedUpgrade() {
        return true;
    }

    /**
     * 卸载升级：mode 0 = 卸载 1 个，1 = 卸载全部，2 = 卸载指定槽位（冷萃 / 费列罗链式槽）。
     * 未实现读条卸载的机器为默认空操作。
     */
    default void uninstallUpgrade(byte mode, int slot) {
    }

    /** 本机器是否支持卸载升级（升级界面据此显示卸载按钮）。 */
    default boolean supportsUpgradeUninstall() {
        return false;
    }

    /** 本机器是否支持能量升级（默认支持）。 */
    default boolean supportsEnergyUpgrade() {
        return true;
    }

    /**
     * 升级弹窗打开/关闭时激活/停用升级槽，供升级窗口调用。
     */
    default void setUpgradePageActive(boolean active) {
    }

    default boolean isUpgradePageActive() {
        return true;
    }
}