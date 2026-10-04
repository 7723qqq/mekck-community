package cn.ism.mekck.client;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.IceFactoryMenu;
import cn.ism.mekck.network.IceAttackConfigPacket;
import cn.ism.mekck.network.ModMessages;
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
 * 制冰工厂屏幕（Mekanism 风格）：
 * 输入/输出方形网格 + 进度箭头 + 水流体条 + 能量条 + 冷萃/创造升级槽 + 攻击控制行，
 * 左侧配置 tab、右上升级 tab、右下红石控制 tab，与其他工厂保持一致。
 */
public final class IceFactoryScreen extends GuiMekanism<IceFactoryMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    private final CuttingMachineFactoryTier tier;
    private final int processes;
    private final int cols;
    private final int rows;

    // Mekanism 风格 tab 布局（与其他工厂一致）
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;
    /** 下单 tab 的 y —— 侧配 tab 下方的空位（与其它屏规则一致）。 */
    private static final int ORDER_TAB_Y = 34;
    /**
     * 「下单」标签页 —— 点开 {@link NetworkOrderWindow}。
     *
     * <p>必须留引用：{@code GuiWindowCreatorTab} 关闭窗口时靠 {@code elementSupplier.get()}
     * 把同一实例重新激活，宿主屏幕也靠它取「正在显示的那一个」面板。</p>
     */
    private NetworkOrderTab orderTab;
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
    /** 攻击控制行的按钮仍走这张 Mekanism button.png（非 tab 部分，勿删）。 */
    private static final ResourceLocation BUTTON_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "button.png");

    // 攻击控制行布局
    private static final int TARGET_X = IceFactoryMenu.INPUT_START_X;
    private static final int TARGET_W = 70;
    private static final int ATTACK_BTN_H = 16;
    private static final int MINUS_X = TARGET_X + TARGET_W + 6;
    private static final int PLUS_X = 176;
    private static final int SMALL_BTN = 16;
    private static final int RADIUS_TEXT_X = MINUS_X + SMALL_BTN + 4;

    /** 索敌半径输入态：点击半径数值进入，可直接键入数字（回车提交/ESC 取消）。 */
    private boolean radiusInputMode;
    private String radiusInputText = "";

    public IceFactoryScreen(IceFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.tier = menu.getTier();
        this.processes = menu.getProcesses();
        this.cols = menu.getGridCols();
        this.rows = menu.getGridRows();
        imageWidth = menu.getImageWidth();
        imageHeight = menu.getInventoryTop() + 83;
        inventoryLabelY = menu.getInventoryTop() - 12;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        int inputStartX = IceFactoryMenu.INPUT_START_X;
        int inputStartY = IceFactoryMenu.INPUT_START_Y;

        // 输入格（方形网格）
        for (int i = 0; i < processes; i++) {
            int col = i % cols;
            int row = i / cols;
            GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.INPUT, this, inputStartX + col * 18 - 1, inputStartY + row * 18 - 1);
            if (menu.slots.get(i) instanceof IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
        }

        // 输出格（与输入相同的方形网格）
        int outputStartX = inputStartX + cols * 18 + IceFactoryMenu.GAP_BETWEEN;
        for (int i = 0; i < processes; i++) {
            int col = i % cols;
            int row = i / cols;
            GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.OUTPUT, this, outputStartX + col * 18, inputStartY - 1 + row * 18);
            int slotIdx = processes + i;
            if (menu.slots.get(slotIdx) instanceof IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
        }

        // 进度条（Mekanism SMALL_RIGHT 箭头，位于两网格之间）
        int progressX = inputStartX + cols * 18 + (IceFactoryMenu.GAP_BETWEEN - 28) / 2;
        int progressY = inputStartY + rows * 18 / 2 - 4;
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

        // 水流体条（左侧，Mekanism 标准流体条）
        addRenderableWidget(new GuiCkFluidGauge(this, 6, inputStartY,
                () -> menu.getWaterStack(), () -> menu.getWaterCapacity()));

        // 能量条（右侧）
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
        }, imageWidth - 12, 22));

        // 能量信息标签（左下角）
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
                Component.translatable("gui.mekck.energy_stored",
                        menu.getEnergy(), menu.getEnergyCapacity()),
                Component.translatable("gui.mekck.energy_per_tick",
                        tier.energyPerTick)
        )));

        // 能源槽（能量物品）
        GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
        powerVs.with(SlotOverlay.POWER);
        if (menu.slots.get(menu.getPowerSlotIndex()) instanceof IVirtualSlot ivs) {
            powerVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(powerVs);

        // 冷萃升级槽（主界面，网格下方一排，①~⑤ 共 5 格）
        int cbY = menu.getCbSlotY();
        for (int i = 0; i < 5; i++) {
            GuiVirtualSlot cbVs = new GuiVirtualSlot(SlotType.NORMAL, this, inputStartX + i * 18, cbY);
            if (menu.slots.get(menu.getCbSlotIndex(i)) instanceof IVirtualSlot ivs) {
                cbVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(cbVs);
        }
        // 创造升级槽（冷萃槽右侧）
        if (menu.hasCreative()) {
            GuiVirtualSlot creativeVs = new GuiVirtualSlot(SlotType.NORMAL, this, inputStartX + 5 * 18 + 8, cbY);
            if (menu.slots.get(menu.getCreativeSlotIndex()) instanceof IVirtualSlot ivs) {
                creativeVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(creativeVs);
        }

        // 侧栏 tab **最后注册**：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，即越晚注册命中优先。tab 全部在面板之外（x=-26~-2 / x=imageWidth~+24），
        // 与攻击控制行、虚拟槽都不重叠，故不会抢掉它们的点击。
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
        // 本屏没有本机下单列表 ⇒ localSource 传 null，面板恒为 ME 模式。
        orderTab = addRenderableWidget(new NetworkOrderTab(this, menu.getBlockPos(),
                TAB_X, ORDER_TAB_Y, true, null, () -> orderTab));

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
    }

    /** 攻击控制行：目标类型按钮 + 半径 -/+ 按钮（Mekanism button.png 风格）。 */
    private void renderAttackControls(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        int attackY = y + menu.getAttackRowY();

        // 目标类型按钮
        int t = menu.getTargetType();
        String targetName = Component.translatable("gui.mekck.ui.target_type."
                + (t == 0 ? "hostile" : t == 1 ? "all" : "animal")).getString();
        int color = t == 0 ? 0xFFE33B32 : t == 1 ? 0xFF4488FF : 0xFF66CC66;
        int tX = x + TARGET_X;
        boolean tHovered = mouseX >= tX && mouseX < tX + TARGET_W && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H;
        guiGraphics.blit(BUTTON_TEXTURE, tX, attackY, 0, tHovered ? 20 : 0, TARGET_W, ATTACK_BTN_H, 200, 60);
        guiGraphics.fill(tX + 1, attackY + 1, tX + TARGET_W - 1, attackY + ATTACK_BTN_H - 1, color);
        String label = Component.translatable("gui.mekck.ui.target", targetName).getString();
        guiGraphics.drawString(font, label, tX + (TARGET_W - font.width(label)) / 2, attackY + 4, 0xFFFFFFFF);

        // 半径 - 按钮
        int mX = x + MINUS_X;
        boolean mHovered = mouseX >= mX && mouseX < mX + SMALL_BTN && mouseY >= attackY && mouseY < attackY + SMALL_BTN;
        guiGraphics.blit(BUTTON_TEXTURE, mX, attackY, 0, mHovered ? 20 : 0, SMALL_BTN, SMALL_BTN, 200, 60);
        guiGraphics.drawString(font, "-", mX + 6, attackY + 4, 0xFFFFFFFF);

        // 半径数值：点击可直接键入；输入态绘制白底编辑框，非输入态显示文本（与其他屏幕的数量输入风格一致）
        if (radiusInputMode) {
            int boxX = x + RADIUS_TEXT_X;
            int boxW = PLUS_X - RADIUS_TEXT_X - 2;
            guiGraphics.fill(boxX, attackY, boxX + boxW, attackY + ATTACK_BTN_H, 0xFF000000);
            guiGraphics.fill(boxX + 1, attackY + 1, boxX + boxW - 1, attackY + ATTACK_BTN_H - 1, 0xFFFFFFFF);
            String displayText = radiusInputText.isEmpty() ? "≥4" : radiusInputText;
            int textColor = radiusInputText.isEmpty() ? 0xFF888888 : 0xFF000000;
            guiGraphics.drawString(font, displayText, boxX + 2, attackY + 4, textColor);
        } else {
            String radiusText = Component.translatable("gui.mekck.ui.radius").getString() + menu.getRadius();
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
        // 大堆叠数量压制为 1 渲染，随后以缩放字体绘制格式化数量（与其他工厂一致）
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
        if (radiusInputMode) {
            if (Character.isDigit(codePoint) && radiusInputText.length() < 10) {
                radiusInputText += codePoint;
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public NetworkOrderPanel networkOrderPanel() {
        // 窗口开着 ⇒ 返回窗口里的面板；关着 ⇒ null（回包丢弃，不再灌进已销毁的面板）。
        return orderTab == null ? null : orderTab.panel();
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
            int attackRowY = topPos + menu.getAttackRowY();
            boolean inRadiusBox = mouseX >= leftPos + RADIUS_TEXT_X && mouseX < leftPos + PLUS_X - 2
                    && mouseY >= attackRowY && mouseY < attackRowY + ATTACK_BTN_H;
            if (!inRadiusBox) commitRadiusInput();
        }
        if (button == 0) {
            int x = leftPos;
            int y = topPos;

            // 攻击控制行
            int attackY = y + menu.getAttackRowY();
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
        // 侧栏 4 个 tab（侧配 / 下单 / 升级 / 红石）的点击交给 MekCkTabElement#onClick —— 它们是
        // renderable widget，由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        // 「下单」tab 开的是 Mek 窗口，窗口内的点击由 GuiMekanism#mouseClicked 先遍历 windows 派发
        // （窗口在 children() 之前），所以旧版那份「面板开着时先定向派发 tab」的补丁已随面板一起删除。
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
