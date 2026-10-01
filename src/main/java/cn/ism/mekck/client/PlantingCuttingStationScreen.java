package cn.ism.mekck.client;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.menu.PlantingCuttingStationMenu;
import cn.ism.mekck.network.ModMessages;
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
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.container.slot.IVirtualSlot;
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

public final class PlantingCuttingStationScreen extends GuiMekanism<PlantingCuttingStationMenu> implements NetworkOrderHost {
    private final cn.ism.mekck.client.BigStackHud bigStackHud = new cn.ism.mekck.client.BigStackHud();
    private boolean upgradePage = false;

    // Mekanism-style tab positions
    // Left side: config tab
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
    // Right side: upgrade tab (at top-right corner, matching Mekanism's GuiUpgradeWindowTab)
    private static final int UPGRADE_TAB_Y = 6;

    // Redstone control tab (right side, identical position to Mekanism's factory: x = imageWidth, y = 137)
    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;

    // Redstone control icon textures (Mekanism)
    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");

    // Mekanism textures（tab 的 holder/button 三层 blit 已由 MekCkTabElement 接管，此处只留 tab 图标）
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");

    // Upgrade slot positions (on upgrade page, matching menu slot positions)
    // Left column: speed (8,17), energy (8,53)
    // Right column: gas (152,17), creative (152,35)
    private static final int UPGRADE_SLOT_X = 8;
    private static final int UPGRADE_SPEED_Y = 17;
    private static final int UPGRADE_ENERGY_Y = 53;
    private static final int UPGRADE_CREATIVE_Y = 35;
    private static final int UPGRADE_GAS_Y = 17;

