package cn.ism.mekck.lang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 第四轮 i18n 迁移的<b>回归护栏</b>：已迁走的那 45 处硬编码中文不许回流。
 *
 * <h3>为什么不是「client/ 全域不许有硬编码中文」</h3>
 * 写这个测试时先按全域扫了一遍，结论是：<b>UI 文案共 98 处 / 21 个文件</b>
 * （另有约 47 处是 {@code LOGGER} 诊断与 {@code MekCkOutlineRenderer} 的预览日志，
 * 玩家读不到，<b>本来就不该</b> i18n）。98 处一次全迁是独立的一轮工作，
 * 与「抽 FactoryRegistrar / 拆 god class」那类结构重构挤在同一轮里，
 * 出错面会被放大到难以 review。
 *
 * <p>所以这里刻意<b>只守住已完成的 45 处</b>，而不是假装覆盖全域：
 * 一个说「全域已清」的测试，要么是假的，要么得挂一个 50 条的豁免清单 ——
 * 两种都比「老实说只做了这些」更糟。剩余欠账登记在 {@code docs/STATUS.md}。</p>
 *
 * <h3>三条断言各防什么</h3>
 * <ol>
 *   <li><b>旧字面量不许回流</b> —— 防止有人把某个 {@code "半径:"} 改回硬编码。</li>
 *   <li><b>新语言键必须在两份语言里都存在</b> —— 防止只加进 {@code zh_cn}、
 *       英文客户端整片看到 raw key（本仓已因此栽过三次，见 {@link TestLangKeyParity}）。</li>
 *   <li><b>新语言键必须真的被引用</b> —— 防止「加了键但没换干净」这种半吊子：
 *       键在语言文件里躺着、代码里还是字面量。</li>
 * </ol>
 *
 * <p>另有 {@link #theDetectionPatternsStillMatchSomething} 做判据的生命周期自检。</p>
 */
public class TestNoHardcodedUiText {

    private static final Path CLIENT_DIR = Path.of("src/main/java/cn/ism/mekck/client");
    private static final Path LANG_DIR = Path.of("src/main/resources/assets/mekck/lang");

    /**
     * 第四轮已迁走的 45 处<b>旧字面量</b> —— 出现任何一个就是回流。
     *
     * <p>刻意写成<b>字面量原样</b>而不是键名：回流往往是「顺手改回硬编码」，
     * 判据必须认得那个具体的中文串。带上下文前缀（{@code drawString(font, "半径:"}）
     * 的用完整形态，单个中文词（{@code "存储"}）用引号包裹以免误伤注释与其它词。</p>
     */
    private static final List<String> RETIRED_LITERALS = List.of(
            "可安装模块", "侧面配置", "下单", "三明治样品槽", "§7放入一个手工做好的三明治",
            "§7机器会照它的材料清单从存储区量产", "§7机器会照它的材料清单自动量产同款",
            "§8需要安装「三明治」模组", "§c需要安装 Some Assembly Required",
            "可安装模块（每系列任意等级机器）", "—— 过滤设置 ——", "先在上方点选一个系列",
            "过滤：", "清空", "手上拿物品点槽位=添加；空手点已有=移除",
            "数量：", "数量: ", "最大：", "最大: ", "搜索配方…", "\"搜索\"",
            "生长方块格", "（只有神秘农业种子受此格约束）",
            "层数: ", "层数：", "完成", "取消", "半径:", "半径：", "目标:", "目标：",
            "发热侧 %.1f℃", "\"名称\"", "\"数量\"", "\"默认\"", "排序:", "排序：",
            "线程 ", "订单进度 ", "未知");

    /** 第四轮新增的 36 个语言键。 */
    private static final List<String> NEW_KEYS = List.of(
            "gui.mekck.ui.modules", "gui.mekck.ui.modules.desc", "gui.mekck.ui.modules.hint",
            "gui.mekck.ui.side_config", "gui.mekck.ui.order", "gui.mekck.ui.sort",
            "gui.mekck.ui.threads", "gui.mekck.ui.order_progress", "gui.mekck.ui.filter_section",
            "gui.mekck.ui.filter.pick_series_first", "gui.mekck.ui.filter.label", "gui.mekck.ui.clear",
            "gui.mekck.ui.unknown", "gui.mekck.ui.quantity", "gui.mekck.ui.maximum",
            "gui.mekck.ui.search", "gui.mekck.ui.search.hint", "gui.mekck.ui.radius",
            "gui.mekck.ui.target", "gui.mekck.ui.slot.gas", "gui.mekck.ui.slot.creative",
            "gui.mekck.ui.growth_block_slot", "gui.mekck.ui.growth_block_slot.note",
            "gui.mekck.ui.sandwich_sample_slot", "gui.mekck.ui.sandwich_sample_slot.desc",
            "gui.mekck.ui.sandwich_sample_slot.central_desc",
            "gui.mekck.ui.sandwich_sample_slot.assembler_desc",
            "gui.mekck.ui.sandwich_sample_slot.requires_mod",
            "gui.mekck.ui.sandwich_sample_slot.requires_sar", "gui.mekck.ui.layers",
            "gui.mekck.ui.done", "gui.mekck.ui.cancel", "gui.mekck.ui.heat_side",
            "gui.mekck.ui.sort_mode.name", "gui.mekck.ui.sort_mode.count", "gui.mekck.ui.sort_mode.default");

    /** CJK 统一表意文字 + 全角标点。 */
    private static final Pattern CJK = Pattern.compile(
            "[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]");

    /** 任意 Java 字符串字面量（含转义）。抽成常量是因为它要在两处用到。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    // ── 1. 旧字面量不许回流 ────────────────────────────────────────────

    @Test
    public void retiredLiteralsDoNotComeBack() throws IOException {
        assertTrue("找不到 client 源码目录（测试需在仓库根目录运行）", Files.isDirectory(CLIENT_DIR));

        // 已迁走的这些文件里，连相关中文字面量都不该再出现
        List<String> files = List.of(
                "CentralKitchenScreen.java", "KitchenModuleWindow.java", "KitchenOrderWindow.java",
                "NetworkOrderPanel.java", "OrderSearchBox.java", "IceMakerScreen.java",
                "PlantingCuttingStationScreen.java", "SandwichAssemblerScreen.java",
                "SkeweringMachineScreen.java", "SmartCookingPotScreen.java");

        Set<String> offenders = new TreeSet<>();
        for (String name : files) {
            Path file = CLIENT_DIR.resolve(name);
            assertTrue("找不到 " + name, Files.isRegularFile(file));
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = STRING_LITERAL.matcher(code);
            while (m.find()) {
                String text = m.group(1);
                for (String retired : RETIRED_LITERALS) {
                    String probe = retired.startsWith("\"") ? retired : retired;
                    if (text.equals(retired) || (probe.equals(retired) && text.equals(retired))) {
                        offenders.add(name + " -> \"" + text + "\"");
                    }
                }
            }
        }
        assertEquals("这些已迁走的硬编码文案回流了（应继续用 gui.mekck.ui.* 语言键）：\n  "
                        + String.join("\n  ", offenders),
                Set.of(), offenders);
    }

    // ── 2/3. 新键在两份语言里都存在，且真的被引用 ───────────────────────

    @Test
    public void newKeysExistInBothLanguagesAndAreReferenced() throws IOException {
        Set<String> en = keysOf(LANG_DIR.resolve("en_us.json"));
        Set<String> zh = keysOf(LANG_DIR.resolve("zh_cn.json"));

        Set<String> missing = new TreeSet<>();
        for (String key : NEW_KEYS) {
            if (!en.contains(key)) missing.add("en_us 缺 " + key);
            if (!zh.contains(key)) missing.add("zh_cn 缺 " + key);
        }
        assertEquals("第四轮新增的语言键有缺失：\n  " + String.join("\n  ", missing),
                Set.of(), missing);

        String allClient = readAllClientCode();
        Set<String> unreferenced = new TreeSet<>();
        for (String key : NEW_KEYS) {
            if (!allClient.contains("\"" + key + "\"")) {
                unreferenced.add(key);
            }
        }
        assertEquals("这些新语言键没有任何代码引用（加了键却没换干净）：\n  "
                        + String.join("\n  ", unreferenced),
                Set.of(), unreferenced);
    }

    // ── 4. 判据生命周期 ────────────────────────────────────────────────

    /**
     * 确认「含 CJK 的字符串字面量」这个判据在本仓<b>确实还能匹配到东西</b>。
     *
     * <p>放在<b>全仓</b>而不是 client/ 上查：被治理的一侧迟早会清到零
     * （那正是迁移的目的），而 common 代码里合法地仍有大量 CJK 字面量。
     * 只要那边还匹配得到，就说明正则形状没写歪。</p>
     *
     * <p>不做这一步的代价：正则某天因重命名/改写法而失配，
     * {@link #retiredLiteralsDoNotComeBack} 会<b>永远全绿</b>，
     * 而它看起来在守着 45 处刚清掉的文案。</p>
     */
    @Test
    public void theDetectionPatternsStillMatchSomething() throws IOException {
        int cjk = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = STRING_LITERAL.matcher(code);
                while (m.find()) {
                    if (CJK.matcher(m.group(1)).find()) cjk++;
                }
            }
        }
        assertTrue("含 CJK 的字符串字面量在全仓都匹配不到了 —— 判据的正则可能已失配", cjk >= 20);
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static Set<String> keysOf(Path langFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(langFile, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            return new LinkedHashSet<>(obj.keySet());
        }
    }

    private static String readAllClientCode() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(CLIENT_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sb.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 只剥注释，字符串原样保留。
     *
     * <p>本仓注释全是中文，判据必须只看向<b>字面量</b>，否则每个文件都会命中。
     * 字符串内容要留着给正则匹配，所以这里不剥字符串 ——
     * 方法名因此不能叫 {@code stripCommentsAndStrings}，那会是不诚实的名字。</p>
     */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }
}
