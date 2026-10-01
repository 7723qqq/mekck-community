package cn.ism.mekck.client;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.menu.ISideConfigurableMenu;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.SideConfigPacket;
import mekanism.api.RelativeSide;
import mekanism.api.text.EnumColor;
import mekanism.api.text.TextComponentUtil;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.BasicColorButton;
import mekanism.client.gui.element.button.MekanismImageButton;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData.WindowType;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 与 Mekanism GuiSideConfiguration 一致的侧面配置窗口。
 * 基于我们机器统一的 SideMode(物品输入/输出) 模型，按钮颜色与 Mekanism 的
 * DataType 颜色一致：无=灰、输入=深红、输出=深蓝。
 */
public class GuiMekCkSideConfiguration extends GuiWindow {

    private final ISideConfigurableMenu menu;
    private final Supplier<Direction> facingSupplier;
    private final Map<RelativeSide, BasicColorButton> sideButtons = new EnumMap<>(RelativeSide.class);
    /** 当前配置类型：0=物品，1=流体（点击标题切换）。 */
    private int configType = SideConfigPacket.TYPE_ITEM;

    public GuiMekCkSideConfiguration(IGuiWrapper gui, ISideConfigurableMenu menu, Supplier<Direction> facingSupplier) {
        super(gui, gui.getWidth() / 2 - 156 / 2, 15, 156, 135, WindowType.UNSPECIFIED);
        this.menu = menu;
        this.facingSupplier = facingSupplier;
        interactionStrategy = InteractionStrategy.ALL;

        // 配置类型切换（物品 / 流体 / 气体）：标题栏左侧按钮
        // 注意：arrow_selection 位于 mekanism:gui/，**不在** mekanism:gui/button/ ——
        // 用 GuiElement#getButtonLocation 会去找 gui/button/arrow_selection.png 而落空，
        // 渲染成粉黑缺纹理棋盘格（2026-09-27 实机截图实证）。故此处显式用 ResourceType.GUI。
        addChild(new MekanismImageButton(gui, relativeX + 4, relativeY + 3, 12,
              MekanismUtils.getResource(ResourceType.GUI, "arrow_selection.png"),
              () -> configType = (configType + 1) % 3,
              getOnHover(() -> net.minecraft.network.chat.Component.translatable("tooltip.mekck.switch_config_type"))));

        // 清除所有侧面的配置按钮（同 Mekanism clear_sides：x+136, y+95）
        addChild(new MekanismImageButton(gui, relativeX + 136, relativeY + 95, 14, getButtonLocation("clear_sides"), this::clearAllSides,
              getOnHover(() -> net.minecraft.network.chat.Component.translatable("tooltip.mekck.clear_sides"))));

        // 六个环形侧面按钮，与 Mekanism GuiSideConfiguration 的布局完全一致
        addSideDataButton(RelativeSide.BOTTOM, 68, 92);
        addSideDataButton(RelativeSide.TOP, 68, 46);
        addSideDataButton(RelativeSide.FRONT, 68, 69);
        addSideDataButton(RelativeSide.BACK, 45, 92);
        addSideDataButton(RelativeSide.LEFT, 45, 69);
        addSideDataButton(RelativeSide.RIGHT, 91, 69);
    }

    private void addSideDataButton(RelativeSide relativeSide, int xPos, int yPos) {
        BasicColorButton button = addChild(new BasicColorButton(gui(), relativeX + xPos, relativeY + yPos, 22,
              () -> colorForSide(relativeSide),
              () -> cycleSide(relativeSide, true),
              () -> cycleSide(relativeSide, false),
              getOnHover(relativeSide)));
        sideButtons.put(relativeSide, button);
    }

    /**
     * 相对面 → 世界方向。机器模型的可见正面 = FACING 的反方向（放置时正面朝向玩家），
     * 左右以"玩家面对机器正面时的左右手"为准，与 GUI 面板标签一致。
     */
    private static Direction sideDirection(RelativeSide relativeSide, Direction blockFacing) {
        return switch (relativeSide) {
            case BOTTOM -> Direction.DOWN;
            case TOP -> Direction.UP;
            case FRONT -> blockFacing.getOpposite();
            case BACK -> blockFacing;
            case RIGHT -> blockFacing.getClockWise();
            case LEFT -> blockFacing.getCounterClockWise();
        };
    }

    /**
     * 依据 SideMode 返回与 Mekanism DataType 一致的颜色：
     * 无=灰、抽取(至输入格)=深红、弹出=深蓝、抽取(至存储空间)=黄(DataType.EXTRA)。
     */
    private EnumColor colorForSide(RelativeSide relativeSide) {
        return colorForMode(modeForSide(relativeSide));
    }

