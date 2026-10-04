package cn.ism.mekck.client;

import cn.ism.mekck.machine.grinding.GrindingFactoryTile;
import cn.ism.mekck.menu.GrindingFactoryMenu;
import mekanism.client.gui.GuiConfigurableTile;
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
 *   <li><b>自动分配</b>（{@code AutoDistributePacket}）。这个 MekCK 自研状态已随旧 BE
 *       一起删除（切菜在阶段 2 Task 4.6、烧烤在阶段 3 Task 3），Mek 自己的
 *       {@code TileComponentEjector} + 弹出配置接管这件事，界面上没有对应按钮。
 *       所以本界面不建这个开关；{@code AutoDistributePacket} 也已无任何有效目标。</li>
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
public final class GrindingFactoryScreen extends MekCkFactoryScreenBase<GrindingFactoryTile, GrindingFactoryMenu> {

    /**
     * 槽位悬浮窗标签页 —— 只有 &gt;17 并行的高档工厂才有窗口槽，
     * 所以本字段可能恒为 null（见 {@code menu.windowSlots().isEmpty()}）。
     * 关闭窗口后需要用同一实例重新激活，因此必须留引用。
     */
    private MekCkSlotWindowTab slotWindowTab;

    public GrindingFactoryScreen(GrindingFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // 面板尺寸由 MekCkFactoryLayout 统一给出（屏幕与菜单共用同一份公式），见该类注释。
        imageWidth = cn.ism.mekck.menu.MekCkFactoryLayout.gridFamilyPanelWidth(tile);
        imageHeight = cn.ism.mekck.menu.MekCkFactoryLayout.gridFamilyPanelHeight(tile, 0, 0, 0);
        inventoryLabelY = cn.ism.mekck.menu.MekCkFactoryLayout.inventoryLabelY(imageHeight);
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
        // 第三个参数是「使用量」供给器 —— 上游 GuiFactory 传的是 tile::getLastUsage
        // （上一 tick 的真实扣电量），不是等级的声明值。
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(), tile::getLastUsage));

        // 竖直能源条（旧 GUI 有、迁移时丢的那条），位置与数据源见基类。
        addEnergyBar();

        // 自动分选标签页（上游 GuiFactory 的第一句就是它）。
        addSortingTab();

        // 槽位悬浮窗标签页：只有高档工厂（>17 并行）或烹饪/穿串才有窗口槽，
        // 三组皆空时不加标签页（menu.windowSlots().isEmpty()）。
        if (!menu.windowSlots().isEmpty()) {
            slotWindowTab = addRenderableWidget(new MekCkSlotWindowTab(this, tile,
                    menu.windowSlots(), () -> slotWindowTab));
        }

        // 进度条：一行式档位每并行槽一条（上游 GuiFactory / GuiExtraFactory 的做法），
        // 悬浮窗布局主面板上没有机器槽、居中一条。几何与理由见 MekCkFactoryScreenBase。
        addFactoryProgressBars(menu::getProgressRatio);

        // 自动补料 / 网络拉料 tab：必须最后注册（命中优先），见基类注释。
        addNetworkPullTabs();
    }
}
