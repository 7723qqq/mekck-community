package cn.ism.mekck.integration.jei;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JEI 分类在<b>可选依赖缺失</b>时不得把注册表 {@code getValue} 的 null 结果
 * 直接灌进 {@code new ItemStack(...)}。
 *
 * <h3>为什么值得单独一个测试</h3>
 * {@code PlantingCuttingRecipeCategory.setRecipe} 会无条件被注册（分类与
 * {@code mekck:plantcut} 配方都是无条件的），而它兜底取的是 mekmm 的桶物品 ——
 * mekmm 只是<b>可选依赖</b>。未安装时 {@code ForgeRegistries.ITEMS.getValue(...)}
 * 返回 null，而 {@code new ItemStack(null, 1)} 的字节码第一件事就是
 * {@code ItemLike.asItem()} 解引用（javap 已证）⇒ {@code setRecipe} 抛 NPE，
 * 玩家打开任意 plantcut 配方页就看到 JEI 错误页。它编译通过、不跑 JEI 看不出来，
 * 只在「没装 mekmm」的实例上发作 —— 正是源码形态断言该守的场景。
 *
 * <h3>判据为什么用「内联」这一形态而不是逐处找 null 检查</h3>
 * 缺陷形态唯一且稳定：{@code new ItemStack( ForgeRegistries.<REG>.getValue( ... )}。
 * 正确写法必然先把结果落进局部变量再判空（对齐 {@code JEIPlugin.makeWineCellarInfoRecipe}），
 * 于是 {@code new ItemStack(} 之后<b>不会紧邻</b> {@code ForgeRegistries}。
 * 因此「整个 {@code integration/jei/} 下不存在该内联形态」就是「所有 getValue 都先判空」的
 * 等价、且不依赖语句分析的可靠判据（注释由 {@link TestSourceText} 剥掉，不受 javadoc 引用干扰）。
 */
public class TestJeiOptionalDependencyNullSafety {

    private static final Path JEI_DIR =
            Path.of("src/main/java/cn/ism/mekck/integration/jei");
    private static final Path PLANTING_CATEGORY = JEI_DIR.resolve("PlantingCuttingRecipeCategory.java");

    /**
     * 缺陷形态：{@code new ItemStack(ForgeRegistries.<REG>.getValue(} —— 构造器参数位置
     * 直接出现注册表取值调用，中间没有任何判空的机会。
     */
    private static final Pattern INLINE_UNCHECKED = Pattern.compile(
            "new\\s+ItemStack\\s*\\(\\s*ForgeRegistries\\s*\\.\\s*\\w+\\s*\\.\\s*getValue\\s*\\(");

    private static List<Path> jeiSources() throws IOException {
        try (Stream<Path> files = Files.list(JEI_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /** 断言 1：整个 JEI 源码目录不存在「未判空的内联 getValue → ItemStack」。 */
    @Test
    public void noUncheckedRegistryValueInlinedIntoItemStack() throws IOException {
        List<Path> sources = jeiSources();
        assertTrue("JEI 源码目录没扫到文件，判据在空转：" + JEI_DIR, sources.size() >= 10);

        List<String> offenders = new ArrayList<>();
        for (Path path : sources) {
            String code = TestSourceText.read(path.toString());
            Matcher matcher = INLINE_UNCHECKED.matcher(code);
            while (matcher.find()) {
                offenders.add(path.getFileName() + ":" + lineOf(code, matcher.start()));
            }
        }
        assertEquals("这些位置把 ForgeRegistries.getValue 的结果直接灌进 new ItemStack(...) —— "
                        + "可选依赖缺失时 getValue 返回 null，构造器第一步即 NPE。先落局部变量再判空：",
                List.of(), offenders);
    }

    /** 断言 2：本文件精确断言 —— 兜底方法必须先判空，且退化时返回空表。 */
    @Test
    public void nutrientFallbackNullChecksBeforeBuildingItemStack() throws IOException {
        String code = TestSourceText.read(PLANTING_CATEGORY.toString());
        String body = TestSourceText.methodBody(code, "private static List<ItemStack> getNutrientProviders()");

        assertFalse("没扫到 getNutrientProviders 方法体，判据失效（方法被改名？）", body.isEmpty());
        assertFalse("方法体里仍有未判空的内联 getValue → ItemStack",
                INLINE_UNCHECKED.matcher(body).find());
        assertTrue("必须对 getValue 的结果判空后再 new ItemStack", body.contains("!= null"));
        assertTrue("所有候选都取不到时必须返回空表（保持「无兜底物品」语义）",
                body.contains("Collections.emptyList()"));
    }

    /**
     * 断言 3：判据不空转 —— 正则既能抓住已知缺陷形态，也不会误伤已判空的写法。
     * 源码断言最危险的失败方式是正则没匹配上任何东西、所有断言恒真。
     */
    @Test
    public void scanIsNotVacuous() {
        assertTrue("正则抓不住已知缺陷形态，护栏失效",
                INLINE_UNCHECKED.matcher(
                        "ItemStack s = new ItemStack(ForgeRegistries.ITEMS.getValue(id), 1);").find());
        assertFalse("正则把已判空的写法也误判为缺陷",
                INLINE_UNCHECKED.matcher(
                        "Item it = ForgeRegistries.ITEMS.getValue(id); if (it != null) { new ItemStack(it, 1); }")
                        .find());
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}