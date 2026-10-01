package cn.ism.mekck.client;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.blockentity.SimpleMachineBlockEntity;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.menu.IUpgradeMenu;
import cn.ism.mekck.menu.SimpleMachineMenu;
import cn.ism.mekck.network.ModMessages;
import net.minecraft.world.item.crafting.Recipe;
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
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.render.MekanismRenderer;
import mekanism.client.render.lib.ColorAtlas.ColorRegistryObject;
import mekanism.common.inventory.container.slot.SlotOverlay;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** 四合一基础机器共用 GUI（寿司卷制机/平均切段机/饭团成型机/凝乳成型机）。 */
public final class SimpleMachineScreen extends GuiMekanism<SimpleMachineMenu> implements NetworkOrderHost {
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

    // 侧栏 tab 几何：外框 24 / 按钮·图标 16 已内建于 MekCkTabElement（OUTER/INNER），
    // 旧常量 TAB_OUTER_W/H、TAB_ICON_SIZE、TAB_ICON_OFFSET 随之删除；坐标常量一律不动。
    private static final int TAB_X = -26;
    private static final int CONFIG_TAB_Y = 6;
    private static final int UPGRADE_TAB_Y = 6;
    private static final int REDSTONE_TAB_SIZE = 26;
    private static final int REDSTONE_TAB_INNER = 18;

    /** 陈酿机果汁液位条位置（机甲风皮肤下，GUI 内相对坐标，位于第一行输入槽下方）。 */
    private static final int JUICE_BAR_X = 16;
    private static final int JUICE_BAR_Y = 66;

    /** vinery 皮肤背景（纯字符串 RL，不复制资源、无编译期依赖；装了 vinery 才引用得到）。 */
    private static final ResourceLocation VINERY_BG = new ResourceLocation("vinery", "textures/gui/fermentation_barrel_gui.png");
    /**
     * {@code fermentation_barrel_gui.png} 的**图集真实尺寸**（实测 256×256，GUI 框架只占左上 176×166）。
     * blit 的最后两个参数是图集尺寸，必须填 256/256；若误填 176/166 会把整张背景横向压 256/176≈1.45 倍、
     * 纵向压 256/166≈1.54 倍，导致背景凹槽与按 vinery 真实坐标摆放的槽位全部错开，且图集右侧
     * （x≥176 的气泡与液位彩条装饰）会被挤进画面。实机取证见 docs/待办总览.md。
     */
    private static final int VINERY_ATLAS_W = 256;
    private static final int VINERY_ATLAS_H = 256;
    /** vinery 对位下果汁条原点（对齐 FermentationBarrelGui 的 (82,44)）。 */
    private static final int VJ_BAR_X = 82;
    private static final int VJ_BAR_Y = 44;
    /** inputTank 流体条（{@link GuiCkFluidGauge}，STANDARD=18×60）摆放：vinery 皮肤放左侧空白列（流体格旁），机甲风放右侧空白。实机微调。 */
    private static final int WV_GAUGE_X = 10;
    private static final int WV_GAUGE_Y = 20;
    /**
     * 简报 §九（2026-09-24）：陈酿机电源槽改到**右侧能量条左边、与果汁格平齐**。
     * 常量已上移到 {@link SimpleMachineMenu#WV_POWER_X}——菜单 addSlot 与这里的 GuiVirtualSlot 必须共用同一坐标，
     * 否则 {@code renderSlots} 会在菜单坐标处多画一个裸露的原版空槽框（用户 2026-09-25 报的「奇怪的格子」）。
     * 机甲风（wl 恒 false）保持 (6,12)。坐标以实机为准校准。
     */
    private static final int MW_GAUGE_X = 150;
    private static final int MW_GAUGE_Y = 22;
    /**
     * vinery 陈酿桶同款**竖直进度柱**（简报 §七，取证自 {@code FermentationBarrelGui} 常量池）：
     * 精灵就在我们已在用的同一张图集 {@code fermentation_barrel_gui.png} 的 (176,0)，尺寸 11×29，
     * 摆位 (122,20)（与槽位同基、相对 GUI 左上）。⇒ 零新增资源。
     */
    private static final int WV_PROGRESS_TEX_X = 176;
    private static final int WV_PROGRESS_W = 11;
    private static final int WV_PROGRESS_H = 29;
    private static final int WV_PROGRESS_X = 122;
    private static final int WV_PROGRESS_Y = 20;

