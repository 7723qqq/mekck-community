package cn.ism.mekck.client;

import mekanism.api.text.EnumColor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.ColorButton;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.button.ToggleButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 机器 GUI 按钮工厂 —— 全部委托给 Mekanism 原生按钮组件，取代各屏的
 * {@code blit(button.png) + fill(颜色) + drawString} 三段手绘。
 *
 * <h3>为什么这样改</h3>
 * 手绘版的问题（实测 305 处、26 个文件）：
 * <ul>
 *   <li>按钮底图用 {@code blit(BUTTON_TEXTURE, …, 0, 0, w, h, w, h)} 直接贴源图左上角，
 *       <b>没有走九宫格、也没有 hover 态</b>，所以外观和 Mek 原生按钮对不上；</li>
 *   <li>颜色靠 {@code guiGraphics.fill()} 盖一块纯色，和 Mek 的按钮高光/边框不协调；</li>
 *   <li>点击要在各屏的 {@code handleConfigClick()} 里再手算一遍命中矩形，
 *       绘制和命中两处坐标容易走偏。</li>
 * </ul>
 * 换成 Mek 原生组件后，底图 / hover / 点击 / 提示全部由框架统一处理。
 *
 * <h3>用不到 Mek 现成类的场合</h3>
 * {@code SideDataButton} 等绑 {@code TileEntityMekanism}，本模组方块实体是 vanilla
 * {@code BlockEntity}，用不了；这里用它们各自可用的父类
 * （{@link ColorButton} / {@link ToggleButton} / {@link MekanismButton}）达到同等观感。
 */
public final class MekCkButtons {

    private MekCkButtons() {
    }

    /**
     * 彩色文字按钮（侧配模式的方向格、ME 自动处理面板的列表项等）。
     * 取代手绘的 {@code blit + fill + drawString}。
     *
     * @param color   按钮填充色，随状态变化（传 Supplier 以每帧刷新）
     * @param onPress 左键
     * @param onRight 右键，可为 null
     */
    public static ColorButton color(IGuiWrapper gui, int x, int y, int width, int height,
                                    Supplier<EnumColor> color, Runnable onPress, Runnable onRight) {
        return color(gui, x, y, width, height, color, Component.empty(), onPress, onRight);
    }

    /**
     * 同上，但额外带一个文字标签：构造后调 {@code setMessage(...)} 把标签交给 Mek 的按钮
     * 文字渲染器（居中）画，取代各屏在 widget 层之上叠画的 {@code drawString}。
     * 标签若随状态变化（如方向格的面名 + 当前模式名），每帧再调一次
     * {@code setMessage(...)} 刷新即可。
     *
     * @param color  按钮填充色，随状态变化（传 Supplier 以每帧刷新）
     * @param label  按钮标签，传 null 等同无标签
     * @param onPress 左键
     * @param onRight 右键，可为 null
     */
    public static ColorButton color(IGuiWrapper gui, int x, int y, int width, int height,
                                    Supplier<EnumColor> color, Component label,
                                    Runnable onPress, Runnable onRight) {
        ColorButton button = new ColorButton(gui, x, y, width, height, color, onPress, onRight);
        // setMessage 是 vanilla net.minecraft.client.gui.components.AbstractWidget 上的 public
        // setter（ColorButton → MekanismButton → GuiElement → AbstractWidget），Mek 未覆写。
        if (label != null) {
            button.setMessage(label);
        }
        return button;
    }

    /**
     * 开关按钮（自动整理 / ME 自动处理 / 持续补料这类「开-关」语义）。
     * Mek 的 {@code ToggleButton} 自带开/关两套贴图与切换动画。
     *
     * @param onIcon  开启态图标，null 用 Mek 默认
     * @param offIcon 关闭态图标，null 用 Mek 默认
     * @param onHover 悬停提示，可为 null
     */
    public static ToggleButton toggle(IGuiWrapper gui, int x, int y, int width, int height,
                                      BooleanSupplier on, Runnable onPress,
                                      ResourceLocation onIcon, ResourceLocation offIcon,
                                      GuiElement.IHoverable onHover) {
        return new ToggleButton(gui, x, y, width, height, onIcon, offIcon, on, onPress, onHover);
    }

    /** 正方形开关按钮（用 Mek 默认贴图，无提示）。 */
    public static ToggleButton toggle(IGuiWrapper gui, int x, int y, int size, BooleanSupplier on,
                                      Runnable onPress) {
        return new ToggleButton(gui, x, y, size, on, onPress, null);
    }

    /** 纯文字按钮（「完成」/「应用」这类确认键），取代手绘的 DONE 按钮。 */
    public static MekanismButton text(IGuiWrapper gui, int x, int y, int width, int height,
                                      Component label, Runnable onPress) {
        return new MekanismButton(gui, x, y, width, height, label, onPress, null, null);
    }

    /**
     * 切换可见/可交互状态。配置模式、自动处理面板这类<b>按需出现</b>的按钮组
     * 需要在 {@code render()} 里随模式开关同步，否则会一直显示。
     */
    public static void setShown(GuiElement element, boolean shown) {
        element.visible = shown;
        element.active = shown;
    }

    /** 批量切换一组按钮的可见性。 */
    public static void setShown(Iterable<? extends GuiElement> elements, boolean shown) {
        for (GuiElement element : elements) {
            setShown(element, shown);
        }
    }
}
