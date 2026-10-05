package cn.ism.mekck.machine.grinding;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 研磨家族<b>随机产出</b>的算术测试 —— 切菜是确定性产出，没有这一层。
 *
 * <h3>为什么只有「期望值那一支」测得到</h3>
 * 逐件掷骰那一支要 {@code Level.random}（{@code RandomSource}），裸 JVM 里没有世界；
 * 而它本身只有一行 {@code nextFloat() < chance}，能错的地方只有 chance 的取值范围，
 * 而 {@link #clampChance} 正是把它夹住的。期望值那一支才是真正有算术的一支：
 * 三个 double 相乘、{@code Math.floor} 拆成整数与小数、小数再折成百万分之一。
 *
 * <h3>这些断言防的故障形态</h3>
 * <ul>
 *   <li>int 溢出：奇点档 81 并行 × 存储卡倍增可以到几百万件，{@code perItem * consumeCount}
 *       以 int 相乘会先溢出，负数再乘 chance 会得到一个「看起来合理」的产量；</li>
 *   <li>系统性少产：直接 {@code (long) raw} 取整会永远少掉小数部分，
 *       在几百万件量级上那是几万件凭空消失；</li>
 *   <li>概率越界：chance &gt; 1 或 &lt; 0 时产量会算出负数或溢出。</li>
 * </ul>
 */
public class TestGrindingRollArithmetic {

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    // ── chance 夹紧 ─────────────────────────────────────────────────────

    /** chance 夹到 [0,1]。与逐件掷骰那一支的「不夹」结果一致：nextFloat() ∈ [0,1)。 */
    @Test
    public void chanceIsClampedIntoTheUnitRange() {
        assertEquals(0.0F, GrindingFactoryExecutor.clampChance(-3.0F), 0.0F);
        assertEquals(0.5F, GrindingFactoryExecutor.clampChance(0.5F), 0.0F);
        assertEquals(1.0F, GrindingFactoryExecutor.clampChance(7.5F), 0.0F);
    }

    // ── 期望产量的整数部分 ──────────────────────────────────────────────

    /** 整除时小数部分必须为 0（否则会白送一件）。 */
    @Test
    public void integralExpectationHasNoRemainder() {
        assertEquals(0, GrindingFactoryExecutor.expectedFractionMillion(2, 10, 1.0F));
        assertEquals(20L, GrindingFactoryExecutor.expectedFloor(2, 10, 1.0F));
    }

    /** 期望 4.5 ⇒ 整数 4、小数 0.5（即 500_000）。 */
    @Test
    public void fractionalExpectationSplitsIntoFloorAndRemainder() {
        assertEquals(4L, GrindingFactoryExecutor.expectedFloor(3, 3, 0.5F));
        assertEquals(500_000, GrindingFactoryExecutor.expectedFractionMillion(3, 3, 0.5F));
    }

    /** chance = 0 ⇒ 期望为 0，一件都不该出。 */
    @Test
    public void zeroChanceProducesNothing() {
        assertEquals(0L, GrindingFactoryExecutor.expectedFloor(64, 1000, 0.0F));
        assertEquals(0, GrindingFactoryExecutor.expectedFractionMillion(64, 1000, 0.0F));
    }

    /**
     * 奇点档量级不得因 int 溢出而算出负数或离谱的数。
     *
     * <p>取一个旧实现会溢出的组合：{@code 64 × 3_000_000} 以 int 相乘是 1.9 亿，
     * 看似不溢出；真正溢出的是再乘上倍增后的百万级 consumeCount。
     * 这里直接用 {@code 64 × 100_000_000}（64 亿）——int 早就绕回去了，
     * 而 double 路径给出的是正确的 640 亿。</p>
     */
    @Test
    public void singularityScaleDoesNotOverflowInt() {
        long expected = 64L * 100_000_000L;
        assertEquals(expected, GrindingFactoryExecutor.expectedFloor(64, 100_000_000, 1.0F));
        assertTrue("奇点档量级的期望产量必须为正",
                GrindingFactoryExecutor.expectedFloor(64, 100_000_000, 1.0F) > Integer.MAX_VALUE);
    }

    /** chance 越界时先夹再算：{@code clampChance} 与算术的组合必须给出合法值。 */
    @Test
    public void outOfRangeChanceNeverProducesNegativeOutput() {
        float clamped = GrindingFactoryExecutor.clampChance(-1.0F);
        assertEquals(0L, GrindingFactoryExecutor.expectedFloor(64, 1000, clamped));
        float clampedHigh = GrindingFactoryExecutor.clampChance(9.0F);
        assertEquals(64_000L, GrindingFactoryExecutor.expectedFloor(64, 1000, clampedHigh));
    }

    // ── 小数部分（百万分之一）───────────────────────────────────────────

    /** 期望 2.25 ⇒ 整数 2、小数 0.25（即 250_000）。 */
    @Test
    public void quarterRemainderBecomesTwoHundredAndFiftyThousand() {
        assertEquals(2L, GrindingFactoryExecutor.expectedFloor(1, 9, 0.25F));
        assertEquals(250_000, GrindingFactoryExecutor.expectedFractionMillion(1, 9, 0.25F));
    }

    /**
     * 小数部分必须恒在 [0, 1_000_000) 内 —— 它要与
     * {@code random.nextInt(1_000_000)} 同量纲比较，越界就等于概率失真。
     */
    @Test
    public void remainderAlwaysStaysInsideTheMillionScale() {
        float[] chances = {0.0F, 0.0001F, 0.1F, 0.3333F, 0.5F, 0.6667F, 0.9F, 0.9999F, 1.0F};
        int[] perItem = {1, 3, 7, 64};
        int[] counts = {1, 2, 63, 65535, 65536, 1_000_000};
        for (float chance : chances) {
            for (int p : perItem) {
                for (int c : counts) {
                    int fraction = GrindingFactoryExecutor.expectedFractionMillion(p, c, chance);
                    assertTrue("fraction 越界：" + p + " × " + c + " @ " + chance + " ⇒ " + fraction,
                            fraction >= 0 && fraction < 1_000_000);
                }
            }
        }
    }

    // ── 「本机下单」面板的份数计算 ─────────────────────────────────────

    /** 逐需求项取最小：一份要 2 个 A、1 个 B，A 只有 5 个就只能做 2 份。 */
    @Test
    public void maxCountTakesTheSmallestIngredientSupply() {
        List<Ingredient> recipe = List.of(
                Ingredient.of(Items.CARROT),
                Ingredient.of(Items.POTATO));
        List<ItemStack> inputs = List.of(
                new ItemStack(Items.CARROT, 5),
                new ItemStack(Items.POTATO, 9));
        assertEquals(5, GrindingFactoryExecutor.maxConsumableCount(recipe, inputs));
    }

    /** 同一需求出现在两个输入槽时数量要<b>相加</b>，不是取较大者。 */
    @Test
    public void sameIngredientAcrossSlotsIsSummed() {
        List<Ingredient> recipe = List.of(Ingredient.of(Items.CARROT));
        List<ItemStack> inputs = List.of(
                new ItemStack(Items.CARROT, 3),
                new ItemStack(Items.CARROT, 4));
        assertEquals(7, GrindingFactoryExecutor.maxConsumableCount(recipe, inputs));
    }

    /** 需求为空（配方没有非空 ingredient）时返回 0，而不是 21 亿。 */
    @Test
    public void recipeWithoutIngredientsYieldsZero() {
        assertEquals(0, GrindingFactoryExecutor.maxConsumableCount(List.of(), List.of()));
        assertEquals(0, GrindingFactoryExecutor.maxConsumableCount(
                List.of(Ingredient.EMPTY), List.of(new ItemStack(Items.CARROT, 10))));
    }

    /** 缺料就是 0，不返回「能做 0 份」以外的任何数。 */
    @Test
    public void missingIngredientYieldsZero() {
        List<Ingredient> recipe = List.of(Ingredient.of(Items.CARROT));
        assertEquals(0, GrindingFactoryExecutor.maxConsumableCount(recipe, List.of()));
        assertEquals(0, GrindingFactoryExecutor.maxConsumableCount(
                recipe, List.of(new ItemStack(Items.POTATO, 10))));
    }

    /** 空槽与 null 槽都必须被跳过，不能 NPE。 */
    @Test
    public void nullAndEmptySlotsAreSkipped() {
        List<Ingredient> recipe = List.of(Ingredient.of(Items.CARROT));
        List<ItemStack> inputs = new ArrayList<>();
        inputs.add(null);
        inputs.add(ItemStack.EMPTY);
        inputs.add(new ItemStack(Items.CARROT, 6));
        assertEquals(6, GrindingFactoryExecutor.maxConsumableCount(recipe, inputs));
    }

    /**
     * 1.20.1 的 {@code Ingredient.test} <b>看不到 NBT</b>；带自定义名的萝卜仍然算作同一种料。
     *
     * <p>这条断言钉的是<b>实测结果</b>，不是我们希望的行为：
     * 旧实现用的也是同一个 {@code Ingredient.test}，行为与它逐字一致。
     * 写成断言是为了让后人不会在这里「修一个意外的串味」——
     * 真要区分带 NBT 与不带的同种料，得地点在配方而不是在这里。</p>
     */
    @Test
    public void vanillaIngredientTestIgnoresNbt() {
        ItemStack named = new ItemStack(Items.CARROT, 10);
        named.getOrCreateTag().putString("k", "v");
        assertEquals("1.20.1 的 Ingredient.test 不区分 NBT，带名萝卜仍然算同种料",
                10, GrindingFactoryExecutor.maxConsumableCount(
                        List.of(Ingredient.of(Items.CARROT)), List.of(named)));
    }

    // ── 「面板列哪些配方」 ──────────────────────────────────────────────

    /** 任一输入槽命中任一 ingredient 即算「这张料投得进去」。 */
    @Test
    public void anyMatchingSlotCountsAsAKnownRecipe() {
        List<ItemStack> inputs = List.of(ItemStack.EMPTY, new ItemStack(Items.CARROT, 1));
        assertTrue(GrindingFactoryExecutor.matchesAnyInput(
                fakeRecipe(List.of(Ingredient.of(Items.POTATO), Ingredient.of(Items.CARROT)), "millstone"), inputs));
    }

    /** 没有 ingredient 或没有非空槽时都判 false（面板不列）。 */
    @Test
    public void recipeWithNoIngredientsIsNotListed() {
        assertFalse(GrindingFactoryExecutor.matchesAnyInput(
                fakeRecipe(List.of(), "empty"), List.of(new ItemStack(Items.CARROT, 1))));
        assertFalse(GrindingFactoryExecutor.matchesAnyInput(
                fakeRecipe(List.of(Ingredient.of(Items.CARROT)), "carrot"), List.of()));
    }

    /** null 配方与 null 输入列表都判 false，不抛。 */
    @Test
    public void nullRecipeAndNullInputsAreTolerated() {
        assertFalse(GrindingFactoryExecutor.matchesAnyInput(null, List.of()));
        assertFalse(GrindingFactoryExecutor.matchesAnyInput(
                fakeRecipe(List.of(Ingredient.of(Items.CARROT)), "carrot"), null));
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    /**
     * 最小 {@code Recipe} 桩。
     *
     * <p>它存在的唯一理由：装配判定与面板列表只用到
     * {@code getIngredients()} 与 {@code getId()}，而真配方变是通用
     * {@code Recipe<?>}（切菜那套测不了的部分就是因为需要
     * 具体配方类的构造器，而这里不需要任何一个）。</p>
     */
    private static net.minecraft.world.item.crafting.Recipe<?> fakeRecipe(
            List<Ingredient> ingredients, String id) {
        return new net.minecraft.world.item.crafting.Recipe<net.minecraft.world.Container>() {

            @Override
            public boolean matches(net.minecraft.world.Container inv, net.minecraft.world.level.Level level) {
                return false;
            }

            @Override
            public boolean canCraftInDimensions(int width, int height) {
                return false;
            }

            @Override
            public ItemStack assemble(net.minecraft.world.Container inv,
                                      net.minecraft.core.RegistryAccess registries) {
                return ItemStack.EMPTY;
            }

            @Override
            public net.minecraft.core.NonNullList<Ingredient> getIngredients() {
                net.minecraft.core.NonNullList<Ingredient> list =
                        net.minecraft.core.NonNullList.withSize(ingredients.size(), Ingredient.EMPTY);
                for (int i = 0; i < ingredients.size(); i++) {
                    list.set(i, ingredients.get(i));
                }
                return list;
            }

            @Override
            public net.minecraft.world.item.crafting.RecipeSerializer<?> getSerializer() {
                return null;
            }

            @Override
            public net.minecraft.world.item.crafting.RecipeType<?> getType() {
                return null;
            }

            @Override
            public ItemStack getResultItem(net.minecraft.core.RegistryAccess registries) {
                return ItemStack.EMPTY;
            }

            @Override
            public net.minecraft.resources.ResourceLocation getId() {
                return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekck", id);
            }
        };
    }

    // ── 产出路径：必须走「带概率」的那一支 ────────────────────────────────

    /**
     * <b>两台研磨机器都不许用 {@code getResultItem()} 当产出。</b>
     *
     * <h3>缺陷形态（本轮实机前自查发现）</h3>
     * 石磨配方的产出是 {@code List<MillstoneOutput>}，<b>每一项各带一个 chance</b>，
     * 而不是单一固定产物。用 {@code recipe.getResultItem()} 取产物有两个后果：
     * <ul>
     *   <li>容量判定按「一个有产物的配方」算，而实际上石磨配方在该 API 下
     *       返回 <b>空栈</b> ⇒ 机器认为「无产物」⇒ <b>永远不开工</b>；</li>
     *   <li>即便侥幸开工，概率产出也被压成固定 1 个 ⇒ 石磨的随机性整个丢失。</li>
     * </ul>
     * 迁移第一版的 {@code GrindingMachineTile} 就是这么写的，靠人眼发现而非护栏。
     *
     * <p>判据：两台机器都必须调 {@link GrindingRecipes#rollOutputs} 与
     * {@link GrindingRecipes#canFitWorstCase}，且源码里不得出现
     * {@code getResultItem(} 取产物。</p>
     */
    @Test
    public void grindingMachinesNeverTakeASingleFixedResult() throws java.io.IOException {
        String[] files = {
                "src/main/java/cn/ism/mekck/machine/grinding/GrindingMachineTile.java",
                "src/main/java/cn/ism/mekck/machine/grinding/GrindingFactoryExecutor.java",
        };
        int scanned = 0;
        for (String path : files) {
            String src = cn.ism.mekck.TestSourceText.read(path);
            scanned++;
            assertFalse(path + "：不得用 getResultItem() 当产出 —— 石磨产出带概率、"
                            + "那个 API 对它返回空栈，机器会判成「无产物」而永远不开工",
                    src.contains("getResultItem("));
            assertTrue(path + "：产出必须经 GrindingRecipes.rollOutputs（带概率掷骰）",
                    src.contains("GrindingRecipes.rollOutputs"));
            assertTrue(path + "：容量判定必须走 GrindingRecipes.canFitWorstCase（最坏情况预留）",
                    src.contains("canFitWorstCase"));
        }
        assertTrue("一台研磨机器都没扫到，判据已失效", scanned >= 2);
    }

    /**
     * 「固定单产出」三类配方必须真的产得出东西。
     *
     * <h3>守的是哪一类缺陷</h3>
     * 迁移第一版把产出表收成了一支：{@code GrindingRecipes.rollOutputs} 直接调
     * {@code KaleidoscopeCompat.getMillstoneOutputs}，而它对 <b>筛粉 / 绞碎 / mekck 磨粉</b>
     * 返回空表 ⇒ 这三类配方照常扣电、扣料、推进进度条，<b>一件都不出</b>。
     * 迁移前的 {@code ElectricGrindingMachineBlockEntity.grindingOutputs} 有这条分支，
     * 收拢进共享类时丢了 —— 编译通过、其余测试全绿，只有玩家发现材料没了。
     *
     * <p>判据钉两件事：① {@code GrindingRecipes} 按<b>配方类型 id</b> 认这三类
     * （不能按类名 / {@code instanceof}：对应模组没装时会炸 {@code NoClassDefFoundError}）；
     * ② 两个机器文件都从 {@code GrindingRecipes.outputsOf} 取表，而不是各自再调一次石磨表 ——
     * 那样下一处改动又会只改一边。</p>
     */
    @Test
    public void fixedOutputRecipesActuallyProduceSomething() throws java.io.IOException {
        String recipes = cn.ism.mekck.TestSourceText.read(
                "src/main/java/cn/ism/mekck/machine/grinding/GrindingRecipes.java");
        assertTrue("GrindingRecipes 必须存在「固定单产出」判定入口 isFixedOutputRecipe",
                recipes.contains("isFixedOutputRecipe"));
        for (String type : new String[]{"flour_sieve", "mincer", "grinding"}) {
            assertTrue("GrindingRecipes 少认了一类固定产出配方（" + type + "）："
                    + "少了它，该类配方扣料后什么都不出", recipes.contains("\"" + type + "\""));
        }
        int scanned = 0;
        for (String path : new String[]{
                "src/main/java/cn/ism/mekck/machine/grinding/GrindingMachineTile.java",
                "src/main/java/cn/ism/mekck/machine/grinding/GrindingFactoryExecutor.java"}) {
            String src = cn.ism.mekck.TestSourceText.read(path);
            scanned++;
            assertTrue(path + "：取产出必须走 GrindingRecipes.outputsOf（单机与工厂同一份表）——"
                            + "退回「各自只认石磨表」就是那三类配方扣料不出货的旧形态",
                    src.contains("GrindingRecipes.outputsOf"));
        }
        assertTrue("一台研磨机器都没扫到，判据已失效", scanned >= 2);
    }
}
