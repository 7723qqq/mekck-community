package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.menu.CuttingMachineFactoryMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.network.AutoDistributePacket;
import cn.ism.mekck.network.AutoProcessListRequestPacket;
import cn.ism.mekck.network.AutoProcessTogglePacket;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkOrderPacket;
import cn.ism.mekck.util.AE2Compat;
import cn.ism.mekck.network.RedstoneControlPacket;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import mekanism.api.text.EnumColor;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.element.bar.GuiBar.IBarInfoHandler;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.button.ColorButton;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiSlot;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.client.gui.element.GuiTexturedElement;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class CuttingMachineFactoryScreen extends GuiMekanism<CuttingMachineFactoryMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    private final CuttingMachineFactoryTier tier;
    // ME 自动处理界面
    private boolean autoProcessMode = false;
    private boolean autoDataRequested = false;
    private List<String> autoAvailableIds = List.of();
    private List<String> autoSelectedIds = List.of();
    /** 覆盖层列表项的按钮池：池大小 = max(面板容量, 当前条目数)，见 {@link #ensureAutoRowPool(int)}。 */
    private final List<ColorButton> autoRowButtons = new ArrayList<>();
    /** 本帧排好的「已选 ∪ 可选」列表：按钮的取色 / 标签 / 点击都按行号从这里取。 */
    private final List<String> autoShownIds = new ArrayList<>();
    /** 列表行布局（取自原手绘版，像素位置勿改）：行 i 的矩形 = (AUTO_ROW_X, AUTO_ROW_Y + i*AUTO_ROW_GAP, imageWidth-AUTO_ROW_W_INSET, AUTO_ROW_H)。 */
    private static final int AUTO_ROW_X = 16;
    private static final int AUTO_ROW_Y = 37;
    private static final int AUTO_ROW_W_INSET = 32;
    private static final int AUTO_ROW_H = 19;
    private static final int AUTO_ROW_GAP = 20;
    /** 面板底 = imageHeight - AUTO_PANEL_BOTTOM_INSET（沿用旧手绘版的行容量算法）。 */
    private static final int AUTO_PANEL_BOTTOM_INSET = 10;
    /** 滚动条宽（贴在面板右内缘，只指示位置，滚动靠滚轮）。 */
    private static final int AUTO_SCROLLBAR_W = 2;
    /** 面板内能完整放下的行数 = 滚动窗口大小。 */
    private int autoRowCapacity = 1;
    /** 列表滚动偏移（0..max(0, 条目数-容量)）：滚轮改它，每帧把「池槽位 → 条目」重绑一遍。 */
    private int autoRowScroll = 0;

    // Mekanism-style tab positions
    // Left side: config tab and auto-distribute button
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;
    /** 「ME 下单」面板开关（本屏没有本机下单列表 ⇒ 面板恒为 ME 模式）。 */
    private boolean orderMode = false;
    private static final int ORDER_TAB_Y = 90; // 侧配 tab(6) / 自动分配(34) / ME 自动处理(62) 之下
    private static final int ORDER_PANEL_LEFT = 10;
    private static final int ORDER_PANEL_TOP = 10;
    /** 下单 tab 图标：自绘「清单 + 向下箭头」（原来借用的 Mekanism sorting.png 像音符、且语义不符）。 */
    private static final ResourceLocation ORDER_TEXTURE = MachineTabIcons.ORDER;
    private final NetworkOrderPanel mePanel = new NetworkOrderPanel(true, false);
    private static final int AUTO_DIST_Y = 34; // below config tab (28px spacing like Mekanism)
    private static final int AUTO_PROCESS_Y = AUTO_DIST_Y + 28; // ME 自动处理 tab（自动分配按钮下方）
    private static final int TAB_OUTER_W = 24;
    private static final int TAB_OUTER_H = 24; // slightly smaller than Mekanism's 26px config tab

    // Auto-distribute button (same size as config tab)
    private static final int AUTO_DIST_W = 24;
    private static final int AUTO_DIST_H = 24;

    // Icon size and offset within the tab (16x16 icon centered in 24x24 tab)
    private static final int TAB_ICON_SIZE = 16;
    private static final int TAB_ICON_OFFSET = 4; // (24 - 16) / 2

    // Right side: upgrade tab (at top-right corner, matching Mekanism's GuiUpgradeWindowTab)
    private static final int UPGRADE_TAB_Y = 6;

    // Redstone control tab (right side, identical position to Mekanism's factory: x = imageWidth, y = 137)
    private static final int REDSTONE_TAB_SIZE = 26; // outer tab size (Mekanism tabs are 26x26)
    private static final int REDSTONE_TAB_INNER = 18; // inner button/icon size
    private static final int REDSTONE_TINT = 0xFFC9071F; // Mekanism SpecialColors.TAB_REDSTONE_CONTROL

    // Redstone control icon textures (Mekanism)
    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    // Mekanism textures
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");
    private static final ResourceLocation SORTING_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "sorting.png");

    private final boolean hasStackUpgrade;

    public CuttingMachineFactoryScreen(CuttingMachineFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.tier = menu.getTier();
        this.hasStackUpgrade = menu.hasStackUpgrade();
        int cols = (int) Math.ceil(Math.sqrt(tier.processes));
        int rows = (int) Math.ceil((double) tier.processes / cols);
        int extraHeight = Math.max(0, (rows - 2) * 18);
        // Calculate width: input grid + gap + output slots (same square grid as input)
        int inputGridRight = 38 + cols * 18;
        int outputRight = inputGridRight + GAP_BETWEEN + cols * 18;
        imageWidth = outputRight + 20;
        imageHeight = 184 + extraHeight;
        // 滚动窗口 = 面板内能完整放下的行数（BASIC 6 行，等级越高越多）。
        // 池另按条目数扩容（ensureAutoRowPool），条目多于窗口时滚轮翻页 —— 不再有「静默丢行」。
        autoRowCapacity = Math.max(1,
                (imageHeight - AUTO_PANEL_BOTTOM_INSET - AUTO_ROW_Y - AUTO_ROW_H) / AUTO_ROW_GAP + 1);
        inventoryLabelY = 89 + extraHeight;
        dynamicSlots = true;
    }

    private static final int GAP_BETWEEN = 30; // gap between input and output grids (> SMALL_RIGHT 28px)

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        int inputSlots = tier.processes;
        int cols = (int) Math.ceil(Math.sqrt(inputSlots));
        int rows = (int) Math.ceil((double) inputSlots / cols);
        int inputStartX = 38;
        int inputStartY = 41;

        // Input slots with INPUT type (tightly packed grid, 18px spacing)
        int inputGridWidth = cols * 18;
        for (int i = 0; i < inputSlots; i++) {
            int col = i % cols;
            int row = i / cols;
            // Slot texture is 18x18, so position at -1 to align with the 18px grid
            GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.INPUT, this, inputStartX + col * 18 - 1, inputStartY + row * 18 - 1);
            if (menu.slots.get(i) instanceof IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
        }

        // Output slots (same square grid layout as input, with larger gap)
        int outputStartX = inputStartX + inputGridWidth + GAP_BETWEEN;
        int outputStartY = inputStartY - 1;
        for (int i = 0; i < inputSlots; i++) {
            int col = i % cols;
            int row = i / cols;
            GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.OUTPUT, this, outputStartX + col * 18, outputStartY + row * 18);
            int slotIdx = inputSlots + i;
            if (menu.slots.get(slotIdx) instanceof IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
        }

        // Single progress bar (Mekanism-style SMALL_RIGHT arrow)
        int progressX = inputStartX + inputGridWidth + (GAP_BETWEEN - 28) / 2; // SMALL_RIGHT is 28px wide
        int progressY = inputStartY + rows * 18 / 2 - 4; // SMALL_RIGHT is 8px tall
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgress() / 24.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgress() > 0;
            }
        }, ProgressType.SMALL_RIGHT, this, progressX, progressY));

        // Energy bar (Mekanism-style vertical power bar, positioned on the right side)
        int energyBarX = imageWidth - 12;
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                      menu.getEnergy(), menu.getEnergyCapacity());
            }

            @Override
            public double getLevel() {
                // Use original tier capacity so the bar shows correct level when energy is at original capacity
                int capacity = tier.energyCapacity;
                return capacity == 0 ? 0 : (double) menu.getEnergy() / capacity;
            }
        }, energyBarX, 22));

        // Energy info tab (bottom-left corner, using Mekanism's texture)
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
              Component.translatable("gui.mekck.energy_stored",
                    menu.getEnergy(), menu.getEnergyCapacity()),
              Component.translatable("gui.mekck.energy_per_tick",
                    tier.energyPerTick)
        )));

        // Power slot (energy items: energy cube / tablet / redstone), at Mekanism position (GUI = container -1 = 6, 12)
        int powerSlotIndex = inputSlots * 2 + (hasStackUpgrade ? 3 : 2);
        if (powerSlotIndex < menu.slots.size()) {
            GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
            powerVs.with(SlotOverlay.POWER);
            if (menu.slots.get(powerSlotIndex) instanceof IVirtualSlot ivs) {
                powerVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(powerVs);
        }

        addTabElements();

        // 侧栏 tab 必须最后注册：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，越晚注册命中优先。
        if (cn.ism.mekck.client.NetworkPullButton.isVisible()) {
            for (var tab : cn.ism.mekck.client.NetworkPullButton.register(this,
                    cn.ism.mekck.client.NetworkPullButton.getX(imageWidth),
                    cn.ism.mekck.client.NetworkPullButton.getY(ORDER_TAB_Y),
                    menu.getBlockPos())) {
                addRenderableWidget(tab);
            }
        }

        // ME 自动处理覆盖层的列表项按钮**最后注册**：Mek 的 GuiMekanism#mouseClicked 对
        // children() 倒序遍历、命中即返回，即**越晚注册命中优先**。列表行与输入/输出虚拟槽在 y 上
        // 重叠（行 i = y(37+20i)..(56+20i)，输入/输出槽第 i 行 = y(40+18i)..(58+18i)），
        // 排在槽位之后才能像旧 handleAutoProcessClick（在 super.mouseClicked 之前拦截）那样压过槽位点击。
        // 默认隐藏，由 syncAutoProcessOverlayButtons() 同步。
        initAutoProcessRowButtons();
    }

    /**
     * 侧栏 6 个 tab 统一走 Mek {@link MekCkTabElement}（继承 {@code GuiInsetElement}）：
     * 三层绘制与旧手绘逐参数一致，tooltip 改走 {@code GuiMekanism#renderLabels} 的元素通道
     * —— 那是渲染管线最后一层，结构上不会再被槽位盖住。
     * 旧实现在 {@code renderBg()} 里直绘 tooltip + 在 {@code mouseClicked} 里手算命中矩形，现已一并移除。
     */
    private void addTabElements() {
        // ── 左列（4 个）──
        addRenderableWidget(MekCkTabElement.left(this, CONFIG_TEXTURE, TAB_X, CONFIG_TAB_Y,
                () -> false,
                () -> List.of(Component.translatable("tooltip.mekck.side_config")),
                this::openSideConfigWindow));

        addRenderableWidget(MekCkTabElement.left(this, SORTING_TEXTURE, TAB_X, AUTO_DIST_Y,
                () -> menu.getAutoDistribute(),
                () -> List.of(Component.translatable("tooltip.mekck.auto_sort")),
                () -> ModMessages.sendToServer(new AutoDistributePacket(menu.getBlockPos()))));

        addRenderableWidget(MekCkTabElement.left(this, SORTING_TEXTURE, TAB_X, AUTO_PROCESS_Y,
                () -> autoProcessMode,
                () -> List.of(Component.translatable("tooltip.mekck.auto_process")),
                this::toggleAutoProcessMode));

        addRenderableWidget(MekCkTabElement.left(this, ORDER_TEXTURE, TAB_X, ORDER_TAB_Y,
                () -> orderMode,
                () -> List.of(Component.translatable("tooltip.mekck.order_panel")),
                this::toggleOrderMode));

        // ── 右列（2 个）──
        addRenderableWidget(MekCkTabElement.right(this, UPGRADE_TEXTURE, imageWidth, UPGRADE_TAB_Y,
                () -> false,
                () -> List.of(Component.translatable("tooltip.mekck.upgrade")),
                () -> {
                    openUpgradeWindow();
                }));

        addRenderableWidget(redstoneTab());
    }

    /**
     * 红石控制 tab：Mek 原生 26×26/18×18 规格、+3/+4 偏移、holder 染红、图标随三态切换，
     * PULSE 态额外叠一层脉冲动画。左键下一档 / 右键上一档。
     */
    private MekCkTabElement redstoneTab() {
        MekCkTabElement tab = new MekCkTabElement(this, REDSTONE_DISABLED,
                imageWidth, imageHeight - REDSTONE_TAB_SIZE, false,
                REDSTONE_TAB_SIZE, REDSTONE_TAB_INNER,
                () -> false,
                mekanism.client.SpecialColors.TAB_REDSTONE_CONTROL,
                () -> List.of(Component.translatable(
                        "gui.mekck.redstone_control."
                                + RedstoneControl.byOrdinal(menu.getRedstoneControl()).name().toLowerCase())),
                () -> {
                },
                null) {
            /** 右键 = 上一档（Mek 的 GuiRedstoneControlTab 同样覆写此项以放行右键）。 */
            @Override
            public boolean isValidClickButton(int button) {
                return button == 0 || button == 1;
            }

            @Override
            public void onClick(double mouseX, double mouseY, int button) {
                ModMessages.sendToServer(new RedstoneControlPacket(menu.getBlockPos(), button == 0 ? 1 : -1));
            }
        };
        // 匿名类体必须紧跟构造括号；引用 tab 自身的 lambda 只能在实例化之后挂上去
        tab.buttonOffset(3, 4);
        tab.dynamicOverlay(() -> switch (RedstoneControl.byOrdinal(menu.getRedstoneControl())) {
            case HIGH -> REDSTONE_HIGH;
            case LOW -> REDSTONE_LOW;
            default -> REDSTONE_DISABLED;
        });
        tab.overlayLayer(gg -> {
            if (RedstoneControl.byOrdinal(menu.getRedstoneControl()) == RedstoneControl.PULSE) {
                tab.drawInnerOverlay(gg, MekanismRenderer.redstonePulse);
            }
        });
        return tab;
    }

    /** ME 自动处理面板开关（原在 mouseClicked 内联，迁出为 tab 动作）。 */
    private void toggleAutoProcessMode() {
        autoProcessMode = !autoProcessMode;
        if (autoProcessMode) {
            autoDataRequested = false;
            ModMessages.sendToServer(new AutoProcessListRequestPacket(menu.getBlockPos()));
        }
    }

    /** ME 下单面板开关（原在 mouseClicked 内联，迁出为 tab 动作）。 */
    private void toggleOrderMode() {
        orderMode = !orderMode;
        if (!orderMode) {
            mePanel.onClosed();
        }
    }

    private static String formatItemCount(int count) {
        // 统一走 CountFormat（含十亿档；21 亿不再显示成 2147.5M）
        return cn.ism.mekck.client.CountFormat.compact(count);
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 20, inventoryLabelY, titleTextColor());
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        int x = leftPos;
        int y = topPos;

        // 侧栏 6 个 tab 已全部迁到 addTabElements()（MekCkTabElement），renderBg 不再手绘；
        // AE2 网络拉料两枚按钮也已在 addGuiElements() 末尾由 NetworkPullButton.register(...)
        // 注册为 Mek 原生元素。

        // ME 自动处理覆盖层的面板底板。必须画在 widget 层之前（renderBg 早于 renderables），
        // 否则会把已注册成 Mek 原生按钮的列表项整块盖住。
        if (autoProcessMode) {
            renderAutoProcessPanelBg(guiGraphics, x, y);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Temporarily set item counts to 1 for stacks with large counts,
        // so the default count text is not rendered (count > 1 triggers text).
        // Items are rendered at their correct positions; only the text is suppressed.
        bigStackHud.shrink(menu.slots);

        // ME 自动处理覆盖层按需出现：每帧同步列表项按钮的显隐 / 标签 / 取色，
        // 否则会一直显示在主界面上（与其它屏的 syncXxxButtons 同形）。
        syncAutoProcessOverlayButtons();

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Restore original counts
        bigStackHud.restore();

        // Draw formatted counts for large item stacks (smaller font via scale)
        for (Slot slot : menu.slots) {
            if (slot.isActive() && slot.hasItem()) {
                int count = slot.getItem().getCount();
                String formatted = formatItemCount(count);
                if (formatted != null) {
                    int sx = this.leftPos + slot.x;
                    int sy = this.topPos + slot.y;
                    // Draw formatted text with smaller font (at z=300)
                    guiGraphics.pose().pushPose();
                    guiGraphics.pose().translate(sx + 16, sy + 16, 300);
                    guiGraphics.pose().scale(0.5f, 0.5f, 1.0f);
                    guiGraphics.drawString(this.font, formatted,
                            -this.font.width(formatted), -8, 0xFFFFFF, true);
                    guiGraphics.pose().popPose();
                }
            }
        }

        int x = leftPos;
        int y = topPos;

        // ME 自动处理 overlay：面板底板/标题已移到 renderBg()，列表项已是 Mek 原生按钮（widget 层），
        // 这里只在按钮之上补画图标与勾选框。
        if (autoProcessMode) {
            renderAutoProcessRowDecorations(guiGraphics, x, y);
        }

        // 6 个侧栏 tab 的 tooltip 已迁到 MekCkTabElement#renderToolTip，
        // 由 GuiMekanism#renderLabels 在渲染管线最后一层统一派发。

        // ME 下单面板最后画（在 GUI 文字 / 槽位之上；本屏没有本机下单列表 ⇒ 只有 ME 一侧）
        if (orderMode) {
            mePanel.bind(menu.getBlockPos());
            mePanel.render(guiGraphics, font, leftPos + ORDER_PANEL_LEFT, topPos + ORDER_PANEL_TOP,
                    imageWidth - ORDER_PANEL_LEFT * 2, imageHeight - ORDER_PANEL_TOP * 2,
                    mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (orderMode && mePanel.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (orderMode && mePanel.charTyped(codePoint, modifiers)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (orderMode) return mePanel.mouseScrolled(delta);
        // ME 自动处理列表：条目多于面板容量时滚轮翻页（面板外不接，滚轮照旧交给底层）。
        if (autoProcessMode && scrollAutoProcess(mouseX, mouseY, delta)) return true;
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public NetworkOrderPanel networkOrderPanel() {
        return orderMode ? mePanel : null;
    }

    private void openSideConfigWindow() {
        if (getWindows().stream().noneMatch(w -> w instanceof GuiMekCkSideConfiguration)) {
            addWindow(new GuiMekCkSideConfiguration(this, (ISideConfigurableMenu) menu, this::getMachineFacing));
        }
    }

    private void openUpgradeWindow() {
        if (getWindows().stream().noneMatch(w -> w instanceof GuiUpgradeWindow)) {
            addWindow(new GuiUpgradeWindow(this, (IUpgradeMenu) menu));
        }
    }

    private Direction getMachineFacing() {
        if (minecraft != null && minecraft.level != null) {
            BlockState state = minecraft.level.getBlockState(menu.getBlockPos());
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            }
        }
        return Direction.NORTH;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 6 个侧栏 tab 的点击已交给 MekCkTabElement#onClick —— 它们是 renderable widget，
        // 由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        if (button == 0) {
            // ME 自动处理覆盖层：列表项已是 Mek 原生按钮，点击由 Mek 原生命中测试处理。
            // 面板内吞掉左键（与旧 handleAutoProcessClick 一致：autoProcessMode 下左键必被消费，不落到槽位）；
            // 面板外则关闭覆盖层 —— 池化改造一度把这个「点外面关闭」吞没了，只能靠 tab 切，已补回。
            if (autoProcessMode) {
                if (!isMouseOverAutoProcessPanel(mouseX, mouseY)) {
                    autoProcessMode = false;
                    return true;
                }
                super.mouseClicked(mouseX, mouseY, button);
                return true;
            }
        }
        // ME 下单：面板点击（面板外点击关闭；左键）。tab 切换已交给 MekCkTabElement。
        if (button == 0) {
            if (orderMode) {
                int panelX = leftPos + ORDER_PANEL_LEFT;
                int panelY = topPos + ORDER_PANEL_TOP;
                int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
                int panelH = imageHeight - ORDER_PANEL_TOP * 2;
                mePanel.bind(menu.getBlockPos());
                if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH) {
                    return mePanel.mouseClicked(mouseX, mouseY, button, panelX, panelY, panelW, panelH,
                            (recipeId, qty) -> ModMessages.sendToServer(new NetworkOrderPacket(
                                    menu.getBlockPos(), recipeId.toString(), qty)));
                }
                orderMode = false;
                mePanel.onClosed();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public void setAutoProcessData(List<String> available, List<String> selected) {
        this.autoAvailableIds = new ArrayList<>(available);
        this.autoSelectedIds = new ArrayList<>(selected);
        this.autoDataRequested = true;
    }

    /**
     * 注册 ME 自动处理面板的列表项按钮为 MekCkButtons 真 widget
     * （取代原手绘的 fill 边框 + fill 底色 + 勾选框 + 图标 + drawString）。
     * 坐标/尺寸/行距全部取自原手绘版，像素位置不变；右键不挂回调（旧版只在 button==0 时处理）。
     * 标签交给 Mek 的按钮文字渲染器（居中），每帧由 {@link #syncAutoProcessOverlayButtons()} 刷新。
     * <p><b>池化按钮的 resize 防护</b>：本方法会随 {@code init()} 重跑，先把上一批池 widget 从
     * {@code children()} / {@code renderables} 里摘掉再重建。与 {@code MekCkTabElement} 那类固定按钮不同，
     * 池化按钮一旦重复注册就会在命中测试里出现两份（点一次发两包），所以这里必须显式摘除而不是只清字段。</p>
     */
    private void initAutoProcessRowButtons() {
        for (ColorButton old : autoRowButtons) {
            children().remove(old);
            renderables.remove(old);
        }
        autoRowButtons.clear();
        for (int i = 0; i < autoRowCapacity; i++) {
            ColorButton button = createAutoRowButton(i);
            addRenderableWidget(button);
            autoRowButtons.add(button);
        }
    }

    /** 单个列表项按钮：坐标 / 尺寸 / 左键回调三者都由「池槽位号」决定（数据下标经 {@link #autoRowDataIndex} 换算）。 */
    private ColorButton createAutoRowButton(int row) {
        ColorButton button = MekCkButtons.color(this,
                AUTO_ROW_X, AUTO_ROW_Y + row * AUTO_ROW_GAP,
                imageWidth - AUTO_ROW_W_INSET, AUTO_ROW_H,
                () -> autoRowColor(row),
                Component.empty(),
                () -> toggleAutoProcessRow(row),
                null);
        MekCkButtons.setShown(button, false);
        return button;
    }

    /**
     * 池按实际条目数扩容：{@code max(面板容量, 条目数)}。
     * 条目多于面板容量时，第 {@code i} 个池槽位显示第 {@code i+autoRowScroll} 个条目（滚轮翻页），
     * 所以<b>没有任何条目会因为池小而既不渲染也不可点</b>——这是原实现的静默丢行。
     * <p>扩容出来的尾部按钮（槽位号 ≥ 容量）每帧被 {@link #syncAutoProcessOverlayButtons()} 隐藏，
     * 它们只是「池不小于条目数」这条约束的兜底，正常不参与显示。</p>
     */
    private void ensureAutoRowPool(int entries) {
        int want = Math.max(autoRowCapacity, entries);
        for (int i = autoRowButtons.size(); i < want; i++) {
            ColorButton button = createAutoRowButton(i);
            addRenderableWidget(button);
            autoRowButtons.add(button);
        }
    }

    /**
     * 每帧同步覆盖层列表项：显隐随 autoProcessMode，行数 / 标签 / 取色随「已选 ∪ 可选」列表与滚动偏移变化。
     * 必须在 super.render() 之前调用，否则本帧画出的还是上一帧的标签。
     */
    private void syncAutoProcessOverlayButtons() {
        refreshAutoShownIds();
        MekCkButtons.setShown(autoRowButtons, autoProcessMode);
        if (!autoProcessMode) {
            return;
        }
        ensureAutoRowPool(autoShownIds.size());
        autoRowScroll = Math.max(0, Math.min(autoRowScroll, autoRowMaxScroll()));
        for (int i = 0; i < autoRowButtons.size(); i++) {
            int data = autoRowDataIndex(i);
            boolean hasRow = i < autoRowCapacity && data < autoShownIds.size();
            MekCkButtons.setShown(autoRowButtons.get(i), hasRow);
            if (hasRow) {
                autoRowButtons.get(i).setMessage(autoRowLabel(i));
            }
        }
    }

    /** 池槽位号 → 条目下标（滚动窗口的偏移在这里统一收口）。 */
    private int autoRowDataIndex(int row) {
        return row + autoRowScroll;
    }

    /** 最大滚动偏移 = 条目数 - 窗口行数（不小于 0）。 */
    private int autoRowMaxScroll() {
        return Math.max(0, autoShownIds.size() - autoRowCapacity);
    }

    /** 「已选 ∪ 可选」的有序去重列表（与旧 renderAutoProcessMode / handleAutoProcessClick 的 shown 逐字等价）。 */
    private void refreshAutoShownIds() {
        autoShownIds.clear();
        autoShownIds.addAll(autoSelectedIds);
        for (String id : autoAvailableIds) {
            if (!autoShownIds.contains(id)) {
                autoShownIds.add(id);
            }
        }
    }

    /**
     * 列表项取色：已选=深蓝、未选=灰（取自 {@code GuiMekCkSideConfiguration.colorForMode()} 的
     * PUSH_OUTPUT / NONE 两档）；hover 交给 Mek 原生按钮，不再手算。
     */
    private EnumColor autoRowColor(int row) {
        int data = autoRowDataIndex(row);
        return data < autoShownIds.size() && autoSelectedIds.contains(autoShownIds.get(data))
                ? EnumColor.DARK_BLUE : EnumColor.GRAY;
    }

    /** 列表项左键：切换该材料的 ME 自动处理（与旧 handleAutoProcessClick 的命中分支语义一致）。 */
    private void toggleAutoProcessRow(int row) {
        int data = autoRowDataIndex(row);
        if (data < autoShownIds.size()) {
            ModMessages.sendToServer(new AutoProcessTogglePacket(menu.getBlockPos(), autoShownIds.get(data)));
        }
    }

    /** 列表项标签：物品名；取不到物品时退回旧版的资源 id 字符串（文案一字未改）。 */
    private Component autoRowLabel(int row) {
        int data = autoRowDataIndex(row);
        if (data >= autoShownIds.size()) {
            return Component.empty();
        }
        String id = autoShownIds.get(data);
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
        return item != null ? new ItemStack(item).getHoverName() : Component.literal(id);
    }

    /**
     * ME 自动处理面板的底板：面板底图 + 边框 + 标题 + 分隔线 + 加载/空列表文案。
     * 原先整套画在 render()（widget 层之上），列表项改成原生按钮后必须下移到 renderBg() 末尾，
     * 否则底板会把这些按钮整块盖住。
     */
    private void renderAutoProcessPanelBg(GuiGraphics guiGraphics, int x, int y) {
        int panelX = x + 10;
        int panelY = y + 10;
        int panelW = imageWidth - 20;
        int panelH = imageHeight - 20;
        GuiUtils.renderBackgroundTexture(guiGraphics, MekanismUtils.getResource(ResourceType.GUI, "base.png"),
                4, 4, panelX, panelY, panelW, panelH, 256, 256);
        GuiUtils.drawOutline(guiGraphics, panelX, panelY, panelW, panelH, 0xFF3E6E91);
        guiGraphics.drawString(font, "ME \u81EA\u52A8\u5904\u7406", panelX + 8, panelY + 6, 0xFFFFFFFF);
        guiGraphics.fill(panelX + 4, panelY + 21, panelX + panelW - 4, panelY + 22, 0xFF3E6E91);

        if (!autoDataRequested) {
            guiGraphics.drawString(font, "\u6B63\u5728\u83B7\u53D6 ME \u7F51\u7EDC\u6570\u636E...",
                    panelX + 8, panelY + 30, 0xFFAAAAAA);
        } else if (autoShownIds.isEmpty()) {
            guiGraphics.drawString(font, "ME \u7F51\u7EDC\u4E2D\u65E0\u53EF\u5904\u7406\u6750\u6599",
                    panelX + 8, panelY + 35, 0xFFAAAAAA);
        }
    }

    /**
     * 列表项按钮之上的装饰层：勾选框 + 物品图标（原生按钮画不了这两种，故仍在 widget 层之后补画），
     * 外加列表溢出时的滚动指示条。行名已改由 MekCkButtons#color 的标签居中渲染，不再在此叠字。
     * 坐标与旧版逐像素一致；勾选框 / 图标只画当前滚动窗口内的行。
     */
    private void renderAutoProcessRowDecorations(GuiGraphics guiGraphics, int x, int y) {
        if (!autoDataRequested) {
            return;
        }
        int rows = Math.min(autoRowCapacity, autoShownIds.size() - autoRowScroll);
        for (int row = 0; row < rows; row++) {
            String id = autoShownIds.get(row + autoRowScroll);
            int rowY = y + AUTO_ROW_Y + row * AUTO_ROW_GAP;
            // 复选框
            guiGraphics.fill(x + 20, rowY + 3, x + 26, rowY + 9,
                    autoSelectedIds.contains(id) ? 0xFF44AA44 : 0xFF333333);
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
            if (item != null) {
                guiGraphics.renderItem(new ItemStack(item), x + 30, rowY + 2);
            }
        }
        renderAutoProcessScrollbar(guiGraphics, x, y);
    }

    /**
     * 列表滚动指示条：轨道高 = 滚动窗口高，滑块高按 窗口/条目 比例，位置随 autoRowScroll。
     * 画在按钮右内缘（列表区右缘与面板边框之间的 4px 空档），不遮任何一行。
     * <p>仅作位置指示：拖动滑块未实现，滚动一律走 {@link #scrollAutoProcess} 的滚轮。</p>
     */
    private void renderAutoProcessScrollbar(GuiGraphics guiGraphics, int x, int y) {
        int maxScroll = autoRowMaxScroll();
        if (maxScroll <= 0) {
            return;
        }
        int trackX = x + imageWidth - AUTO_PANEL_BOTTOM_INSET - AUTO_SCROLLBAR_W;
        int trackY = y + AUTO_ROW_Y;
        int trackH = autoRowCapacity * AUTO_ROW_GAP;
        guiGraphics.fill(trackX, trackY, trackX + AUTO_SCROLLBAR_W, trackY + trackH, 0xFF2B2B2B);
        int thumbH = Math.max(4, trackH * autoRowCapacity / autoShownIds.size());
        int thumbY = trackY + (trackH - thumbH) * autoRowScroll / maxScroll;
        guiGraphics.fill(trackX, thumbY, trackX + AUTO_SCROLLBAR_W, thumbY + thumbH, 0xFF8C8C8C);
    }

    /**
     * ME 自动处理覆盖层面板的矩形命中（绝对屏幕坐标）。
     * 内缩沿用侧配/下单面板的 ORDER_PANEL_LEFT/TOP（都是 10 ⇒ 宽高 imageWidth/Height-20），
     * 与旧手绘版 handleAutoProcessClick 的判定逐像素一致。
     */
    private boolean isMouseOverAutoProcessPanel(double mouseX, double mouseY) {
        int panelX = leftPos + ORDER_PANEL_LEFT;
        int panelY = topPos + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;
        return mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH;
    }

    /** 滚轮翻页（仅当悬停在面板内且确有溢出行）；返回是否吃下这次滚动。 */
    private boolean scrollAutoProcess(double mouseX, double mouseY, double delta) {
        refreshAutoShownIds();
        int maxScroll = autoRowMaxScroll();
        if (maxScroll <= 0 || !isMouseOverAutoProcessPanel(mouseX, mouseY)) {
            return false;
        }
        int step = delta > 0 ? -1 : 1; // 向上滚看更早的行
        autoRowScroll = Math.max(0, Math.min(maxScroll, autoRowScroll + step));
        return true;
    }
}