package cn.ism.mekck.client;

import cn.ism.mekck.item.ColdBrewTier;
import cn.ism.mekck.item.ColdBrewUpgradeItem;
import cn.ism.mekck.machine.icemaker.IceMakerTile;
import cn.ism.mekck.menu.IceMakerMenu;
import cn.ism.mekck.network.IceAttackConfigPacket;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.UpgradeUninstallPacket;
import mekanism.client.gui.element.GuiUpArrow;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.common.inventory.container.slot.InventoryContainerSlot;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import mekanism.common.util.text.InputValidator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 急冻制冰机屏幕（Mek 体系版）。
 *
 * <h3>画风口径：与 Mek 基础电力机器逐项对齐</h3>
 * 面板 176 是 Mek 的默认宽度，但本机比基础机器多两行自定义控件
 * （索敌目标 / 半径，控温开关 / 目标温度，Mek 无对应物），因此整体高度加两行 48px
 * （{@link IceMakerMenu#IMAGE_HEIGHT}），玩家背包与标签一起下移。
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
 *       自动建 widget，坐标取自 tile 建槽时写进 {@code MekCkSlots.IceMaker} 的那一份；</li>
 *   <li><b>23 条 {@code ContainerData} 下标与 3 个 16 位拆位槽</b>：读数改问菜单
 *       （菜单再问 tile 的同步通道，Mek 自己就把能量 / 流体 / 温度送到了客户端）。</li>
 * </ul>
 *
 * <h3>保留的只有四样</h3>
 * 索敌控件行、控温控件行、机身温度读数（{@code drawForegroundText} 右端）、
 * ME 下单 tab（Mek 没有 AE2 集成），外加 {@link BigStackHud} 大堆叠数量显示。
 *
 * <h3>冷萃槽的「已安装」徽标与点击卸载</h3>
 * 冷萃升级<b>不是 Mek 的 {@code Upgrade}</b>（它由本机的额外槽 + 20 tick 读条承担，
 * 见 {@code IceMakerTile}），所以 Mek 的升级界面碰不到它。原实现的卸载按钮在自研升级窗里，
 * 那个窗随迁移删除 ⇒ 本屏把「点已安装的冷萃槽 ⇒ 卸回槽里」补回来，手法与 Mek 自己的
 * 升级窗（点已安装的升级即卸下）同款。
 */
public final class IceMakerScreen
        extends MekCkContainerScreenBase<IceMakerTile, IceMakerMenu>
        implements NetworkOrderHost {

    private final BigStackHud bigStackHud = new BigStackHud();

    /** 「下单」tab 的 y —— 放在右列（左列 6/34/62/90/137 已被 Mek 的 tab 占满）。 */
    private static final int ORDER_TAB_Y = 34;

    /** Mek 的通用按钮贴图（两行自定义控件沿用迁移前的画法）。 */
    private static final ResourceLocation BUTTON_TEXTURE =
            MekanismUtils.getResource(ResourceType.GUI, "button.png");

    // ── 索敌控件行几何（面板内坐标）──────────────────────────────────────
    private static final int TARGET_X = 8;
    private static final int TARGET_W = 70;
    private static final int CONTROL_H = IceMakerMenu.CONTROL_ROW_HEIGHT;
    private static final int RADIUS_LABEL_X = TARGET_X + TARGET_W + 6;
    private static final int RADIUS_FIELD_X = 110;
    private static final int RADIUS_FIELD_W = IceMakerMenu.IMAGE_WIDTH - 8 - RADIUS_FIELD_X;

    // ── 控温控件行几何 ──────────────────────────────────────────────────
    private static final int TEMP_TOGGLE_W = 46;
    private static final int TEMP_LABEL_X = TARGET_X + TEMP_TOGGLE_W + 4;
    private static final int TEMP_FIELD_X = 92;
    private static final int TEMP_FIELD_W = IceMakerMenu.IMAGE_WIDTH - 8 - TEMP_FIELD_X;

    /** 索敌半径输入框（Mek 的数字输入框，回车提交绝对值）。 */
    private GuiTextField radiusField;
    /** 目标温度输入框（DECIMAL 允许负号，回车提交）。 */
    private GuiTextField tempField;

    /**
     * 「下单」标签页 —— 点开 {@link NetworkOrderWindow}。
     *
     * <p>必须留引用：{@code GuiWindowCreatorTab} 关闭窗口时靠 {@code elementSupplier.get()}
     * 把同一实例重新激活，宿主屏幕也靠它取「正在显示的那一个」面板。</p>
     */
    private NetworkOrderTab orderTab;

    /** 五个冷萃槽在容器里的 {@code Slot} 对象（画徽标 + 命中卸载都要用）。 */
    private final Slot[] coldBrewSlotWidgets = new Slot[IceMakerTile.COLD_BREW_SLOTS];

    public IceMakerScreen(IceMakerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = IceMakerMenu.IMAGE_WIDTH;
        imageHeight = IceMakerMenu.IMAGE_HEIGHT;
        // 标签留在玩家背包首行之上 10px（与 MekCkFactoryLayout 的口径一致）。
        inventoryLabelY = IceMakerMenu.INV_TOP - 10;
        // dynamicSlots 由基类设；尺寸与标签位置之外刻意不写别的（写了就与上游漂移）。
    }

    @Override
    protected void addGuiElements() {
        // 侧配 / 传输配置 / 升级 / 红石 / 安全 + 全部槽位 widget —— 一句 super 全排好。
        super.addGuiElements();

        // 上箭头 —— 上游 GuiElectricMachine(68,38)。
        addRenderableWidget(new GuiUpArrow(this, 68, 38));

        // 能源条 —— 上游 GuiElectricMachine 的 (imageWidth-12, 16)，直接吃 tile 的能量容器。
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

        // 水位流体条：数据直接吃 tile 的水罐 —— Mek 的容器同步通道（SyncableFluidStack）
        // 已经把整个 FluidStack 送到客户端，不再需要 ContainerData 拆高低位。
        addRenderableWidget(new GuiCkFluidGauge(this, 6, 17,
                () -> tile.getWaterTank().getFluid(),
                () -> tile.getWaterTank().getCapacity()));

        // 索敌半径：Mek 的数字输入框（DIGIT，回车提交绝对值）。
        radiusField = new GuiTextField(this, RADIUS_FIELD_X, IceMakerMenu.ATTACK_ROW_Y,
                RADIUS_FIELD_W, CONTROL_H)
                .setInputValidator(InputValidator.DIGIT)
                .configureDigitalBorderInput(this::commitRadiusInput);
        radiusField.setMaxLength(10);
        radiusField.setText(String.valueOf(menu.getRadius()));
        addRenderableWidget(radiusField);

        // 目标温度：Mek 的数字输入框（DECIMAL 允许负号，回车提交），本机只降温、值恒 ≤0。
        tempField = new GuiTextField(this, TEMP_FIELD_X, IceMakerMenu.TEMP_ROW_Y,
                TEMP_FIELD_W, CONTROL_H)
                .setInputValidator(InputValidator.DECIMAL.or(InputValidator.from('-')))
                .configureDigitalBorderInput(this::commitTempInput);
        tempField.setMaxLength(8);
        tempField.setText(String.format("%.2f", menu.getTargetTemperature() / 100.0));
        addRenderableWidget(tempField);

        // 冷萃槽的 Slot 对象：Mek 的 InventoryContainerSlot 的 Slot.index 恒为 0
        // （它的父构造传的是空容器），所以只能按「底层 IInventorySlot 是不是本机那一格」认。
        for (int i = 0; i < coldBrewSlotWidgets.length; i++) {
            var want = tile.getColdBrewSlot(i);
            for (Slot slot : menu.slots) {
                if (slot instanceof InventoryContainerSlot container
                        && container.getInventorySlot() == want) {
                    coldBrewSlotWidgets[i] = slot;
                    break;
                }
            }
        }

        // ME 下单：Mek 无对应物，保留自绘 tab + 虚拟窗口（右列 y=34）。
        orderTab = addRenderableWidget(new NetworkOrderTab(this, menu.getBlockPos(),
                imageWidth, ORDER_TAB_Y, false, localOrderSource(), () -> orderTab));

        // 自动补料 / 网络拉料两枚 tab **最后注册**：Mek 的 mouseClicked 对 children()
        // 倒序遍历、命中即返回，越晚注册命中越优先（tab 才能压过同区域的控件）。
        addNetworkPullTabs(menu.getBlockPos());
    }

    // ================== 两行自定义控件 ==================

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        renderAttackControls(guiGraphics, mouseX, mouseY, leftPos, topPos);
        renderTemperatureControls(guiGraphics, mouseX, mouseY, leftPos, topPos);
        renderInstalledColdBrew(guiGraphics);
    }

    /** 攻击控制行：目标类型按钮 + 半径标签（数值输入交给 Mekanism GuiTextField）。 */
    private void renderAttackControls(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        int attackY = y + IceMakerMenu.ATTACK_ROW_Y;

        int t = menu.getTargetType();
        String targetName = Component.translatable("gui.mekck.ui.target_type."
                + (t == 0 ? "hostile" : t == 1 ? "all" : "animal")).getString();
        int color = t == 0 ? 0xFFE33B32 : t == 1 ? 0xFF4488FF : 0xFF66CC66;
        int tX = x + TARGET_X;
        boolean tHovered = mouseX >= tX && mouseX < tX + TARGET_W
                && mouseY >= attackY && mouseY < attackY + CONTROL_H;
        guiGraphics.blit(BUTTON_TEXTURE, tX, attackY, 0, tHovered ? 20 : 0, TARGET_W, CONTROL_H, 200, 60);
        guiGraphics.fill(tX + 1, attackY + 1, tX + TARGET_W - 1, attackY + CONTROL_H - 1, color);
        String label = Component.translatable("gui.mekck.ui.target", targetName).getString();
        guiGraphics.drawString(font, label, tX + (TARGET_W - font.width(label)) / 2, attackY + 4, 0xFFFFFFFF);

        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.radius").getString(),
                x + RADIUS_LABEL_X, attackY + 4, 0xFFFFFFFF);
    }

    /** 控温控制行：控温开关按钮 + 目标温度标签（数值编辑交给 Mekanism GuiTextField）。 */
    private void renderTemperatureControls(GuiGraphics guiGraphics, int mouseX, int mouseY, int x, int y) {
        int rowY = y + IceMakerMenu.TEMP_ROW_Y;
        boolean enabled = menu.isTemperatureControlEnabled();
        int toggleX = x + TARGET_X;
        boolean toggleHovered = mouseX >= toggleX && mouseX < toggleX + TEMP_TOGGLE_W
                && mouseY >= rowY && mouseY < rowY + CONTROL_H;
        guiGraphics.blit(BUTTON_TEXTURE, toggleX, rowY, 0, toggleHovered ? 20 : 0,
                TEMP_TOGGLE_W, CONTROL_H, 200, 60);
        guiGraphics.fill(toggleX + 1, rowY + 1, toggleX + TEMP_TOGGLE_W - 1, rowY + CONTROL_H - 1,
                enabled ? 0xFF33AA55 : 0xFF777777);
        String toggleLabel = Component.translatable("gui.mekck.ui.temp_control",
                Component.translatable(enabled ? "gui.mekck.ui.on" : "gui.mekck.ui.off")).getString();
        guiGraphics.drawString(font, toggleLabel,
                toggleX + (TEMP_TOGGLE_W - font.width(toggleLabel)) / 2, rowY + 4, 0xFFFFFFFF);
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.target").getString(),
                x + TEMP_LABEL_X, rowY + 4, 0xFFFFFFFF);
    }

    /**
     * 已安装冷萃的徽标 —— 槽空但读条器里装着某一级时，把该级的物品图标画在槽位上。
     *
     * <p>它同时是<b>卸载入口</b>：点一下即把该级卸回槽里（见 {@link #mouseClicked}）。
     * 与 Mek 自己的升级窗「点已安装的升级 → 卸下」同款。</p>
     */
    private void renderInstalledColdBrew(GuiGraphics guiGraphics) {
        for (int i = 0; i < coldBrewSlotWidgets.length; i++) {
            Slot slot = coldBrewSlotWidgets[i];
            if (slot == null || slot.hasItem()) {
                continue;
            }
            ItemStack badge = installedBadge(i);
            if (badge.isEmpty()) {
                continue;
            }
            guiGraphics.renderItem(badge, leftPos + slot.x, topPos + slot.y);
        }
    }

    /** 第 i 格已安装的冷萃对应的物品（未安装返回空栈）。 */
    private ItemStack installedBadge(int index) {
        int code = menu.getInstalledColdBrewCode(index);
        ColdBrewTier[] tiers = ColdBrewTier.values();
        if (code <= 0 || code > tiers.length) {
            return ItemStack.EMPTY;
        }
        var registered = ColdBrewUpgradeItem.REGISTRY.get(tiers[code - 1]);
        return registered == null ? ItemStack.EMPTY : new ItemStack(registered.get());
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 机器名 + 「Inventory」标签由基类画（Mek 的 renderLabels 不调 super）。
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
        // 温度系统：显示机身温度（摄氏度），摆在「Inventory」同一行的右端。
        String temperature = Component.translatable("gui.mekck.ui.temperature",
                menu.getCurrentTemperature() / 100.0).getString();
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

    // ================== 自定义控件的输入 ==================

    /** 点击已安装的冷萃槽 → 卸载；返回该槽（未点中返回 null）。 */
    @Nullable
    private Slot coldBrewSlotAt(double mouseX, double mouseY) {
        for (int i = 0; i < coldBrewSlotWidgets.length; i++) {
            Slot slot = coldBrewSlotWidgets[i];
            if (slot == null || slot.hasItem()) {
                continue;
            }
            if (installedBadge(i).isEmpty()) {
                continue; // 未安装：槽只是个空框，不抢点击
            }
            if (isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                return slot;
            }
        }
        return null;
    }

    private void cycleTarget() {
        int t = (menu.getTargetType() + 1) % 3;
        ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 0, t));
    }

    /** 提交半径输入：钳制到 ≥4 后以绝对值发送（上限由服务端的唯一钳制闸门兜底）。 */
    private void commitRadiusInput() {
        String text = radiusField.getText();
        if (text.isEmpty()) {
            return;
        }
        try {
            int v = (int) Math.max(4L, Math.min((long) Integer.MAX_VALUE, Long.parseLong(text)));
            if (v != menu.getRadius()) {
                ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 2, v));
            }
        } catch (NumberFormatException ignored) {
            // 输入框的 DIGIT 校验器已经挡住非数字；真出现解析不了的内容就当作没提交。
        }
    }

    /** 提交目标温度：本机只降温，取 -|输入| 钳制到 [-273.15, 0]，×100 四舍五入后以 type3 发送。 */
    private void commitTempInput() {
        String text = tempField.getText();
        if (text.isEmpty()) {
            return;
        }
        try {
            double celsius = -Math.abs(Double.parseDouble(text));
            celsius = Math.max(-273.15, Math.min(0.0, celsius));
            int milli = (int) Math.round(celsius * 100.0);
            if (milli != menu.getTargetTemperature()) {
                ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 3, milli));
            }
        } catch (NumberFormatException ignored) {
            // 同上：解析不了就当作没提交。
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int x = leftPos;
            int y = topPos;

            // 已安装的冷萃槽：点一下即卸载（Mek 的升级界面碰不到冷萃，这是唯一入口）。
            Slot coldBrew = coldBrewSlotAt(mouseX, mouseY);
            if (coldBrew != null) {
                for (int i = 0; i < coldBrewSlotWidgets.length; i++) {
                    if (coldBrewSlotWidgets[i] == coldBrew) {
                        ModMessages.sendToServer(new UpgradeUninstallPacket(
                                menu.getBlockPos(), (byte) 2, IceMakerTile.CB_SLOT_1 + i));
                        return true;
                    }
                }
            }

            // 索敌：目标类型按钮（半径数值走右侧输入框）。
            int attackY = y + IceMakerMenu.ATTACK_ROW_Y;
            int tX = x + TARGET_X;
            if (mouseX >= tX && mouseX < tX + TARGET_W
                    && mouseY >= attackY && mouseY < attackY + CONTROL_H) {
                cycleTarget();
                return true;
            }

            // 控温：开关按钮（目标温度走右侧输入框）。
            int tempY = y + IceMakerMenu.TEMP_ROW_Y;
            if (mouseX >= tX && mouseX < tX + TEMP_TOGGLE_W
                    && mouseY >= tempY && mouseY < tempY + CONTROL_H) {
                ModMessages.sendToServer(new IceAttackConfigPacket(menu.getBlockPos(), (byte) 5,
                        menu.isTemperatureControlEnabled() ? 0 : 1));
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
                // 面板数据直接问 tile：Mek 的容器工厂在取不到 BE 时直接抛「Missing tile」，
                // 根本构造不出「空菜单」，所以这里不需要（也不该有）machine == null 兜底。
                return tile.getAvailableRecipes();
            }

            @Override
            public int maxCraftable(Recipe<?> recipe) {
                return tile.getMaxConsumableCountForOrder(recipe);
            }

            @Override
            public void order(Recipe<?> recipe, int quantity) {
                ModMessages.sendToServer(new cn.ism.mekck.network.OrderRecipePacket(
                        menu.getBlockPos(), recipe.getId(), quantity));
            }
        };
    }
}
