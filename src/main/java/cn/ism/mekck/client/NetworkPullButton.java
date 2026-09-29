package cn.ism.mekck.client;

import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkPullPacket;
import cn.ism.mekck.util.AE2Compat;
import mekanism.client.gui.GuiUtils;
import mekanism.client.render.MekanismRenderer;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** AE2 通用"网络拉料"按钮（ME拉 + 自动）。 */
public final class NetworkPullButton {
    /**
     * §F14 #4（用户 2026-09-24）：按钮从**面板右下角**搬到**面板外左侧 tab 列**，并从 30×16 改为 24×24 正方形
     * ——与各屏侧配/下单 tab 同尺寸同列。集中改这一处即覆盖 15 个调用屏（它们全部走 getX/getY，无自定义坐标）。
     */
    public static final int W = 24;
    public static final int H = 24;
    /** 面板外左侧 tab 列的 x（与各屏 {@code TAB_X = -26} 对齐）。 */
    public static final int TAB_X = -26;
    /** 两按钮的纵向步长（24 高 + 2 间隙，与各屏 tab 的 28 步长一致）。 */
    public static final int STEP = H + 2;
    /** tab 列与按钮列之间、以及按钮列与 tab 列首格之间的间隙（与各屏 {@code CONFIG_TAB_Y = 6} 同口径）。 */
    public static final int GAP = 4;
    /** tab 列首格（侧配 tab）的顶边 y：无左侧 tab 列的屏（生物反应堆）从这里起排。 */
    public static final int FIRST_TAB_Y = 6;

    private static final ResourceLocation BUTTON_TEXTURE = MekanismUtils.getResource(ResourceType.GUI, "button.png");

    /**
     * 「自动补料」icon：改用 Mekanism 自带按钮贴图 {@code mekanism:gui/button/auto_pull.png}（原生 14×14）。
     * <p><b>选它的理由</b>：这张图就是 Mek 自己的「从 ME 网络自动拉取」开关图标（数字矿机的 AUTO_PULL 开关同义），
     * 与本按钮语义逐字对齐；{@code toggle/toggle_flipped} 是通用开关对，而本按钮不画开/关两态（图标与状态无关），
     * 只取一个反而丢了状态语义。</p>
     * <p><b>取法</b>：对齐 Mek 自己的 {@code ToggleButton}——{@link ResourceType#GUI_BUTTON} 前缀是 {@code gui/button}，
     * 文件名须自带 {@code .png}（{@code getResource} 只做「前缀 + 名字」拼接，不补后缀）。</p>
     */
    private static final ResourceLocation ICON_AUTO = MekanismUtils.getResource(ResourceType.GUI_BUTTON, "auto_pull.png");
    /** {@code auto_pull.png} 的原生尺寸，用作 blit 的 UV 区域与贴图尺寸（与按钮尺寸 W/H 无关）。 */
    private static final int ICON_AUTO_TEX_SIZE = 14;
    /**
     * 「ME拉」icon：Mek 自带按钮贴图里<b>没有</b>「单次从 ME 网络拉取」的对应图——{@code auto_eject} 是反向的
     * 自动弹出、{@code inventory} 是背包、{@code follow} 是跟随，均不对齐，故保持自绘不动。
     * 美术线 24×24 满幅（§F14 尾单 2026-09-25：图标与文字二选一，默认只留图标）。
     */
    private static final ResourceLocation ICON_ME_PULL = new ResourceLocation("mekck", "textures/gui/icon_me_pull.png");

    private NetworkPullButton() {
    }

    /** 「ME拉」按钮左上角 X（相对面板）：固定在左侧 tab 列。参数仅为兼容既有 15 屏调用，不再参与计算。 */
    public static int getX(int imageWidth) {
        return TAB_X;
    }

