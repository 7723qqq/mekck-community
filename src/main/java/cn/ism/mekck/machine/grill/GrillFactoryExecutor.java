package cn.ism.mekck.machine.grill;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.machine.MekCkBatchPacking;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkOrderState;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import cn.ism.mekck.util.BarbequesDelightCompat;
import cn.ism.mekck.util.CountMath;
import cn.ism.mekck.util.KaleidoscopeGrillingCompat;
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
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 烧烤工厂的执行器 —— 烤制配方匹配、订单推进、调味料应用、并行数。
 *
 * <h3>与研磨执行器的三处<b>本质</b>差别</h3>
 * <ol>
 *   <li><b>调味料是订单级状态，会改写产物本身。</b>它写进产物的 NBT
 *       （{@code BarbequesDelightCompat.applySeasoning}）并按产出个数<b>消耗调味料耐久</b>。
 *       因此容量判定必须用「已调味」的预览栈——NBT 不同就是不同物品，
 *       用未调味的栈判容量会判少。</li>
 *   <li><b>调味料槽有启用开关</b>（位标志 {@code SeasoningEnabled}），
 *       默认模式下在启用的槽里挑剩余次数最多的那个用。</li>
 *   <li><b>工作模式</b>（{@code DEFAULT} / {@code ORDER}）：
 *       {@code DEFAULT} 自动调味，{@code ORDER} 只在订单指定了调味时才用。</li>
 * </ol>
 */
public final class GrillFactoryExecutor implements MekCkRecipeExecutor {

    /** 调味料槽数。与旧实现同名常量同值。 */
    public static final int SEASONING_SLOTS = 3;

    /** 与旧存档<b>逐字同名</b>的键：{@code MekCkLegacyMachineNbt} 只换位置不改名。 */
    public static final String TAG_ORDER_RECIPE = MekCkOrderState.TAG_ORDER_RECIPE;
    public static final String TAG_ORDER_QUANTITY = MekCkOrderState.TAG_ORDER_QUANTITY;
    public static final String TAG_ORDER_COMPLETED = MekCkOrderState.TAG_ORDER_COMPLETED;
    public static final String TAG_ORDER_SEASONING = "OrderSeasoning";
    public static final String TAG_WORK_MODE = "WorkMode";
    public static final String TAG_SEASONING_ENABLED = "SeasoningEnabled";

    /** 工作模式。与旧 {@code GrillFactoryBlockEntity.WorkMode} 同名同序。 */
    public enum WorkMode {
        DEFAULT, ORDER;

        public static WorkMode byOrdinal(int ordinal) {
            WorkMode[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : DEFAULT;
        }
    }

    // ── 执行器自有状态 ──────────────────────────────────────────────────

    /**
     * 订单状态。唯一的持有者。
     *
     * <p>第四轮从三个手写字段（{@code orderRecipeId/orderQuantity/orderCompleted}）换成
     * {@link MekCkOrderState}，与烹饪 / 研磨 / 种植切配对齐（6 个执行器同一份契约）。
     * 值与语义都没变 —— {@link MekCkOrderState} 的三个存档键与此处原有的
     * {@code OrderRecipeId/OrderQuantity/OrderCompleted} <b>逐字相同</b>，
     * 所以既有存档不受影响。</p>
     *
     * <p><b>调味料（{@link #orderSeasoning}）刻意留在本类</b>：它是烧烤独有的
     * 「订单附带指定调味」语义，塞进公共状态类就得让另外 5 个家族背一个用不到的字段。
     * 它与订单生命周期绑定（清订单必清调味），故读写两侧都紧挨着订单代码。</p>
     */
    private final MekCkOrderState order = new MekCkOrderState();

    private String orderSeasoning;
    private WorkMode workMode = WorkMode.DEFAULT;
    private final boolean[] seasoningEnabled = new boolean[SEASONING_SLOTS];

    // ── 配方缓存 ────────────────────────────────────────────────────────

    private long[] slotRecipeKey;
    private Recipe<?>[] slotRecipeValue;
    private boolean[] slotRecipeValid;

    private MekCkMachineTile tile;
    private GrillFactoryTile owner;
    private boolean busy;

    // ── MekCkRecipeExecutor ─────────────────────────────────────────────

