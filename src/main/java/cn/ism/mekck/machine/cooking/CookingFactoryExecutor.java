package cn.ism.mekck.machine.cooking;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.MekCkBatchPacking;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkOrderState;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.util.CountMath;
import cn.ism.mekck.util.FluidIngredientHelper;
import cn.ism.mekck.compat.KaleidoscopeCompat;
import cn.ism.mekck.util.RecipeCache;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.Containers;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 烹饪工厂的执行器 —— 配方匹配（含回溯）、流体算量、批量、订单推进。
 *
 * <h3>与穿串执行器的三处本质差别</h3>
 * <ol>
 *   <li><b>流体</b>。配方要的水 / 奶由 {@link FluidIngredientHelper#sumFluids} 从
 *       配料里推出来（水瓶/水桶→水，奶瓶/奶桶→奶），落在三个罐里的某一个上。
 *       机器<b>从不产出流体</b>，只消耗。</li>
 *   <li><b>匹配必须回溯</b>。一个 Ingredient 可以被多个栈满足，两种不同的
 *       分配方式会导致「扣完料却做不出来」。旧实现贪心取首个匹配再回溯校验，
 *       两边不一致时症状是「材料被错误消耗、进度条空转、订单永不完成」，
 *       AE2 网络按精确物品抽料时最容易触发（AE2 时代的实测故障）。
 *       这里直接一步到位：<b>先枚举分配、确认整条配方都扣得动，再扣</b>。</li>
 *   <li><b>返还槽装的是「被消耗物品的剩余物」</b>——空碗、空瓶。
 *       FD 的 {@code CookingPotRecipe.getOutputContainer()}（碗）是<b>原料</b>，
 *       在旧实现里被当配料扣掉、<b>不再返还</b>（注释写明「matching the
 *       SmartCookingPot behaviour」，是一次已经发生过的行为变更）。</li>
 * </ol>
 *
 * <h3>流体批量的口径：本轮<b>修掉</b>了旧实现的一个真 bug</h3>
 * 旧 {@code getMaxConsumableCount} 用 {@code totalOf(type)}（<b>跨罐求和</b>）算批量，
 * 而实际 {@code drainOf} 要求<b>单个罐</b>里有足量。水被拆成两罐（600 + 600）、
 * 每份配方要 1000 mB 时：批量算成 1 → 扣料 → 出货 → <b>水一滴没扣</b>。
 * 那是「凭空造物品」级别的故障。
 * 这里改用 {@link #batchForFluid}：对每个匹配罐算 {@code 罐内量 / 每份需求}，
 * 取跨罐最大值——与 {@code drainOf} 的「单罐足量」判定<b>同一口径</b>。
 */
public final class CookingFactoryExecutor implements MekCkRecipeExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CookingFactoryExecutor.class);

    /**
     * 与旧存档<b>逐字同名</b>的键：{@code MekCkLegacyMachineNbt} 只换位置不改名。
     * 值的定义处已收进 {@link MekCkOrderState}；这里保留公开别名是为了外部引用。
     */
    public static final String TAG_ORDER_RECIPE = MekCkOrderState.TAG_ORDER_RECIPE;
    public static final String TAG_ORDER_QUANTITY = MekCkOrderState.TAG_ORDER_QUANTITY;
    public static final String TAG_ORDER_COMPLETED = MekCkOrderState.TAG_ORDER_COMPLETED;

    private CookingFactoryTile owner;
    private boolean busy;

    /**
     * 订单状态。唯一的持有者。
     *
     * <p>统一契约见 {@link MekCkOrderState}。本类此前有三处与其它家族不一致的行为，
     * 其中一处是真缺陷：{@code load} 用 {@code Math.max(0, …)} 读份数，旧存档里
     * quantity=0 的订单会落成「有配方、无份数」，而 {@code tick} 里
     * {@code batch = Math.min(batch, orderQuantity - orderCompleted)} 夹出 0 ⇒
     * 机器<b>永远不再开工</b>，而玩家除了重下一单没有任何办法解除。</p>
     */
    private final MekCkOrderState order = new MekCkOrderState();

    // ── MekCkRecipeExecutor ─────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>烹饪是<b>整机一次</b>的批次操作：一次扫描全部配料槽算出一个 batch，
     * 只跑一次操作，没有「第几路」可言。</p>
     */
    @Override
    public int processCount(MekCkMachineTile tile) {
        return 1;
    }

    /**
     * 第 0 路此刻能不能开工：有订单、有配方、批量算得出来且大于 0、<b>产物装得下</b>。
     *
     * <p>产物容量判定是补上的：缺了它，产物槽满时本方法仍返回 true，
     * {@code workCycle} 照常扣电、进度条照走，而 {@link #run} 在落槽前直接
     * {@code return} —— 玩家看不到产出、看不到告警，电却一直在掉。旧实现的
     * {@code canProcess} 里本来就有这一项（{@code canFitAll}），见类注释。</p>
     */
    @Override
    public boolean canProcess(MekCkMachineTile tile, int index) {
        this.owner = tile instanceof CookingFactoryTile c ? c : null;
        Level level = tile == null ? null : tile.getLevel();
        if (level == null || owner == null) {
            return false;
        }
        // 无订单绝不加工（旧实现第 729-730 行 // No order set - do not auto-process）。
        Recipe<?> recipe = findRecipe(level);
        if (recipe == null) {
            return false;
        }
        List<IInventorySlot> scan = owner.ingredientSlots();
        int batch = batchSize(recipe, scan);
        batch = order.remainingOrUnlimited(batch);
        if (batch <= 0) {
            return false;
        }
        return canFitBatch(owner.productSlots(), recipe.getResultItem(level.registryAccess()), batch);
    }

    @Override
    public void process(MekCkMachineTile tile, int index) {
        this.owner = tile instanceof CookingFactoryTile c ? c : null;
        this.busy = false;
        if (!canProcess(tile, index)) {
            return;
        }
        Level level = tile.getLevel();
        Recipe<?> recipe = findRecipe(level);
        if (recipe == null) {
            return;
        }
        List<IInventorySlot> scan = owner.ingredientSlots();
        int batch = batchSize(recipe, scan);
        batch = order.remainingOrUnlimited(batch);
        if (batch <= 0) {
            return;
        }
        run(level, recipe, batch, scan);
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    @Override
    public void save(CompoundTag tag) {
        order.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        order.load(tag);
    }

    // ── 订单 ────────────────────────────────────────────────────────────

    private Recipe<?> findRecipe(Level level) {
        if (!order.hasRecipe()) {
            return null;
        }
        for (Recipe<?> recipe : availableRecipes(level, owner == null ? null : owner.getTier())) {
            if (order.getRecipeId().equals(recipe.getId())) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * 本机当前可下单的配方（供界面列举）。四个来源，按旧实现的次序「先到先得」：
     * <ol>
     *   <li>{@code farmersdelight:cooking}</li>
     *   <li>{@code farm_and_charm:pot_cooking}</li>
     *   <li>{@code avaritia_delight:extreme_cooking_*} —— <b>仅 SINGULARITY 档</b></li>
     *   <li>森罗物语 Cookery 的 stockpot / pot / flex（该 mod 未装时整条跳过）</li>
     * </ol>
     * 出口做两级去重：先按配方 id，再按<b>产物物品 id + NBT</b>——
     * FD 与森罗 / 终焉的同款食物只留一条（与旧 {@code getAvailableRecipes} 逐字同款）。
     */
    public static List<Recipe<?>> availableRecipes(Level level, CuttingMachineFactoryTier tier) {
        List<Recipe<?>> out = new ArrayList<>();
        Set<ResourceLocation> seenIds = new HashSet<>();
        Set<String> seenResults = new HashSet<>();
        for (Recipe<?> recipe : allSourceRecipes(level, tier)) {
            if (!seenIds.add(recipe.getId())) {
                continue;
            }
            ItemStack result = recipe.getResultItem(level.registryAccess());
            String resultKey = result.isEmpty()
                    ? recipe.getId().toString()
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(result.getItem()) + "/"
                            + result.getTag();
            if (seenResults.add(resultKey)) {
                out.add(recipe);
            }
        }
        return out;
    }

    /** 四个来源的原始配方表（未去重），供界面与执行器共用。 */
    private static List<Recipe<?>> allSourceRecipes(Level level, CuttingMachineFactoryTier tier) {
        List<Recipe<?>> out = new ArrayList<>();
        RecipeType<?> fd = RecipeCache.type("farmersdelight", "cooking");
        if (fd != null) {
            out.addAll(RecipeCache.all(level, fd));
        }
        RecipeType<?> fac = RecipeCache.type("farm_and_charm", "pot_cooking");
        if (fac != null) {
            out.addAll(RecipeCache.all(level, fac));
        }
        addExtremeCooking(level, out, tier);
        if (KaleidoscopeCompat.isLoaded()) {
            out.addAll(KaleidoscopeCompat.getAllKaleidoscopeRecipes(level));
        }
        return out;
    }

    /**
     * 终焉料理的两个配方类型 —— <b>仅 SINGULARITY 档</b>。
     *
     * <p>与旧 {@code isItemValid} 的等级分流同款：其余等级<b>不接受</b>
     * {@code extreme_cooking} 食材（放进去就取不出来，见旧实现第 311-314 行）。</p>
     */
    private static void addExtremeCooking(Level level, List<Recipe<?>> out, CuttingMachineFactoryTier tier) {
        if (tier != CuttingMachineFactoryTier.SINGULARITY) {
            return;
        }
        for (String path : List.of("extreme_cooking_shaped", "extreme_cooking_shapeless")) {
            RecipeType<?> type = RecipeCache.type("avaritia_delight", path);
            if (type != null) {
                out.addAll(RecipeCache.all(level, type));
            }
        }
    }

    @Override
    public boolean hasOrder() {
        return order.isActive();
    }

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

    /** 下单。{@code recipeId == null} 等价于 {@link #clearOrder()}。 */
    public void setOrder(ResourceLocation recipeId, int quantity) {
        order.setOrder(recipeId, quantity);
    }

    public void clearOrder() {
        order.clear();
    }

    // ── 批量算量 ────────────────────────────────────────────────────────

    /** 配方要扣的全部固体配料（每单位一份），按旧实现的三个来源拼成一张平表。 */
    private static List<Ingredient> allToConsume(Recipe<?> recipe) {
        List<Ingredient> all = new ArrayList<>(solidIngredients(recipe));
        all.addAll(extraConsumables(recipe));
        ItemStack container = consumedContainer(recipe);
        if (!container.isEmpty()) {
            all.add(Ingredient.of(container.getItem()));
        }
        return all;
    }

    /**
     * 固体配料。
     *
     * <p>森罗配方走 {@code KaleidoscopeCompat} 的专用读取；其余走
     * {@code recipe.getIngredients()} 并<b>过滤掉两类</b>：空 Ingredient
     * （九宫格 shaped 配方的空格，不过滤的话回溯分配必然失败）、
     * 以及承载流体的水瓶 / 奶瓶（它们转成流体需求，见
     * {@link FluidIngredientHelper#classify}，不作为固体扣）。</p>
     */
    public static List<Ingredient> solidIngredients(Recipe<?> recipe) {
        List<Ingredient> list = new ArrayList<>();
        if (KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)) {
            list.addAll(KaleidoscopeCompat.getSolidIngredients(recipe));
            return list;
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            if (!FluidIngredientHelper.classify(ingredient).isEmpty()) {
                continue;
            }
            list.add(ingredient);
        }
        return list;
    }

    /**
     * 承载流体的配料（水瓶 / 水桶 / 奶瓶 / 奶桶）。
     *
     * <p>机器侧它们<b>不作为固体扣</b>——配方要的水 / 奶由
     * {@link FluidIngredientHelper#sumFluids} 推出来、从罐里抽。所以这里返回
     * 「被 {@link #solidIngredients} 过滤掉的那一部分」，两个方法合起来恰好等于
     * {@code recipe.getIngredients()} 去掉空 Ingredient。</p>
     *
     * <p><b>但 AE2 侧仍然要投这些瓶子</b>：机器的罐只能靠「存储区里的水瓶
     * 每 20 tick 自动转罐」被填上，AE2 插不进流体罐。所以 {@code MekckAe2}
     * 的投入清单要显式列出它们——两个方法口径不同，正是因为机器<b>扣</b>的是流体、
     * AE2<b>投</b>的必须是瓶子。</p>
     */
    public static List<Ingredient> fluidBottleIngredients(Recipe<?> recipe) {
        List<Ingredient> list = new ArrayList<>();
        if (KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)) {
            // 森罗配方的载体由它自己的 getSolidIngredients / getExtraConsumables 覆盖，
            // 这里不重复列出——否则 AE2 会把同一个东西投两次。
            return list;
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            if (!FluidIngredientHelper.classify(ingredient).isEmpty()) {
                list.add(ingredient);
            }
        }
        return list;
    }

    /**
     * 额外消耗物（森罗的 carrier / oil 之类）。非森罗配方恒空。
     *
     * <p>{@code public} 是因为 {@code MekckAe2} 的面板算份数时也读它。</p>
     */
    public static List<Ingredient> extraConsumables(Recipe<?> recipe) {
        return KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)
                ? KaleidoscopeCompat.getExtraConsumables(recipe)
                : new ArrayList<>();
    }

    /**
     * 「用完就没了」的被消耗容器 —— FD 的 {@code CookingPotRecipe.getOutputContainer()}（碗）。
     *
     * <p>它被<b>当成配料扣掉、不再返还</b>：注释写明这是「matching the
     * SmartCookingPot behaviour」，是一次<b>已经发生过的行为变更</b>，不要改回旧语义。
     * 森罗配方恒返空（它自己的 carrier 走 {@link #extraConsumables}）。</p>
     *
     * <p>非 FD 的配方用反射试 {@code getOutputContainer()}（方法句柄由
     * {@code Reflect} 缓存，不每次调用重新解析签名）。</p>
     */
    public static ItemStack consumedContainer(Recipe<?> recipe) {
        if (KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)) {
            return ItemStack.EMPTY;
        }
        if (recipe instanceof vectorwing.farmersdelight.common.crafting.CookingPotRecipe cooking) {
            return cooking.getOutputContainer();
        }
        Object container = cn.ism.mekck.util.Reflect.call(recipe, "getOutputContainer");
        return container instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    /**
     * 「水」判据。逐字对齐旧 {@code MultiFluidHandler.isWater}：
     * <b>先判非空再 {@code isSame}</b>——空 {@code FluidStack} 的
     * {@code getFluid()} 返回 {@code Fluids.EMPTY}，对空栈调 {@code isSame}
     * 本身不会炸，但把「空」和「水」混在一处判会让「奶」那一支把空罐算进去。
     */
    public static boolean isWater(FluidStack stack) {
        return !stack.isEmpty() && stack.getFluid().isSame(Fluids.WATER);
    }

    /** 「非水（奶等）」判据。罐的校验器接受任意流体，所以岩浆也能顶替奶。 */
    public static boolean isNotWater(FluidStack stack) {
        return !stack.isEmpty() && !stack.getFluid().isSame(Fluids.WATER);
    }

    /** 抽成静态纯函数，好进裸 JVM 单测。语义与穿串的同名方法一致。 */
    public static int batchForMaterial(int available, int perUnit) {
        if (available <= 0) {
            return 0;
        }
        return perUnit <= 0 ? Integer.MAX_VALUE : available / perUnit;
    }

    /**
     * 流体能支撑几份 —— <b>与 {@code drainOf} 同一口径</b>。
     *
     * <p>{@code drainOf} 的语义是「<b>某一个</b>匹配罐里有足量就抽那一个」，
     * 所以这里对每个匹配罐算 {@code 罐内量 / 每份需求} 再取<b>跨罐最大值</b>。
     * 旧实现用跨罐<b>求和</b>，与实际扣减口径不一致（见类注释那段 bug 说明）。</p>
     *
     * @param water    true = 求水，false = 求非水（奶）
     * @param perUnit  每份配方对该种流体的需求（mb）；{@code <= 0} 表示这张配方不用它
     */
    public static int batchForFluid(IExtendedFluidTank[] tanks, boolean water, int perUnit) {
        if (perUnit <= 0) {
            return Integer.MAX_VALUE;
        }
        if (tanks == null) {
            return batchForFluidAmounts(null, null, perUnit);
        }
        int[] amounts = new int[tanks.length];
        boolean[] matches = new boolean[tanks.length];
        for (int i = 0; i < tanks.length; i++) {
            IExtendedFluidTank tank = tanks[i];
            if (tank == null) {
                continue;
            }
            FluidStack fluid = tank.getFluid();
            matches[i] = water ? isWater(fluid) : isNotWater(fluid);
            amounts[i] = matches[i] ? fluid.getAmount() : 0;
        }
        return batchForFluidAmounts(amounts, matches, perUnit);
    }

    /**
     * 流体批量的<b>纯算术</b>——{@link #batchForFluid} 的内核。
     *
     * <p><b>为什么拆出来</b>：{@code FluidStack} 与 {@code Fluids.WATER} 在裸 JVM 里
     * 会触发 Minecraft 的引导初始化（{@code ExceptionInInitializerError}），
     * 真罐 {@code IExtendedFluidTank} 更造不出来。所以测试只能打这一层——
     * 算术本身与「跨罐取最大」的口径都在这里，拆开并没有让测试变得自说自话。
     * 同 {@code TestRandomizeUpgradeBranches} / {@code TestSkeweringToolBatchArithmetic} 的处理。</p>
     *
     * <p><b>注意 null / 空数组返回 0 而不是 {@link Integer#MAX_VALUE}</b>：
     * 「没有罐」=「一种流体都没有」，与「这张配方不用它」（{@code perUnit <= 0}）
     * 是两回事。写成 MAX_VALUE 会让 {@link #batchSize} 的 {@code Math.min} 拿到上界，
     * 于是需要流体的配方在完全没有流体时仍被判为可做 —— 正是本轮要修的
     * 那类「凭空造物品」。这条由 {@code TestCookingFluidBatchArithmetic} 钉住。</p>
     *
     * @param amounts 每个罐的液体量（mB）
     * @param matches 每个罐是否匹配所求种类
     */
    public static int batchForFluidAmounts(int[] amounts, boolean[] matches, int perUnit) {
        if (perUnit <= 0) {
            return Integer.MAX_VALUE;
        }
        if (amounts == null || matches == null || amounts.length != matches.length) {
            return 0;
        }
        int best = 0;
        for (int i = 0; i < amounts.length; i++) {
            if (matches[i] && amounts[i] > 0) {
                best = Math.max(best, amounts[i] / perUnit);
            }
        }
        return best;
    }

    private int batchSize(Recipe<?> recipe, List<IInventorySlot> scan) {
        List<Ingredient> all = allToConsume(recipe);
        if (all.isEmpty()) {
            return 0;
        }
        // 先取解析上界：每种配料每个单位各要 1 个，所以「任一配料的总可用数」
        // 就是批量的天然上界。用它给下面的可行性循环封顶——否则循环次数会直接
        // 取到 stackMultiplier()，奇点创世那档是 21 亿级。
        int bound = Integer.MAX_VALUE;
        for (Ingredient ingredient : all) {
            bound = Math.min(bound, availableCount(scan, ingredient));
        }
        // 流体
        FluidIngredientHelper.FluidInfo need = FluidIngredientHelper.sumFluids(recipe.getIngredients());
        IExtendedFluidTank[] tanks = owner.cookingFluidTanks();
        bound = Math.min(bound, batchForFluid(tanks, true, need.waterMb));
        bound = Math.min(bound, batchForFluid(tanks, false, need.milkMb));
        bound = Math.min(bound, owner.stackMultiplier());
        if (bound <= 0) {
            return 0;
        }
        // 再逐单位「真消耗」地校验可行性。只证明「1 份可行」是错的——两个 Ingredient
        // 能被同一栈同时满足时（重叠配料），第 k 份的互异槽位分配可能失败；旧实现在
        // 未被消耗的同一 scan 上反复调用纯函数 findAssignment，每轮结果恒同，于是把
        // 解析上界 bound 当成了可行份数。planUnits 在「槽位计数镜像」上逐单位递减，
        // 与 consumeIngredients 共用同一入口，「预检说能做 N 份」严格蕴含「这 N 份都扣得动」。
        return planUnits(scan, all, bound).length;
    }

    /** 某种配料在「输入 + 存储」里一共还剩几个。配料为空视为无限。 */
    private static int availableCount(List<IInventorySlot> scan, Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int total = 0;
        for (IInventorySlot slot : scan) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && ingredient.test(stack)) {
                total = CountMath.addClamp(total, stack.getCount());
            }
        }
        return total;
    }

    // ── 加工 ────────────────────────────────────────────────────────────

    private void run(Level level, Recipe<?> recipe, int batch, List<IInventorySlot> scan) {
        List<IInventorySlot> outputs = owner.productSlots();
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (!canFitBatch(outputs, result, batch)) {
            return;
        }
        ItemStack produced = batchProduct(result, batch);

        // 先扣流体再扣料——旧实现第 590-592 行的次序。
        // 两者都按「已算出的 batch」扣，而 batch 本身已与 drain 的口径一致，
        // 所以不会出现「扣了料但流体没扣」那种凭空造物的形态。
        consumeFluid(recipe, batch);
        if (!consumeIngredients(scan, recipe, batch)) {
            return;
        }

        MekCkBatchPacking.insertOutput(outputs, produced);
        busy = true;
        // 推进与判定都在 MekCkOrderState 里（加法整体走 long，不会像 `orderCompleted++`
        // 那样先在 int 上溢出）。批量语义是「本批做掉 batch 份」。
        if (order.advance(batch)) {
            order.clear();
        }
    }

    /**
     * 本批产物预览栈 —— {@link #canFitBatch} 与 {@link #run} 共用的唯一算法。
     *
     * <p>不可产出（配方无产物 / 数量溢出）时返回 {@link ItemStack#EMPTY}：
     * {@code MekCkBatchPacking.canFitAll} 对空栈是<b>跳过</b>（返回 true），
     * 所以「空产物」必须在这里拦掉，不能指望容量判定。</p>
     */
    private static ItemStack batchProduct(ItemStack result, int batch) {
        if (result == null || result.isEmpty() || batch <= 0) {
            return ItemStack.EMPTY;
        }
        int resultCount = CountMath.mulClamp(Integer.MAX_VALUE, result.getCount(), batch);
        if (resultCount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack produced = result.copy();
        produced.setCount(resultCount);
        return produced;
    }

    /**
     * 本批产物装不装得下 —— {@link #canProcess} 与 {@link #run} 共用的唯一判据。
     *
     * <p>抽成 {@code static} 纯函数是为了能在裸 JVM 里断言（真 tile 造不出来，
     * 见 {@code TestCookingFactoryEnergyDrain}）。两处共用同一入口，才不会出现
     * 「预检说能加工、落槽时却装不下」的漂移。</p>
     */
    public static boolean canFitBatch(List<IInventorySlot> outputs, ItemStack result, int batch) {
        if (outputs == null || outputs.isEmpty()) {
            return false;
        }
        ItemStack produced = batchProduct(result, batch);
        if (produced.isEmpty()) {
            return false;
        }
        return MekCkBatchPacking.canFitAll(outputs, List.of(produced), 1);
    }

    /**
     * 扣流体。按「先校验过、必扣得动」的前提调 {@code extract}。
     *
     * <p>用 {@link AutomationType#MANUAL} 而不是 EXTERNAL：Mek 的罐把
     * {@code notExternal} 绑在 <b>canExtract</b> 上（与
     * {@code MachineEnergyContainer.input} 同款坑），传 EXTERNAL 会被整条拒掉、
     * 一滴都不扣。理由与基类 {@code deductEnergy} 的注释逐字相同。</p>
     */
    private void consumeFluid(Recipe<?> recipe, int batch) {
        FluidIngredientHelper.FluidInfo need = FluidIngredientHelper.sumFluids(recipe.getIngredients());
        if (need.isEmpty() || batch <= 0) {
            return;
        }
        extractFluid(true, CountMath.mulClamp(Integer.MAX_VALUE, need.waterMb, batch));
        extractFluid(false, CountMath.mulClamp(Integer.MAX_VALUE, need.milkMb, batch));
    }

    private void extractFluid(boolean water, int amount) {
        if (amount <= 0) {
            return;
        }
        IExtendedFluidTank[] tanks = owner.cookingFluidTanks();
        if (tanks == null) {
            return;
        }
        for (IExtendedFluidTank tank : tanks) {
            FluidStack fluid = tank.getFluid();
            boolean match = water ? isWater(fluid) : isNotWater(fluid);
            if (match && fluid.getAmount() >= amount) {
                tank.extract(amount, Action.EXECUTE, AutomationType.MANUAL);
                return;
            }
        }
    }

    /**
     * 扣固体配料 —— 按 {@link #planUnits} 算出的分配计划逐单位「各扣 1 个」。
     *
     * <p><b>必须回溯而不是贪心</b>：一个 Ingredient 可以被多个栈满足，
     * 而一张配方可能有两个 Ingredient 都能被同一个栈满足。贪心取首个匹配时，
     * 第一个 Ingredient 抢走了第二个唯一能用的栈，于是「校验说能做、扣的时候扣不动」
     * ——旧实现的症状是材料被错误消耗、进度条空转、订单永不完成，
     * AE2 网络按精确物品抽料时最容易触发（AE2 时代的实测故障）。</p>
     *
     * <p>预检（{@link #batchSize}）与这里<b>共用同一分配入口</b> {@link #planUnits}：
     * 预检返回的份数就是该计划的行数，所以按计划逐单位扣减不会中途失败——
     * 旧实现「扣了一半失败、整批流体已扣、零产出且无日志」的路径至此消失。</p>
     *
     * <p>返还物按<b>每个单位的实际匹配栈</b>算：空碗/空瓶来自那件被扣掉的物品，
     * 不是来自配方声明（同一个 Ingredient 匹配到水桶与水瓶时返还物不同）。</p>
     *
     * @return false = 扣不动（计划与真实槽位不一致，理论上不该发生）；
     *         此时打 WARN 记录配方 id / 请求份数 / 失败单位，不静默
     */
    private boolean consumeIngredients(List<IInventorySlot> scan, Recipe<?> recipe, int batch) {
        List<Ingredient> all = allToConsume(recipe);
        if (all.isEmpty()) {
            return true;
        }
        // 返还物先攒着、最后统一落槽：逐单位边扣边插会让扫描集合在本单位中途变形，
        // 而扫描集合正是下一单位分配的下标依据（旧实现第 1206-1208 行同款理由）。
        List<ItemStack> pendingReturns = new ArrayList<>();
        int[][] plan = planUnits(scan, all, batch);
        if (plan.length < batch) {
            LOGGER.warn("CookingFactory batch feasibility diverged from live inventory: recipe={}, requested={}, plannable={}",
                    recipe.getId(), batch, plan.length);
            return false;
        }
        int consumed = consumePlan(scan, all, plan, pendingReturns);
        giveBackRemainingItems(pendingReturns);
        if (consumed < batch) {
            LOGGER.warn("CookingFactory ingredient consumption stopped early: recipe={}, requested={}, consumed={}",
                    recipe.getId(), batch, consumed);
            return false;
        }
        return true;
    }

    /**
     * 按计划逐单位扣减真实槽位。返回<b>完整扣完的份数</b>，任一槽与计划不符即停在当前份。
     *
     * <p>抽成 {@code static} 是为能在裸 JVM 里用假槽位断言（真机器需要
     * {@code BlockEntityType} 注册表），也保证它与预检用的镜像分配走同一份计划。</p>
     */
    static int consumePlan(List<IInventorySlot> scan, List<Ingredient> all,
                           int[][] plan, List<ItemStack> pendingReturns) {
        int unit = 0;
        for (int[] assignment : plan) {
            for (int i = 0; i < all.size(); i++) {
                int slotIndex = assignment[i];
                if (slotIndex < 0 || slotIndex >= scan.size()) {
                    return unit;
                }
                ItemStack stack = scan.get(slotIndex).getStack();
                if (stack.isEmpty()) {
                    return unit;
                }
                // 快照是「缩量之前」的那 1 个：剩余物判定看的是被扣掉的是哪件东西。
                ItemStack snapshot = stack.copyWithCount(1);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    scan.get(slotIndex).setStack(ItemStack.EMPTY);
                }
                pendingReturns.addAll(
                        FluidIngredientHelper.getReturnStacksForConsumed(snapshot, all.get(i)));
            }
            unit++;
        }
        return unit;
    }

    /**
     * 逐单位分配计划 —— <b>预检与消耗共用的唯一入口</b>。
     *
     * <p>在「槽位计数镜像」上跑与真实扫描同一套回溯分配：{@code sample} 记每个槽当前的
     * 代表栈（耗尽即置空）、{@code remaining} 记剩余个数。每定下一单位的
     * {@code 配料 → 槽下标} 分配，就按分配把对应槽的计数减 1，于是<b>下一单位看到的是
     * 已被消耗过的状态</b>——这正是旧实现缺的一步（它对未被消耗的 scan 反复调用纯函数，
     * 每轮结果恒同，只证明 1 份可行）。</p>
     *
     * <p>返回数组的行数即真实可行份数（{@code <= maxUnits}）；每行与该单位在真实槽位上
     * 应执行的分配一致，故 {@link #consumePlan} 按此逐单位扣减不会中途失败。</p>
     *
     * @param maxUnits 解析上界（由 {@link #batchSize} 用配料总数 / 流体 / 倍增给出），
     *                 也即循环封顶——避免对奇点档的 {@code stackMultiplier} 空转
     */
    static int[][] planUnits(List<IInventorySlot> scan, List<Ingredient> all, int maxUnits) {
        if (scan == null || all == null || all.isEmpty() || maxUnits <= 0) {
            return new int[0][];
        }
        int slots = scan.size();
        ItemStack[] sample = new ItemStack[slots];
        int[] remaining = new int[slots];
        for (int i = 0; i < slots; i++) {
            ItemStack stack = scan.get(i).getStack();
            // sample 只作匹配读，不写回真实槽位；remaining 为 0 时视为空。
            sample[i] = stack.isEmpty() ? ItemStack.EMPTY : stack;
            remaining[i] = stack.isEmpty() ? 0 : stack.getCount();
        }
        List<int[]> plan = new ArrayList<>();
        for (int unit = 0; unit < maxUnits; unit++) {
            int[] assignment = findAssignment(sample, remaining, all);
            if (assignment == null) {
                break;
            }
            plan.add(assignment);
            for (int i = 0; i < all.size(); i++) {
                int slot = assignment[i];
                if (--remaining[slot] <= 0) {
                    sample[slot] = ItemStack.EMPTY;
                }
            }
        }
        return plan.toArray(new int[0][]);
    }

    /**
     * 回溯分配（作用在 {@link #planUnits} 的计数镜像上）：给每种配料挑一个互不相同的槽，
     * 返回 {@code all.get(i) → 槽下标}，无解时返回 null。{@code remaining[i] <= 0} 的槽视作空。
     *
     * <p>排序用「候选最少的配料先分」（最少剩余值启发式），否则
     * 144 格存储的扫描里最坏情况会退化成阶乘。逐字对齐旧实现的
     * {@code findCustomMatches} + {@code backtrackCustomMatches}。</p>
     */
    static int[] findAssignment(ItemStack[] sample, int[] remaining, List<Ingredient> ingredients) {
        int count = ingredients.size();
        List<List<Integer>> matches = new ArrayList<>(count);
        for (Ingredient ingredient : ingredients) {
            List<Integer> matching = new ArrayList<>();
            for (int i = 0; i < sample.length; i++) {
                if (remaining[i] > 0 && !sample[i].isEmpty() && ingredient.test(sample[i])) {
                    matching.add(i);
                }
            }
            if (matching.isEmpty()) {
                return null;
            }
            matches.add(matching);
        }
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            order.add(i);
        }
        order.sort(java.util.Comparator.comparingInt(i -> matches.get(i).size()));
        int[] result = new int[count];
        boolean[] used = new boolean[sample.length];
        return backtrack(matches, order, 0, used, result) ? result : null;
    }

    private static boolean backtrack(List<List<Integer>> matches, List<Integer> order, int depth,
                                     boolean[] used, int[] result) {
        if (depth >= order.size()) {
            return true;
        }
        int ingredientIndex = order.get(depth);
        for (int inputIndex : matches.get(ingredientIndex)) {
            if (used[inputIndex]) {
                continue;
            }
            used[inputIndex] = true;
            result[ingredientIndex] = inputIndex;
            if (backtrack(matches, order, depth + 1, used, result)) {
                return true;
            }
            used[inputIndex] = false;
        }
        return false;
    }

    /**
     * 落返还物（空碗、空瓶…）。返还槽装不下就<b>掉在机器头顶</b>——
     * 那是显式的 fail-safe（items are never silently lost），不是溢出 bug。
     * 逐字对应旧实现 {@code distributeToSlotsOrDrop}。</p>
     */
    private void giveBackRemainingItems(List<ItemStack> pending) {
        List<IInventorySlot> returns = owner.returnSlots();
        for (ItemStack remainder : pending) {
            if (remainder == null || remainder.isEmpty()) {
                continue;
            }
            if (!returns.isEmpty() && MekCkBatchPacking.canFitAll(returns, List.of(remainder), 1)) {
                MekCkBatchPacking.insertOutput(returns, remainder);
                continue;
            }
            dropAtMachine(level(), remainder);
        }
    }

    private Level level() {
        return owner == null ? null : owner.getLevel();
    }

    private void dropAtMachine(Level level, ItemStack stack) {
        if (level == null || level.isClientSide || stack.isEmpty()) {
            return;
        }
        BlockPos pos = owner.getBlockPos();
        Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, stack);
    }

    /** 供界面列举「这张配方还缺什么」用。 */
    public static Optional<Recipe<?>> findAvailable(Level level, CuttingMachineFactoryTier tier,
                                                    ResourceLocation id) {
        if (id == null || level == null) {
            return Optional.empty();
        }
        for (Recipe<?> recipe : availableRecipes(level, tier)) {
            if (id.equals(recipe.getId())) {
                return Optional.of(recipe);
            }
        }
        return Optional.empty();
    }
}