    /**
     * 「ME拉」按钮左上角 Y（相对面板）：**紧接本屏 tab 列最后一个 tab 的下方**，与 tab 同列续排。
     * <p>参数是调用屏「左列最后一个 tab 的顶边 y」（不是 imageHeight！）：实机取证（用户 2026-09-24 陈酿机
     * 截图）表明旧的 {@code imageHeight - H - 4} 会把按钮孤零零掉到面板左下、与上方 tab 列断开一大截空白，
     * 故改锚定 tab 列。各屏取值：普通屏 {@code ORDER_TAB_Y = 34}（或 {@code AUTO_DIST_Y = 34}）、
     * 工厂屏 62、切菜工厂 90。</p>
     * <p>排布：自动在上、ME拉 在下 ⇒ 自动顶 = lastTabTop + H + GAP（与 tab 底留 4px），
     * ME拉 顶再 +STEP。⇒ 34 得自动 62 / ME拉 88；62 得 90 / 116；90 得 118 / 144。</p>
     */
    /**
     * 「ME拉」按钮左上角 Y 的<b>基准</b>：画在「自动」上方一格。
     *
     * <p><b>注意</b>：这只是按左侧 tab 列推算的基准值，<b>最终 Y 由
     * {@link #avoidEnergyTabY} 每帧动态修正</b> —— Mek 的 {@code GuiEnergyTab} 把位置写死在
     * {@code (-26, 137, 26, 26)}，左侧 tab 多时（4 个 tab → 基准 144）会压进它，
     * 实机症状是两个 tooltip 叠在一起。这里不复制 137 这个常量，由运行时读实际元素位置。</p>
     */
    public static int getY(int lastTabTopY) {
        return lastTabTopY + H + GAP + STEP;
    }

    /**
     * 返回一个每帧重算的 Y：在基准值基础上，避开同列的 {@code GuiEnergyTab}。
     *
     * <p>遍历 {@code gui.children()} 找实际的能量 tab 并取其下沿 —— Mek 升级改了常量也不会错位。</p>
     */
    private static int avoidEnergyTabY(GuiMekanism<?> gui, int baseY) {
        int y = baseY;
        for (var element : gui.children()) {
            if (element instanceof GuiEnergyTab energy) {
                // 必须用 getRelativeY()（GUI 相对坐标），不能用继承自 AbstractWidget 的 getY()
                // —— 后者是**绝对屏幕坐标** = guiTop + relativeY，与 baseY 不是同一坐标系。
                // 混用会让 bottom 随窗口高度放大（guiTop 越大推得越远），拉料按钮被顶出屏幕。
                // 实测 GuiEnergyTab 的 relativeY = 137（构造字节码 sipush 137），故 bottom 恒为 167。
                //
                // ⚠️ 这里**不能**记日志：本方法由 render 每帧调用，而「能量 tab 下沿超过基准」
                // 是多数屏幕的常态，任何级别的日志都会变成每帧一条的刷屏。
                y = Math.max(y, energy.getRelativeY() + energy.getHeight() + GAP);
            }
        }
        return y;
    }

    /** 无左侧 tab 列的屏（生物反应堆）专用：「自动」从 tab 列首格 {@link #FIRST_TAB_Y} 起，ME拉 在其下一格。 */
    public static int getYTop() {
        return FIRST_TAB_Y + STEP;
    }

    /** 「自动」按钮的 Y：画在「ME拉」上方一格（旧右下角布局里自动在 ME拉 左侧，排序不变）。 */
    public static int getAutoY(int mePullY) {
        return mePullY - STEP;
    }

    /** 文字在 24×24 按钮内水平 + 垂直居中（两套调用方共用，避免各屏自己拍偏移）。 */
    public static void drawLabel(GuiGraphics guiGraphics, Font font, int x, int y, String text) {
        guiGraphics.drawString(font, text,
                x + (W - font.width(text)) / 2, y + (H - font.lineHeight) / 2, 0xFFFFFFFF);
    }

    /**
     * 在 24×24 按钮位上叠画 icon（盖在 {@link #blitButton} 的九宫格底之上）。
     * blit 前先复位颜色，免得继承上一站的 tint；<b>命中矩形、hover、tooltip 一律不动</b>（用户 2026-09-25 工单红线）。
     * public 供自绘画面的屏（如 {@code SimpleMachineScreen}）复用，保证全部调用屏单一口径。
     * <p>两枚 icon 都按各自原生尺寸 1:1 画：ME拉自绘图原生就是 24×24，铺满；Mek {@code auto_pull} 原生 14×14，
     * <b>居中</b>画进按钮区（{@code (24-14)/2 = 5}）——原先被撑到 24×24 是 1.71× 非整数放大，会发糊。</p>
     */
    public static void blitIcon(GuiGraphics guiGraphics, int x, int y, boolean mePull) {
        MekanismRenderer.resetColor(guiGraphics);
        if (mePull) {
            guiGraphics.blit(ICON_ME_PULL, x, y, 0, 0, W, H, W, H);
        } else {
            guiGraphics.blit(ICON_AUTO,
                    x + (W - ICON_AUTO_TEX_SIZE) / 2, y + (H - ICON_AUTO_TEX_SIZE) / 2,
                    0, 0, ICON_AUTO_TEX_SIZE, ICON_AUTO_TEX_SIZE, ICON_AUTO_TEX_SIZE, ICON_AUTO_TEX_SIZE);
        }
    }