    private static final ResourceLocation REDSTONE_DISABLED = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_disabled.png");
    private static final ResourceLocation REDSTONE_HIGH = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_high.png");
    private static final ResourceLocation REDSTONE_LOW = MekanismUtils.getResource(ResourceType.GUI, "redstone_control_low.png");
    private static final ResourceLocation CONFIG_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "configuration.png");
    private static final ResourceLocation UPGRADE_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "upgrade.png");

    public SimpleMachineScreen(SimpleMachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        if (menu.vineryLayout()) {
            // winery + 已装 vinery：套用陈酿桶 176×166 框架（与 Menu 对位坐标同基）。
            imageWidth = SimpleMachineMenu.VINERY_IMAGE_W;
            imageHeight = SimpleMachineMenu.VINERY_IMAGE_H;
            inventoryLabelY = 74;
        } else {
            imageWidth = SimpleMachineMenu.IMAGE_WIDTH;
            imageHeight = SimpleMachineMenu.IMAGE_HEIGHT;
            inventoryLabelY = 83;
        }
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();

        final boolean wl = menu.vineryLayout();
        final boolean winery = menu.isWinery();

        // 输入槽（虚拟槽）：winery 统一角色 0..2 配料 / 3 酒瓶 carrier / 4 弃用（不画）；其余机器沿用通用输入行。
        // 酒瓶格（carrier 槽）改用 SlotType.EXTRA 外观（用户 2026-09-24 要求）：它与配料区职责互斥，
        // 用 INPUT 框会和 0..2 看起来同质；SlotType 仅决定槽框纹理/尺寸（EXTRA、OUTPUT 均 18×18），
        // 不参与放入/取出判定，故纯视觉改动。warning 叠图只有显式 .warning() 才绘制，不受影响。
        for (int i = 0; i < SimpleMachineBlockEntity.INPUT_COUNT; i++) {
            int sx, sy;
            SlotType slotType = SlotType.INPUT;
            if (winery) {
                if (i <= 2) {
                    sx = (wl ? SimpleMachineMenu.WV_ING_X : SimpleMachineMenu.INPUT_X) + i * 18;
                    sy = wl ? SimpleMachineMenu.WV_ING_Y : SimpleMachineMenu.INPUT_Y;
                } else if (i == SimpleMachineBlockEntity.WINERY_CARRIER_SLOT) {
                    sx = wl ? SimpleMachineMenu.WV_BOTTLE_X : SimpleMachineMenu.INPUT_X + 3 * 18;
                    sy = wl ? SimpleMachineMenu.WV_BOTTLE_Y : SimpleMachineMenu.INPUT_Y;
                    slotType = SlotType.EXTRA;
                } else {
                    continue; // 弃用槽（菜单坐标 -1000）：不画虚拟槽
                }
            } else {
                sx = SimpleMachineMenu.INPUT_X + i * 18;
                sy = SimpleMachineMenu.INPUT_Y;
            }
            GuiVirtualSlot vs = new GuiVirtualSlot(slotType, this, sx, sy);
            if (menu.slots.get(i) instanceof IVirtualSlot ivs) {
                vs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(vs);
        }
        // 搅拌机：扩展输入槽 10..13 的第二行虚拟槽（menu 槽索引紧随 5 个输入槽）
        if (menu.usesExtendedSlots()) {
            for (int i = 0; i < SimpleMachineBlockEntity.EXT_INPUT_COUNT; i++) {
                GuiVirtualSlot vs = new GuiVirtualSlot(SlotType.INPUT, this,
                        SimpleMachineMenu.INPUT_X + i * 18, SimpleMachineMenu.INPUT_Y + 18);
                int idx = SimpleMachineBlockEntity.INPUT_COUNT + i;
                if (idx < menu.slots.size() && menu.slots.get(idx) instanceof IVirtualSlot ivs) {
                    vs.updateVirtualSlot(null, ivs);
                }
                addRenderableWidget(vs);
            }
        }

        // 搅拌机在 5 个常规输入槽后额外插入 4 个扩展输入槽 → 后续槽（输出/创造/能源）在 menu 中的索引后移
        int slotShift = menu.usesExtendedSlots() ? SimpleMachineBlockEntity.EXT_INPUT_COUNT : 0;

        // 输出槽（vinery 对位放陈酿桶产物格）
        GuiVirtualSlot outputVS = new GuiVirtualSlot(SlotType.OUTPUT, this,
                wl ? SimpleMachineMenu.WV_OUT_X : SimpleMachineMenu.OUTPUT_X,
                wl ? SimpleMachineMenu.WV_OUT_Y : SimpleMachineMenu.OUTPUT_Y);
        if (menu.slots.get(SimpleMachineBlockEntity.OUTPUT_SLOT + slotShift) instanceof IVirtualSlot ivs) {
            outputVS.updateVirtualSlot(null, ivs);
        }
        addRenderableWidget(outputVS);

        // 创造升级槽不再在主界面常显：与速度/能量一致，仅在升级页选中时由 selectedSlot 重定位绘制。

        // 能源条
        addRenderableWidget(new GuiVerticalPowerBar(this, new IBarInfoHandler() {
            @Override
            public Component getTooltip() {
                return Component.translatable("gui.mekck.energy", menu.getEnergy(), menu.getEnergyCapacity());
            }

            @Override
            public double getLevel() {
                return (double) menu.getEnergy() / menu.getEnergyCapacity();
            }
        }, imageWidth - 12, 22));

        // 进度条：机甲风（含未装 vinery 的陈酿机）沿用 Mekanism 横向箭头；
        // vinery 皮肤下改画陈酿桶同款竖直柱（简报 §七），在 renderBg 里 blit，此处跳过。
        if (!wl) {
            addRenderableWidget(new GuiProgress(new IProgressInfoHandler() {
                @Override
                public double getProgress() {
                    return menu.getProgress() / 24.0;
                }

                @Override
                public boolean isActive() {
                    return menu.getProgress() > 0;
                }
            }, ProgressType.SMALL_RIGHT, this, 100, 38));
        }

        // 能量信息 tab
        addRenderableWidget(new GuiEnergyTab(this, () -> List.of(
                Component.translatable("gui.mekck.energy_stored", menu.getEnergy(), menu.getEnergyCapacity()),
                Component.translatable("gui.mekck.energy_per_tick", menu.getEnergyPerTick())
        )));

        // 能源槽：简报 §九——vinery 皮肤下移到右侧能量条左边、与果汁格平齐 (146,17)；
        // 其他机器 wl 恒 false ⇒ 保持 (6,12)。槽本体用 GuiVirtualSlot 画，但菜单 addSlot 的坐标已同步取
        // SimpleMachineMenu.WV_POWER_*（两处不一致就会在旧位残留一个裸露的原版空槽框）。
        if (SimpleMachineBlockEntity.SLOT_POWER < menu.slots.size()) {
            GuiVirtualSlot powerVs = new GuiVirtualSlot(SlotType.POWER, this,
                    wl ? SimpleMachineMenu.WV_POWER_X : 6, wl ? SimpleMachineMenu.WV_POWER_Y : 12);
            powerVs.with(SlotOverlay.POWER);
            if (menu.slots.get(SimpleMachineBlockEntity.SLOT_POWER + slotShift) instanceof IVirtualSlot ivs) {
                powerVs.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(powerVs);
        }

        // 陈酿机：果汁格（瓶装→液位 ∪ 流体桶→inputTank，共用一格）/ 返还槽 + inputTank 流体条。
        // 原「流体物品输入格」已废弃（vinery 背景只有 6 个格位），不再渲染。
        if (winery) {
            int juiceIdx = SimpleMachineBlockEntity.SLOT_POWER + slotShift + 1;
            GuiVirtualSlot juiceVS = new GuiVirtualSlot(SlotType.EXTRA, this,
                    wl ? SimpleMachineMenu.WV_JUICE_X : SimpleMachineMenu.INPUT_X,
                    wl ? SimpleMachineMenu.WV_JUICE_Y : SimpleMachineMenu.INPUT_Y + 18);
            if (juiceIdx < menu.slots.size() && menu.slots.get(juiceIdx) instanceof IVirtualSlot ivs) {
                juiceVS.updateVirtualSlot(null, ivs);
            }
            addRenderableWidget(juiceVS);
            // 返还槽（流体桶抽空后返的空桶 / 分装后多出的载具）：改用 SlotType.OUTPUT 外观（用户 2026-09-24
            // 要求）——它本就只准取出不准放入（menu 层 OutputSlot），与输出格语义一致；菜单中紧随果汁格追加。
            int returnIdx = juiceIdx + 1;
            GuiVirtualSlot returnVS = new GuiVirtualSlot(SlotType.OUTPUT, this,
                    wl ? SimpleMachineMenu.WV_RETURN_X : SimpleMachineMenu.OUTPUT_X,
                    wl ? SimpleMachineMenu.WV_RETURN_Y : SimpleMachineMenu.OUTPUT_Y + 18);
            if (returnIdx < menu.slots.size() && menu.slots.get(returnIdx) instanceof IVirtualSlot rvs) {
                returnVS.updateVirtualSlot(null, rvs);
            }
            addRenderableWidget(returnVS);
            // 原「流体物品输入格」已废弃：流体桶与瓶装果汁共用果汁格（vinery 背景只有 6 个格位），不再单独渲染一格。
            // 流体条：Mekanism 同款 GuiCkFluidGauge（读 menu.getFluidStack/getFluidCapacity），替代旧自绘色块。
            addRenderableWidget(new GuiCkFluidGauge(this,
                    wl ? WV_GAUGE_X : MW_GAUGE_X, wl ? WV_GAUGE_Y : MW_GAUGE_Y,
                    menu::getFluidStack, menu::getFluidCapacity));
            // 清空果汁按钮：移到果汁液位条**正上方**、与条左对齐、留 2px 间隙（简报 §八.2，
            // 按钮 21×10 ⇒ 顶 y = jbY-12）；两套皮肤共用此相对式：vinery 得 (82,32)、机甲风得 (16,54)。
            int jbX = wl ? VJ_BAR_X : JUICE_BAR_X;
            int jbY = wl ? VJ_BAR_Y : JUICE_BAR_Y;
            addRenderableWidget(new MekCkWineryDumpButton(this, menu.getMachine(), jbX, jbY - 12));
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

        // ME 下单在 Mek 里无对应图标：保留本模组自绘的「清单 + 向下箭头」图标，只取官方染色。
        // 面板本体已从「屏幕手绘覆盖层」迁进 Mek 虚拟窗口（NetworkOrderWindow），
        // 本机数据源在窗口创建时注入（见 localOrderSource()）。
        orderTab = addRenderableWidget(new NetworkOrderTab(this, menu.getBlockPos(),
                TAB_X, ORDER_TAB_Y, true, localOrderSource(), () -> orderTab));

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
                rsTab.drawInnerOverlay(gg, MekanismRenderer.redstonePulse);
            }
        });
        return rsTab;
    }

    @Override
    protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderTitleText(guiGraphics);
        drawString(guiGraphics, playerInventoryTitle, 20, inventoryLabelY, titleTextColor());
        // 加热类机器：显示机身温度（摄氏度）
        if (menu.isHeatingMachine()) {
            guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.temperature", menu.getTemperature() / 100.0).getString(), 20, 78, 0xFFFF5555);
        }
        super.drawForegroundText(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        final boolean wl = menu.vineryLayout();
        if (wl) {
            // vinery 皮肤：不走机甲风基图，直接贴陈酿桶 GUI（取图集左上 176×166，图集实际 256×256）。
            guiGraphics.blit(VINERY_BG, leftPos, topPos, 0, 0,
                    SimpleMachineMenu.VINERY_IMAGE_W, SimpleMachineMenu.VINERY_IMAGE_H,
                    VINERY_ATLAS_W, VINERY_ATLAS_H);
        } else {
            super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        }
        int x = leftPos;
        int y = topPos;

        // 陈酿机 vinery 皮肤：竖直进度柱（简报 §七），自底向上填充，取代 Mekanism 横向箭头
        // （箭头已在 addGuiElements 里按 wl 跳过）。纹理高 29，取底部 h 像素贴到 (122, 20+(29-h))。
        if (wl) {
            int h = Math.round(WV_PROGRESS_H * Math.min(1f, (float) (menu.getProgress() / 24.0)));
            if (h > 0) {
                guiGraphics.blit(VINERY_BG,
                        x + WV_PROGRESS_X, y + WV_PROGRESS_Y + (WV_PROGRESS_H - h),
                        WV_PROGRESS_TEX_X, WV_PROGRESS_H - h, WV_PROGRESS_W, h,
                        VINERY_ATLAS_W, VINERY_ATLAS_H);
            }
        }

        // 陈酿机：果汁液位条（优先复用葡园酒香的 FermentationBarrelGui.drawJuiceBar，未装 vinery 时自绘）
        if (menu.isWinery()) {
            int jx = x + (wl ? VJ_BAR_X : JUICE_BAR_X);
            int jy = y + (wl ? VJ_BAR_Y : JUICE_BAR_Y);
            String juiceType = menu.getJuiceType();
            int level = menu.getJuiceLevel();
            // 空桶时强制走自绘（原版绘制方法在液位 0 时不画底槽，视觉上会缺一条）
            if (level <= 0 || !VineryJuiceBar.drawNative(guiGraphics, juiceType, level, jx, jy)) {
                VineryJuiceBar.drawFallback(guiGraphics, juiceType, level, jx, jy);
            }
            // inputTank 流体已由 addGuiElements 里的 GuiCkFluidGauge 组件渲染，不再自绘色块。
        }

        // AE2 网络拉料按钮已迁到 addGuiElements() 末尾的 NetworkPullButton.register(...)（Mek 原生 tab）。
        // 侧栏 4 个 tab 已全部迁到 addTabElements()（MekCkTabElement），renderBg 不再手绘。
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        bigStackHud.shrink(menu.slots);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        bigStackHud.restore();

        // 大堆叠数量回绘（简报 §F14 #1）：BigStackHud.shrink 把 count ≥ 1000 临时压成 1 以躲开原版数字，
        // 但本屏从来没补上回绘制 ⇒ >999 的格位**什么都不显示**（陈酿机产物格堆到一千多时看得见瓶看不见数）。
        // 研磨机 / 切菜机等 12 屏在 restore() 之后都有一段 CountFormat.compact 回绘，现照同一范式补齐
        // （0.5 缩放、槽右下角、z=300）。坐标取菜单 Slot.x/y：陈酿机的果汁/产物/返还格在菜单里就是按
        // WV_* 真实坐标 addSlot 的，与 GuiVirtualSlot 渲染位一致，不会错位（PowerSlot 上限 64、不涉本路径）。
        for (Slot slot : menu.slots) {
            if (!slot.isActive() || !slot.hasItem()) continue;
            String formatted = cn.ism.mekck.client.CountFormat.compact(slot.getItem().getCount());
            if (formatted == null) continue;
            int sx = leftPos + slot.x;
            int sy = topPos + slot.y;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(sx + 16, sy + 16, 300);
            guiGraphics.pose().scale(0.5f, 0.5f, 1.0f);
            guiGraphics.drawString(font, formatted, -font.width(formatted), -8, 0xFFFFFF, true);
            guiGraphics.pose().popPose();
        }

        int x = leftPos;
        int y = topPos;

        // 陈酿机：果汁条悬停提示（类型 + 液位）
        if (menu.isWinery()) {
            final boolean wl = menu.vineryLayout();
            int jx = x + (wl ? VJ_BAR_X : JUICE_BAR_X);
            int jy = y + (wl ? VJ_BAR_Y : JUICE_BAR_Y);
            if (mouseX >= jx - 1 && mouseX < jx + VineryJuiceBar.WIDTH + 1
                    && mouseY >= jy - 1 && mouseY < jy + VineryJuiceBar.HEIGHT + 1) {
                String juiceType = menu.getJuiceType();
                Component juiceName = Component.translatable(juiceType.isEmpty()
                        ? "gui.mekck.juice.empty" : "gui.mekck.juice." + juiceType);
                displayTooltips(guiGraphics, mouseX, mouseY,
                        Component.translatable("gui.mekck.juice_tooltip", juiceName,
                                menu.getJuiceLevel(), VineryJuiceBar.MAX_LEVEL));
            }
            // 果汁格悬停提示（一格两用：瓶装果汁→液位池，流体桶→抽入内部储罐）：坐标直接取菜单 Slot，与两种皮肤对位自动一致。
            Slot juiceSlotForTip = menu.slots.get(SimpleMachineBlockEntity.JUICE_SLOT);
            int fsx = x + juiceSlotForTip.x;
            int fsy = y + juiceSlotForTip.y;
            if (mouseX >= fsx && mouseX < fsx + 18 && mouseY >= fsy && mouseY < fsy + 18) {
                displayTooltips(guiGraphics, mouseX, mouseY,
                        Component.translatable("gui.mekck.juice_slot"));
            }
        }

        // 侧栏 4 个 tab 的 tooltip 已迁到 MekCkTabElement#renderToolTip，
        // 由 GuiMekanism#renderLabels 在渲染管线最后一层统一派发。
    }

    @Override
    public NetworkOrderPanel networkOrderPanel() {
        // 窗口开着 ⇒ 返回窗口里的面板；关着 ⇒ null（回包丢弃，不再灌进已销毁的面板）。
        return orderTab == null ? null : orderTab.panel();
    }

    /**
     * 联动机器的「本机」一侧：配方 / 可做份数都按机器输入槽里的材料算，下单走通用订单包。
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
        // 侧栏 4 个 tab 的点击已交给 MekCkTabElement#onClick —— 它们是 renderable widget，
        // 由框架在 super.mouseClicked(...) 里统一派发（含红石 tab 的右键上一档）。
        // 「下单」tab 同理：它开的是 Mek 窗口，窗口内的点击由 GuiMekanism#mouseClicked
        // 先遍历 windows 派发（窗口在 children() 之前），本屏不再需要任何命中矩形。
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