    /**
     * 第 {@code index} 路此刻能不能开工 —— 判定条件与旧 {@code tick} 的循环体逐字一致。
     *
     * <p>容量判定必须用<b>已调味</b>的预览栈（见 {@link #completeRecipe}），
     * 否则会「预演说装得下、落槽只塞一半」。</p>
     */
    @Override
    public boolean canProcess(MekCkMachineTile tile, int index) {
        this.tile = tile;
        this.owner = tile instanceof GrillFactoryTile g ? g : null;

        Level level = tile == null ? null : tile.getLevel();
        List<IInventorySlot> inputs = tile == null ? null : tile.getInputSlots();
        List<IInventorySlot> outputs = tile == null ? null : tile.getOutputSlots();
        if (level == null || inputs == null || outputs == null || outputs.isEmpty()
                || index < 0 || index >= inputs.size()) {
            return false;
        }
        ItemStack input = inputs.get(index).getStack();
        if (input.isEmpty()) {
            return false;
        }
        Optional<Recipe<?>> found = findRecipe(index);
        if (found.isEmpty()) {
            return false;
        }
        int budget = effectiveProcessCount(tile);
        if (budget <= 0) {
            return false;
        }
        int consumeCount = Math.min(budget, input.getCount());
        if (consumeCount <= 0) {
            return false;
        }
        ItemStack result = found.get().getResultItem(level.registryAccess());
        if (result.isEmpty()) {
            return false;
        }
        return canFitAll(outputs, seasonedPreview(result, currentSeasoningFor(result)), consumeCount);
    }