    /** 命中矩形：新位置在面板**左侧（x 为负）**，故参数用 double 不做 (int) 截断，免得边界像素偏 1。 */
    private static boolean over(int x, int y, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + W && mouseY >= y && mouseY < y + H;
    }

    private static void blitButton(GuiGraphics guiGraphics, int x, int y, boolean hovered) {
        MekanismRenderer.resetColor(guiGraphics);
        GuiUtils.blitNineSlicedSized(guiGraphics, BUTTON_TEXTURE, x, y, W, H,
                20, 4, 200, 20, 0, (hovered ? 2 : 1) * 20, 200, 60);
    }

    /**
     * 绘制两个按钮（自动 + ME拉，同列纵向堆叠）：返回是否被鼠标悬停。
     * <p>传入的 (x, y) 是「ME拉」的左上角；「自动」画在它上方一格。把"自动"一起画在这里，
     * 所有已经显示 ME拉 的屏幕（工厂/机器共 15 个）无需改动即可获得持续补料开关。</p>
     * <p>2026-09-25 用户工单：面文字改画美术 icon（{@link #blitIcon}，与文字二选一、默认只留图标）。</p>
     * <p><b>2026-09-27 层级工单</b>：本方法只在 {@code renderBg()} 阶段画按钮，tooltip 改由
     * {@link #queueTip} 排队到 Forge 延迟通道（见该方法注释）。</p>
     */
    public static boolean render(Screen screen, GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY) {
        int autoY = getAutoY(y);
        boolean hoverAuto = over(x, autoY, mouseX, mouseY);
        blitButton(guiGraphics, x, autoY, hoverAuto);
        blitIcon(guiGraphics, x, autoY, false);

        boolean hover = over(x, y, mouseX, mouseY);
        blitButton(guiGraphics, x, y, hover);
        blitIcon(guiGraphics, x, y, true);

        // §F41（用户口径：悬停显示名字与 tips）：tooltip 排队给延迟通道，15 个调用屏单点生效；
        // 自动在上 ME拉 在下，两处互斥（同屏不会同时悬停）。
        if (hoverAuto) {
            queueTip(screen, "auto_pull");
        } else if (hover) {
            queueTip(screen, "network_pull");
        }
        return hover || hoverAuto;
    }

    /**
     * 两行 tooltip：标题（默认白）+ 描述（灰），与 Mekanism 元素 tooltip 观感一致。
     * <p><b>层级关键（§F42，用户 2026-09-27 切菜工厂截图）</b>：这里<b>只排队不绘制</b>。tooltip 交给 Forge 的
     * 延迟通道 {@link Screen#setTooltipForNextRenderPass(List)}，由 {@code Screen#renderWithTooltip} 在
     * {@code render()} 返回<b>之后</b>统一绘制，绘完即把 {@code deferredTooltipRendering} 置 null。</p>
     * <p>旧实现直接 {@code guiGraphics.renderTooltip(...)}，而 15 个调用屏都在 {@code renderBg()} 里调本类 ——
     * {@code AbstractContainerScreen#render} 的顺序是「① renderBg() → ② Screen#render() 遍历全部
     * renderable widget（槽位底图/物品/能量条）」，所以 ② 必然盖住 ① 画的 tooltip：实机截图里
     * 「开启后原料<b>▯▯</b>时自动从 ME 网络拉取」正好被大网格首行槽位压掉两个字。
     * 排队之后 tooltip 恒在最上层，<b>与调用点在哪个阶段无关</b>，以后再加工具也不会回归。</p>
     * <p>对标 Mekanism：它的 {@code GuiElement#renderToolTip} 同样是独立于 {@code renderWidget} 的
     * 最后一遍绘制，侧栏 tab 基类 {@code GuiTabElementType} 实现该方法，结构上杜绝同类问题。</p>
     */
    private static void queueTip(Screen screen, String key) {
        List<FormattedCharSequence> lines = List.of(
                Component.translatable("tooltip.mekck." + key + ".title").getVisualOrderText(),
                Component.translatable("tooltip.mekck." + key + ".desc").withStyle(ChatFormatting.GRAY).getVisualOrderText());
        screen.setTooltipForNextRenderPass(lines);
    }

