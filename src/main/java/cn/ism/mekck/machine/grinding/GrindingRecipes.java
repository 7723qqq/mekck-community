package cn.ism.mekck.machine.grinding;

import cn.ism.mekck.compat.KaleidoscopeCompat;
import cn.ism.mekck.machine.MekCkBatchPacking;
import cn.ism.mekck.machine.MekCkOrderState;
import cn.ism.mekck.util.RecipeCache;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 研磨类机器的<b>配方语汇</b> —— 单机（{@code GrindingMachineTile}）与工厂
 * （{@code GrindingFactoryExecutor}）共用的那一份纯逻辑。
 *
 * <h3>为什么要有这个类</h3>
 * 研磨机与研磨工厂是同一套工艺的两个形态：差别只在「一路」与「多路并行 + 档位」，
 * 而<b>配方怎么找、随机产出怎么掷、订单怎么卡</b>这三件事逐字相同。
 * 迁移前这两台机器各写了一份，于是同一处语义有两份实现、改一处要记得改另一处。
 *
 * <p>本类只收「与档位、与并行路数都无关」的部分，全部是<b>静态纯函数</b>或
 * 不持有机器状态的工具方法 —— 因此它不绑定任何 tile 类型，两边都能调。
 * 带档位的东西（{@code effectiveProcessCount}）留在工厂执行器里。</p>
 *
 * <h3>为什么配方是泛型 {@code Recipe<?>}</h3>
 * 石磨配方来自森罗物语厨房，{@link KaleidoscopeCompat} 用反射读它的字段，
 * 因此本类<b>不 import 任何森罗类</b>（森罗未安装时一 import 就
 * {@code NoClassDefFoundError}）。
 *
 * <h3>概率产出</h3>
 * {@code KaleidoscopeCompat.MillstoneOutput} 带一个 {@code chance}，每个消耗的输入
 * 独立掷一次骰。所以容量判定按「全部产出都命中」的最坏情况算，见
 * {@link #canFitWorstCase}。
 */
public final class GrindingRecipes {

    /** 一次搬运里逐件掷骰的上限；超过则改用期望值近似。见 {@link #rollOutputs}。 */
    public static final int MAX_ROLL_PER_SLOT = 65_536;

    /** 掷骰分辨率：期望值近似那一支把小数位折成百万分之一再掷一次。 */
    private static final int FRACTION_SCALE = 1_000_000;

    private GrindingRecipes() {
    }

    // ── 配方查找 ────────────────────────────────────────────────────────

    /**
     * 按输入物品找石磨配方。
     *
     * <p>走 {@link KaleidoscopeCompat#findMillstoneRecipe}：它内部用
     * {@code SimpleContainer(stack)} + {@code getRecipeFor}，与原版石磨同口径，
     * 且森罗未安装时安全返回空。</p>
     */
    public static Optional<Recipe<?>> findRecipe(Level level, ItemStack stack) {
        if (level == null || stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return KaleidoscopeCompat.findMillstoneRecipe(level, stack);
    }

    /**
     * 各输入槽里的材料能做的石磨配方（去重）。
     *
     * <p>{@code Kaleidoscope} 未安装时 {@code RecipeCache.type} 返回 {@code null}、
     * 返回空表 —— 未安装是常态，不是故障，不抛异常也不打日志。</p>
     */
    public static List<Recipe<?>> availableRecipes(Level level, List<ItemStack> inputs) {
        if (level == null) {
            return List.of();
        }
        RecipeType<?> type = RecipeCache.type("kaleidoscope_cookery", "millstone");
        if (type == null) {
            return List.of();
        }
        List<Recipe<?>> all = RecipeCache.all(level, type);
        List<Recipe<?>> out = new ArrayList<>(all.size());
        for (Recipe<?> recipe : all) {
            if (matchesAnyInput(recipe, inputs)) {
                out.add(recipe);
            }
        }
        return dedupeById(out);
    }

    /** 按配方 id 去重，保留首次出现的顺序。 */
    public static List<Recipe<?>> dedupeById(List<Recipe<?>> recipes) {
        Set<ResourceLocation> seen = new HashSet<>();
        List<Recipe<?>> out = new ArrayList<>(recipes.size());
        for (Recipe<?> recipe : recipes) {
            if (seen.add(recipe.getId())) {
                out.add(recipe);
            }
        }
        return out;
    }

    /**
     * 任一输入槽里的材料是否满足该配方。
     *
     * <p>石磨配方按<b>首个</b> ingredient 判定（{@code findMillstoneRecipe} 走的是
     * {@code SimpleContainer(stack)} + {@code getRecipeFor}，容器只有 1 格），
     * 但这里遍历全部 ingredient：它只用来筛「面板上列出哪些配方」，
     * 宁可多列一张（点下去也只是 Max 算不准），也不要漏列玩家投得进去的料。</p>
     */
    public static boolean matchesAnyInput(Recipe<?> recipe, List<ItemStack> inputs) {
        if (recipe == null) {
            return false;
        }
        try {
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients == null || ingredients.isEmpty()) {
                return false;
            }
            for (ItemStack stack : inputs) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                for (Ingredient ing : ingredients) {
                    if (ing != null && !ing.isEmpty() && ing.test(stack)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 「本机下单」面板的 Max 按钮：这些材料能支撑几份（逐需求项取最小）。
     *
     * <p>含两条约定：① 需求为空时返回 {@code 0} 而不是 {@link Integer#MAX_VALUE}
     * ——面板上「Max」显示 21 亿是没意义的；② 任何异常都吞成 0，
     * 因为这是 GUI 上随手点的一下，不该把面板炸开。</p>
     */
    public static int maxConsumableCount(Recipe<?> recipe, List<ItemStack> inputs) {
        if (recipe == null) {
            return 0;
        }
        try {
            return maxConsumableCount(recipe.getIngredients(), inputs);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** {@link #maxConsumableCount(Recipe, List)} 的纯函数内核。 */
    public static int maxConsumableCount(List<Ingredient> ingredients, List<ItemStack> inputs) {
        int max = Integer.MAX_VALUE;
        for (Ingredient ing : ingredients) {
            if (ing == null || ing.isEmpty()) {
                continue;
            }
            int have = 0;
            for (ItemStack stack : inputs) {
                if (stack != null && !stack.isEmpty() && ing.test(stack)) {
                    have += stack.getCount();
                }
            }
            max = Math.min(max, have);
            if (max <= 0) {
                return 0;
            }
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    // ── 产出 ────────────────────────────────────────────────────────────

    /**
     * 最坏情况容量判定：石磨产出带概率，所以按「<b>每一项都命中</b>」算。
     *
     * <p>它之所以正确：判定只是用来决定「这一槽本批次动不动手」，真掷骰之后落不下的
     * 部分在 {@link #rollOutputs} 里自然少掉 —— 少掉的是<b>掷骰没命中的份额</b>，
     * 而不是凭空消失的物品。</p>
     */
    public static boolean canFitWorstCase(List<IInventorySlot> outputs, Recipe<?> recipe, int multiplier) {
        List<KaleidoscopeCompat.MillstoneOutput> rolls = KaleidoscopeCompat.getMillstoneOutputs(recipe);
        List<ItemStack> worst = new ArrayList<>(rolls.size());
        for (KaleidoscopeCompat.MillstoneOutput out : rolls) {
            worst.add(out.stack());
        }
        return MekCkBatchPacking.canFitAll(outputs, worst, multiplier);
    }

    /**
     * 把一次加工的产出掷出来并写入输出槽。
     *
     * <p>件数少时逐件掷（与原版石磨逐件行为一致）；件数超过
     * {@link #MAX_ROLL_PER_SLOT} 时改用期望值近似 —— 否则每个批次要跑
     * {@code consumeCount × 产出项数} 次 {@code nextFloat}，奇点档可以到几百万件，
     * 那一 tick 会把服务器线程卡住几分钟。</p>
     */
    public static void rollOutputs(List<IInventorySlot> outputs, Recipe<?> recipe,
                                   int consumeCount, RandomSource random) {
        List<KaleidoscopeCompat.MillstoneOutput> rolls = KaleidoscopeCompat.getMillstoneOutputs(recipe);
        if (rolls.isEmpty()) {
            return;
        }
        if (consumeCount <= MAX_ROLL_PER_SLOT) {
            rollIndividually(outputs, rolls, consumeCount, random);
        } else {
            rollByExpectation(outputs, rolls, consumeCount, random);
        }
    }

    /**
     * 逐件掷骰：每个消耗的输入、每一项产出各掷一次，与原版石磨行为一致。
     *
     * <p>chance <b>不</b>先夹到 [0,1] —— {@code nextFloat() ∈ [0,1)} 对 chance ≥ 1 恒真、
     * 对 chance ≤ 0 恒假，夹与不夹结果完全相同，保留原样是省掉一次证明。</p>
     */
    private static void rollIndividually(List<IInventorySlot> outputs,
                                         List<KaleidoscopeCompat.MillstoneOutput> rolls,
                                         int consumeCount, RandomSource random) {
        for (int i = 0; i < consumeCount; i++) {
            for (KaleidoscopeCompat.MillstoneOutput out : rolls) {
                if (random != null && random.nextFloat() < out.chance()) {
                    MekCkBatchPacking.insertOutput(outputs, out.stack().copy());
                }
            }
        }
    }

    /**
     * 期望值近似：并行大到不能逐件掷时，按「期望产量 + 一次百万分之一的补 1」出。
     *
     * <p>概率被拆成「确定的整数部分」与「按小数位掷一次的 0/1」，于是近似的误差只剩
     * ±1 个物品，且无偏 —— 直接取整会系统性少产（期望 4.7 永远给 4），
     * 在几百万件的量级上那是几万件凭空消失。</p>
     */
    private static void rollByExpectation(List<IInventorySlot> outputs,
                                          List<KaleidoscopeCompat.MillstoneOutput> rolls,
                                          int consumeCount, RandomSource random) {
        for (KaleidoscopeCompat.MillstoneOutput out : rolls) {
            float chance = clampChance(out.chance());
            int perItem = out.stack().getCount();
            long floor = expectedFloor(perItem, consumeCount, chance);
            int total = (int) Math.min(floor + expectedBonus(perItem, consumeCount, chance, random),
                    Integer.MAX_VALUE);
            if (total <= 0) {
                continue;
            }
            ItemStack stack = out.stack().copy();
            stack.setCount(total);
            MekCkBatchPacking.insertOutput(outputs, stack);
        }
    }

    /** chance 夹到 [0,1]。与逐件掷骰那一支的「不夹」结果一致（理由见 {@link #rollIndividually}）。 */
    static float clampChance(float chance) {
        return Math.max(0.0F, Math.min(1.0F, chance));
    }

    /**
     * 期望产量的整数部分。
     *
     * <p>三个操作数全部先转 {@code double}：{@code perItem × consumeCount} 以 int 相乘
     * 会先溢出（奇点档几百万件 × 单件 64），溢出后的负数再乘 chance 会得到一个
     * 看似合理、实则完全错误的产量。</p>
     */
    static long expectedFloor(int perItem, int consumeCount, float chance) {
        return (long) Math.floor((double) perItem * (double) consumeCount * (double) chance);
    }

    /**
     * 期望产量的小数部分折成的「百万分之一」整数，与
     * {@code random.nextInt(1_000_000) < 结果} 配对使用即为无偏的 0/1 补正。
     *
     * <p>抽出来是因为它是这段算术里唯一可能出错的一格：{@code Math.round}
     * 作用在 {@code long} 上会返回 {@code long}，而 {@code nextInt} 的上界是 int，
     * 两边类型对不上就会静默截断。</p>
     */
    static int expectedFractionMillion(int perItem, int consumeCount, float chance) {
        double raw = (double) perItem * (double) consumeCount * (double) chance;
        long floor = (long) Math.floor(raw);
        return (int) Math.round((raw - (double) floor) * FRACTION_SCALE);
    }

    /** 有随机源时掷那 1/1_000_000；没有随机源时不补。 */
    private static long expectedBonus(int perItem, int consumeCount, float chance, RandomSource random) {
        int fraction = expectedFractionMillion(perItem, consumeCount, chance);
        if (random == null) {
            return 0L;
        }
        return random.nextInt(FRACTION_SCALE) < fraction ? 1L : 0L;
    }

    /**
     * 消耗输入并推进订单的公共收尾。
     *
     * <p>直接写槽，不用 {@code extractItem}：一次搬运会多一次内容变更通知。</p>
     *
     * @param order 订单状态；为 {@code null} 时不推进（调用方无订单系统）
     * @return 实际消耗的件数
     */
    public static int consumeInput(IInventorySlot inputSlot, int consumeCount, MekCkOrderState order) {
        if (inputSlot == null) {
            return 0;
        }
        ItemStack input = inputSlot.getStack();
        if (input.isEmpty()) {
            return 0;
        }
        int actual = Math.min(consumeCount, input.getCount());
        if (actual <= 0) {
            return 0;
        }
        if (input.getCount() <= actual) {
            inputSlot.setStack(ItemStack.EMPTY);
        } else {
            ItemStack remaining = input.copy();
            remaining.setCount(input.getCount() - actual);
            inputSlot.setStack(remaining);
        }
        if (order != null && order.isActive() && order.advance(1)) {
            // 推进与「是否已满」都由 MekCkOrderState 一处判定。修复前这里是
            // `orderCompleted++; if (advanceOrder(...))`：++ 是 int 自增，份数配成
            // Integer.MAX_VALUE 且真跑满时先绕成 MIN_VALUE，随后 advanceOrder 里的
            // (long) 转换已经太晚 ⇒ 订单永远完不成、机器永远只认这一张配方，且不报任何错。
            order.clear();
        }
        return actual;
    }
}
