package cn.ism.mekck.client;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.blockentity.IceMakerBlockEntity;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.IceMakerMenu;
import cn.ism.mekck.network.IceAttackConfigPacket;
import cn.ism.mekck.network.ModMessages;
import net.minecraft.world.item.crafting.Recipe;
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
import mekanism.client.gui.element.slot.SlotType;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.container.slot.IVirtualSlot;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 急冻制冰机屏幕（Mekanism 风格）：
 * 输入/输出槽 + 进度箭头 + 水流体条 + 能量条 + 冷萃/创造升级槽 + 攻击控制行，
 * 左侧配置 tab、右上升级 tab、右下红石控制 tab，与其他基础机器保持一致。
 */
public final class IceMakerScreen extends GuiMekanism<IceMakerMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    /** 下单 tab 的 y —— 侧配 tab 下方 28px（与其它屏一致）。 */
    private static final int ORDER_TAB_Y = 34;
    /**
     * 「下单」标签页 —— 点开 {@link NetworkOrderWindow}。
     *
     * <p>必须留引用：{@code GuiWindowCreatorTab} 关闭窗口时靠 {@code elementSupplier.get()}
     * 把同一实例重新激活，宿主屏幕也靠它取「正在显示的那一个」面板。</p>
     */
    private NetworkOrderTab orderTab;
    // Mekanism 风格 tab 布局（与其他机器一致）
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;
    private static final int UPGRADE_TAB_Y = 6;

    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;

    // Redstone control icon textures (Mekanism)
    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    // Mekanism textures
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");
    /** 攻击控制行 / 温度控制行的按钮仍走这张 Mekanism button.png（非 tab 部分，勿删）。 */
    private static final ResourceLocation BUTTON_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "button.png");

    // 攻击控制行布局
    private static final int TARGET_X = IceMakerMenu.INPUT_X;
    private static final int TARGET_W = 70;
    private static final int ATTACK_BTN_H = 16;
    private static final int MINUS_X = TARGET_X + TARGET_W + 6;
    private static final int PLUS_X = 178;
    private static final int SMALL_BTN = 16;
    private static final int RADIUS_TEXT_X = MINUS_X + SMALL_BTN + 4;

    // 温度控制行（位于攻击控制行下方）
    private static final int TEMP_ROW_Y = IceMakerMenu.TEMP_ROW_Y;
    private static final int TEMP_TOGGLE_W = 46;
    private static final int TEMP_MINUS_X = TARGET_X + TEMP_TOGGLE_W + 4;
    private static final int TEMP_TEXT_X = TEMP_MINUS_X + SMALL_BTN + 4;
    private static final int TEMP_PLUS_X = 178;
    /** 索敌半径 / 目标温度输入框（Mekanism GuiTextField，统一数字输入）。 */
    private GuiTextField radiusField;
    private GuiTextField tempField;

    // 两个输入框的布局（替换掉旧的 DIY 输入态与 -/+ 步进按钮）
    private static final int RADIUS_FIELD_X = 140;
    private static final int RADIUS_FIELD_W = 54;
    private static final int TEMP_FIELD_X = 116;
    private static final int TEMP_FIELD_W = 54;

    public IceMakerScreen(IceMakerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = IceMakerMenu.IMAGE_WIDTH;
        imageHeight = IceMakerMenu.IMAGE_HEIGHT;
        inventoryLabelY = IceMakerMenu.INV_TOP - 12;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // 输入槽
        GuiVirtualSlot inputVs = new GuiVirtualSlot(SlotType.INPUT, this, IceMakerMenu.INPUT_X - 1, IceMakerMenu.INPUT_Y - 1);
        if (menu.slots.get(IceMakerBlockEntity.INPUT_SLOT) instanceof IVirtualSlot ivs) {
            inputVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(inputVs);

        // 输出槽
        GuiVirtualSlot outputVs = new GuiVirtualSlot(SlotType.OUTPUT, this, IceMakerMenu.OUTPUT_X, IceMakerMenu.OUTPUT_Y - 1);
        if (menu.slots.get(IceMakerBlockEntity.OUTPUT_SLOT) instanceof IVirtualSlot ivs) {
            outputVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(outputVs);

        // 进度条（Mekanism SMALL_RIGHT 箭头，位于输入与输出之间）
        int progressX = IceMakerMenu.INPUT_X + 18 + (IceMakerMenu.OUTPUT_X - IceMakerMenu.INPUT_X - 18 - 28) / 2;
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgress() / 24.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgress() > 0;
            }
        }, ProgressType.SMALL_RIGHT, this, progressX, IceMakerMenu.INPUT_Y + 5));

        // 水流体条（左侧，Mekanism 标准流体条）
        addRenderableWidget(new GuiCkFluidGauge(this, 6, 34,
                () -> menu.getWaterStack(), () -> menu.getWaterCapacity()));

        // 能量条（右侧）
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                        menu.getEnergy(), IceMakerBlockEntity.ENERGY_CAPACITY);
            }

            @Override
            public double getLevel() {
                return (double) menu.getEnergy() / IceMakerBlockEntity.ENERGY_CAPACITY;
            }
        }, imageWidth - 12, 22));

        // 能量信息标签（左下角）
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
                Component.translatable("gui.mekck.energy_stored",
                        menu.getEnergy(), IceMakerBlockEntity.ENERGY_CAPACITY),
                Component.translatable("gui.mekck.energy_per_tick",
                        IceMakerBlockEntity.ENERGY_PER_TICK)
        )));

        // 能源槽（能量物品）
        GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
        powerVs.with(SlotOverlay.POWER);
        if (menu.slots.get(IceMakerBlockEntity.SLOT_POWER) instanceof IVirtualSlot ivs) {
            powerVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(powerVs);

        // 冷萃升级槽（主界面，输入槽下方一排，①~⑤ 共 5 格）
        for (int i = 0; i < 5; i++) {
            GuiVirtualSlot cbVs = new GuiVirtualSlot(SlotType.NORMAL, this, IceMakerMenu.INPUT_X + i * 18, IceMakerMenu.CB_ROW_Y);
            if (menu.slots.get(IceMakerBlockEntity.CB_SLOT_1 + i) instanceof IVirtualSlot ivs) {
                cbVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(cbVs);
        }

        // 创造升级槽（冷萃槽右侧）
        GuiVirtualSlot creativeVs = new GuiVirtualSlot(SlotType.NORMAL, this, IceMakerMenu.INPUT_X + 5 * 18 + 8, IceMakerMenu.CB_ROW_Y);
        if (menu.slots.get(IceMakerBlockEntity.SLOT_CREATIVE_UPGRADE) instanceof IVirtualSlot ivs) {
            creativeVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(creativeVs);

        // 索敌半径：Mekanism 数字输入框（DIGIT，回车提交绝对值），替换旧的 DIY 输入框与 -/+ 步进按钮。
        // §F43（用户口径：我们的输入框缺了 Mekanism 那块黑色背景）：取证据 Mekanism 字节码——
        // configureDigitalInput 把背景设为 NONE（只改绿色屏字），带黑底的是 configureDigitalBorderInput
        // （BackgroundType.DIGITAL：灰外框+内层 0xFF000000），两者签名相同⇒全仓 8 处调用换后者。
        radiusField = new GuiTextField(this, RADIUS_FIELD_X, IceMakerMenu.ATTACK_ROW_Y, RADIUS_FIELD_W, ATTACK_BTN_H)
                .setInputValidator(InputValidator.DIGIT)
                .configureDigitalBorderInput(this::commitRadiusInput);
        radiusField.setMaxLength(10);
        radiusField.setText(String.valueOf(menu.getRadius()));
        addRenderableWidget(radiusField);

        // 目标温度：Mekanism 数字输入框（DECIMAL 允许负号，回车提交），本机只降温、值恒 ≤0
        tempField = new GuiTextField(this, TEMP_FIELD_X, TEMP_ROW_Y, TEMP_FIELD_W, ATTACK_BTN_H)
                .setInputValidator(InputValidator.DECIMAL.or(InputValidator.from('-')))
                .configureDigitalBorderInput(this::commitTempInput);
        tempField.setMaxLength(8);
        tempField.setText(String.format("%.2f", menu.getTargetTemperature() / 100.0));
        addRenderableWidget(tempField);

        // 侧栏 tab **最后注册**：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，即越晚注册命中优先。tab 全部在面板之外（x=-26~-2 / x=imageWidth~+24），
        // 与攻击/温度控制行、虚拟槽都不重叠，故不会抢掉它们的点击。
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
                "tooltip.mekck.side_config", this::openSideConfigWindow);

        // ME 下单在 Mek 里无对应图标：保留本模组自绘的「清单 + 向下箭头」图标，只取官方染色。
        // 面板本体已从「屏幕手绘覆盖层」迁进 Mek 虚拟窗口（NetworkOrderWindow），
        // 本机数据源在窗口创建时注入（见 localOrderSource()）。
        orderTab = addRenderableWidget(new NetworkOrderTab(this, menu.getBlockPos(),
                TAB_X, ORDER_TAB_Y, true, localOrderSource(), () -> orderTab));

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

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, (imageWidth - 162) / 2, inventoryLabelY, titleTextColor());
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        int x = leftPos;
        int y = topPos;

        // 侧栏 4 个 tab（侧配 / 下单 / 升级 / 红石）已全部迁到 addTabElements()（MekCkTabElement），renderBg 不再手绘。

        renderAttackControls(guiGraphics, mouseX, mouseY, x, y);
        renderTemperatureControls(guiGraphics, mouseX, mouseY, x, y);
    }

    /** 攻击控制行：目标类型按钮 + 半径标签（数值输入交给 Mekanism GuiTextField，见 addGuiElements）。 */
    private void renderAttackControls(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        int attackY = y + IceMakerMenu.ATTACK_ROW_Y;

        // 目标类型按钮
        int t = menu.getTargetType();
        String targetName = t == 0 ? "敌对" : t == 1 ? "全部" : "动物";
        int color = t == 0 ? 0xFFE33B32 : t == 1 ? 0xFF4488FF : 0xFF66CC66;
        int tX = x + TARGET_X;
        boolean tHovered = mouseX >= tX && mouseX < tX + TARGET_W && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H;
        guiGraphics.blit(BUTTON_TEXTURE, tX, attackY, 0, tHovered ? 20 : 0, TARGET_W, ATTACK_BTN_H, 200, 60);
        guiGraphics.fill(tX + 1, attackY + 1, tX + TARGET_W - 1, attackY + ATTACK_BTN_H - 1, color);
        String label = Component.translatable("gui.mekck.ui.target", targetName).getString();
        guiGraphics.drawString(font, label, tX + (TARGET_W - font.width(label)) / 2, attackY + 4, 0xFFFFFFFF);

        // 半径标签（数值编辑交给右侧 Mekanism 输入框）
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.radius").getString(), x + MINUS_X - 4, attackY + 4, 0xFFFFFFFF);
    }

    /** 温度控制行：控温开关按钮 + 目标温度输入标签 + 当前温度读数（目标值编辑交给 Mekanism GuiTextField）。 */
    private void renderTemperatureControls(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        int rowY = y + TEMP_ROW_Y;
        // 开关按钮
        boolean enabled = menu.isTemperatureControlEnabled();
        int toggleX = x + TARGET_X;
        boolean toggleHovered = mouseX >= toggleX && mouseX < toggleX + TEMP_TOGGLE_W
                && mouseY >= rowY && mouseY < rowY + ATTACK_BTN_H;
        guiGraphics.blit(BUTTON_TEXTURE, toggleX, rowY, 0, toggleHovered ? 20 : 0, TEMP_TOGGLE_W, ATTACK_BTN_H, 200, 60);
        guiGraphics.fill(toggleX + 1, rowY + 1, toggleX + TEMP_TOGGLE_W - 1, rowY + ATTACK_BTN_H - 1,
                enabled ? 0xFF33AA55 : 0xFF777777);
        String toggleLabel = enabled ? "控温:开" : "控温:关";
        guiGraphics.drawString(font, toggleLabel,
                toggleX + (TEMP_TOGGLE_W - font.width(toggleLabel)) / 2, rowY + 4, 0xFFFFFFFF);
        // 目标输入标签 + 当前温度读数（目标值本身显示/编辑在中间输入框内）
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.target").getString(), toggleX + TEMP_TOGGLE_W + 2, rowY + 4, 0xFFFFFFFF);
        String curText = String.format("%.1f℃", menu.getCurrentTemperature() / 100.0);
        guiGraphics.drawString(font, curText, x + TEMP_FIELD_X + TEMP_FIELD_W + 2, rowY + 4, 0xFFAAAAAA);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 大堆叠数量压制为 1 渲染，随后以缩放字体绘制格式化数量（与其他机器一致）
        bigStackHud.shrink(menu.slots);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        bigStackHud.restore();

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

        // 侧栏 4 个 tab 的 tooltip 已迁到 MekCkTabElement#renderToolTip，
        // 由 GuiMekanism#renderLabels 在渲染管线最后一层统一派发。
    }

    private void cycleTarget() {
        int t = (menu.getTargetType() + 1) % 3;
        ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 0, t));
    }

    /** 提交半径输入：从 Mekanism 输入框取值、钳制到 ≥4（上限不限）后以绝对值 type2 发送。 */
    private void commitRadiusInput() {
        String text = radiusField.getText();
        if (!text.isEmpty()) {
            try {
                int v = (int) Math.max(4L, Math.min((long) Integer.MAX_VALUE, Long.parseLong(text)));
                if (v != menu.getRadius()) {
                    ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 2, v));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /** 提交目标温度：本机只降温，取 -|输入| 钳制到 [-273.15, 0]，×100 四舍五入后以 type3 发送。 */
    private void commitTempInput() {
        String text = tempField.getText();
        if (!text.isEmpty()) {
            try {
                double celsius = -Math.abs(Double.parseDouble(text));
                celsius = Math.max(-273.15, Math.min(0.0, celsius));
                int milli = (int) Math.round(celsius * 100.0);
                if (milli != menu.getTargetTemperature()) {
                    ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 3, milli));
                }
            } catch (NumberFormatException ignored) {
            }
        }
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
            int x = leftPos;
            int y = topPos;

            // 攻击控制行
            int attackY = y + IceMakerMenu.ATTACK_ROW_Y;
            int tX = x + TARGET_X;
            if (mouseX >= tX && mouseX < tX + TARGET_W && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H) {
                cycleTarget();
                return true;
            }
            // 温度控制行：仅保留控温开关（type5），-/+ 步进与 DIY 输入已移除
            int tempY = y + TEMP_ROW_Y;
            int toggleX = x + TARGET_X;
            if (mouseX >= toggleX && mouseX < toggleX + TEMP_TOGGLE_W
                    && mouseY >= tempY && mouseY < tempY + ATTACK_BTN_H) {
                ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 5,
                        menu.isTemperatureControlEnabled() ? 0 : 1));
                return true;
            }
        }
        // 侧栏 4 个 tab（侧配 / 下单 / 升级 / 红石）的点击交给 MekCkTabElement#onClick —— 它们是
        // renderable widget，由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        // 「下单」tab 开的是 Mek 窗口，窗口内的点击由 GuiMekanism#mouseClicked 先遍历 windows 派发
        // （窗口在 children() 之前），所以旧版那份「面板开着时先定向派发 tab」的补丁已随面板一起删除。
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
