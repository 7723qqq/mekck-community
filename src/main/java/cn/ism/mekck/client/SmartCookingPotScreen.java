package cn.ism.mekck.client;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.blockentity.SmartCookingPotBlockEntity;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.SmartCookingPotMenu;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.OrderRecipePacket;
import cn.ism.mekck.network.RedstoneControlPacket;
import java.util.List;
import java.util.function.BooleanSupplier;
import mekanism.client.SpecialColors;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.bar.GuiBar.IBarInfoHandler;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.render.MekanismRenderer;
import mekanism.client.render.lib.ColorAtlas.ColorRegistryObject;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class SmartCookingPotScreen extends GuiMekanism<SmartCookingPotMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    private static final int AUTO_DIST_Y = 34;

    // Redstone control tab (right side, identical position to Mekanism's factory: x = imageWidth, y = 137)
    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;

    // Redstone control icon textures (Mekanism)
    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    /**
     * 「下单」标签页 —— 点开 {@link NetworkOrderWindow}。
     *
     * <p>必须留引用：{@code GuiWindowCreatorTab} 关闭窗口时靠 {@code elementSupplier.get()}
     * 把同一实例重新激活，宿主屏幕也靠它取「正在显示的那一个」面板。</p>
     */
    private NetworkOrderTab orderTab;

    // ================== 存储区「单列纵向滚动」（拍板 F1·方案 5） ==================
    /** 存储列相对 GUI 左缘的 x（与左侧 tab 同列，宽 ~24px，塞进缩放 4 的左边距）。 */
    private static final int STORAGE_COL_X = -24;
    /** 存储列顶部 y（在左侧 config(y6)/order(y34) tab 堆下方）。 */
    private static final int STORAGE_COL_TOP = 64;
    /** 单个槽位间距（原版 18px）。 */
    private static final int STORAGE_SLOT_PITCH = 18;
    /** 81 个存储槽的 GuiVirtualSlot widget（顺序与菜单槽索引一致），滚动靠 move() 重摆。 */
    private final java.util.List<GuiVirtualSlot> storageWidgets = new java.util.ArrayList<>();
    /** 当前滚动偏移（以“行”计，0..storageMaxScroll）——纯客户端字段，无需同步。 */
    private int storageScrollOffset = 0;
    /** 单列可视行数（由 imageHeight 算出）。 */
    private int storageVisibleRows = 9;
    /** 最大滚动偏移 = STORAGE_SLOT_COUNT - 可视行数（不小于 0）。 */
    private int storageMaxScroll = 0;

    // Cancel button bounds for order progress display
    private int orderCancelBtnX = -1;
    private int orderCancelBtnY = -1;
    private int orderCancelBtnW = 30;
    private int orderCancelBtnH = 14;
    
    // Mekanism-style tab positions
    // Left side: config tab
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;

    // Right side: upgrade tab (at top-right corner, matching Mekanism's GuiUpgradeWindowTab)
    private static final int UPGRADE_TAB_Y = 6;

    // Mekanism textures
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");

    // Slot layout constants
    private static final int INPUT_START_X = 38;
    private static final int INPUT_START_Y = 41;
    private static final int INPUT_COLS = 3;
    private static final int INPUT_SPACING = 18;
    private static final int OUTPUT_X = 130;
    private static final int OUTPUT_Y = 41;
    private static final int RETURN_Y = 59;

    // Fluid gauge layout (3 vertical fluid bars, inside the GUI box below the input grid)
    private static final int FLUID_GAUGE_X = 40;
    private static final int FLUID_GAUGE_Y = 88;
    private static final int FLUID_GAUGE_SPACING = 28;

    public SmartCookingPotScreen(SmartCookingPotMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // 存储区改单列纵向滚动后，GUI 高度固定 235（不再由存储行数推导 ⇒ 修正缩放 4 下 333 高出屏），
        // invTop=152（与菜单玩家槽一致，且在流体计 y88~146 下方）。
        int invTop = 152;
        imageWidth = Math.max(176, 130 + 18 + 20);
        imageHeight = invTop + 83;
        inventoryLabelY = invTop - 12;
        dynamicSlots = true;
        // 单列可视行数：从列顶到 GUI 底（留 4px 边距），至少 2 行，至多全部。
        int avail = imageHeight - STORAGE_COL_TOP - 4;
        storageVisibleRows = Math.max(2, Math.min(cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT, avail / STORAGE_SLOT_PITCH));
        storageMaxScroll = Math.max(0, cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT - storageVisibleRows);
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // 6 input slots: 3x2 grid
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < INPUT_COLS; col++) {
                int x = INPUT_START_X + col * INPUT_SPACING - 1;
                int y = INPUT_START_Y + row * INPUT_SPACING - 1;
                int slotIdx = row * INPUT_COLS + col;
                GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.INPUT, this, x, y);
                if (menu.slots.get(slotIdx) instanceof IVirtualSlot ivs) {
                    vs.updateVirtualSlot(null, ivs);
                }
                addRenderableWidget(vs);
            }
        }

        // 存储槽（81）：改「单列纵向滚动」——为每个槽建一个 GuiVirtualSlot 并绑定其 IVirtualSlot，
        // 与输入/输出/升级槽同款；实际位置由 applyStorageScrollLayout() 按滚动偏移摆放。
        // 菜单内存储槽起始索引 = STORAGE_SLOT_START（输入/输出/返回/升级在菜单里的位置与 BE 索引一致）。
        int storageMenuStart = cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.STORAGE_SLOT_START;
        storageWidgets.clear();
        for (int i = 0; i < cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT; i++) {
            int menuSlotIndex = storageMenuStart + i;
            GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.EXTRA, this, STORAGE_COL_X, STORAGE_COL_TOP);
            if (menuSlotIndex < menu.slots.size() && menu.slots.get(menuSlotIndex) instanceof IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
            storageWidgets.add(vs);
        }
        applyStorageScrollLayout();

        // Output slot
        GuiVirtualSlot outputVS = new GuiVirtualSlot(SlotType.OUTPUT, this, OUTPUT_X - 1, OUTPUT_Y - 1);
        if (menu.slots.get(cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.OUTPUT_SLOT) instanceof IVirtualSlot ivs) {
            outputVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(outputVS);
        // Return slot (EXTRA texture)
        GuiVirtualSlot returnVS = new GuiVirtualSlot(SlotType.EXTRA, this, OUTPUT_X - 1, RETURN_Y - 1);
        if (menu.slots.get(cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.RETURN_SLOT) instanceof IVirtualSlot ivs) {
            returnVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(returnVS);

        // Energy bar (right side)
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                      menu.getEnergy(), SmartCookingPotBlockEntity.ENERGY_CAPACITY);
            }

            @Override
            public double getLevel() {
                return (double) menu.getEnergy() / SmartCookingPotBlockEntity.ENERGY_CAPACITY;
            }
        }, imageWidth - 12, 22));

        // Progress bar (Mekanism-style SMALL_RIGHT arrow at 100, 41)
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgress() / 24.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgress() > 0;
            }
        }, ProgressType.SMALL_RIGHT, this, 100, 38));

        // Energy info tab (bottom-left corner, using Mekanism's texture)
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
              Component.translatable("gui.mekck.energy_stored",
                    menu.getEnergy(), SmartCookingPotBlockEntity.ENERGY_CAPACITY),
              Component.translatable("gui.mekck.energy_per_tick",
                    getActualEnergyPerTick())
        )));

        // Power slot (energy items: energy cube / tablet / redstone) next to the energy bar
        int powerSlotIndex = 10 + cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT;
        if (powerSlotIndex < menu.slots.size()) {
            GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
            powerVs.with(SlotOverlay.POWER);
            if (menu.slots.get(powerSlotIndex) instanceof IVirtualSlot ivs) {
                powerVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(powerVs);
        }

        // Fluid gauges (3 vertical bars, matching mekanism:chemical_dissolution_chamber)
        for (int i = 0; i < cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.FLUID_TANK_COUNT; i++) {
            final int idx = i;
            addRenderableWidget(new GuiCkFluidGauge(this,
                    FLUID_GAUGE_X + i * FLUID_GAUGE_SPACING, FLUID_GAUGE_Y,
                    () -> menu.getFluidStack(idx), () -> menu.getFluidCapacity()));
        }

        // 侧栏 tab **最后注册**：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，即越晚注册命中优先。tab 全部在面板之外（与存储列 y64 起、存储列不重叠），
        // 故不会抢掉虚拟槽 / 流体计的点击。
        addTabElements();
    }

    /**
     * 侧栏 4 个 tab 统一走 Mek {@link MekCkTabElement}（继承 {@code GuiInsetElement}）：
     * 三层绘制与旧手绘逐参数一致，tooltip 改走 {@code GuiMekanism#renderLabels} 的元素通道
     * —— 那是渲染管线最后一层，结构上不会再被槽位盖住。
     * 旧实现在 {@code renderBg()} 里直绘 tooltip + 在 {@code mouseClicked} 里手算命中矩形，现已一并移除。
     */
    private void addTabElements() {
        // ── 左列（2 个）──
        addTab(CONFIG_TEXTURE, TAB_X, CONFIG_TAB_Y, true,
                () -> false, SpecialColors.TAB_CONFIGURATION,
                "gui.mekck.ui.side_config_short", this::openSideConfigWindow);

        // ME 下单在 Mek 里无对应图标：保留本模组自绘的「清单 + 向下箭头」图标，只取官方染色。
        // 面板本体已从「屏幕手绘覆盖层」迁进 Mek 虚拟窗口（NetworkOrderWindow）。
        // 旧版「本机」一侧是 renderOrderMode/handleOrderClick 手绘的深色列表，现已删除：
        // 面板的「本机 / ME」两档共用同一套网格 / 搜索 / 数量 / 缺料 UI，本机档改由
        // localOrderSource() 供数据（配方 / 可做份数 / 下单通道与旧手绘版逐条同源）。
        // 顺带修掉一个旧缺陷：旧 mePanel 是 new NetworkOrderPanel()（localMode=true）却从未
        // setLocalSource，所以它的「本机」档恒显示「机器里没有可做的材料」。
        orderTab = addRenderableWidget(new NetworkOrderTab(this, menu.getBlockPos(),
                TAB_X, AUTO_DIST_Y, true, localOrderSource(), () -> orderTab));

        // ── 右列（2 个）──
        addTab(UPGRADE_TEXTURE, imageWidth, UPGRADE_TAB_Y, false,
                () -> false, SpecialColors.TAB_UPGRADE,
                "tooltip.mekck.upgrade", this::openUpgradeWindow);

        redstoneTab();

        // 侧栏 tab 必须最后注册：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，越晚注册命中优先。
        if (cn.ism.mekck.client.NetworkPullButton.isVisible()) {
            for (var tab : cn.ism.mekck.client.NetworkPullButton.register(this, menu.getBlockPos())) {
                addRenderableWidget(tab);
            }
        }
    }

    /** 注册一个侧栏 tab（几何 26/18，MekCkTabElement 常量）。 */
    private MekCkTabElement addTab(ResourceLocation icon, int relX, int relY, boolean left,
            BooleanSupplier selected, ColorRegistryObject tint, String tooltipKey, Runnable action) {
        MekCkTabElement tab = new MekCkTabElement(this, icon, relX, relY, left,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                selected, tint, () -> List.of(Component.translatable(tooltipKey)), action, null);
        addRenderableWidget(tab);
        return tab;
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
                SpecialColors.TAB_REDSTONE_CONTROL,
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
        addRenderableWidget(tab);
        return tab;
    }

    private static String formatItemCount(int count) {
        // 统一走 CountFormat（含十亿档；21 亿不再显示成 2147.5M）
        return cn.ism.mekck.client.CountFormat.compact(count);
    }

    /** 按当前滚动偏移摆放 81 个存储 GuiVirtualSlot：可视窗口内放到单列，其余移到屏外（自动不可见/不可点）。 */
    private void applyStorageScrollLayout() {
        for (int i = 0; i < storageWidgets.size(); i++) {
            GuiVirtualSlot w = storageWidgets.get(i);
            int viewRow = i - storageScrollOffset;
            int targetX;
            int targetY;
            if (viewRow >= 0 && viewRow < storageVisibleRows) {
                targetX = STORAGE_COL_X;
                targetY = STORAGE_COL_TOP + viewRow * STORAGE_SLOT_PITCH;
            } else {
                targetX = -10000;
                targetY = -10000;
            }
            // move() 是增量，且会同步 relativeX/relativeY 与 widget 坐标；用绝对差值定位。
            w.move(targetX - w.getRelativeX(), targetY - w.getRelativeY());
        }
    }

    /** 鼠标是否悬停在存储单列（或其滚轮区）上——绝对屏幕坐标。 */
    private boolean isMouseOverStorageColumn(double mouseX, double mouseY) {
        int colLeft = leftPos + STORAGE_COL_X - 2;
        int colRight = leftPos + STORAGE_COL_X + STORAGE_SLOT_PITCH + 8; // 含右侧滚动条
        int colTop = topPos + STORAGE_COL_TOP - 2;
        int colBottom = topPos + STORAGE_COL_TOP + storageVisibleRows * STORAGE_SLOT_PITCH + 2;
        return mouseX >= colLeft && mouseX < colRight && mouseY >= colTop && mouseY < colBottom;
    }

    /**
     * Calculates the actual energy consumption per tick based on upgrade counts.
     * Matches the calculation in SmartCookingPotBlockEntity.serverTick.
     */
    private int getActualEnergyPerTick() {
        double speedMult = Math.pow(10, menu.getSpeedUpgradeCount() / 8.0);
        double energyConsumptionMult = Math.pow(0.1, menu.getEnergyUpgradeCount() / 8.0);
        return (int) Math.ceil(SmartCookingPotBlockEntity.ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 20, inventoryLabelY, titleTextColor());
        // 温度系统：显示机身温度（摄氏度）
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.temperature", menu.getTemperature() / 100.0).getString(), 20, 78, 0xFFFF5555);
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        int x = leftPos;
        int y = topPos;

        // 侧栏 4 个 tab（侧配 / 下单 / 升级 / 红石）已全部迁到 addTabElements()（MekCkTabElement），renderBg 不再手绘。

        // 存储区（单列纵向滚动）：标签 + 滚动条（轨道 + 滑块）
        int storageLblX = x + STORAGE_COL_X;
        guiGraphics.drawString(font, Component.translatable("gui.mekck.slot_window.storage").getString(), storageLblX, y + STORAGE_COL_TOP - 10, 0xFFAAAAAA);
        if (storageMaxScroll > 0) {
            int trackX = x + STORAGE_COL_X + STORAGE_SLOT_PITCH + 2;
            int trackTop = y + STORAGE_COL_TOP;
            int trackH = storageVisibleRows * STORAGE_SLOT_PITCH;
            guiGraphics.fill(trackX, trackTop, trackX + 4, trackTop + trackH, 0xFF2B2B2B); // 轨道
            int thumbH = Math.max(8, trackH * storageVisibleRows / cn.ism.mekck.blockentity.SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT);
            int thumbY = trackTop + (trackH - thumbH) * storageScrollOffset / storageMaxScroll;
            guiGraphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFF8C8C8C); // 滑块
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Temporarily set item counts to 1 for stacks with large counts,
        // so the default count text is not rendered (count > 1 triggers text).
        // Items are rendered at their correct positions; only the text is suppressed.
        bigStackHud.shrink(menu.slots);

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

        // 侧栏 4 个 tab 的 tooltip 已迁到 MekCkTabElement#renderToolTip，
        // 由 GuiMekanism#renderLabels 在渲染管线最后一层统一派发。

        // Order progress display
        if (menu.getOrderQuantity() > 0) {
            int orderPanelX = x + 5;
            int orderPanelY = y + 5;
            int orderQty = menu.getOrderQuantity();
            int orderCompleted = menu.getOrderCompleted();
            String orderText = Component.translatable("gui.mekck.ui.current_order",
                    Math.min(orderCompleted, orderQty), orderQty).getString();
            guiGraphics.fill(orderPanelX, orderPanelY, orderPanelX + font.width(orderText) + 8, orderPanelY + 14, 0xCC000000);
            guiGraphics.drawString(font, orderText, orderPanelX + 4, orderPanelY + 3, 0xFFFFFF00);

            // Cancel button for current order
            int cancelBtnX = orderPanelX + font.width(orderText) + 12;
            int cancelBtnY = orderPanelY;
            int cancelBtnW = 30;
            int cancelBtnH = 14;
            boolean cancelHovered = mouseX >= cancelBtnX && mouseX < cancelBtnX + cancelBtnW
                    && mouseY >= cancelBtnY && mouseY < cancelBtnY + cancelBtnH;
            int cancelColor = cancelHovered ? 0xFFAA4444 : 0xFF882222;
            guiGraphics.fill(cancelBtnX, cancelBtnY, cancelBtnX + cancelBtnW, cancelBtnY + cancelBtnH, cancelColor);
            guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.cancel").getString(), cancelBtnX + 4, cancelBtnY + 3, 0xFFFFFFFF);

            // Store cancel button bounds for click handling
            this.orderCancelBtnX = cancelBtnX;
            this.orderCancelBtnY = cancelBtnY;
            this.orderCancelBtnW = cancelBtnW;
            this.orderCancelBtnH = cancelBtnH;
        } else {
            this.orderCancelBtnX = -1;
            this.orderCancelBtnY = -1;
        }
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
        if (button == 0) {
            // Cancel order button
            if (orderCancelBtnX >= 0 && mouseX >= orderCancelBtnX && mouseX < orderCancelBtnX + orderCancelBtnW
                    && mouseY >= orderCancelBtnY && mouseY < orderCancelBtnY + orderCancelBtnH) {
                ModMessages.sendToServer(new OrderRecipePacket(menu.getBlockPos(), null, 0));
                return true;
            }
        }
        // 侧栏 4 个 tab（侧配 / 下单 / 升级 / 红石）的点击交给 MekCkTabElement#onClick —— 它们是
        // renderable widget，由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        // 「下单」tab 开的是 Mek 窗口，窗口内的点击由 GuiMekanism#mouseClicked 先遍历 windows 派发
        // （窗口在 children() 之前），所以旧版那份「面板开着时先定向派发 tab」的补丁已随面板一起删除。
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 存储单列滚动：仅在鼠标悬停列上时接，避免与 Mek 窗口抢滚轮。
        if (storageMaxScroll > 0 && isMouseOverStorageColumn(mouseX, mouseY)) {
            int step = delta > 0 ? -1 : 1; // 向上滚看更早的行
            int next = Math.max(0, Math.min(storageMaxScroll, storageScrollOffset + step));
            if (next != storageScrollOffset) {
                storageScrollOffset = next;
                applyStorageScrollLayout();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public NetworkOrderPanel networkOrderPanel() {
        // 窗口开着 ⇒ 返回窗口里的面板；关着 ⇒ null（回包丢弃，不再灌进已销毁的面板）。
        return orderTab == null ? null : orderTab.panel();
    }

    /**
     * 本机一侧：配方 / 可做份数按机器输入槽里的材料算，下单走通用订单包。
     *
     * <p>与旧手绘本机列表（{@code renderOrderMode} / {@code handleOrderClick}）逐条同源：
     * 列表取 {@code getAvailableRecipes()}、上限取 {@code getMaxConsumableCountForOrder()}、
     * 确认下单发 {@code OrderRecipePacket}。迁到窗口创建时注入
     * （{@link NetworkOrderTab#createWindow()} → {@code setLocalSource}）：面板随窗口每次打开重建，
     * 数据源必须跟着重建，否则新面板的本机模式是空的。</p>
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
                ModMessages.sendToServer(new OrderRecipePacket(menu.getBlockPos(), recipe.getId(), quantity));
            }
        };
    }
}
