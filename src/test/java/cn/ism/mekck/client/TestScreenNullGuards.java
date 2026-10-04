package cn.ism.mekck.client;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M30 的<b>源码形态</b>护栏：中央厨房 / 三明治组装机的屏幕与窗口对 {@code menu.getMachine()} 判空。
 *
 * <h3>这个缺陷为什么必须用源码形态钉</h3>
 * M25 已给 {@code CentralKitchenMenu} / {@code SandwichAssemblerMenu} 的客户端构造器与菜单内
 * 读取路径加了 {@code machine == null} 兜底（空菜单），但对应屏幕/窗口仍无条件
 * {@code menu.getMachine().xxx}。触发条件不变：服务端发出 {@code ClientboundOpenScreenPacket}
 * 之后、客户端处理它之前，方块被破坏/替换或区块卸载 ⇒ 客户端 {@code getBlockEntity} 返回 null。
 * vanilla {@code MenuScreens.ScreenConstructor} 先 {@code createMenu} 再 {@code setScreen}
 * （反编译核实）⇒ 屏幕一定会被创建，于是崩溃点从构造器挪到首帧渲染，客户端仍然会崩。
 *
 * <p>屏幕/窗口是客户端渲染类，单测里起不了 Minecraft 运行时，所以只能钉源码形态；
 * 每条断言都要求真的匹配到东西（找不到方法/调用点即红），避免判据空转。</p>
 *
 * <h3>判据（对四个文件里每个含 {@code menu.getMachine()} 的方法）</h3>
 * <ol>
 *   <li>不得出现链式解引用 {@code menu.getMachine().xxx}（原缺陷形态）；</li>
 *   <li>每个调用点都必须写成 {@code var machine = menu.getMachine();} 的提取形态；</li>
 *   <li>每次提取之后（同一方法体内、下一次提取之前）必须出现 {@code machine == null}
 *       或 {@code machine != null} 的判空分支 —— 空菜单渲染空槽、跳过依赖 BE 的绘制与发包。</li>
 * </ol>
 */
public class TestScreenNullGuards {

    private static final String CENTRAL_SCREEN =
            "src/main/java/cn/ism/mekck/client/CentralKitchenScreen.java";
    private static final String ASSEMBLER_SCREEN =
            "src/main/java/cn/ism/mekck/client/SandwichAssemblerScreen.java";
    private static final String ORDER_WINDOW =
            "src/main/java/cn/ism/mekck/client/KitchenOrderWindow.java";
    private static final String MODULE_WINDOW =
            "src/main/java/cn/ism/mekck/client/KitchenModuleWindow.java";

    /** 提取形态：所有调用点都必须先落到这个局部变量，再判空。 */
    private static final String EXTRACTION = "var machine = menu.getMachine();";

    @Test
    public void centralKitchenScreenGuardsEveryGetMachine() throws IOException {
        assertGuarded(CENTRAL_SCREEN,
                "private mekanism.client.gui.element.button.MekanismButton sortButton(String modeKey)",
                "protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY)",
                "protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY)",
                "public boolean mouseScrolled(double mouseX, double mouseY, double delta)",
                "private void openSideConfigWindow()",
                "private void pushSearch()");
    }

    @Test
    public void sandwichAssemblerScreenGuardsEveryGetMachine() throws IOException {
        assertGuarded(ASSEMBLER_SCREEN,
                "private void sendMode(int mode)",
                "private void sendCount(int delta)",
                "protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY)",
                "protected void drawForegroundText(GuiGraphics guiGraphics, int mouseX, int mouseY)");
    }

    @Test
    public void kitchenOrderWindowGuardsEveryGetMachine() throws IOException {
        assertGuarded(ORDER_WINDOW,
                "private void refresh()",
                "private String labelOf(Recipe<?> recipe)",
                "public void renderForeground(GuiGraphics guiGraphics, int mouseX, int mouseY)",
                "private void sendOrder(byte mode)",
                "public mekanism.client.gui.element.GuiElement mouseClickedNested(double mouseX, double mouseY, int button)");
    }

    @Test
    public void kitchenModuleWindowGuardsEveryGetMachine() throws IOException {
        assertGuarded(MODULE_WINDOW,
                "public KitchenModuleWindow(IGuiWrapper gui, CentralKitchenMenu menu)",
                "private String installedStatus(KitchenFamily family)",
                "private void sendFilter(byte action, KitchenFamily family, int index,");
    }

    /**
     * 对每个方法体断言三条判据（见类注释）。找不到方法体或方法体内没有调用点都直接红，
     * 避免判据因重构改名而空转。
     */
    private static void assertGuarded(String path, String... signatures) throws IOException {
        String src = TestSourceText.read(path);
        // 文件级兜底：新增方法若漏判空、直接链式解引用，也会在这里红。
        assertFalse(path + "：整份源码不得链式解引用 menu.getMachine().xxx",
                src.contains("menu.getMachine()."));
        for (String signature : signatures) {
            String body = TestSourceText.methodBody(src, signature);
            assertFalse(path + "：找不到方法 " + signature + "（判据失配，需同步更新本护栏）", body.isEmpty());
            int calls = count(body, "menu.getMachine()");
            assertTrue(path + "：" + signature + " 预期至少一处 menu.getMachine() 调用点（判据失配）", calls > 0);
            assertEquals(path + "：" + signature + " 的每个 menu.getMachine() 调用点都必须写成 " + EXTRACTION,
                    calls, count(body, EXTRACTION));
            int from = 0;
            while (true) {
                int at = body.indexOf(EXTRACTION, from);
                if (at < 0) {
                    break;
                }
                int next = body.indexOf(EXTRACTION, at + EXTRACTION.length());
                String segment = body.substring(at + EXTRACTION.length(), next < 0 ? body.length() : next);
                assertTrue(path + "：" + signature + " 的提取点之后必须判空（machine == null / machine != null）",
                        segment.contains("machine == null") || segment.contains("machine != null"));
                from = at + EXTRACTION.length();
            }
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
