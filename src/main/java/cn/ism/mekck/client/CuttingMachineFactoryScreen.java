package cn.ism.mekck.client;

import cn.ism.mekck.machine.cutting.CuttingFactoryTile;
import cn.ism.mekck.menu.CuttingMachineFactoryMenu;
import mekanism.client.gui.GuiConfigurableTile;
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
 * <h3>本界面没有的两个面板（不是取舍，是 tile 换人的必然结果）</h3>
 * <ul>
 *   <li><b>自动分配</b>（{@code AutoDistributePacket}）。这个 MekCK 自研状态已随旧 BE
 *       一起删除（切菜在阶段 2 Task 4.6、烧烤在阶段 3 Task 3），Mek 自己的
 *       {@code TileComponentEjector} + 弹出配置接管这件事，界面上没有对应按钮。
 *       所以本界面不建这个开关；{@code AutoDistributePacket} 也已无任何有效目标。</li>
 *   <li><b>ME 自动处理面板</b>（{@code AutoProcessTogglePacket}）同理：本界面不建。
 *       但服务端这一侧的通道是通的——包处理器按 {@link CuttingFactoryTile} 实现的
 *       {@code IMekCkPorted} 判定，再转交 {@code AE2Compat}（阶段 2 Task 4.6 接的）。</li>
 *   <li><b>ME 下单面板</b>与 <b>AE2 网络拉料按钮</b>锚在旧 tile 的
 *       {@code INetworkPullable} 上；新 tile 走的是 {@code IMekCkPorted} 端口声明，
 *       本界面同样不建。</li>
 * </ul>
 * 它们的正主是阶段 2 后续的 AE2 自动化层——{@code IMekCkPorted}（Task 2）
 * 声明的就是那一层要消费的端口契约，切菜这一档已经按契约把
 * {@code meGroupParallelItemInputs()} 等 7 个方法填好了。
 */
public final class CuttingMachineFactoryScreen extends MekCkFactoryScreenBase<CuttingFactoryTile, CuttingMachineFactoryMenu> {

    /**
     * 槽位悬浮窗标签页 —— 只有 &gt;17 并行的高档工厂才有窗口槽，
     * 所以本字段可能恒为 null（见 {@code menu.windowSlots().isEmpty()}）。
     * 关闭窗口后需要用同一实例重新激活，因此必须留引用。
     */
    private MekCkSlotWindowTab slotWindowTab;

    public CuttingMachineFactoryScreen(CuttingMachineFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // 面板尺寸由 MekCkFactoryLayout 统一给出（屏幕与菜单共用同一份公式）：
        // 悬浮窗布局（>17 并行，输入输出整块在窗口里）收窄到 Mek 的标准 176×166；
        // 一行式（≤17）照 Mek / MekExtras 的实测值；其余走方阵公式。
        // 此前这里自己写三分支、菜单只按方阵公式算，一行式档位下背包槽比标签低 18px。
        imageWidth = cn.ism.mekck.menu.MekCkFactoryLayout.gridFamilyPanelWidth(tile);
        imageHeight = cn.ism.mekck.menu.MekCkFactoryLayout.gridFamilyPanelHeight(tile, 0, 0, 0);
        // 菜单侧用它覆写 {@code getInventoryYOffset()}，屏幕侧用同一份算标签，
        // 两边不可能再各算各的。公式来源见该类注释（反推自 Mek 的三档实测）。
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
        // 第二个构造器直接吃 MachineEnergyContainer 与「每 tick 能耗」供给器，
        // tooltip 里的存量/上限/每秒耗量由 Mek 自己组装，不必像旧界面那样手写三个 Component。
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
    }
}
