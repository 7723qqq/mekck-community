package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.blockentity.SkeweringFactoryBlockEntity;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.SkeweringFactoryMenu;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkMissingRequestPacket;
import cn.ism.mekck.network.NetworkOrderPacket;
import cn.ism.mekck.network.NetworkRecipeRequestPacket;
import cn.ism.mekck.network.OrderRecipePacket;
import cn.ism.mekck.network.RedstoneControlPacket;
import cn.ism.mekck.network.SideConfigPacket;
import cn.ism.mekck.util.AE2Compat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mekanism.api.text.EnumColor;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.GuiUtils;
import mekanism.client.gui.element.GuiElement;
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

public final class SkeweringFactoryScreen extends GuiMekanism<SkeweringFactoryMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    private final CuttingMachineFactoryTier tier;
    private final int storageSlots;
    private boolean configMode = false;
    private boolean orderMode = false;
    // ME 网络下单模式（穿串工厂与烹饪工厂同款）
    private boolean networkOrderMode = false;
    private boolean networkDataRequested = false;
    private List<ResourceLocation> networkRecipeIds = List.of();
    private Map<ResourceLocation, Integer> networkMaxCraftable = Map.of();
    /** AE 终端式缺料清单（服务端回包）：选中配方 / 改数量后问一次，材料够则为 null。 */
    private String networkMissingText;
    /** 终端式搜索框（按产物名称 / 注册名过滤配方列表）。 */
    private final cn.ism.mekck.client.OrderSearchBox orderSearch = new cn.ism.mekck.client.OrderSearchBox();
    private boolean orderSearchReady;

    // Mekanism-style tab positions
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;
    private static final int AUTO_DIST_Y = 34;
    private static final int TAB_OUTER_W = 24;
    private static final int TAB_OUTER_H = 24;

    private static final int TAB_ICON_SIZE = 16;
    private static final int TAB_ICON_OFFSET = 4;

    private static final int UPGRADE_TAB_Y = 6;

    // Redstone control tab (right side, identical position to Mekanism's factory: x = imageWidth, y = 137)
    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;
    private static final int REDSTONE_TINT = 0xFFC9071F;

    // Redstone control icon textures (Mekanism)
    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    // Mekanism textures
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");
    private static final ResourceLocation SORTING_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "sorting.png");

    // Config mode layout
    private static final int CONFIG_BUTTON_W = 60;
    private static final int CONFIG_BUTTON_H = 21;
    private static final int CONFIG_START_X = 10;
    private static final int CONFIG_START_Y = 10;
    private static final int CONFIG_COL_GAP = 62;
    private static final int CONFIG_ROW_GAP = 23;
    private static final int DONE_BUTTON_W = 126;
    private static final int DONE_BUTTON_H = 21;

    /** 配置覆盖层 6 个方向格的排布顺序（行优先：DOWN/UP → NORTH/SOUTH → EAST/WEST）。 */
    private static final Direction[] CONFIG_SIDE_DIRS = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    /** SideMode.ordinal() → 中文名，与 GuiMekCkSideConfiguration.componentForMode() 同序。 */
    private static final String[] CONFIG_MODE_NAMES = {"无", "抽取(输入格)", "输出", "抽取(存储)"};

    /** 配置覆盖层的按钮组（真 widget，默认隐藏，由 render() 随 configMode 同步）。 */
    private final List<GuiElement> configButtons = new ArrayList<>();

    // Slot layout constants (matching the menu)
    private static final int INPUT_START_X = 38;
    private static final int INPUT_START_Y = 41;
    private static final int INPUT_COLS = 3;
    private static final int INPUT_SPACING = 18;
    private static final int OUTPUT_X = 130;
    private static final int OUTPUT_Y = 41;
    private static final int RETURN_Y = 59;
    private static final int STORAGE_ROWS = 9;
    private static final int STORAGE_START_Y = 81;

    // Order panel layout
    private static final int ORDER_PANEL_LEFT = 10;
    private static final int ORDER_PANEL_TOP = 10;
    private static final int ORDER_PANEL_WIDTH = 150;
    private static final int ORDER_ENTRY_HEIGHT = 20;
    private static final int ORDER_LIST_ROWS = 6;
    /** ME 网格的格子边长（像素）。本机模式仍是行式列表，两者互不影响。 */
    private static final int ME_CELL = 18;
    private static final int ORDER_LIST_HEIGHT = ORDER_ENTRY_HEIGHT * ORDER_LIST_ROWS;

    private final boolean hasStackUpgrade;
    private List<Recipe<?>> availableRecipes;
    private Recipe<?> selectedRecipe;
    private int orderQuantity = 1;
    private int orderScrollOffset = 0;
    private boolean orderListDirty = true;
    private boolean customInputMode = false;
    /** §F23：自定义数量输入框（Mekanism GuiTextField，本机/AE 两面板共用一个，渲染时按当帧面板 resize）。 */
    private GuiTextField qtyField;
    private boolean customMixMode = false;
    private final java.util.List<ItemStack> customSelected = new java.util.ArrayList<>();

    // Cancel button bounds for order progress display
    private int orderCancelBtnX = -1;
    private int orderCancelBtnY = -1;
    private int orderCancelBtnW = 30;
    private int orderCancelBtnH = 14;

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

    /** Mekanism 风格按钮。 */
    private void drawMekButton(GuiGraphics guiGraphics, int x, int y, int w, int h, String label, boolean hovered, boolean enabled) {
        int state = enabled ? (hovered ? 2 : 1) : 0;
        GuiUtils.blitNineSlicedSized(guiGraphics, MekanismUtils.getResource(ResourceType.GUI, "button.png"),
                x, y, w, h, 20, 4, 200, 20, 0, state * 20, 200, 60);
        int labelW = font.width(label);
        guiGraphics.drawString(font, label, x + (w - labelW) / 2, y + (h - 9) / 2 + 1, 0xFFFFFFFF);
    }

    private static boolean isHovered(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    public SkeweringFactoryScreen(SkeweringFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.tier = menu.getTier();
        this.storageSlots = menu.getStorageSlots();
        this.hasStackUpgrade = menu.hasStackUpgrade();

        // GUI 尺寸固定（物品栏不再挂在存储区下面 —— 那是缩放 4 下整个界面 333 高、放不下的根因）
        imageWidth = Math.max(176, OUTPUT_X + 18 + 20);
        imageHeight = SkeweringFactoryMenu.INV_TOP + 58 + 26;
        inventoryLabelY = SkeweringFactoryMenu.INV_TOP - 10;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // Render slot backgrounds for input slots (3 slots: 1 row x 3 cols)
        for (int col = 0; col < INPUT_COLS; col++) {
            int x = INPUT_START_X + col * INPUT_SPACING - 1;
            int y = INPUT_START_Y - 1;
            int slotIdx = col;
            GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.INPUT, this, x, y);
            if (menu.slots.get(slotIdx) instanceof IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
        }

        // 存储槽背景：与菜单同一套**双侧紧凑布局**（见 SkeweringFactoryMenu#storageSlotX/Y）
        for (int i = 0; i < storageSlots; i++) {
            int x = SkeweringFactoryMenu.storageSlotX(i, imageWidth) - 1;
            int y = SkeweringFactoryMenu.storageSlotY(i) - 1;
            addRenderableWidget(new GuiSlot(SlotType.EXTRA, this, x, y));
        }

        // Output slot background
        // 注意：菜单中储物槽排在本槽之前(addSlot 顺序 = 输入、储物、输出、返回)，
        // 而 getOutputSlot() 返回的是物品处理器中的槽位下标，二者并不相等，因此必须用菜单槽位下标查找。
        int outputMenuIdx = SkeweringFactoryBlockEntity.INPUT_SLOTS + storageSlots;
        GuiVirtualSlot outputVS = new GuiVirtualSlot(SlotType.OUTPUT, this, OUTPUT_X - 1, OUTPUT_Y - 1);
        if (outputMenuIdx < menu.slots.size() && menu.slots.get(outputMenuIdx) instanceof IVirtualSlot ivs) {
            outputVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(outputVS);

        // Return slot background (EXTRA texture for return/container items)
        int returnMenuIdx = outputMenuIdx + 1;
        GuiVirtualSlot returnVS = new GuiVirtualSlot(SlotType.EXTRA, this, OUTPUT_X - 1, RETURN_Y - 1);
        if (returnMenuIdx < menu.slots.size() && menu.slots.get(returnMenuIdx) instanceof IVirtualSlot ivs) {
            returnVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(returnVS);

        // Progress bar (between input grid and output slot)
        int progressX = INPUT_START_X + INPUT_COLS * INPUT_SPACING + 10;
        int progressY = OUTPUT_Y + 4;
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
        int powerSlotIndex = SkeweringFactoryBlockEntity.INPUT_SLOTS + storageSlots + 4 + (hasStackUpgrade ? 1 : 0); // input(3)+storage+output(1)+return(1)+speed(1)+energy(1)+[stack(1)]
        if (powerSlotIndex < menu.slots.size()) {
            GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
            powerVs.with(SlotOverlay.POWER);
            if (menu.slots.get(powerSlotIndex) instanceof IVirtualSlot ivs) {
                powerVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(powerVs);
        }

        // §F23：自定义数量输入框（DIGIT 正整数、回车提交；默认隐藏，进「自」档才显示；
        // 初坐标为占位，渲染时按当前面板 resize）
        qtyField = new GuiTextField(this, 8, 8, 80, 14)
                .setInputValidator(InputValidator.DIGIT)
                .configureDigitalBorderInput(this::commitQty);
        qtyField.setMaxLength(9);
        qtyField.setText("");
        qtyField.setVisible(false);
        addRenderableWidget(qtyField);

        // §F24：配置覆盖层的按钮组 —— 改成 Mek 原生按钮组件。
        // 坐标逐像素沿用旧手绘版（CONFIG_START_X/Y + COL/ROW_GAP、DONE_BUTTON_W/H），排布不变；
        // 底图/hover/点击/提示全部交给框架，render() 里按 configMode 同步显隐。
        // 注册用 addRenderableWidget 落进 children()/renderables()；只 configButtons.add 是不够的 ——
        // 那样按钮既不会被 GuiMekanism 遍历绘制、也收不到命中测试（2026-09-27 核查发现该屏曾漏注册）。
        for (int i = 0; i < CONFIG_SIDE_DIRS.length; i++) {
            Direction dir = CONFIG_SIDE_DIRS[i];
            int bx = CONFIG_START_X + (i % 2) * CONFIG_COL_GAP;
            int by = CONFIG_START_Y + (i / 2) * CONFIG_ROW_GAP;
            // onRight 传 null：旧 handleConfigClick 只在 button==0 时被调用（右键本就无效），保持原行为。
            // 标签交给 Mek 的按钮文字渲染器（居中），每帧由 syncConfigOverlayButtons() 刷新。
            configButtons.add(addRenderableWidget(MekCkButtons.color(this, bx, by, CONFIG_BUTTON_W, CONFIG_BUTTON_H,
                    () -> colorForSideMode(menu.getSideMode(dir)),
                    configDirLabel(dir),
                    () -> cycleSideMode(dir, true), null)));
        }
        configButtons.add(addRenderableWidget(MekCkButtons.text(this,
                CONFIG_START_X, CONFIG_START_Y + 3 * CONFIG_ROW_GAP + 4, DONE_BUTTON_W, DONE_BUTTON_H,
                Component.literal("完成"), () -> configMode = false)));
        MekCkButtons.setShown(configButtons, false);

        // 侧栏 tab 统一走 Mek 原生 MekCkTabElement（见 addTabElements），注册在全部虚拟槽与
        // configMode 覆盖层按钮之后：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，越晚注册命中优先。tab 在 x=-26 / x=imageWidth，与覆盖层按钮
        // （x=10..136、y=10..100）不重叠，先后不影响命中。
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
                () -> configMode,
                mekanism.client.SpecialColors.TAB_CONFIGURATION,
                () -> List.of(Component.translatable("tooltip.mekck.side_config")),
                () -> {
                    configMode = !configMode;
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
                    configMode = false;
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
        }
        configMode = false;
    }

    /** 方向格按钮标签：相对朝向名 + 「面: 」+ 当前模式名（文案与旧版叠画的 drawString 完全一致）。 */
    private Component configDirLabel(Direction dir) {
        return Component.literal(getRelativeDirectionName(dir, getMachineFacing()) + "面: "
                + CONFIG_MODE_NAMES[menu.getSideMode(dir).ordinal()]);
    }

    /**
     * 每帧同步覆盖层按钮：显隐随 configMode，标签随「机器朝向 + 各面模式」变化。
     * 必须在 super.render() 之前调用，否则本帧画出的还是上一帧的标签。
     */
    private void syncConfigOverlayButtons() {
        MekCkButtons.setShown(configButtons, configMode);
        if (!configMode) {
            return;
        }
        for (int i = 0; i < CONFIG_SIDE_DIRS.length; i++) {
            configButtons.get(i).setMessage(configDirLabel(CONFIG_SIDE_DIRS[i]));
        }
    }

    /** 方向格点击：循环切换该面模式并同步服务端（等价旧 handleConfigClick 里的分支）。 */
    private void cycleSideMode(Direction dir, boolean next) {
        SideMode current = menu.getSideMode(dir);
        SideMode nextMode = current.cycle(next, menu.supportsStoragePull());
        ModMessages.sendToServer(new SideConfigPacket(menu.getBlockPos(), dir, nextMode));
    }

    /** 与 GuiMekCkSideConfiguration.colorForMode() 同一套映射：无=灰、输入=深红、输出=深蓝、存储抽取=黄。 */
    private static EnumColor colorForSideMode(SideMode mode) {
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

    /**
     * Calculates the actual energy consumption per tick based on upgrade counts.
     * Matches the calculation in SkeweringFactoryBlockEntity.serverTick.
     */
    private int getActualEnergyPerTick() {
        double speedMult = Math.pow(10, menu.getSpeedUpgradeCount() / 8.0);
        double energyConsumptionMult = Math.pow(0.1, menu.getEnergyUpgradeCount() / 8.0);
        int baseEnergyPerTick = (int) Math.ceil(SkeweringFactoryBlockEntity.BASE_ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);
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
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        int x = leftPos;
        int y = topPos;

        // 侧栏 4 个 tab（侧配 / 升级 / 下单 / 红石）已全部迁到 addTabElements()（MekCkTabElement），
        // renderBg 不再手绘；AE2 网络拉料两枚按钮也已在 addGuiElements() 末尾由
        // NetworkPullButton.register(...) 注册为 Mek 原生元素。

        // 配置覆盖层的纯装饰底板。必须画在 widget 层之前（renderBg 早于 renderables），
        // 否则会把已注册成原生按钮的方向格整块盖住。
        if (configMode) {
            guiGraphics.fill(x + 3, y + 3, x + imageWidth - 3, y + imageHeight - 3, 0xFF000000);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 搜索框懒初始化（本屏幕未覆写 init()）
        if (!orderSearchReady) {
            orderSearch.init(font, leftPos + 4, topPos + 20, 90);
            orderSearchReady = true;
        }
        orderSearch.move(leftPos + 4, topPos + 20, 90);
        orderSearch.render(guiGraphics, mouseX, mouseY, partialTick);
        // Temporarily set item counts to 1 for stacks with large counts
        bigStackHud.shrink(menu.slots);

        // §F24：配置覆盖层按钮组显隐 + 标签同步（必须早于 super.render，否则会晚一帧才显示/隐藏）
        syncConfigOverlayButtons();

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
            // §F23：退出下单面板时隐藏数量输入框
            qtyField.setVisible(false);
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

    private void renderOrderMode(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY) {
        // ME 网络下单模式（Mekanism 风格）
        if (networkOrderMode) {
            renderNetworkOrderMode(guiGraphics, x, y, mouseX, mouseY);
            return;
        }
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        // Mekanism 风格背景
        GuiUtils.renderBackgroundTexture(guiGraphics, MekanismUtils.getResource(ResourceType.GUI, "base.png"),
                4, 4, panelX, panelY, panelW, panelH, 256, 256);
        GuiUtils.drawOutline(guiGraphics, panelX, panelY, panelW, panelH, 0xFF3E6E91);

        // Title
        guiGraphics.drawString(font, "下单", panelX + 4, panelY + 4, 0xFFFFFFFF);

        // 下单来源切换：本机库存 / ME 网络（AE2 已装才显示 ME）
        boolean ae2Loaded = AE2Compat.isLoaded();
        int modeBtnW = 40;
        int modeBtnH = 13;
        int modeBtnY = panelY + 3;
        if (ae2Loaded) {
            int localBtnX = panelX + panelW - modeBtnW * 2 - 4;
            int netBtnX = panelX + panelW - modeBtnW;
            boolean localHover = isHovered(mouseX, mouseY, localBtnX, modeBtnY, modeBtnW, modeBtnH);
            boolean netHover = isHovered(mouseX, mouseY, netBtnX, modeBtnY, modeBtnW, modeBtnH);
            drawMekButton(guiGraphics, localBtnX, modeBtnY, modeBtnW, modeBtnH, "本机", localHover || !networkOrderMode, true);
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

        // Scroll indicators
        if (orderScrollOffset > 0) {
            guiGraphics.drawString(font, "↑", panelX + listWidth - 10, listTop, 0xFFFFFFFF);
        }
        if (orderScrollOffset + ORDER_LIST_ROWS < availableRecipes.size()) {
            guiGraphics.drawString(font, "↓", panelX + listWidth - 10, listBottom - 10, 0xFFFFFFFF);
        }

        // Quantity controls (below recipe list)
        int qtyY = listBottom + 4;
        guiGraphics.drawString(font, "数量: " + orderQuantity, panelX + 4, qtyY, 0xFFFFFFFF);

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
            int color = hovered ? 0xFF4488FF : 0xFF444444;
            boolean active = qtyValues[i] == -1 && customInputMode;
            drawMekButton(guiGraphics, bx, qtyBtnY, qtyBtnW, qtyBtnH, qtyLabels[i], hovered || active, true);
        }

        // §F23：Custom input → GuiTextField（本机面板位）
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

        // 自选组合穿串
        int mixBtnY = qtyBtnY + qtyBtnH + 14;
        boolean mixHovered = mouseX >= qtyBtnX && mouseX < qtyBtnX + 60 && mouseY >= mixBtnY && mouseY < mixBtnY + 14;
        guiGraphics.fill(qtyBtnX, mixBtnY, qtyBtnX + 60, mixBtnY + 14, customMixMode ? 0xFF44AA44 : (mixHovered ? 0xFF6666AA : 0xFF44446A));
        guiGraphics.drawString(font, "自选搭配", qtyBtnX + 8, mixBtnY + 3, 0xFFFFFFFF);
        if (customMixMode) {
            java.util.LinkedHashSet<ItemStack> distinct = new java.util.LinkedHashSet<>();
            int ss = cn.ism.mekck.blockentity.SkeweringFactoryBlockEntity.INPUT_SLOTS + 4;
            for (Slot s : menu.slots) {
                if (s.index >= ss && s.index < ss + machineStorageCount() && s.hasItem()) {
                    boolean dup = false;
                    for (ItemStack d : distinct) if (ItemStack.isSameItemSameTags(d, s.getItem())) { dup = true; break; }
                    if (!dup) distinct.add(s.getItem().copy());
                }
            }
            int ix = qtyBtnX + 64, iy = mixBtnY;
            for (ItemStack d : distinct) {
                if (ix + 18 > panelX + panelW - 4) { ix = qtyBtnX; iy += 18; }
                boolean sel = false;
                for (ItemStack c : customSelected) if (ItemStack.isSameItemSameTags(c, d)) { sel = true; break; }
                guiGraphics.fill(ix, iy, ix + 18, iy + 18, sel ? 0xFF44AA44 : 0xFF333333);
                guiGraphics.renderItem(d, ix + 1, iy + 1);
                ix += 20;
            }
            guiGraphics.drawString(font, "已选 " + customSelected.size() + " (点击材料添加/取消)", qtyBtnX, iy + 20, 0xFFAAAAAA);
        }

        // Confirm button
        int confirmBtnY = qtyBtnY + qtyBtnH + 6;
        int confirmBtnW = 60;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW) / 2;
        boolean confirmHovered = mouseX >= confirmBtnX && mouseX < confirmBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH;
        drawMekButton(guiGraphics, confirmBtnX, confirmBtnY, confirmBtnW, confirmBtnH,
                "确认下单", confirmHovered, true);

        // Cancel button
        int cancelBtnX = confirmBtnX + confirmBtnW + 4;
        boolean cancelHovered = mouseX >= cancelBtnX && mouseX < cancelBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH;
        drawMekButton(guiGraphics, cancelBtnX, confirmBtnY, confirmBtnW, confirmBtnH,
                "取消", cancelHovered, true);
    }

    /** ME 网络下单面板（Mekanism 风格）。 */
    private void renderNetworkOrderMode(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY) {
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        // Mekanism 风格背景
        GuiUtils.renderBackgroundTexture(guiGraphics, MekanismUtils.getResource(ResourceType.GUI, "base.png"),
                4, 4, panelX, panelY, panelW, panelH, 256, 256);
        GuiUtils.drawOutline(guiGraphics, panelX, panelY, panelW, panelH, 0xFF3E6E91);

        // 标题 + 来源切换
        guiGraphics.drawString(font, "ME \u7F51\u7EDC\u4E0B\u5355", panelX + 8, panelY + 6, 0xFFFFFFFF);
        int modeBtnW = 40, modeBtnH = 13;
        int modeBtnY = panelY + 3;
        int localBtnX = panelX + panelW - modeBtnW * 2 - 4;
        int netBtnX = panelX + panelW - modeBtnW;
        drawMekButton(guiGraphics, localBtnX, modeBtnY, modeBtnW, modeBtnH, "\u672C\u673A",
                isHovered(mouseX, mouseY, localBtnX, modeBtnY, modeBtnW, modeBtnH) || !networkOrderMode, true);
        drawMekButton(guiGraphics, netBtnX, modeBtnY, modeBtnW, modeBtnH, "ME",
                isHovered(mouseX, mouseY, netBtnX, modeBtnY, modeBtnW, modeBtnH) || networkOrderMode, true);
        guiGraphics.fill(panelX + 4, panelY + 21, panelX + panelW - 4, panelY + 22, 0xFF3E6E91);

        // 刷新配方列表
        if (orderListDirty) {
            if (!networkDataRequested) {
                networkDataRequested = true;
                ModMessages.sendToServer(new NetworkRecipeRequestPacket(menu.getBlockPos()));
            }
            availableRecipes = orderSearch.filter(resolveNetworkRecipes());
            orderListDirty = false;
        }

        int listTop = panelY + 27;
        int rowX = panelX + 6;
        // ME 模式改为**格子网格**（AE 终端样式）：列数按面板宽度运行时算，行数沿用 ORDER_LIST_ROWS。
        int cols = meGridCols();
        int cellsPerPage = cols * ORDER_LIST_ROWS;
        int listBottom = listTop + ORDER_LIST_ROWS * ME_CELL;
        int scrollbarX = panelX + panelW - 7;

        if (availableRecipes.isEmpty()) {
            String msg = networkDataRequested ? "ME\u7F51\u7EDC\u4E2D\u65E0\u53EF\u7528\u98DF\u6750" : "\u6B63\u5728\u83B7\u53D6ME\u7F51\u7EDC\u6570\u636E...";
            guiGraphics.drawString(font, msg, rowX + 2, listTop + 8, 0xFFAAAAAA);
        }

        // 格子网格（AE 单元格：1px 描边 + 底色 + 图标数量角标 + 缺料压暗 + 悬停高亮/提示）
        int visibleCount = Math.min(cellsPerPage, availableRecipes.size() - orderScrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int recipeIdx = orderScrollOffset + i;
            if (recipeIdx >= availableRecipes.size()) break;
            Recipe<?> recipe = availableRecipes.get(recipeIdx);
            // 网格定位：第 i 格 → 第 (i / cols) 行、第 (i % cols) 列
            int cellX = rowX + (i % cols) * ME_CELL;
            int cellY = listTop + (i / cols) * ME_CELL;
            boolean isSelected = recipe == selectedRecipe;
            boolean cellHover = isHovered(mouseX, mouseY, cellX, cellY, ME_CELL, ME_CELL);
            int bg = isSelected ? 0xE63E6E91 : (cellHover ? 0xE64A7BA6 : 0xE61C1C1C);
            guiGraphics.fill(cellX, cellY, cellX + ME_CELL, cellY + ME_CELL, 0xFF3E6E91);
            guiGraphics.fill(cellX + 1, cellY + 1, cellX + ME_CELL - 1, cellY + ME_CELL - 1, bg);

            ItemStack result = recipe.getResultItem(minecraft.level.registryAccess());
            int maxQty = networkMaxCraftable.getOrDefault(recipe.getId(), 0);
            boolean craftable = maxQty > 0;
            if (!result.isEmpty()) {
                guiGraphics.renderItem(result, cellX + 1, cellY + 1);
                // AE 终端风格：产物数量角标
                guiGraphics.renderItemDecorations(font, result, cellX + 1, cellY + 1);
            }
            if (!craftable) {
                // AE 终端风格：材料不足的项整体压暗
                guiGraphics.fill(cellX + 1, cellY + 1, cellX + ME_CELL - 1, cellY + ME_CELL - 1, 0x99000000);
            }

            if (cellHover) {
                // AE 终端风格：悬停显示产物名与可做次数
                guiGraphics.renderTooltip(font,
                        java.util.List.of(
                                result.isEmpty() ? net.minecraft.network.chat.Component.literal("Unknown") : result.getHoverName(),
                                net.minecraft.network.chat.Component.literal(craftable
                                        ? ("\u53EF\u505A " + maxQty + " \u6B21") : "\u6750\u6599\u4E0D\u8DB3")),
                        java.util.Optional.empty(), mouseX, mouseY);
            }
        }

        // 滚动条（细轨道 + 滑块；滚轮滚动 / 点击上下半页翻一行格子）
        if (availableRecipes.size() > cellsPerPage) {
            guiGraphics.fill(scrollbarX, listTop, scrollbarX + 2, listBottom, 0xFF3E6E91);
            int totalCells = Math.max(1, availableRecipes.size());
            int trackH = listBottom - listTop;
            int thumbH = Math.max(14, trackH * cellsPerPage / totalCells);
            int scrollRange = totalCells - cellsPerPage;
            int thumbY = listTop + (scrollRange == 0 ? 0 : orderScrollOffset * (trackH - thumbH) / scrollRange);
            guiGraphics.fill(scrollbarX - 1, thumbY, scrollbarX + 3, thumbY + thumbH, 0xFF4A7BA6);
        }

        // 数量区
        int qtyY = listBottom + 6;
        guiGraphics.drawString(font, "\u6570\u91CF: " + orderQuantity, rowX, qtyY, 0xFFFFFF55);

        int qtyBtnX = rowX;
        int qtyBtnY = qtyY + 12;
        int qtyBtnW = 24;
        int qtyBtnH = 14;
        String[] qtyLabels = {"1", "16", "32", "64", "\u81EA", "Max"};
        int[] qtyValues = {1, 16, 32, 64, -1, -2};
        for (int i = 0; i < qtyLabels.length; i++) {
            int bx = qtyBtnX + i * (qtyBtnW + 2);
            boolean active = qtyValues[i] == -1 && customInputMode;
            drawMekButton(guiGraphics, bx, qtyBtnY, qtyBtnW, qtyBtnH, qtyLabels[i],
                    isHovered(mouseX, mouseY, bx, qtyBtnY, qtyBtnW, qtyBtnH) || active, true);
        }

        // §F23：Custom input → GuiTextField（AE 面板位，与本机面板共用一个控件）
        if (qtyField != null) {
            qtyField.setVisible(customInputMode);
            if (customInputMode) {
                qtyField.resize(qtyBtnX - leftPos, qtyBtnY + qtyBtnH + 2 - topPos, 80, 14);
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
                    guiGraphics.drawString(font, "\u6700\u5927: " + maxQty, qtyBtnX, qtyBtnY + qtyBtnH + 2, 0xFFAAAAAA);
                }
            }
        }

        // 确认 / 取消
        int confirmBtnW = 74;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW * 2 - 8) / 2;
        int confirmBtnY = qtyBtnY + 30;
        drawMekButton(guiGraphics, confirmBtnX, confirmBtnY, confirmBtnW, confirmBtnH,
                "\u786E\u8BA4\u4E0B\u5355", isHovered(mouseX, mouseY, confirmBtnX, confirmBtnY, confirmBtnW, confirmBtnH), true);
        drawMekButton(guiGraphics, confirmBtnX + confirmBtnW + 8, confirmBtnY, confirmBtnW, confirmBtnH,
                "\u53D6\u6D88", isHovered(mouseX, mouseY, confirmBtnX + confirmBtnW + 8, confirmBtnY, confirmBtnW, confirmBtnH), true);
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

    /** 缺料清单可能超宽：按可用宽度截断。 */
    private String fitWidth(String text, int maxWidth) {
        if (text == null) return "";
        return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, Math.max(8, maxWidth - 6)) + "\u2026";
    }

    /**
     * ME 网格每行能放几个格子 —— 按面板可用宽度**运行时算**，窗口尺寸 / GUI 缩放变化都自适应。
     * <p><b>渲染、滚轮、点击三处必须共用这一个方法</b>，否则会出现「按 N 列画、按 M 列点」的错位。</p>
     */
    private int meGridCols() {
        int usable = imageWidth - ORDER_PANEL_LEFT * 2 - 24 - 4;
        return Math.max(1, usable / ME_CELL);
    }

    /** ME 网络下单点击处理（Mekanism 风格坐标）。 */
    private boolean handleNetworkOrderClick(double mouseX, double mouseY, int x, int y) {
        int panelX = x + ORDER_PANEL_LEFT;
        int panelY = y + ORDER_PANEL_TOP;
        int panelW = imageWidth - ORDER_PANEL_LEFT * 2;
        int panelH = imageHeight - ORDER_PANEL_TOP * 2;

        if (mouseX < panelX || mouseX > panelX + panelW || mouseY < panelY || mouseY > panelY + panelH) {
            orderMode = false;
            return true;
        }

        // 来源切换
        int modeBtnW = 40, modeBtnH = 13;
        int modeBtnY = panelY + 3;
        int localBtnX = panelX + panelW - modeBtnW * 2 - 4;
        int netBtnX = panelX + panelW - modeBtnW;
        if (isHovered(mouseX, mouseY, localBtnX, modeBtnY, modeBtnW, modeBtnH)) {
            networkOrderMode = false;
            selectedRecipe = null;
            orderListDirty = true;
            orderScrollOffset = 0;
            exitCustomInput();
            return true;
        }
        if (isHovered(mouseX, mouseY, netBtnX, modeBtnY, modeBtnW, modeBtnH)) {
            return true;
        }

        // 配方格子点击（与渲染共用同一套网格几何 —— 列数出自同一个 meGridCols()）
        int listTop = panelY + 27;
        int rowX = panelX + 6;
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

        // 滚动条点击（上/下半页各翻一行格子）
        int listBottom = listTop + ORDER_LIST_ROWS * ME_CELL;
        int scrollbarX = panelX + panelW - 7;
        if (mouseX >= scrollbarX - 2 && mouseX < scrollbarX + 4
                && mouseY >= listTop && mouseY < listBottom) {
            int maxOffset = Math.max(0, availableRecipes.size() - cellsPerPage);
            if (mouseY < listTop + (listBottom - listTop) / 2) {
                orderScrollOffset = Math.max(0, orderScrollOffset - cols);
            } else {
                orderScrollOffset = Math.min(maxOffset, orderScrollOffset + cols);
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
                        orderQuantity = Math.max(1, networkMaxCraftable.getOrDefault(selectedRecipe.getId(), 0));
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
        if (orderSearch.mouseClicked(mouseX, mouseY, button)) return true;
        // 4 个侧栏 tab 的点击已交给 MekCkTabElement#onClick —— 它们是 renderable widget，
        // 由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        if (button == 0) {
            int x = leftPos;
            int y = topPos;

            if (configMode) {
                // §F24：覆盖层内的方向格 / 「完成」已是真 widget，点击由 Mek 原生命中测试处理；
                // 遮罩区域仍吞掉左键（与旧 handleConfigClick 一致：configMode 下左键必被消费，不落到槽位）。
                super.mouseClicked(mouseX, mouseY, button);
                return true;
            }

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
        if (networkOrderMode) requestNetworkMissing();
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
        if (orderMode && !availableRecipes.isEmpty()) {
            // ME 模式一屏 = cols × ORDER_LIST_ROWS 个格子；本机模式仍是 ORDER_LIST_ROWS 行（行式列表）。
            int perPage = networkOrderMode ? meGridCols() * ORDER_LIST_ROWS : ORDER_LIST_ROWS;
            int maxOffset = Math.max(0, availableRecipes.size() - perPage);
            if (delta > 0) { orderScrollOffset = Math.max(0, orderScrollOffset - 1); }
            else { orderScrollOffset = Math.min(maxOffset, orderScrollOffset + 1); }
            orderScrollOffset = Math.max(0, Math.min(maxOffset, orderScrollOffset));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int machineStorageCount() {
        try { return menu.getMachine().getStorageSlots(); } catch (Exception e) { return 81; }
    }

    private boolean handleOrderClick(double mouseX, double mouseY, int x, int y) {
        // ME 网络下单模式
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
            if (isHovered(mouseX, mouseY, localBtnX, modeBtnY, modeBtnW, modeBtnH)) {
                if (networkOrderMode) {
                    networkOrderMode = false;
                    selectedRecipe = null;
                    orderListDirty = true;
                    orderScrollOffset = 0;
                    exitCustomInput();
                }
                return true;
            }
            if (isHovered(mouseX, mouseY, netBtnX, modeBtnY, modeBtnW, modeBtnH)) {
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

        // §F23：输入框点击聚焦改由 GuiTextField 控件自身处理

        // Confirm button
        int confirmBtnY = qtyBtnY + qtyBtnH + 6;
        int confirmBtnW = 60;
        int confirmBtnH = 18;
        int confirmBtnX = panelX + (panelW - confirmBtnW) / 2;

        // Confirm button
        if (mouseX >= confirmBtnX && mouseX < confirmBtnX + confirmBtnW
                && mouseY >= confirmBtnY && mouseY < confirmBtnY + confirmBtnH) {
            if (!customSelected.isEmpty() && orderQuantity > 0) {
                ModMessages.sendToServer(new cn.ism.mekck.network.SkewerThreadingOrderPacket(menu.getBlockPos(), null, orderQuantity, new java.util.ArrayList<>(customSelected)));
                orderMode = false; selectedRecipe = null; orderQuantity = 1; customMixMode = false; customSelected.clear();
                return true;
            }
            if (selectedRecipe != null && orderQuantity > 0) {
                ResourceLocation recipeId = selectedRecipe.getId();
                ModMessages.sendToServer(new OrderRecipePacket(menu.getBlockPos(), recipeId, orderQuantity));
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
}
