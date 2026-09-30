package cn.ism.mekck.client;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.blockentity.ChocolateCannonBlockEntity;
import cn.ism.mekck.menu.ChocolateCannonMenu;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.network.IceAttackConfigPacket;
import cn.ism.mekck.network.ModMessages;
import net.minecraft.world.item.crafting.Recipe;
import cn.ism.mekck.network.NetworkOrderPacket;
import cn.ism.mekck.network.RedstoneControlPacket;
import java.util.List;
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
import mekanism.client.render.MekanismRenderer;
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
 * 巧克力大炮屏幕（Mekanism 风格）：
 * 输入 / extra / 产物槽 + 进度箭头 + 2 个流体条 + 能量条 + 费列罗升级槽 + 攻击控制行，
 * 左侧配置 tab、右上升级 tab、右下红石控制 tab，与其他基础机器保持一致。
 */
public final class ChocolateCannonScreen extends GuiMekanism<ChocolateCannonMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    /** 「ME 下单」面板开关（本屏没有本机下单列表 ⇒ 面板恒为 ME 模式）。 */
    private boolean orderMode = false;
    private static final int ORDER_TAB_Y = 34; // 侧配 tab 下方 28px（与其它屏一致）
    private static final int ORDER_PANEL_LEFT = 10;
    private static final int ORDER_PANEL_TOP = 10;
    /** 下单 tab 图标：自绘「清单 + 向下箭头」（原来借用的 Mekanism sorting.png 像音符、且语义不符）。 */
    private static final ResourceLocation ORDER_TEXTURE = MachineTabIcons.ORDER;
    private final NetworkOrderPanel mePanel = new NetworkOrderPanel(true, true);
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

    // Mekanism textures（tab 的 holder/button 三层 blit 已由 MekCkTabElement 接管，此处只留 tab 图标）
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");
    /** 攻击控制行仍直接画 button.png（非 tab），故保留。 */
    private static final ResourceLocation BUTTON_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "button.png");

    // 攻击控制行布局
    private static final int TARGET_X = ChocolateCannonMenu.INPUT_X;
    private static final int TARGET_W = 70;
    private static final int ATTACK_BTN_H = 16;
    private static final int MINUS_X = TARGET_X + TARGET_W + 6;
    private static final int PLUS_X = 178;
    private static final int SMALL_BTN = 16;
    private static final int RADIUS_TEXT_X = MINUS_X + SMALL_BTN + 4;

    /** 索敌半径输入态：点击半径数值进入，可直接键入数字（回车提交/ESC 取消）。 */
    private boolean radiusInputMode;
    private String radiusInputText = "";

    public ChocolateCannonScreen(ChocolateCannonMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = ChocolateCannonMenu.IMAGE_WIDTH;
        imageHeight = ChocolateCannonMenu.IMAGE_HEIGHT;
        inventoryLabelY = ChocolateCannonMenu.INV_TOP - 12;
        dynamicSlots = true;
        wireLocalOrderSource();
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // 输入槽
        GuiVirtualSlot inputVs = new GuiVirtualSlot(SlotType.INPUT, this, ChocolateCannonMenu.INPUT_X - 1, ChocolateCannonMenu.INPUT_Y - 1);
        if (menu.slots.get(ChocolateCannonBlockEntity.INPUT_SLOT) instanceof IVirtualSlot ivs) {
            inputVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(inputVs);

        // extra 输入槽（参考 Mekanism 融合机的 extra 槽）
        GuiVirtualSlot extraVs = new GuiVirtualSlot(SlotType.EXTRA, this, ChocolateCannonMenu.EXTRA_X - 1, ChocolateCannonMenu.EXTRA_Y - 1);
        if (menu.slots.get(ChocolateCannonBlockEntity.EXTRA_SLOT) instanceof IVirtualSlot ivs) {
            extraVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(extraVs);

        // 产物槽（同时是攻击弹药库）
        GuiVirtualSlot outputVs = new GuiVirtualSlot(SlotType.OUTPUT, this, ChocolateCannonMenu.OUTPUT_X, ChocolateCannonMenu.OUTPUT_Y - 1);
        if (menu.slots.get(ChocolateCannonBlockEntity.OUTPUT_SLOT) instanceof IVirtualSlot ivs) {
            outputVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(outputVs);

        // 进度条（Mekanism SMALL_RIGHT 箭头，位于输入与产物之间）
        int progressX = ChocolateCannonMenu.INPUT_X + 18 + (ChocolateCannonMenu.OUTPUT_X - ChocolateCannonMenu.INPUT_X - 18 - 28) / 2;
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgress() / 24.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgress() > 0;
            }
        }, ProgressType.SMALL_RIGHT, this, progressX, ChocolateCannonMenu.INPUT_Y + 5));

        // 流体条 ×2（左侧：流体输入 1 / 流体输入 2）
        addRenderableWidget(new GuiCkFluidGauge(this, 6, 34,
                () -> menu.getFluid1Stack(), () -> menu.getFluid1Capacity()));
        addRenderableWidget(new GuiCkFluidGauge(this, 24, 34,
                () -> menu.getFluid2Stack(), () -> menu.getFluid2Capacity()));

        // 能量条（右侧）
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                        menu.getEnergy(), ChocolateCannonBlockEntity.ENERGY_CAPACITY);
            }

            @Override
            public double getLevel() {
                return (double) menu.getEnergy() / ChocolateCannonBlockEntity.ENERGY_CAPACITY;
            }
        }, imageWidth - 12, 22));

        // 能量信息标签（左下角）
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
                Component.translatable("gui.mekck.energy_stored",
                        menu.getEnergy(), ChocolateCannonBlockEntity.ENERGY_CAPACITY),
                Component.translatable("gui.mekck.energy_per_tick",
                        ChocolateCannonBlockEntity.ENERGY_PER_TICK)
        )));

        // 能源槽（能量物品）
        GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 7, 12);
        powerVs.with(SlotOverlay.POWER);
        if (menu.slots.get(ChocolateCannonBlockEntity.SLOT_POWER) instanceof IVirtualSlot ivs) {
            powerVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(powerVs);

        // 费列罗升级槽 ×5（主界面，输入槽下方一排）
        for (int i = 0; i < 5; i++) {
            GuiVirtualSlot ferreroVs = new GuiVirtualSlot(SlotType.NORMAL, this,
                    ChocolateCannonMenu.INPUT_X + i * 18, ChocolateCannonMenu.FERRERO_ROW_Y);
            if (menu.slots.get(ChocolateCannonBlockEntity.FERRERO_SLOT_BASE + i) instanceof IVirtualSlot ivs) {
                ferreroVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(ferreroVs);
        }

        // 创造升级槽（费列罗槽右侧）
        GuiVirtualSlot creativeVs = new GuiVirtualSlot(SlotType.NORMAL, this,
                ChocolateCannonMenu.INPUT_X + 5 * 18 + 8, ChocolateCannonMenu.FERRERO_ROW_Y);
        if (menu.slots.get(ChocolateCannonBlockEntity.SLOT_CREATIVE_UPGRADE) instanceof IVirtualSlot ivs) {
            creativeVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(creativeVs);

        // 流体容器槽 ×2（流体条下方，注入流体用）
        GuiVirtualSlot fluid1Vs = new GuiVirtualSlot(SlotType.INPUT, this, 6, ChocolateCannonMenu.FERRERO_ROW_Y);
        if (menu.slots.get(ChocolateCannonBlockEntity.FLUID_SLOT_1) instanceof IVirtualSlot ivs) {
            fluid1Vs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(fluid1Vs);
        GuiVirtualSlot fluid2Vs = new GuiVirtualSlot(SlotType.INPUT, this, 24, ChocolateCannonMenu.FERRERO_ROW_Y);
        if (menu.slots.get(ChocolateCannonBlockEntity.FLUID_SLOT_2) instanceof IVirtualSlot ivs) {
            fluid2Vs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(fluid2Vs);

        // 侧栏 tab **最后注册**：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、命中即返回，
        // 即越晚注册命中优先；排在虚拟槽之后才能保住旧的命中优先级。
        addTabElements();

        // 侧栏 tab 必须最后注册：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，越晚注册命中优先。
        if (cn.ism.mekck.client.NetworkPullButton.isVisible()) {
            for (var tab : cn.ism.mekck.client.NetworkPullButton.register(this, menu.getBlockPos())) {
                addRenderableWidget(tab);
            }
        }
    }

    /**
     * 侧栏 4 个 tab 统一走 Mek {@link MekCkTabElement}（继承 {@code GuiInsetElement}）：
     * 三层绘制与旧手绘逐参数一致，tooltip 改走 {@code GuiMekanism#renderLabels} 的元素通道
     * —— 那是渲染管线最后一层，结构上不会再被槽位盖住。
     * 旧实现在 {@code renderBg()} 里直绘 tooltip + 在 {@code mouseClicked} 里手算命中矩形，现已一并移除。
     * 坐标常量（{@code TAB_X} / {@code CONFIG_TAB_Y} / {@code ORDER_TAB_Y} / {@code UPGRADE_TAB_Y} /
     * {@code imageHeight - REDSTONE_TAB_SIZE}）逐像素沿用旧值，未作任何改动。
     */
    private void addTabElements() {
        // ── 左列（2 个）──
        addRenderableWidget(new MekCkTabElement(this, CONFIG_TEXTURE, TAB_X, CONFIG_TAB_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> false,
                mekanism.client.SpecialColors.TAB_CONFIGURATION,
                () -> List.of(Component.translatable("tooltip.mekck.side_config")),
                this::openSideConfigWindow, null));

        // ME 下单在 Mek 里无对应图标：保留本模组自绘的「清单 + 向下箭头」图标，只取官方染色。
        addRenderableWidget(new MekCkTabElement(this, ORDER_TEXTURE, TAB_X, ORDER_TAB_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> orderMode,
                mekanism.client.SpecialColors.TAB_CONTAINER_EDIT_MODE,
                () -> List.of(Component.translatable("tooltip.mekck.order_panel")),
                this::toggleOrderMode, null));

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
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        int x = leftPos;
        int y = topPos;

        // AE2 网络拉料按钮已迁到 addGuiElements() 末尾的 NetworkPullButton.register(...)（Mek 原生 tab）。
        // 侧栏 4 个 tab（侧配 / ME 下单 / 升级 / 红石）已全部迁到 addTabElements()（MekCkTabElement），
        // renderBg 不再手绘。攻击控制行仍是 renderBg 阶段的按钮，保留原样。
        renderAttackControls(guiGraphics, mouseX, mouseY, x, y);
    }

    /** 攻击控制行：目标类型按钮 + 半径 -/+ 按钮（Mekanism button.png 风格）。 */
    private void renderAttackControls(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        int attackY = y + ChocolateCannonMenu.ATTACK_ROW_Y;

        // 目标类型按钮
        int t = menu.getTargetType();
        String targetName = t == 0 ? "敌对" : t == 1 ? "全部" : "动物";
        int color = t == 0 ? 0xFFE33B32 : t == 1 ? 0xFF4488FF : 0xFF66CC66;
        int tX = x + TARGET_X;
        boolean tHovered = mouseX >= tX && mouseX < tX + TARGET_W && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H;
        guiGraphics.blit(BUTTON_TEXTURE, tX, attackY, 0, tHovered ? 20 : 0, TARGET_W, ATTACK_BTN_H, 200, 60);
        guiGraphics.fill(tX + 1, attackY + 1, tX + TARGET_W - 1, attackY + ATTACK_BTN_H - 1, color);
        String label = "目标:" + targetName;
        guiGraphics.drawString(font, label, tX + (TARGET_W - font.width(label)) / 2, attackY + 4, 0xFFFFFFFF);

        // 半径 - 按钮
        int mX = x + MINUS_X;
        boolean mHovered = mouseX >= mX && mouseX < mX + SMALL_BTN && mouseY >= attackY && mouseY < attackY + SMALL_BTN;
        guiGraphics.blit(BUTTON_TEXTURE, mX, attackY, 0, mHovered ? 20 : 0, SMALL_BTN, SMALL_BTN, 200, 60);
        guiGraphics.drawString(font, "-", mX + 6, attackY + 4, 0xFFFFFFFF);

        // 半径数值：点击可直接键入；输入态绘制白底编辑框，非输入态显示文本
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

        // 半径 + 按钮
        int pX = x + PLUS_X;
        boolean pHovered = mouseX >= pX && mouseX < pX + SMALL_BTN && mouseY >= attackY && mouseY < attackY + SMALL_BTN;
        guiGraphics.blit(BUTTON_TEXTURE, pX, attackY, 0, pHovered ? 20 : 0, SMALL_BTN, SMALL_BTN, 200, 60);
        guiGraphics.drawString(font, "+", pX + 5, attackY + 4, 0xFFFFFFFF);
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

        // ME 下单面板最后画（在 GUI 文字 / 槽位之上；本屏没有本机下单列表 ⇒ 只有 ME 一侧）
        if (orderMode) {
            mePanel.bind(menu.getBlockPos());
            mePanel.render(guiGraphics, font, leftPos + ORDER_PANEL_LEFT, topPos + ORDER_PANEL_TOP,
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
            if (keyCode == 257 || keyCode == 335) { // Enter：提交
                commitRadiusInput();
                return true;
            }
            if (keyCode == 256) { // ESC：取消输入
                radiusInputMode = false;
                radiusInputText = "";
                return true;
            }
            if (keyCode == 259 && !radiusInputText.isEmpty()) { // Backspace
                radiusInputText = radiusInputText.substring(0, radiusInputText.length() - 1);
                return true;
            }
            return false; // 其余按键交给 charTyped 处理
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
        // 半径输入态：点击数值区外任意位置 → 提交并退出输入态（后续点击照常处理）
        if (radiusInputMode) {
            boolean inRadiusBox = mouseX >= leftPos + RADIUS_TEXT_X && mouseX < leftPos + PLUS_X - 2
                    && mouseY >= topPos + ChocolateCannonMenu.ATTACK_ROW_Y && mouseY < topPos + ChocolateCannonMenu.ATTACK_ROW_Y + ATTACK_BTN_H;
            if (!inRadiusBox) commitRadiusInput();
        }
        // 侧栏 4 个 tab 的点击已交给 MekCkTabElement#onClick —— 它们是 renderable widget，
        // 由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        if (button == 0) {
            int x = leftPos;
            int y = topPos;

            // 攻击控制行
            int attackY = y + ChocolateCannonMenu.ATTACK_ROW_Y;
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
            // 半径数值区：点击进入直接输入模式（预填当前值）
            int rX = x + RADIUS_TEXT_X;
            if (mouseX >= rX && mouseX < x + PLUS_X - 2 && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H) {
                if (!radiusInputMode) {
                    radiusInputMode = true;
                    radiusInputText = String.valueOf(menu.getRadius());
                }
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
}
