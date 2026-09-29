package cn.ism.mekck.lang;

import com.google.gson.JsonElement;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 语言文件对账 —— 三种「静默显示 raw key」的成因各钉一条。
 *
 * <h3>为什么值得单独一个测试</h3>
 * 语言键缺失<b>不报错、不写日志</b>，玩家看到的是 {@code tooltip.mekck.xxx} 这样的
 * 原始键名。这类问题在代码审查里极难发现（要同时读 Java 与两份 JSON），
 * 但用脚本一查就出来。本仓库历史上已经踩过三次：
 * <ul>
 *   <li>{@code gui.mekck.slot_window.*} 五个键只加进了 {@code zh_cn}，
 *       英文客户端整扇悬浮窗的标题与分组名都是 raw key；</li>
 *   <li>{@code tooltip.mekck.nebula_planting_cutting_factory} 与
 *       {@code ..._singularity_...} 两个键<b>两种语言都没有</b>，
 *       而 {@code UniversalCuttingMachine} 的 switch 明确引用了它们；</li>
 *   <li>{@code tooltip.mekck.beverage_blender} / {@code smart_extractor} /
 *       {@code packaging_station} 由 {@code MekCkBlockItem.descriptionOrDefault}
 *       从方块 id 派生，同样两种语言都没有。</li>
 * </ul>
 *
 * <h3>三条断言分别覆盖什么</h3>
 * <ol>
 *   <li><b>键集合相等</b>：{@code en_us} 与 {@code zh_cn} 的键集合必须逐字相同。
 *       只加一边就是「一半玩家看到 raw key」。</li>
 *   <li><b>没有空值</b>：空字符串与缺键在客户端表现一样（都是空白），
 *       但空值不会被「缺键」类的检查抓到。历史上
 *       {@code tooltip.mekck.nebula_skewering_factory} 与
 *       {@code ..._singularity_...} 在 {@code zh_cn} 里就是空串。</li>
 *   <li><b>源码里的静态键都存在</b>：扫 {@code src/main/java} 下所有
 *       {@code translatable("字面量")} 调用，逐个查 {@code en_us}。
 *       只收「参数就是一个完整字符串字面量」的形式
 *       （{@code translatable("a." + x)} 这类拼接<b>刻意排除</b>——
 *       它的键在编译期不可知，扫出来只会是假阳性）。</li>
 * </ol>
 *
 * <h3>为什么排除外部命名空间的键</h3>
 * 本模组会引用别的模组的键（例如
 * {@code farmersdelight.block.cutting_board.remaining_items}），
 * 它们由那个模组自己提供，不该出现在 {@code mekck} 的语言文件里。
 * 判据取「键里是否含 {@code mekck}」——本模组所有自有键都带它。
 */
public class TestLangKeyParity {

    private static final Path EN = Path.of("src/main/resources/assets/mekck/lang/en_us.json");
    private static final Path ZH = Path.of("src/main/resources/assets/mekck/lang/zh_cn.json");
    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /**
     * 只匹配「参数就是一个完整字符串字面量」的 {@code translatable} 调用。
     *
     * <p>结尾的 {@code [,)]} 是关键：{@code translatable("a." + x)} 的字符串后面
     * 跟的是 {@code +}，不匹配；{@code translatable("k", arg)} 与
     * {@code translatable("k")} 都匹配。</p>
     */
    private static final Pattern STATIC_KEY =
            Pattern.compile("translatable\\(\\s*\"([^\"]+)\"\\s*[,)]");

