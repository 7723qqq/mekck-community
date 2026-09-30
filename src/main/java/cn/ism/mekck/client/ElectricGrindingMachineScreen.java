package cn.ism.mekck.client;

import cn.ism.mekck.menu.ISideConfigurableMenu;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.ElectricGrindingMachineMenu;
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
import mekanism.client.gui.element.slot.GuiSlot;
import mekanism.client.gui.element.slot.GuiVirtualSlot;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.client.gui.element.GuiTexturedElement;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class ElectricGrindingMachineScreen extends GuiMekanism<ElectricGrindingMachineMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    /** 「ME 下单」面板开关（本屏没有本机下单列表 ⇒ 面板恒为 ME 模式）。 */
    private boolean orderMode = false;
    private static final int ORDER_TAB_Y = 34; // 侧配 tab 下方 28px（与其它屏一致）
    private static final int ORDER_PANEL_LEFT = 10;
    private static final int ORDER_PANEL_TOP = 10;
    /** 下单 tab 图标：自绘「清单 + 向下箭头」（原来借用的 Mekanism sorting.png 像音符、且语义不符）。 */
    private static final ResourceLocation ORDER_TEXTURE = MachineTabIcons.ORDER;
    private final NetworkOrderPanel mePanel = new NetworkOrderPanel(true, true);

    // Mekanism-style tab positions
    // Left side: config tab
    // 侧栏 tab 几何：外框 24 / 按钮·图标 16 已内建于 MekCkTabElement（OUTER/INNER），
    // 旧常量 TAB_OUTER_W/H、TAB_ICON_SIZE、TAB_ICON_OFFSET 随之删除；坐标常量一律不动。
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;

    // Right side: upgrade tab (at top-right corner, matching Mekanism's GuiUpgradeWindowTab)
    private static final int UPGRADE_TAB_Y = 6;

    // Redstone control tab (right side, identical position to Mekanism's factory: x = imageWidth, y = 137)
    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;

    // Redstone control icon textures (Mekanism)
    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    // Mekanism textures
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");

    public ElectricGrindingMachineScreen(ElectricGrindingMachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 220;
        imageHeight = 184;
        inventoryLabelY = 89;
        dynamicSlots = true;
        wireLocalOrderSource();
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // Machine page: input slot (tightly packed)
        GuiVirtualSlot inputVS = new GuiVirtualSlot(SlotType.INPUT, this, 37, 40);
        if (menu.slots.get(0) instanceof IVirtualSlot ivs) {
            inputVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(inputVS);

        // Machine page: output slot (tightly packed, right next to input)
        GuiVirtualSlot outputVS = new GuiVirtualSlot(SlotType.OUTPUT, this, 55, 40);
        if (menu.slots.get(1) instanceof IVirtualSlot ivs) {
            outputVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(outputVS);

        // Energy bar (right side)
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                      menu.getEnergy(), ElectricGrindingMachineBlockEntity.ENERGY_CAPACITY);
            }

            @Override
            public double getLevel() {
                return (double) menu.getEnergy() / ElectricGrindingMachineBlockEntity.ENERGY_CAPACITY;
            }
        }, imageWidth - 12, 22));

        // Progress bar (Mekanism-style SMALL_RIGHT arrow)
        addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
            @Override
            public double getProgress() {
                return menu.getProgress() / 24.0;
            }

            @Override
            public boolean isActive() {
                return menu.getProgress() > 0;
            }
        }, ProgressType.SMALL_RIGHT, this, 78, 38));

        // Energy info tab (bottom-left corner, using Mekanism's texture)
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
              Component.translatable("gui.mekck.energy_stored",
                    menu.getEnergy(), ElectricGrindingMachineBlockEntity.ENERGY_CAPACITY),
              Component.translatable("gui.mekck.energy_per_tick",
                    ElectricGrindingMachineBlockEntity.ENERGY_PER_TICK)
        )));

        // Power slot (energy items: energy cube / tablet / redstone) next to the energy bar
        int powerSlotIndex = 4; // input(0), output(1), speed(2), energy(3), power(4)
        if (powerSlotIndex < menu.slots.size()) {
            GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
            powerVs.with(SlotOverlay.POWER);
            if (menu.slots.get(powerSlotIndex) instanceof IVirtualSlot ivs) {
                powerVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(powerVs);
        }

        // 侧栏 tab **最后注册**：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、命中即返回，
        // 即越晚注册命中优先（tab 才能压过同区域的虚拟槽）。同 CuttingMachineFactoryScreen。
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
        drawString(guiGraphics, playerInventoryTitle, 20, inventoryLabelY, titleTextColor());
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        // AE2 网络拉料按钮已迁到 addGuiElements() 末尾的 NetworkPullButton.register(...)（Mek 原生 tab）。
        // 侧栏 4 个 tab 已全部迁到 addTabElements()（MekCkTabElement），renderBg 不再手绘。
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Temporarily set item counts to 1 for stacks with large counts,
        // so the default count text is not rendered (count > 1 triggers text).
        // Items are rendered at their correct positions; only the text is suppressed.
        bigStackHud.shrink(menu.slots);

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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (orderMode && mePanel.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (orderMode && mePanel.charTyped(codePoint, modifiers)) return true;
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
        // 侧栏 4 个 tab 的点击已交给 MekCkTabElement#onClick —— 它们是 renderable widget，
        // 由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        // ME 下单：面板点击（面板外点击关闭；左键）。tab 切换已交给 MekCkTabElement。
        if (button == 0 && orderMode) {
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
        return super.mouseClicked(mouseX, mouseY, button);
    }
}