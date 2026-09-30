package cn.ism.mekck.integration.jei;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * JEI 催化剂注册必须<b>遍历档位表</b>，而不是逐档手写常量。
 *
 * <h3>为什么值得单独一个测试</h3>
 * 「手写档位清单漏一档」在本仓已经复现<b>两次</b>，而且两次的后果完全不同、
 * 因此极难靠人工 review 兜住：
 * <ul>
 *   <li>{@code block/IceFactoryBlock.java} 的 ticker switch 只列了 11 档、漏 BLAZE
 *       ⇒ 烈焰等级工厂<b>放置时</b>抛 {@code IllegalArgumentException}（2026-09-16 修复，
 *       注释留在原处）；</li>
 *   <li>{@code JEIPlugin.registerRecipeCatalysts} 的切菜催化剂手写了 11 行
 *       BASIC…SINGULARITY，同样漏 BLAZE ⇒ 烈焰切菜工厂在 JEI 里<b>查不到催化剂</b>，
 *       不崩、不报错、只是安静地少一个图标。</li>
 * </ul>
 * 两次漏的都是同一个档位，根因也相同：档位别名字段只有 11 个（从来没有 BLAZE 的），
 * 于是「照着字段清单写」这个动作本身就会漏。别名字段现已删除，
 * 本测试钉住剩下的唯一正确写法 —— 遍历 {@code *_FACTORY_BLOCKS}。
 *
 * <h3>为什么用源码扫描而不是真跑一遍注册</h3>
 * {@code addRecipeCatalyst} 要在 JEI 插件初始化时对着已注册的 Forge 注册表取值，
 * 普通 JVM 测试里 {@code RegistryObject.get()} 必然抛
 * {@code Registry Object not present}。判据只能落在源码形态上 ——
 * 这与本仓 {@code TestLangKeyParity} / {@code TestBlockDropInvariants} 的取舍一致。
 *
 * <h3>三条断言分别覆盖什么</h3>
 * <ol>
 *   <li><b>每个家族都走 entrySet 遍历</b>：从 {@code UniversalCuttingMachine} 扫出
 *       全部 {@code Map<CuttingMachineFactoryTier, RegistryObject<Block>>} 声明，
 *       逐个要求 {@code JEIPlugin} 里存在对该 map 的 {@code .entrySet()} 遍历。
 *       漏一个家族 = 那一族 12 档全部没有催化剂。</li>
 *   <li><b>禁止逐档别名</b>：任何 {@code <档位>_FACTORY_BLOCK.get()} 形式的
 *       催化剂注册都判失败 —— 这是本测试存在的直接原因，防止有人「为了可读性」
 *       把循环改回常量清单。</li>
 *   <li><b>判据不空转</b>：扫到的催化剂注册数必须 ≥ 档位表规模，
 *       否则说明正则没匹配上任何东西、断言 1 与 2 全都恒真。</li>
 * </ol>
 */
public class TestJeiCatalystCoverage {

    private static final Path REGISTRY =
            Path.of("src/main/java/cn/ism/mekck/UniversalCuttingMachine.java");
    private static final Path JEI_PLUGIN =
            Path.of("src/main/java/cn/ism/mekck/integration/jei/JEIPlugin.java");
    private static final Path TIER_ENUM =
            Path.of("src/main/java/cn/ism/mekck/CuttingMachineFactoryTier.java");

    /** 扫出全部「档位 → 方块」家族表的声明。两种写法都收：裸 {@code Map} 与 {@code java.util.Map}。 */
    private static final Pattern FAMILY_DECL = Pattern.compile(
            "(?:java\\.util\\.)?Map<CuttingMachineFactoryTier,\\s*RegistryObject<Block>>\\s+([A-Z][A-Z_]*)");

    /**
     * 逐档别名形如 {@code BASIC_FACTORY_BLOCK.get()} —— 正是漏 BLAZE 的那种写法。
     *
     * <p>结尾的 {@code (?![A-Z0-9_])} 词边界是必需的：否则
     * {@code COOKING_FACTORY_BLOCKS.get(CuttingMachineFactoryTier.SINGULARITY)} 会被
     * {@code ..._FACTORY_BLOCK} 抢先匹配、把 {@code S} 落在捕获组外，误报成
     * 「手写 COOKING 档」。而那处其实是<b>合法的单档查询</b> ——
     * {@code avaritia_delight:extreme_cooking} 只在 SINGULARITY 档可做
     * （见 {@code CookingFactoryExecutor:173}「仅 SINGULARITY 档」），
     * 对应 {@code block.mekck.singularity_cooking_factory = "Endless Greed Cooking Factory"}。</p>
     */
    private static final Pattern TIER_ALIAS_CATALYST = Pattern.compile(
            "addRecipeCatalyst\\s*\\(\\s*new ItemStack\\(\\s*UniversalCuttingMachine\\."
                    + "([A-Z][A-Z_]*)_FACTORY_(?:BLOCK|ITEM)(?![A-Z0-9_])");

