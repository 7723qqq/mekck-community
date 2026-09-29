package cn.ism.mekck.factory;

import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * MekCK 工厂 GUI（Mek 体系版）—— <b>tab 对齐的验收点</b>。
 *
 * <h3>关键：只写 super.addGuiElements()</h3>
 * 继承 {@link GuiConfigurableTile} 后，侧栏 tab 由 Mek 自己排布，坐标无需手写：
 * <pre>
 *   GuiMekanismTile.addGuiElements()
 *     └─ addGenericTabs()
 *          ├─ GuiUpgradeWindowTab   (x=imageWidth, y=6)    右列
 *          ├─ GuiRedstoneControlTab (x=imageWidth, y=137)  右列
 *          └─ GuiSecurityTab        (y=34)                 左列
 *   GuiConfigurableTile.addGuiElements()
 *     ├─ GuiSideConfigurationTab  (x=-26, y=6)            左列
 *     └─ GuiTransporterConfigTab  (x=-26, y=34)           左列
 * </pre>
 * 这些都是 Mek 用固定常量创建的元素（反编译实测），因此天然与原生机器逐像素对齐——
 * 这正是本项目旧实现做不到的：旧套自研 tab 与 Mek 的 tab 分属两套坐标系，
 * 要靠 {@code avoidEnergyTabY} 运行时避让，导致重叠。
 *
 * <h3>与 Mek/Extras 的对应</h3>
 * 本类对应 {@code GuiFactory}（Mek 原生工厂的 GUI）与 {@code GuiExtraFactory}
 * （Extras 的工厂 GUI）：三者都只做「{@code super.addGuiElements()} +
 * 追加自己独有的元素」，不手写任何 tab 坐标。
 *
 * <h3>能量 tab</h3>
 * 单独追加 {@link GuiEnergyTab}：它由 Mek 构造为 {@code (x=-26, y=137, 26, 26)}，
 * 位置固定，与侧配/传输配置同处左列且不冲突（左列三个位置 6/34/62 之后才是它）。
 */
public class MekCkFactoryScreen extends GuiConfigurableTile<MekCkFactoryTile, MekCkFactoryMenu> {

    public MekCkFactoryScreen(MekCkFactoryMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    @Override
    protected void addGuiElements() {
        // 侧配(6) / 传输配置(34) / 升级(6,右) / 红石(137,右) / 安全 —— 全部由 Mek 排布
        super.addGuiElements();

        // 能量 tab：Mek 固定 (x=-26, y=137, 26, 26)
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
                Component.translatable("gui.mekck.energy_stored",
                        tile.getEnergyContainer().getEnergy(),
                        tile.getEnergyContainer().getMaxEnergy()))));
    }
}