    /** 点击检测并发送拉料包（左键 ME拉 = 拉一份；点上方的「自动」= 切换持续补料）。 */
    public static boolean click(double mouseX, double mouseY, int x, int y, BlockPos pos) {
        if (over(x, getAutoY(y), mouseX, mouseY)) {
            ModMessages.sendToServer(new NetworkPullPacket(pos, NetworkPullPacket.ACTION_TOGGLE_AUTO, ""));
            return true;
        }
        if (over(x, y, mouseX, mouseY)) {
            ModMessages.sendToServer(new NetworkPullPacket(pos, NetworkPullPacket.ACTION_PULL, ""));
            return true;
        }
        return false;
    }

    /** 是否显示（仅装 AE2 时）。 */
    public static boolean isVisible() {
        return AE2Compat.isLoaded();
    }

    /**
     * 注册两枚按钮为 Mek 原生 tab 元素（{@link MekCkTabElement}）—— 与其余侧栏 tab 同一套体系。
     *
     * <p><b>2026-09-28</b>：此前本类是「在 {@code renderBg()} 里手绘按钮 + 在 {@code mouseClicked()} 里手算命中」，
     * 与已 Mek 化的侧栏 tab 完全两套 —— 同一列按钮一半走框架、一半手搓。
     * 现改为注册真 widget：底图/hover/点击/tooltip 全部交给 Mek，
     * tooltip 走 {@code GuiMekanism#renderLabels} 的元素通道（管线最后一层）。</p>
     *
     * <p><b>调用位置要求</b>：必须在各屏元素注册的<b>末尾</b>调用 ——
     * Mek 的 {@code GuiMekanism#mouseClicked} 对 {@code children()} 倒序遍历、命中即返回，越晚注册命中优先。</p>
     *
     * @param mePullX 「ME拉」按钮左上角 x（相对面板，沿用 {@link #getX(int)}）
     * @param mePullY 「ME拉」按钮左上角 y（相对面板，沿用 {@link #getY(int)}）；「自动」在其上方一格
     */
    public static List<MekCkTabElement> register(GuiMekanism<?> gui, int mePullX, int mePullY, BlockPos pos) {
        // 在**构造前**一次性避开同列的 GuiEnergyTab。
        //
        // 为什么不能靠 MekCkTabElement#dynamicY 每帧重算：GuiInsetElement 画 holder / 按钮 / 图标
        // 用的都是**构造时固定的 relativeX/relativeY**，从不读 getY()（2026-09-28 实机踩坑：
        // 动态 Y 只影响了命中测试，绘制原地不动 —— 反而更糟）。能量 tab 位置本身是静态的，
        // 所以这里直接算一次传进构造器即可。
        int pullY = avoidEnergyTabY(gui, mePullY);
        int autoY = getAutoY(pullY);
        // 两枚图标都不是 Mek tab 的 18×18：ME拉 自绘 24×24 满幅、Mek auto_pull 原生 14×14，
        // 故显式 iconSize 让 drawBackgroundOverlay 按原生尺寸居中（否则会按 18 采样而错位/裁切）。
        MekCkTabElement auto = MekCkTabElement.left(gui, ICON_AUTO, mePullX, autoY,
                () -> false,
                () -> List.of(
                        Component.translatable("tooltip.mekck.auto_pull.title"),
                        Component.translatable("tooltip.mekck.auto_pull.desc").withStyle(ChatFormatting.GRAY)),
                () -> ModMessages.sendToServer(new NetworkPullPacket(pos, NetworkPullPacket.ACTION_TOGGLE_AUTO, "")))
                .iconSize(ICON_AUTO_TEX_SIZE);
        MekCkTabElement pull = MekCkTabElement.left(gui, ICON_ME_PULL, mePullX, pullY,
                () -> false,
                () -> List.of(
                        Component.translatable("tooltip.mekck.network_pull.title"),
                        Component.translatable("tooltip.mekck.network_pull.desc").withStyle(ChatFormatting.GRAY)),
                () -> ModMessages.sendToServer(new NetworkPullPacket(pos, NetworkPullPacket.ACTION_PULL, "")))
                .iconSize(W);
        return List.of(auto, pull);
    }
}
