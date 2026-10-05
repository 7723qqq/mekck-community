package cn.ism.mekck.client;

import cn.ism.mekck.TestSourceText;
import cn.ism.mekck.menu.MekCkFactoryLayout;
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
 * <h3>四条断言</h3>
 * <ol>
 *   <li><b>该画字的屏必须真的覆写</b>（或继承一个已覆写的屏基类）；</li>
 *   <li><b>标签不得压住背包首行</b>：{@code inventoryLabelY + 标签高 ≤ 背包首行}
 *       —— 背包首行按<b>屏幕的菜单类型</b>解析（Mek 容器不覆写 = 84；覆写 = 解析返回值），
 *       不再拿「源码里出现过 getInventoryYOffset」当代理（M4-10）；</li>
 *   <li><b>烹饪面板的流体条不得压背包与标签</b>（纯函数：条底 ≤ 标签 ≤ 背包首行）；</li>
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
    // 会把这两行字画出来的屏基类。加了 MekCkContainerScreenBase 之后：
    // 研磨机屏不再自己覆写 drawForegroundText，而是继承它 —— 判据必须认继承，
    // 否则「正确的复用」会被判成「漏画」。
    private static final List<String> BASES_THAT_DRAW =
            List.of("MekCkContainerScreenBase", "MekCkFactoryScreenBase");
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
     * <p>判据<b>不再</b>用「屏幕源码里出现过 getInventoryYOffset」当代理（M4-10：
     * {@code WineCellarScreen} 不含该串 ⇒ 被跳过，而它的菜单恰恰是 Mek 容器且不覆写）。
     * 改为解析屏幕的菜单类型：</p>
     * <ul>
     *   <li>菜单 {@code extends MekanismTileContainer} 且<b>不覆写</b>
     *       {@code getInventoryYOffset()} ⇒ 背包首行 = Mek 默认 {@code BASE_Y_OFFSET}；</li>
     *   <li>覆写 ⇒ 解析覆写返回的常量（如 {@code return INV_TOP;}）；</li>
     *   <li>自定义 {@code AbstractContainerMenu} ⇒ 背包由菜单自己摆，判据不适用（跳过）。</li>
     * </ul>
     * <p>表达式型 {@code inventoryLabelY} 也做常量解析（{@code XxxMenu.INV_TOP - 9}、
     * 本文件常量 ± 偏移）；解析不出的（如工厂布局公式）交给
     * {@link #labelConstantsResolveAboveTheInventory} 与布局类自己的断言。</p>
     */
    @Test
    public void labelYDoesNotOverlapTheInventory() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> checkedScreens = new ArrayList<>();
        for (Path file : screens()) {
            String source = read(file);
            List<String> exprs = labelAssignments(source);
            if (exprs.isEmpty()) {
                continue;
            }
            String menu = menuTypeOf(source);
            if (menu == null) {
                continue;
            }
            Path menuFile = Path.of("src/main/java/cn/ism/mekck/menu", menu + ".java");
            if (!Files.isRegularFile(menuFile)) {
                continue;
            }
            String menuSource = read(menuFile);
            if (!menuSource.contains("extends MekanismTileContainer")) {
                continue;   // 自定义菜单：背包由菜单自己摆，判据不适用
            }
            Integer inventoryTop = menuInventoryTop(menuSource);
            if (inventoryTop == null) {
                continue;   // 覆写值解析不出来（如工厂布局公式），交给布局类断言
            }
            for (String expr : exprs) {
                Integer label = resolveLabel(source, menuSource, expr);
                if (label == null) {
                    continue;
                }
                checkedScreens.add(file.getFileName().toString());
                if (label + LABEL_HEIGHT > inventoryTop) {
                    offenders.add(file.getFileName() + " → inventoryLabelY = " + expr
                            + "（解析为 " + label + "，标签底边 " + (label + LABEL_HEIGHT)
                            + " 压住背包首行 " + inventoryTop + "）");
                }
            }
        }
        assertTrue("WineCellarScreen 必须被这条判据覆盖（M4-10 的漏屏就是它）",
                checkedScreens.contains("WineCellarScreen.java"));
        assertEquals("这些屏把「Inventory」标签画在玩家背包首行上：\n  " + String.join("\n  ", offenders),
                List.of(), offenders);
    }

    /** 解析屏幕的菜单类型：{@code extends X<..., YyyMenu>} 里最后一个以 Menu 结尾的类型参数。 */
    private static String menuTypeOf(String source) {
        Matcher m = Pattern.compile("class\\s+\\w+\\s+extends\\s+[\\w.]+\\s*<([^<>]*)>").matcher(source);
        if (!m.find()) {
            return null;
        }
        String[] args = m.group(1).split(",");
        for (int i = args.length - 1; i >= 0; i--) {
            String arg = args[i].trim();
            if (arg.endsWith("Menu")) {
                return arg;
            }
        }
        return null;
    }

    /**
     * 菜单的玩家背包首行：不覆写 {@code getInventoryYOffset()} ⇒ Mek 默认
     * {@code BASE_Y_OFFSET}；覆写 ⇒ 解析 {@code return <常量>;}；解析不出返回 null。
     */
    private static Integer menuInventoryTop(String menuSource) {
        if (!menuSource.contains("protected int getInventoryYOffset")) {
            return MekCkFactoryLayout.MEK_DEFAULT_INVENTORY_Y;
        }
        String body = TestSourceText.methodBody(menuSource, "protected int getInventoryYOffset");
        Matcher m = Pattern.compile("return\\s+([^;]+);").matcher(body);
        if (!m.find()) {
            return null;
        }
        return resolveNamedConstant(menuSource, null, m.group(1).trim());
    }

    /** 解析 {@code inventoryLabelY} 表达式：字面量 / 常量 / 常量 ± 数字。 */
    private static Integer resolveLabel(String screenSource, String menuSource, String expr) {
        String e = expr.trim();
        Matcher m = Pattern.compile("([\\w.]+)\\s*([+-])\\s*(\\d+)").matcher(e);
        if (m.matches()) {
            Integer base = resolveNamedConstant(screenSource, menuSource, m.group(1));
            if (base == null) {
                return null;
            }
            int delta = Integer.parseInt(m.group(3));
            return m.group(2).equals("-") ? base - delta : base + delta;
        }
        return resolveNamedConstant(screenSource, menuSource, e);
    }

    /**
     * 解析一个具名常量：字面量 / 本文件 {@code NAME = <数字>} / {@code XxxMenu.NAME}（菜单文件里）。
     * 其余（{@code MekCkFactoryLayout.INVENTORY_LABEL_Y} 这类表达式型常量）返回 null。
     */
    private static Integer resolveNamedConstant(String screenSource, String menuSource, String name) {
        if (name.matches("-?\\d+")) {
            return Integer.parseInt(name);
        }
        int dot = name.lastIndexOf('.');
        String owner = dot > 0 ? name.substring(0, dot) : null;
        String field = dot > 0 ? name.substring(dot + 1) : name;
        String source;
        if (owner == null) {
            source = screenSource;
        } else if (owner.endsWith("Menu") && menuSource != null) {
            source = menuSource;
        } else {
            return null;
        }
        Matcher m = Pattern.compile("\\b" + Pattern.quote(field) + "\\s*=\\s*(-?\\d+)").matcher(source);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
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

    // ── 3. 烹饪面板：流体条不得压背包与标签 ─────────────────────────────

    /**
     * 烹饪面板的 3 个流体条必须整块落在玩家背包与「Inventory」标签之上。
     *
     * <p>纯函数断言：条底 = {@code COOKING_FLUID_GAUGE_Y + COOKING_FLUID_GAUGE_H}；
     * 背包首行 = {@code inventoryYOffset(cookingImageHeight())}；标签 =
     * {@code inventoryLabelY(...)}。三者必须满足 条底 ≤ 标签 ≤ 背包首行。</p>
     */
    @Test
    public void cookingFluidGaugesClearTheInventory() {
        int gaugeBottom = MekCkFactoryLayout.COOKING_FLUID_GAUGE_Y + MekCkFactoryLayout.COOKING_FLUID_GAUGE_H;
        int panelHeight = MekCkFactoryLayout.cookingImageHeight();
        int inventoryTop = MekCkFactoryLayout.inventoryYOffset(panelHeight);
        int labelY = MekCkFactoryLayout.inventoryLabelY(panelHeight);
        assertTrue("流体条底边 " + gaugeBottom + " 压住玩家背包首行 " + inventoryTop
                        + "（面板高 " + panelHeight + "）",
                gaugeBottom <= inventoryTop);
        assertTrue("流体条底边 " + gaugeBottom + " 压住「Inventory」标签 " + labelY
                        + "（面板高 " + panelHeight + "）",
                gaugeBottom <= labelY);
    }

    /** 屏幕摆条用的 y 必须取自布局类常量 —— 否则「屏幕摆条、布局算高度」两处会漂移。 */
    @Test
    public void cookingScreenUsesTheSharedGaugeGeometry() throws IOException {
        String src = TestSourceText.read("src/main/java/cn/ism/mekck/client/CookingFactoryScreen.java");
        assertTrue("CookingFactoryScreen 的流体条 y 必须取自 MekCkFactoryLayout.COOKING_FLUID_GAUGE_Y",
                src.contains("MekCkFactoryLayout.COOKING_FLUID_GAUGE_Y"));
    }

    // ── 4. 判据不许空转 ────────────────────────────────────────────────

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
        int withMenu = 0;
        for (Path file : screens()) {
            String source = read(file);
            if (!labelAssignments(source).isEmpty()) {
                withLabel++;
            }
            if (source.contains("protected void drawForegroundText")) {
                withOverride++;
            }
            if (menuTypeOf(source) != null) {
                withMenu++;
            }
        }
        assertTrue("一个 inventoryLabelY 都没扫到，判据失效了", withLabel >= 10);
        assertTrue("一个 drawForegroundText 覆写都没扫到，判据失效了", withOverride >= 10);
        assertTrue("菜单类型解析失效了（一个都没认出来）", withMenu >= 15);
    }

    // ── 5. 槽位 widget：继承 Mek 容器屏的必须开 dynamicSlots ──────────────

    /**
     * <b>继承 Mek 容器屏基类的屏幕必须设 {@code dynamicSlots = true}。</b>
     *
     * <h3>缺陷形态（本轮实机踩到）</h3>
     * {@code GuiMekanism.addSlots()} 只在 {@code dynamicSlots} 为真时遍历
     * {@code menu.slots} 为每个槽建 widget。不设 ⇒ <b>机器槽一个都不显示</b>
     * （输入/输出/能源全空），而<b>背景贴图照常画出来</b> —— 玩家看到的是一个
     * 有背景、有标题、但里面空无一物的面板。
     *
     * <p>它的隐蔽之处在于：<b>编译通过、全部测试绿</b>。槽位 widget 的创建是纯运行期行为，
     * 而本仓的护栏全是静态断言 —— 所以在补本条之前，没有任何东西守这件事。</p>
     *
     * <p>判据：凡是 {@code extends GuiConfigurableTile} / {@code GuiMekanismTile}
     * （这两个基类的 addGuiElements 依赖 dynamicSlots 建槽）的屏，源码里必须出现
     * {@code dynamicSlots = true}。</p>
     */
    @Test
    public void mekContainerScreensEnableDynamicSlots() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path file : screens()) {
            // ⚠️ 必须用 TestSourceText.read（剥注释），**不能**用本文件的 read(Path)
            // —— 后者不剥注释，于是「javadoc 里解释了 dynamicSlots = true 是什么」
            // 会被当成「代码真的设了它」。本断言第一版实测漏网（变异测试：把
            // `dynamicSlots = true;` 那行删掉，它照旧全绿）。
            // 这是本仓第四次撞上同一形态：**判据不能用全文子串匹配**。
            String source = TestSourceText.read(file.toString());
            // 认三种基类：Mek 的两个容器屏基类，以及本仓的 MekCkContainerScreenBase
            //（它继承 GuiConfigurableTile 并在构造器里设 dynamicSlots，子类不必再写）。
            if (!source.contains("extends GuiConfigurableTile")
                    && !source.contains("extends GuiMekanismTile")
                    && !source.contains("extends MekCkContainerScreenBase")) {
                continue;
            }
            scanned++;
            // 继承 MekCkContainerScreenBase 的屏由基类构造器设 dynamicSlots，不必自己写。
            if (!source.contains("dynamicSlots = true")
                    && !source.contains("extends MekCkContainerScreenBase")) {
                offenders.add(file.getFileName().toString());
            }
        }
        // 阈值 = 本仓实际继承这两个基类的屏数（不是全部屏：多数屏直接 extends GuiMekanism，
        // 自己手建槽 widget，不受 dynamicSlots 影响）。新增此类屏时这个数要跟着涨。
        assertTrue("一个继承 Mek 容器屏的屏都没扫到，判据已失效（扫描面变了？）", scanned >= 3);
        assertEquals("这些屏继承 Mek 的容器屏基类却没开 dynamicSlots —— "
                        + "槽位 widget 一个都不会建，玩家看到的是空面板：\n  ",
                List.of(), offenders);
    }

    // ── 6. 不许重复实现共享基类已有的东西 ──────────────────────────────

    /**
     * <b>继承 {@code MekCkContainerScreenBase} 的屏，不得再自己实现基类已有的那两行字。</b>
     *
     * <h3>缺陷形态（本仓的结构性问题）</h3>
     * 此前那套基类绑死在 {@code MekCkMachineTile}（工厂）上，13 台无档位单机继承不了，
     * 于是每个单机屏只好把「机器名/背包标签」「竖直能源条」各自重写一遍
     * —— 实测有 15 个屏各写了一份 {@code drawForegroundText}。
     *
     * <p>本类把通用部分上移到 {@code MekCkContainerScreenBase} 之后，
     * 「继承它又自己再写一遍」就是纯重复，且会让两处实现再次漂移。
     * 本条守的就是这件事：<b>复用的入口开了，就不许再绕过去。</b></p>
     *
     * <h3>判据在 2026-10-06 从「一律不许覆写」改成「覆写必须只做追加」（落点变更）</h3>
     * <p>坚果爆炒机屏幕要在「Inventory」同一行的右端画机身温度 ——
     * 而 {@code MekCkContainerScreenBase.drawForegroundText} 的 javadoc 与
     * 本条原判据的报错文案<b>都</b>写着「若确需追加读数，覆写并先调 super」。
     * 原来的实现（一律不许覆写）比它自己声明与基类文档都严，把文档允许的扩展点判成了违规。</p>
     *
     * <p>改后的判据仍然钉住原缺陷：</p>
     * <ol>
     *   <li>覆写的方法体<b>必须先调</b> {@code super.drawForegroundText(...)}
     *       —— 漏了它机器名与「Inventory」两行一个都不画（Mek 的 renderLabels 不调 super）；</li>
     *   <li>覆写里<b>不得</b>再出现 {@code renderTitleText(} 或 {@code playerInventoryTitle}
     *       —— 那就是「把基类那两行又抄了一遍」，正是本条要防的重复实现。</li>
     * </ol>
     * <p>对不覆写的屏（其余全部）判据与从前逐字一致，所以这不是放宽：它把
     * 「文档允许的追加」从不许变成本条从来没检查过的两种坏形态（漏 super / 抄一遍）。</p>
     */
    @Test
    public void screensExtendingTheSharedBaseDoNotReimplementIt() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path file : screens()) {
            String source = read(file);
            if (!source.contains("extends MekCkContainerScreenBase")) {
                continue;
            }
            scanned++;
            String name = file.getFileName().toString();
            if (source.contains("protected void drawForegroundText")) {
                String body = TestSourceText.methodBody(source, "protected void drawForegroundText");
                if (body.isEmpty()) {
                    offenders.add(name + "：找不到 drawForegroundText 方法体（判据失配）");
                    continue;
                }
                if (!body.contains("super.drawForegroundText(")) {
                    offenders.add(name + "：覆写了 drawForegroundText 却没先调 super —— "
                            + "机器名与「Inventory」标签会一个都不画");
                }
                if (body.contains("renderTitleText(") || body.contains("playerInventoryTitle")) {
                    offenders.add(name + "：把基类的「机器名 + 背包标签」又抄了一遍 —— "
                            + "两处实现会各自漂移，这两行只该由基类画");
                }
            }
            // 刻意**不**在这里查 addSlots()：javadoc 里解释「槽位由 addSlots 自动装配」
            // 是很正常的事，全文字符串匹配会把注释当代码 —— 本仓在这上面栽过四次。
            // 槽位是否真的自动装配，由 mekContainerScreensEnableDynamicSlots 那条守。
        }
        assertTrue("一个继承共享基类的屏都没扫到，判据已失效（扫描面变了？）", scanned >= 1);
        assertEquals("这些屏继承了 MekCkContainerScreenBase 却又把基类已有的东西重写了一遍：\n  ",
                List.of(), offenders);
    }
}