    private static String read(Path path) throws IOException {
        assertTrue("找不到源文件：" + path + "（工作目录不对？源码测试需要在仓库根目录跑）",
                Files.isRegularFile(path));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 扫出档位枚举的常量名（{@code BASIC} / {@code BLAZE} / …）。 */
    private static Set<String> tierNames() throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher matcher = Pattern.compile("^\\s*([A-Z][A-Z_]*)\\(\"[a-z_]+\",", Pattern.MULTILINE)
                .matcher(read(TIER_ENUM));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Set<String> familyMaps() throws IOException {
        Set<String> maps = new TreeSet<>();
        Matcher matcher = FAMILY_DECL.matcher(read(REGISTRY));
        while (matcher.find()) {
            maps.add(matcher.group(1));
        }
        return maps;
    }

    /**
     * 断言 1：每个家族表都必须在 {@code JEIPlugin} 里被 {@code .entrySet()} 遍历。
     *
     * <p>允许一个家族完全没有催化剂注册（功能上就当它不进 JEI），
     * 但<b>不允许</b>「既有注册、又是逐档手写」—— 不过既然断言 2 已经禁止了手写，
     * 这里实际上等价于「有注册就必须遍历」。</p>
     */
    @Test
    public void everyFamilyMapIsIteratedInJeiPlugin() throws IOException {
        Set<String> maps = familyMaps();
        String jei = read(JEI_PLUGIN);

        assertTrue("一个家族表都没扫到，判据失效了（注册写法变了？）", maps.size() >= 7);

        Set<String> notIterated = new TreeSet<>();
        for (String map : maps) {
            if (!jei.contains(map + ".entrySet()")) {
                notIterated.add(map);
            }
        }
        assertEquals("这些家族表在 JEIPlugin 里没有 entrySet 遍历 —— "
                        + "若同时存在逐档手写注册，就是漏档的老路：",
                Set.of(), notIterated);
    }

    /**
     * 断言 2：禁止用 {@code <档位>_FACTORY_BLOCK.get()} 这种逐档别名注册催化剂。
     *
     * <p>这是本测试的核心。别名字段本身已在 {@code UniversalCuttingMachine} 中删除
     * （全仓零读取方、且恰好缺 BLAZE），这条断言防止它们以别的形式回来 ——
     * 比如有人图省事写 {@code FACTORY_BLOCKS.get(BASIC).get()} 再复制 11 份。</p>
     */
    @Test
    public void noHandEnumeratedTierCatalysts() throws IOException {
        Set<String> tiers = tierNames();
        assertTrue("档位枚举一个常量都没扫到，判据失效了", tiers.size() >= 12);

        Set<String> offenders = new TreeSet<>();
        Matcher matcher = TIER_ALIAS_CATALYST.matcher(read(JEI_PLUGIN));
        while (matcher.find()) {
            offenders.add(matcher.group(1));
        }
        assertEquals("这些催化剂是逐档手写的（手写清单会漏档，本仓已因此漏过 BLAZE 两次）："
                        + "改成 for (var e : UniversalCuttingMachine.<家族>_FACTORY_BLOCKS.entrySet())",
                Set.of(), offenders);
    }

    /**
     * 断言 3：判据不空转。
     *
     * <p>源码断言最危险的失败方式是<b>正则没匹配上任何东西</b> ——
     * 三条断言全部恒真、全绿、什么都没守。这里要求至少扫到
     * 「家族数 + 若干次 addRecipeCatalyst」量级的命中。</p>
     */
    @Test
    public void scanIsNotVacuous() throws IOException {
        String jei = read(JEI_PLUGIN);
        int catalystCalls = count(jei, "addRecipeCatalyst");
        int loops = count(jei, ".entrySet()");

        assertTrue("JEIPlugin 里一处 addRecipeCatalyst 都没扫到，护栏在空转", catalystCalls >= 20);
        assertTrue("JEIPlugin 里一处 entrySet 遍历都没扫到，护栏在空转", loops >= 7);
        assertTrue("家族表数量不对", familyMaps().size() >= 7);
        assertEquals("档位表必须是 12 档", 12, tierNames().size());
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            total++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return total;
    }
}
