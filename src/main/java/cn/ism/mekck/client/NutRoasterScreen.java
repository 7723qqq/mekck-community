package cn.ism.mekck.client;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.blockentity.NutRoasterBlockEntity;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.NutRoasterMenu;
import cn.ism.mekck.network.IceAttackConfigPacket;
import cn.ism.mekck.network.ModMessages;
import net.minecraft.world.item.crafting.Recipe;
import cn.ism.mekck.network.NetworkOrderPacket;
import cn.ism.mekck.network.RedstoneControlPacket;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import mekanism.client.SpecialColors;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.bar.GuiBar.IBarInfoHandler;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.client.gui.element.tab.GuiEnergyTab;
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
 * 坚果爆炒机屏幕（Mekanism 风格）：输入/输出槽 + 进度箭头 + 能量条 + 攻击控制行（目标类型/半径），
 * 左侧配置 tab、右上升级 tab、右下红石控制 tab，与急冻制冰机保持一致。
 */
public final class NutRoasterScreen extends GuiMekanism<NutRoasterMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    // 侧栏 tab 几何：外框 24 / 按钮·图标 16 已内建于 MekCkTabElement（OUTER/INNER），
    // 旧常量 TAB_OUTER_W/H、TAB_ICON_SIZE 随之删除；坐标常量一律不动。
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;
    private static final int UPGRADE_TAB_Y = 6;

    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;

    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");
    /** 攻击控制行仍沿用 Mekanism button.png（renderAttackControls），不在本次 tab 改造范围内。 */
    private static final ResourceLocation BUTTON_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "button.png");

    // 攻击控制行布局
    private static final int TARGET_X = NutRoasterMenu.INPUT_X;
    private static final int TARGET_W = 70;
    private static final int ATTACK_BTN_H = 16;
    private static final int MINUS_X = TARGET_X + TARGET_W + 6;
    private static final int PLUS_X = 178;
    private static final int SMALL_BTN = 16;
    private static final int RADIUS_TEXT_X = MINUS_X + SMALL_BTN + 4;

    /** 索敌半径输入态：点击半径数值进入，可直接键入数字（回车提交/ESC 取消）。 */
    private boolean radiusInputMode;
    private String radiusInputText = "";

    /** 「ME 下单」面板开关（本屏没有本机下单列表 ⇒ 面板恒为 ME 模式）。 */
    private boolean orderMode = false;
    private static final int ORDER_TAB_Y = 34; // 侧配 tab 下方 28px（与其它屏一致）
    private static final int ORDER_PANEL_LEFT = 10;
    private static final int ORDER_PANEL_TOP = 10;
    /** 下单 tab 图标：自绘「清单 + 向下箭头」（原来借用的 Mekanism sorting.png 像音符、且语义不符）。 */
    private static final ResourceLocation ORDER_TEXTURE = MachineTabIcons.ORDER;
    private final NetworkOrderPanel mePanel = new NetworkOrderPanel(true, true);

    public NutRoasterScreen(NutRoasterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = NutRoasterMenu.IMAGE_WIDTH;
        imageHeight = NutRoasterMenu.IMAGE_HEIGHT;
        inventoryLabelY = NutRoasterMenu.INV_TOP - 12;
        dynamicSlots = true;
        wireLocalOrderSource();
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // 输入槽
        GuiVirtualSlot inputVs = new GuiVirtualSlot(SlotType.INPUT, this, NutRoasterMenu.INPUT_X - 1, NutRoasterMenu.INPUT_Y - 1);
        if (menu.slots.get(NutRoasterBlockEntity.INPUT_SLOT) instanceof IVirtualSlot ivs) {
            inputVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(inputVs);

        // 输出槽
        GuiVirtualSlot outputVs = new GuiVirtualSlot(SlotType.OUTPUT, this, NutRoasterMenu.OUTPUT_X, NutRoasterMenu.OUTPUT_Y - 1);
        if (menu.slots.get(NutRoasterBlockEntity.OUTPUT_SLOT) instanceof IVirtualSlot ivs) {
            outputVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(outputVs);

        // 进度条
        int progressX = NutRoasterMenu.INPUT_X + 18 + (NutRoasterMenu.OUTPUT_X - NutRoasterMenu.INPUT_X - 18 - 28) / 2;
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgress() / 24.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgress() > 0;
            }
        }, ProgressType.SMALL_RIGHT, this, progressX, NutRoasterMenu.INPUT_Y + 5));

        // 能量条（右侧）
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                        menu.getEnergy(), NutRoasterBlockEntity.ENERGY_CAPACITY);
            }

            @Override
            public double getLevel() {
                return (double) menu.getEnergy() / NutRoasterBlockEntity.ENERGY_CAPACITY;
            }
        }, imageWidth - 12, 22));

        // 能量信息标签（左下角）
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
                Component.translatable("gui.mekck.energy_stored",
                        menu.getEnergy(), NutRoasterBlockEntity.ENERGY_CAPACITY),
                Component.translatable("gui.mekck.energy_per_tick",
                        NutRoasterBlockEntity.ENERGY_PER_TICK)
        )));

        // 能源槽（能量物品）
        GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 7, 12);
        powerVs.with(SlotOverlay.POWER);
        if (menu.slots.get(NutRoasterBlockEntity.SLOT_POWER) instanceof IVirtualSlot ivs) {
            powerVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(powerVs);

        // 创造升级槽（产物格右侧，主界面常显）
        GuiVirtualSlot creativeVs = new GuiVirtualSlot(SlotType.NORMAL, this, NutRoasterMenu.OUTPUT_X + 2 * 18 + 8, NutRoasterMenu.OUTPUT_Y);
        if (menu.slots.get(NutRoasterBlockEntity.SLOT_CREATIVE_UPGRADE) instanceof IVirtualSlot ivs) {
            creativeVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(creativeVs);

        // 侧栏 tab **最后注册**：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、命中即返回，
        // 即越晚注册命中优先（tab 才能压过同区域的虚拟槽）。同 CuttingMachineFactoryScreen。
        addTabElements();

        // 侧栏 tab 必须最后注册：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，越晚注册命中优先。
        if (cn.ism.mekck.client.NetworkPullButton.isVisible()) {
            for (var tab : cn.ism.mekck.client.NetworkPullButton.register(this,
                    NetworkPullButton.getX(imageWidth),
                    NetworkPullButton.getY(ORDER_TAB_Y),
                    menu.getBlockPos())) {
                addRenderableWidget(tab);
            }
        }
    }

    /**
     * 侧栏 4 个 tab 统一走 Mek {@link MekCkTabElement}（继承 {@code GuiInsetElement}）：
     * 三层绘制与旧手绘逐参数一致，tooltip 改走 {@code GuiMekanism#renderLabels} 的元素通道
     * —— 那是渲染管线最后一层，结构上不会再被槽位盖住。
     * 旧实现在 {@code renderBg()} 里直绘 tooltip + 在 {@code mouseClicked} 里手算命中矩形，现已一并移除。
     */
    private void addTabElements() {
        // ── 左列（2 个）──
        addRenderableWidget(tab(CONFIG_TEXTURE, TAB_X, CONFIG_TAB_Y, true,
                SpecialColors.TAB_CONFIGURATION,
                () -> false,
                () -> List.of(Component.translatable("tooltip.mekck.side_config")),
                this::openSideConfigWindow));

        addRenderableWidget(tab(ORDER_TEXTURE, TAB_X, ORDER_TAB_Y, true,
                SpecialColors.TAB_CONTAINER_EDIT_MODE,
                () -> orderMode,
                () -> List.of(Component.translatable("tooltip.mekck.order_panel")),
                this::toggleOrderMode));

        // ── 右列（2 个）──
        addRenderableWidget(tab(UPGRADE_TEXTURE, imageWidth, UPGRADE_TAB_Y, false,
                SpecialColors.TAB_UPGRADE,
                () -> false,
                () -> List.of(Component.translatable("tooltip.mekck.upgrade")),
                this::openUpgradeWindow));

        addRenderableWidget(redstoneTab());
    }

    /**
     * 侧栏 tab 工厂：几何固定 24×24 外框 + 16×16 按钮/图标（等价旧 {@code TAB_OUTER_W/H}
     * 与 {@code TAB_ICON_SIZE}），holder 染成对应 Mek tab 颜色。
     */
    private MekCkTabElement tab(ResourceLocation icon, int relX, int relY, boolean left,
                                 ColorRegistryObject tint, BooleanSupplier selected,
                                 Supplier<List<Component>> tooltip, Runnable action) {
        return new MekCkTabElement(this, icon, relX, relY, left,
                MekCkTabElement.OUTER, MekCkTabElement.INNER, selected, tint, tooltip, action, null);
    }

    /**
     * 红石控制 tab：Mek 原生 26×26/18×18 规格、+3/+4 偏移、holder 染红、图标随三态切换，
     * PULSE 态额外叠一层脉冲动画。左键下一档 / 右键上一档。
     */
    private MekCkTabElement redstoneTab() {
        MekCkTabElement rsTab = new MekCkTabElement(this, REDSTONE_DISABLED,
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
        rsTab.buttonOffset(3, 4);
        rsTab.dynamicOverlay(() -> switch (RedstoneControl.byOrdinal(menu.getRedstoneControl())) {
            case HIGH -> REDSTONE_HIGH;
            case LOW -> REDSTONE_LOW;
            default -> REDSTONE_DISABLED;
        });
        rsTab.overlayLayer(gg -> {
            if (RedstoneControl.byOrdinal(menu.getRedstoneControl()) == RedstoneControl.PULSE) {
                rsTab.drawInnerOverlay(gg, mekanism.client.render.MekanismRenderer.redstonePulse);
            }
        });
        return rsTab;
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
        drawString(guiGraphics, playerInventoryTitle, (imageWidth - 162) / 2, inventoryLabelY, titleTextColor());
        // 温度系统：显示机身温度（摄氏度）
        guiGraphics.drawString(font, String.format("温度: %.1f℃", menu.getTemperature() / 100.0), 20, 78, 0xFFFF5555);
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        int x = leftPos;
        int y = topPos;

        // AE2 网络拉料按钮已迁到 addGuiElements() 末尾的 NetworkPullButton.register(...)（Mek 原生 tab）。
        // 侧栏 4 个 tab 已全部迁到 addTabElements()（MekCkTabElement），renderBg 不再手绘。

        renderAttackControls(guiGraphics, mouseX, mouseY, x, y);
    }

    /** 攻击控制行：目标类型按钮 + 半径 -/+ 按钮（Mekanism button.png 风格）。 */
    private void renderAttackControls(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        int attackY = y + NutRoasterMenu.ATTACK_ROW_Y;

        int t = menu.getTargetType();
        String targetName = t == 0 ? "敌对" : t == 1 ? "全部" : "动物";
        int color = t == 0 ? 0xFFE33B32 : t == 1 ? 0xFF4488FF : 0xFF66CC66;
        int tX = x + TARGET_X;
        boolean tHovered = mouseX >= tX && mouseX < tX + TARGET_W && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H;
        guiGraphics.blit(BUTTON_TEXTURE, tX, attackY, 0, tHovered ? 20 : 0, TARGET_W, ATTACK_BTN_H, 200, 60);
        guiGraphics.fill(tX + 1, attackY + 1, tX + TARGET_W - 1, attackY + ATTACK_BTN_H - 1, color);
        String label = "目标:" + targetName;
        guiGraphics.drawString(font, label, tX + (TARGET_W - font.width(label)) / 2, attackY + 4, 0xFFFFFFFF);

        int mX = x + MINUS_X;
        boolean mHovered = mouseX >= mX && mouseX < mX + SMALL_BTN && mouseY >= attackY && mouseY < attackY + SMALL_BTN;
        guiGraphics.blit(BUTTON_TEXTURE, mX, attackY, 0, mHovered ? 20 : 0, SMALL_BTN, SMALL_BTN, 200, 60);
        guiGraphics.drawString(font, "-", mX + 6, attackY + 4, 0xFFFFFFFF);

        if (radiusInputMode) {
            int boxX = x + RADIUS_TEXT_X;
            int boxW = PLUS_X - RADIUS_TEXT_X - 2;
            guiGraphics.fill(boxX, attackY, boxX + boxW, attackY + ATTACK_BTN_H, 0xFF000000);
            guiGraphics.fill(boxX + 1, attackY + 1, boxX + boxW - 1, attackY + ATTACK_BTN_H - 1, 0xFFFFFFFF);
            String displayText = radiusInputText.isEmpty() ? "≥4" : radiusInputText;
            int textColor = radiusInputText.isEmpty() ? 0xFF888888 : 0xFF000000;
            guiGraphics.drawString(font, displayText, boxX + 2, attackY + 4, textColor);
        } else {
            String radiusText = "半径:" + menu.getRadius();
            guiGraphics.drawString(font, radiusText, x + RADIUS_TEXT_X, attackY + 4, 0xFFFFFFFF);
        }

        int pX = x + PLUS_X;
        boolean pHovered = mouseX >= pX && mouseX < pX + SMALL_BTN && mouseY >= attackY && mouseY < attackY + SMALL_BTN;
        guiGraphics.blit(BUTTON_TEXTURE, pX, attackY, 0, pHovered ? 20 : 0, SMALL_BTN, SMALL_BTN, 200, 60);
        guiGraphics.drawString(font, "+", pX + 5, attackY + 4, 0xFFFFFFFF);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
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

        int x = leftPos;
        int y = topPos;

        // 侧栏 4 个 tab 的 tooltip 已迁到 MekCkTabElement#renderToolTip，
        // 由 GuiMekanism#renderLabels 在渲染管线最后一层统一派发。

        // ME 下单面板最后画（在 GUI 文字 / 槽位之上；本屏没有本机下单列表 ⇒ 只有 ME 一侧）
        if (orderMode) {
            mePanel.bind(menu.getBlockPos());
            mePanel.render(guiGraphics, font, x + ORDER_PANEL_LEFT, y + ORDER_PANEL_TOP,
                    imageWidth - ORDER_PANEL_LEFT * 2, imageHeight - ORDER_PANEL_TOP * 2,
                    mouseX, mouseY, partialTick);
        }
    }

    private void cycleTarget() {
        int t = (menu.getTargetType() + 1) % 3;
        ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 0, t));
    }

    private void sendRadius(int delta) {
        ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 1, delta));
    }

    /** 提交半径输入内容：解析数字并钳制到 ≥4（上限不限）后以绝对值发送，随后退出输入态。 */
    private void commitRadiusInput() {
        if (!radiusInputText.isEmpty()) {
            int v = (int) Math.max(4L, Math.min((long) Integer.MAX_VALUE, Long.parseLong(radiusInputText)));
            if (v != menu.getRadius()) {
                ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 2, v));
            }
        }
        radiusInputMode = false;
        radiusInputText = "";
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (orderMode && mePanel.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (radiusInputMode) {
            if (keyCode == 257 || keyCode == 335) {
                commitRadiusInput();
                return true;
            }
            if (keyCode == 256) {
                radiusInputMode = false;
                radiusInputText = "";
                return true;
            }
            if (keyCode == 259 && !radiusInputText.isEmpty()) {
                radiusInputText = radiusInputText.substring(0, radiusInputText.length() - 1);
                return true;
            }
            return false;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (orderMode && mePanel.charTyped(codePoint, modifiers)) return true;
        if (radiusInputMode) {
            if (Character.isDigit(codePoint) && radiusInputText.length() < 10) {
                radiusInputText += codePoint;
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (orderMode) return mePanel.mouseScrolled(delta);
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public NetworkOrderPanel networkOrderPanel() {
        return orderMode ? mePanel : null;
    }

    /** 本机一侧：配方 / 可做份数按机器输入槽里的材料算，下单走通用订单包。 */
    private void wireLocalOrderSource() {
        mePanel.setLocalSource(new NetworkOrderPanel.LocalSource() {
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
        });
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
        if (radiusInputMode) {
            boolean inRadiusBox = mouseX >= leftPos + RADIUS_TEXT_X && mouseX < leftPos + PLUS_X - 2
                    && mouseY >= topPos + NutRoasterMenu.ATTACK_ROW_Y && mouseY < topPos + NutRoasterMenu.ATTACK_ROW_Y + ATTACK_BTN_H;
            if (!inRadiusBox) commitRadiusInput();
        }
        // 侧栏 4 个 tab 的点击已交给 MekCkTabElement#onClick —— 它们是 renderable widget，
        // 由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        if (button == 0) {
            int x = leftPos;
            int y = topPos;

            if (orderMode) {
                int panelX = x + ORDER_PANEL_LEFT;
                int panelY = y + ORDER_PANEL_TOP;
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

            int attackY = y + NutRoasterMenu.ATTACK_ROW_Y;
            int tX = x + TARGET_X;
            if (mouseX >= tX && mouseX < tX + TARGET_W && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H) {
                cycleTarget();
                return true;
            }
            int mX = x + MINUS_X;
            if (mouseX >= mX && mouseX < mX + SMALL_BTN && mouseY >= attackY && mouseY < attackY + SMALL_BTN) {
                sendRadius(-1);
                return true;
            }
            int pX = x + PLUS_X;
            if (mouseX >= pX && mouseX < pX + SMALL_BTN && mouseY >= attackY && mouseY < attackY + SMALL_BTN) {
                sendRadius(1);
                return true;
            }
            int rX = x + RADIUS_TEXT_X;
            if (mouseX >= rX && mouseX < x + PLUS_X - 2 && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H) {
                if (!radiusInputMode) {
                    radiusInputMode = true;
                    radiusInputText = String.valueOf(menu.getRadius());
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
