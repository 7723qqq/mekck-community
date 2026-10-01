package cn.ism.mekck.machine.plantingcutting;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.machine.MekCkBatchPacking;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkOrderState;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.recipe.PlantingCuttingRecipe;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import cn.ism.mekck.util.CountMath;
import cn.ism.mekck.util.RecipeCache;
import cn.ism.mekck.UniversalCuttingMachine;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 种植切配工厂的执行器 —— 种子 →（种植）→（切菜）→ 产物，外加营养液气体与生长方块两道门禁。
 *
 * <h3>与研磨执行器的三处<b>本质</b>差别</h3>
 * <ol>
 *   <li><b>种子是催化剂，不被消耗。</b>执行时只产出、不扣输入槽。
 *       研磨是「扣原料、出产物」，这里是「原料留着、产物按份数走」。</li>
 *   <li><b>营养液是<b>整批</b>门禁，不是逐槽。</b>
 *       {@code needed = 活跃槽数 × 气体倍率 × 每槽 100 mB}，一次不够就整批不动。
 *       研磨是「这一槽装不下就跳过这一槽」，语义相反。</li>
 *   <li><b>生长方块是逐槽门禁，且在活跃槽计数<b>之前</b>。</b>
 *       需要生长土的配方（神秘农业种子）在土槽不合格时直接不计入活跃槽。</li>
 * </ol>
 *
 * <h3>能量与进度条闸门不在这</h3>
 * 与切菜/研磨同口径：{@link #process} 的语义是「本 tick 尽可能多地加工」，
 * 该不该干活由 {@link MekCkMachineTile#onUpdateServer} 的闸门决定。
 * 这里额外加一道<b>营养液</b>闸门——它是原料门禁，与机器该不该通电无关。
 */
public final class PlantingCuttingFactoryExecutor implements MekCkRecipeExecutor {

    /** 每格每次处理消耗的营养液 mB。逐字取自旧实现的同名常量。 */
    public static final long NUTRIENT_MB_PER_SLOT = 100;

    /** 与旧存档同名：{@code MekCkLegacyMachineNbt} 只把根标签换成子标签，不改键名。 */
    public static final String TAG_ORDER_RECIPE = MekCkOrderState.TAG_ORDER_RECIPE;
    public static final String TAG_ORDER_QUANTITY = MekCkOrderState.TAG_ORDER_QUANTITY;
    public static final String TAG_ORDER_COMPLETED = MekCkOrderState.TAG_ORDER_COMPLETED;

    // ── 执行器自有状态：订单 ────────────────────────────────────────────

    /** 订单状态。唯一的持有者。统一契约见 {@link MekCkOrderState}。 */
    private final MekCkOrderState order = new MekCkOrderState();

    // ── 配方缓存 ────────────────────────────────────────────────────────

    private long[] slotRecipeKey;
    private Recipe<?>[] slotRecipeValue;
    private boolean[] slotRecipeValid;

    private MekCkMachineTile tile;
    private boolean busy;

    // ── MekCkRecipeExecutor ─────────────────────────────────────────────
    //
    // 种植切配是**逐槽**的：每个输入槽有自己的配方与产物，槽与槽之间互不影响，
    // 所以走接口默认的「一路一个输入槽」，不覆写 processCount。
    //
    // 营养液是唯一的共享资源，按**单槽**的量逐路扣：nutrientNeeded(1, g) = g × 100。
    // N 路合计 = N × g × 100，与旧实现 nutrientNeeded(N, g) = ceil(N × g × 100)
    // 完全相同 —— g × 100 恒为整数（1.0 / 0.1 / 0.05 / 0.01 / 0.0），ceil 是恒等变换。
    // 差别只在门禁粒度：旧实现「一次不够则整批不动」，现在「这一路不够则这一路不动」。

    /**
     * 第 {@code index} 路此刻能不能开工：这一槽有输入、有配方、装得下、土合格，
     * 且营养液够<b>这一路</b>用。
     *
     * <p>本方法每 tick 对每一路各调一次，<b>不得改动机器状态</b>；
     * {@link #tile} 的绑定与配方缓存是执行器自有状态，可以在这里刷新。</p>
     */
    @Override
    public boolean canProcess(MekCkMachineTile tile, int index) {
        this.tile = tile;
        Level level = tile == null ? null : tile.getLevel();
        List<IInventorySlot> inputs = tile == null ? null : tile.getInputSlots();
        if (level == null || inputs == null || index < 0 || index >= inputs.size()) {
            return false;
        }
        int budget = effectiveProcessCount(tile);
        if (budget <= 0) {
            return false;
        }
        ItemStack input = inputs.get(index).getStack();
        if (input.isEmpty()) {
            return false;
        }
        Optional<PlantingCuttingRecipe> found = findRecipe(index);
        if (found.isEmpty()) {
            return false;
        }
        PlantingCuttingRecipe recipe = found.get();
        if (!canFitAll(recipe, budget)) {
            // 装不下就跳过这一槽，而不是让所有槽一起停摆（与旧实现同口径）
            return false;
        }
        if (!hasValidGrowthSoil(recipe)) {
            return false;
        }
        PlantingCuttingFactoryTile owner = tile instanceof PlantingCuttingFactoryTile p ? p : null;
        double gasMult = owner == null ? 1.0 : owner.getGasConsumptionMultiplier();
        long needed = nutrientNeeded(1, gasMult);
        return needed <= 0 || owner == null || owner.hasNutrient(needed);
    }

    /**
     * 加工第 {@code index} 路一次。先调一次 {@link #canProcess} 兜底，再重新取配方执行。
     *
     * <p>{@code busy} 的复位与旧 {@code tick} 同款：开工前先清，真跑完才置位。</p>
     */
    @Override
    public void process(MekCkMachineTile tile, int index) {
        this.tile = tile;
        this.busy = false;
        if (!canProcess(tile, index)) {
            return;
        }
        int budget = effectiveProcessCount(tile);
        PlantingCuttingRecipe recipe = findRecipe(index).orElse(null);
        if (recipe == null) {
            return;
        }
        PlantingCuttingFactoryTile owner = tile instanceof PlantingCuttingFactoryTile p ? p : null;
        double gasMult = owner == null ? 1.0 : owner.getGasConsumptionMultiplier();
        long needed = nutrientNeeded(1, gasMult);
        if (needed > 0 && owner != null) {
            owner.consumeNutrient(needed);
        }
        completeRecipe(index, recipe, budget);
        this.busy = true;
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    @Override
    public void save(CompoundTag tag) {
        if (order.isActive()) {
            order.save(tag);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>键不存在时把订单整体清空</b>，而不是「什么都不做」：执行器与方块实体同寿，
     * 一次读档之后它还活着，而订单字段非 {@code null} 就会一直卡着「只加工这一张配方」。
     * 旧实现只在 {@code contains} 为真时赋值，同一次会话里重载一次就会留下无法取消的幽灵订单。
     */
    @Override
    public void load(CompoundTag tag) {
        invalidateCache();
        order.load(tag);
    }

    // ── 订单 ────────────────────────────────────────────────────────────

    public ResourceLocation getOrderRecipeId() {
        return order.getRecipeId();
    }

    @Override
    public int getOrderQuantity() {
        return order.getQuantity();
    }

    @Override
    public int getOrderCompleted() {
        return order.getCompleted();
    }

    /** 下单。{@code recipeId == null} 等价于 {@link #clearOrder()}（不留残留字段）。 */
    public void setOrder(ResourceLocation recipeId, int quantity) {
        order.setOrder(recipeId, quantity);
    }

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

    void invalidateCache() {
        slotRecipeKey = null;
        slotRecipeValue = null;
        slotRecipeValid = null;
    }

    /**
     * 输入槽指纹：物品注册名 + NBT（不含数量，配方匹配与数量无关）。
     *
     * <p>与切菜/研磨逐字相同：含数量会让「同一物品、不同堆叠数」反复失效缓存；
     * 不含 NBT 则不同附魔的同种物品会互相串味。两个都要。</p>
     */
    private static long stackKey(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0L;
        }
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        long h = (id == null ? 0 : id.hashCode());
        h = h * 31L + (stack.getTag() == null ? 0 : stack.getTag().hashCode());
        return h == 0L ? 1L : h;
    }

    /** 找出这个输入槽能做的 plantcut 配方，<b>并套用订单门禁</b>。 */
    private Optional<PlantingCuttingRecipe> findRecipe(int inputSlot) {
        Level level = tile == null ? null : tile.getLevel();
        List<IInventorySlot> inputs = tile == null ? null : tile.getInputSlots();
        if (level == null || inputs == null || inputSlot >= inputs.size()) {
            return Optional.empty();
        }
        ItemStack seed = inputs.get(inputSlot).getStack();
        if (seed.isEmpty()) {
            return Optional.empty();
        }
        RecipeType<PlantingCuttingRecipe> type = recipeType();
        if (type == null) {
            return Optional.empty();
        }
        if (slotRecipeValid == null) {
            int n = Math.max(1, inputs.size());
            slotRecipeKey = new long[n];
            slotRecipeValue = new Recipe<?>[n];
            slotRecipeValid = new boolean[n];
        }
        Optional<PlantingCuttingRecipe> found;
        if (inputSlot < slotRecipeValid.length) {
            long key = stackKey(seed);
            if (slotRecipeValid[inputSlot] && slotRecipeKey[inputSlot] == key) {
                // ofNullable：缓存里存的 null 是「查过、没有配方」这一有效结论
                @SuppressWarnings("unchecked")
                PlantingCuttingRecipe cached =
                        (PlantingCuttingRecipe) slotRecipeValue[inputSlot];
                found = Optional.ofNullable(cached);
            } else {
                found = lookup(level, type, seed);
                slotRecipeValid[inputSlot] = true;
                slotRecipeKey[inputSlot] = key;
                slotRecipeValue[inputSlot] = found.orElse(null);
            }
        } else {
            found = lookup(level, type, seed);
        }
        if (order.isActive() && (!found.isPresent() || !order.getRecipeId().equals(found.get().getId()))) {
            return Optional.empty();
        }
        return found;
    }

    /**
     * plantcut 配方类型。
     *
     * <p>返回<b>强类型</b>而不是 {@code RecipeType<?>}：
     * {@code RecipeManager.getRecipeFor} 的类型参数 {@code T} 是从
     * {@code RecipeType<T>} 推断的，调用方给 {@code RecipeType<?>} 会让推断失败。
     */
    private static RecipeType<PlantingCuttingRecipe> recipeType() {
        return UniversalCuttingMachine.PLANTING_CUTTING_RECIPE_TYPE.get();
    }

    /**
     * 单槽配方查找。
     *
     * <p>用 {@code RecipeCache.singleSlotQuery(level, type, seed)} 而不是自己造
     * {@code ItemStackHandler} + {@code RecipeWrapper}：旧实现每次调用都 new 一对匿名对象，
     * 而本方法在奇点档是每 tick × 每槽调用。
     */
    private static Optional<PlantingCuttingRecipe> lookup(
            Level level, RecipeType<PlantingCuttingRecipe> type, ItemStack seed) {
        return RecipeCache.singleSlotQuery(level, type, seed);
    }

    // ── 营养液 ──────────────────────────────────────────────────────────

    /**
     * 整批的营养液需求。
     *
     * <p>{@code ceil(活跃槽数 × 气体倍率 × 每槽 100 mB)}。走 {@code double} 中间量再
     * {@code ceil}：三个操作数都是 int 相乘会先溢出（奇点档几百万件 × 100），
     * 溢出后的负数会让 {@code needed} 变成负数，{@code needed > 0} 的门禁直接失效——
     * 机器会在零营养液下无限开工。
     */
    static long nutrientNeeded(int activeSlots, double gasMultiplier) {
        return (long) Math.ceil(activeSlots * gasMultiplier * (double) NUTRIENT_MB_PER_SLOT);
    }

    // ── 批量执行 ────────────────────────────────────────────────────────

    /**
     * 一次配方的全部潜在产出：主产物 + 次级产物（按最坏情况，次级也全中）。
     *
     * <p>次级产物带概率，但容量判定按全中算——判定只决定「这一槽本批次动不动手」，
     * 真掷骰之后落不下的部分在 {@link MekCkBatchPacking#insertOutput} 里自然少掉。
     */
    private static List<ItemStack> allOutputs(PlantingCuttingRecipe recipe, int multiplier) {
        List<ItemStack> outputs = new ArrayList<>();
        for (ItemStack result : recipe.getResults()) {
            outputs.add(scaled(result, multiplier));
        }
        for (ItemStack result : recipe.getSecondaryResults()) {
            outputs.add(scaled(result, multiplier));
        }
        return outputs;
    }

    /** 数量乘以批大小；溢出夹到 {@link Integer#MAX_VALUE}。 */
    private static ItemStack scaled(ItemStack result, int multiplier) {
        long total = (long) result.getCount() * multiplier;
        ItemStack copy = result.copy();
        copy.setCount(total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total);
        return copy;
    }

    private boolean canFitAll(PlantingCuttingRecipe recipe, int multiplier) {
        return MekCkBatchPacking.canFitAll(tile.getOutputSlots(), allOutputs(recipe, multiplier), 1);
    }

    private void completeRecipe(int inputSlot, PlantingCuttingRecipe recipe, int consumeCount) {
        List<IInventorySlot> outputs = tile.getOutputSlots();
        Level level = tile.getLevel();

        for (ItemStack result : recipe.getResults()) {
            MekCkBatchPacking.insertOutput(outputs, scaled(result, consumeCount));
        }
        // 次级产出带概率：与旧实现同款，用配方自带的 chance 掷一次
        if (level != null && level.random.nextFloat() < recipe.getSecondaryChance()) {
            for (ItemStack result : recipe.getSecondaryResults()) {
                MekCkBatchPacking.insertOutput(outputs, scaled(result, consumeCount));
            }
        }
        if (order.isActive() && order.advance(1)) {
            // 推进与判定都在 MekCkOrderState 里。修复前这里是 `orderCompleted++`（int 自增）：
            // 份数配成 Integer.MAX_VALUE 且真跑满时先绕成 MIN_VALUE，advanceOrder 的 long
            // 转换太晚 ⇒ 订单永远完不成、机器永远只认这一张配方，且不报任何错。
            order.clear();
        }
    }

    /** 该配方是否满足生长土要求（配方没要求 ⇒ 恒 true）。 */
    private boolean hasValidGrowthSoil(PlantingCuttingRecipe recipe) {
        if (recipe == null || !recipe.requiresGrowthSoil()) {
            return true;
        }
        return tile instanceof PlantingCuttingFactoryTile owner && owner.hasValidGrowthSoil(recipe);
    }

    // ── 本机下单的配方清单（供「本机下单」面板 / 未来 AE2 层消费）──────

    /** 各输入槽里的种子能做的 plantcut 配方（去重）。 */
    public List<Recipe<?>> getAvailableRecipes(Level level) {
        if (level == null || tile == null) {
            return List.of();
        }
        RecipeType<PlantingCuttingRecipe> type = recipeType();
        if (type == null) {
            return List.of();
        }
        List<IInventorySlot> inputs = tile.getInputSlots();
        if (inputs == null) {
            return List.of();
        }
        Set<ResourceLocation> seen = new HashSet<>();
        List<Recipe<?>> out = new ArrayList<>();
        for (IInventorySlot slot : inputs) {
            ItemStack seed = slot.getStack();
            if (seed.isEmpty()) {
                continue;
            }
            for (Recipe<?> recipe : RecipeCache.all(level, type)) {
                if (matchesAnyInput(recipe, seed) && seen.add(recipe.getId())) {
                    out.add(recipe);
                }
            }
        }
        return out;
    }

    /**
     * 「本机下单」面板的 Max 按钮：当前这些种子能支撑几份。
     *
     * <p>逐字取自旧实现的约定：需求为空时返回 {@code 0} 而不是
     * {@link Integer#MAX_VALUE}——面板上「Max」显示 21 亿是没意义的。</p>
     */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        if (recipe == null || tile == null) {
            return 0;
        }
        List<IInventorySlot> inputs = tile.getInputSlots();
        if (inputs == null) {
            return 0;
        }
        try {
            int max = Integer.MAX_VALUE;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) {
                    continue;
                }
                int have = 0;
                for (IInventorySlot slot : inputs) {
                    ItemStack stack = slot.getStack();
                    if (stack != null && !stack.isEmpty() && ingredient.test(stack)) {
                        have += stack.getCount();
                    }
                }
                max = Math.min(max, have);
                if (max <= 0) {
                    return 0;
                }
            }
            return max == Integer.MAX_VALUE ? 0 : max;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 该配方的任一需求项能否由这一颗种子满足（只用来筛「面板上列出哪些配方」）。 */
    static boolean matchesAnyInput(Recipe<?> recipe, ItemStack seed) {
        if (recipe == null || seed == null || seed.isEmpty()) {
            return false;
        }
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient != null && !ingredient.isEmpty() && ingredient.test(seed)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 并行数 ──────────────────────────────────────────────────────────

    /**
     * 本 tick 每槽能产出的最大份数。
     *
     * <p>上限走 {@link MekCkUpgradeTypes#capOf} 而不是旧实现的
     * {@code MekckConfig.getFactoryStackUpgradeMax(tier)}：前者先过
     * {@code isSupportedBy} 准入闸门，而读档路径中间<b>没有任何 supports 检查</b>。
     * </p>
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

    /** 供测试用：空订单的不可变视图。 */
    static List<Recipe<?>> emptyRecipes() {
        return Collections.emptyList();
    }
}
