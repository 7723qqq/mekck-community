package cn.ism.mekck.client;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInsetElement;
import mekanism.client.render.MekanismRenderer;
import mekanism.client.render.lib.ColorAtlas.ColorRegistryObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 机器侧栏 tab —— 继承 Mekanism {@link GuiInsetElement}，取代各屏在 {@code renderBg()} 里的手绘三层 blit。
 *
 * <h3>为什么继承而不是继续手绘</h3>
 * 本类的三层绘制与旧手绘代码**逐参数相同**（已对 10.4.6.20 反编译核对）：
 * <ol>
 *   <li>{@code GuiSideHolder.draw()} —— 同一套 {@code holder_left.png}/{@code holder_right.png}，
 *       同一九宫格 border 4，取元素自身 width/height；</li>
 *   <li>{@code drawButton()} —— 同一张 {@code button.png}，九宫格参数
 *       {@code 4, 0, 0, state*20, 200, 60}，state 由 {@link #getButtonTextureY} 给出；</li>
 *   <li>{@code drawBackgroundOverlay()} —— 图标 blit 到 {@code getButtonX()/getButtonY()}，
 *       尺寸 {@code innerWidth × innerHeight}。</li>
 * </ol>
 * 几何只需传 {@code outer=24 / inner=16}，{@code border} 由父类算出 {@code (24-16)/2 = 4}，
 * 与旧常量 {@code TAB_OUTER_W/H=24}、{@code TAB_ICON_SIZE=16}、{@code HOLDER_BORDER=4} 一致。
 *
 * <h3>关键收益：tooltip 层级</h3>
 * 旧实现在 {@code renderBg()} 里直接 {@code guiGraphics.renderTooltip(...)}，而
 * {@code AbstractContainerScreen#render} 的顺序是「① renderBg() → ② Screen#render() 画全部
 * renderable widget」，所以槽位底图必然盖住 tooltip（2026-09-27 实机截图实证）。
 * 改继承后 tooltip 走 {@code GuiMekanism#renderLabels} 的元素通道 —— 那是管线的**最后一层**，
 * 结构上不可能再被盖，同时 {@link NetworkPullButton} 里的 Forge 延迟通道只是同构的另一条兜底。
 *
 * <h3>与旧行为的唯一差异</h3>
 * 旧代码点击判定用整个 holder（24×24）而 tooltip 只用按钮矩形（+6,+5,16×16）—— 两者本就不一致。
 * 本类统一用 holder 矩形：点击热区**与旧完全相同**（不会缩小），代价是 hover 高亮/tooltip
 * 在 holder 外圈 4px 内也会触发。属无回退方向的放宽。
 */
public class MekCkTabElement extends GuiInsetElement<Object> {

    /**
     * holder 外框尺寸 —— 对齐 Mekanism 窗口类 tab（26 宽）。
     *
     * <p>实测自 {@code mekanism-268560-6018299} 反混淆 jar 的构造字节码：</p>
     * <ul>
     *   <li>{@code GuiSideConfigurationTab}: {@code bipush -26, 6, 26, 18} → 26×18</li>
     *   <li>{@code GuiUpgradeWindowTab}: {@code getWidth(), 6, 26, 18} → 26×18</li>
     *   <li>{@code GuiRedstoneControlTab}: {@code getWidth(), 137, 26, 18} → 26×18</li>
     *   <li>{@code GuiSortingTab}: {@code bipush -26, 62, 35, 18} → <b>35×18</b>（特例，见下）</li>
     * </ul>
     *
     * <p>{@code GuiSortingTab} 用 35 是因为它在 tab 上额外画文字标签
     * （{@code drawTextScaledBound(..., 21.0f)}，y = relativeY + 24）。本项目的 tab 一律不画文字，
     * 属窗口/开关类，故取 26 而非 35。</p>
     *
     * <p>旧值 24 是本项目手写 tab 时代遗留的尺寸，比 Mek 窗口类 tab 窄 2px，
     * 且与 {@code TAB_X = -26}（按 26 宽算出的列偏移）自相矛盾。</p>
     */
    public static final int OUTER = 26;
    /** 按钮/图标尺寸 —— Mek tab 图标原生 18×18；父类据此算出 border = (26-18)/2 = 4。 */
    public static final int INNER = 18;

    /**
     * Mekanism 官方 tab 图标的原生尺寸 —— 实测 18×18（读自 {@code mekanism-268560-6018299} jar
     * 内的 PNG 字节：{@code configuration} / {@code sorting} / {@code upgrade} /
     * {@code redstone_control_{disabled,high,low}} 全部 18×18）。
     *
     * <p>与 {@link #INNER} 同值：Mek tab 的按钮区与图标区同为 18×18，故默认无需缩放，
     * 直接走父类 blit。非 Mek 图标（如拉料按钮的 24×24 / 14×14）由调用方 {@link #iconSize} 覆盖。</p>
     *
     * @deprecated 与 {@link #INNER} 重复；新代码直接用 {@link #INNER}。
     */
    @Deprecated
    public static final int MEK_TAB_ICON_SIZE = INNER;

    /** 按钮相对 holder 左上角的偏移（沿用旧手绘实测值，不走父类的 ±1 推导）。 */
    private static final int BTN_OFF_X_LEFT = 6;
    private static final int BTN_OFF_Y = 5;
    private static final int BTN_OFF_X_RIGHT = 4;

    private int buttonOffX;
    private int buttonOffY = BTN_OFF_Y;
    private int iconSize = MEK_TAB_ICON_SIZE;
    private java.util.function.IntSupplier dynamicY;
    private final BooleanSupplier selected;
    private final ColorRegistryObject tint;
    private final Supplier<List<Component>> tooltip;
    private final Runnable action;
    /** 绘制在图标之上的附加层（红石 PULSE 脉冲动画用），可为 null。 */
    private Consumer<GuiGraphics> overlayLayer;
    /** 动态图标（红石 tab 随模式换图）；非 null 时优先于构造传入的静态图标。 */
    private Supplier<ResourceLocation> dynamicOverlay;

    /** 覆写按钮横向偏移（红石 tab 用 +3，而非常规右侧的 +4）。 */
    public MekCkTabElement buttonOffsetX(int offX) {
        return buttonOffset(offX, BTN_OFF_Y);
    }

    /** 同时覆写按钮横/纵向偏移。 */
    public MekCkTabElement buttonOffset(int offX, int offY) {
        this.buttonOffX = offX;
        this.buttonOffY = offY;
        return this;
    }

    /** 图标每帧重新求值（红石控制 3 态）。 */
    public MekCkTabElement dynamicOverlay(Supplier<ResourceLocation> supplier) {
        this.dynamicOverlay = supplier;
        return this;
    }

    /**
     * 令本元素的 y 每帧由 supplier 重算。
     *
     * <p>用于<b>避开同列其它元素</b>：Mek 的 {@code GuiEnergyTab} 在自己的构造器里把位置写死为
     * {@code (-26, 137, 26, 26)}。AE2 拉料按钮同在 x=-26 的左列，若按「最后一个左侧 tab 往下推」
     * 算，tab 多的高档位（4 个 tab → y=144）就会压进能量 tab —— 实机症状是两个 tooltip
     * 叠在一起、且点击落到错误元素。</p>
     *
     * <p><b>不在本类里复述 137 这个常量</b>：那是 Mek 的内部实现细节，升级后可能变。
     * 由调用方遍历 {@code gui.children()} 找实际的 {@code GuiEnergyTab} 位置动态计算。</p>
     */
    public MekCkTabElement dynamicY(IntSupplier supplier) {
        this.dynamicY = supplier;
        return this;
    }

    @Override
    public int getY() {
        return dynamicY != null ? dynamicY.getAsInt() : super.getY();
    }

    /**
     * 延后设置图标之上的附加层。
     * <p>必须在实例化<b>之后</b>调用：lambda 里要引用 {@code this}（本 tab），而构造参数求值时
     * {@code this} 尚未绑定。</p>
     */
    public MekCkTabElement overlayLayer(Consumer<GuiGraphics> layer) {
        this.overlayLayer = layer;
        return this;
    }

    /**
     * 按钮矩形在 GUI 相对坐标下的左上角 —— 供<b>包外</b>调用方（如各 Screen 给按钮加高亮/角标）定位。
     *
     * <p>为什么不直接暴露 {@code getButtonX/getButtonY}：那两个在 {@code GuiInsetElement} 里是
     * {@code protected}，子类之外（含同包其它类）拿不到；而 Mech 自己的几何基于 {@code relativeX}
     * 而不是 {@code AbstractWidget#getX()}（绝对坐标），外部照抄 {@code getX()} 会差一整个
     * {@code leftPos/topPos}，把叠加层画到屏幕另一头（2026-09-28 实机踩坑）。</p>
     */
    public int buttonX() {
        return getButtonX();
    }

    public int buttonY() {
        return getButtonY();
    }

    /**
     * 在按钮矩形内缩 1px 叠一张动画贴图（红石 PULSE 的脉冲动画）。
     * 对外公开是因为调用方（各 Screen）处于本类的包外作用域，拿不到 protected 成员。
     */
    public void drawInnerOverlay(GuiGraphics guiGraphics, TextureAtlasSprite texture) {
        guiGraphics.blit(getButtonX() + 1, getButtonY() + 1, 0,
                innerWidth - 2, innerHeight - 2, texture);
    }

    @Override
    protected ResourceLocation getOverlay() {
        return dynamicOverlay != null ? dynamicOverlay.get() : super.getOverlay();
    }

    /**
     * @param icon      图标贴图（作为父类 overlay 绘制）
     * @param relX/relY 相对 GUI 左上角（Mekanism 会再叠加 leftPos/topPos）
     * @param left      true = 面板左侧 tab，false = 右侧 tab
     * @param outer     holder 尺寸（24 或 26）
     * @param inner     按钮/图标尺寸（16 或 18）
     * @param selected  选中态（配置/下单面板打开）→ 按钮常亮，对齐旧 {@code btnState}
     * @param tint      holder 染色，可为 null
     * @param tooltip   悬停提示，每次悬停重新求值
     * @param action    左键动作
     * @param overlayLayer 图标之上的附加绘制，可为 null
     */
    public MekCkTabElement(IGuiWrapper gui, ResourceLocation icon, int relX, int relY, boolean left,
                           int outer, int inner, BooleanSupplier selected, ColorRegistryObject tint,
                           Supplier<List<Component>> tooltip, Runnable action, Consumer<GuiGraphics> overlayLayer) {
        super(icon, gui, null, relX, relY, outer, inner, left);
        this.buttonOffX = left ? BTN_OFF_X_LEFT : BTN_OFF_X_RIGHT;
        this.selected = selected;
        this.tint = tint;
        this.tooltip = tooltip;
        this.action = action;
        this.overlayLayer = overlayLayer;
    }

    /** 常规左侧 tab（24/16）。 */
    public static MekCkTabElement left(IGuiWrapper gui, ResourceLocation icon, int relX, int relY,
                                        BooleanSupplier selected, Supplier<List<Component>> tooltip, Runnable action) {
        return new MekCkTabElement(gui, icon, relX, relY, true, OUTER, INNER,
                selected, null, tooltip, action, null);
    }

    /** 常规右侧 tab（24/16）。 */
    public static MekCkTabElement right(IGuiWrapper gui, ResourceLocation icon, int relX, int relY,
                                         BooleanSupplier selected, Supplier<List<Component>> tooltip, Runnable action) {
        return new MekCkTabElement(gui, icon, relX, relY, false, OUTER, INNER,
                selected, null, tooltip, action, null);
    }

    // getButtonX()/getButtonY() 一律交给父类计算。
    //
    // 2026-09-28 踩坑：曾用 `getX() + buttonOffX` 覆写它们，结果图标全部飞到屏幕右下角。
    // 原因是两套坐标系不一致 —— GuiSideHolder#draw 画 holder 用 relativeX（GUI 相对），
    // 而 AbstractWidget#getX() 是绝对坐标；混用就会差一整个 leftPos/topPos 的偏移。
    // 父类的实现（relativeX + border ± 1）才是对的，它与 Mek 自身 tab 一致；
    // 代价是按钮位置与我们旧手绘值差 1px（父类给 +5/+4，我们旧值 +6/+5），可接受。
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX < getX() + width
                && mouseY >= getY() && mouseY < getY() + height;
    }

    /** 对齐旧 {@code btnState = selected || btnHovered ? 2 : 1}。 */
    @Override
    protected int getButtonTextureY(boolean hovered) {
        return selected.getAsBoolean() || hovered ? 2 : 1;
    }

    @Override
    protected void colorTab(GuiGraphics guiGraphics) {
        if (tint != null) {
            MekanismRenderer.color(guiGraphics, tint);
        }
    }

    /**
     * 图标层必须先把染色清掉，否则会继承 {@link #colorTab} 留在 render state 里的 tint。
     *
     * <p>{@code GuiElement#drawButton} 内部虽然会 {@code resetColor}，但它只在
     * {@code buttonBackground != NONE} 时才被调用；一旦某个 tab 用了 {@code ButtonBackground.NONE}
     * （holder 本身就是按钮，Mek 自己的 tab 正是这么画的），这条重置就被跳过，
     * 紧接着的 {@code drawBackgroundOverlay} 就会把图标染成和底板同色。</p>
     *
     * <p><b>2026-09-28 图标错位修复</b>：父类的 blit 写作
     * {@code blit(overlay, getButtonX(), getButtonY(), 0, 0, innerWidth, innerHeight, innerWidth, innerHeight)}
     * —— 源裁剪区与贴图 UV 尺寸<b>都取 innerWidth/innerHeight</b>，等于要求 overlay 贴图必须正好是
     * {@code inner} 像素见方。而 Mek 的 tab 图标尺寸并不统一（12/14/16/18 都有），
     * 按 16×16 裁会把图标切掉一块、表现为错位/残缺（实机截图实证）。
     * 现改为<b>按贴图原生尺寸绘制并在按钮内居中</b>；取不到原生尺寸时回落到父类行为。</p>
     */
    @Override
    protected void drawBackgroundOverlay(GuiGraphics guiGraphics) {
        MekanismRenderer.resetColor(guiGraphics);
        ResourceLocation tex = getOverlay();
        if (tex == null) {
            return;
        }
        int s = iconSize > 0 ? iconSize : innerWidth;
        if (s == innerWidth && s == innerHeight) {
            // 图标原生尺寸与按钮一致，直接沿用父类绘制
            super.drawBackgroundOverlay(guiGraphics);
            return;
        }
        guiGraphics.blit(tex,
                getButtonX() + (innerWidth - s) / 2,
                getButtonY() + (innerHeight - s) / 2,
                0.0F, 0.0F, s, s, s, s);
    }

    /**
     * 覆写图标贴图的**原生**像素尺寸（同时用作源裁剪区与 UV 尺寸）。
     *
     * <p>默认 {@link #MEK_TAB_ICON_SIZE} = 18 —— 实测 Mekanism 10.4 的 tab 图标
     * （{@code configuration/sorting/upgrade/redstone_control_*}）<b>全部是 18×18</b>。
     * 父类却用 {@code innerWidth/innerHeight} 当裁剪尺寸，按 16 裁 18 的图会切掉右下各 2 像素，
     * 表现为图标错位/残缺。</p>
     */
    public MekCkTabElement iconSize(int size) {
        this.iconSize = size;
        return this;
    }

    /** 图标之上再画一层（红石 PULSE 脉冲）。 */
    @Override
    public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderForeground(guiGraphics, mouseX, mouseY);
        if (overlayLayer != null) {
            overlayLayer.accept(guiGraphics);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        action.run();
    }

    /** 走 {@code GuiMekanism#renderLabels} 的元素 tooltip 通道 —— 管线最后一层。 */
    @Override
    public void renderToolTip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<Component> lines = tooltip.get();
        if (lines != null && !lines.isEmpty()) {
            displayTooltips(guiGraphics, mouseX, mouseY, lines);
        }
    }
}
