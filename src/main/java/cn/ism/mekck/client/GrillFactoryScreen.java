package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.blockentity.GrillFactoryBlockEntity;
import cn.ism.mekck.menu.GrillFactoryMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.network.AutoDistributePacket;
import cn.ism.mekck.network.AutoProcessListRequestPacket;
import cn.ism.mekck.network.AutoProcessTogglePacket;
import cn.ism.mekck.network.GrillSeasoningOrderPacket;
import cn.ism.mekck.network.GrillSeasoningTogglePacket;
import cn.ism.mekck.network.GrillWorkModePacket;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkOrderPacket;
import cn.ism.mekck.util.AE2Compat;
import cn.ism.mekck.network.RedstoneControlPacket;
import cn.ism.mekck.util.BarbequesDelightCompat;
import java.util.ArrayList;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;
import net.minecraft.world.item.crafting.Recipe;
import mekanism.api.text.EnumColor;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.element.bar.GuiBar.IBarInfoHandler;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.button.ColorButton;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.client.gui.element.GuiTexturedElement;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.common.util.text.InputValidator;
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

public final class GrillFactoryScreen extends GuiMekanism<GrillFactoryMenu> implements NetworkOrderHost {
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

    // ================== 下单系统（含调味） ==================
    private boolean orderMode = false;
    /** 「ME 来源」下单面板（AE 终端风格；下单会带上当前选中的风味）。 */
    private final NetworkOrderPanel mePanel = new NetworkOrderPanel();

    private static final int ORDER_TAB_Y = 62; // 自动分配按钮下方
    private static final int AUTO_PROCESS_Y = ORDER_TAB_Y + 28; // ME 自动处理 tab（订单按钮下方）
    private static final int WORK_MODE_Y = AUTO_PROCESS_Y + 28; // 工作模式切换 tab（ME 自动处理下方）
    private static final int ORDER_PANEL_LEFT = 10;
    private static final int ORDER_PANEL_TOP = 10;
    private static final int ORDER_ENTRY_HEIGHT = 20;
    private static final int ORDER_LIST_ROWS = 5;
    private static final int ORDER_LIST_HEIGHT = ORDER_ENTRY_HEIGHT * ORDER_LIST_ROWS;

    private List<Recipe<?>> availableRecipes = new ArrayList<>();
    private Recipe<?> selectedRecipe;
    private int orderQuantity = 1;
    private int orderScrollOffset = 0;
    private boolean orderListDirty = true;
    // §F23：「自定义数量」输入改 Mekanism GuiTextField（同 §F22 范式）；customInputMode 仅作编辑态标记。
    private boolean customInputMode = false;
    private GuiTextField qtyField;
    /** 下单面板中选中的调味料下标（BarbequesDelightCompat.getAllSeasonings()），-1 = 不调味。 */
    private int selectedSeasoningIndex = -1;
    private int orderCancelBtnX = -1;
    private int orderCancelBtnY = -1;
    private int orderCancelBtnW = 0;
    private int orderCancelBtnH = 0;

    // Mekanism-style tab positions
    // Left side: config tab and auto-distribute button
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;
    private static final int AUTO_DIST_Y = 34; // below config tab (28px spacing like Mekanism)
    private static final int TAB_OUTER_W = 24;
    private static final int TAB_OUTER_H = 24;

    // Auto-distribute button (same size as config tab)
    private static final int AUTO_DIST_W = 24;
    private static final int AUTO_DIST_H = 24;

    // Icon size and offset within the tab (16x16 icon centered in 24x24 tab)
    private static final int TAB_ICON_SIZE = 16;

    // Right side: upgrade tab (at top-right corner, matching Mekanism's GuiUpgradeWindowTab)
    private static final int UPGRADE_TAB_Y = 6;

