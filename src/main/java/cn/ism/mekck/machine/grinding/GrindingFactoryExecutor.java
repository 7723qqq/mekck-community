package cn.ism.mekck.machine.grinding;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.machine.MekCkBatchPacking;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkOrderState;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import cn.ism.mekck.util.CountMath;
import cn.ism.mekck.util.KaleidoscopeCompat;
import cn.ism.mekck.util.RecipeCache;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.util.RandomSource;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 研磨工厂的执行器 —— 石磨配方匹配、随机产出掷骰、订单推进、并行数。
 *
 * <h3>与切菜执行器的三处<b>本质</b>差别（其余逐行同构）</h3>
 * <ol>
 *   <li><b>配方是泛型 {@code Recipe<?>}</b>，不是某个具体配方类。石磨配方来自
 *       森罗物语厨房，{@link KaleidoscopeCompat} 用反射读它的字段，因此本类<b>不 import
 *       任何森罗类</b>（森罗未安装时一 import 就 {@code NoClassDefFoundError}）。</li>
 *   <li><b>产出是随机的</b>：{@code KaleidoscopeCompat.MillstoneOutput} 带一个
 *       {@code chance}，每个消耗的输入独立掷一次骰。所以容量判定按
 *       「全部产出都命中」的最坏情况算，见 {@link #canFitWorstCase}。</li>
 *   <li><b>有订单</b>：{@code orderRecipeId != null} 时只执行订单指定的那张配方，
 *       做完 {@code orderQuantity} 份自动清除。切菜没有这一层。</li>
 * </ol>
 *
 * <h3>能量与进度条闸门不在这</h3>
 * 与切菜执行器同口径：{@link #tick} 的语义是「本 tick 尽可能多地加工」，
 * 该不该干活由 {@link MekCkMachineTile#onUpdateServer} 的闸门决定。
 *
 * <h3>配方缓存的由来（与切菜逐字相同）</h3>
 * 旧 {@code serverTick} 每 tick 对每个输入槽做一次 O(配方数) 的配方查找，
 * 奇点级 81 并行等于每 tick 上万次配方测试。现在：① 复用同一个包装器实例；
 * ② 按「输入槽 + 物品指纹」记忆上一次结果，输入没变直接复用。
 * 丢弃缓存在读档时进行——存档往返之后世界可能已换过数据包或被 {@code /reload} 刷新过。
 */
public final class GrindingFactoryExecutor implements MekCkRecipeExecutor {

    /**
     * 单槽单次完成的随机掷骰上限；超过则用期望值近似，防止奇点级并行卡死。
     *
     * <p>逐字取自旧 {@code GrindingFactoryBlockEntity.MAX_ROLL_PER_SLOT}。
     * 超过它就不是「慢一点」，而是每个批次要跑 {@code consumeCount × 产出项数} 次
     * {@code RandomSource.nextFloat}；奇点档的基础并行 × 存储卡倍增可以到几百万件，
     * 那一 tick 会把服务器线程卡住几分钟。</p>
     */
    public static final int MAX_ROLL_PER_SLOT = 65_536;

    /** 掷骰分辨率：期望值近似那一支把小数位折成百万分之一再掷一次。 */
    private static final int FRACTION_SCALE = 1_000_000;

    // ── 执行器自有状态：订单 ────────────────────────────────────────────
    //
    // 状态与契约统一由 {@link MekCkOrderState} 提供（第三轮审查：6 份手写订单实现
    // 已漂移成 3 套 null 约定 + 2 套数量下界）。本类只保留公开方法名不变，
    // 让 {@code MekckAe2} 与下单包一行都不用改。

    /** 订单状态。唯一的持有者。 */
    private final MekCkOrderState order = new MekCkOrderState();

    /**
     * 新格式的订单键。
     *
     * <p><b>与旧存档逐字同名</b>：{@code MekCkLegacyMachineNbt} 对这三个键做的是
     * <b>换位置</b>（根标签 → {@code mekckExecutor} 子标签）而不是换名字。两个标签本身已经在
     * 不同的命名空间里，同名不会造成歧义；一只改一个键的名字、留两个不改，
     * 才是真的混淆。</p>
     *
     * <p>值的定义处已收进 {@link MekCkOrderState}；这里保留公开别名是因为
     * {@code MekCkLegacyMachineNbt} 直接引用这三个常量。</p>
     */
    public static final String TAG_ORDER_RECIPE = MekCkOrderState.TAG_ORDER_RECIPE;
    public static final String TAG_ORDER_QUANTITY = MekCkOrderState.TAG_ORDER_QUANTITY;
    public static final String TAG_ORDER_COMPLETED = MekCkOrderState.TAG_ORDER_COMPLETED;

    // ── 配方缓存 ────────────────────────────────────────────────────────

    /** 每槽的缓存指纹。 */
    private long[] slotRecipeKey;
    /** 每槽的缓存结果；{@code null} 表示「查过，确实没有配方」，是有效结论不是未查。 */
    private Recipe<?>[] slotRecipeValue;
    /** 每槽的缓存是否已填充。 */
    private boolean[] slotRecipeValid;

    /** 本执行器绑定的机器。由 {@link #tick} 每次刷新。 */
    private MekCkMachineTile tile;

    /** 本 tick 是否真的加工了至少一个槽。 */
    private boolean busy;

    // ── MekCkRecipeExecutor ─────────────────────────────────────────────

    @Override
    public void tick(MekCkMachineTile tile, int slotCount) {
        this.tile = tile;
        this.busy = false;

        Level level = tile == null ? null : tile.getLevel();
        List<IInventorySlot> inputs = tile == null ? null : tile.getInputSlots();
        List<IInventorySlot> outputs = tile == null ? null : tile.getOutputSlots();
        if (level == null || inputs == null || outputs == null || outputs.isEmpty()) {
            return;
        }
        int slots = Math.min(slotCount, inputs.size());
        if (slots <= 0) {
            return;
        }

        int budget = effectiveProcessCount(tile);
        for (int i = 0; i < slots; i++) {
            ItemStack input = inputs.get(i).getStack();
            if (input.isEmpty()) {
                continue;
            }
            Optional<Recipe<?>> found = findRecipe(i);
            if (found.isEmpty()) {
                continue;
            }
            int consumeCount = Math.min(budget, input.getCount());
            if (consumeCount <= 0) {
                continue;
            }
            if (!canFitWorstCase(found.get(), consumeCount)) {
                // 装不下就跳过这一槽，而不是让所有槽一起停摆（与旧实现同口径）。
                continue;
            }
            completeRecipe(inputs, outputs, i, found.get(), consumeCount);
            this.busy = true;
        }
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>只写订单。进度条、能量、红石、升级数量全归 {@code TileEntityMekanism}
     * 与基类；配方缓存是「物品指纹 → 配方」的进程内记忆，序列化它只会让存档变大，
     * 且读回来时世界已经变了、本就该丢弃。</p>
     */
    @Override
    public void save(CompoundTag tag) {
        order.save(tag);
    }

    /**
     * {@inheritDoc}
     *
     * <p>顺带丢配方缓存，理由见类注释。订单的「键不存在即无订单」语义收在
     * {@link MekCkOrderState#load} 里，六个家族同一份。</p>
     */
    @Override
    public void load(CompoundTag tag) {
        invalidateCache();
        order.load(tag);
    }

    // ── 订单 ────────────────────────────────────────────────────────────

    /** 当前订单配方 id；{@code null} 表示无固定配方单（此时机器按投进来的料自由加工）。 */
    public ResourceLocation getOrderRecipeId() {
        return order.getRecipeId();
    }

    /** 当前订单剩余份数（无订单时为 0）。 */
    public int getOrderQuantity() {
        return order.getQuantity();
    }

    /** 当前订单已完成份数（无订单时为 0）。 */
    public int getOrderCompleted() {
        return order.getCompleted();
    }

    /**
     * 下一个订单。
     *
     * @param recipeId 配方 id；{@code null} 等价于 {@link #clearOrder()}（不留残留字段）
     * @param quantity 份数，夹到 {@code [1, MAX_VALUE]}（{@code <= 0} 视为 1，与旧实现同）
     */
    public void setOrder(ResourceLocation recipeId, int quantity) {
        order.setOrder(recipeId, quantity);
    }

    /** 取消订单。 */
    public void clearOrder() {
        order.clear();
    }

    /**
     * 完成一份订单后的推进判定 —— 纯函数形态，供裸 JVM 断言用。
     *
     * @see MekCkOrderState#advancedTo(int, int, int)
     */
    static boolean advanceOrder(int completed, int quantity) {
        return MekCkOrderState.advancedTo(completed, quantity, 1);
    }

    // ── 配方匹配 ────────────────────────────────────────────────────────

    /** 丢弃配方缓存。读档、以及槽位数变化后都必须调。 */
    void invalidateCache() {
        slotRecipeKey = null;
        slotRecipeValue = null;
        slotRecipeValid = null;
    }

    /**
     * 输入槽指纹：物品注册名 + NBT（<b>不含数量</b>，配方匹配与数量无关）。
     *
     * <p>与切菜执行器逐字相同：含数量会让「同一物品、不同堆叠数」反复失效缓存；
     * 不含 NBT 则不同附魔的同种物品会互相串味。两个都要。</p>
     */
    private static long stackKey(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0L;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        long h = (id == null ? 0 : id.hashCode());
        h = h * 31L + (stack.getTag() == null ? 0 : stack.getTag().hashCode());
        return h == 0L ? 1L : h;
    }

    /**
     * 找出这个输入槽能做出来的石磨配方，<b>并套用订单门禁</b>。
     *
     * <p>门禁语义逐字取自旧 {@code findRecipe}：有订单时，匹配到的配方 id
     * 与订单不一致就当「这张料现在不该加工」——不是「不加工」，而是这一槽
     * 在本批次里空转。玩家下的单没投进对的料时看到的就是这个。</p>
     */
    private Optional<Recipe<?>> findRecipe(int inputSlot) {
        Level level = tile == null ? null : tile.getLevel();
        List<IInventorySlot> inputs = tile == null ? null : tile.getInputSlots();
        if (level == null || inputs == null || inputSlot >= inputs.size()) {
            return Optional.empty();
        }
        ItemStack stack = inputs.get(inputSlot).getStack();
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        if (slotRecipeValid == null || slotRecipeKey == null || slotRecipeValue == null) {
            int n = Math.max(1, inputs.size());
            slotRecipeKey = new long[n];
            slotRecipeValue = new Recipe<?>[n];
            slotRecipeValid = new boolean[n];
        }
        Optional<Recipe<?>> found;
        if (inputSlot < slotRecipeValid.length) {
            long key = stackKey(stack);
            if (slotRecipeValid[inputSlot] && slotRecipeKey[inputSlot] == key) {
                // ofNullable：缓存里存的 null 是「查过、没有配方」这一有效结论。
                found = Optional.ofNullable(slotRecipeValue[inputSlot]);
            } else {
                found = KaleidoscopeCompat.findMillstoneRecipe(level, stack);
                slotRecipeValid[inputSlot] = true;
                slotRecipeKey[inputSlot] = key;
                slotRecipeValue[inputSlot] = found.orElse(null);
            }
        } else {
            found = KaleidoscopeCompat.findMillstoneRecipe(level, stack);
        }
        if (order.isActive() && (!found.isPresent() || !order.getRecipeId().equals(found.get().getId()))) {
            return Optional.empty();
        }
        return found;
    }

    // ── 批量执行 ────────────────────────────────────────────────────────

    /**
     * 最坏情况容量判定：石磨产出带概率，所以按「<b>每一项都命中</b>」算。
     *
     * <p>这是旧实现的原样搬运，注释也逐字照抄。它之所以正确：判定只是用来决定
     * 「这一槽本批次动不动手」，真掷骰之后落不下的部分在
     * {@link #insertOutput} 里自然少掉——少掉的是<b>掷骰没命中的份额</b>，
     * 而不是凭空消失的物品。</p>
     */
    private boolean canFitWorstCase(Recipe<?> recipe, int multiplier) {
        List<KaleidoscopeCompat.MillstoneOutput> outputs = KaleidoscopeCompat.getMillstoneOutputs(recipe);
        List<ItemStack> worst = new ArrayList<>(outputs.size());
        for (KaleidoscopeCompat.MillstoneOutput out : outputs) {
            worst.add(out.stack());
        }
        return MekCkBatchPacking.canFitAll(tile.getOutputSlots(), worst, multiplier);
    }

    private void completeRecipe(List<IInventorySlot> inputs, List<IInventorySlot> outputs,
                                int inputSlot, Recipe<?> recipe, int consumeCount) {
        if (inputSlot < 0 || inputSlot >= inputs.size()) {
            return;
        }
        IInventorySlot inputSlotRef = inputs.get(inputSlot);
        ItemStack input = inputSlotRef.getStack();
        if (input.isEmpty()) {
            return;
        }

        consumeCount = Math.min(consumeCount, input.getCount());
        if (consumeCount <= 0) {
            return;
        }

        List<KaleidoscopeCompat.MillstoneOutput> rolls = KaleidoscopeCompat.getMillstoneOutputs(recipe);
        if (rolls.isEmpty()) {
            return;
        }
        if (!canFitWorstCase(recipe, consumeCount)) {
            return;
        }
        // 直接写槽，不用 extractItem：一次搬运会多一次内容变更通知。
        if (input.getCount() <= consumeCount) {
            inputSlotRef.setStack(ItemStack.EMPTY);
        } else {
            ItemStack remaining = input.copy();
            remaining.setCount(input.getCount() - consumeCount);
            inputSlotRef.setStack(remaining);
        }
        if (consumeCount <= MAX_ROLL_PER_SLOT) {
            rollIndividually(outputs, rolls, consumeCount);
        } else {
            rollByExpectation(outputs, rolls, consumeCount);
        }
        if (order.isActive() && order.advance(1)) {
            // 推进与「是否已满」都由 MekCkOrderState 一处判定。
            // 修复前这里是 `orderCompleted++; if (advanceOrder(...))`：
            // ++ 是 int 自增，份数配成 Integer.MAX_VALUE 且真跑满时先绕成 MIN_VALUE，
            // 随后 advanceOrder 里的 (long) 转换已经太晚 ⇒ 订单永远完不成、机器永远
            // 只认这一张配方，且不报任何错。详见 MekCkOrderState#advance。
            order.clear();
        }
    }

    /**
     * 逐件掷骰：每个消耗的输入、每一项产出各掷一次，与原版石磨行为一致。
     *
     * <p>chance <b>不</b>先夹到 [0,1]——{@code nextFloat() ∈ [0,1)} 对 chance ≥ 1 恒真、
     * 对 chance ≤ 0 恒假，夹与不夹结果完全相同，保留原样是省掉一次证明。</p>
     */
    private void rollIndividually(List<IInventorySlot> outputs,
                                  List<KaleidoscopeCompat.MillstoneOutput> rolls, int consumeCount) {
        RandomSource random = tile == null || tile.getLevel() == null ? null : tile.getLevel().random;
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
     * <p>概率被拆成「确定的整数部分」与「按小数位掷一次的 0/1」，
     * 于是近似的误差只剩 ±1 个物品，且无偏——直接取整会系统性少产
     * （期望 4.7 永远给 4），在几百万件的量级上那是几万件凭空消失。</p>
     */
    private void rollByExpectation(List<IInventorySlot> outputs,
                                   List<KaleidoscopeCompat.MillstoneOutput> rolls, int consumeCount) {
        RandomSource random = tile == null || tile.getLevel() == null ? null : tile.getLevel().random;
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
     * 两边类型对不上就会静默截断。无参版本（不掷骰）只为让测试能断言它本身。</p>
     */
    static int expectedFractionMillion(int perItem, int consumeCount, float chance) {
        double raw = (double) perItem * (double) consumeCount * (double) chance;
        long floor = (long) Math.floor(raw);
        return (int) Math.round((raw - (double) floor) * FRACTION_SCALE);
    }

    /** 有随机源时掷那 1/1_000_000；没有随机源（理论上不可能）时不补。 */
    private static long expectedBonus(int perItem, int consumeCount, float chance, RandomSource random) {
        int fraction = expectedFractionMillion(perItem, consumeCount, chance);
        if (random == null) {
            return 0L;
        }
        return random.nextInt(FRACTION_SCALE) < fraction ? 1L : 0L;
    }

    // ── 本机下单的配方清单（供「本机下单」面板 / 未来 AE2 层消费）──────

    /**
     * 各输入槽里的材料能做的石磨配方（去重）。
     *
     * <p>{@code Kaleidoscope} 未安装时 {@code RecipeCache.type} 返回 {@code null}、
     * 返回空表——与旧实现同口径，不抛异常、不打日志（未安装是常态，不是故障）。</p>
     */
    public List<Recipe<?>> getAvailableRecipes(Level level) {
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
            if (matchesAnyInput(recipe, inputStacks())) {
                out.add(recipe);
            }
        }
        return dedupeById(out);
    }

    /**
     * 当前输入槽里各槽的栈（只读快照）。
     *
     * <p>返回的是 {@code IInventorySlot.getStack()} 的<b>引用</b>而不是副本——
     * 与 {@link #canFitWorstCase} 里「只记引用不拷贝」同一条原则：这两个方法
     * 只做 {@code isEmpty}/{@code count}/{@code Ingredient.test} 读，不改内容。</p>
     */
    private List<ItemStack> inputStacks() {
        List<IInventorySlot> inputs = tile == null ? null : tile.getInputSlots();
        if (inputs == null) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>(inputs.size());
        for (IInventorySlot slot : inputs) {
            out.add(slot.getStack());
        }
        return out;
    }

    /** 按配方 id 去重，保留首次出现的顺序。 */
    static List<Recipe<?>> dedupeById(List<Recipe<?>> recipes) {
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
     * 「本机下单」面板的 Max 按钮：当前这些材料能支撑几份（逐需求项取最小）。
     *
     * <p>逐字取自旧 {@code getMaxConsumableCountForOrder}，含它的两条约定：
     * ① 需求为空（配方没有非空 ingredient）时返回 {@code 0} 而不是
     * {@link Integer#MAX_VALUE}——面板上「Max」按钮显示 21 亿是没意义的；
     * ② 任何异常都吞成 0，因为这是 GUI 上随手点的一下，不该把面板炸开。</p>
     */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        if (recipe == null) {
            return 0;
        }
        try {
            return maxConsumableCount(recipe.getIngredients(), inputStacks());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** {@link #getMaxConsumableCountForOrder} 的纯函数内核（{@code matchesAnyInput} 为真时才调）。 */
    static int maxConsumableCount(List<Ingredient> ingredients, List<ItemStack> inputs) {
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

    /**
     * 任一输入槽里的材料是否满足该配方。
     *
     * <p>石磨配方按<b>首个</b> ingredient 判定（{@code findMillstoneRecipe} 走的是
     * {@code SimpleContainer(stack)} + {@code getRecipeFor}，容器只有 1 格），
     * 但这里遍历全部 ingredient：它只用来筛「面板上列出哪些配方」，
     * 宁可多列一张（点下去也只是 Max 算不准），也不要漏列玩家投得进去的料。</p>
     */
    static boolean matchesAnyInput(Recipe<?> recipe, List<ItemStack> inputs) {
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

    // ── 并行数 ──────────────────────────────────────────────────────────

    /**
     * 本 tick 单槽能吃下的最大数量。
     *
     * <p>上限走 {@link MekCkUpgradeTypes#capOf} 而不是旧实现的
     * {@code MekckConfig.getFactoryStackUpgradeMax(tier)}：前者先过
     * {@code isSupportedBy} 准入闸门（{@code SINGULARITY} 明确不接受存储卡），
     * 而读档路径（{@code TileComponentUpgrade} 的 {@code clear + putAll}）
     * 中间<b>没有任何 supports 检查</b>，不裁剪就等于让手改存档直接决定倍增倍数。</p>
     */
    private int effectiveProcessCount(MekCkMachineTile tile) {
        CuttingMachineFactoryTier tier = tile.getTier();
        if (tier == null) {
            return 1;
        }
        int base = MekckConfig.getMultithreadedBase(tier);
        int maxParallel = MekckConfig.getMultithreadedMax(tier);
        Upgrade storage = MekCkUpgradeRefs.storage();
        int installed = tile.getComponent() == null ? 0 : tile.getComponent().getUpgrades(storage);
        int cap = MekCkUpgradeTypes.capOf(storage, tier);
        int multiplier = MekCkUpgradeTypes.stackMultiplier(installed, cap, base, maxParallel);
        return CountMath.mulClamp(Integer.MAX_VALUE, base, multiplier);
    }
}
