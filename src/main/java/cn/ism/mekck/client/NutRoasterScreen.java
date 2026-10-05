package cn.ism.mekck.client;

import cn.ism.mekck.machine.roasting.NutRoasterTile;
import cn.ism.mekck.menu.NutRoasterMenu;
import cn.ism.mekck.network.IceAttackConfigPacket;
import cn.ism.mekck.network.ModMessages;
import mekanism.client.gui.element.GuiUpArrow;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/**
 * 坚果爆炒机屏幕（Mek 体系版）。
 *
 * <h3>画风口径：与 Mek 基础电力机器逐项对齐</h3>
 * 面板 176×166 是 Mek 的默认值，但本机比基础机器多一行索敌控件
 * （目标类型 / 半径 −/+，Mek 无对应物），因此整体高度加一行 24px
 * （{@link NutRoasterMenu#IMAGE_HEIGHT}），玩家背包与标签一起下移。
 * 其余逐条对齐 {@code GuiElectricMachine}：上箭头 {@code (68,38)}、进度条
 * {@code ProgressType.BAR (86,38)}、能源条 {@code (imageWidth-12, 16)}、
 * 能源 tab 传 {@code tile::getActive}。
 *
 * <h3>从 {@code GuiMekanism} 换成 {@link MekCkContainerScreenBase} 后删掉的三块</h3>
 * <ul>
 *   <li><b>自摆的侧配 / 升级 / 红石 tab</b>（{@code MekCkTabElement} + 手绘
 *       {@code GuiMekCkSideConfiguration} / {@code GuiUpgradeWindow}）：由
 *       {@code GuiConfigurableTile} 与 Mek 的组件自动提供，留着就是两套 tab 叠在一起；</li>
 *   <li><b>手绘槽位</b>（{@code GuiVirtualSlot} + {@code IVirtualSlot}）：
 *       {@code dynamicSlots} 让 {@code addSlots()} 按容器槽的 {@code ContainerSlotType}
 *       自动建 widget，坐标取自 tile 建槽时写进 {@code MekCkSlots.NutRoaster} 的那一份；</li>
 *   <li><b>13 条 {@code ContainerData} 下标</b>：读数改问菜单（菜单再问 tile 的同步通道）。</li>
 * </ul>
 *
 * <h3>保留的只有三样</h3>
 * 索敌控件行（自定义功能）、温度读数（{@code drawForegroundText} 右端）、
 * ME 下单 tab（Mek 没有 AE2 集成），外加 {@link BigStackHud} 大堆叠数量显示。
 */
