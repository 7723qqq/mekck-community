package cn.ism.mekck.client;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.blockentity.SkeweringMachineBlockEntity;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.SkeweringMachineMenu;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkOrderPacket;
import cn.ism.mekck.network.OrderRecipePacket;
import cn.ism.mekck.network.RedstoneControlPacket;
import cn.ism.mekck.network.SideConfigPacket;
import java.util.List;
import java.util.function.BooleanSupplier;
import mekanism.api.text.EnumColor;
import mekanism.client.SpecialColors;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.bar.GuiBar.IBarInfoHandler;
import mekanism.client.gui.element.button.ColorButton;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.common.util.text.InputValidator;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class SkeweringMachineScreen extends GuiMekanism<SkeweringMachineMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    private boolean configMode = false;
    private boolean orderMode = false;
    /** 「ME 来源」下单面板（AE 终端风格，未装 AE2 时只显示本机模式）。 */
    private final NetworkOrderPanel mePanel = new NetworkOrderPanel();
    private static final int AUTO_DIST_Y = 34;
    private static final ResourceLocation SORTING_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "sorting.png");
    private List<Recipe<?>> availableRecipes;
    private Recipe<?> selectedRecipe;
    private int orderQuantity = 1;
    private int orderScrollOffset = 0;
    private boolean orderListDirty = true;
    // §F23：「自定义数量」输入改 Mekanism GuiTextField（同 §F22 范式）；customInputMode 仅作编辑态标记。
    private boolean customInputMode = false;
    private GuiTextField qtyField;

    /** 侧栏 tab（MekCkTabElement）——供 {@link #clickTabElement} 在覆盖层分支里做优先派发。 */
    private final List<MekCkTabElement> tabElements = new java.util.ArrayList<>();

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
    private int storageVisibleRows = 6;
    /** 最大滚动偏移 = STORAGE_SLOT_COUNT - 可视行数（不小于 0）。 */
    private int storageMaxScroll = 0;

    // Cancel button bounds for order progress display
    private int orderCancelBtnX = -1;
    private int orderCancelBtnY = -1;
    private int orderCancelBtnW = 30;
    private int orderCancelBtnH = 14;

    // Order panel layout
    private static final int ORDER_PANEL_LEFT = 10;
    private static final int ORDER_PANEL_TOP = 10;
    private static final int ORDER_PANEL_WIDTH = 150;
    private static final int ORDER_ENTRY_HEIGHT = 20;
    private static final int ORDER_LIST_ROWS = 6;
    private static final int ORDER_LIST_HEIGHT = ORDER_ENTRY_HEIGHT * ORDER_LIST_ROWS;

    // Mekanism-style tab positions
    // Left side: config tab
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;

    // Right side: upgrade tab (at top-right corner, matching Mekanism's GuiUpgradeWindowTab)
    private static final int UPGRADE_TAB_Y = 6;

    // Mekanism textures
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");

    // Redstone control tab (right side, identical position to Mekanism's factory: x = imageWidth, y = 137)
    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;

    // Redstone control icon textures (Mekanism)
    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    // Slot layout constants
    private static final int INPUT_START_X = 38;
    private static final int INPUT_START_Y = 41;
    private static final int INPUT_COLS = 3;
    private static final int INPUT_SPACING = 18;
    private static final int OUTPUT_X = 130;
    private static final int OUTPUT_Y = 41;
    private static final int RETURN_Y = 59;

    // Config mode layout
    private static final int CONFIG_BUTTON_W = 60;
    private static final int CONFIG_BUTTON_H = 21;
    private static final int CONFIG_START_X = 10;
    private static final int CONFIG_START_Y = 10;
    private static final int CONFIG_COL_GAP = 62;
    private static final int CONFIG_ROW_GAP = 23;
    private static final int DONE_BUTTON_W = 126;
    private static final int DONE_BUTTON_H = 21;

    // Config overlay widgets (MekCkButtons)。方向顺序/模式名沿用原手绘版，勿改。
    private static final Direction[] CONFIG_DIRS = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final String[] CONFIG_MODE_NAMES = {"无", "抽取(输入格)", "输出", "抽取(存储)"};
    /** 覆盖层 6 个方向格，顺序同 CONFIG_DIRS。 */
    private final List<ColorButton> configDirButtons = new java.util.ArrayList<>();
    /** 覆盖层「完成」按钮。 */
    private MekanismButton configDoneButton;

    // Upgrade slot positions (on upgrade page)
    private static final int UPGRADE_SLOT_X = 40;
    private static final int UPGRADE_SPEED_Y = 46;
    private static final int UPGRADE_ENERGY_Y = 72;

    public SkeweringMachineScreen(SkeweringMachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // 存储区改单列纵向滚动后，GUI 高度固定 184（不再由存储行数推导 ⇒ 修正缩放 4 下 333 高出屏），
        // 与菜单玩家槽 (20,101)/(20,159) 对齐；invTop=101。
        int invTop = 101;
        imageWidth = Math.max(176, 130 + 18 + 20);
        imageHeight = invTop + 83;
        inventoryLabelY = invTop - 12;
        dynamicSlots = true;
        // 单列可视行数：从列顶到 GUI 底（留 4px 边距），至少 2 行，至多全部。
        int avail = imageHeight - STORAGE_COL_TOP - 4;
        storageVisibleRows = Math.max(2, Math.min(SkeweringMachineBlockEntity.STORAGE_SLOT_COUNT, avail / STORAGE_SLOT_PITCH));
        storageMaxScroll = Math.max(0, SkeweringMachineBlockEntity.STORAGE_SLOT_COUNT - storageVisibleRows);
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // §F23：自定义数量输入框（敲数字回车提交，DIGIT；默认隐藏，位置由 renderOrderMode resize）
        qtyField = new GuiTextField(this, 14, 176, 80, 14)
                .setInputValidator(InputValidator.DIGIT)
                .configureDigitalBorderInput(this::commitQty);
        qtyField.setMaxLength(9);
        qtyField.setText("");
        qtyField.setVisible(false);
        addRenderableWidget(qtyField);

        // 3 input slots: 1 row x 3 columns
        for (int col = 0; col < INPUT_COLS; col++) {
            int x = INPUT_START_X + col * INPUT_SPACING - 1;
            int y = INPUT_START_Y - 1;
            GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.INPUT, this, x, y);
            if (menu.slots.get(col) instanceof mekanism.common.inventory.container.slot.IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
        }

        // 存储槽（81）：改「单列纵向滚动」——为每个槽建一个 GuiVirtualSlot 并绑定其 IVirtualSlot，
        // 与输入/输出/升级槽同款；实际位置由 applyStorageScrollLayout() 按滚动偏移摆放。
        // 菜单内存储槽起始索引 = STORAGE_SLOT_START（本机器输入/输出/返回/升级在菜单里的位置与 BE 索引一致）。
        int storageMenuStart = SkeweringMachineBlockEntity.STORAGE_SLOT_START;
        storageWidgets.clear();
        for (int i = 0; i < SkeweringMachineBlockEntity.STORAGE_SLOT_COUNT; i++) {
            int menuSlotIndex = storageMenuStart + i;
            GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.EXTRA, this, STORAGE_COL_X, STORAGE_COL_TOP);
            if (menuSlotIndex < menu.slots.size() && menu.slots.get(menuSlotIndex) instanceof mekanism.common.inventory.container.slot.IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
            storageWidgets.add(vs);
        }
        applyStorageScrollLayout();

        // Output slot
        GuiVirtualSlot outputVS = new GuiVirtualSlot(SlotType.OUTPUT, this, OUTPUT_X - 1, OUTPUT_Y - 1);
        if (menu.slots.get(SkeweringMachineBlockEntity.OUTPUT_SLOT) instanceof mekanism.common.inventory.container.slot.IVirtualSlot ivs) {
            outputVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(outputVS);
        // Return slot (EXTRA texture)
        GuiVirtualSlot returnVS = new GuiVirtualSlot(SlotType.EXTRA, this, OUTPUT_X - 1, RETURN_Y - 1);
        if (menu.slots.get(SkeweringMachineBlockEntity.RETURN_SLOT) instanceof mekanism.common.inventory.container.slot.IVirtualSlot ivs) {
            returnVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(returnVS);

        // Energy bar (right side)
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                      menu.getEnergy(), SkeweringMachineBlockEntity.ENERGY_CAPACITY);
            }

            @Override
            public double getLevel() {
                return (double) menu.getEnergy() / SkeweringMachineBlockEntity.ENERGY_CAPACITY;
            }
        }, imageWidth - 12, 22));

        // Progress bar (Mekanism-style SMALL_RIGHT arrow at 100, 38)
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
                    menu.getEnergy(), SkeweringMachineBlockEntity.ENERGY_CAPACITY),
              Component.translatable("gui.mekck.energy_per_tick",
                    getActualEnergyPerTick())
        )));

        // Power slot (energy items: energy cube / tablet / redstone) next to the energy bar
        // 菜单里 power 槽位于 3 输入+输出+返回+2 升级+81 存储之后 = 索引 7 + STORAGE_SLOT_COUNT（之前误用 BE 的 SLOT_POWER=8+count，差一致命不中）。
        int powerSlotIndex = SkeweringMachineBlockEntity.STORAGE_SLOT_START + SkeweringMachineBlockEntity.STORAGE_SLOT_COUNT;
        if (powerSlotIndex < menu.slots.size()) {
            GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
            powerVs.with(SlotOverlay.POWER);
            if (menu.slots.get(powerSlotIndex) instanceof mekanism.common.inventory.container.slot.IVirtualSlot ivs) {
                powerVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(powerVs);
        }

        // 覆盖层按钮**最后注册**：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，即**越晚注册命中优先**。方向格与虚拟槽确有重叠（方向格 x10~70 / x72~132，
        // y10~31 / 33~54 / 56~77；输入槽 x37~91 y40~58、输出/返回槽 x129~147、电源槽 x6~24 y12~30），
        // 排在槽位之后才能像旧 handleConfigClick（在 super.mouseClicked 之前拦截）那样压过槽位点击。
        initConfigOverlayButtons();

        // 侧栏 tab 排在最后：tab 全部在**面板之外**（左列 x=-26~-2、右列 x=imageWidth~imageWidth+24、
        // y=6~30 / 34~58），与覆盖层方向格、虚拟槽都不重叠，故这里的「最后注册」不会抢掉 configMode 的点击。
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
                () -> configMode, SpecialColors.TAB_CONFIGURATION,
                "tooltip.mekck.side_config", () -> configMode = !configMode);

        addTab(MachineTabIcons.ORDER, TAB_X, AUTO_DIST_Y, true,
                () -> orderMode, SpecialColors.TAB_CONTAINER_EDIT_MODE,
                "tooltip.mekck.order_panel", this::toggleOrderMode);

        // ── 右列（2 个）──
        addTab(UPGRADE_TEXTURE, imageWidth, UPGRADE_TAB_Y, false,
                () -> false, SpecialColors.TAB_UPGRADE,
                "tooltip.mekck.upgrade", () -> {
                    openUpgradeWindow();
                    configMode = false;
                });

        redstoneTab();
    }

    /** 注册一个侧栏 tab（几何 24/16，MekCkTabElement 常量）并记入 {@link #tabElements}。 */
    private MekCkTabElement addTab(ResourceLocation icon, int relX, int relY, boolean left,
            BooleanSupplier selected, ColorRegistryObject tint, String tooltipKey, Runnable action) {
        MekCkTabElement tab = new MekCkTabElement(this, icon, relX, relY, left,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                selected, tint, () -> List.of(Component.translatable(tooltipKey)), action, null);
        addRenderableWidget(tab);
        tabElements.add(tab);
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
        tabElements.add(tab);
        return tab;
    }

    /** ME 下单面板开关（原在 mouseClicked 内联，迁出为 tab 动作）。 */
    private void toggleOrderMode() {
        orderMode = !orderMode;
        if (orderMode) {
            orderListDirty = true;
        } else {
            mePanel.onClosed();
        }
        configMode = false;
    }

    /**
     * 侧栏 tab 的**优先**派发：只在本屏的 tab 列表里倒序找第一个命中者。
     * <p>旧手绘版的 tab 命中分支写在 {@code mouseClicked} 最前面，优先于 {@code orderMode} 那个
     * 「吞掉整次左键」的覆盖层分支；tab 变成 widget 后该分支会先于 {@code super.mouseClicked} 返回，
     * 因此这里必须补一次定向派发，否则「下单面板开着时点侧栏 tab 关面板 / 开升级窗」就废了。
     * 命中判定与动作完全走 {@link MekCkTabElement} 自己的 {@code mouseClicked}，与框架对
     * {@code children()} 的派发同语义（含 tab 之间的优先级：越晚注册越优先）。</p>
     */
    private boolean clickTabElement(double mouseX, double mouseY, int button) {
        for (int i = tabElements.size() - 1; i >= 0; i--) {
            if (tabElements.get(i).mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 注册配置覆盖层的 7 个按钮为 MekCkButtons 真 widget（取代原手绘 blit/fill/drawString）。
     * 坐标全部取自原常量，像素位置不变；右键不挂回调，与旧版「只在 configMode 处理左键」一致。
     */
    private void initConfigOverlayButtons() {
        configDirButtons.clear();
        for (int i = 0; i < CONFIG_DIRS.length; i++) {
            final Direction dir = CONFIG_DIRS[i];
            ColorButton button = MekCkButtons.color(this,
                    CONFIG_START_X + (i % 2) * CONFIG_COL_GAP,
                    CONFIG_START_Y + (i / 2) * CONFIG_ROW_GAP,
                    CONFIG_BUTTON_W, CONFIG_BUTTON_H,
                    () -> colorForMode(menu.getSideMode(dir)),
                    () -> cycleConfigMode(dir),
                    null);
            MekCkButtons.setShown(button, false);
            addRenderableWidget(button);
            configDirButtons.add(button);
        }
        configDoneButton = MekCkButtons.text(this,
                CONFIG_START_X,
                CONFIG_START_Y + 3 * CONFIG_ROW_GAP + 4,
                DONE_BUTTON_W, DONE_BUTTON_H,
                Component.literal("完成"),
                () -> configMode = false);
        MekCkButtons.setShown(configDoneButton, false);
        addRenderableWidget(configDoneButton);
    }

    /**
     * 每帧同步覆盖层按钮：可见性随 configMode 开关，文字标签随「机器朝向 + 各面模式」变化。
     * 必须在 super.render() 之前调用，否则本帧画出的还是上一帧的标签。
     */
    private void syncConfigOverlayButtons() {
        MekCkButtons.setShown(configDirButtons, configMode);
        MekCkButtons.setShown(configDoneButton, configMode);
        if (!configMode) {
            return;
        }
        Direction facing = getMachineFacing();
        for (int i = 0; i < configDirButtons.size(); i++) {
            Direction dir = CONFIG_DIRS[i];
            SideMode mode = menu.getSideMode(dir);
            configDirButtons.get(i).setMessage(
                    Component.literal(getRelativeDirectionName(dir, facing) + "面: " + CONFIG_MODE_NAMES[mode.ordinal()]));
        }
    }

    /** 方向格左键：循环到下一种面配置（原 handleConfigClick 的行为，只处理左键）。 */
    private void cycleConfigMode(Direction dir) {
        SideMode current = menu.getSideMode(dir);
        SideMode next = current.cycle(true, menu.supportsStoragePull());
        ModMessages.sendToServer(new SideConfigPacket(menu.getBlockPos(), dir, next));
    }

    /** 与 GuiMekCkSideConfiguration.colorForMode() 同映射（那边是 private，这里照抄一份）。 */
    private static EnumColor colorForMode(SideMode mode) {
        return switch (mode) {
            case PULL_INPUT -> EnumColor.DARK_RED;
            case PULL_INPUT_STORAGE -> EnumColor.YELLOW;
            case PUSH_OUTPUT -> EnumColor.DARK_BLUE;
            case NONE -> EnumColor.GRAY;
        };
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
     * Matches the calculation in SkeweringMachineBlockEntity.serverTick.
     */
    private int getActualEnergyPerTick() {
        double speedMult = Math.pow(10, menu.getSpeedUpgradeCount() / 8.0);
        double energyConsumptionMult = Math.pow(0.1, menu.getEnergyUpgradeCount() / 8.0);
        return (int) Math.ceil(SkeweringMachineBlockEntity.ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);
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

        // 侧栏 4 个 tab（侧配 / 下单 / 升级 / 红石）已全部迁到 addTabElements()（MekCkTabElement），renderBg 不再手绘。

        // 存储区（单列纵向滚动）：标签 + 滚动条（轨道 + 滑块）
        int storageLblX = x + STORAGE_COL_X;
        guiGraphics.drawString(font, "存储", storageLblX, y + STORAGE_COL_TOP - 10, 0xFFAAAAAA);
        if (storageMaxScroll > 0) {
            int trackX = x + STORAGE_COL_X + STORAGE_SLOT_PITCH + 2;
            int trackTop = y + STORAGE_COL_TOP;
            int trackH = storageVisibleRows * STORAGE_SLOT_PITCH;
            guiGraphics.fill(trackX, trackTop, trackX + 4, trackTop + trackH, 0xFF2B2B2B); // 轨道
            int thumbH = Math.max(8, trackH * storageVisibleRows / SkeweringMachineBlockEntity.STORAGE_SLOT_COUNT);
            int thumbY = trackTop + (trackH - thumbH) * storageScrollOffset / storageMaxScroll;
            guiGraphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFF8C8C8C); // 滑块
        }

        // 配置覆盖层的纯装饰底板。必须画在 widget 层之前（renderBg 早于 renderables），
        // 否则会把已注册成原生按钮的方向格整块盖住。
        if (configMode) {
            guiGraphics.fill(x + 3, y + 3, x + imageWidth - 3, y + imageHeight - 3, 0xFF000000);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Temporarily set item counts to 1 for stacks with large counts,
        // so the default count text is not rendered (count > 1 triggers text).
        // Items are rendered at their correct positions; only the text is suppressed.
        bigStackHud.shrink(menu.slots);

        // 覆盖层按钮按需出现（configMode 在 mouseClicked 里翻转），须先于 widget 渲染同步。
        syncConfigOverlayButtons();

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

        // Order mode overlay
        if (orderMode) {
            renderOrderMode(guiGraphics, x, y, mouseX, mouseY);
        } else if (qtyField != null) {
            qtyField.setVisible(false); // 退下单面板时收起自定义输入框
        }

        // Order progress display (when order is active and not in order mode)
        if (!orderMode && menu.getOrderQuantity() > 0) {
            int orderPanelX = x + 5;
            int orderPanelY = y + 5;
            int orderQty = menu.getOrderQuantity();
            int orderCompleted = menu.getOrderCompleted();
            String orderText = "当前订单: " + Math.min(orderCompleted, orderQty) + "/" + orderQty;
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
            guiGraphics.drawString(font, "取消", cancelBtnX + 4, cancelBtnY + 3, 0xFFFFFFFF);

            // Store cancel button bounds for click handling
            this.orderCancelBtnX = cancelBtnX;
            this.orderCancelBtnY = cancelBtnY;
            this.orderCancelBtnW = cancelBtnW;
            this.orderCancelBtnH = cancelBtnH;
        } else {
            this.orderCancelBtnX = -1;
            this.orderCancelBtnY = -1;
        }

        // 侧栏 4 个 tab 的 tooltip 已迁到 MekCkTabElement#renderToolTip，
        // 由 GuiMekanism#renderLabels 在渲染管线最后一层统一派发。
    }

    private void renderOrderMode(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY) {
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        // ME 来源：整块换成 AE 终端风格面板（本机模式代码原样保留在下面）
        mePanel.bind(menu.getBlockPos());
        if (mePanel.isMe()) {
            mePanel.render(guiGraphics, font, panelX, panelY, panelW, panelH, mouseX, mouseY, 0f);
            return;
        }

        guiGraphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xCC000000);
        guiGraphics.drawString(font, "下单", panelX + 4, panelY + 4, 0xFFFFFFFF);

        if (orderListDirty) {
            availableRecipes = menu.getMachine().getAvailableRecipes();
            orderListDirty = false;
        }

        int listTop = panelY + 16;
        int listBottom = listTop + ORDER_LIST_HEIGHT;
        int listWidth = panelW - 8;

        int visibleCount = Math.min(ORDER_LIST_ROWS, availableRecipes.size() - orderScrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int recipeIdx = orderScrollOffset + i;
            if (recipeIdx >= availableRecipes.size()) break;

            Recipe<?> recipe = availableRecipes.get(recipeIdx);
            int entryY = listTop + i * ORDER_ENTRY_HEIGHT;
            boolean isSelected = recipe == selectedRecipe;

            int entryColor = isSelected ? 0x884488FF : 0x44444444;
            guiGraphics.fill(panelX + 2, entryY, panelX + listWidth, entryY + ORDER_ENTRY_HEIGHT - 1, entryColor);

            ItemStack result = recipe.getResultItem(minecraft.level.registryAccess());
            if (!result.isEmpty()) {
                guiGraphics.renderItem(result, panelX + 4, entryY + 2);
            }

            String name = result.isEmpty() ? "Unknown" : result.getHoverName().getString();
            if (font.width(name) > listWidth - 50) {
                name = font.plainSubstrByWidth(name, listWidth - 50) + "...";
            }
            guiGraphics.drawString(font, name, panelX + 24, entryY + 4, 0xFFFFFFFF);
        }

        if (orderScrollOffset > 0) {
            guiGraphics.drawString(font, "↑", panelX + listWidth - 10, listTop, 0xFFFFFFFF);
        }
        if (orderScrollOffset + ORDER_LIST_ROWS < availableRecipes.size()) {
            guiGraphics.drawString(font, "↓", panelX + listWidth - 10, listBottom - 10, 0xFFFFFFFF);
        }

        int qtyY = listBottom + 4;
        guiGraphics.drawString(font, "数量: " + orderQuantity, panelX + 4, qtyY, 0xFFFFFFFF);

        int qtyBtnX = panelX + 4;
        int qtyBtnY = qtyY + 10;
        int qtyBtnW = 24;
        int qtyBtnH = 14;
        String[] qtyLabels = {"1", "16", "32", "64", "自", "Max"};
        int[] qtyValues = {1, 16, 32, 64, -1, -2};
        for (int i = 0; i < qtyLabels.length; i++) {
            int bx = qtyBtnX + i * (qtyBtnW + 2);
            boolean hovered = mouseX >= bx && mouseX < bx + qtyBtnW && mouseY >= qtyBtnY && mouseY < qtyBtnY + qtyBtnH;
            int color = hovered ? 0xFF4488FF : 0xFF444444;
            // Highlight current custom input mode
            if (qtyValues[i] == -1 && customInputMode) {
                color = 0xFF44AA44;
            }
            guiGraphics.fill(bx, qtyBtnY, bx + qtyBtnW, qtyBtnY + qtyBtnH, color);
            String label = qtyLabels[i];
            int labelW = font.width(label);
            guiGraphics.drawString(font, label, bx + (qtyBtnW - labelW) / 2, qtyBtnY + 3, 0xFFFFFFFF);
        }

        // Custom input text field（§F23：DIY 白框改 GuiTextField 自绘，这里同步可见性并按本面板位移动）
        if (qtyField != null) {
            qtyField.setVisible(customInputMode);
            if (customInputMode) {
                qtyField.resize(qtyBtnX - leftPos, qtyBtnY + qtyBtnH + 2 - topPos, 80, 14);
            }
        }

        // Max quantity hint
        if (selectedRecipe != null) {
            int maxQty = menu.getMachine().getMaxConsumableCountForOrder(selectedRecipe);
            if (maxQty > 0) {
                String maxText = "最大: " + maxQty;
                guiGraphics.drawString(font, maxText, qtyBtnX, qtyBtnY + qtyBtnH + 2, 0xFFAAAAAA);
            }
        }

        int confirmBtnY = qtyBtnY + qtyBtnH + 6;
        int confirmBtnW = 60;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW) / 2;
        boolean confirmHovered = mouseX >= confirmBtnX && mouseX < confirmBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH;
        int confirmColor = confirmHovered ? 0xFF44AA44 : 0xFF228822;
        guiGraphics.fill(confirmBtnX, confirmBtnY, confirmBtnX + confirmBtnW, confirmBtnY + confirmBtnH, confirmColor);
        String confirmText = "确认下单";
        int confirmTextW = font.width(confirmText);
        guiGraphics.drawString(font, confirmText, confirmBtnX + (confirmBtnW - confirmTextW) / 2, confirmBtnY + 4, 0xFFFFFFFF);

        int cancelBtnX = confirmBtnX + confirmBtnW + 4;
        boolean cancelHovered = mouseX >= cancelBtnX && mouseX < cancelBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH;
        int cancelColor = cancelHovered ? 0xFFAA4444 : 0xFF882222;
        guiGraphics.fill(cancelBtnX, confirmBtnY, cancelBtnX + confirmBtnW, confirmBtnY + confirmBtnH, cancelColor);
        String cancelText = "取消";
        int cancelTextW = font.width(cancelText);
        guiGraphics.drawString(font, cancelText, cancelBtnX + (confirmBtnW - cancelTextW) / 2, confirmBtnY + 4, 0xFFFFFFFF);
        // 来源切换按钮**最后画**（本机列表会盖住它，点击判定仍在前面）
        mePanel.renderModeButtons(guiGraphics, font, panelX, panelY, panelW, mouseX, mouseY);
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

    private static String getRelativeDirectionName(Direction dir, Direction facing) {
        if (dir == Direction.DOWN) return "下";
        if (dir == Direction.UP) return "上";
        if (dir == facing.getOpposite()) return "正";
        if (dir == facing) return "背";
        if (dir == facing.getClockWise()) return "右";
        if (dir == facing.getCounterClockWise()) return "左";
        return "?";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int x = leftPos;
            int y = topPos;

            // configMode 下左键必被消费（与旧 handleConfigClick 一致）：方向格 / 「完成」是真
            // widget，由 super.mouseClicked 命中（注册在虚拟槽之后，见 addGuiElements）；
            // 空白处的左键则被这里吞掉，不落到主界面的输入 / 电源虚拟槽。
            // 侧栏 tab 也在 children() 里（注册更晚 ⇒ 命中优先），所以 configMode 下点 tab 仍然有效。
            if (configMode) {
                super.mouseClicked(mouseX, mouseY, button);
                return true;
            }

            // If in order mode, handle order clicks
            // 旧手绘版的 tab 命中分支在覆盖层之前；tab 变成 widget 后这里要先补一次定向派发，
            // 否则「面板开着时点侧栏 tab 关面板 / 开升级窗 / 切侧配」会被面板吞掉。语义与旧版逐条一致。
            if (orderMode) {
                if (clickTabElement(mouseX, mouseY, button)) {
                    return true;
                }
                return handleOrderClick(mouseX, mouseY, x, y);
            }

            // Cancel order button
            if (orderCancelBtnX >= 0 && mouseX >= orderCancelBtnX && mouseX < orderCancelBtnX + orderCancelBtnW
                    && mouseY >= orderCancelBtnY && mouseY < orderCancelBtnY + orderCancelBtnH) {
                ModMessages.sendToServer(new OrderRecipePacket(menu.getBlockPos(), null, 0));
                return true;
            }
        }
        // 侧栏 4 个 tab（侧配 / 下单 / 升级 / 红石）的点击交给 MekCkTabElement#onClick —— 它们是
        // renderable widget，由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (orderMode && mePanel.isMe() && mePanel.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        // §F23：Enter/Backspace/数字全交 GuiTextField（回车经 configureDigitalBorderInput → commitQty）；
        // 只保留 Escape 取消（与旧 DIY 一致，并阻止原版 Esc 直接关 GUI）。
        if (customInputMode && keyCode == 256) {
            exitCustomInput();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (orderMode && mePanel.isMe() && mePanel.charTyped(codePoint, modifiers)) {
            return true;
        }
        // §F23：数字接收改由 GuiTextField（DIGIT 校验）处理。
        return super.charTyped(codePoint, modifiers);
    }

    /** §F23：敲数字回车提交：正整数且 >0 生效；空/非法/0 保持原值并退出编辑（等价旧 DIY Enter 行为）。 */
    private void commitQty() {
        String t = qtyField.getText();
        if (!t.isEmpty()) {
            try {
                int val = Integer.parseInt(t);
                if (val > 0) {
                    orderQuantity = val;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        exitCustomInput();
    }

    /** 退出自定义输入态：清文本、隐控件、交还焦点。 */
    private void exitCustomInput() {
        customInputMode = false;
        if (qtyField != null) {
            qtyField.setText("");
            qtyField.setVisible(false);
            qtyField.setFocused(false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (orderMode && mePanel.isMe()) {
            return mePanel.mouseScrolled(delta);
        }
        // 存储单列滚动：仅在非各 overlay 模式且鼠标悬停列上时接，避免与 ME 面板抢滚轮。
        if (!orderMode && !configMode && storageMaxScroll > 0 && isMouseOverStorageColumn(mouseX, mouseY)) {
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
        return orderMode ? mePanel : null;
    }

    private boolean handleOrderClick(double mouseX, double mouseY, int x, int y) {
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        if (mouseX < panelX || mouseX > panelX + panelW || mouseY < panelY || mouseY > panelY + panelH) {
            orderMode = false;
            mePanel.onClosed();
            return true;
        }

        // ME 来源：整块交给共用面板（几何与渲染共用）
        mePanel.bind(menu.getBlockPos());
        if (mePanel.isMe()) {
            return mePanel.mouseClicked(mouseX, mouseY, 0, panelX, panelY, panelW, panelH,
                    (recipeId, qty) -> ModMessages.sendToServer(
                            new NetworkOrderPacket(menu.getBlockPos(), recipeId.toString(), qty)));
        }
        if (mePanel.handleModeClick(mouseX, mouseY, panelX, panelY, panelW)) {
            return true;
        }

        int listTop = panelY + 16;
        int listWidth = panelW - 8;

        for (int i = 0; i < ORDER_LIST_ROWS; i++) {
            int recipeIdx = orderScrollOffset + i;
            if (recipeIdx >= availableRecipes.size()) break;

            int entryY = listTop + i * ORDER_ENTRY_HEIGHT;
            if (mouseX >= panelX + 2 && mouseX < panelX + listWidth
                    && mouseY >= entryY && mouseY < entryY + ORDER_ENTRY_HEIGHT - 1) {
                selectedRecipe = availableRecipes.get(recipeIdx);
                orderQuantity = 1;
                return true;
            }
        }

        // Scroll up
        if (mouseX >= panelX + listWidth - 12 && mouseX < panelX + listWidth
                && mouseY >= listTop && mouseY < listTop + 10) {
            orderScrollOffset = Math.max(0, orderScrollOffset - 1);
            return true;
        }
        // Scroll down
        int listBottom = listTop + ORDER_LIST_HEIGHT;
        if (mouseX >= panelX + listWidth - 12 && mouseX < panelX + listWidth
                && mouseY >= listBottom - 10 && mouseY < listBottom) {
            orderScrollOffset = Math.min(availableRecipes.size() - ORDER_LIST_ROWS, orderScrollOffset + 1);
            return true;
        }

        // Quantity buttons
        int qtyY = listBottom + 4;
        int qtyBtnX = panelX + 4;
        int qtyBtnY = qtyY + 10;
        int qtyBtnW = 24;
        int qtyBtnH = 14;
        int[] qtyValues = {1, 16, 32, 64, -1, -2};
        for (int i = 0; i < qtyValues.length; i++) {
            int bx = qtyBtnX + i * (qtyBtnW + 2);
            if (mouseX >= bx && mouseX < bx + qtyBtnW && mouseY >= qtyBtnY && mouseY < qtyBtnY + qtyBtnH) {
                int val = qtyValues[i];
                if (val == -1) {
                    // §F23：切换编辑态；显示控件并把焦点交给文本框
                    customInputMode = !customInputMode;
                    if (qtyField != null) {
                        qtyField.setVisible(customInputMode);
                        qtyField.setFocused(customInputMode);
                        if (!customInputMode) {
                            qtyField.setText("");
                        }
                    }
                } else if (val == -2) {
                    // Max - calculate from available materials
                    if (selectedRecipe != null) {
                        int maxQty = menu.getMachine().getMaxConsumableCountForOrder(selectedRecipe);
                        orderQuantity = Math.max(1, maxQty);
                    }
                } else {
                    exitCustomInput();
                    orderQuantity = val;
                }
                return true;
            }
        }

        // Handle custom input click
        if (customInputMode) {
            int inputX = qtyBtnX;
            int inputY = qtyBtnY + qtyBtnH + 2;
            int inputW = 80;
            int inputH = 14;
            if (mouseX >= inputX && mouseX < inputX + inputW && mouseY >= inputY && mouseY < inputY + inputH) {
                // Focus already on custom input
                return true;
            }
        }

        int confirmBtnY = qtyBtnY + qtyBtnH + 6;
        int confirmBtnW = 60;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW) / 2;

        if (mouseX >= confirmBtnX && mouseX < confirmBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH) {
            if (selectedRecipe != null && orderQuantity > 0) {
                ResourceLocation recipeId = selectedRecipe.getId();
                ModMessages.sendToServer(new OrderRecipePacket(menu.getBlockPos(), recipeId, orderQuantity));
                orderMode = false;
                selectedRecipe = null;
                orderQuantity = 1;
            }
            return true;
        }

        int cancelBtnX = confirmBtnX + confirmBtnW + 4;
        if (mouseX >= cancelBtnX && mouseX < cancelBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH) {
            orderMode = false;
            selectedRecipe = null;
            orderQuantity = 1;
            return true;
        }

        return true;
    }
}