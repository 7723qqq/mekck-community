package cn.ism.mekck.client;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「GUI 相对坐标」护栏 —— 命中侧换算 + 绘制侧同口径。
 *
 * <h3>为什么值得单独守</h3>
 * Mek 的框架约定（javap 实测 10.4.6.20）：
 * <ul>
 *   <li>{@code GuiMekanism.mouseClicked} 把<b>绝对</b> mouseX/mouseY 原样下发给
 *       {@code GuiWindow.mouseClickedNested}；</li>
 *   <li>{@code GuiMekanism.renderLabels} 在 {@code translate(leftPos, topPos, 300)} 之后渲染窗口，
 *       窗口的 {@code renderForeground} 里绘制用的是 <b>GUI 相对</b>坐标。</li>
 * </ul>
 * 于是「绘制用 GUI 相对、命中用绝对」是本框架的既定分工 —— 命中侧忘了换算，
 * 面板/按钮就会「看得见的位置点不动、偏移 (leftPos, topPos) 的位置反而响应」。
 * 三个 GuiWindow（ME 下单 / 厨房下单 / 模块）都栽过这个坑（M4-1）。
 *
 * <h3>两条断言</h3>
 * <ol>
 *   <li><b>换算关系</b>：{@link NetworkOrderPanel#hitsRelativeRect} 的纯函数语义
 *       （绝对鼠标 − GUI 原点 与 相对矩形比较）；</li>
 *   <li><b>调用点</b>：三个窗口的命中方法里必须出现换算（{@code getGuiLeft()/getGuiTop()}），
 *       且不得把原始 mouseX/mouseY 直接交给面板；绘制侧（{@code drawForegroundText}、
 *       窗口内 tooltip）同样不得再叠加 leftPos/topPos。</li>
 * </ol>
 *
 * <h3>为什么用源码形态而不是跑起来量</h3>
 * 这类问题<b>编译通过、测试全绿、只在玩家真的打开界面时看得见</b>，
 * 而本仓的护栏全是裸 JVM 里跑的静态断言。代价是判据只能看结构，
 * 所以 {@link #criteriaStillMatchSomething} 与变异测试是它的必要配套。
 */
public class TestWindowHitTest {

    private static final String NETWORK_ORDER_WINDOW = "src/main/java/cn/ism/mekck/client/NetworkOrderWindow.java";
    private static final String KITCHEN_ORDER_WINDOW = "src/main/java/cn/ism/mekck/client/KitchenOrderWindow.java";
    private static final String KITCHEN_MODULE_WINDOW = "src/main/java/cn/ism/mekck/client/KitchenModuleWindow.java";
    private static final String NETWORK_ORDER_PANEL = "src/main/java/cn/ism/mekck/client/NetworkOrderPanel.java";
    private static final String SANDWICH_SCREEN = "src/main/java/cn/ism/mekck/client/SandwichAssemblerScreen.java";

    // ── 1. 换算关系（纯函数）────────────────────────────────────────────

    /**
     * 命中判定必须先把绝对鼠标换算成 GUI 相对，再与相对矩形比较。
     *
     * <p>「未换算的绝对鼠标」这一条就是 M4-1 的缺陷形态：面板画在 GUI 相对 (10,20)，
     * 而鼠标在屏幕上的绝对坐标是 (guiLeft+10, guiTop+20) —— 拿后者直接比相对矩形，
     * 只有 guiLeft/guiTop 恰为 0 时才碰巧命中。</p>
     */
    @Test
    public void hitTestConvertsAbsoluteMouseToGuiRelative() {
        int guiLeft = 320;
        int guiTop = 48;
        int relX = 10;
        int relY = 20;
        int w = 30;
        int h = 40;

        assertTrue("面板内的绝对鼠标必须命中",
                NetworkOrderPanel.hitsRelativeRect(guiLeft + relX + 1, guiTop + relY + 1,
                        guiLeft, guiTop, relX, relY, w, h));
        assertFalse("未换算的绝对鼠标（= 相对坐标本身）不得命中 —— 这正是 M4-1 的缺陷形态",
                NetworkOrderPanel.hitsRelativeRect(relX + 1, relY + 1, guiLeft, guiTop, relX, relY, w, h));
        assertTrue("左上角含",
                NetworkOrderPanel.hitsRelativeRect(guiLeft + relX, guiTop + relY,
                        guiLeft, guiTop, relX, relY, w, h));
        assertFalse("左边界外 1px 不含",
                NetworkOrderPanel.hitsRelativeRect(guiLeft + relX - 1, guiTop + relY,
                        guiLeft, guiTop, relX, relY, w, h));
        assertFalse("右边界（半开）不含",
                NetworkOrderPanel.hitsRelativeRect(guiLeft + relX + w, guiTop + relY,
                        guiLeft, guiTop, relX, relY, w, h));
        assertFalse("下边界（半开）不含",
                NetworkOrderPanel.hitsRelativeRect(guiLeft + relX, guiTop + relY + h,
                        guiLeft, guiTop, relX, relY, w, h));
        assertTrue("GUI 原点为 0 时退化为直接比较",
                NetworkOrderPanel.hitsRelativeRect(relX, relY, 0, 0, relX, relY, w, h));
    }

    // ── 2. 调用点：命中侧必须换算 ──────────────────────────────────────

    /**
     * 三个窗口的 {@code mouseClickedNested} 必须出现换算，且不得把原始鼠标交给面板。
     *
     * <p>「不得把原始鼠标交给面板」用 {@code mouseClicked(mouseX, mouseY} 这个形态钉：
     * 面板内部按 GUI 相对坐标命中，收到绝对坐标就会整体偏移。</p>
     */
    @Test
    public void windowHitTestsConvertMouseToGuiRelative() throws IOException {
        for (String path : List.of(NETWORK_ORDER_WINDOW, KITCHEN_ORDER_WINDOW, KITCHEN_MODULE_WINDOW)) {
            String src = TestSourceText.read(path);
            String clicked = TestSourceText.methodBody(src, "mouseClickedNested");
            assertTrue(path + "：mouseClickedNested 找不到（判据失配）", !clicked.isEmpty());
            assertTrue(path + "：命中判定没有把绝对鼠标换算成 GUI 相对（缺 getGuiLeft/getGuiTop）",
                    clicked.contains("getGuiLeft()") && clicked.contains("getGuiTop()"));
            assertFalse(path + "：面板点击仍收到未换算的绝对鼠标（mouseClicked(mouseX, mouseY …)）",
                    clicked.contains("mouseClicked(mouseX, mouseY"));
        }
    }

    /**
     * 窗口的悬停判定（{@code renderForeground}）同样要换算 —— 面板内部的
     * {@code isHovered} 与搜索框 {@code EditBox} 都按 GUI 相对坐标比较。
     */
    @Test
    public void windowHoverChecksConvertMouseToGuiRelative() throws IOException {
        for (String path : List.of(NETWORK_ORDER_WINDOW, KITCHEN_ORDER_WINDOW)) {
            String src = TestSourceText.read(path);
            String fg = TestSourceText.methodBody(src, "renderForeground");
            assertTrue(path + "：renderForeground 找不到（判据失配）", !fg.isEmpty());
            assertTrue(path + "：悬停判定没有换算鼠标坐标（缺 getGuiLeft/getGuiTop）",
                    fg.contains("getGuiLeft()") && fg.contains("getGuiTop()"));
        }
    }

    // ── 3. 绘制侧同口径 ────────────────────────────────────────────────

    /**
     * 面板 tooltip 必须先撤销 (leftPos, topPos) 平移再渲染。
     *
     * <p>{@code renderForeground} 跑在 {@code translate(leftPos, topPos, 300)} 之后的 pose 里，
     * 而 {@code GuiGraphics.renderTooltip} 的坐标是绝对屏幕坐标、按当前 pose 绘制 ——
     * 不撤销就会整体偏移 (leftPos, topPos)（M4-5）。</p>
     */
    @Test
    public void panelTooltipIsRenderedAtScreenOrigin() throws IOException {
        String src = TestSourceText.read(NETWORK_ORDER_PANEL);
        String render = TestSourceText.methodBody(src, "public void render(GuiGraphics");
        assertTrue("NetworkOrderPanel.render 找不到（判据失配）", !render.isEmpty());
        assertTrue("面板 tooltip 必须走撤销 (leftPos, topPos) 平移的通道（renderTooltipAtScreenOrigin）",
                render.contains("renderTooltipAtScreenOrigin("));
        String helper = TestSourceText.methodBody(src, "private static void renderTooltipAtScreenOrigin");
        assertTrue("tooltip 通道找不到（判据失配）", !helper.isEmpty());
        assertTrue("tooltip 通道没有 translate(-guiLeft, -guiTop) —— tooltip 会整体偏移 (leftPos, topPos)",
                helper.contains("translate(-guiLeft, -guiTop"));
        assertTrue("tooltip 通道没有真正渲染 tooltip", helper.contains("renderTooltip("));
    }

    /**
     * {@code drawForegroundText} 跑在 GUI 相对空间，不得再叠加 leftPos/topPos。
     *
     * <p>三明治组装机的数量文本曾写成 {@code x + MODE_BTN_X + 24}（x = leftPos）⇒
     * 画到面板右缘之外（M4-4）。</p>
     */
    @Test
    public void foregroundTextDrawsInGuiRelativeSpace() throws IOException {
        String src = TestSourceText.read(SANDWICH_SCREEN);
        String fg = TestSourceText.methodBody(src, "protected void drawForegroundText");
        assertTrue("SandwichAssemblerScreen.drawForegroundText 找不到（判据失配）", !fg.isEmpty());
        assertFalse("drawForegroundText 跑在 translate(leftPos, topPos) 之后的 pose 里，"
                        + "不能再叠加 leftPos/topPos（数量文本会画到面板外）",
                fg.contains("leftPos") || fg.contains("topPos"));
    }

    // ── 4. 判据不许空转 ────────────────────────────────────────────────

    /** 确认判据在本仓确实还能匹配到东西（改名/失配会让上面几条变成永远绿的摆设）。 */
    @Test
    public void criteriaStillMatchSomething() throws IOException {
        for (String path : List.of(NETWORK_ORDER_WINDOW, KITCHEN_ORDER_WINDOW, KITCHEN_MODULE_WINDOW)) {
            String src = TestSourceText.read(path);
            assertFalse(path + "：mouseClickedNested 判据失配",
                    TestSourceText.methodBody(src, "mouseClickedNested").isEmpty());
        }
        String panel = TestSourceText.read(NETWORK_ORDER_PANEL);
        assertFalse("NetworkOrderPanel.render 判据失配",
                TestSourceText.methodBody(panel, "public void render(GuiGraphics").isEmpty());
        assertFalse("tooltip 通道判据失配",
                TestSourceText.methodBody(panel, "private static void renderTooltipAtScreenOrigin").isEmpty());
    }
}
