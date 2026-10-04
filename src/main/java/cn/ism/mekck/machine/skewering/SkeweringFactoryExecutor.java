package cn.ism.mekck.machine.skewering;

import cn.ism.mekck.machine.MekCkBatchPacking;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkOrderState;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.util.CountMath;
import cn.ism.mekck.compat.KaleidoscopeGrillingCompat;
import cn.ism.mekck.util.RecipeCache;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 穿串工厂的执行器 —— 配方匹配、批量算料、订单推进。
 *
 * <h3>这台机器与前四个家族的根本差别</h3>
 * 并行方阵家族是「<b>第 i 个输入槽配第 i 个输出槽，各跑各的</b>」；穿串是
 * 「<b>三个输入槽一起</b>做出一个周期的产出」。具体差别有四处：
 * <ol>
 *   <li><b>没有自动生产</b>。{@link #findRecipe} 在 {@code orderRecipeId == null} 时
 *       直接返回空——材料摆满也不动，必须先下单（逐字对齐旧实现第 640-641 行
 *       的 "No order set - do not auto-process"）。</li>
 *   <li><b>配料位置无关</b>。匹配扫「3 个输入槽 + 全部存储槽」，只判三种材料
 *       「各至少有一个」。刻意不用 3 个输入栈的固定位置：否则玩家把主料放进
 *       辅料那一格就会卡住，而没有任何提示。</li>
 *   <li><b>主料数量硬编码 1</b>，只有辅料走 {@code sideCount}、签子走
 *       {@code ingredientCount}。见 {@link #toolCountOf} 的命名陷阱说明。</li>
 *   <li><b>产量是批量的</b>：一个周期做出 {@code batch} 个串，batch 由
 *       「三种材料各还剩多少」与「存储卡倍率」与「订单剩余量」三者取小决定。</li>
 * </ol>
 *
 * <h3>没有搬过来的两样</h3>
 * <ul>
 *   <li><b>{@code StorageMerger} 的定时前移合并</b>。旧实现每 20 tick 把同物向前压，
 *       是为了让「存储区前几格」在旧 GUI 里好找。新体系里存储槽由
 *       {@code MekCkBatchPacking.insertOutput} 自然打包，GUI 也不再依赖顺序。</li>
 *   <li><b>能力面（{@code IItemHandler} 等）的只出不进语义</b>：见
 *       {@code SkeweringFactoryTile} 的 {@code mePatternItemInputs} 注释。</li>
 * </ul>
 */
public final class SkeweringFactoryExecutor implements MekCkRecipeExecutor {

    /**
     * 与旧存档<b>逐字同名</b>的键：{@code MekCkLegacyMachineNbt} 只换位置不改名。
     * 前三个与切菜/研磨/烧烤共用同名，机器间不通用，但迁移器按家族分流。
     */
    public static final String TAG_ORDER_RECIPE = MekCkOrderState.TAG_ORDER_RECIPE;
    public static final String TAG_ORDER_QUANTITY = MekCkOrderState.TAG_ORDER_QUANTITY;
    public static final String TAG_ORDER_COMPLETED = MekCkOrderState.TAG_ORDER_COMPLETED;
    /** 自选组合的材料表（旧存档里是 StringTag 列表，每项一个物品 id、各 1 个）。 */
    public static final String TAG_ORDER_CUSTOM = "OrderCustomIngredients";

    private SkeweringFactoryTile owner;
    private boolean busy;

    /**
     * 订单状态。唯一的持有者。
     *
     * <p>第四轮从三个手写字段换成 {@link MekCkOrderState}，与烹饪 / 研磨 / 种植切配 / 烧烤
     * 对齐（6 个执行器同一份契约）。三个存档键与原有的
     * {@code OrderRecipeId/OrderQuantity/OrderCompleted} <b>逐字相同</b>，既有存档不受影响。</p>
     *
     * <p><b>自选材料（{@link #orderCustomIngredients}）刻意留在本类</b>：它是穿串独有的
     * 「配方现场拼、因而没有 id 可存」语义。公共状态类用一个 {@code active} 标志表达
     * 「有单但无配方 id」（见 {@link MekCkOrderState#setActiveWithoutRecipe}），
     * 而<b>材料清单本身</b>只有这一个家族用，塞进去等于让另外 5 个家族背一个用不到的字段。</p>
     */
    private final MekCkOrderState order = new MekCkOrderState();

    private final List<String> orderCustomIngredients = new ArrayList<>();

    // ── MekCkRecipeExecutor ─────────────────────────────────────────────

    /**
     * 本机只有一路 —— 三个输入槽一起做出一个批次，没有「第几路」可言。
     * 覆写成 1 之后 {@code MekCkMachineTile} 的进度数组长度为 1，整机共用一条进度条。
     */
    @Override
    public int processCount(MekCkMachineTile tile) {
        return 1;
    }

    /**
     * 第 0 路此刻能不能开工：有订单、有配方、批量算得出来且大于 0、<b>产物与返还物都装得下</b>。
     *
     * <p>{@code index} 在本家族无意义（{@link #processCount} 恒为 1）。本方法每 tick
     * 被调一次，<b>不得改动机器状态</b>；{@link #owner} 的绑定与配方缓存是执行器自有状态，
     * 可以在这里刷新。</p>
     *
     * <p>容量判定是补上的：缺了它，产物槽满时本方法仍返回 true，{@code workCycle}
     * 照常扣电、进度条照走，而 {@link #run} 在落槽前直接 {@code return} ——
     * 玩家看不到产出、看不到告警，电却一直在掉。判据与 {@link #run} 共用
     * {@link #canFitBatch}，两处不会漂移。</p>
     */
    @Override
    public boolean canProcess(MekCkMachineTile tile, int index) {
        this.owner = tile instanceof SkeweringFactoryTile s ? s : null;
        Level level = tile == null ? null : tile.getLevel();
        if (level == null || owner == null) {
            return false;
        }
        Recipe<?> recipe = findRecipe(level);
        if (recipe == null) {
            return false;
        }
        List<IInventorySlot> scan = owner.ingredientSlots();
        int batch = batchSize(recipe, scan);
        if (order.isActive()) {
            // 订单剩余量是硬上限：不能做出「比订单多」的东西。
            batch = Math.min(batch, order.remainingOrUnlimited(batch));
        }
        if (batch <= 0) {
            return false;
        }
        return canFitBatch(owner.getOutputSlots(), recipe.getResultItem(level.registryAccess()), batch,
                returnPayload(recipe, owner.getInputSlots(), batch));
    }

    /**
     * 加工第 0 路一次。先调一次 {@link #canProcess} 兜底，再重新取配方与批量执行。
     *
     * <p>{@code busy} 的复位与旧 {@code tick} 同款：开工前先清，真跑完才置位。</p>
     */
    @Override
    public void process(MekCkMachineTile tile, int index) {
        this.owner = tile instanceof SkeweringFactoryTile s ? s : null;
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
        if (order.isActive()) {
            // 订单剩余量是硬上限：不能做出「比订单多」的东西。
            batch = Math.min(batch, order.remainingOrUnlimited(batch));
        }
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
        // ⚠️ 不能直接用 order.save(tag)：它按「有没有配方 id」决定写不写数量，
        // 而自选组合单的 recipeId 恒为 null（配方是现场拼的虚拟配方，没有 id 可存）⇒
        // 数量与完成数会整个丢失，表现为存读档后「有材料、却不动也不报错」。
        // 所以这里先按「有配方 id」写一次，再在自选组合这条路上补写数量。
        if (order.hasRecipe()) {
            order.save(tag);
        } else if (order.isActive()) {
            tag.putInt(TAG_ORDER_QUANTITY, order.getQuantity());
            tag.putInt(TAG_ORDER_COMPLETED, order.getCompleted());
        }
        if (!orderCustomIngredients.isEmpty()) {
            ListTag list = new ListTag();
            for (String itemId : orderCustomIngredients) {
                list.add(StringTag.valueOf(itemId));
            }
            tag.put(TAG_ORDER_CUSTOM, list);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        // 键不存在即「无订单」（MekCkOrderState.load 内部整体清空）：执行器与方块实体同寿，
        // 只在键存在时赋值会留下无法取消的幽灵订单。
        order.load(tag);
        orderCustomIngredients.clear();
        // 契约（MekCkRecipeExecutor#load）：旧存档无此键时必须保持默认态、不得抛异常。
        // 不能靠调用方兜底，所以这里判 null。
        if (tag == null) {
            return;
        }
        // getString 对缺失键给空串、getInt 给 0，因此不需要 contains 分支。
        // 固定配方与自选组合是互斥的两条路：各自读各自的数量。
        if (order.hasRecipe()) {
            return;
        }
        Tag list = tag.get(TAG_ORDER_CUSTOM);
        if (list instanceof ListTag items) {
            for (Tag element : items) {
                if (element instanceof StringTag text) {
                    orderCustomIngredients.add(text.getAsString());
                }
            }
            if (!orderCustomIngredients.isEmpty()) {
                // 自选组合单：有材料清单但没有配方 id。
                // setActiveWithoutRecipe 会把 completed 清零，所以 completed 必须
                // 在它**之后**单独复原 —— 不能用 advance() 顺带推上去：
                // advance() 顺带判定「是否已满」，completed 恰好等于 quantity 时会
                // 立刻判定满单，调用方随即 clearOrder()，把一张刚读回来的单清掉。
                order.setActiveWithoutRecipe(Math.max(1, MekCkOrderState.readQuantity(tag)));
                order.restoreCompleted(MekCkOrderState.readCompleted(tag));
            }
        }
    }

    // ── 订单 ────────────────────────────────────────────────────────────

    /**
     * 查本批该按哪张配方做。
     *
     * <p>两条路：<b>自选组合</b>（{@link KaleidoscopeGrillingCompat#makeCustomThreadingRecipe}
     * 现场拼一张虚拟配方，产物是烟火未完成烤串）优先于<b>固定配方</b>。
     * 固定配方下 {@code orderRecipeId == null} 就是「没有订单」，
     * 返回空即机器静止——这是本家族的<b>设计</b>，不是缺陷。</p>
     */
    private Recipe<?> findRecipe(Level level) {
        if (!orderCustomIngredients.isEmpty()) {
            return buildCustomRecipe();
        }
        if (!order.hasRecipe()) {
            return null;
        }
        return findById(level, order.getRecipeId());
    }

    /** 按 id 找配方，自有来源优先于外部（逐字对齐旧 {@code getSkeweringRecipeTypes} 的次序）。 */
    private static Recipe<?> findById(Level level, ResourceLocation id) {
        for (RecipeType<?> type : skeweringRecipeTypes()) {
            for (Recipe<?> recipe : RecipeCache.all(level, type)) {
                if (id.equals(recipe.getId())) {
                    return recipe;
                }
            }
        }
        if (KaleidoscopeGrillingCompat.isLoaded()) {
            for (Recipe<?> recipe : KaleidoscopeGrillingCompat.getThreadingVirtualRecipes()) {
                if (id.equals(recipe.getId())) {
                    return recipe;
                }
            }
        }
        return null;
    }

    /**
     * 配方类型列表：自有 {@code mekck:skewering} 优先，回落 {@code barbequesdelight:skewering}。
     * 后者未安装时 {@link RecipeCache#type} 返回 null 并被跳过，<b>不抛异常也不打日志</b>。
     */
    private static List<RecipeType<?>> skeweringRecipeTypes() {
        List<RecipeType<?>> types = new ArrayList<>(2);
        RecipeType<?> own = RecipeCache.type("mekck", "skewering");
        if (own != null) {
            types.add(own);
        }
        RecipeType<?> external = RecipeCache.type("barbequesdelight", "skewering");
        if (external != null) {
            types.add(external);
        }
        return types;
    }

    /** 自选组合 → 虚拟配方。材料里出现未注册物品时返回 null（本批不加工，不抛）。 */
    private Recipe<?> buildCustomRecipe() {
        List<ItemStack> materials = new ArrayList<>(orderCustomIngredients.size());
        for (String itemId : orderCustomIngredients) {
            ResourceLocation id = ResourceLocation.tryParse(itemId);
            Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item == null) {
                return null;
            }
            materials.add(new ItemStack(item));
        }
        if (materials.isEmpty()) {
            return null;
        }
        return KaleidoscopeGrillingCompat.makeCustomThreadingRecipe(materials, 1);
    }

    /**
     * 下一个周期该做几个 —— 抽成静态纯函数，好进裸 JVM 单测
     * （见 {@code cn.ism.mekck.machine.TestSkeweringBatchMath}）。
     *
     * <p>三种材料各自算可用批数再取小。{@code perUnit <= 0} 表示「这种材料不消耗、
     * 只要求存在」，此时<b>不限制批量</b>（返回 {@link Integer#MAX_VALUE}）——注意不是
     * 返回「现有个数」：签子不消耗且会被原样取回返还槽，1 根与 64 根对批量的约束必须相同。
     * 旧实现在 {@code toolCount == 0} 时写成 {@code available / 0}，靠外层
     * {@code catch (Exception) { return 0; }} 吞掉 {@code ArithmeticException}，
     * 症状是「配方列表恒空、订单设不了、机器完全惰性且无任何日志」；
     * 本方法把那条分支显式写出来，这条回归由
     * {@code TestSkeweringToolBatchArithmetic} 直接钉在本方法上。</p>
     */
    public static int batchForMaterial(int available, int perUnit) {
        if (available <= 0) {
            return 0;
        }
        return perUnit <= 0 ? Integer.MAX_VALUE : available / perUnit;
    }

    /** 三种材料各算一遍再取小；任一为 0 则整批做不了。 */
    private int batchSize(Recipe<?> recipe, List<IInventorySlot> scan) {
        int byTool = batchForMaterial(availableCount(scan, toolOf(recipe)), toolCountOf(recipe));
        int byMain = batchForMaterial(availableCount(scan, mainOf(recipe)), 1);
        int bySide = batchForMaterial(availableCount(scan, sideOf(recipe)), sideCountOf(recipe));
        int smallest = Math.min(byTool, Math.min(byMain, bySide));
        return Math.min(smallest, owner.stackMultiplier());
    }

    /** 某种配料在「输入 + 存储」里一共还剩几个。配料为空（这张配方不用它）视为无限。 */
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

    /**
     * 扣料 → 落产物 → 落返还槽 → 推进订单。
     *
     * <p>顺序与旧 {@code completeRecipe} 逐字对应：<b>先扣料，再从输入槽 0 取返还</b>
     * （旧实现第 900-909 行就是先 {@code consumeIngredients} 再读 {@code getStackInSlot(0)}）。
     * 顺序反了的话，签子被扣空时返还槽就拿不到东西。</p>
     *
     * <p><b>返还受 {@link #returnPayload} 的 toolCount &gt; 0 闸门约束</b>：自有配方
     * 序列化器写死 {@code ingredientCount = 0}（签子不消耗），无条件返还等于每批把
     * batch 个签子复制进返还槽（物品复制）。闸门与机器侧
     * {@code SkeweringMachineBlockEntity.completeRecipe} 同款。</p>
     *
     * <p>与旧实现的一处<b>刻意</b>不同：旧 {@code insertIntoSlot} 在槽里是别的物品时
     * 直接丢弃、在槽空时无视容量直接塞满。这里走共用的
     * {@link MekCkBatchPacking#insertOutput}（尊重槽容量、装不下的留在参数里）。
     * 旧行为是静默丢物品，不是能复刻的「语义」。</p>
     */
    private void run(Level level, Recipe<?> recipe, int batch, List<IInventorySlot> scan) {
        List<IInventorySlot> inputs = owner.getInputSlots();
        List<IInventorySlot> outputs = owner.getOutputSlots();
        if (inputs.isEmpty() || outputs.size() < 2) {
            return;
        }
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) {
            return;
        }
        // 返还槽的容量要在扣料<b>之前</b>判，否则会出现「料已扣、返不下」的白工。
        // 判定用扣料前的槽 0 栈：扣料只可能让它更空，所以这是保守（偏严）的估计。
        ItemStack returnPreview = returnPayload(recipe, inputs, batch);
        if (!canFitBatch(outputs, result, batch, returnPreview)) {
            return;
        }
        ItemStack produced = batchProduct(result, batch);

        consume(scan, recipe, batch);

        MekCkBatchPacking.insertOutput(outputs.subList(0, 1), produced);
        ItemStack returned = returnPayload(recipe, inputs, batch);
        if (!returned.isEmpty()) {
            MekCkBatchPacking.insertOutput(outputs.subList(1, 2), returned);
        }
        busy = true;
        advanceOrder(batch);
    }

    /**
     * 本批产物预览栈 —— {@link #canFitBatch} 与 {@link #run} 共用的唯一算法。
     *
     * <p>不可产出（配方无产物 / 数量溢出）时返回 {@link ItemStack#EMPTY}：
     * {@link MekCkBatchPacking#canFitAll} 对空栈是<b>跳过</b>（返回 true），
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
     * 本批「产物 + 返还物」装不装得下 —— {@link #canProcess} 与 {@link #run} 共用的唯一判据。
     *
     * <p>产物只落 {@code outputs[0]}、返还物只落 {@code outputs[1]}（见 {@link #run}），
     * 所以两段容量判定分开做，不能把整个 outputs 交给 {@code canFitAll} ——
     * 那会让「产物槽满、返还槽空」被判成装得下，而 run 只往产物槽落，
     * 剩余部分被静默丢弃。</p>
     *
     * <p>抽成 {@code static} 纯函数是为了能在裸 JVM 里断言（真 tile 造不出来，
     * 同 {@code TestCookingFactoryEnergyDrain}）。</p>
     *
     * @param returnPreview 本批要返还的栈（{@link #returnPayload} 的结果）；
     *                      空 = 本批不返还，返还槽不参与判定
     */
    public static boolean canFitBatch(List<IInventorySlot> outputs, ItemStack result, int batch,
                                      ItemStack returnPreview) {
        if (outputs == null || outputs.size() < 2) {
            return false;
        }
        ItemStack produced = batchProduct(result, batch);
        if (produced.isEmpty()) {
            return false;
        }
        if (!MekCkBatchPacking.canFitAll(outputs.subList(0, 1), List.of(produced), 1)) {
            return false;
        }
        if (returnPreview == null || returnPreview.isEmpty()) {
            return true;
        }
        return MekCkBatchPacking.canFitAll(outputs.subList(1, 2), List.of(returnPreview), 1);
    }

    /**
     * 返还槽的负载：输入槽 0 当前那一叠，数量是<b>批量本身</b>。
     *
     * <p><b>唯一的返还闸门</b>：只有真被消耗过的签子才返还 ——
     * {@code toolCountOf(recipe) > 0} 且签子配料非空，与机器侧
     * {@code SkeweringMachineBlockEntity.completeRecipe} 同款。自有配方序列化器写死
     * {@code ingredientCount = 0}（签子不消耗），无条件返还等于每批把 batch 个签子
     * 复制进返还槽（物品复制）。</p>
     *
     * <p>数量逐字对齐旧 {@code completeRecipe}：
     * {@code returnStack.setCount(CountMath.mulClamp(MAX_COUNT, 1, multiplier))}。
     * 注意那个 {@code mulClamp} 的三个实参是 {@code cap=MAX_COUNT, a=1, b=multiplier}，
     * 所以它是「1 × multiplier」=<b>倍率</b>，<b>不是</b>「整叠数量 × 倍率」——
     * 第一次读到这里时很容易把 {@code a} 认成槽 0 的数量。
     * 槽 0 被扣空则无物可返，调用方跳过返还。</p>
     */
    private ItemStack returnPayload(Recipe<?> recipe, List<IInventorySlot> inputs, int batch) {
        if (inputs == null || inputs.isEmpty()) {
            return ItemStack.EMPTY;
        }
        boolean consumedTool = toolCountOf(recipe) > 0 && !toolOf(recipe).isEmpty();
        if (!consumedTool) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = inputs.get(0).getStack();
        if (stack.isEmpty() || batch <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copy();
        copy.setCount(CountMath.mulClamp(CountMath.MAX_COUNT, 1, batch));
        return copy;
    }

    /** 扣三种料。位置无关：按「输入 + 存储」逐槽扣，先输入槽后存储槽。 */
    private void consume(List<IInventorySlot> scan, Recipe<?> recipe, int batch) {
        consumeOne(scan, toolOf(recipe), CountMath.mulClamp(Integer.MAX_VALUE, toolCountOf(recipe), batch));
        consumeOne(scan, mainOf(recipe), batch);
        consumeOne(scan, sideOf(recipe), CountMath.mulClamp(Integer.MAX_VALUE, sideCountOf(recipe), batch));
    }

    private static void consumeOne(List<IInventorySlot> scan, Ingredient ingredient, int amount) {
        if (ingredient == null || ingredient.isEmpty() || amount <= 0) {
            return;
        }
        int remaining = amount;
        for (IInventorySlot slot : scan) {
            if (remaining <= 0) {
                return;
            }
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !ingredient.test(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) {
                slot.setStack(ItemStack.EMPTY);
            }
            remaining -= take;
        }
    }

    /**
     * 推进订单。
     *
     * <p>用 {@code long} 加法再比：份数被玩家配到 {@link Integer#MAX_VALUE} 且真跑满时，
     * int 会在加法那一刻绕成负数，订单永远完不成。</p>
     */
    private void advanceOrder(int batch) {
        if (order.advance(batch)) {
            clearOrder();
        }
    }

    // ── 对外：订单读写 ──────────────────────────────────────────────────

    @Override
    public boolean hasOrder() {
        // isActive() 已覆盖「有配方 id」与「只有自选材料」两种激活方式 ——
        // 迁移前这里是 orderRecipeId != null || !orderCustomIngredients.isEmpty() 两条路。
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

    public List<String> getOrderCustomIngredients() {
        return List.copyOf(orderCustomIngredients);
    }

    /** 下固定配方单。{@code recipeId == null} 等价于 {@link #clearOrder()}。 */
    public void setOrder(ResourceLocation recipeId, int quantity) {
        orderCustomIngredients.clear();
        if (recipeId == null) {
            clearOrder();
            return;
        }
        order.setOrder(recipeId, quantity);
    }

    /** 下自选组合单（森罗物语「烟火」）。材料按物品 id 存，各 1 个。 */
    public void setCustomOrder(List<String> itemIds, int quantity) {
        order.clear();
        orderCustomIngredients.clear();
        if (itemIds == null || itemIds.isEmpty()) {
            clearOrder();
            return;
        }
        for (String id : itemIds) {
            if (id != null && !id.isEmpty()) {
                orderCustomIngredients.add(id);
            }
        }
        if (orderCustomIngredients.isEmpty()) {
            clearOrder();
            return;
        }
        // 有材料、但没有配方 id ⇒ 走 setActiveWithoutRecipe（active=true / recipeId=null）。
        // 这一步是穿串能正确表达「有单却无 id」的关键：判「有没有单」必须用
        // isActive()，判「要不要卡配方门禁」才用 hasRecipe()。
        order.setActiveWithoutRecipe(quantity);
    }

    /** 取消订单：公共状态与自选材料<b>全清</b>（旧实现第 500-508 行同款）。 */
    public void clearOrder() {
        order.clear();
        orderCustomIngredients.clear();
    }

    // ── 配方字段访问（三种来源，三种形状）──────────────────────────────
    //
    // 自有配方 MekCkSkeweringRecipe 的字段是 private、只有部分 getter；
    // 森罗的 VirtualRecipe 字段全 public；BBQ Delight 的那张表形状未知。
    // 因此一律走「先找 public 字段、再找 public getter、都没有就取默认值」。

    /**
     * 签子（tool）配料。三个来源形状不同，一律走「先 public 字段、再 public getter」。
     *
     * <p>{@code public} 是因为 {@code MekckAe2} 算「AE2 要往里投哪些料」时也读这三个字段：
     * 两边必须走同一套读取，否则终端展示的配方与机器实际做的会漂移。</p>
     */
    public static Ingredient toolOf(Recipe<?> recipe) {
        return asIngredient(readMember(recipe, "tool"));
    }

    public static Ingredient mainOf(Recipe<?> recipe) {
        return asIngredient(readMember(recipe, "ingredient"));
    }

    public static Ingredient sideOf(Recipe<?> recipe) {
        return asIngredient(readMember(recipe, "side"));
    }

    /**
     * 签子（tool）的<b>每串消耗数</b>。
     *
     * <p><b>命名陷阱</b>：自有配方里这个数字叫 {@code ingredientCount}，
     * 但它<b>不是</b>主料的消耗数——主料恒为 1，{@code ingredientCount} 管的是签子。
     * 改这个语义等于静默停产（每串的签子消耗会从 0 变成别的数）。
     * 取不到时返回 0，也就是「签子不消耗、只要求存在」——
     * 这正是自有 8 条配方想要的口径（它们的 JSON 里根本没有 tool 字段）。</p>
     */
    public static int toolCountOf(Recipe<?> recipe) {
        return asInt(readMember(recipe, "ingredientCount"), 0);
    }

    public static int sideCountOf(Recipe<?> recipe) {
        return asInt(readMember(recipe, "sideCount"), 0);
    }

    private static Ingredient asIngredient(Object value) {
        return value instanceof Ingredient ingredient ? ingredient : Ingredient.EMPTY;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** 反射读一个成员：先 public 字段，再 public getter，都没有返回 null。 */
    private static Object readMember(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            var field = target.getClass().getField(name);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 落到 getter
        }
        String suffix = name.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + name.substring(1);
        try {
            return target.getClass().getMethod("get" + suffix).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /** 本机当前能下单的配方（供界面列举）。三个来源合并去重。 */
    public static List<Recipe<?>> availableRecipes(Level level) {
        List<Recipe<?>> out = new ArrayList<>();
        java.util.Set<ResourceLocation> seen = new java.util.HashSet<>();
        for (RecipeType<?> type : skeweringRecipeTypes()) {
            for (Recipe<?> recipe : RecipeCache.all(level, type)) {
                if (seen.add(recipe.getId())) {
                    out.add(recipe);
                }
            }
        }
        if (KaleidoscopeGrillingCompat.isLoaded()) {
            for (Recipe<?> recipe : KaleidoscopeGrillingCompat.getThreadingVirtualRecipes()) {
                if (seen.add(recipe.getId())) {
                    out.add(recipe);
                }
            }
        }
        return out;
    }

    /** 按 id 取可下单配方，供 {@code NetworkRecipeRequestPacket} 的回包复用。 */
    public static Optional<Recipe<?>> findAvailable(Level level, ResourceLocation id) {
        if (id == null || level == null) {
            return Optional.empty();
        }
        for (Recipe<?> recipe : availableRecipes(level)) {
            if (id.equals(recipe.getId())) {
                return Optional.of(recipe);
            }
        }
        return Optional.empty();
    }
}
