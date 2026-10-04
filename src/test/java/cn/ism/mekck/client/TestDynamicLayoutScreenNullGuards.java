package cn.ism.mekck.client;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M34 的<b>源码形态</b>护栏：动态布局菜单对应的两个屏幕在空菜单（客户端 BE 为 null）下不得崩。
 *
 * <h3>这个缺陷为什么必须用源码形态钉</h3>
 * M34 已给 {@code IceFactoryMenu} / {@code SimpleMachineMenu} 的客户端构造器与菜单内读取路径
 * 加了 {@code machine == null} 兜底（空菜单），但对应屏幕仍可能无条件解引用 BE：
 * {@code SimpleMachineScreen} 的 {@code menu.getMachine()}（清空按钮参数、本机下单数据源），
 * 以及 {@code IceFactoryScreen} 的 {@code menu.getTier()}（空菜单下返回 null）。
 * 触发条件不变：服务端发出 {@code ClientboundOpenScreenPacket} 之后、客户端处理它之前，
 * 方块被破坏/替换或区块卸载 ⇒ 客户端 {@code getBlockEntity} 返回 null。
 * vanilla {@code MenuScreens.ScreenConstructor} 先 {@code createMenu} 再 {@code setScreen}
 * （反编译核实）⇒ 屏幕一定会被创建，于是崩溃点从构造器挪到首帧渲染，客户端仍然会崩。
 *
 * <p>屏幕是客户端渲染类，单测里起不了 Minecraft 运行时，所以只能钉源码形态；
 * 每条断言都要求真的匹配到东西（找不到方法/调用点即红），避免判据空转。</p>
 *
 * <h3>判据（对含 {@code menu.getMachine()} 的方法）</h3>
 * <ol>
 *   <li>不得出现链式解引用 {@code menu.getMachine().xxx}（原缺陷形态）；</li>
 *   <li>每个调用点都必须写成 {@code var machine = menu.getMachine();} 的提取形态；</li>
 *   <li>每次提取之后（同一方法体内、下一次提取之前）必须出现 {@code machine == null}
 *       或 {@code machine != null} 的判空分支。</li>
 * </ol>
 */
public class TestDynamicLayoutScreenNullGuards {

    private static final String SIMPLE_SCREEN = "src/main/java/cn/ism/mekck/client/SimpleMachineScreen.java";
    private static final String ICE_SCREEN = "src/main/java/cn/ism/mekck/client/IceFactoryScreen.java";

    /** 提取形态：所有调用点都必须先落到这个局部变量，再判空。 */
    private static final String EXTRACTION = "var machine = menu.getMachine();";

    @Test
    public void simpleMachineScreenGuardsEveryGetMachine() throws IOException {
        assertGuarded(SIMPLE_SCREEN,
                "protected void addGuiElements()",
                "private NetworkOrderPanel.LocalSource localOrderSource()");
    }

    /**
     * 制冰工厂屏幕没有 {@code menu.getMachine()} 调用点，但 {@code menu.getTier()} 在空菜单下返回 null：
     * 唯一解引用点必须判空，否则首帧渲染 NPE。
     */
    @Test
    public void iceFactoryScreenGuardsMachineDependentReads() throws IOException {
        String src = TestSourceText.read(ICE_SCREEN);
        assertFalse(ICE_SCREEN + "：整份源码不得链式解引用 menu.getMachine().xxx", src.contains("menu.getMachine()."));
        assertEquals(ICE_SCREEN + "：tier.energyPerTick 只应出现在判空表达式里", 1, count(src, "tier.energyPerTick"));
        assertTrue(ICE_SCREEN + "：tier 解引用必须判空（tier == null ? 0 : tier.energyPerTick）",
                src.contains("tier == null ? 0 : tier.energyPerTick"));
    }

    /**
     * 对每个方法体断言三条判据（见类注释）。找不到方法体或方法体内没有调用点都直接红，
     * 避免判据因重构改名而空转。
     */
    private static void assertGuarded(String path, String... signatures) throws IOException {
        String src = TestSourceText.read(path);
        // 文件级兜底：新增方法若漏判空、直接链式解引用，也会在这里红。
        assertFalse(path + "：整份源码不得链式解引用 menu.getMachine().xxx", src.contains("menu.getMachine()."));
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