    @Override
    public void process(MekCkMachineTile tile, int index) {
        this.tile = tile;
        this.busy = false;
        if (!canProcess(tile, index)) {
            return;
        }
        List<IInventorySlot> inputs = tile.getInputSlots();
        Recipe<?> recipe = findRecipe(index).orElse(null);
        if (recipe == null) {
            return;
        }
        int consumeCount = Math.min(effectiveProcessCount(tile), inputs.get(index).getStack().getCount());
        completeRecipe(index, recipe, consumeCount);
        this.busy = true;
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    @Override
    public void save(CompoundTag tag) {
        // 三个订单键由 MekCkOrderState 一处写；键名与迁移前逐字相同，既有存档不受影响。
        order.save(tag);
        if (orderSeasoning != null && !orderSeasoning.isEmpty()) {
            tag.putString(TAG_ORDER_SEASONING, orderSeasoning);
        }
        tag.putInt(TAG_WORK_MODE, workMode.ordinal());
        tag.putInt(TAG_SEASONING_ENABLED, seasoningFlags());
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>键不存在时把订单整体清空</b>，与研磨/种植切配同款理由：
     * 执行器与方块实体同寿，只在 {@code contains} 为真时赋值会留下无法取消的幽灵订单。
     * 但 {@code WorkMode} 与 {@code SeasoningEnabled} 是<b>常驻状态</b>而非订单的一部分，
     * 缺失时按默认值重建，不清空。
     * </p>
     */
    @Override
    public void load(CompoundTag tag) {
        invalidateCache();
        // 键不存在即「无订单」（整体清空）：执行器与方块实体同寿，只在 contains 为真时
        // 赋值会留下无法取消的幽灵订单。
        order.load(tag);
        if (!order.isActive()) {
            // 无订单时不得残留调味料：它会在「自由投料」下强制调味，且会被 save 持久化。
            orderSeasoning = null;
        }
        if (tag != null) {
            orderSeasoning = tag.contains(TAG_ORDER_SEASONING, Tag.TAG_STRING)
                    ? tag.getString(TAG_ORDER_SEASONING) : null;
            if (orderSeasoning != null && orderSeasoning.isEmpty()) {
                orderSeasoning = null;
            }
            workMode = WorkMode.byOrdinal(tag.getInt(TAG_WORK_MODE));
            int flags = tag.getInt(TAG_SEASONING_ENABLED);
            for (int i = 0; i < SEASONING_SLOTS; i++) {
                seasoningEnabled[i] = (flags & (1 << i)) != 0;
            }
        }
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

    /** 订单指定的调味料 id；{@code null} / 空串表示不指定。 */
    public String getOrderSeasoning() {
        return orderSeasoning;
    }

    /**
     * 设订单。
     *
     * <p><b>逐字对齐旧 {@code GrillFactoryBlockEntity.setOrder}</b>：数量下界取
     * {@code max(0, …)} 而不是 {@code max(1, …)}，且 {@code recipeId == null}（取消订单）
     * 时把调味料一并清空。取消按钮发的正是 {@code (null, 0, null)}，
     * 用 {@code max(1, …)} 会让「无订单」状态残留一份调味料。
     * 读数侧另有兜底：{@link #getOrderQuantity()} 对 {@code orderRecipeId == null} 直接返 0。</p>
     *
     * @param seasoningId 调味料 id；{@code null} / 空串表示不指定（随后按工作模式决定）
     */
    public void setOrder(ResourceLocation recipeId, int quantity, String seasoningId) {
        if (recipeId == null) {
            // 取消订单：整体清空，**含调味料**。
            // 取消按钮发的正是 (null, 0, null) —— 若这里只清 id 而留下 orderSeasoning，
            // 「无订单」状态就会残留一份调味料；而 currentSeasoningFor() 在**无订单**时
            // 也会拿它去强制调味，于是玩家刚点的「取消」看起来毫无效果。
            // MekCkOrderState.setOrder(null, ·) 本身即 clear()，但「清调味」是烧烤独有的
            // 语义、状态类里没有这个字段，仍要显式做。
            clearOrder();
            return;
        }
        order.setOrder(recipeId, quantity);
        this.orderSeasoning = (seasoningId == null || seasoningId.isEmpty()) ? null : seasoningId;
    }

    /**
     * 清空订单 —— <b>含调味料</b>。
     *
     * <p>⚠️ 这一点是相对旧实现的**行为回归**，第三轮复核时才发现：
     * 旧 {@code GrillFactoryBlockEntity.clearOrder} 明确写着「订单完成，清空（含调味料）」
     * 并执行了 {@code machine.orderSeasoning = null}；迁到本类时只清了那三个订单字段。
     * 后果链不止「显示上多一行」：{@code currentSeasoningFor()} 把它用于
     * <b>自由投料</b>（无订单也强制调味），而 {@code consumeSeasoningUses} 的
     * {@code enabledOnly = orderSeasoning == null || isEmpty()} 会变成 {@code false}
     * ⇒ 去扣<b>未启用</b>槽的次数；{@code save()} 又会把它写进 NBT 持久化下来。
     * 也就是说一次订单跑完，调味料会永久赖在机器上影响后续自由加工。</p>
     */
    public void clearOrder() {
        order.clear();
        this.orderSeasoning = null;
    }

    /**
     * 完成一份订单后的推进 —— 抽成静态纯函数。
     *
     * <p>「先自增再比」：与旧实现尾部 {@code orderCompleted++; if (>= quantity) 清空} 同序。
     * 差一位会导致最后一份做完订单还挂着，机器再也不接新料。加法走 {@code long}，
     * 否则份数配成 {@link Integer#MAX_VALUE} 且真跑满时 int 绕成负数，订单永远完不成。
     * </p>
     */
    static boolean advanceOrder(int completed, int quantity) {
        return MekCkOrderState.advancedTo(completed, quantity, 1);
    }

    // ── 工作模式与调味料开关 ────────────────────────────────────────────

    public WorkMode getWorkMode() {
        return workMode;
    }

    /** 切换工作模式：默认（自动调味）↔ 订单（只按订单指定的调味）。 */
    public void toggleWorkMode() {
        this.workMode = workMode == WorkMode.DEFAULT ? WorkMode.ORDER : WorkMode.DEFAULT;
    }

    public boolean isSeasoningEnabled(int index) {
        return index >= 0 && index < SEASONING_SLOTS && seasoningEnabled[index];
    }

    /**
     * 三个调味料启用位打包成一个 int（{@code bit0..bit2}）—— 供容器同步用。
     *
     * <p>为什么打包而不发 3 个 int：这三个位<b>一起变</b>（切换其中一个），
     * 打包成一位图后只要一个 {@code SyncableInt} 就能覆盖整个三元素组，
     * 少两条同步条目、少两次脏值判定。</p>
     *
     * <p>每次都重新打包（不缓存字段）：容器同步的脏值判定就是靠「两次读取结果不同」
     * 判定的，缓存起来就永远读出同一个值 ⇒ 永不推送。</p>
     */
    public int seasoningEnabledBits() {
        int bits = 0;
        for (int i = 0; i < SEASONING_SLOTS && i < seasoningEnabled.length; i++) {
            if (seasoningEnabled[i]) {
                bits |= 1 << i;
            }
        }
        return bits;
    }

    public void toggleSeasoningEnabled(int index) {
        if (index >= 0 && index < SEASONING_SLOTS) {
            seasoningEnabled[index] = !seasoningEnabled[index];
        }
    }

    /** 打包成位标志。与旧实现的 {@code seasoningFlags()} 同款。 */
    int seasoningFlags() {
        int flags = 0;
        for (int i = 0; i < SEASONING_SLOTS; i++) {
            if (seasoningEnabled[i]) {
                flags |= 1 << i;
            }
        }
        return flags;
    }

    // ── 配方匹配 ────────────────────────────────────────────────────────

    void invalidateCache() {
        slotRecipeKey = null;
        slotRecipeValue = null;
        slotRecipeValid = null;
    }

    private static long stackKey(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0L;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        long h = (id == null ? 0 : id.hashCode());
        h = h * 31L + (stack.getTag() == null ? 0 : stack.getTag().hashCode());
        return h == 0L ? 1L : h;
    }

    private Optional<Recipe<?>> findRecipe(int inputSlot) {
        Level level = tile == null ? null : tile.getLevel();
        List<IInventorySlot> inputs = tile == null ? null : tile.getInputSlots();
        if (level == null || inputs == null || inputSlot >= inputs.size()) {
            return Optional.empty();
        }
        ItemStack input = inputs.get(inputSlot).getStack();
        if (input.isEmpty()) {
            return Optional.empty();
        }
        if (slotRecipeValid == null) {
            int n = Math.max(1, inputs.size());
            slotRecipeKey = new long[n];
            slotRecipeValue = new Recipe<?>[n];
            slotRecipeValid = new boolean[n];
        }
        Optional<Recipe<?>> found;
        if (inputSlot < slotRecipeValid.length) {
            long key = stackKey(input);
            if (slotRecipeValid[inputSlot] && slotRecipeKey[inputSlot] == key) {
                found = Optional.ofNullable(slotRecipeValue[inputSlot]);
            } else {
                found = lookup(level, input);
                slotRecipeValid[inputSlot] = true;
                slotRecipeKey[inputSlot] = key;
                slotRecipeValue[inputSlot] = found.orElse(null);
            }
        } else {
            found = lookup(level, input);
        }
        if (order.hasRecipe() && (found.isEmpty() || !order.getRecipeId().equals(found.get().getId()))) {
            return Optional.empty();
        }
        return found;
    }

    /**
     * 烧烤配方类型。
     *
     * <p>两个来源都查：本体是 {@code mekck:grilling}，Barbeque's Delight 另有
     * {@code barbequesdelight:grilling}。<b>自有优先</b>，外部那条未安装时为 null。
     * 未安装不是故障，不抛异常也不打日志。</p>
     */
    private static RecipeType<?> recipeType() {
        RecipeType<?> own = RecipeCache.type("mekck", "grilling");
        return own != null ? own : RecipeCache.type("barbequesdelight", "grilling");
    }

    /**
     * 单槽配方查找，**两个来源按优先级逐个试**。
     *
     * <p>走 {@link RecipeCache#singleSlotQueryUntyped} 而不是
     * {@code RecipeCache.singleSlotQuery}：后者的类型参数绑死在
     * {@code Recipe<RecipeWrapper>}，而外部那条 {@code barbequesdelight:grilling}
     * 拿到手是 {@code RecipeType<?>}，类型推断直接失败。
     * 强转的前提是「两源都是 1 格容器配方」——由
     * {@code MekCkGrillingRecipe} 与 Barbeque's Delight 的实现保证；
     * 若将来第三源的容器类型不同，这里会 {@code ClassCastException} 而不是静默错配。</p>
     */
    private static Optional<Recipe<?>> lookup(Level level, ItemStack input) {
        return RecipeCache.singleSlotQueryUntyped(level, recipeType(), input);
    }

    // ── 批量执行 ────────────────────────────────────────────────────────

    /**
     * 执行一次烤制。
     *
     * <p>逐字对应旧 {@code completeRecipe}：<b>容量判定用「已调味」的预览栈</b>——
     * 调味写的是产物 NBT，NBT 不同就是不同物品，用未调味的栈判会判少，
     * 表现为「预演说装得下、真落槽时只塞一半」。这条与阶段 2 切菜踩过的
     * 「预演说装得下、落槽只塞一部分」是同一类故障，只是这里的成因是调味。
     * </p>
     */
    private void completeRecipe(int inputSlot, Recipe<?> recipe, int consumeCount) {
        List<IInventorySlot> inputs = tile.getInputSlots();
        List<IInventorySlot> outputs = tile.getOutputSlots();
        Level level = tile.getLevel();
        if (inputSlot < 0 || inputSlot >= inputs.size() || level == null) {
            return;
        }
        ItemStack input = inputs.get(inputSlot).getStack();
        if (input.isEmpty()) {
            return;
        }
        consumeCount = Math.min(consumeCount, input.getCount());
        if (consumeCount <= 0) {
            return;
        }
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) {
            return;
        }

        String seasoning = currentSeasoningFor(result);
        if (!canFitAll(outputs, seasonedPreview(result, seasoning), consumeCount)) {
            return;
        }

        // 扣原料
        if (input.getCount() <= consumeCount) {
            inputs.get(inputSlot).setStack(ItemStack.EMPTY);
        } else {
            ItemStack remaining = input.copy();
            remaining.setCount(input.getCount() - consumeCount);
            inputs.get(inputSlot).setStack(remaining);
        }

        long outputCountLong = (long) result.getCount() * consumeCount;
        int outputCount = outputCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) outputCountLong;
        ItemStack multiplied = result.copy();
        multiplied.setCount(outputCount);

        if (seasoning != null && outputCount > 0) {
            applySeasoningTo(multiplied, seasoning);
            consumeSeasoningUses(seasoning, outputCount, orderSeasoning == null || orderSeasoning.isEmpty());
        }

        MekCkBatchPacking.insertOutput(outputs, multiplied);

        if (order.isActive()) {
            if (order.advance(1)) {
                clearOrder();
            }
        }
    }

    /** 单份产出的容量判定。传的是<b>已调味</b>的栈。 */
    static boolean canFitAll(List<IInventorySlot> outputs, ItemStack seasoned, int multiplier) {
        return MekCkBatchPacking.canFitAll(outputs, List.of(seasoned), multiplier);
    }

    /**
     * 预演栈 —— 把调味写进产物副本，供容量判定使用。
     *
     * <p>预演必须与实际落槽走同一个分派（见 {@link #applySeasoningTo}）：两个兼容门面写的
     * NBT 键不同，预演写错门面就会让 canFitAll 与实际 insertOutput 的口径分家。</p>
     */
    private ItemStack seasonedPreview(ItemStack result, String seasoning) {
        ItemStack preview = result.copy();
        if (seasoning != null) {
            applySeasoningTo(preview, seasoning);
        }
        return preview;
    }

    /**
     * 把调味写进烤串 —— <b>预演与落槽的唯一入口</b>。
     *
     * <p>两个兼容门面写的 NBT <b>不是同一个键</b>：{@code BarbequesDelightCompat.applySeasoning}
     * 写 {@code {Seasoning: <id>}}（{@code BarbequesDelightCompat:73-76}），
     * {@code KaleidoscopeGrillingCompat.applySeasoningToSkewer} 写
     * {@code {SeasoningIngredients:[<id>], SeasoningUses:n}}（{@code KaleidoscopeGrillingCompat:265-272}）。
     * 而 {@code ItemStack.isSameItemSameTags} 是逐 NBT 比较 ⇒ 预演用什么门面，就等于
     * 「canFitAll 认为哪些已有的堆能并进来」。两处一旦分派不一致：预演说装得下、
     * {@code insertOutput} 却并进不去，余量留在参数里被丢弃 —— <b>产物静默消失</b>，
     * 不报错、不留日志。所以两处必须调本方法，谁都不许自己写 if。</p>
     */
    static void applySeasoningTo(ItemStack skewer, String seasoning) {
        if (seasoning.startsWith(KaleidoscopeGrillingCompat.MOD_ID)) {
            KaleidoscopeGrillingCompat.applySeasoningToSkewer(skewer, seasoning);
        } else {
            BarbequesDelightCompat.applySeasoning(skewer, seasoning);
        }
    }

    // ── 调味料 ──────────────────────────────────────────────────────────

    /**
     * 该产物当前应使用的调味料 id。
     *
     * <p>逐字取自旧 {@code currentSeasoningFor}：产物不可调味 ⇒ 永远 null；
     * 订单指定了调味 ⇒ 用订单的；否则 {@code DEFAULT} 模式下自动挑一个已启用的，
     * {@code ORDER} 模式下不自动挑。</p>
     */
    private String currentSeasoningFor(ItemStack result) {
        if (owner == null || !BarbequesDelightCompat.isSeasonable(result)) {
            return null;
        }
        if (orderSeasoning != null && !orderSeasoning.isEmpty()) {
            return orderSeasoning;
        }
        if (workMode == WorkMode.DEFAULT) {
            return pickDefaultSeasoning();
        }
        return null;
    }

    /**
     * 默认模式下在启用的槽里挑剩余次数最多的调味料。
     *
     * <p>并列时取<b>下标更小</b>的（严格大于才替换），于是多个启用时会
     * 自然均匀消耗而不是先把一个用光——与旧实现逐字同款。</p>
     */
    String pickDefaultSeasoning() {
        if (owner == null) {
            return null;
        }
        String bestId = null;
        int bestUses = 0;
        for (int i = 0; i < SEASONING_SLOTS; i++) {
            if (!seasoningEnabled[i]) {
                continue;
            }
            ItemStack stack = owner.getSeasoningSlotStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = seasoningIdOf(stack);
            if (id == null) {
                continue;
            }
            int uses = seasoningUsesOf(stack);
            if (uses > bestUses) {
                bestUses = uses;
                bestId = id;
            }
        }
        return bestId;
    }

    /** 该调味料当前还剩多少次可用。{@code enabledOnly} 为真时只统计已启用的槽。 */
    public int availableSeasoningUses(String seasoningId, boolean enabledOnly) {
        if (owner == null || seasoningId == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < SEASONING_SLOTS; i++) {
            if (enabledOnly && !seasoningEnabled[i]) {
                continue;
            }
            ItemStack stack = owner.getSeasoningSlotStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = seasoningIdOf(stack);
            if (seasoningId.equals(id)) {
                total += seasoningUsesOf(stack);
            }
        }
        return total;
    }

    public int availableSeasoningUses(String seasoningId) {
        return availableSeasoningUses(seasoningId, false);
    }

    /**
     * 消耗调味料耐久。
     *
     * <p>逐字取自旧实现：两个兼容门面各写各的消耗方法
     * （Barbeque's Delight 改堆叠数、Kaleidoscope 改单件 NBT），
     * 所以这里也分派而不合并。</p>
     */
    private void consumeSeasoningUses(String seasoningId, int uses, boolean enabledOnly) {
        if (owner == null || seasoningId == null || uses <= 0) {
            return;
        }
        int remaining = uses;
        for (int i = 0; i < SEASONING_SLOTS && remaining > 0; i++) {
            if (enabledOnly && !seasoningEnabled[i]) {
                continue;
            }
            ItemStack stack = owner.getSeasoningSlotStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!seasoningId.equals(seasoningIdOf(stack))) {
                continue;
            }
            remaining -= applySeasoningConsumption(i, stack, remaining);
        }
    }

