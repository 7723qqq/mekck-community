package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.cutting.CuttingFactoryTile;
import cn.ism.mekck.menu.CuttingMachineFactoryMenu;
import mekanism.api.math.FloatingLong;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 切菜工厂 GUI（Mek 体系版）—— 阶段 2 Task 4。
 *
 * <h3>从 741 行缩到几十行：省下的是什么</h3>
 * 旧界面里 6 个侧栏 tab 全是手摆的 {@code MekCkTabElement}：坐标自己写、
 * 图标自己挑、tooltip 自己挂、红石三态自己切图、PULSE 还自己叠动画层。
 * 现在继承 {@link GuiConfigurableTile} 后，{@code super.addGuiElements()} 一句就把
 * 侧配 / 传输配置 / 升级 / 红石 / 安全全部排好，坐标由 Mek 的常量决定。
 * <b>这正是本项目换掉旧体系的目的</b>：旧自研 tab 与 Mek 的 tab 分属两套坐标系，
 * 运行时只能靠 {@code avoidEnergyTabY} 互相避让，表现为侧栏元素重叠。
 *
 * <p>槽位 widget 也一行都不用写：{@code dynamicSlots = true} 会让
 * {@code GuiMekanism.addSlots()} 遍历 {@code menu.slots}，对每个
 * {@code InventoryContainerSlot} 按其 {@code ContainerSlotType}
 * （INPUT / OUTPUT / POWER / NORMAL）自动建 {@code GuiSlot}，坐标直接取
 * 容器槽的 {@code x/y}——而那两个值又是 tile 侧 {@code getInitialInventory} 里
 * 排方阵时写进去的。旧界面手摆那 2N 个 {@code GuiVirtualSlot} 的原因也在这里：
 * 旧菜单的槽是原版 {@code Slot}，不是 {@code InventoryContainerSlot}，
 * 自动通路根本认不出来。</p>
 *
 * <h3>随 Task 4 一并消失的功能（不是本类的取舍，是 tile 换人的必然结果）</h3>
 * <ul>
 *   <li><b>自动分配</b>（{@code AutoDistributePacket} → {@code toggleAutoDistribute}）
 *       与 <b>ME 自动处理面板</b>（{@code AutoProcessTogglePacket} →
 *       {@code getAutoSelectedItems}）依赖旧 tile 上的两个字段。新 tile 没有这些状态，
 *       包到达后 {@code be instanceof CuttingMachineFactoryBlockEntity} 判否、静默跳过，
 *       不会崩但也不会生效。</li>
 *   <li><b>ME 下单面板</b>与 <b>AE2 网络拉料按钮</b>同理，锚在旧 tile 的
 *       {@code INetworkPullable} 上。</li>
 * </ul>
 * 它们的正主是阶段 2 后续的 AE2 自动化层——{@code IMekCkPorted}（Task 2）
 * 声明的就是那一层要消费的端口契约，切菜这一档已经按契约把
 * {@code meGroupParallelItemInputs()} 等 7 个方法填好了。
 */
public final class CuttingMachineFactoryScreen extends GuiConfigurableTile<CuttingFactoryTile, CuttingMachineFactoryMenu> {

    /** 输入方阵与输出方阵的水平间隔，与 tile 侧 {@code GRID_GAP} 同值。 */
    private static final int GAP_BETWEEN = 30;

    public CuttingMachineFactoryScreen(CuttingMachineFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        CuttingMachineFactoryTier tier = tile.getTier();
        int columns = tier == null ? 1 : (int) Math.ceil(Math.sqrt(tier.processes));
        int rows = tier == null ? 1 : (int) Math.ceil((double) tier.processes / columns);
        int extraHeight = Math.max(0, (rows - 2) * 18);
        // 面板尺寸必须随并行数长，否则高等级工厂的槽位会画到面板外面。
        // 底图不用自己画：GuiMekanism.renderBg 直接把 base.png 拉到 (imageWidth, imageHeight)
        // （实测字节码：renderBackgroundTexture(..., imageWidth, imageHeight, 256, 256)）。
        imageWidth = 38 + columns * 18 + GAP_BETWEEN + columns * 18 + 20;
        imageHeight = 184 + extraHeight;
        inventoryLabelY = 89 + extraHeight;
        // 让 GuiMekanism.addSlots() 从容器槽自动建 widget（见类注释）。
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        // 侧配(-26,6) / 传输配置(-26,34) / 升级(imageWidth,6) / 红石(imageWidth,137) / 安全
        // 与全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        // 能量 tab：Mek 固定 (x=-26, y=137, 26, 26)，与侧配/传输配置同处左列不冲突。
        // 第二个构造器直接吃 MachineEnergyContainer 与「每 tick 能耗」供给器，
        // tooltip 里的存量/上限/每秒耗量由 Mek 自己组装，不必像旧界面那样手写三个 Component。
        CuttingMachineFactoryTier tier = tile.getTier();
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(),
                () -> FloatingLong.create(tier == null ? 0 : tier.energyPerTick)));

        // 进度条：SMALL_RIGHT 箭头，横在输入方阵与输出方阵之间。
        int columns = tier == null ? 1 : (int) Math.ceil(Math.sqrt(tier.processes));
        int rows = tier == null ? 1 : (int) Math.ceil((double) tier.processes / columns);
        int progressX = 38 + columns * 18 + (GAP_BETWEEN - 28) / 2;
        int progressY = 41 + rows * 18 / 2 - 4;
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgressRatio();
            }

            @Override
            public boolean isActive() {
                return menu.isBusy();
            }
        }, ProgressType.SMALL_RIGHT, this, progressX, progressY));
    }
}