    // Mekanism textures
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");
    private static final ResourceLocation SORTING_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "sorting.png");

    // Redstone control tab (right side, identical position to Mekanism's factory: x = imageWidth, y = 137)
    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;
    private static final int REDSTONE_TINT = 0xFFC9071F;

    // Redstone control icon textures (Mekanism)
    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    private final boolean hasStackUpgrade;

    // ================== 存储区「单列纵向滚动」（E4 / 拍板 F1·方案 5） ==================
    /** 存储列相对 GUI 左缘的 x（与左侧 tab 同列，宽 ~24px，塞进缩放 4 的左边距）。 */
    private static final int STORAGE_COL_X = -24;
    /** 存储列顶部 y（在左侧 tab 堆下方：work-mode tab 118 + 24 + 6 = 148）。 */
    private static final int STORAGE_COL_TOP = 148;
    /** 单个槽位间距（原版 18px）。 */
    private static final int STORAGE_SLOT_PITCH = 18;
    /** 45 个存储槽的 GuiVirtualSlot widget（顺序与菜单槽索引一致），滚动靠 move() 重摆。 */
    private final java.util.List<GuiVirtualSlot> storageWidgets = new ArrayList<>();
    /** 当前滚动偏移（以“行”计，0..storageMaxScroll）——纯客户端字段，无需同步。 */
    private int storageScrollOffset = 0;
    /** 单列可视行数（由 imageHeight 算出）。 */
    private int storageVisibleRows = 5;
    /** 最大滚动偏移 = STORAGE_SLOTS - 可视行数（不小于 0）。 */
    private int storageMaxScroll = 0;

    public GrillFactoryScreen(GrillFactoryMenu menu, Inventory inventory, Component title) {
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
        int invTop = Math.max(101, 85 + rows * 18);
        imageHeight = invTop + 83;
        inventoryLabelY = invTop - 12;
        dynamicSlots = true;
        // 单列可视行数：从列顶到 GUI 底（留 4px 边距），至少 2 行，至多全部 45 行。
        int avail = imageHeight - STORAGE_COL_TOP - 4;
        storageVisibleRows = Math.max(2, Math.min(GrillFactoryBlockEntity.STORAGE_SLOTS, avail / STORAGE_SLOT_PITCH));
        storageMaxScroll = Math.max(0, GrillFactoryBlockEntity.STORAGE_SLOTS - storageVisibleRows);
        // ME 自动处理列表的滚动窗口 = 面板内能完整放下的行数（BASIC 6 行，等级越高越多）；
        // 池另按条目数扩容（ensureAutoRowPool），条目多于窗口时滚轮翻页 —— 不会「静默丢行」。
        autoRowCapacity = Math.max(1,
                (imageHeight - AUTO_PANEL_BOTTOM_INSET - AUTO_ROW_Y - AUTO_ROW_H) / AUTO_ROW_GAP + 1);
    }

    private static final int GAP_BETWEEN = 30; // gap between input and output grids (> SMALL_RIGHT 28px)

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // §F23：自定义数量输入框（敲数字回车提交，DIGIT；默认隐藏，进「自」档才显示；位置由 renderOrderMode resize）
        qtyField = new GuiTextField(this, 14, 176, 80, 14)
                .setInputValidator(InputValidator.DIGIT)
                .configureDigitalBorderInput(this::commitQty);
        qtyField.setMaxLength(9);
        qtyField.setText("");
        qtyField.setVisible(false);
        addRenderableWidget(qtyField);

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

        // Power slot (energy items: energy cube / tablet / redstone) next to the energy bar
        int powerSlotIndex = inputSlots * 2 + (hasStackUpgrade ? 3 : 2);
        if (powerSlotIndex < menu.slots.size()) {
            GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
            powerVs.with(SlotOverlay.POWER);
            if (menu.slots.get(powerSlotIndex) instanceof IVirtualSlot ivs) {
                powerVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(powerVs);
        }

        // 存储槽（45）：改「单列纵向滚动」——为每个槽建一个 GuiVirtualSlot 并绑定其 IVirtualSlot，
        // 与输入/输出/升级槽同款；实际位置由 applyStorageScrollLayout() 按滚动偏移摆放。
        // 菜单内存储槽起始索引 = 2*输入 + (有堆叠升级?3:2) + 能量槽 + 调味料槽数。
        int storageMenuStart = inputSlots * 2 + (hasStackUpgrade ? 3 : 2) + 1 + GrillFactoryBlockEntity.SEASONING_SLOTS;
        storageWidgets.clear();
        for (int i = 0; i < GrillFactoryBlockEntity.STORAGE_SLOTS; i++) {
            int menuSlotIndex = storageMenuStart + i;
            GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.EXTRA, this, STORAGE_COL_X, STORAGE_COL_TOP);
            if (menuSlotIndex < menu.slots.size() && menu.slots.get(menuSlotIndex) instanceof IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
            storageWidgets.add(vs);
        }
        applyStorageScrollLayout();

        // 调味料槽背景（输出网格下方居中）
        int seasoningStartIndex = menu.getMachine().getSeasoningSlotStart();
        int seasoningGridX = outputStartX + (cols * 18 - GrillFactoryBlockEntity.SEASONING_SLOTS * 18) / 2 - 1;
        int seasoningGridY = outputStartY + rows * 18 + 6;
        for (int i = 0; i < GrillFactoryBlockEntity.SEASONING_SLOTS; i++) {
            int menuSlotIndex = seasoningStartIndex + i;
            GuiVirtualSlot svs = new GuiVirtualSlot(SlotType.EXTRA, this,
                    seasoningGridX + i * 18, seasoningGridY);
            if (menuSlotIndex < menu.slots.size() && menu.slots.get(menuSlotIndex) instanceof IVirtualSlot ivs) {
                svs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(svs);
        }

        // 侧栏 tab 统一走 Mek 原生 MekCkTabElement（见 addTabElements）。注册位置在全部
        // 虚拟槽/进度条/能量条/存储槽之后：Mek 的 GuiMekanism#mouseClicked 对 children()
        // 倒序遍历、命中即返回，越晚注册命中优先。tab 与槽位/列表行在 x 上不重叠（左列 x=-26、
        // 右列 x=imageWidth，列表行 x=16..imageWidth-32），先后不影响命中。
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

        // ME 自动处理覆盖层的列表项按钮**最后注册**：Mek 的 GuiMekanism#mouseClicked 是
        // 「children() 倒序遍历、命中即返回」，即**越晚注册命中优先**。列表前几行与输入/输出
        // 虚拟槽在 y 上重叠（行 0 = y37..56，输入槽首行 = y41..59），只有排在槽位之后才能像旧
        // handleAutoProcessClick（在 super.mouseClicked 之前拦截）那样压过槽位点击。
        // 默认隐藏，由 syncAutoProcessOverlayButtons() 同步。
        initAutoProcessRowButtons();
    }

    /**
     * 侧栏 7 个 tab 统一走 Mek {@link MekCkTabElement}（继承 {@code GuiInsetElement}）：
     * 三层绘制与旧手绘逐参数一致（坐标/尺寸/间距常量一字未改），tooltip 改走
     * {@code GuiMekanism#renderLabels} 的元素通道 —— 那是渲染管线最后一层，结构上不会再被槽位盖住。
     * 旧实现在 {@code renderBg()} 里直绘、在 {@code mouseClicked} 里手算命中矩形，现已一并移除。
     * <p>图标沿用旧版：侧配/升级/自动分配/红石用 Mek 官方贴图；下单（自绘清单+箭头）、
     * ME 自动处理（借用 sorting.png）、工作模式（借用 configuration.png）在 Mek 里无对应图标，保持原样。</p>
     */
    private void addTabElements() {
        // ── 左列（5 个）──
        addRenderableWidget(new MekCkTabElement(this, CONFIG_TEXTURE, TAB_X, CONFIG_TAB_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> false,
                mekanism.client.SpecialColors.TAB_CONFIGURATION,
                () -> List.of(Component.translatable("tooltip.mekck.side_config")),
                this::openSideConfigWindow, null));

        addRenderableWidget(new MekCkTabElement(this, SORTING_TEXTURE, TAB_X, AUTO_DIST_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> menu.getAutoDistribute(),
                mekanism.client.SpecialColors.TAB_FACTORY_SORT,
                () -> List.of(Component.translatable("tooltip.mekck.auto_sort")),
                () -> ModMessages.sendToServer(new AutoDistributePacket(menu.getBlockPos())), null));

        addRenderableWidget(new MekCkTabElement(this, MachineTabIcons.ORDER, TAB_X, ORDER_TAB_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> orderMode || menu.getOrderQuantity() > 0,
                mekanism.client.SpecialColors.TAB_CONTAINER_EDIT_MODE,
                () -> List.of(Component.translatable("gui.mekck.grill_order")),
                this::toggleOrderMode, null));

        addRenderableWidget(new MekCkTabElement(this, SORTING_TEXTURE, TAB_X, AUTO_PROCESS_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> autoProcessMode,
                mekanism.client.SpecialColors.TAB_FACTORY_SORT,
                // 旧版这个 tab 本来就没有 tooltip（见旧 render() 的 tooltip 段），保持不弹。
                List::of,
                this::toggleAutoProcessMode, null));

        // 工作模式切换 tab：图标/染色 —— Mek 的 SpecialColors 里没有「模式切换」这一档。
        // TODO(tabMek): 工作模式 tab 无对应 TAB_* 颜色，先按旧版保持 holder 无染色；
        //   若后续要在 Mek 侧补一档颜色，只改这里的 tint 参数即可。
        MekCkTabElement workModeTab = new MekCkTabElement(this, CONFIG_TEXTURE, TAB_X, WORK_MODE_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> menu.getWorkMode() == GrillFactoryBlockEntity.WorkMode.ORDER,
                null,
                () -> List.of(
                        Component.literal(menu.getWorkMode() == GrillFactoryBlockEntity.WorkMode.ORDER
                                ? "下单模式：管道/Shift 材料进存储，下单后制作"
                                : "默认模式：材料直接进输入格加工"),
                        Component.literal("点击切换工作模式")),
                () -> ModMessages.sendToServer(new GrillWorkModePacket(menu.getBlockPos())),
                null);
        // 旧版在按钮内缩 1px 叠的黄色高亮（0x55FFDD4F），原样搬到图标之上的附加层。
        workModeTab.overlayLayer(gg -> {
            if (menu.getWorkMode() == GrillFactoryBlockEntity.WorkMode.ORDER) {
                gg.fill(workModeTab.buttonX() + 1, workModeTab.buttonY() + 1,
                        workModeTab.buttonX() + MekCkTabElement.INNER - 1,
                        workModeTab.buttonY() + MekCkTabElement.INNER - 1, 0x55FFDD4F);
            }
        });
        addRenderableWidget(workModeTab);

        // ── 右列（2 个）──
        addRenderableWidget(new MekCkTabElement(this, UPGRADE_TEXTURE, imageWidth, UPGRADE_TAB_Y, false,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> false,
                mekanism.client.SpecialColors.TAB_UPGRADE,
                () -> List.of(Component.translatable("tooltip.mekck.upgrade")),
                this::openUpgradeWindow, null));

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

    /** 下单面板开关（原在 mouseClicked 内联，迁出为 tab 动作）。 */
    private void toggleOrderMode() {
        orderMode = !orderMode;
        if (orderMode) {
            orderListDirty = true;
            selectedRecipe = null;
            orderQuantity = 1;
        } else {
            mePanel.onClosed();
        }
    }

    /** ME 自动处理面板开关（原在 mouseClicked 内联，迁出为 tab 动作）。 */
    private void toggleAutoProcessMode() {
        autoProcessMode = !autoProcessMode;
        orderMode = false;
        if (autoProcessMode) {
            autoDataRequested = false;
            ModMessages.sendToServer(new AutoProcessListRequestPacket(menu.getBlockPos()));
        }
    }

    /** 按当前滚动偏移摆放 45 个存储 GuiVirtualSlot：可视窗口内放到单列，其余移到屏外（自动不可见/不可点）。 */
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

    private static String formatItemCount(int count) {
        // 统一走 CountFormat（含十亿档；21 亿不再显示成 2147.5M）
        return cn.ism.mekck.client.CountFormat.compact(count);
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 20, inventoryLabelY, titleTextColor());
        // 温度系统：显示机身温度（摄氏度）
        guiGraphics.drawString(font, String.format("温度: %.1f℃", menu.getTemperature() / 100.0), 20, 78, 0xFFFF5555);
        // 工作模式指示（输出网格上方）
        int cols = (int) Math.ceil(Math.sqrt(tier.processes));
        boolean order = menu.getWorkMode() == GrillFactoryBlockEntity.WorkMode.ORDER;
        String modeText = order ? "下单模式" : "默认模式";
        int modeX = 38 + cols * 18 + 30 + cols * 18 - font.width(modeText) - 4;
        drawString(guiGraphics, Component.literal(modeText), modeX, 8, order ? 0xFFFFDD4F : 0xFF50D050);
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        int x = leftPos;
        int y = topPos;

        // 侧栏 7 个 tab（侧配 / 升级 / 自动分配 / 下单 / ME 自动处理 / 工作模式 / 红石）已全部
        // 迁到 addTabElements()（MekCkTabElement），renderBg 不再手绘；AE2 网络拉料两枚按钮
        // 也已在 addGuiElements() 末尾由 NetworkPullButton.register(...) 注册为 Mek 原生元素。

        // 存储区（单列纵向滚动）：标签 + 滚动条（轨道 + 滑块）
        int storageLblX = x + STORAGE_COL_X;
        guiGraphics.drawString(font, "存储", storageLblX, y + STORAGE_COL_TOP - 10, 0xFFAAAAAA);
        if (storageMaxScroll > 0) {
            int trackX = x + STORAGE_COL_X + STORAGE_SLOT_PITCH + 2;
            int trackTop = y + STORAGE_COL_TOP;
            int trackH = storageVisibleRows * STORAGE_SLOT_PITCH;
            guiGraphics.fill(trackX, trackTop, trackX + 4, trackTop + trackH, 0xFF2B2B2B); // 轨道
            int thumbH = Math.max(8, trackH * storageVisibleRows / GrillFactoryBlockEntity.STORAGE_SLOTS);
            int thumbY = trackTop + (trackH - thumbH) * storageScrollOffset / storageMaxScroll;
            guiGraphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFF8C8C8C); // 滑块
        }

        // 调味料启用按钮（每个调味料槽下方）：默认工作模式下自动调味的开关
        int sCols = (int) Math.ceil(Math.sqrt(tier.processes));
        int sRows = (int) Math.ceil((double) tier.processes / sCols);
        int sOutputBaseX = 38 + sCols * 18 + GAP_BETWEEN;
        int sSeasonGridX = sOutputBaseX + (sCols * 18 - GrillFactoryBlockEntity.SEASONING_SLOTS * 18) / 2;
        int sBtnY = y + 41 + sRows * 18 + 6 + 18 + 2;
        for (int i = 0; i < GrillFactoryBlockEntity.SEASONING_SLOTS; i++) {
            int bx = x + sSeasonGridX + i * 18 - 1;
            boolean enabled = menu.getSeasoningEnabled(i);
            boolean hovered = mouseX >= bx && mouseX < bx + 18 && mouseY >= sBtnY && mouseY < sBtnY + 10;
            int color = enabled ? (hovered ? 0xFF7CE25F : 0xFF2E8B2E) : (hovered ? 0xFF666666 : 0xFF333333);
            guiGraphics.fill(bx, sBtnY, bx + 18, sBtnY + 10, color);
            guiGraphics.drawString(font, enabled ? "开" : "关", bx + 5, sBtnY + 1, 0xFFFFFFFF);
        }

        // ME 自动处理覆盖层的面板底板。必须画在 widget 层之前（renderBg 早于 renderables），
        // 否则会把已注册成 Mek 原生按钮的列表项整块盖住（与 CuttingMachineFactoryScreen 同形）。
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

        // ME 自动处理覆盖层按需出现：每帧同步列表项按钮的显隐 / 标签 / 取色 / 滚动重绑，
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
        // 这里只在按钮之上补画图标、勾选框与滚动指示条。
        if (autoProcessMode) {
            renderAutoProcessRowDecorations(guiGraphics, x, y);
        }

        // Order mode overlay (on top of everything)
        if (orderMode) {
            renderOrderMode(guiGraphics, x, y, mouseX, mouseY);
        } else if (qtyField != null) {
            qtyField.setVisible(false); // 退下单面板时收起自定义输入框
        }

        // 当前订单进度（非下单模式时显示在左上角）
        if (!orderMode && menu.getOrderQuantity() > 0) {
            int orderPanelX = x + 5;
            int orderPanelY = y + 5;
            int qty = menu.getOrderQuantity();
            int completed = Math.min(menu.getOrderCompleted(), qty);
            String orderText = "当前订单: " + completed + "/" + qty;

            // 附带调味料信息
            int seasonIdx = menu.getOrderSeasoningIndex();
            if (seasonIdx >= 0 && minecraft != null && minecraft.level != null) {
                List<BarbequesDelightCompat.SeasoningInfo> all = BarbequesDelightCompat.getAllSeasonings();
                if (seasonIdx < all.size()) {
                    orderText += " [" + all.get(seasonIdx).getIcon().getHoverName().getString() + "]";
                }
            }
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

            this.orderCancelBtnX = cancelBtnX;
            this.orderCancelBtnY = cancelBtnY;
            this.orderCancelBtnW = cancelBtnW;
            this.orderCancelBtnH = cancelBtnH;
        } else {
            this.orderCancelBtnX = -1;
            this.orderCancelBtnY = -1;
        }

        // 7 个侧栏 tab 的 tooltip 已迁到 MekCkTabElement#renderToolTip，
        // 由 GuiMekanism#renderLabels 在渲染管线最后一层统一派发（文案逐字沿用旧版）。
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

    // ================== 下单面板（含调味） ==================

    private void renderOrderMode(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY) {
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        // ME 来源：整块换成 AE 终端风格面板（本机"调味下单"代码原样保留在下面）
        mePanel.bind(menu.getBlockPos());
        if (mePanel.isMe()) {
            mePanel.render(guiGraphics, font, panelX, panelY, panelW, panelH, mouseX, mouseY, 0f);
            return;
        }

        // Semi-transparent background
        guiGraphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xCC000000);

        // Title
        guiGraphics.drawString(font, "调味下单", panelX + 4, panelY + 4, 0xFFFFFFFF);

        // Refresh available recipes list
        if (orderListDirty) {
            availableRecipes = menu.getMachine().getAvailableRecipes();
            orderListDirty = false;
            // 默认选中当前订单的调味料
            selectedSeasoningIndex = menu.getOrderSeasoningIndex();
        }

        // Recipe list (scrollable)
        int listTop = panelY + 16;
        int listBottom = listTop + ORDER_LIST_HEIGHT;
        int listWidth = panelW - 8;

        if (availableRecipes.isEmpty()) {
            guiGraphics.drawString(font, "请先放入食材", panelX + 4, listTop + 4, 0xFFAAAAAA);
        }

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
            guiGraphics.drawString(font, name, panelX + 24, entryY + 6, 0xFFFFFFFF);
        }

        // Scroll indicators
        if (orderScrollOffset > 0) {
            guiGraphics.drawString(font, "↑", panelX + listWidth - 10, listTop, 0xFFFFFFFF);
        }
        if (orderScrollOffset + ORDER_LIST_ROWS < availableRecipes.size()) {
            guiGraphics.drawString(font, "↓", panelX + listWidth - 10, listBottom - 10, 0xFFFFFFFF);
        }

        // Quantity controls
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
            if (qtyValues[i] == -1 && customInputMode) {
                color = 0xFF44AA44;
            }
            guiGraphics.fill(bx, qtyBtnY, bx + qtyBtnW, qtyBtnY + qtyBtnH, color);
            String label = qtyLabels[i];
            int labelW = font.width(label);
            guiGraphics.drawString(font, label, bx + (qtyBtnW - labelW) / 2, qtyBtnY + 3, 0xFFFFFFFF);
        }

        // Custom input text field（§F23：DIY 白框改 GuiTextField 自绘，这里同步可见性并按本面板位移动；
        // 注：belowQtyY 仍供下方风味行布局使用，保留）
        int belowQtyY = qtyBtnY + qtyBtnH + 2;
        if (qtyField != null) {
            qtyField.setVisible(customInputMode);
            if (customInputMode) {
                qtyField.resize(qtyBtnX - leftPos, belowQtyY - topPos, 80, 14);
            }
        }

        // Seasoning row（风味选择）
        List<BarbequesDelightCompat.SeasoningInfo> seasonings = BarbequesDelightCompat.getAllSeasonings();
        int seasonLabelY = belowQtyY + 16;
        guiGraphics.drawString(font, "风味:", panelX + 4, seasonLabelY + 4, 0xFFFFCC66);
        int seasonBtnX = panelX + 34;
        int seasonBtnSize = 18;
        // 第一个按钮 = 无（不调味）
        boolean noneHovered = mouseX >= seasonBtnX && mouseX < seasonBtnX + seasonBtnSize
                && mouseY >= seasonLabelY && mouseY < seasonLabelY + seasonBtnSize;
        int noneColor = selectedSeasoningIndex == -1 ? 0xFF44AA44 : (noneHovered ? 0xFF666666 : 0x55444444);
        guiGraphics.fill(seasonBtnX, seasonLabelY, seasonBtnX + seasonBtnSize, seasonLabelY + seasonBtnSize, noneColor);
        guiGraphics.drawString(font, "无", seasonBtnX + 5, seasonLabelY + 5, 0xFFFFFFFF);
        for (int i = 0; i < seasonings.size(); i++) {
            int bx = seasonBtnX + (i + 1) * (seasonBtnSize + 2);
            if (bx + seasonBtnSize > panelX + panelW - 4) break;
            boolean hovered = mouseX >= bx && mouseX < bx + seasonBtnSize
                    && mouseY >= seasonLabelY && mouseY < seasonLabelY + seasonBtnSize;
            boolean isSelected = selectedSeasoningIndex == i;
            guiGraphics.fill(bx, seasonLabelY, bx + seasonBtnSize, seasonLabelY + seasonBtnSize,
                    isSelected ? 0xFF44AA44 : (hovered ? 0xFF666666 : 0xFF333333));
            guiGraphics.renderItem(seasonings.get(i).getIcon(), bx + 1, seasonLabelY + 1);

            // Tooltip
            if (hovered) {
                displayTooltips(guiGraphics, mouseX, mouseY,
                        seasonings.get(i).getIcon().getHoverName(),
                        Component.literal("消耗耐久为烤串调味"));
            }
        }

        // Max quantity hint
        int hintY = seasonLabelY + seasonBtnSize + 2;
        if (selectedRecipe != null) {
            int maxQty = menu.getMachine().getMaxConsumableCountForOrder(selectedRecipe);
            if (maxQty > 0) {
                guiGraphics.drawString(font, "最大: " + maxQty, qtyBtnX, hintY, 0xFFAAAAAA);
            }
        }

        // Confirm button
        int confirmBtnY = hintY + 14;
        int confirmBtnW = 60;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW * 2 - 8) / 2;
        boolean confirmHovered = mouseX >= confirmBtnX && mouseX < confirmBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH;
        int confirmColor = confirmHovered ? 0xFF44AA44 : 0xFF228822;
        guiGraphics.fill(confirmBtnX, confirmBtnY, confirmBtnX + confirmBtnW, confirmBtnY + confirmBtnH, confirmColor);
        String confirmText = "确认下单";
        guiGraphics.drawString(font, confirmText, confirmBtnX + (confirmBtnW - font.width(confirmText)) / 2, confirmBtnY + 5, 0xFFFFFFFF);

        // Cancel button
        int cancelX = confirmBtnX + confirmBtnW + 8;
        boolean cancelHovered = mouseX >= cancelX && mouseX < cancelX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH;
        int cancelColor = cancelHovered ? 0xFFAA4444 : 0xFF882222;
        guiGraphics.fill(cancelX, confirmBtnY, cancelX + confirmBtnW, confirmBtnY + confirmBtnH, cancelColor);
        String cancelText = "取消";
        guiGraphics.drawString(font, cancelText, cancelX + (confirmBtnW - font.width(cancelText)) / 2, confirmBtnY + 5, 0xFFFFFFFF);
        // 来源切换按钮**最后画**（本机列表会盖住它，点击判定仍在前面）
        mePanel.renderModeButtons(guiGraphics, font, panelX, panelY, panelW, mouseX, mouseY);
    }

    private boolean handleOrderClick(double mouseX, double mouseY, int x, int y) {
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        // Click outside the panel closes order mode
        if (mouseX < panelX || mouseX > panelX + panelW || mouseY < panelY || mouseY > panelY + panelH) {
            orderMode = false;
            mePanel.onClosed();
            return true;
        }

        // ME 来源：整块交给共用面板（几何与渲染共用）；ME 下单同样带上当前选中的风味
        mePanel.bind(menu.getBlockPos());
        if (mePanel.isMe()) {
            return mePanel.mouseClicked(mouseX, mouseY, 0, panelX, panelY, panelW, panelH,
                    (recipeId, qty) -> ModMessages.sendToServer(new NetworkOrderPacket(
                            menu.getBlockPos(), recipeId.toString(), qty, selectedSeasoningId())));
        }
        if (mePanel.handleModeClick(mouseX, mouseY, panelX, panelY, panelW)) {
            return true;
        }

        // Recipe list click
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
                exitCustomInput();
                return true;
            }
        }

        // Scroll up / down
        int listBottom = listTop + ORDER_LIST_HEIGHT;
        if (mouseX >= panelX + listWidth - 12 && mouseX < panelX + listWidth
                && mouseY >= listTop && mouseY < listTop + 10) {
            orderScrollOffset = Math.max(0, orderScrollOffset - 1);
            return true;
        }
        if (mouseX >= panelX + listWidth - 12 && mouseX < panelX + listWidth
                && mouseY >= listBottom - 10 && mouseY < listBottom) {
            orderScrollOffset = Math.min(Math.max(0, availableRecipes.size() - ORDER_LIST_ROWS), orderScrollOffset + 1);
            return true;
        }

        // Quantity buttons
        int qtyY = listBottom + 4;
        int qtyBtnX = panelX + 4;
        int qtyBtnY = qtyY + 10;
        int qtyBtnW = 24;
        int qtyBtnH = 14;
        int[] qtyValues = {1, 16, 32, 64, -1, -2};
        for (int val : qtyValues) {
            int idx = indexOfQty(val, qtyValues);
            int bx = qtyBtnX + idx * (qtyBtnW + 2);
            if (mouseX >= bx && mouseX < bx + qtyBtnW && mouseY >= qtyBtnY && mouseY < qtyBtnY + qtyBtnH) {
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

        // Seasoning buttons
        List<BarbequesDelightCompat.SeasoningInfo> seasonings = BarbequesDelightCompat.getAllSeasonings();
        int seasonLabelY = qtyBtnY + qtyBtnH + 18;
        int seasonBtnX = panelX + 34;
        int seasonBtnSize = 18;
        if (mouseX >= seasonBtnX && mouseX < seasonBtnX + seasonBtnSize
                && mouseY >= seasonLabelY && mouseY < seasonLabelY + seasonBtnSize) {
            selectedSeasoningIndex = -1;
            return true;
        }
        for (int i = 0; i < seasonings.size(); i++) {
            int bx = seasonBtnX + (i + 1) * (seasonBtnSize + 2);
            if (bx + seasonBtnSize > panelX + panelW - 4) break;
            if (mouseX >= bx && mouseX < bx + seasonBtnSize
                    && mouseY >= seasonLabelY && mouseY < seasonLabelY + seasonBtnSize) {
                selectedSeasoningIndex = i;
                return true;
            }
        }

        // Confirm button
        int hintY = seasonLabelY + seasonBtnSize + 2;
        int confirmBtnY = hintY + 14;
        int confirmBtnW = 60;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW * 2 - 8) / 2;
        if (mouseX >= confirmBtnX && mouseX < confirmBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH) {
            if (selectedRecipe != null && orderQuantity > 0) {
                String seasoningId = null;
                if (selectedSeasoningIndex >= 0 && selectedSeasoningIndex < seasonings.size()) {
                    seasoningId = seasonings.get(selectedSeasoningIndex).id();
                }
                ModMessages.sendToServer(new GrillSeasoningOrderPacket(
                        menu.getBlockPos(), selectedRecipe.getId(), orderQuantity, seasoningId));
                orderMode = false;
                selectedRecipe = null;
                orderQuantity = 1;
                exitCustomInput();
            }
            return true;
        }

        // Cancel button
        int cancelX = confirmBtnX + confirmBtnW + 8;
        if (mouseX >= cancelX && mouseX < cancelX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH) {
            orderMode = false;
            selectedRecipe = null;
            orderQuantity = 1;
            exitCustomInput();
            return true;
        }

        return true;
    }

    /** 当前选中的风味 id（无 = null）。本机与 ME 两条下单路径共用。 */
    private String selectedSeasoningId() {
        List<BarbequesDelightCompat.SeasoningInfo> seasonings = BarbequesDelightCompat.getAllSeasonings();
        if (selectedSeasoningIndex >= 0 && selectedSeasoningIndex < seasonings.size()) {
            return seasonings.get(selectedSeasoningIndex).id();
        }
        return null;
    }

    private static int indexOfQty(int value, int[] values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) return i;
        }
        return 0;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 7 个侧栏 tab 的点击已交给 MekCkTabElement#onClick —— 它们是 renderable widget，
        // 由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        if (button == 0) {
            int x = leftPos;
            int y = topPos;

            // ME 自动处理面板点击：面板外关闭、面板内由 Mek 原生列表项按钮命中
            if (autoProcessMode) {
                return handleAutoProcessClick(mouseX, mouseY);
            }

            // If in order mode, handle order clicks
            if (orderMode) {
                return handleOrderClick(mouseX, mouseY, x, y);
            }

            // 调味料启用按钮（每个调味料槽下方）
            int sCols = (int) Math.ceil(Math.sqrt(tier.processes));
            int sRows = (int) Math.ceil((double) tier.processes / sCols);
            int sOutputBaseX = 38 + sCols * 18 + GAP_BETWEEN;
            int sSeasonGridX = sOutputBaseX + (sCols * 18 - GrillFactoryBlockEntity.SEASONING_SLOTS * 18) / 2;
            int sBtnY = y + 41 + sRows * 18 + 6 + 18 + 2;
            for (int i = 0; i < GrillFactoryBlockEntity.SEASONING_SLOTS; i++) {
                int bx = x + sSeasonGridX + i * 18 - 1;
                if (mouseX >= bx && mouseX < bx + 18 && mouseY >= sBtnY && mouseY < sBtnY + 10) {
                    ModMessages.sendToServer(new GrillSeasoningTogglePacket(menu.getBlockPos(), i));
                    return true;
                }
            }

            // Cancel current order button
            if (orderCancelBtnX >= 0 && mouseX >= orderCancelBtnX && mouseX < orderCancelBtnX + orderCancelBtnW
                    && mouseY >= orderCancelBtnY && mouseY < orderCancelBtnY + orderCancelBtnH) {
                ModMessages.sendToServer(new GrillSeasoningOrderPacket(menu.getBlockPos(), null, 0, null));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (orderMode && mePanel.isMe()) {
            return mePanel.mouseScrolled(delta);
        }
        // ME 自动处理列表：条目多于面板容量时滚轮翻页（面板外不接，滚轮照旧交给存储列 / 底层）。
        if (autoProcessMode && scrollAutoProcess(mouseX, mouseY, delta)) {
            return true;
        }
        // 存储单列滚动：仅在非各 overlay 模式且鼠标悬停列上时接，避免与 ME 面板抢滚轮。
        if (!orderMode && !autoProcessMode && storageMaxScroll > 0
                && isMouseOverStorageColumn(mouseX, mouseY)) {
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
     * 所以<b>没有任何条目会因为池小而既不渲染也不可点</b>（原手绘版把超出的行丢在面板外）。
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

    /** 池槽位号 → 条目下标（滚动窗口的偏移在这里统一收口）。 */
    private int autoRowDataIndex(int row) {
        return row + autoRowScroll;
    }

    /** 最大滚动偏移 = 条目数 - 窗口行数（不小于 0）。 */
    private int autoRowMaxScroll() {
        return Math.max(0, autoShownIds.size() - autoRowCapacity);
    }

    /**
     * 列表项取色：已选=深蓝、未选=灰（取自 GuiMekCkSideConfiguration.colorForMode 的两档）；
     * hover 交给 Mek 原生按钮，不再手算。
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
     * 否则底板会把这些按钮整块盖住（与 CuttingMachineFactoryScreen 同形）。
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
            // y = panelY+35：与旧手绘版的 listTop+8（listTop = panelY+27）同像素。
            guiGraphics.drawString(font, "ME \u7F51\u7EDC\u4E2D\u65E0\u53EF\u5904\u7406\u6750\u6599",
                    panelX + 8, panelY + 35, 0xFFAAAAAA);
        }
    }

    /**
     * 列表项按钮之上的装饰层：勾选框 + 物品图标（原生按钮画不了这两种，故仍在 widget 层之后补画），
     * 外加列表溢出时的滚动指示条。行名已改由 MekCkButtons#color 的标签居中渲染，不再在此叠字。
     * 坐标与旧手绘版逐像素一致；勾选框 / 图标只画当前滚动窗口内的行。
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
     * 与旧 handleAutoProcessClick 的判定逐像素一致。
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

    /**
     * ME 自动处理面板点击：面板外关闭覆盖层（与旧手绘版同语义），面板内由 Mek 原生按钮命中
     * ——列表项已是 widget，由 {@code super.mouseClicked} 派发；这里仍吞掉左键，不落到槽位。
     */
    private boolean handleAutoProcessClick(double mouseX, double mouseY) {
        if (!isMouseOverAutoProcessPanel(mouseX, mouseY)) {
            autoProcessMode = false;
            return true;
        }
        super.mouseClicked(mouseX, mouseY, 0);
        return true;
    }
}