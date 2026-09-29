package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.grinding.GrindingFactoryTile;
import cn.ism.mekck.menu.GrindingFactoryMenu;
import mekanism.api.math.FloatingLong;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 研磨工厂 GUI（Mek 体系版）—— 阶段 3 Task 1。
 *
 * <h3>从 457 行缩到几十行：省下的是什么</h3>
 * 旧界面里 6 个侧栏 tab 全是手摆的 {@code MekCkTabElement}：坐标自己写、图标自己挑、
 * tooltip 自己挂、红石三态自己切图。现在继承 {@link GuiConfigurableTile} 后，
 * {@code super.addGuiElements()} 一句就把侧配 / 传输配置 / 升级 / 红石 / 安全全部排好，
 * 坐标由 Mek 的常量决定。<b>这正是本项目换掉旧体系的目的</b>：旧自研 tab 与 Mek 的 tab
 * 分属两套坐标系，运行时只能互相避让，表现为侧栏元素重叠。
 *
 * <p>槽位 widget 也一行都不用写：{@code dynamicSlots = true} 会让
 * {@code GuiMekanism.addSlots()} 遍历 {@code menu.slots}，对每个
 * {@code InventoryContainerSlot} 按其 {@code ContainerSlotType} 自动建 {@code GuiSlot}，
 * 坐标直接取容器槽的 {@code x/y}——而那两个值又是 tile 侧
 * {@code getInitialInventory} 里排方阵时写进去的。</p>
 *
 * <h3>本界面没有的三个面板（不是取舍，是 tile 换人的必然结果）</h3>
 * <ul>
 *   <li><b>自动分配</b>（{@code AutoDistributePacket}）。服务端处理器只认
 *       {@code GrillFactoryBlockEntity} 与 {@code PlantingCuttingFactoryBlockEntity}，
 *       研磨这一档已被排除（AE2 自动化的正主是 AE2 自己的网格，机器侧只提供
 *       {@code eject / side / config} 三个导出设置）。</li>
 *   <li><b>ME 自动处理面板</b>同理不建。但服务端这一侧的通道是通的——
 *       {@code MekckAe2} 按 {@link cn.ism.mekck.machine.ports.IMekCkPorted} 判定，
 *       再转交 {@code AE2Compat}（阶段 2 Task 4.6 接的）。</li>
 *   <li><b>「本机 / ME」下单面板</b>与 <b>AE2 网络拉料按钮</b>锚在旧 tile 的
 *       {@code INetworkPullable} 与自绘 tab 上；新 tile 走 {@code IMekCkPorted} 端口声明。
 *       订单<b>引擎</b>没丢——{@code GrindingFactoryExecutor} 仍持有订单状态、
 *       仍按订单门禁配方匹配、仍推进完成计数（见该类的订单小节）；
 *       丢的只是这一块手绘 GUI，理由与切菜工厂同款：GUI 侧的订单面板要等
 *       {@code GuiConfigurableTile} 的 tab 布局定稿后再统一接。</li>
 * </ul>
 */
public final class GrindingFactoryScreen extends GuiConfigurableTile<GrindingFactoryTile, GrindingFactoryMenu> {

    /** 输入方阵与输出方阵的水平间隔，与 tile 侧 {@code GRID_GAP} 同值。 */
    private static final int GAP_BETWEEN = 30;

    public GrindingFactoryScreen(GrindingFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        CuttingMachineFactoryTier tier = tile.getTier();
        int columns = tier == null ? 1 : (int) Math.ceil(Math.sqrt(tier.processes));
        int rows = tier == null ? 1 : (int) Math.ceil((double) tier.processes / columns);
        int extraHeight = Math.max(0, (rows - 2) * 18);
        // 面板尺寸必须随并行数长，否则高等级工厂的槽位会画到面板外面。
        // 底图不用自己画：GuiMekanism.renderBg 直接把 base.png 拉到 (imageWidth, imageHeight)。
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
        // tooltip 里的存量/上限/每秒耗量由 Mek 自己组装。
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
