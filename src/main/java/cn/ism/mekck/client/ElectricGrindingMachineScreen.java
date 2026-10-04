package cn.ism.mekck.client;

import cn.ism.mekck.machine.grinding.GrindingMachineTile;
import cn.ism.mekck.menu.ElectricGrindingMachineMenu;
import cn.ism.mekck.network.ModMessages;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.GuiUpArrow;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/**
 * 电力研磨机屏幕 —— Mek 体系版。
 *
 * <h3>画风口径：逐条对齐 Mek 自己的基础机器（{@code GuiElectricMachine}，粉碎机用的就是它）</h3>
 * 本机在 MekCK 里的定位就是「一台基础电力机器」，所以它的 GUI 不该有自己的尺寸与排布，
 * 而应当与 Mek 的粉碎机 / 富集仓逐项一致。<b>下列每一项都是从
 * {@code GuiElectricMachine} 的字节码逐条读出来的</b>（不是「照同类抄个大概」）：
 *
 * <pre>
 *   GuiElectricMachine.&lt;init&gt;       dynamicSlots = true      ← 且**不覆写** imageWidth/Height
 *   GuiElectricMachine.addGuiElements:
 *     super.addGuiElements()
 *     new GuiUpArrow(this, 68, 38)
 *     new GuiVerticalPowerBar(this, tile.getEnergyContainer(), 164, 15)   + NOT_ENOUGH_ENERGY 警告
 *     new GuiEnergyTab(this, tile.getEnergyContainer(), tile::getLastUsage)
 *     new GuiProgress(..., ProgressType.BAR, this, 86, 38)
 * </pre>
 *
 * <p><b>尺寸刻意不写</b>：{@code GuiElectricMachine} 一个字节都没覆写 {@code imageWidth} /
 * {@code imageHeight}，用的是继承来的默认值（176×166）。写出来反而会与上游漂移。
 * 面板底板由 {@code GuiMekanism} 按这两个值从公共贴图切出，<b>尺寸一改画风就变</b>
 * —— 迁移第一版我照 GrillScreen 抄了 176×166、第二版又照旧自研屏写回 220×184，
 * 两次都错：前者尺寸对但没抄其余项，后者连尺寸都不该动。</p>
 *
 * <h3>为什么从 {@code GuiMekanism} 换成 {@link GuiConfigurableTile}</h3>
 * 迁移前本屏自己管着 4 枚侧栏 tab（侧配 / ME 下单 / 升级 / 红石）、一套自绘的
 * {@code GuiMekCkSideConfiguration} 侧配窗口、一套自绘的 {@code GuiUpgradeWindow} 升级窗口，
 * 外加手绘命中矩形与 tooltip —— 因为这些能力在自研体系里全都要自己实现。
 * 继承 {@code GuiConfigurableTile} 后，<b>侧配 / 传输配置 / 升级 / 红石 / 安全由 Mek 自己挂</b>，
 * 这里一行都不该再写：写了就是两套 tab 叠在一起。
 *
 * <p>槽位同理不手绘：{@code dynamicSlots = true} 让 {@code GuiMekanism.addSlots()} 遍历
 * {@code menu.slots}，按每个 {@code InventoryContainerSlot} 的 {@code ContainerSlotType}
 * 自动建 widget，坐标取容器槽的 {@code x/y} —— 而那两个值是 tile 的
 * {@code getInitialInventory} 建槽时写进去的。这正是本次迁移要消掉的
 * 「菜单与屏幕各写一套坐标」。</p>
 *
 * <h3>本屏比上游多出来的唯一一样：ME 下单面板</h3>
 * Mek 没有对应物（它没有 AE2 集成），是真正的自定义功能，经 {@link NetworkOrderTab}
 * 装进 Mek 虚拟窗口 —— 与烧烤架 / 穿串机同款。
 */
public final class ElectricGrindingMachineScreen
        extends MekCkContainerScreenBase<GrindingMachineTile, ElectricGrindingMachineMenu>
        implements NetworkOrderHost {

    /** 「下单」tab 的 y —— 侧配 tab 下方。 */
    private static final int ORDER_TAB_Y = 34;
    /** 左列 tab 的 x。 */
    private static final int TAB_X = 6;

    private NetworkOrderTab orderTab;

    public ElectricGrindingMachineScreen(ElectricGrindingMachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // dynamicSlots 由基类设；尺寸与标签位置**刻意不写**：与 GuiElectricMachine 一致，用继承来的默认值。
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        // 上箭头 —— 上游 GuiElectricMachine(68,38)。
        addRenderableWidget(new GuiUpArrow(this, 68, 38));

        // 能源条 —— 上游 GuiElectricMachine(164,15)，直接吃 tile 的能量容器。
        addRenderableWidget(new GuiVerticalPowerBar(this, tile.getEnergyContainer(), 164, 15));

        // 能量信息 tab —— 上游传的是 tile::getLastUsage（上一 tick 的真实扣电量）。
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(), tile::getLastUsage));

        // 进度条 —— 上游 GuiElectricMachine 用 ProgressType.BAR 且位于 (86,38)。
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgressPercent() / 100.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgressPercent() > 0;
            }
        }, ProgressType.BAR, this, 86, 38));

        // ME 下单：Mek 无对应物，保留自绘 tab + 虚拟窗口。
        // **最后注册**：Mek 的 mouseClicked 对 children() 倒序遍历、命中即返回，越晚注册命中越优先。
        orderTab = addRenderableWidget(new NetworkOrderTab(this, menu.getBlockPos(),
                TAB_X, ORDER_TAB_Y, true, localOrderSource(), () -> orderTab));
    }

    @Override
    public NetworkOrderPanel networkOrderPanel() {
        // 窗口开着 ⇒ 返回窗口里的面板；关着 ⇒ null（回包丢弃，不再灌进已销毁的面板）。
        return orderTab == null ? null : orderTab.panel();
    }

    /**
     * 本机一侧：配方 / 可做份数按机器输入槽里的材料算，下单走通用订单包。
     *
     * <p>面板随窗口每次打开重建，数据源必须跟着重建，否则新面板的本机模式是空的。</p>
     */
    private NetworkOrderPanel.LocalSource localOrderSource() {
        return new NetworkOrderPanel.LocalSource() {
            @Override
            public List<Recipe<?>> recipes() {
                var machine = menu.getMachine();
                return machine == null ? List.of() : machine.getAvailableRecipes();
            }

            @Override
            public int maxCraftable(Recipe<?> recipe) {
                var machine = menu.getMachine();
                return machine == null ? 0 : machine.getMaxConsumableCountForOrder(recipe);
            }

            @Override
            public void order(Recipe<?> recipe, int quantity) {
                ModMessages.sendToServer(new cn.ism.mekck.network.OrderRecipePacket(
                        menu.getBlockPos(), recipe.getId(), quantity));
            }
        };
    }
}
