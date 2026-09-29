package cn.ism.mekck.factory;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 切菜配方逻辑的<b>源码不变量</b>特征测试。
 *
 * <h3>为什么是源码检查而不是行为测试</h3>
 * 本仓库的切菜配方类型是 {@code vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe}。
 * 实测（{@code src/test/java/cn/ism/mekck/recipe/} 下的临时探针，2026-09-29）在裸 JVM 里：
 * <pre>
 *   SharedConstants.tryDetectVersion()            => OK
 *   Bootstrap.bootStrap()                         => 网络钩子阶段抛
 *                                                    NoSuchMethodException:
 *                                                    net.minecraftforge.network.NetworkEvent.&lt;init&gt;()
 *   （容错吞掉上面那次异常之后）ItemStack / Items / ForgeRegistries / Ingredient
 *     / ItemStackHandler / RecipeWrapper / Upgrade.SPEED.getMax()  => 全部 OK
 *   ModRecipeTypes.CUTTING.get()                   => NullPointerException:
 *                                                    Registry Object not present: farmersdelight:cutting
 *   new CuttingBoardRecipe(...).getResults()      => NoSuchFieldError: f_41583_
 *   new CuttingBoardRecipe(...).matches(w, null)  => NoSuchMethodError: RecipeWrapper.m_7983_()
 *   MekCkUpgradeRefs.storage()                     => IllegalStateException: STORAGE 未注入
 *   MekckConfig.getFactoryStackUpgradeMax(tier)    => IllegalStateException:
 *                                                    Cannot get config value before config is loaded.
 * </pre>
 * 三条结论，每条都是<b>实测</b>而不是推断：
 * <ol>
 *   <li>「{@code Upgrade} 的静态初始化链在裸 JVM 里<b>必然</b>抛
 *       {@code ExceptionInInitializerError}」是<b>错的</b>。只要先
 *       {@code SharedConstants.tryDetectVersion()}、再容错吞掉
 *       {@code Bootstrap.bootStrap()} 在 Forge 网络钩子阶段的失败，
 *       {@code ItemStack} / {@code Items} / {@code ForgeRegistries} / {@code Upgrade} 全部能初始化。
 *       真正的硬阻断是下面两条。</li>
 *   <li>Farmer's Delight 在测试期是 <b>raw 未重映射 jar</b>
 *       （{@code build.gradle} 的 {@code files(...) dependencies are not deobfuscated}
 *       警告为证），其字节码里的 MC 成员仍是 SRG 名（{@code f_41583_} / {@code m_7983_}），
 *       与 classpath 上的 mojmap 对不上 ⇒ {@code CuttingBoardRecipe} 既不能构造也不能调用。</li>
 *   <li>{@code MekCkUpgradeRefs.storage()} 与 {@code MekckConfig} 在裸 JVM 里都会抛，
 *       前者因为 Mixin 未在 test classpath 上生效，后者因为配置未加载。</li>
 * </ol>
 * 而 {@code findRecipe} 必须同时用到 {@code CuttingBoardRecipe} 与
 * {@code MekCkUpgradeRefs.storage()}，因此<b>配方匹配这条路径在纯 JVM 里无路可走</b>。
 * 沿用 {@code TestNbtPersistenceInvariants} 的做法：读源文本检查结构约束。
 *
 * <h3>被钉住的不变量（每条都对应一次真实踩过的坑）</h3>
 * <ol>
 *   <li>{@code findRecipe} 内不新建 {@code ItemStackHandler} / {@code RecipeWrapper}
 *       —— 旧 BE 第 529 行注释记的优化：81 并行的奇点工厂否则每 tick 上百次分配 + 上万次配方测试。</li>
 *   <li>缓存指纹只取「物品注册名 + NBT」，<b>不含数量</b>——数量与配方匹配无关，
 *       含进去会让「同一物品不同堆叠数」反复失效缓存。</li>
 *   <li><b>未命中也要缓存</b>（存 {@code null}），否则没有配方的原料每 tick 重跑一次全表匹配。</li>
 *   <li>下标超出缓存长度时仍要回落到真正的配方查询，不能静默返回空。</li>
 *   <li>配方类型常量是 {@code ModRecipeTypes.CUTTING}（brief 正文写的
 *       {@code CUTTING_BOARD} <b>在本版本不存在</b>，javap 实测只有 CUTTING / COOKING）。</li>
 *   <li>并行数的移位量必须先过 {@code Math.min}，不能直接移已安装数量。</li>
 *   <li>产出容量判定过程里不得出现 {@code ItemStack.copy()}（旧 BE 第 641 行注释记的分配优化）。</li>
 * </ol>
 *
 * <h3>测试怎么跟着代码走</h3>
 * 迁移前配方逻辑在 {@code blockentity/CuttingMachineFactoryBlockEntity.java}，
 * 迁移后在 {@code machine/cutting/CuttingFactoryExecutor.java}（旧文件到 Task 5 才删）。
 * {@link #logicSource()} 按「先新后旧」解析，所以同一份断言在迁移前后都成立，
 * 不需要为了改测试路径而重写。
 */
public class TestCuttingRecipeInvariants {

    /** 迁移后的执行器。 */
    private static final Path EXECUTOR = Path.of("src", "main", "java", "cn", "ism", "mekck",
            "machine", "cutting", "CuttingFactoryExecutor.java");
    /** 迁移前的旧方块实体（Task 5 才删除）。 */
    private static final Path LEGACY_BE = Path.of("src", "main", "java", "cn", "ism", "mekck",
            "blockentity", "CuttingMachineFactoryBlockEntity.java");

    private static Path logicSource() {
        if (Files.exists(EXECUTOR)) {
            return EXECUTOR;
        }
        return LEGACY_BE;
    }

    private static String readLogic() throws IOException {
        Path file = logicSource();
        if (!Files.exists(file)) {
            fail("找不到切菜配方逻辑的源文件（测试需在项目根目录运行）：" + file.toAbsolutePath());
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * 按花括号深度截出某个方法的方法体（不含签名行）。
     *
     * <p>用深度追踪而不是正则：Java 方法签名里带泛型与参数列表，正则很容易被
     * 参数里的括号骗过去。</p>
     */
    private static String methodBody(String source, Pattern signature) {
        Matcher m = signature.matcher(source);
        if (!m.find()) {
            fail("在 " + logicSource().getFileName() + " 里找不到方法签名 " + signature.pattern());
        }
        int open = source.indexOf('{', m.end());
        if (open < 0) {
            fail("方法 " + signature.pattern() + " 后没有方法体");
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        fail("方法 " + signature.pattern() + " 的花括号不配对");
        return "";
    }

    private static final Pattern FIND_RECIPE =
            Pattern.compile("Optional<\\s*CuttingBoardRecipe\\s*>\\s+findRecipe\\s*\\(\\s*int\\s+\\w+\\s*\\)");
    private static final Pattern STACK_KEY =
            Pattern.compile("static\\s+long\\s+stackKey\\s*\\(");
    private static final Pattern CAN_FIT_ALL =
            Pattern.compile("boolean\\s+canFitAll\\s*\\(");

    /** 断言 1：配方匹配不得每次新建包装器 / 栈句柄。 */
    @Test
    public void findRecipeReusesOneWrapperInstance() throws IOException {
        String body = methodBody(readLogic(), FIND_RECIPE);
        assertTrue("findRecipe 里出现了 new RecipeWrapper(："
                        + "旧 BE 第 529 行注释记的优化（复用同一个包装器实例）被回退了，"
                        + "81 并行工厂会退回每 tick 上百次分配 + 上万次配方测试",
                !body.contains("new RecipeWrapper("));
        assertTrue("findRecipe 里出现了 new ItemStackHandler(：同上，复用被回退",
                !body.contains("new ItemStackHandler("));
        assertTrue("findRecipe 必须走预先建好的 singleSlotWrapper，否则复用无从谈起",
                body.contains("singleSlotWrapper"));
    }

    /** 断言 2：缓存指纹 = 物品注册名 + NBT，不含数量。 */
    @Test
    public void cacheFingerprintUsesItemIdAndNbtButNotCount() throws IOException {
        String body = methodBody(readLogic(), STACK_KEY);
        assertTrue("缓存指纹必须含物品注册名，否则换物品会命中上一个的缓存",
                body.matches("(?s).*ForgeRegistries\\s*\\.\\s*ITEMS\\s*\\.\\s*getKey\\s*\\(.*"));
        assertTrue("缓存指纹必须含 NBT，否则同一物品的不同 NBT 会互相串味",
                body.contains("getTag()"));
        assertTrue("缓存指纹不得含 getCount()：配方匹配与数量无关，"
                        + "含进去会让同一物品的不同堆叠数反复失效缓存",
                !body.contains("getCount()"));
    }

    /** 断言 3 + 4：未命中要缓存；下标越界要回落到真正的配方查询。 */
    @Test
    public void cacheStoresMissesAndFallsBackWhenOutOfRange() throws IOException {
        String body = methodBody(readLogic(), FIND_RECIPE);

        assertTrue("未命中必须以 null 存进缓存（found.orElse(null)）："
                        + "否则没有配方的原料每 tick 都要重跑一次全表配方匹配",
                body.contains("orElse(null)"));
        assertTrue("缓存命中时必须用 Optional.ofNullable 取回，否则缓存下来的「无配方」会被当成有配方",
                body.contains("Optional.ofNullable("));
        assertTrue("缓存命中分支必须先比对指纹 slotRecipeKey[",
                body.contains("slotRecipeKey["));
        assertTrue("缓存未命中时必须立刻写入指纹并置位 valid，否则每 tick 都重查",
                body.matches("(?s).*slotRecipeValid\\[[^]]*]\\s*=\\s*true.*"));

        int queries = count(body, "getRecipeFor(");
        assertTrue("findRecipe 体内应至少有 2 处 getRecipeFor("
                        + "（未命中重查 + 下标超出缓存长度时的回落），实际 " + queries
                        + " 处：少一处意味着越界下标会静默返回空配方",
                queries >= 2);
    }

    /** 断言 5：配方类型常量名（本版本只有 CUTTING，没有 CUTTING_BOARD）。 */
    @Test
    public void recipeTypeConstantIsTheOneThatActuallyExists() throws IOException {
        String source = readLogic();
        assertTrue("必须用 ModRecipeTypes.CUTTING",
                source.contains("ModRecipeTypes.CUTTING"));
        assertTrue("ModRecipeTypes.CUTTING_BOARD 在 Farmer's Delight 1.20.1 里不存在"
                        + "（javap 实测该类只有 RECIPE_TYPES / COOKING / CUTTING 三个字段）",
                !source.contains("ModRecipeTypes.CUTTING_BOARD"));
    }

    /** 断言 6：并行数的移位量必须先过 min 钳制。 */
    @Test
    public void stackMultiplierShiftAmountIsClamped() throws IOException {
        String source = readLogic();
        assertTrue("并行数必须写成 1 << Math.min(已安装数, 上限)："
                        + "直接 1 << 已安装数 会在读档路径上（无 capOf 准入闸门）失控",
                source.matches("(?s).*1\\s*<<\\s*Math\\s*\\.\\s*min\\s*\\(.*"));
    }

    /** 断言 7：产出容量判定过程不得拷贝 ItemStack。 */
    @Test
    public void capacityCheckDoesNotCopyItemStacks() throws IOException {
        String body = methodBody(readLogic(), CAN_FIT_ALL);
        assertTrue("canFitAll 判定过程里出现了 .copy()："
                        + "旧 BE 第 641 行注释记的优化（只记槽内物品引用 + 数量）被回退了，"
                        + "81 并行工厂每 tick 又是上万次分配",
                !body.contains(".copy()"));
        assertTrue("canFitAll 必须用 isSameItemSameTags 判同物，"
                        + "只比物品会把不同 NBT 的产物并进同一个槽",
                body.contains("isSameItemSameTags"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }

    /**
     * 迁移后才成立的约束：并行数上限必须走 {@code MekCkUpgradeTypes.capOf} 的准入闸门。
     *
     * <p>迁移前旧 BE 直接调 {@code MekckConfig.getFactoryStackUpgradeMax(tier)}，
     * 没有「本机档位是否接受存储卡」这一关。本断言只在执行器存在时执行。</p>
     */
    @Test
    public void migratedExecutorRoutesStackCapThroughTheAdmissionGate() throws IOException {
        if (!Files.exists(EXECUTOR)) {
            return;
        }
        String source = readLogic();
        List<String> problems = new ArrayList<>();
        if (!source.contains("MekCkUpgradeTypes.capOf(")) {
            problems.add("没有出现 MekCkUpgradeTypes.capOf(");
        }
        if (!source.contains("MekCkUpgradeRefs.storage()")) {
            problems.add("没有出现 MekCkUpgradeRefs.storage()");
        }
        if (source.contains("MekckConfig.getFactoryStackUpgradeMax(")) {
            problems.add("仍直接读 MekckConfig.getFactoryStackUpgradeMax(，绕过了准入闸门与枚举上限钳制");
        }
        assertTrue("并行数上限必须走 MekCkUpgradeTypes.capOf(MekCkUpgradeRefs.storage(), tier)：\n  "
                + String.join("\n  ", problems), problems.isEmpty());
    }

    /**
     * 迁移后才成立的约束：读档必须丢弃配方缓存。
     *
     * <p>缓存是「物品指纹 → 配方」的进程内记忆，跨存档往返后世界已变
     * （数据包被换、配方被 /reload），留着旧值会让机器按上一次的配方加工。</p>
     */
    @Test
    public void migratedExecutorDropsTheRecipeCacheOnLoad() throws IOException {
        if (!Files.exists(EXECUTOR)) {
            return;
        }
        String body = methodBody(readLogic(), Pattern.compile("public\\s+void\\s+load\\s*\\(\\s*CompoundTag"));
        assertTrue("load 必须清掉配方缓存（把 slotRecipeValid / slotRecipeKey / slotRecipeValue 置 null）："
                        + "缓存跨存档往返会把上一个世界的配方带进新世界",
                body.contains("slotRecipeValid")
                        || body.contains("invalidateCache()")
                        || body.contains("clearCache()"));
    }
}
