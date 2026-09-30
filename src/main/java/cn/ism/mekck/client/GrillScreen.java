package cn.ism.mekck.client;

import cn.ism.mekck.blockentity.GrillBlockEntity;
import cn.ism.mekck.menu.GrillMenu;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/**
 * 电力烧烤架 GUI（Mek 体系版）。
 *
 * <h3>从 531 行缩到一百来行：省下的是什么</h3>
 * 继承 {@link GuiConfigurableTile} 后 {@code super.addGuiElements()} 一句就把
 * 侧配 / 传输配置 / 升级 / 红石 / 安全排好，坐标由 Mek 的常量决定。
 * <b>这正是换掉旧体系的目的</b>：旧自研 tab 与 Mek 的 tab 分属两套坐标系，
 * 运行时只能靠 {@code avoidEnergyTabY} 互相避让，表现为侧栏元素重叠。
 *
 * <p>槽位 widget 也不用手写：{@code dynamicSlots = true} 让 {@code GuiMekanism.addSlots()}
 * 遍历 {@code menu.slots}，对每个 {@code InventoryContainerSlot} 按其 {@code ContainerSlotType}
 * 自动建 {@code GuiSlot}，坐标直接取容器槽的 x/y——而那两个值又是 tile 侧
 * {@code getInitialInventory} 排槽时写进去的。旧界面手摆 {@code GuiVirtualSlot} 的原因
 * 也在这里：旧菜单的槽是原版 {@code Slot}，不是 {@code InventoryContainerSlot}，
 * 自动通路根本认不出来。</p>
 *
 * <h3>被删掉的三块（不是取舍，是换体系的必然结果）</h3>
 * <ul>
 *   <li><b>自研配置覆盖层</b>（6 个方向格 + 「完成」按钮，{@code MekCkButtons}）：
 *       由 Mek 的侧配窗口取代（{@code GuiSideConfigurationTab} → {@code GuiSideConfiguration}）。</li>
 *   <li><b>自研侧配 / 升级 / 红石三个 tab</b>（{@code MekCkTabElement}）：
 *       由 {@code GuiConfigurableTile} 与 {@code GuiMekanismTile.addGenericTabs()} 自动提供。</li>
 *   <li><b>自研升级窗口</b>（{@code GuiUpgradeWindow} + {@code IUpgradeMenu}）：
 *       由 {@code GuiUpgradeWindowTab} + {@code TileComponentUpgrade} 取代，
 *       连 20 tick 安装读条都是 Mek 自带的。</li>
 * </ul>
 *
 * <h3>仍然保留的四块</h3>
 * ME 下单面板（{@link NetworkOrderPanel} + {@link NetworkOrderHost}）、温度读数、
 * {@link BigStackHud}（大堆叠数量显示）、自动补料 / 网络拉料两枚 tab。
 */
public final class GrillScreen extends GuiConfigurableTile<GrillBlockEntity, GrillMenu> implements NetworkOrderHost {

    private final BigStackHud bigStackHud = new BigStackHud();

    /**
     * 下单 tab 的 y。
     *
     * <p><b>从 34 挪到右列</b>：旧屏把它放在左列 y=34，而那个位置在 Mek 体系里是
     * {@code GuiTransporterConfigTab}（实测其构造字节码 {@code bipush -26, 34, 26, 18}）。
     * 左列 6/34/62/90/137 五格已被侧配 / 传输配置 / 自动补料 / 网络拉料 / 能量占满，
     * 118..135 只剩 18px 塞不下 26px 的 tab，所以只能换列。
     * 右列 6 是升级 tab、137 是红石 tab（实测 {@code getWidth(), 137, 26, 18}），
     * 34 空着。</p>
     */
    private static final int ORDER_TAB_Y = 34;

    /**
     * 「下单」标签页 —— 点开 {@link NetworkOrderWindow}。
     *
     * <p>必须留引用：{@code GuiWindowCreatorTab} 关闭窗口时靠 {@code elementSupplier.get()}
     * 把同一实例重新激活，宿主屏幕也靠它取「正在显示的那一个」面板。</p>
     */
    private NetworkOrderTab orderTab;