public final class NutRoasterScreen
        extends MekCkContainerScreenBase<NutRoasterTile, NutRoasterMenu>
        implements NetworkOrderHost {

    private final BigStackHud bigStackHud = new BigStackHud();

    /** 「下单」tab 的 y —— 放在右列（左列 6/34/62/90/137 已被 Mek 的 tab 占满）。 */
    private static final int ORDER_TAB_Y = 34;

    /** Mek 的通用按钮贴图（索敌控件行沿用迁移前的画法）。 */
    private static final ResourceLocation BUTTON_TEXTURE =
            MekanismUtils.getResource(ResourceType.GUI, "button.png");

    // ── 索敌控件行几何（面板内坐标）──────────────────────────────────────
    private static final int TARGET_X = 8;
    private static final int TARGET_W = 70;
    private static final int ATTACK_BTN_H = NutRoasterMenu.ATTACK_ROW_HEIGHT;
    private static final int MINUS_X = TARGET_X + TARGET_W + 6;
    private static final int PLUS_X = 140;
    private static final int SMALL_BTN = 16;
    private static final int RADIUS_TEXT_X = MINUS_X + SMALL_BTN + 4;

    /** 索敌半径输入态：点击半径数值进入，可直接键入数字（回车提交/ESC 取消）。 */
    private boolean radiusInputMode;
    private String radiusInputText = "";

    /**
     * 「下单」标签页 —— 点开 {@link NetworkOrderWindow}。
     *
     * <p>必须留引用：{@code GuiWindowCreatorTab} 关闭窗口时靠 {@code elementSupplier.get()}
     * 把同一实例重新激活，宿主屏幕也靠它取「正在显示的那一个」面板。</p>
     */
    private NetworkOrderTab orderTab;

    public NutRoasterScreen(NutRoasterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = NutRoasterMenu.IMAGE_WIDTH;
        imageHeight = NutRoasterMenu.IMAGE_HEIGHT;
        // 标签留在玩家背包首行之上 10px（与 MekCkFactoryLayout 的口径一致）。
        inventoryLabelY = NutRoasterMenu.INV_TOP - 10;
        // dynamicSlots 由基类设；尺寸与标签位置之外刻意不写别的（写了就与上游漂移）。
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        // 上箭头 —— 上游 GuiElectricMachine(68,38)。
        addRenderableWidget(new GuiUpArrow(this, 68, 38));

        // 能源条 —— 上游 GuiElectricMachine 的 (imageWidth-12, 16)，直接吃 tile 的能量容器，
        // 存量/上限与 tooltip 都由 Mek 自己组装。
        addEnergyBar(tile.getEnergyContainer(), null);

        // 能量信息 tab —— 上游传的是 tile::getActive（是否正在工作），不是 getLastUsage。
        addRenderableWidget(new GuiEnergyTab(this, tile.getEnergyContainer(), tile::getActive));

        // 进度条 —— 上游 GuiElectricMachine 用 ProgressType.BAR 且位于 (86,38)。
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgressPercent() / 100.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgressPercent() > 0;
            }
        }, ProgressType.BAR, this, 86, 38));

        // ME 下单：Mek 无对应物，保留自绘 tab + 虚拟窗口（右列 y=34）。
        orderTab = addRenderableWidget(new NetworkOrderTab(this, menu.getBlockPos(),
                imageWidth, ORDER_TAB_Y, false, localOrderSource(), () -> orderTab));

        // 自动补料 / 网络拉料两枚 tab **最后注册**：Mek 的 mouseClicked 对 children()
        // 倒序遍历、命中即返回，越晚注册命中越优先（tab 才能压过同区域的控件）。
        addNetworkPullTabs(menu.getBlockPos());
    }

    // ================== 索敌控件行 ==================

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        renderAttackControls(guiGraphics, mouseX, mouseY, leftPos, topPos);
    }

    /** 攻击控制行：目标类型按钮 + 半径 -/+ 按钮（Mekanism button.png 风格）。 */
    private void renderAttackControls(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        int attackY = y + NutRoasterMenu.ATTACK_ROW_Y;

        int t = menu.getTargetType();
        String targetName = Component.translatable("gui.mekck.ui.target_type."
                + (t == 0 ? "hostile" : t == 1 ? "all" : "animal")).getString();
        int color = t == 0 ? 0xFFE33B32 : t == 1 ? 0xFF4488FF : 0xFF66CC66;
        int tX = x + TARGET_X;
        boolean tHovered = mouseX >= tX && mouseX < tX + TARGET_W
                && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H;
        guiGraphics.blit(BUTTON_TEXTURE, tX, attackY, 0, tHovered ? 20 : 0, TARGET_W, ATTACK_BTN_H, 200, 60);
        guiGraphics.fill(tX + 1, attackY + 1, tX + TARGET_W - 1, attackY + ATTACK_BTN_H - 1, color);
        String label = Component.translatable("gui.mekck.ui.target", targetName).getString();
        guiGraphics.drawString(font, label, tX + (TARGET_W - font.width(label)) / 2, attackY + 4, 0xFFFFFFFF);

        int mX = x + MINUS_X;
        boolean mHovered = mouseX >= mX && mouseX < mX + SMALL_BTN
                && mouseY >= attackY && mouseY < attackY + SMALL_BTN;
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
            String radiusText = Component.translatable("gui.mekck.ui.radius").getString() + menu.getRadius();
            guiGraphics.drawString(font, radiusText, x + RADIUS_TEXT_X, attackY + 4, 0xFFFFFFFF);
        }

        int pX = x + PLUS_X;
        boolean pHovered = mouseX >= pX && mouseX < pX + SMALL_BTN
                && mouseY >= attackY && mouseY < attackY + SMALL_BTN;
        guiGraphics.blit(BUTTON_TEXTURE, pX, attackY, 0, pHovered ? 20 : 0, SMALL_BTN, SMALL_BTN, 200, 60);
        guiGraphics.drawString(font, "+", pX + 5, attackY + 4, 0xFFFFFFFF);
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 机器名 + 「Inventory」标签由基类画（Mek 的 renderLabels 不调 super）。
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
        // 温度系统：显示机身温度（摄氏度），摆在「Inventory」同一行的右端。
        String temperature = Component.translatable("gui.mekck.ui.temperature",
                menu.getTemperature() / 100.0).getString();
        guiGraphics.drawString(font, temperature,
                imageWidth - 8 - font.width(temperature), inventoryLabelY, 0xFFFF5555);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        bigStackHud.shrink(menu.slots);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        bigStackHud.restore();

        // 大堆叠数量：原版只画到 64，这里把真实数量（可到 21 亿）画在槽位右下角。
        for (Slot slot : menu.slots) {
            if (slot.isActive() && slot.hasItem()) {
                String formatted = CountFormat.compact(slot.getItem().getCount());
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
    }

    // ================== 索敌控件的输入 ==================

    private void cycleTarget() {
        int t = (menu.getTargetType() + 1) % 3;
        ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 0, t));
    }

    private void sendRadius(int delta) {
        ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 1, delta));
    }

    /** 提交半径输入内容：解析数字并钳制到 ≥4 后以绝对值发送，随后退出输入态。 */
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
        if (radiusInputMode) {
            if (Character.isDigit(codePoint) && radiusInputText.length() < 10) {
                radiusInputText += codePoint;
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (radiusInputMode) {
            boolean inRadiusBox = mouseX >= leftPos + RADIUS_TEXT_X && mouseX < leftPos + PLUS_X - 2
                    && mouseY >= topPos + NutRoasterMenu.ATTACK_ROW_Y
                    && mouseY < topPos + NutRoasterMenu.ATTACK_ROW_Y + ATTACK_BTN_H;
            if (!inRadiusBox) {
                commitRadiusInput();
            }
        }
        if (button == 0) {
            int x = leftPos;
            int y = topPos;
            int attackY = y + NutRoasterMenu.ATTACK_ROW_Y;

            int tX = x + TARGET_X;
            if (mouseX >= tX && mouseX < tX + TARGET_W
                    && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H) {
                cycleTarget();
                return true;
            }
            int mX = x + MINUS_X;
            if (mouseX >= mX && mouseX < mX + SMALL_BTN
                    && mouseY >= attackY && mouseY < attackY + SMALL_BTN) {
                sendRadius(-1);
                return true;
            }
            int pX = x + PLUS_X;
            if (mouseX >= pX && mouseX < pX + SMALL_BTN
                    && mouseY >= attackY && mouseY < attackY + SMALL_BTN) {
                sendRadius(1);
                return true;
            }
            int rX = x + RADIUS_TEXT_X;
            if (mouseX >= rX && mouseX < x + PLUS_X - 2
                    && mouseY >= attackY && mouseY < attackY + ATTACK_BTN_H) {
                if (!radiusInputMode) {
                    radiusInputMode = true;
                    radiusInputText = String.valueOf(menu.getRadius());
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ================== ME 下单 ==================

    @Override
    public NetworkOrderPanel networkOrderPanel() {
        // 窗口开着 ⇒ 返回窗口里的面板；关着 ⇒ null（回包丢弃，不再灌进已销毁的面板）。
        return orderTab == null ? null : orderTab.panel();
    }

    /**
     * 本机一侧：配方 / 可做份数按机器输入槽里的材料算，下单走通用订单包。
     *
     * <p>面板随窗口每次打开重建，数据源必须跟着重建，否则新面板的本机模式是空的。</p>
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
}