    /** 按当前配置类型读取该面的模式。 */
    private SideMode modeForSide(RelativeSide relativeSide) {
        Direction global = sideDirection(relativeSide, facingSupplier.get());
        return switch (configType) {
            case SideConfigPacket.TYPE_FLUID -> menu.getFluidSideMode(global);
            case SideConfigPacket.TYPE_GAS -> menu.getGasSideMode(global);
            default -> menu.getSideMode(global);
        };
    }

    /**
     * 循环切换某一面配置：左键下一个模式，右键上一个模式，与 Mekanism 一致。
     */
    private void cycleSide(RelativeSide relativeSide, boolean next) {
        Direction global = sideDirection(relativeSide, facingSupplier.get());
        SideMode current = modeForSide(relativeSide);
        // 流体/气体侧配不提供「抽取至存储空间」模式；气体是否提供「弹出」由机器决定
        // （当前只有种植切配工厂接入气体侧配且其储罐只装原料，故不支持弹出）
        boolean allowStorage = configType == SideConfigPacket.TYPE_ITEM && menu.supportsStoragePull();
        boolean allowPush = configType != SideConfigPacket.TYPE_GAS || menu.supportsGasPush();
        SideMode cycled = current.cycle(next, allowStorage, allowPush);
        ModMessages.sendToServer(new SideConfigPacket(menu.getBlockPos(), global, cycled, configType));
    }

    private void clearAllSides() {
        for (RelativeSide relativeSide : RelativeSide.values()) {
            ModMessages.sendToServer(new SideConfigPacket(menu.getBlockPos(),
                  sideDirection(relativeSide, facingSupplier.get()), SideMode.NONE, configType));
        }
    }

    private GuiElement.IHoverable getOnHover(RelativeSide relativeSide) {
        return (onHover, guiGraphics, mouseX, mouseY) -> displayTooltips(guiGraphics, mouseX, mouseY,
              net.minecraft.network.chat.Component.translatable(sideLangKey(relativeSide)),
              componentForMode(modeForSide(relativeSide)));
    }

    private net.minecraft.network.chat.Component componentForMode(SideMode mode) {
        net.minecraft.network.chat.Component name = switch (mode) {
            case PULL_INPUT -> net.minecraft.network.chat.Component.translatable("gui.mekck.ui.side_mode.pull_input");
            case PULL_INPUT_STORAGE -> net.minecraft.network.chat.Component.translatable("gui.mekck.ui.side_mode.pull_storage");
            case PUSH_OUTPUT -> net.minecraft.network.chat.Component.translatable("gui.mekck.ui.side_mode.push_output");
            case NONE -> net.minecraft.network.chat.Component.translatable("gui.mekck.ui.side_mode.none");
        };
        return TextComponentUtil.build(colorForMode(mode), name);
    }

    private EnumColor colorForMode(SideMode mode) {
        return switch (mode) {
            case PULL_INPUT -> EnumColor.DARK_RED;
            case PULL_INPUT_STORAGE -> EnumColor.YELLOW;
            case PUSH_OUTPUT -> EnumColor.DARK_BLUE;
            case NONE -> EnumColor.GRAY;
        };
    }

    /**
     * 相对面 → {@code gui.mekck.ui.side.*} 语言键后缀。
     *
     * <p>刻意返回<b>键</b>而不是译文：调用点一律走
     * {@code Component.translatable(sideLangKey(side))}，这样 tooltip 里的
     * 面名与标题共用同一批键，不会出现「一处已迁一处还是硬编码」。</p>
     */
    private String sideLangKey(RelativeSide side) {
        return switch (side) {
            case FRONT -> "gui.mekck.ui.side.front";
            case BACK -> "gui.mekck.ui.side.back";
            case TOP -> "gui.mekck.ui.side.top";
            case BOTTOM -> "gui.mekck.ui.side.bottom";
            case LEFT -> "gui.mekck.ui.side.left";
            case RIGHT -> "gui.mekck.ui.side.right";
        };
    }

    @Override
    public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderForeground(guiGraphics, mouseX, mouseY);
        String key = switch (configType) {
            case SideConfigPacket.TYPE_FLUID -> "gui.mekck.config_type_fluid";
            case SideConfigPacket.TYPE_GAS -> "gui.mekck.config_type_gas";
            default -> "gui.mekck.config_type_item";
        };
        drawTitleText(guiGraphics, net.minecraft.network.chat.Component.translatable(key), 20);
        drawCenteredText(guiGraphics, net.minecraft.network.chat.Component.translatable("gui.mekck.ui.side.slot"), relativeX + 80, relativeY + 120, subheadingTextColor());
    }

    @Override
    protected int getTitlePadEnd() {
        return super.getTitlePadEnd() + 15;
    }
}