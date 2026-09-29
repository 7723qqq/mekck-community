package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.blockentity.CookingFactoryBlockEntity;
import cn.ism.mekck.menu.CookingFactoryMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkMissingRequestPacket;
import cn.ism.mekck.network.NetworkOrderPacket;
import cn.ism.mekck.network.NetworkRecipeRequestPacket;
import cn.ism.mekck.network.OrderRecipePacket;
import cn.ism.mekck.network.RedstoneControlPacket;
import cn.ism.mekck.util.AE2Compat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.element.bar.GuiBar.IBarInfoHandler;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiSlot;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.container.slot.IVirtualSlot;
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
import net.minecraft.world.item.crafting.Recipe;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

public final class CookingFactoryScreen extends GuiMekanism<CookingFactoryMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    private final CuttingMachineFactoryTier tier;
    private final int storageSlots;
    private boolean orderMode = false;
    // ME 网络下单模式（烹饪工厂作为“无限容量合成CPU”：从 ME 网络取料制作）
    private boolean networkOrderMode = false;
    private boolean networkDataRequested = false;
    private List<ResourceLocation> networkRecipeIds = List.of();
    private Map<ResourceLocation, Integer> networkMaxCraftable = Map.of();
    /** AE 终端式缺料清单（服务端回包）：选中配方 / 改数量后问一次，材料够则为 null。 */
    private String networkMissingText;

    // Mekanism-style tab positions
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;
    private static final int AUTO_DIST_Y = 34;
    private static final int TAB_OUTER_W = 24;
    private static final int TAB_OUTER_H = 24;

    private static final int TAB_ICON_SIZE = 16;
    private static final int TAB_ICON_OFFSET = 4;

    private static final int UPGRADE_TAB_Y = 6;

    // Mekanism textures
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");
    private static final ResourceLocation SORTING_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "sorting.png");
    private static final ResourceLocation BUTTON_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "button.png");

    // Upgrade page layout
    private static final int UPGRADE_SLOT_X = 40;
    private static final int UPGRADE_SPEED_Y = 46;
    private static final int UPGRADE_ENERGY_Y = 72;
    private static final int UPGRADE_STACK_Y = 98;

    // Redstone control tab (right side, identical position to Mekanism's factory: x = imageWidth, y = 137)
    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;
    private static final int REDSTONE_TINT = 0xFFC9071F;

    // Redstone control icon textures (Mekanism)
    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    // Slot layout constants (matching the menu)
    private static final int INPUT_START_X = 38;
    private static final int INPUT_START_Y = 41;
    private static final int INPUT_COLS = 3;
    private static final int INPUT_SPACING = 18;
    private static final int OUTPUT_START_X = 130;
    private static final int OUTPUT_START_Y = 41;
    private static final int OUTPUT_COLS = 3;
    private static final int RETURN_START_X = 130;
    private static final int RETURN_Y = OUTPUT_START_Y + 3 * INPUT_SPACING + 4;
    private static final int STORAGE_ROWS = 12;
    private static final int STORAGE_START_Y = 63; // 存储区上移 1 格（原 81）
    private static final int INV_TOP = 160; // 玩家物品栏固定放在 GUI 内部

    // Fluid gauge layout (3 vertical fluid bars, inside the GUI box below the input grid)
    private static final int FLUID_GAUGE_X = 40;
    private static final int FLUID_GAUGE_Y = 88;
    private static final int FLUID_GAUGE_SPACING = 28;

    // Order panel layout
    private static final int ORDER_PANEL_LEFT = 10;
    private static final int ORDER_PANEL_TOP = 10;
    private static final int ORDER_PANEL_WIDTH = 150;
    private static final int ORDER_ENTRY_HEIGHT = 20;
    private static final int ORDER_LIST_ROWS = 6;
    /** ME 网格的格子边长（像素）。本机模式仍是行式列表，两者互不影响。 */
    private static final int ME_CELL = 18;
    private static final int ORDER_LIST_HEIGHT = ORDER_ENTRY_HEIGHT * ORDER_LIST_ROWS;

    // AE 纹理资源（直接引用 ae2 的 GUI 贴图；用无格子的 background.png 作面板，避免终端格子）
    private static final ResourceLocation AE_PANEL = new ResourceLocation("ae2", "textures/guis/background.png");
    private static final ResourceLocation AE_TEXT_FIELD = new ResourceLocation("ae2", "textures/guis/text_field.png");

    // Mekanism 风格面板（本机下单模式）
    private static final ResourceLocation MEK_BASE = MekanismUtils.getResource(ResourceType.GUI, "base.png");

    // AE 风格配色（淡蓝版本：比原深蓝更浅，接近 AE 亮色终端观感）
    private static final int AE_BG = 0xFF2B4764;
    private static final int AE_BORDER = 0xFF7BA8CC;
    private static final int AE_SLOT = 0xFF3A5C80;
    private static final int AE_SLOT_HOVER = 0xFF5483AE;
    private static final int AE_BTN = 0xFF41648A;
    private static final int AE_BTN_HOVER = 0xFF5B87B0;
    private static final int AE_BTN_ACTIVE = 0xFF76A4CF;
    private static final int AE_TEXT = 0xFFFFFFFF;
    private static final int AE_TEXT_DIM = 0xFFB4CFE6;
    private static final int AE_TEXT_ACCENT = 0xFFB8DBF5;
    private static final int AE_TEXT_YELLOW = 0xFFFFFF55;

    private final boolean hasStackUpgrade;
    private List<Recipe<?>> availableRecipes;
    /** 终端式搜索框（按产物名称 / 注册名过滤配方列表）。 */
    private final cn.ism.mekck.client.OrderSearchBox orderSearch = new cn.ism.mekck.client.OrderSearchBox();
    private Recipe<?> selectedRecipe;
    private int orderQuantity = 1;
    private int orderScrollOffset = 0;
    private boolean orderListDirty = true;
    // §F23：「自定义数量」输入改 Mekanism GuiTextField（同 §F22 范式）；本机/ME 两面板共用一个控件，
    // 渲染时按各自 DIY 位 resize；customInputMode 仅作编辑态标记，字符串自持与手绘/键鼠分支删除。
    private boolean customInputMode = false;
    private GuiTextField qtyField;

    // Cancel button bounds for order progress display
    private int orderCancelBtnX = -1;
    private int orderCancelBtnY = -1;
    private int orderCancelBtnW = 30;
    private int orderCancelBtnH = 14;

    public CookingFactoryScreen(CookingFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.tier = menu.getTier();
        this.storageSlots = menu.getStorageSlots();
        this.hasStackUpgrade = menu.hasStackUpgrade();

        imageWidth = Math.max(176, OUTPUT_START_X + 3 * INPUT_SPACING + 20);
        imageHeight = INV_TOP + 58 + 26; // 底部多留 8px，避免物品栏超出 GUI
        inventoryLabelY = INV_TOP - 10;
        dynamicSlots = true;
    }

    /** 服务端回复的 ME 网络可下单配方数据。 */
    public void setNetworkOrderData(List<String> ids, Map<String, Integer> max) {
        List<ResourceLocation> newIds = new ArrayList<>();
        Set<ResourceLocation> seenIds = new java.util.HashSet<>();
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && seenIds.add(rl)) newIds.add(rl);
        }
        Map<ResourceLocation, Integer> newMax = new HashMap<>();
        for (Map.Entry<String, Integer> e : max.entrySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
            if (rl != null) newMax.put(rl, e.getValue());
        }
        this.networkRecipeIds = newIds;
        this.networkMaxCraftable = newMax;
        this.networkDataRequested = true;
    }

    /** 把网络配方 id 解析为客户端 Recipe（用于显示图标/名称），并按 id 去重。 */
    private List<Recipe<?>> resolveNetworkRecipes() {
        List<Recipe<?>> out = new ArrayList<>();
        if (minecraft == null || minecraft.level == null) return out;
        Set<ResourceLocation> seen = new java.util.HashSet<>();
        for (ResourceLocation id : networkRecipeIds) {
            if (!seen.add(id)) continue;
            minecraft.level.getRecipeManager().byKey(id).ifPresent(out::add);
        }
        return out;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // §F23：自定义数量输入框（敲数字回车提交，DIGIT 正整数；默认隐藏，进「自」档才显示；
        // 初坐标给本机面板布局，渲染时按当前面板 resize）
        qtyField = new GuiTextField(this, ORDER_PANEL_LEFT + 4, ORDER_PANEL_TOP + 16 + ORDER_LIST_HEIGHT + 4 + 10 + 16, 80, 14)
                .setInputValidator(InputValidator.DIGIT)
                .configureDigitalBorderInput(this::commitQty);
        qtyField.setMaxLength(9);
        qtyField.setText("");
        qtyField.setVisible(false);
        addRenderableWidget(qtyField);

        // Render slot backgrounds for input slots (6 slots: 3 cols x 2 rows)
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

        // 存储槽背景：与菜单同一套**双侧紧凑布局**（见 CookingFactoryMenu#storageSlotX/Y）。
        for (int i = 0; i < storageSlots; i++) {
            int x = CookingFactoryMenu.storageSlotX(i, imageWidth) - 1;
            int y = CookingFactoryMenu.storageSlotY(i) - 1;
            addRenderableWidget(new GuiSlot(SlotType.EXTRA, this, x, y));
        }

        // Output slot backgrounds: 3x3 grid (9 slots)
        int outputSlotStart = menu.getMachine().getOutputSlot();
        for (int i = 0; i < CookingFactoryBlockEntity.OUTPUT_SLOTS; i++) {
            int col = i % OUTPUT_COLS;
            int row = i / OUTPUT_COLS;
            int sx = OUTPUT_START_X + col * INPUT_SPACING - 1;
            int sy = OUTPUT_START_Y + row * INPUT_SPACING - 1;
            GuiVirtualSlot outputVS = new GuiVirtualSlot(SlotType.OUTPUT, this, sx, sy);
            int slotIdx = outputSlotStart + i;
            if (slotIdx < menu.slots.size() && menu.slots.get(slotIdx) instanceof IVirtualSlot ivs) {
                outputVS.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(outputVS);
        }

        // Return slot backgrounds: 3 slots below the output grid
        int returnSlotStart = menu.getMachine().getReturnSlot();
        for (int i = 0; i < CookingFactoryBlockEntity.RETURN_SLOTS; i++) {
            int sx = RETURN_START_X + i * INPUT_SPACING - 1;
            GuiVirtualSlot returnVS = new GuiVirtualSlot(SlotType.EXTRA, this, sx, RETURN_Y - 1);
            int slotIdx = returnSlotStart + i;
            if (slotIdx < menu.slots.size() && menu.slots.get(slotIdx) instanceof IVirtualSlot ivs) {
                returnVS.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(returnVS);
        }

        // Progress bar (between input grid and output slot)
        int progressX = INPUT_START_X + INPUT_COLS * INPUT_SPACING + 10;
        int progressY = OUTPUT_START_Y + 4;
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

        // Energy bar (right side)
        int energyBarX = imageWidth - 12;
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                      menu.getEnergy(), menu.getEnergyCapacity());
            }

            @Override
            public double getLevel() {
                int capacity = menu.getEnergyCapacity();
                return capacity == 0 ? 0 : (double) menu.getEnergy() / capacity;
            }
        }, energyBarX, 22));

        // Energy info tab
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
              Component.translatable("gui.mekck.energy_stored",
                    menu.getEnergy(), menu.getEnergyCapacity()),
              Component.translatable("gui.mekck.energy_per_tick",
                    getActualEnergyPerTick()),
              Component.translatable("gui.mekck.actual_parallel",
                    getStackMultiplier())
        )));

        // Power slot (energy items: energy cube / tablet / redstone) next to the energy bar
        int powerSlotIndex = menu.getMachine().getPowerSlot();
        if (powerSlotIndex < menu.slots.size()) {
            GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
            powerVs.with(SlotOverlay.POWER);
            if (menu.slots.get(powerSlotIndex) instanceof IVirtualSlot ivs) {
                powerVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(powerVs);
        }

        // Fluid gauges (3 vertical bars, matching mekanism:chemical_dissolution_chamber)
        for (int i = 0; i < CookingFactoryBlockEntity.FLUID_TANK_COUNT; i++) {
            final int idx = i;
            addRenderableWidget(new GuiCkFluidGauge(this,
                    FLUID_GAUGE_X + i * FLUID_GAUGE_SPACING, FLUID_GAUGE_Y,
                    () -> menu.getFluidStack(idx), () -> menu.getFluidCapacity()));
        }

        // 侧栏 tab 统一走 Mek 原生 MekCkTabElement（见 addTabElements），注册在全部虚拟槽/
        // 进度条/能量条/流体计之后：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，越晚注册命中优先。tab 在 x=-26 / x=imageWidth，与槽位不重叠。
        addTabElements();

        // 侧栏 tab 必须最后注册：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，越晚注册命中优先。
        if (cn.ism.mekck.client.NetworkPullButton.isVisible()) {
            for (var tab : cn.ism.mekck.client.NetworkPullButton.register(this,
                    cn.ism.mekck.client.NetworkPullButton.getX(imageWidth),
                    cn.ism.mekck.client.NetworkPullButton.getY(AUTO_DIST_Y),
                    menu.getBlockPos())) {
                addRenderableWidget(tab);
            }
        }
    }

    /**
     * 侧栏 4 个 tab 统一走 Mek {@link MekCkTabElement}（继承 {@code GuiInsetElement}）：
     * 三层绘制与旧手绘逐参数一致（坐标/尺寸/间距常量一字未改），tooltip 改走
     * {@code GuiMekanism#renderLabels} 的元素通道 —— 那是渲染管线最后一层，结构上不会再被槽位盖住。
     * 旧实现在 {@code renderBg()} 里直绘（renderConfigTab/renderUpgradeTab/renderOrderTab/
     * renderRedstoneTab 四个方法）、在 {@code mouseClicked} 里手算命中矩形，现已一并移除。
     * <p>图标沿用旧版：侧配/升级用 Mek 官方贴图；下单用自绘的清单+箭头
     * （{@link MachineTabIcons#ORDER}，Mek 里无对应图标）。</p>
     */
    private void addTabElements() {
        // ── 左列（2 个）──
        addRenderableWidget(new MekCkTabElement(this, CONFIG_TEXTURE, TAB_X, CONFIG_TAB_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> false,
                mekanism.client.SpecialColors.TAB_CONFIGURATION,
                () -> List.of(Component.translatable("tooltip.mekck.side_config")),
                () -> {
                    openSideConfigWindow();
                    orderMode = false;
                }, null));

        addRenderableWidget(new MekCkTabElement(this, MachineTabIcons.ORDER, TAB_X, AUTO_DIST_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> orderMode,
                mekanism.client.SpecialColors.TAB_CONTAINER_EDIT_MODE,
                // 旧版这个 tab 本来就没有 tooltip（见旧 render() 的 tooltip 段），保持不弹。
                List::of,
                this::toggleOrderMode, null));

        // ── 右列（2 个）──
        addRenderableWidget(new MekCkTabElement(this, UPGRADE_TEXTURE, imageWidth, UPGRADE_TAB_Y, false,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> false,
                mekanism.client.SpecialColors.TAB_UPGRADE,
                () -> List.of(Component.translatable("tooltip.mekck.upgrade")),
                () -> {
                    openUpgradeWindow();
                    orderMode = false;
                }, null));

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
            if (networkOrderMode) {
                networkDataRequested = false; // 重新拉取 ME 网络数据
            }
        }
        menu.setUpgradePageActive(false);
    }

    @Override
    public void init() {
        super.init();
        // GUI 高度超出屏幕时贴顶显示，避免整体跑出屏幕外
        if (this.topPos < 0) {
            this.topPos = 0;
        }
        // 搜索框：先给占位位置，实际每帧按面板位置 move()
        orderSearch.init(this.font, leftPos + 4, topPos + 4, 60);
    }

    private static String formatItemCount(int count) {
        // 统一走 CountFormat（含十亿档；21 亿不再显示成 2147.5M）
        return cn.ism.mekck.client.CountFormat.compact(count);
    }

    /**
     * Calculates the actual energy consumption per tick based on upgrade counts.
     * Matches the calculation in CookingFactoryBlockEntity.serverTick.
     */
    private int getActualEnergyPerTick() {
        double speedMult = Math.pow(10, menu.getSpeedUpgradeCount() / 8.0);
        double energyConsumptionMult = Math.pow(0.1, menu.getEnergyUpgradeCount() / 8.0);
        int baseEnergyPerTick = (int) Math.ceil(CookingFactoryBlockEntity.BASE_ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);
        int stackMult = getStackMultiplier();
        return cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, baseEnergyPerTick, stackMult);
    }

    private int getStackMultiplier() {
        int stackCount = menu.getStackUpgradeCount();
        if (tier == CuttingMachineFactoryTier.SINGULARITY) {
            return Math.min(1 << (stackCount / 2), 6);
        }
        return 1 << Math.min(stackCount, 6);
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 20, inventoryLabelY, titleTextColor());
        // 温度系统：显示机身温度（摄氏度）
        guiGraphics.drawString(font, String.format("温度: %.1f℃", menu.getTemperature() / 100.0), 20, 78, 0xFFFF5555);
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        // 侧栏 4 个 tab（侧配 / 升级 / 下单 / 红石）已全部迁到 addTabElements()（MekCkTabElement），
        // renderBg 不再手绘；AE2 网络拉料两枚按钮也已在 addGuiElements() 末尾由
        // NetworkPullButton.register(...) 注册为 Mek 原生元素。
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 终端式搜索框：定位在下单面板头部左侧（本机/ME 模式按钮在右侧）
        orderSearch.move(leftPos + ORDER_PANEL_LEFT + 3, topPos + ORDER_PANEL_TOP + 2,
                Math.max(40, (imageWidth - ORDER_PANEL_LEFT * 2) / 2 - 8));
        orderSearch.render(guiGraphics, mouseX, mouseY, partialTick);
        // Temporarily set item counts to 1 for stacks with large counts
        bigStackHud.shrink(menu.slots);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Restore original counts
        bigStackHud.restore();

        // Draw formatted counts for large item stacks
        for (Slot slot : menu.slots) {
            if (slot.isActive() && slot.hasItem()) {
                int count = slot.getItem().getCount();
                String formatted = formatItemCount(count);
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

        // 4 个侧栏 tab 的 tooltip 已迁到 MekCkTabElement#renderToolTip，
        // 由 GuiMekanism#renderLabels 在渲染管线最后一层统一派发（文案逐字沿用旧版）。
    }

    /** 本屏的 ME 面板是自绘的（见 renderNetworkOrderMode），不走共用面板。 */
    @Override
    public NetworkOrderPanel networkOrderPanel() {
        return null;
    }

    @Override
    public void setNetworkMissing(net.minecraft.core.BlockPos pos, String recipeId, int quantity, String text) {
        if (pos == null || !pos.equals(menu.getBlockPos())) return;
        if (selectedRecipe == null || recipeId == null) return;
        if (!recipeId.equals(selectedRecipe.getId().toString()) || quantity != orderQuantity) return;
        this.networkMissingText = text == null || text.isEmpty() ? null : text;
    }

    /** 面板 ME：选中配方 / 改数量后问一次服务端「缺什么、缺多少」。 */
    private void requestNetworkMissing() {
        networkMissingText = null;
        if (selectedRecipe == null) return;
        ModMessages.sendToServer(new NetworkMissingRequestPacket(menu.getBlockPos(),
                selectedRecipe.getId().toString(), orderQuantity));
    }

    /** 缺料清单 / 最大可做提示都可能超宽：按可用宽度截断。 */
    private String fitWidth(String text, int maxWidth) {
        if (text == null) return "";
        return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, Math.max(8, maxWidth - 6)) + "\u2026";
    }

    private void renderOrderMode(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY) {
        // ME 网络下单模式使用 AE 风格界面
        if (networkOrderMode) {
            renderNetworkOrderMode(guiGraphics, x, y, mouseX, mouseY);
            return;
        }
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        // Mekanism 风格背景（base.png 九宫格 + 蓝色描边）
        GuiUtils.renderBackgroundTexture(guiGraphics, MEK_BASE, 4, 4, panelX, panelY, panelW, panelH, 256, 256);
        GuiUtils.drawOutline(guiGraphics, panelX, panelY, panelW, panelH, 0xFF3E6E91);

        // Title
        guiGraphics.drawString(font, "\u4E0B\u5355", panelX + 4, panelY + 4, 0xFFFFFFFF);

        // 下单来源切换：本机库存 / ME 网络（AE2 已装才显示 ME）
        boolean ae2Loaded = AE2Compat.isLoaded();
        int modeBtnW = 40;
        int modeBtnH = 13;
        int modeBtnY = panelY + 3;
        if (ae2Loaded) {
            int localBtnX = panelX + panelW - modeBtnW * 2 - 4;
            int netBtnX = panelX + panelW - modeBtnW;
            boolean localHover = mouseX >= localBtnX && mouseX < localBtnX + modeBtnW
                    && mouseY >= modeBtnY && mouseY < modeBtnY + modeBtnH;
            boolean netHover = mouseX >= netBtnX && mouseX < netBtnX + modeBtnW
                    && mouseY >= modeBtnY && mouseY < modeBtnY + modeBtnH;
            drawMekButton(guiGraphics, localBtnX, modeBtnY, modeBtnW, modeBtnH, "\u672C\u673A", localHover || !networkOrderMode, true);
            drawMekButton(guiGraphics, netBtnX, modeBtnY, modeBtnW, modeBtnH, "ME", netHover || networkOrderMode, true);
        }

        // Refresh available recipes list
        if (orderListDirty) {
            if (networkOrderMode) {
                if (!networkDataRequested) {
                    networkDataRequested = true;
                    ModMessages.sendToServer(new NetworkRecipeRequestPacket(menu.getBlockPos()));
                }
                availableRecipes = orderSearch.filter(resolveNetworkRecipes());
            } else {
                availableRecipes = orderSearch.filter(menu.getMachine().getAvailableRecipes());
            }
            orderListDirty = false;
        }

        // Recipe list (scrollable)
        int listTop = panelY + 16;
        int listBottom = listTop + ORDER_LIST_HEIGHT;
        int listWidth = panelW - 8;

        // Draw recipe entries
        int visibleCount = Math.min(ORDER_LIST_ROWS, availableRecipes.size() - orderScrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int recipeIdx = orderScrollOffset + i;
            if (recipeIdx >= availableRecipes.size()) break;

            Recipe<?> recipe = availableRecipes.get(recipeIdx);
            int entryY = listTop + i * ORDER_ENTRY_HEIGHT;
            boolean isSelected = recipe == selectedRecipe;

            // Entry background（Mekanism 风格深色行）
            int entryColor = isSelected ? 0xE63E6E91 : 0xE61C1C1C;
            guiGraphics.fill(panelX + 2, entryY, panelX + listWidth, entryY + ORDER_ENTRY_HEIGHT - 1, entryColor);

            // Recipe icon
            ItemStack result = recipe.getResultItem(minecraft.level.registryAccess());
            if (!result.isEmpty()) {
                guiGraphics.renderItem(result, panelX + 4, entryY + 2);
            }

            // Recipe name
            String name = result.isEmpty() ? "Unknown" : result.getHoverName().getString();
            if (font.width(name) > listWidth - 50) {
                name = font.plainSubstrByWidth(name, listWidth - 50) + "...";
            }
            guiGraphics.drawString(font, name, panelX + 24, entryY + 4, 0xFFFFFFFF);
        }

        // 空列表提示（本机模式）
        if (availableRecipes.isEmpty()) {
            guiGraphics.drawString(font, "\u65E0\u53EF\u7528\u98DF\u6750", panelX + 4, listTop + 4, 0xFFAAAAAA);
        }

        // Scroll indicators
        if (orderScrollOffset > 0) {
            guiGraphics.drawString(font, "\u2191", panelX + listWidth - 10, listTop, 0xFFFFFFFF);
        }
        if (orderScrollOffset + ORDER_LIST_ROWS < availableRecipes.size()) {
            guiGraphics.drawString(font, "\u2193", panelX + listWidth - 10, listBottom - 10, 0xFFFFFFFF);
        }

        // ME 网络模式空列表提示
        if (networkOrderMode && availableRecipes.isEmpty()) {
            String msg = networkDataRequested ? "ME\u7F51\u7EDC\u4E2D\u65E0\u53EF\u7528\u98DF\u6750" : "\u6B63\u5728\u83B7\u53D6ME\u7F51\u7EDC\u6570\u636E...";
            guiGraphics.drawString(font, msg, panelX + 4, listTop, 0xFFAAAAAA);
        }

        // Quantity controls (below recipe list)
        int qtyY = listBottom + 4;
        guiGraphics.drawString(font, "\u6570\u91CF: " + orderQuantity, panelX + 4, qtyY, 0xFFFFFFFF);

        // Quantity buttons: 1, 16, 32, 64, Custom, Max
        int qtyBtnX = panelX + 4;
        int qtyBtnY = qtyY + 10;
        int qtyBtnW = 24;
        int qtyBtnH = 14;
        String[] qtyLabels = {"1", "16", "32", "64", "自", "Max"};
        int[] qtyValues = {1, 16, 32, 64, -1, -2};
        for (int i = 0; i < qtyLabels.length; i++) {
            int bx = qtyBtnX + i * (qtyBtnW + 2);
            boolean hovered = mouseX >= bx && mouseX < bx + qtyBtnW && mouseY >= qtyBtnY && mouseY < qtyBtnY + qtyBtnH;
            boolean active = qtyValues[i] == -1 && customInputMode;
            drawMekButton(guiGraphics, bx, qtyBtnY, qtyBtnW, qtyBtnH, qtyLabels[i], hovered || active, true);
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
            int maxQty = networkOrderMode
                    ? networkMaxCraftable.getOrDefault(selectedRecipe.getId(), 0)
                    : menu.getMachine().getMaxConsumableCountForOrder(selectedRecipe);
            if (maxQty > 0) {
                String maxText = "最大: " + maxQty;
                guiGraphics.drawString(font, maxText, qtyBtnX, qtyBtnY + qtyBtnH + 2, 0xFFAAAAAA);
            }
        }

        // Confirm button
        int confirmBtnY = qtyBtnY + qtyBtnH + 6;
        int confirmBtnW = 60;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW) / 2;
        boolean confirmHovered = mouseX >= confirmBtnX && mouseX < confirmBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH;
        drawMekButton(guiGraphics, confirmBtnX, confirmBtnY, confirmBtnW, confirmBtnH,
                "\u786E\u8BA4\u4E0B\u5355", confirmHovered, true);

        // Cancel button
        int cancelBtnX = confirmBtnX + confirmBtnW + 4;
        boolean cancelHovered = mouseX >= cancelBtnX && mouseX < cancelBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH;
        drawMekButton(guiGraphics, cancelBtnX, confirmBtnY, confirmBtnW, confirmBtnH,
                "\u53D6\u6D88", cancelHovered, true);
    }

    /** ME 网络下单面板：AE 终端风格。 */
    private void renderNetworkOrderMode(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY) {
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        // 背景：AE 面板纹理（无格子）+ 淡蓝染色
        guiGraphics.setColor(0.55f, 0.72f, 0.90f, 1.0f);
        GuiUtils.blitNineSlicedSized(guiGraphics, AE_PANEL, panelX, panelY, panelW, panelH,
                4, 256, 256, 0, 0, 256, 256);
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);

        // 表头：标题 + 来源切换（AE 文本按钮），下方一条分隔线
        guiGraphics.drawString(font, "ME \u7F51\u7EDC\u4E0B\u5355", panelX + 8, panelY + 6, AE_TEXT_ACCENT);
        int modeBtnW = 40, modeBtnH = 14;
        int modeBtnY = panelY + 4;
        int localBtnX = panelX + panelW - modeBtnW * 2 - 8;
        int netBtnX = panelX + panelW - modeBtnW - 4;
        drawAeButton(guiGraphics, localBtnX, modeBtnY, modeBtnW, modeBtnH,
                "\u672C\u673A", !networkOrderMode, isHovered(mouseX, mouseY, localBtnX, modeBtnY, modeBtnW, modeBtnH));
        drawAeButton(guiGraphics, netBtnX, modeBtnY, modeBtnW, modeBtnH,
                "ME", networkOrderMode, isHovered(mouseX, mouseY, netBtnX, modeBtnY, modeBtnW, modeBtnH));
        guiGraphics.fill(panelX + 4, panelY + 21, panelX + panelW - 4, panelY + 22, AE_BORDER);

        // 刷新配方列表
        if (orderListDirty) {
            if (!networkDataRequested) {
                networkDataRequested = true;
                ModMessages.sendToServer(new NetworkRecipeRequestPacket(menu.getBlockPos()));
            }
            availableRecipes = orderSearch.filter(resolveNetworkRecipes());
            orderListDirty = false;
        }

        // 列表区（AE 单元格列表，右侧滚动条）
        int listTop = panelY + 27;
        int rowX = panelX + 6;
        int rowW = panelW - 24; // 给右侧滚动条留空间
        int listWidth = rowW;
        // ME 模式改为**格子网格**（AE 终端样式）：列数按面板宽度运行时算，行数沿用 ORDER_LIST_ROWS。
        int cols = meGridCols();
        int cellsPerPage = cols * ORDER_LIST_ROWS;
        int listBottom = listTop + ORDER_LIST_ROWS * ME_CELL;
        int scrollbarX = panelX + panelW - 7;

        // 空列表提示
        if (availableRecipes.isEmpty()) {
            String msg = networkDataRequested ? "ME\u7F51\u7EDC\u4E2D\u65E0\u53EF\u7528\u98DF\u6750" : "\u6B63\u5728\u83B7\u53D6ME\u7F51\u7EDC\u6570\u636E...";
            guiGraphics.drawString(font, msg, rowX + 2, listTop + 8, AE_TEXT_DIM);
        }

        // 表格行：图标 + 名称 + 可做数量（AE 单元格：1px 描边，悬停/选中高亮）
        int visibleCount = Math.min(cellsPerPage, availableRecipes.size() - orderScrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int recipeIdx = orderScrollOffset + i;
            if (recipeIdx >= availableRecipes.size()) break;
            Recipe<?> recipe = availableRecipes.get(recipeIdx);
            // 网格定位：第 i 格 -> 第 (i / cols) 行、第 (i % cols) 列
            int cellX = rowX + (i % cols) * ME_CELL;
            int cellY = listTop + (i / cols) * ME_CELL;
            boolean isSelected = recipe == selectedRecipe;
            boolean cellHover = isHovered(mouseX, mouseY, cellX, cellY, ME_CELL, ME_CELL);
            int bg = isSelected ? AE_SLOT_HOVER : (cellHover ? 0xFF4A7BA6 : AE_SLOT);
            guiGraphics.fill(cellX, cellY, cellX + ME_CELL, cellY + ME_CELL, AE_BORDER);
            guiGraphics.fill(cellX + 1, cellY + 1, cellX + ME_CELL - 1, cellY + ME_CELL - 1, bg);

            ItemStack result = recipe.getResultItem(minecraft.level.registryAccess());
            int maxQty = networkMaxCraftable.getOrDefault(recipe.getId(), 0);
            boolean craftable = maxQty > 0;
            if (!result.isEmpty()) {
                guiGraphics.renderItem(result, cellX + 1, cellY + 1);
                guiGraphics.renderItemDecorations(font, result, cellX + 1, cellY + 1);
            }
            if (!craftable) {
                guiGraphics.fill(cellX + 1, cellY + 1, cellX + ME_CELL - 1, cellY + ME_CELL - 1, 0x99000000);
            }

            if (cellHover) {
                guiGraphics.renderTooltip(font,
                        java.util.List.of(
                                result.isEmpty() ? net.minecraft.network.chat.Component.literal("Unknown") : result.getHoverName(),
                                net.minecraft.network.chat.Component.literal(craftable
                                        ? ("\u53ef\u505a " + maxQty + " \u6b21") : "\u6750\u6599\u4e0d\u8db3")),
                        java.util.Optional.empty(), mouseX, mouseY);
            }
        }

        // AE 风格滚动条（细轨道 + 蓝色滑块；可滚轮滚动）
        if (availableRecipes.size() > cellsPerPage) {
            guiGraphics.fill(scrollbarX, listTop, scrollbarX + 2, listBottom, AE_BORDER);
            int totalCells = Math.max(1, availableRecipes.size());
            int trackH = listBottom - listTop;
            int thumbH = Math.max(14, trackH * cellsPerPage / totalCells);
            int scrollRange = totalCells - cellsPerPage;
            int thumbY = listTop + (scrollRange == 0 ? 0 : orderScrollOffset * (trackH - thumbH) / scrollRange);
            guiGraphics.fill(scrollbarX - 1, thumbY, scrollbarX + 3, thumbY + thumbH, AE_BTN_ACTIVE);
        }

        // 数量区
        int qtyY = listBottom + 6;
        guiGraphics.drawString(font, "\u6570\u91CF: " + orderQuantity, rowX, qtyY, AE_TEXT_YELLOW);

        int qtyBtnX = rowX;
        int qtyBtnY = qtyY + 12;
        int qtyBtnW = 24;
        int qtyBtnH = 14;
        String[] qtyLabels = {"1", "16", "32", "64", "\u81EA", "Max"};
        int[] qtyValues = {1, 16, 32, 64, -1, -2};
        for (int i = 0; i < qtyLabels.length; i++) {
            int bx = qtyBtnX + i * (qtyBtnW + 2);
            boolean active = qtyValues[i] == -1 && customInputMode;
            drawAeButton(guiGraphics, bx, qtyBtnY, qtyBtnW, qtyBtnH, qtyLabels[i], active,
                    isHovered(mouseX, mouseY, bx, qtyBtnY, qtyBtnW, qtyBtnH));
        }

        // 自定义数量输入框（§F23：AE 纹理 DIY 框改 GuiTextField；blit 原在 (inputX-1,inputY-1) 82x16，同位放置）
        if (qtyField != null) {
            qtyField.setVisible(customInputMode);
            if (customInputMode) {
                qtyField.resize(qtyBtnX - 1 - leftPos, qtyBtnY + qtyBtnH + 2 - 1 - topPos, 82, 16);
            }
        }

        // 最大可做提示 / AE 终端式缺料清单（红字，终端同款位置 = 数量区下方）
        if (!customInputMode) {
            if (networkMissingText != null) {
                guiGraphics.drawString(font, fitWidth(networkMissingText, panelW - 16),
                        qtyBtnX, qtyBtnY + qtyBtnH + 2, 0xFFFF7070);
            } else if (selectedRecipe != null) {
                int maxQty = networkMaxCraftable.getOrDefault(selectedRecipe.getId(), 0);
                if (maxQty > 0) {
                    guiGraphics.drawString(font, "\u6700\u5927: " + maxQty, qtyBtnX, qtyBtnY + qtyBtnH + 2, AE_TEXT_DIM);
                }
            }
        }

        // 底部：确认下单 / 取消（AE 文本按钮）
        int confirmBtnW = 74;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW * 2 - 8) / 2;
        int confirmBtnY = qtyBtnY + 30;
        drawAeButton(guiGraphics, confirmBtnX, confirmBtnY, confirmBtnW, confirmBtnH,
                "\u786E\u8BA4\u4E0B\u5355", false, isHovered(mouseX, mouseY, confirmBtnX, confirmBtnY, confirmBtnW, confirmBtnH));
        drawAeButton(guiGraphics, confirmBtnX + confirmBtnW + 8, confirmBtnY, confirmBtnW, confirmBtnH,
                "\u53D6\u6D88", false, isHovered(mouseX, mouseY, confirmBtnX + confirmBtnW + 8, confirmBtnY, confirmBtnW, confirmBtnH));
    }

    /** AE 风格小按钮：蓝色描边 + 深蓝底 + 白色文字。 */
    private void drawAeButton(GuiGraphics guiGraphics, int x, int y, int w, int h, String label, boolean active, boolean hovered) {
        int fill = active ? AE_BTN_ACTIVE : (hovered ? AE_BTN_HOVER : AE_BTN);
        guiGraphics.fill(x, y, x + w, y + h, AE_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        int labelW = font.width(label);
        guiGraphics.drawString(font, label, x + (w - labelW) / 2, y + (h - 9) / 2 + 1, AE_TEXT);
    }

    private static boolean isHovered(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Mekanism 风格按钮：button.png 三态（0禁用/1常态/2悬停）+ 白色文字。 */
    private void drawMekButton(GuiGraphics guiGraphics, int x, int y, int w, int h, String label, boolean hovered, boolean enabled) {
        int state = enabled ? (hovered ? 2 : 1) : 0;
        GuiUtils.blitNineSlicedSized(guiGraphics, BUTTON_TEXTURE, x, y, w, h, 20, 4, 200, 20, 0, state * 20, 200, 60);
        int labelW = font.width(label);
        guiGraphics.drawString(font, label, x + (w - labelW) / 2, y + (h - 9) / 2 + 1, 0xFFFFFFFF);
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
        if (orderSearch.mouseClicked(mouseX, mouseY, button)) return true;
        // 4 个侧栏 tab 的点击已交给 MekCkTabElement#onClick —— 它们是 renderable widget，
        // 由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        if (button == 0) {
            int x = leftPos;
            int y = topPos;

            if (orderMode) {
                return handleOrderClick(mouseX, mouseY, x, y);
            }

            // Cancel order button
            if (orderCancelBtnX >= 0 && mouseX >= orderCancelBtnX && mouseX < orderCancelBtnX + orderCancelBtnW
                    && mouseY >= orderCancelBtnY && mouseY < orderCancelBtnY + orderCancelBtnH) {
                ModMessages.sendToServer(new OrderRecipePacket(menu.getBlockPos(), null, 0));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // ME 网络下单模式：滚轮滚动配方列表
        if (orderMode && networkOrderMode) {
            int maxScroll = Math.max(0, availableRecipes.size() - meGridCols() * ORDER_LIST_ROWS);
            int next = orderScrollOffset + (delta > 0 ? -1 : 1);
            orderScrollOffset = Math.max(0, Math.min(maxScroll, next));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (orderSearch.keyPressed(keyCode, scanCode, modifiers)) return true;
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
        if (orderSearch.charTyped(codePoint, modifiers)) return true;
        // §F23：数字接收改由 GuiTextField（DIGIT 校验）处理。
        return super.charTyped(codePoint, modifiers);
    }

    /** §F23：敲数字回车提交：正整数且 >0 生效；空/非法/0 保持原值并退出编辑（等价旧 DIY Enter 行为，
     *  含旧 Enter 分支尾的 ME 模式缺料刷新）。 */
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
        if (networkOrderMode) {
            requestNetworkMissing();
        }
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

    private boolean handleOrderClick(double mouseX, double mouseY, int x, int y) {
        // ME 网络下单模式使用 AE 风格界面
        if (networkOrderMode) {
            return handleNetworkOrderClick(mouseX, mouseY, x, y);
        }
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        // Check if click is outside the panel (close order mode)
        if (mouseX < panelX || mouseX > panelX + panelW || mouseY < panelY || mouseY > panelY + panelH) {
            orderMode = false;
            return true;
        }

        // 下单来源切换：本机库存 / ME 网络
        if (AE2Compat.isLoaded()) {
            int modeBtnW = 40;
            int modeBtnH = 13;
            int modeBtnY = panelY + 3;
            int localBtnX = panelX + panelW - modeBtnW * 2 - 4;
            int netBtnX = panelX + panelW - modeBtnW;
            if (mouseX >= localBtnX && mouseX < localBtnX + modeBtnW && mouseY >= modeBtnY && mouseY < modeBtnY + modeBtnH) {
                if (networkOrderMode) {
                    networkOrderMode = false;
                    selectedRecipe = null;
                    orderListDirty = true;
                }
                return true;
            }
            if (mouseX >= netBtnX && mouseX < netBtnX + modeBtnW && mouseY >= modeBtnY && mouseY < modeBtnY + modeBtnH) {
                if (!networkOrderMode) {
                    networkOrderMode = true;
                    networkDataRequested = false;
                    selectedRecipe = null;
                    orderListDirty = true;
                    orderScrollOffset = 0;
                    exitCustomInput();
                }
                return true;
            }
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
                    // Max - calculate from available materials (ME 模式取网络库存上限)
                    if (selectedRecipe != null) {
                        int maxQty = networkOrderMode
                                ? networkMaxCraftable.getOrDefault(selectedRecipe.getId(), 0)
                                : menu.getMachine().getMaxConsumableCountForOrder(selectedRecipe);
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

        // Confirm button
        int confirmBtnY = qtyBtnY + qtyBtnH + 6;
        int confirmBtnW = 60;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW) / 2;

        // Confirm button
        if (mouseX >= confirmBtnX && mouseX < confirmBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH) {
            if (selectedRecipe != null && orderQuantity > 0) {
                ResourceLocation recipeId = selectedRecipe.getId();
                if (networkOrderMode) {
                    // ME 网络下单：服务端先从网络抽料再下单（烹饪工厂即“无限容量合成CPU”）
                    ModMessages.sendToServer(new NetworkOrderPacket(menu.getBlockPos(), recipeId.toString(), orderQuantity));
                } else {
                    ModMessages.sendToServer(new OrderRecipePacket(menu.getBlockPos(), recipeId, orderQuantity));
                }
                orderMode = false;
                selectedRecipe = null;
                orderQuantity = 1;
            }
            return true;
        }

        // Cancel button
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

    /** ME 网络下单面板点击处理（AE 风格坐标，与 renderNetworkOrderMode 对应）。 */
    /**
     * ME 网格每行能放几个格子 —— 按面板可用宽度**运行时算**，窗口尺寸 / GUI 缩放变化都自适应。
     * <p><b>渲染、滚轮、点击三处必须共用这一个方法</b>，否则会出现「按 N 列画、按 M 列点」的错位。</p>
     */
    private int meGridCols() {
        int usable = imageWidth - ORDER_PANEL_LEFT * 2 - 24 - 4;
        return Math.max(1, usable / ME_CELL);
    }

    private boolean handleNetworkOrderClick(double mouseX, double mouseY, int x, int y) {
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        // 点击面板外 → 关闭
        if (mouseX < panelX || mouseX > panelX + panelW || mouseY < panelY || mouseY > panelY + panelH) {
            orderMode = false;
            return true;
        }

        // 来源切换
        int modeBtnW = 40, modeBtnH = 14;
        int modeBtnY = panelY + 4;
        int localBtnX = panelX + panelW - modeBtnW * 2 - 8;
        int netBtnX = panelX + panelW - modeBtnW - 4;
        if (isHovered(mouseX, mouseY, localBtnX, modeBtnY, modeBtnW, modeBtnH)) {
            networkOrderMode = false;
            selectedRecipe = null;
            orderListDirty = true;
            orderScrollOffset = 0;
            exitCustomInput();
            return true;
        }
        if (isHovered(mouseX, mouseY, netBtnX, modeBtnY, modeBtnW, modeBtnH)) {
            return true; // 已在 ME 模式
        }

        // 配方行点击
        // 配方格子点击（与渲染共用同一套网格几何 —— 列数出自同一个 meGridCols()）
        int listTop = panelY + 27;
        int rowX = panelX + 6;
        int rowW = panelW - 24;
        int cols = meGridCols();
        int cellsPerPage = cols * ORDER_LIST_ROWS;
        for (int i = 0; i < cellsPerPage; i++) {
            int recipeIdx = orderScrollOffset + i;
            if (recipeIdx >= availableRecipes.size()) break;
            int cellX = rowX + (i % cols) * ME_CELL;
            int cellY = listTop + (i / cols) * ME_CELL;
            if (isHovered(mouseX, mouseY, cellX, cellY, ME_CELL, ME_CELL)) {
                selectedRecipe = availableRecipes.get(recipeIdx);
                orderQuantity = 1;
                requestNetworkMissing();
                return true;
            }
        }

        // 滚动条点击（上/下半页翻页）
        int listBottom = listTop + ORDER_LIST_ROWS * ME_CELL;
        int scrollbarX = panelX + panelW - 7;
        if (mouseX >= scrollbarX - 2 && mouseX < scrollbarX + 4
                && mouseY >= listTop && mouseY < listBottom) {
            if (mouseY < listTop + (listBottom - listTop) / 2) {
                orderScrollOffset = Math.max(0, orderScrollOffset - cols);
            } else {
                orderScrollOffset = Math.min(availableRecipes.size() - cellsPerPage, orderScrollOffset + cols);
            }
            return true;
        }

        // 数量按钮
        int qtyY = listBottom + 6;
        int qtyBtnX = rowX;
        int qtyBtnY = qtyY + 12;
        int qtyBtnW = 24;
        int qtyBtnH = 14;
        int[] qtyValues = {1, 16, 32, 64, -1, -2};
        for (int i = 0; i < qtyValues.length; i++) {
            int bx = qtyBtnX + i * (qtyBtnW + 2);
            if (isHovered(mouseX, mouseY, bx, qtyBtnY, qtyBtnW, qtyBtnH)) {
                int val = qtyValues[i];
                if (val == -1) {
                    // §F23：AE 面板同款切换（控件位置由 renderNetworkOrderMode 里 resize）
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
                        int maxQty = networkMaxCraftable.getOrDefault(selectedRecipe.getId(), 0);
                        orderQuantity = Math.max(1, maxQty);
                    }
                    requestNetworkMissing();
                } else {
                    exitCustomInput();
                    orderQuantity = val;
                    requestNetworkMissing();
                }
                return true;
            }
        }

        // 确认 / 取消
        int confirmBtnW = 74;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW * 2 - 8) / 2;
        int confirmBtnY = qtyBtnY + 30;
        if (isHovered(mouseX, mouseY, confirmBtnX, confirmBtnY, confirmBtnW, confirmBtnH)) {
            if (selectedRecipe != null && orderQuantity > 0) {
                ResourceLocation recipeId = selectedRecipe.getId();
                ModMessages.sendToServer(new NetworkOrderPacket(menu.getBlockPos(), recipeId.toString(), orderQuantity));
                orderMode = false;
                selectedRecipe = null;
                orderQuantity = 1;
            }
            return true;
        }
        if (isHovered(mouseX, mouseY, confirmBtnX + confirmBtnW + 8, confirmBtnY, confirmBtnW, confirmBtnH)) {
            orderMode = false;
            selectedRecipe = null;
            orderQuantity = 1;
            return true;
        }

        return true;
    }
}