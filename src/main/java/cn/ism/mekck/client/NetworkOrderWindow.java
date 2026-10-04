package cn.ism.mekck.client;

import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkOrderPacket;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 「ME 下单」窗口 —— 把 {@link NetworkOrderPanel} 装进 Mek 的虚拟窗口（{@link GuiWindow}），
 * 与「槽位视图」({@link MekCkSlotWindow}) 同一套机制：可拖动、带标题栏与关闭按钮、
 * 由 {@code GuiMekanism.windows} 统一派发输入。
 *
 * <h3>为什么不再手绘</h3>
 * 旧实现是各屏自己在 {@code render()} 里画面板、在 {@code mouseClicked()} 里手算命中矩形。
 * 代价有三处，都是结构性的：
 * <ul>
 *   <li><b>吞掉整次左键</b>：面板开着时 {@code mouseClicked} 直接 return true，侧栏 tab
 *       （{@code MekCkTabElement}）的点击被吃掉，于是 {@code IceFactoryScreen} /
 *       {@code SkeweringMachineScreen} / {@code SmartCookingPotScreen} 各自补了一份
 *       {@code clickTabElement()} 定向派发来绕开自己。窗口化后 {@code GuiMekanism#mouseClicked}
 *       先遍历 {@code windows} 再遍历 {@code children()}，tab 天然拿得到点击，那份补丁可以删。</li>
 *   <li><b>键盘事件要逐屏转发</b>：{@code keyPressed}/{@code charTyped}/{@code mouseScrolled}
 *       三个覆写只是把事件转给面板。窗口化后 {@code GuiMekanism#keyPressed} 与 {@code #charTyped}
 *       都会 {@code windows.stream().anyMatch(w -> w.keyPressed(...))}（实测 10.4.6.20 字节码），
 *       面板的搜索框与自定义数量输入直接可用。</li>
 *   <li><b>几何散落各屏</b>：面板矩形由每屏的 {@code ORDER_PANEL_LEFT/TOP} 与
 *       {@code imageWidth/imageHeight} 现算，渲染与点击必须各算一遍、算错就「画在这里、点在那里」。</li>
 * </ul>
 *
 * <h3>几何</h3>
 * 窗口 236×160，面板内缩 (2, 12) 后 232×144 —— 与 {@link KitchenOrderWindow} 同比例
 * （它 240×176 装 236×160 的面板）。纵向 12 是给标题栏留的：{@code GuiWindow#drawTitleText}
 * 把标题画在 {@code relativeY + 5}（字体高 9 ⇒ 占到 14），{@code GuiWindow#addCloseButton}
 * 把关闭按钮放在 {@code (relativeX + 6, relativeY + 6)}（实测构造字节码 {@code bipush 6, 6}）。
 * 面板若从 {@code relativeY} 起画，标题与关闭按钮会被面板底板盖住。
 *
 * <h3>窗口身份用 UNSPECIFIED，不用 UPGRADE</h3>
 * {@code SelectedWindowData#equals} 只比 {@code type} 与 {@code extraData}（实测字节码），
 * 所以 {@code WindowType.UPGRADE} 的窗口与升级窗是<b>同一个身份</b>；而
 * {@code GuiWindow#close()} 会调 {@code windowData.updateLastPosition(x, y)}，
 * 位置按 {@code type.getSaveName()} 存进 {@code MekanismConfig.client.lastWindowPositions}。
 * 用 UPGRADE 的后果是：关掉升级窗后开下单窗，下单窗会跳到升级窗上次的位置，
 * 而不是本类要求的「居中偏上」。{@code UNSPECIFIED} 的 {@code saveName} 是 null
 * （{@code WindowType} 静态初始化里唯一传 null 的那个），位置不落盘，
 * {@code getLastPosition()} 恒返回 {@code (MAX_VALUE, MAX_VALUE)} ⇒ 每次都按构造参数定位。
 * {@link MekCkSlotWindow} 用 {@code MekCkMachineTile.SLOT_WINDOW}（同样是 UNSPECIFIED 基底）
 * 也是这个理由。
 */
@OnlyIn(Dist.CLIENT)
public class NetworkOrderWindow extends GuiWindow {

    /** 窗口宽 —— 与 {@link KitchenOrderWindow} 的 {@code PANEL_W} 同值。 */
    private static final int WINDOW_W = 236;
    /** 窗口高 —— 与 {@link KitchenOrderWindow} 的 {@code PANEL_H} 同值。 */
    private static final int WINDOW_H = 160;
    /** 面板横向内缩：左右各 2，与 {@code KitchenOrderWindow#panelX} 的 {@code relativeX + 2} 同值。 */
    private static final int PANEL_INSET_X = 2;
    /** 面板纵向内缩：顶部 12 让出标题栏，与 {@code KitchenOrderWindow#panelY} 的 {@code relativeY + 12} 同值。 */
    private static final int PANEL_INSET_Y = 12;
    /** 面板底部留白，与 {@code KitchenOrderWindow} 的 176-12-160 = 4 同值。 */
    private static final int PANEL_PAD_BOTTOM = 4;
    private static final int PANEL_W = WINDOW_W - PANEL_INSET_X * 2;
    private static final int PANEL_H = WINDOW_H - PANEL_INSET_Y - PANEL_PAD_BOTTOM;

    /** 下单目标机器坐标（窗口创建时固定；窗口每次打开都是新实例）。 */
    private final BlockPos pos;
    /** AE 终端风格面板本体 —— 内部实现一行未改，本类只负责「装」它。 */
    private final NetworkOrderPanel panel;

    /**
     * @param localSource 本机数据源；<b>为 null 表示本屏没有本机下单列表</b>
     *                    （制冰工厂 / 种植切割站），此时面板恒为 ME 模式且不画「本机 / ME」按钮
     *                    —— 与旧屏 {@code new NetworkOrderPanel(true, false)} 的语义逐字对应。
     */
    public NetworkOrderWindow(IGuiWrapper gui, BlockPos pos, NetworkOrderPanel.LocalSource localSource) {
        super(gui, gui.getWidth() / 2 - WINDOW_W / 2, 18, WINDOW_W, WINDOW_H,
                SelectedWindowData.WindowType.UNSPECIFIED);
        this.pos = pos;
        this.panel = new NetworkOrderPanel(true, localSource != null);
        this.panel.setLocalSource(localSource);
        // ALL：窗口外的点击必须落到主界面槽位（CONTAINER 会把窗口外的点击也吞掉，
        // 见 GuiWindow#mouseClickedNested 末尾的 allowAll 分支）。窗口内的点击由本类
        // 覆写的 mouseClickedNested 显式返回 this 吃掉，不依赖这个枚举。
        interactionStrategy = InteractionStrategy.ALL;
    }

    /** 供宿主屏幕 / 网络回包读取的面板（{@code NetworkOrderHost#networkOrderPanel}）。 */
    public NetworkOrderPanel panel() {
        return panel;
    }

    /** 面板左上角（窗口相对坐标）。 */
    private int panelX() {
        return relativeX + PANEL_INSET_X;
    }

    private int panelY() {
        return relativeY + PANEL_INSET_Y;
    }

    /** 鼠标是否落在面板矩形内（渲染与点击共用同一份几何）。 */
    private boolean inPanel(double mouseX, double mouseY) {
        return NetworkOrderPanel.hitsRelativeRect(mouseX, mouseY, getGuiLeft(), getGuiTop(),
                panelX(), panelY(), PANEL_W, PANEL_H);
    }

    @Override
    public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderForeground(guiGraphics, mouseX, mouseY);
        // 标题复用 tab 的语言键：本任务不允许改语言键，也不新增键。
        drawTitleText(guiGraphics, Component.translatable("tooltip.mekck.order_panel"), 5);
        // 每帧 bind：机器坐标不变时是空操作，但面板靠它触发首次数据拉取（bind 里判 dataRequested）。
        panel.bind(pos);
        // 面板绘制在 GUI 相对坐标（pose 已在 GUI 原点），悬停判定必须用同一坐标系：
        // 把绝对鼠标换算成 GUI 相对再交给面板（否则高亮/搜索框悬停整体偏移 (leftPos, topPos)）。
        panel.render(guiGraphics, getFont(), panelX(), panelY(), PANEL_W, PANEL_H,
                mouseX - getGuiLeft(), mouseY - getGuiTop(), 0f, getGuiLeft(), getGuiTop());
    }

    /**
     * 面板内的点击必须由窗口吃掉。
     *
     * <p>{@code InteractionStrategy.ALL} 下 {@code GuiWindow#mouseClickedNested} 对
     * 「窗口内、无子元素命中」的点击返回 <b>null</b>（末尾 {@code allowAll()} 分支），
     * 于是事件会继续落到 {@code children()} 与主界面槽位 —— 点面板上的按钮会顺手点到背后的槽位。
     * 这里显式返回 {@code this}，与 {@link KitchenOrderWindow#mouseClickedNested} 的做法一致。</p>
     */
    @Override
    public GuiElement mouseClickedNested(double mouseX, double mouseY, int button) {
        if (inPanel(mouseX, mouseY)) {
            panel.bind(pos);
            // 面板内部按 GUI 相对坐标命中（与绘制同源），这里把绝对鼠标换算过去。
            panel.mouseClicked(mouseX - getGuiLeft(), mouseY - getGuiTop(), button, panelX(), panelY(), PANEL_W, PANEL_H,
                    (recipeId, qty) -> ModMessages.sendToServer(
                            new NetworkOrderPacket(pos, recipeId.toString(), qty)));
            return this;
        }
        return super.mouseClickedNested(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 面板先吃：搜索框聚焦时 Esc 只清空搜索框，不该顺手关掉窗口
        // （GuiWindow#keyPressed 末尾把 Esc 当关闭键）。
        if (panel.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (panel.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /**
     * 滚轮只在鼠标位于面板内时接。
     *
     * <p>{@link KitchenOrderWindow} 是无条件转发（{@code if (mePanel.isMe()) return mePanel.mouseScrolled(delta);}），
     * 而 {@code GuiMekanism#mouseScrolled} 只把滚轮交给<b>最上层窗口</b>，于是鼠标停在玩家背包上滚
     * 也会翻配方页。这里加了矩形判定：面板外滚轮照旧落到主界面，面板内行为与旧手绘版一致。</p>
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (inPanel(mouseX, mouseY)) {
            return panel.mouseScrolled(delta);
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
}