    public GrillScreen(GrillMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // 面板尺寸与 Mek 基础电力熔炼炉（GuiElectricMachine）一致：176×166。
        // 这两个值同时也是 AbstractContainerScreen 的默认值，写出来是为了让「别动」这件事显式。
        imageWidth = 176;
        imageHeight = 166;
        // 玩家背包首行的 y：Mek 的 MekanismContainer.getInventoryYOffset() 默认返回
        // BASE_Y_OFFSET = 84，与旧菜单手摆的 84 同值；但 inventoryLabelY 的默认值是
        // imageHeight - 94 = 72，必须显式对齐到 84，否则「Inventory」标签会浮在背包上方 12px。
        inventoryLabelY = 84;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        // 上箭头：Mek 基础电力熔炼炉 GuiUpArrow(68,38)。
        addRenderableWidget(new GuiUpArrow(this, 68, 38));

        // 能源条：Mek 基础电力熔炼炉 GuiVerticalPowerBar(164,15)。
        // 直接吃 tile 的能量容器，存量/上限与 tooltip 都由 Mek 自己组装。
        addRenderableWidget(new GuiVerticalPowerBar(this, tile.getEnergyContainer(), 164, 15));

        // 进度条：Mek 基础电力熔炼炉 GuiProgress(ProgressType.BAR, 86,38)。
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgress() / 24.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgress() > 0;
            }
        }, ProgressType.BAR, this, 86, 38));

        // 能量 tab：Mek 固定 (x=-26, y=137, 26, 26)，与侧配/传输配置同处左列不冲突。
        // 文案沿用旧屏的两行（存量/上限 + 每 tick 耗量），与 Mek 自己的组装口径不同，
        // 所以走 (IGuiWrapper, IInfoHandler) 这个构造器而不是吃能量容器的那个。
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
                Component.translatable("gui.mekck.energy_stored", menu.getEnergy(), menu.getMaxEnergy()),
                Component.translatable("gui.mekck.energy_per_tick", GrillBlockEntity.ENERGY_PER_TICK))));

        // ME 下单 tab（右列 y=34，理由见 ORDER_TAB_Y）。
        // 面板本体已从「屏幕手绘覆盖层」迁进 Mek 虚拟窗口（NetworkOrderWindow），
        // 本机数据源在窗口创建时注入（见 localOrderSource()）。
        orderTab = addRenderableWidget(new NetworkOrderTab(this, menu.getBlockPos(),
                imageWidth, ORDER_TAB_Y, false, localOrderSource(), () -> orderTab));

        // 自动补料 / 网络拉料两枚 tab **最后注册**：Mek 的 GuiMekanism#mouseClicked 对
        // children() 倒序遍历、命中即返回，即越晚注册命中优先（tab 才能压过同区域的虚拟槽）。
        if (NetworkPullButton.isVisible()) {
            for (var tab : NetworkPullButton.register(this, menu.getBlockPos())) {
                addRenderableWidget(tab);
            }
        }
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Mek 的 GuiMekanism.renderLabels 覆写了原版 AbstractContainerScreen.renderLabels
        // 且不调 super，所以机器名与「Inventory」两行不会自动出现，必须自己补。
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 8, inventoryLabelY, titleTextColor());
        // 温度系统：显示机身温度（摄氏度）。摆在能源槽下方、背包标签上方的空档（y 72~83）。
        guiGraphics.drawString(font, String.format("温度: %.1f℃", menu.getTemperature() / 100.0), 8, 74, 0xFFFF5555);
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        bigStackHud.shrink(menu.slots);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        bigStackHud.restore();

        // 大堆叠数量：原版只画到 64，这里把真实数量（可到 21 亿）画在槽位右下角。
        for (Slot slot : menu.slots) {
            if (slot.isActive() && slot.hasItem()) {
                int count = slot.getItem().getCount();
                String formatted = CountFormat.compact(count);
                if (formatted != null) {
                    int sx = this.leftPos + slot.x;
                    int sy = this.topPos + slot.y;
                    guiGraphics.pose().pushPose();
                    guiGraphics.pose().translate(sx + 16, sy + 16, 300);
                    guiGraphics.pose().scale(0.5f, 0.5f, 1.0f);
                    guiGraphics.drawString(this.font, formatted,
                            -this.font.width(formatted), -8, 0xFFFFFF, true);
                    guiGraphics.pose().popPose();
                }
            }
        }

        // ME 下单面板已迁进 Mek 虚拟窗口（NetworkOrderWindow），由 GuiMekanism#render
        // 在 windows 通道里绘制，本屏不再手绘覆盖层。
    }

    @Override
    public NetworkOrderPanel networkOrderPanel() {
        // 窗口开着 ⇒ 返回窗口里的面板；关着 ⇒ null（回包丢弃，不再灌进已销毁的面板）。
        return orderTab == null ? null : orderTab.panel();
    }

    /**
     * 本机一侧：配方 / 可做份数按机器输入槽里的材料算，下单走通用订单包。
     *
     * <p>迁到窗口创建时注入（{@link NetworkOrderTab#createWindow()} → {@code setLocalSource}）：
     * 面板随窗口每次打开重建，数据源必须跟着重建，否则新面板的本机模式是空的。</p>
     */
    private NetworkOrderPanel.LocalSource localOrderSource() {
        return new NetworkOrderPanel.LocalSource() {
            @Override
            public List<Recipe<?>> recipes() {
                return menu.getMachine().getAvailableRecipes();
            }

            @Override
            public int maxCraftable(Recipe<?> recipe) {
                return menu.getMachine().getMaxConsumableCountForOrder(recipe);
            }

            @Override
            public void order(Recipe<?> recipe, int quantity) {
                ModMessages.sendToServer(new cn.ism.mekck.network.OrderRecipePacket(
                        menu.getBlockPos(), recipe.getId(), quantity));
            }
        };
    }
}