    private static Map<String, String> load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            Map<String, String> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                out.put(entry.getKey(), entry.getValue().getAsString());
            }
            return out;
        }
    }

    /** 断言 1：两份语言文件的键集合必须逐字相同。 */
    @Test
    public void keySetsAreIdentical() throws IOException {
        Set<String> en = new TreeSet<>(load(EN).keySet());
        Set<String> zh = new TreeSet<>(load(ZH).keySet());

        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(zh);
        Set<String> onlyZh = new TreeSet<>(zh);
        onlyZh.removeAll(en);

        assertEquals("这些键只在 en_us 里有（中文客户端会显示 raw key）：" + onlyEn, Set.of(), onlyEn);
        assertEquals("这些键只在 zh_cn 里有（英文客户端会显示 raw key）：" + onlyZh, Set.of(), onlyZh);
    }

    /** 断言 2：两份语言文件都不许有空值。 */
    @Test
    public void noEmptyValues() throws IOException {
        List<String> empty = new ArrayList<>();
        for (Map.Entry<String, String> entry : load(EN).entrySet()) {
            if (entry.getValue().isBlank()) {
                empty.add("en_us:" + entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : load(ZH).entrySet()) {
            if (entry.getValue().isBlank()) {
                empty.add("zh_cn:" + entry.getKey());
            }
        }
        assertEquals("空值在客户端与缺键表现相同（都是空白）：" + empty, List.of(), empty);
    }

    /**
     * 断言 3：源码里所有静态 {@code translatable} 键都存在于 {@code en_us}。
     *
     * <p>只查 {@code en_us}：断言 1 已经保证两份文件的键集合相同，
     * 所以「在 en_us 里」等价于「两份都在」。</p>
     */
    @Test
    public void everyStaticKeyExistsInEnglish() throws IOException {
        Set<String> en = load(EN).keySet();
        Set<String> missing = new TreeSet<>();

        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = STATIC_KEY.matcher(source);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    // 外部模组的键由那个模组自己提供，不该出现在本模组的语言文件里。
                    if (!key.contains("mekck")) {
                        continue;
                    }
                    if (!en.contains(key)) {
                        missing.add(key + "  ← " + SOURCE_ROOT.relativize(file));
                    }
                }
            }
        }

        assertEquals("这些键在源码里被静态引用，但 en_us.json 里没有：", Set.of(), missing);
    }

    /**
     * 断言 4：{@code MekCkBlockItem} 从方块 id 派生的描述键必须存在。
     *
     * <p>{@code descriptionOrDefault} 对没显式传描述的方块物品返回
     * {@code Component.translatable("tooltip.mekck." + <方块 id 去掉标准档前缀>)}。
     * 这条派生规则在编译期不可见，所以断言 3 抓不到它——
     * 上面列的三个缺失键（{@code beverage_blender} / {@code smart_extractor} /
     * {@code packaging_station}）正是从这条路径漏出去的。</p>
     *
     * <h3>判据为什么是「BLOCKS 与 ITEMS 同名注册的交集」</h3>
     * 只有<b>同时</b>注册了方块与同名物品的 id 才可能被 {@code MekCkBlockItem} 包着，
     * 派生键才有意义。单看 {@code BLOCKS.register} 会误报两类：
     * <ul>
     *   <li>{@code hazelnut_cocoa_paste} —— 流体方块，物品是
     *       {@code hazelnut_cocoa_paste_bucket}（另一个 id）；</li>
     *   <li>{@code bioreactor_bounding} —— 生物反应堆的占位方块，根本没有物品。</li>
     * </ul>
     * 标准 9 档工厂的描述被归一到家族键（{@code tooltip.mekck.<family>_factory}），
     * 所以带标准档前缀的 id 查归一后的键。
     */
    @Test
    public void derivedBlockDescriptionKeysExist() throws IOException {
        Set<String> en = load(EN).keySet();
        Path registry = SOURCE_ROOT.resolve("cn/ism/mekck/UniversalCuttingMachine.java");
        String source = Files.readString(registry, StandardCharsets.UTF_8);

        Set<String> blocks = new TreeSet<>();
        Matcher blockMatcher = Pattern.compile("BLOCKS\\.register\\(\"([a-z0-9_]+)\"").matcher(source);
        while (blockMatcher.find()) {
            blocks.add(blockMatcher.group(1));
        }
        Set<String> items = new TreeSet<>();
        Matcher itemMatcher = Pattern.compile("ITEMS\\.register\\(\"([a-z0-9_]+)\"").matcher(source);
        while (itemMatcher.find()) {
            items.add(itemMatcher.group(1));
        }
        blocks.retainAll(items);

        assertTrue("方块注册表里一个「方块 + 同名物品」都没扫到，说明判据失效了（注册方式变了？）",
                blocks.size() >= 20);

        Set<String> standardTiers = Set.of("basic", "advanced", "elite", "ultimate", "absolute",
                "supreme", "cosmic", "infinite", "crystal_matrix");

        Set<String> missing = new TreeSet<>();
        for (String id : blocks) {
            String path = id;
            if (path.endsWith("_factory")) {
                for (String tier : standardTiers) {
                    if (path.startsWith(tier + "_")) {
                        path = path.substring(tier.length() + 1);
                        break;
                    }
                }
            }
            String key = "tooltip.mekck." + path;
            if (!en.contains(key)) {
                missing.add(id + " → " + key);
            }
        }

        assertEquals("这些方块由 MekCkBlockItem 从 id 派生描述键，但 en_us.json 里没有：",
                Set.of(), missing);
    }

    /** 两份文件的键数一致（断言 1 的冗余版本，失败信息更直白）。 */
    @Test
    public void keyCountsMatch() throws IOException {
        assertEquals("两份语言文件的键数必须一致",
                load(EN).size(), load(ZH).size());
    }

    /** 语言文件必须是合法 JSON 且顶层是对象（{@code load} 会抛，这里只是显式钉一条）。 */
    @Test
    public void bothFilesParse() throws IOException {
        assertTrue("en_us.json 不该为空", load(EN).size() > 0);
        assertTrue("zh_cn.json 不该为空", load(ZH).size() > 0);
    }

    /** 未使用的占位符检查：带 {@code %s} 的键在两种语言里占位符个数必须一致。 */
    @Test
    public void placeholderCountsMatch() throws IOException {
        Map<String, String> en = load(EN);
        Map<String, String> zh = load(ZH);
        Set<String> mismatched = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : en.entrySet()) {
            String other = zh.get(entry.getKey());
            if (other == null) {
                continue;
            }
            int a = countPlaceholders(entry.getValue());
            int b = countPlaceholders(other);
            if (a != b) {
                mismatched.add(entry.getKey() + "（en " + a + " 个 / zh " + b + " 个）");
            }
        }
        assertEquals("占位符个数不一致会让格式化抛异常或吞掉参数：", Set.of(), mismatched);
    }

    /**
     * 断言 5：走 Mek 体系的工厂方块必须有 {@code container.mekck.<方块 id>} 键。
     *
     * <h3>这条派生路径为什么断言 3 抓不到</h3>
     * 键不是源码里写出来的，是 Mek 在运行时拼的：
     * {@code TileEntityMekanism.getDisplayName()} 对可命名的机器返回
     * {@code Util.makeDescriptionId("container", getBlockTypeRegistryName())}
     * ⇒ {@code container.mekck.<方块注册名>}。源码里搜不到这个字面量，
     * 所以「扫 translatable 字面量」的断言 3 天然漏掉它。
     *
     * <h3>症状</h3>
     * GUI 标题直接显示 {@code container.mekck.cosmic_grill_factory}。
     * 2026-09-30 实机截图确认。
     *
     * <h3>为什么只查 6 个家族</h3>
     * {@code ice_factory} 系列仍是旧 {@code BlockEntity}（{@code IceFactoryBlockEntity}
     * 自己实现 {@code MenuProvider.getDisplayName()}），不走 Mek 的 tile，
     * 所以不需要这个键。判据取「方块是否由 Mek 的 {@code BlockDeferredRegister} 注册」，
     * 在源码里就是那 6 个 {@code *_FACTORY_BLOCKS_REG.register(id, ...)} 调用点。
     */
    @Test
    public void mekMachineContainerKeysExist() throws IOException {
        Set<String> en = load(EN).keySet();

        // 6 个走 Mek BlockDeferredRegister 的家族 —— 从注册调用点扫出来，不写死。
        // 锚点用 register(id, 而不是 register(bus)：后者是注册器本身挂到事件总线，不是方块。
        Set<String> families = new TreeSet<>();
        String registry = Files.readString(
                SOURCE_ROOT.resolve("cn/ism/mekck/UniversalCuttingMachine.java"), StandardCharsets.UTF_8);
        Matcher familyMatcher =
                Pattern.compile("([A-Z][A-Z_]*)_FACTORY_BLOCKS_REG\\.register\\(id,").matcher(registry);
        while (familyMatcher.find()) {
            families.add(familyMatcher.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        assertEquals("没扫到 6 个 Mek 工厂家族的注册点，判据失效了（注册方式变了？）：" + families,
                Set.of("cooking", "cutting", "grill", "grinding", "planting_cutting", "skewering"), families);

        // 12 个档位名 —— 从枚举常量的第一个构造参数扫出来。
        Set<String> tiers = new TreeSet<>();
        String tierSource = Files.readString(
                SOURCE_ROOT.resolve("cn/ism/mekck/CuttingMachineFactoryTier.java"), StandardCharsets.UTF_8);
        Matcher tierMatcher =
                Pattern.compile("^\\s*[A-Z][A-Z_]*\\(\"([a-z_]+)\",", Pattern.MULTILINE).matcher(tierSource);
        while (tierMatcher.find()) {
            tiers.add(tierMatcher.group(1));
        }
        assertTrue("没扫到档位名，判据失效了（枚举写法变了？）", tiers.size() >= 12);

        Set<String> missing = new TreeSet<>();
        for (String tier : tiers) {
            for (String family : families) {
                String key = "container.mekck." + tier + "_" + family + "_factory";
                if (!en.contains(key)) {
                    missing.add(key);
                }
            }
        }
        assertEquals("这些方块走 Mek 的 tile，GUI 标题会显示 raw key（Mek 用 container.<方块 id> 拼标题）：",
                Set.of(), missing);
    }

    private static int countPlaceholders(String value) {
        int count = 0;
        Matcher matcher = Pattern.compile("%[sd]").matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
