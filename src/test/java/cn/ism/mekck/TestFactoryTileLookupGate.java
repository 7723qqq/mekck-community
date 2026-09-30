package cn.ism.mekck;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 「逐档家族的 tile 延迟查找闸门」只允许存在<b>一份</b>实现。
 *
 * <h3>为什么这条值得钉</h3>
 * 第四轮之前，这里有 <b>6 份手抄的 {@code findXxxFactoryTile}</b>，
 * 正文<b>逐字相同</b>，只有抛异常时那句中文家族名不同：
 * <pre>
 *   private static …TileEntityTypeRegistryObject&lt;XxxTile&gt; findXxxFactoryTile(tier) {
 *       … found = XXX_FACTORY_TILES.get(tier);
 *       if (found == null) throw new IllegalStateException("Xxx工厂 tile 尚未注册：tier=" + tier …);
 *       return found;
 *   }
 * </pre>
 *
 * <p>6 份拷贝的真实代价不是「行数多」，而是<b>报错会指错方向</b>：
 * 家族名是手写的字符串，改家族时漏改一处，排查的人就会被那句错误的中文
 * 指向另一个模块，在错的地方找半天。已统一为泛型
 * {@code findFactoryTile(tiles, tier, family)}，家族名由调用点传入。</p>
 *
 * <p>那个 {@code null} 检查也不是防御性冗余：它是<b>唯一</b>能把
 * 「BlockType 的 Supplier 被过早求值」这类注册顺序错误变成可读异常的地方。
 * 静默返回 null 会让 Mek 在远端才 NPE，堆栈指向完全无关的类。</p>
 */
public class TestFactoryTileLookupGate {

    private static final Path REGISTRY = Path.of("src/main/java/cn/ism/mekck/UniversalCuttingMachine.java");

    /** 6 个逐档家族各自的 {@code *_FACTORY_TILES} 表 —— 每张都必须真的传给闸门。 */
    private static final Set<String> TIER_TILE_MAPS = Set.of(
            "CUTTING_FACTORY_TILES", "COOKING_FACTORY_TILES", "SKEWERING_FACTORY_TILES",
            "GRILL_FACTORY_TILES", "PLANTING_CUTTING_FACTORY_TILES", "GRINDING_FACTORY_TILES");

    /** 逐档家族的 {@code blockTypeFor} 调用点数量。 */
    private static final Pattern BLOCK_TYPE_FOR = Pattern.compile(
            "blockTypeFor\\s*\\(");

    private static String read() throws IOException {
        assertTrue("找不到 " + REGISTRY + "（源码测试需在仓库根目录运行）", Files.isRegularFile(REGISTRY));
        return Files.readString(REGISTRY, StandardCharsets.UTF_8);
    }

    /**
     * 闸门只能有一份 —— 手抄的 {@code findXxxFactoryTile} 不许回来。
     *
     * <p>同时把「单一实现」和「单一调用形状」都钉住：只查方法数会漏掉
     * 「有人加了第 7 份、但没删旧的」这种半吊子。</p>
     */
    @Test
    public void theGateHasExactlyOneImplementation() throws IOException {
        String src = read();

        int gateDefinitions = count(src, "TileEntityTypeRegistryObject<T> findFactoryTile(");
        assertEquals("泛型闸门 findFactoryTile 应恰好 1 份", 1, gateDefinitions);

        Set<String> strays = new TreeSet<>();
        Matcher stray = Pattern.compile(
                "private static [^;{]*\\bfind(Cutting|Cooking|Skewering|GrillFactory|Grinding|PlantingCutting)FactoryTile\\s*\\(")
                .matcher(src);
        while (stray.find()) {
            strays.add(stray.group(0).trim());
        }
        assertEquals("这些手抄的 findXxxFactoryTile 又回来了（家族名手写 6 份，报错会指错方向）：\n  "
                + String.join("\n  ", strays), Set.of(), strays);

        // 防「加了闸门却没改干净」：调用点必须全部走闸门，且不残留旧名
        assertTrue("仍有调用点在用已删除的 findXxxFactoryTile",
                !Pattern.compile("->\\s*find(Cutting|Cooking|Skewering|GrillFactory|Grinding|PlantingCutting)FactoryTile\\(")
                        .matcher(src).find());
    }

    /**
     * 6 张逐档 tile 表<b>每张</b>都必须真的传给闸门，且家族名不得为空。
     *
     * <p>这是上一条的反向锚定：闸门存在但某个家族没接过去时，
     * {@code tiles.get(tier)} 会命中一张空表 ⇒ 构造期必然抛
     * 「tile 尚未注册」，而症状出现在<b>游戏启动</b>而不是编译期。</p>
     */
    @Test
    public void everyTierFamilyPassesItsOwnMapAndName() throws IOException {
        String src = read();

        Set<String> missing = new TreeSet<>();
        for (String map : TIER_TILE_MAPS) {
            Matcher m = Pattern.compile(
                    "findFactoryTile\\(\\s*" + map + "\\s*,\\s*tier\\s*,\\s*\"([^\"]+)\"\\s*\\)")
                    .matcher(src);
            if (!m.find()) {
                missing.add(map + "：没有以「自己的表 + 非空家族名」调用闸门");
            }
        }
        assertEquals("这些逐档家族没有正确接入闸门：\n  " + String.join("\n  ", missing),
                Set.of(), missing);
    }

    /**
     * 非逐档的电力烧烤架<b>刻意</b>保留独立闸门，不要顺手并进去。
     *
     * <p>它没有档位维度（就一台机器），{@code findFactoryTile} 的
     * {@code Map<CuttingMachineFactoryTier, …>} 签名对它不适用 ——
     * 硬套就得造一张只有一个 BAS IC 键的假表，那种「为了统一而统一」正是
     * 本轮在删的东西。</p>
     */
    @Test
    public void theSingleMachineKeepsItsOwnLookup() throws IOException {
        String src = read();
        assertTrue("电力烧烤架的独立闸门 findGrillTile 不见了",
                src.contains("TileEntityTypeRegistryObject<GrillBlockEntity> findGrillTile()"));
        assertTrue("findGrillTile 的调用点不见了",
                src.contains("findGrillTile())"));
        // 它的报错信息里没有 tier=，因为压根没有档位
        assertTrue("findGrillTile 的报错信息不应出现 tier=",
                !src.contains("电力烧烤架 tile 尚未注册：tier="));
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
