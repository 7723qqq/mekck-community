package cn.ism.mekck.client;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * GUI 的<b>两行字</b>护栏：机器名与「Inventory」标签。
 *
 * <h3>为什么这两件事值得单独守</h3>
 * Mek 的 {@code GuiMekanism.renderLabels} 覆写了原版
 * {@code AbstractContainerScreen.renderLabels} 且<b>不调 super</b>，而
 * {@code GuiConfigurableTile} / {@code GuiMekanismTile} 都<b>没有</b>覆写
 * {@code drawForegroundText}（实测其方法体就是 {@code return}）。
 * 换句话说：**不自己覆写 {@code drawForegroundText} 的屏，这两行字一个都不画。**
 *
 * <p>本仓已经在这两件事上各栽了一次：</p>
 * <ul>
 *   <li><b>缺失</b>：{@code UniversalCuttingMachineScreen} 直接继承
 *       {@code GuiConfigurableTile} 且不覆写 ⇒ 机器名与背包标签<b>都不显示</b>。
 *       它的 {@code inventoryLabelY} 被设成了 84，却<b>没有任何代码读它</b> ——
 *       典型的「设了值就以为画了」。</li>
 *   <li><b>压槽</b>：{@code GrillScreen} 把 {@code inventoryLabelY} 写成 <b>84</b>，
 *       而玩家背包首行就是 84（Mek 的 {@code BASE_Y_OFFSET}，本菜单不覆写）⇒
 *       标签画在第一行背包槽的正上方。原注释把「标签在背包上方 12px」当成要修的
 *       问题，方向正好反了 —— 那 12px 恰恰是正确的间距。</li>
 * </ul>
 *
 * <h3>三条断言</h3>
 * <ol>
 *   <li><b>该画字的屏必须真的覆写</b>（或继承一个已覆写的屏基类）；</li>
 *   <li><b>标签不得压住背包首行</b>：{@code inventoryLabelY + 标签高 ≤ 背包首行}；</li>
 *   <li>判据不许空转。</li>
 * </ol>
 *
 * <h3>为什么用源码形态而不是跑起来量</h3>
 * 这类问题<b>编译通过、测试全绿、只在玩家真的打开界面时看得见</b>，
 * 而本仓的护栏全是裸 JVM 里跑的静态断言。代价是判据只能看结构，
 * 所以 {@link #criteriaStillMatchSomething} 与变异测试是它的必要配套。
 */
public class TestGuiInventoryLabels {

    private static final Path CLIENT = Path.of("src/main/java/cn/ism/mekck/client");

    /** 标签一行文字的高度（MC 默认字体 9px）。 */
    private static final int LABEL_HEIGHT = 9;
    /** 判据要认的屏基类：它们已经代为画好了这两行字。 */
    private static final List<String> BASES_THAT_DRAW = List.of("MekCkFactoryScreenBase");
    /** 判据要认的屏基类：{@code GuiMekanism} 家族自己会画（逐个覆写也接受）。 */
    private static final String GUI_MEKANISM = "GuiMekanism";

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static List<Path> screens() throws IOException {
        try (var s = Files.list(CLIENT)) {
            return s.filter(p -> p.getFileName().toString().endsWith("Screen.java")).sorted().toList();
        }
    }

    /** 取出 {@code inventoryLabelY = <表达式>;} 的所有赋值表达式（可能是常量名）。 */
    private static List<String> labelAssignments(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("inventoryLabelY\\s*=\\s*([^;]+);").matcher(source);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    // ── 1. 该画字的屏必须真的画 ────────────────────────────────────────

    /**
     * 每个 Screen 都必须覆写 {@code drawForegroundText}，或继承一个已经代画的基类。
     *
     * <p>不豁免 {@code GuiMekanism} 家族：它们逐个覆写，本仓没有例外，
     * 一旦某个新屏忘了，这条会立刻指出是谁。</p>
     */
    @Test
    public void everyScreenDrawsItsTitleAndInventoryLabel() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : screens()) {
            String source = read(file);
            String name = file.getFileName().toString();
            if (source.contains("protected void drawForegroundText")) {
                continue;
            }
            // ⚠️ 只认**真正的 extends 子类名**，不能拿「全文出现过这个名字」当判据 ——
            // 变异测试实测：把本方法改名后这条断言仍然是绿的，因为该文件的 javadoc 里
            // 引用了 {@code MekCkFactoryScreenBase}（解释「六个工厂屏由它统一补上」），
            // 全文子串匹配把这处**注释**当成了继承关系。
            String superclass = superclassOf(source);
            if (BASES_THAT_DRAW.stream().anyMatch(superclass::contains)) {
                continue;
            }
            offenders.add(name + "（继承 " + superclass + " 且未覆写 drawForegroundText）");
        }
        assertEquals("这些屏不会画机器名与「Inventory」标签 —— Mek 的 GuiMekanism.renderLabels "
                        + "不调 super，而 GuiConfigurableTile/GuiMekanismTile 的 drawForegroundText "
                        + "是空方法体。补一个覆写：renderTitleText + drawString(playerInventoryTitle, …)：\n  "
                        + String.join("\n  ", offenders),
                List.of(), offenders);
    }

    private static String superclassOf(String source) {
        Matcher m = Pattern.compile("class\\s+\\w+\\s+extends\\s+([\\w<>,\\s\\.]+)").matcher(source);
        return m.find() ? m.group(1).trim().replaceAll("\\s+", " ") : "?";
    }

    // ── 2. 标签不得压住背包首行 ────────────────────────────────────────

    /**
     * {@code inventoryLabelY} 必须在玩家背包首行<b>之上</b>，且留得下一行字高。
     *
     * <p>判据只覆盖<b>字面量</b>赋值；引用 {@code MekCkFactoryLayout.INVENTORY_LABEL_Y}
     * 这类常量的写法由 {@link #labelConstantsResolveAboveTheInventory} 单独钉。
     * 两者合起来覆盖了本仓现有的全部写法。</p>
     */
    @Test
    public void literalLabelYDoesNotOverlapTheInventory() throws IOException {
        // 这些菜单继承 MekanismTileContainer 且**不覆写** getInventoryYOffset() ⇒ 背包首行 = 84
        final int mekDefaultInventoryY = 84;

        List<String> offenders = new ArrayList<>();
        for (Path file : screens()) {
            String source = read(file);
            if (!source.contains("getInventoryYOffset")) {
                continue;   // 该屏的菜单不是 Mek 容器，背包由菜单自己摆，判据不适用
            }
            for (String expr : labelAssignments(source)) {
                Matcher num = Pattern.compile("^-?\\d+$").matcher(expr);
                if (!num.matches()) {
                    continue;   // 常量 / 表达式，交给另一条断言
                }
                int y = Integer.parseInt(expr);
                if (y + LABEL_HEIGHT > mekDefaultInventoryY) {
                    offenders.add(file.getFileName() + " → inventoryLabelY = " + y
                            + "（背包首行 " + mekDefaultInventoryY + "，标签底边 " + (y + LABEL_HEIGHT)
                            + " 已压到槽位上）");
                }
            }
        }
        assertEquals("这些屏把「Inventory」标签画在玩家背包首行上：\n  " + String.join("\n  ", offenders),
                List.of(), offenders);
    }

    /**
     * {@code MekCkFactoryLayout.INVENTORY_LABEL_Y} 必须真的在背包首行之上。
     *
     * <p>这条守住的是「引用常量的写法」——它把具体数字藏在布局类里，
     * 源码断言看不到数字，只能钉住这个常量本身的几何关系。</p>
     */
    @Test
    public void labelConstantsResolveAboveTheInventory() throws IOException {
        String layout = read(Path.of("src/main/java/cn/ism/mekck/menu/MekCkFactoryLayout.java"));

        // 逐个符号解析，而不是写死数字：判据要钉的是**三者之间的关系**，
        // 写死 84/10 的话有人改了常量里的表达式它就静默失配。
        int inventoryY = intConstant(layout, "MEK_DEFAULT_INVENTORY_Y");
        int gap = intConstant(layout, "LABEL_ABOVE_INVENTORY");

        Matcher label = Pattern.compile(
                "INVENTORY_LABEL_Y\\s*=\\s*MEK_DEFAULT_INVENTORY_Y\\s*-\\s*(\\w+)").matcher(layout);
        assertTrue("布局类里找不到 INVENTORY_LABEL_Y 的定义式，判据可能失配", label.find());
        assertEquals("INVENTORY_LABEL_Y 减的应当就是 LABEL_ABOVE_INVENTORY",
                "LABEL_ABOVE_INVENTORY", label.group(1));

        assertTrue("Mek 的 BASE_Y_OFFSET 实测是 84，写错会让所有引用它的屏整体错位",
                inventoryY == 84);
        assertTrue("标签必须留出至少一行字高（" + LABEL_HEIGHT + "px）才不压到背包槽，"
                + "当前 MEK_DEFAULT_INVENTORY_Y(" + inventoryY + ") - LABEL_ABOVE_INVENTORY(" + gap + ")",
                gap >= LABEL_HEIGHT);
    }

    /** 从布局类里取一个 {@code public static final int NAME = <数字>;} 的值。 */
    private static int intConstant(String source, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(\\d+)").matcher(source);
        assertTrue("布局类里找不到常量 " + name + " 的字面量定义，判据可能失配", m.find());
        return Integer.parseInt(m.group(1));
    }

    // ── 3. 判据不许空转 ────────────────────────────────────────────────

    /**
     * 确认判据在本仓<b>确实还能匹配到东西</b>。
     *
     * <p>不这么做的话，某个屏改名 / 某条正则失配会让上面两条断言一起变成永远绿的摆设 ——
     * 而它们看起来在守着两行字。</p>
     */
    @Test
    public void criteriaStillMatchSomething() throws IOException {
        int screenCount = screens().size();
        assertTrue("没扫到任何 Screen，判据失效了", screenCount >= 15);

        int withLabel = 0;
        int withOverride = 0;
        for (Path file : screens()) {
            String source = read(file);
            if (!labelAssignments(source).isEmpty()) {
                withLabel++;
            }
            if (source.contains("protected void drawForegroundText")) {
                withOverride++;
            }
        }
        assertTrue("一个 inventoryLabelY 都没扫到，判据失效了", withLabel >= 10);
        assertTrue("一个 drawForegroundText 覆写都没扫到，判据失效了", withOverride >= 10);
    }
}