    /**
     * 消耗一个槽的调味料耐久，返回实际消耗次数。
     *
     * <p>两个兼容门面的消耗方式不同：Barbeque's Delight 改堆叠数，
     * Kaleidoscope Grilling 改单件 NBT。抽成方法只为能单测这两个分支。
     * </p>
     *
     * @return 实际消耗次数，上限为该槽剩余次数
     */
    int applySeasoningConsumption(int index, ItemStack stack, int want) {
        if (KaleidoscopeGrillingCompat.isSeasoningBottle(stack)) {
            int available = KaleidoscopeGrillingCompat.getSeasoningUses(stack);
            int take = Math.max(0, Math.min(want, available));
            for (int i = 0; i < take; i++) {
                // 森罗的消耗是「用一次掉一格」，签名只吃一个栈
                KaleidoscopeGrillingCompat.consumeSeasoningUse(stack);
            }
            owner.writeSeasoningSlot(index, stack);
            return take;
        }
        // Barbeque's Delight 走耐久值：累加 damage，够到 maxDamage 就清空该槽
        int maxDamage = stack.getMaxDamage();
        if (maxDamage <= 0) {
            return 0;
        }
        int available = maxDamage - stack.getDamageValue();
        int take = Math.max(0, Math.min(want, available));
        if (take <= 0) {
            return 0;
        }
        int newDamage = stack.getDamageValue() + take;
        if (newDamage >= maxDamage) {
            owner.clearSeasoningSlot(index);
        } else {
            stack.setDamageValue(newDamage);
            owner.writeSeasoningSlot(index, stack);
        }
        return take;
    }