    public PlantingCuttingStationScreen(PlantingCuttingStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 166;
        inventoryLabelY = 71;
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        // Input slot
        GuiVirtualSlot inputVS = new GuiVirtualSlot(SlotType.INPUT, this, 55, 16);
        if (menu.slots.get(0) instanceof IVirtualSlot ivs) {
            inputVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(inputVS);

        // Nutrient slot
        GuiVirtualSlot nutrientVS = new GuiVirtualSlot(SlotType.INPUT, this, 55, 52);
        if (menu.slots.get(1) instanceof IVirtualSlot ivs) {
            nutrientVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(nutrientVS);

        // 生长方块格（种子与营养液之间）：所有配方共用 1 格
        // 只有神秘农业种子受它约束（BotanyPots 的 soil categories 判定），其余配方无视该格
        GuiVirtualSlot growthVS = new GuiVirtualSlot(SlotType.INPUT, this, 55, 34);
        if (menu.slots.get(PlantingCuttingStationBlockEntity.GROWTH_SLOT) instanceof IVirtualSlot ivs) {
            growthVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(growthVS);

        // Output slot
        GuiVirtualSlot outputVS = new GuiVirtualSlot(SlotType.OUTPUT, this, 115, 34);
        if (menu.slots.get(2) instanceof IVirtualSlot ivs) {
            outputVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(outputVS);

        // Energy bar (right side)
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy",
                      menu.getEnergy(), PlantingCuttingStationBlockEntity.ENERGY_CAPACITY);
            }

            @Override
            public double getLevel() {
                return (double) menu.getEnergy() / PlantingCuttingStationBlockEntity.ENERGY_CAPACITY;
            }
        }, imageWidth - 12, 16));

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
        }, ProgressType.SMALL_RIGHT, this, 78, 26));

        // Energy info tab (bottom-left corner, using Mekanism's texture)
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
              Component.translatable("gui.mekck.energy_stored",
                    menu.getEnergy(), PlantingCuttingStationBlockEntity.ENERGY_CAPACITY),
              Component.translatable("gui.mekck.energy_per_tick",
                    PlantingCuttingStationBlockEntity.ENERGY_PER_TICK)
        )));

        // Power slot (energy items: energy cube / tablet / redstone) next to the energy bar
        GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this, 6, 12);
        powerVs.with(SlotOverlay.POWER);
        if (menu.slots.get(PlantingCuttingStationBlockEntity.SLOT_POWER) instanceof IVirtualSlot ivs) {
            powerVs.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(powerVs);

        // 侧栏 tab **最后注册**：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、命中即返回，
        // 即越晚注册命中优先；排在虚拟槽之后才能保住旧的命中优先级。
        addTabElements();
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
        // 旧行为：点侧配 tab 会顺带收起升级浮层，此副作用原样保留。
        addRenderableWidget(new MekCkTabElement(this, CONFIG_TEXTURE, TAB_X, CONFIG_TAB_Y, true,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> false,
                mekanism.client.SpecialColors.TAB_CONFIGURATION,
                () -> List.of(Component.translatable("tooltip.mekck.side_config")),
                () -> {
                    openSideConfigWindow();
                    upgradePage = false;
                }, null));

        // ME 下单在 Mek 里无对应图标：保留本模组自绘的「清单 + 向下箭头」图标，只取官方染色。
        // 面板本体已从「屏幕手绘覆盖层」迁进 Mek 虚拟窗口（NetworkOrderWindow）。
        // 本屏没有本机下单列表 ⇒ localSource 传 null，面板恒为 ME 模式。
        orderTab = addRenderableWidget(new NetworkOrderTab(this, menu.getBlockPos(),
                TAB_X, ORDER_TAB_Y, true, null, () -> orderTab));

        // ── 右列（2 个）──
        // 本屏的「升级 tab」切的是自制浮层（旧 btnState 也以 upgradePage 为准），不弹 Mek 升级窗。
        addRenderableWidget(new MekCkTabElement(this, UPGRADE_TEXTURE, imageWidth, UPGRADE_TAB_Y, false,
                MekCkTabElement.OUTER, MekCkTabElement.INNER,
                () -> upgradePage,
                mekanism.client.SpecialColors.TAB_UPGRADE,
                () -> List.of(Component.translatable("tooltip.mekck.upgrade")),
                this::toggleUpgradePage, null));

        addRenderableWidget(redstoneTab());

        // 侧栏 tab 必须最后注册：Mek 的 GuiMekanism#mouseClicked 对 children() 倒序遍历、
        // 命中即返回，越晚注册命中优先。
        if (cn.ism.mekck.client.NetworkPullButton.isVisible()) {
            for (var tab : cn.ism.mekck.client.NetworkPullButton.register(this, menu.getBlockPos())) {
                addRenderableWidget(tab);
            }
        }
    }

    /** 升级浮层开关（原在 mouseClicked 内联，迁出为 tab 动作；含旧有的 setUpgradePageActive 同步）。 */
    private void toggleUpgradePage() {
        upgradePage = !upgradePage;
        menu.setUpgradePageActive(upgradePage);
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

    private static String formatItemCount(int count) {
        // 统一走 CountFormat（含十亿档；21 亿不再显示成 2147.5M）
        return cn.ism.mekck.client.CountFormat.compact(count);
    }

    /** 生长方块格在 GUI 内的位置（与 addGuiElements 里的 GuiVirtualSlot 一致）。 */
    private static final int GROWTH_SLOT_X = 55;
    private static final int GROWTH_SLOT_Y = 34;

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 8, inventoryLabelY, titleTextColor());
        renderGrowthSlotHint(guiGraphics, mouseX, mouseY);
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    /**
     * 生长方块格提示：只在「配方要等级而没满足」时出现，缺方块与等级不足**文案不同**。
     * <p>悬停时给出完整说明（含所需档位名）。</p>
     */
    private void renderGrowthSlotHint(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int status = menu.getGrowthStatus();
        if (status == PlantingCuttingStationBlockEntity.GROWTH_OK) {
            return;
        }
        String tier = menu.getGrowthTierName();
        boolean missing = status == PlantingCuttingStationBlockEntity.GROWTH_MISSING;
        guiGraphics.drawString(font, Component.translatable(missing
                ? "gui.mekck.ui.growth_status.missing"
                : "gui.mekck.ui.growth_status.tier_low").getString(),
                GROWTH_SLOT_X + 21, GROWTH_SLOT_Y + 5, 0xFFFF5555);

        boolean hovering = mouseX >= GROWTH_SLOT_X && mouseX < GROWTH_SLOT_X + 18
                && mouseY >= GROWTH_SLOT_Y && mouseY < GROWTH_SLOT_Y + 18;
        if (hovering) {
            String tierText = tier.isEmpty() ? "" : (tier + " ");
            guiGraphics.renderTooltip(font, java.util.List.of(
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.growth_block_slot"),
                    net.minecraft.network.chat.Component.translatable(missing
                            ? "gui.mekck.ui.growth_need"
                            : "gui.mekck.ui.growth_need_tier_low", tierText),
                    net.minecraft.network.chat.Component.translatable("gui.mekck.ui.growth_block_slot.note")),
                    java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        // Draw nutrient indicator (small colored dot next to the nutrient slot)
        boolean hasNutrient = menu.hasNutrient();
        int nutrientIndicatorX = leftPos + 55 + 18 + 2; // right of the nutrient slot
        int nutrientIndicatorY = topPos + 52 + 9 - 2;   // vertically centered on the slot
        int indicatorColor = hasNutrient ? 0xFF00FF00 : 0xFF444444; // green if present, dark gray if absent
        guiGraphics.fill(nutrientIndicatorX, nutrientIndicatorY, nutrientIndicatorX + 4, nutrientIndicatorY + 4, indicatorColor);

        // 侧栏 4 个 tab（侧配 / ME 下单 / 升级 / 红石）已全部迁到 addTabElements()（MekCkTabElement），
        // renderBg 不再手绘。
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

        int x = leftPos;
        int y = topPos;

        // Upgrade page overlay (on top of everything)
        if (upgradePage) {
            renderUpgradePage(guiGraphics, x, y);
        }

        // 侧栏 4 个 tab 的 tooltip 已迁到 MekCkTabElement#renderToolTip，
        // 由 GuiMekanism#renderLabels 在渲染管线最后一层统一派发。
    }

    private void renderUpgradePage(GuiGraphics guiGraphics, int x, int y) {
        // Semi-transparent overlay
        guiGraphics.fill(x + 3, y + 3, x + imageWidth - 3, y + imageHeight - 3, 0xCC000000);

        int slotX = x + UPGRADE_SLOT_X;
        int speedY = y + UPGRADE_SPEED_Y;
        int energyY = y + UPGRADE_ENERGY_Y;
        int creativeY = y + UPGRADE_CREATIVE_Y;
        int gasY = y + UPGRADE_GAS_Y;
        int rightSlotX = x + 152; // right column X position

        // Speed upgrade slot (left column, top)
        guiGraphics.blit(SlotType.INPUT.getTexture(), slotX - 1, speedY - 1, 0, 0, 18, 18, 18, 18);
        int speedCount = menu.getSpeedUpgradeCount();
        if (speedCount > 0) {
            String speedText = "S" + (speedCount > 1 ? "x" + speedCount : "");
            guiGraphics.drawString(font, speedText, slotX + 2, speedY + 4, 0xFFFFFFFF);
        }
        // 复用 UpgradeHelper 的曲线常量：GUI 显示的倍率必须与方块实体实际生效的倍率同源，
        // 否则调整升级曲线后这里会显示过期数值。
        double speedMult = cn.ism.mekck.util.UpgradeHelper.speedMultiplier(speedCount);
        guiGraphics.drawString(font, String.format("Speed: %d (%.1fx)", speedCount, speedMult), slotX + 22, speedY + 4, 0xFFFFFFFF);

        // Energy upgrade slot (left column, bottom)
        guiGraphics.blit(SlotType.INPUT.getTexture(), slotX - 1, energyY - 1, 0, 0, 18, 18, 18, 18);
        int energyCount = menu.getEnergyUpgradeCount();
        if (energyCount > 0) {
            String energyText = "E" + (energyCount > 1 ? "x" + energyCount : "");
            guiGraphics.drawString(font, energyText, slotX + 2, energyY + 4, 0xFFFFFFFF);
        }
        double consumptionMult = cn.ism.mekck.util.UpgradeHelper.energyConsumptionMultiplier(energyCount);
        double capacityMult = cn.ism.mekck.util.UpgradeHelper.energyCapacityMultiplier(energyCount);
        guiGraphics.drawString(font, String.format("Energy: %d (%.2fx/%.1fx)", energyCount, consumptionMult, capacityMult), slotX + 22, energyY + 4, 0xFFFFFFFF);

        // Gas upgrade slot (right column, top)
        guiGraphics.blit(SlotType.INPUT.getTexture(), rightSlotX - 1, gasY - 1, 0, 0, 18, 18, 18, 18);
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.slot.gas").getString(), rightSlotX + 18 + 2, gasY + 4, 0xFFFFFFFF);

        // Creative upgrade slot (right column, middle)
        // ⚠️ 原先这里是 `boolean hasCreative = menu.getEnergyCapacity() > ENERGY_CAPACITY;`
        // 然后**再也没用过它**（死变量），而那条判据本身也是错的：ContainerData 经
        // ClientboundContainerSetDataPacket 传输时对每个值用 writeShort（**16 位有符号**），
        // 10 万的容量到客户端会被 readShort() 符号扩展成 **-31072**
        // （0x186A0 → 低 16 位 0x86A0 → 作为 short 是负数），于是 `-31072 > 100_000` 恒假。
        //
        // 注意别被「100000 - 65536 = 34464」那种说法骗了：那只是**无符号**解读，
        // 而 readShort() 返回有符号 short，赋给 int 字段时会发生符号扩展。
        // -31072 是 TestWideDataSlot#channelIsSixteenBitSigned 拿真实包往返实测出来的。
        //
        // 现在改读专用的 DATA_CREATIVE_UPGRADE（值域 {0,1}，不可能溢出），与
        // client/MekCkUpgradeType 里 `case CREATIVE -> menu.getCreativeUpgradeCount()`
        // 的既有约定一致。
        //
        // 状态表现刻意用**文字着色**而不是换槽位贴图：javap 实测 Mek 的
        // mekanism.client.gui.element.slot.SlotType 只有 NORMAL / DIGITAL / POWER /
        // EXTRA / INPUT / INPUT_2 / OUTPUT / OUTPUT_2 / OUTPUT_WIDE / OUTPUT_LARGE /
        // ORE / INNER_HOLDER_SLOT —— **没有 CREATIVE**，拿 POWER 顶替会给出误导性的图标。
        boolean hasCreative = menu.getCreativeUpgradeCount() > 0;
        guiGraphics.blit(SlotType.INPUT.getTexture(), rightSlotX - 1, creativeY - 1, 0, 0, 18, 18, 18, 18);
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.slot.creative").getString(), rightSlotX + 18 + 2, creativeY + 4,
                hasCreative ? 0xFFFF55 : 0xFFFFFFFF);

        // Label at top
        guiGraphics.drawString(font, Component.translatable("gui.mekck.upgrades").getString(), x + 10, y + 10, 0xFFFFFFFF);
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
        // 「下单」tab 同理：它开的是 Mek 窗口，窗口内的点击由 GuiMekanism#mouseClicked
        // 先遍历 windows 派发（窗口在 children() 之前），本屏不再需要任何命中矩形。
        return super.mouseClicked(mouseX, mouseY, button);
    }
}