    /**
     * 该栈的调味料 id —— 两个兼容门面分派。
     *
     * <p>逐字取自旧 {@code seasoningIdOf}：先问森罗的瓶子，
     * 是就用它；否则问 Barbeque's Delight。<b>顺序不能反</b>——
     * 森罗的瓶子同时也可能带 Barbeque's Delight 的 NBT，反了会取错 id。</p>
     */
    static String seasoningIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (KaleidoscopeGrillingCompat.isSeasoningBottle(stack)) {
            return KaleidoscopeGrillingCompat.getSeasoningId(stack);
        }
        return BarbequesDelightCompat.getSeasoningId(stack);
    }

    /**
     * 该栈的调味料剩余可用次数。
     *
     * <p>逐字取自旧 {@code seasoningUsesOf}：森罗瓶子问它自己的；
     * Barbeque's Delight 的调味料是耐久物品，用 {@code maxDamage - damageValue} 自己算，
     * 耐久为 0（不可损坏）返回 0 表示「不消耗」。</p>
     */
    static int seasoningUsesOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (KaleidoscopeGrillingCompat.isSeasoningBottle(stack)) {
            return KaleidoscopeGrillingCompat.getSeasoningUses(stack);
        }
        int maxDamage = stack.getMaxDamage();
        return maxDamage <= 0 ? 0 : Math.max(0, maxDamage - stack.getDamageValue());
    }

    // ── 本机下单的配方清单 ─────────────────────────────────────────────

    public List<Recipe<?>> getAvailableRecipes(Level level) {
        if (level == null || tile == null) {
            return List.of();
        }
        RecipeType<?> type = recipeType();
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
            ItemStack input = slot.getStack();
            if (input.isEmpty()) {
                continue;
            }
            for (Recipe<?> recipe : RecipeCache.all(level, type)) {
                if (matchesAnyInput(recipe, input) && seen.add(recipe.getId())) {
                    out.add(recipe);
                }
            }
        }
        return out;
    }

    /**
     * 「本机下单」面板的 Max 按钮：当前这些材料能支撑几份。
     *
     * <p>逐字取自旧实现的约定：需求为空时返回 {@code 0} 而不是
     * {@link Integer#MAX_VALUE}。</p>
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

    static boolean matchesAnyInput(Recipe<?> recipe, ItemStack input) {
        if (recipe == null || input == null || input.isEmpty()) {
            return false;
        }
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient != null && !ingredient.isEmpty() && ingredient.test(input)) {
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
     * 本 tick 单槽能吃下的最大数量。
     *
     * <p>上限走 {@link MekCkUpgradeTypes#capOf}：它先过 {@code isSupportedBy} 准入闸门，
     * 而读档路径（{@code TileComponentUpgrade} 的 {@code clear + putAll}）中间
     * <b>没有任何 supports 检查</b>，不裁剪等于让手改存档直接决定倍增倍数。</p>
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
        return CountMath.mulClamp(Integer.MAX_VALUE, base,
                MekCkUpgradeTypes.stackMultiplier(installed, cap, base, maxParallel));
    }
}
