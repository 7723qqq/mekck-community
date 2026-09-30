package cn.ism.mekck.machine.cutting;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.machine.MekCkBatchPacking;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import cn.ism.mekck.util.CountMath;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.List;
import java.util.Optional;

/**
 * 切菜工厂的执行器 —— 家族特有的一切都在这里：配方匹配、批量执行、并行数。
 *
 * <h3>为什么它只写这三样</h3>
 * 7 个工厂家族共有的 42 个方法（升级追踪、能力暴露、红石、侧配、NBT、AE2 拉料…）
 * 已由 {@link MekCkMachineTile} 与 Mekanism 基类提供。切菜真正特有的只有
 * 「怎么按 Farmer's Delight 的砧板配方匹配输入」与「怎么把一批原料一次性变成产物」。
 * 本类不含任何方块实体职责，也不碰能量、红石、进度条。
 *
 * <h3>⚠️ 本执行器<b>没有能量与进度条闸门</b></h3>
 * {@link #canProcess} / {@link #process} 的语义是「这一路该不该加工 / 加工一次」，
 * 调用方必须先确认机器<b>本 tick 该不该干活</b>。旧
 * {@code CuttingMachineFactoryBlockEntity.serverTick} 的顺序是
 * 「红石 → 扣能量 → 累进度 → 进度满才 completeRecipe」；这三段现在都在
 * {@code MekCkMachineTile.workCycle} 里，本类只负责最后一步。
 *
 * <h3>配方缓存的由来</h3>
 * 原先每次 {@code findRecipe} 都 {@code new} 一个匿名 {@code ItemStackHandler} 加
 * {@code RecipeWrapper}，并且对每个输入槽做一次 O(配方数) 的 {@code getRecipeFor}；
 * 81 并行的奇点工厂等于每 tick 上百次分配加上万次配方测试。现在：
 * ① 复用同一个包装器实例；② 按「输入槽 + 物品指纹」记忆上一次结果，输入没变直接复用。
 * 迁移时这两点原样保留。
 */
public final class CuttingFactoryExecutor implements MekCkRecipeExecutor {

    /**
     * 单个产出槽能装多少 —— <b>现在问槽自己</b>，不再由本类写死（阶段 2 Task 4.9）。
     *
     * <p><b>⚠️ 这是行为变更</b>：在 {@link cn.ism.mekck.machine.MekCkSlot} 落地前，
     * 本类用常量 {@code OUTPUT_SLOT_CAPACITY = Integer.MAX_VALUE} 判容量，理由是
     * 「用 Mek 的 {@code IInventorySlot.getLimit(ItemStack)} 会被截到
     * {@code min(64, 物品自身堆叠上限)}，导致 81 并行工厂静默降速」。那个理由只在
     * 「上限硬编码成 64」时成立；上限一旦可配（{@code MekCkSlot} 直接继承
     * {@code BasicInventorySlot} 并把配置值递进 7 参构造），两者就能统一：
     * 判定用的上限与玩家实际能存进去的上限来自<b>同一个</b> {@code getLimit}。</p>
     *
     * <p>默认配置下（{@code slot_limit = 2147483647}）行为与旧常量<b>完全一致</b>，
     * 因为那正是旧方块实体对输入/输出槽的既有容量；把配置调小才会看到「装到上限为止」。</p>
     *
     * <p>顺带确认过：{@code BasicInventorySlot.setStack} 会 {@code stack.copy()} 并检查
     * {@code isItemValid}（不合法直接抛 {@code RuntimeException}），但<b>不</b>按上限截断；
     * 且 {@code MekCkSlot} 传下去的物品合法性谓词是
     * {@code BasicInventorySlot.alwaysTrue}，所以这里 setStack 不会抛。</p>
     */
    static int slotCapacity(IInventorySlot slot, ItemStack stack) {
        return MekCkBatchPacking.slotCapacity(slot, stack);
    }

    /** 配方匹配的输入栈，通过它把当前槽的栈喂给 {@link #singleSlotWrapper}。 */
    private final ItemStack[] singleSlotStack = new ItemStack[]{ItemStack.EMPTY};

    /**
     * 复用的配方包装器。匿名 {@code ItemStackHandler} 只读、不许改，
     * 三个「写」方法全是空实现——配方匹配只读不写。
     */
    private final RecipeWrapper singleSlotWrapper = new RecipeWrapper(new ItemStackHandler(1) {
        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack s) {
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @NotNull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return singleSlotStack[0];
        }

        @NotNull
        @Override
        public ItemStack insertItem(int slot, @NotNull ItemStack s, boolean simulate) {
            return s;
        }

        @NotNull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return singleSlotStack[0].isEmpty() ? 64 : singleSlotStack[0].getMaxStackSize();
        }
    });

    /** 每槽的缓存指纹。 */
    private long[] slotRecipeKey;
    /** 每槽的缓存结果；{@code null} 表示「查过，确实没有配方」，是有效结论不是未查。 */
    private CuttingBoardRecipe[] slotRecipeValue;
    /** 每槽的缓存是否已填充。 */
    private boolean[] slotRecipeValid;

    /** 本执行器绑定的机器。由 {@link #tick} 每次刷新。 */
    private MekCkMachineTile tile;

    /** 本 tick 是否真的加工了至少一个槽。 */
    private boolean busy;

    // ── MekCkRecipeExecutor ─────────────────────────────────────────────

    @Override
    public boolean canProcess(MekCkMachineTile tile, int index) {
        this.tile = tile;
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
        Optional<CuttingBoardRecipe> found = findRecipe(index);
        if (found.isEmpty()) {
            return false;
        }
        int consumeCount = Math.min(effectiveProcessCount(tile), input.getCount());
        // 装不下就跳过这一槽，而不是让所有槽一起停摆（与旧实现同口径）。
        return consumeCount > 0 && canFitAll(outputs, found.get().getResults(), consumeCount);
    }

    @Override
    public void process(MekCkMachineTile tile, int index) {
        this.tile = tile;
        this.busy = false;
        if (!canProcess(tile, index)) {
            return;
        }
        List<IInventorySlot> inputs = tile.getInputSlots();
        List<IInventorySlot> outputs = tile.getOutputSlots();
        CuttingBoardRecipe recipe = findRecipe(index).orElse(null);
        if (recipe == null) {
            return;
        }
        int consumeCount = Math.min(effectiveProcessCount(tile), inputs.get(index).getStack().getCount());
        completeRecipe(inputs, outputs, index, recipe, consumeCount);
        this.busy = true;
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>切菜家族<b>没有需要落盘的自有状态</b>：进度条、能量、红石、升级数量全归
     * {@code TileEntityMekanism} 与 Task 4 的机器基线；配方缓存是「物品指纹 → 配方」的
     * 进程内记忆，序列化它只会让存档凭空变大，且读回来时世界已经变了、本就该丢弃。
     * 所以本方法有意为空实现，<b>不是漏写</b>。</p>
     */
    @Override
    public void save(CompoundTag tag) {
    }

    /**
     * {@inheritDoc}
     *
     * <p>唯一要做的是丢缓存：存档往返之后世界可能已经换过数据包或被
     * {@code /reload} 刷新过配方表，留着旧值会让机器按上一个世界的配方加工，
     * 而且这种错配不报错、不留日志。顺带满足「旧存档无此键时保持默认态」——
     * 本方法不看 tag 的任何内容，天然满足。</p>
     */
    @Override
    public void load(CompoundTag tag) {
        invalidateCache();
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
     * <p>含数量会让「同一物品、不同堆叠数」反复失效缓存；不含 NBT 则不同附魔的
     * 同种物品会互相串味。两个都要。</p>
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

    private Optional<CuttingBoardRecipe> findRecipe(int inputSlot) {
        Level level = tile.getLevel();
        ItemStack stack = tile.getInputSlots().get(inputSlot).getStack();
        if (level == null || stack.isEmpty()) {
            return Optional.empty();
        }
        if (slotRecipeValid == null || slotRecipeKey == null || slotRecipeValue == null) {
            int n = Math.max(1, tile.getInputSlots().size());
            slotRecipeKey = new long[n];
            slotRecipeValue = new CuttingBoardRecipe[n];
            slotRecipeValid = new boolean[n];
        }
        if (inputSlot < slotRecipeValid.length) {
            long key = stackKey(stack);
            if (slotRecipeValid[inputSlot] && slotRecipeKey[inputSlot] == key) {
                // ofNullable：缓存里存的 null 是「查过、没有配方」这一有效结论。
                return Optional.ofNullable(slotRecipeValue[inputSlot]);
            }
            singleSlotStack[0] = stack;
            Optional<CuttingBoardRecipe> found =
                    level.getRecipeManager().getRecipeFor(ModRecipeTypes.CUTTING.get(), singleSlotWrapper, level);
            slotRecipeValid[inputSlot] = true;
            slotRecipeKey[inputSlot] = key;
            slotRecipeValue[inputSlot] = found.orElse(null);
            return found;
        }
        singleSlotStack[0] = stack;
        return level.getRecipeManager().getRecipeFor(ModRecipeTypes.CUTTING.get(), singleSlotWrapper, level);
    }

    // ── 批量执行 ────────────────────────────────────────────────────────

    private void completeRecipe(List<IInventorySlot> inputs, List<IInventorySlot> outputs,
                                int inputSlot, CuttingBoardRecipe recipe, int consumeCount) {
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

        List<ItemStack> results = recipe.getResults();
        if (!canFitAll(outputs, results, consumeCount)) {
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
        for (ItemStack result : results) {
            if (result == null || result.isEmpty()) {
                continue;
            }
            // 先用 64 位乘再钳到 int：result.getCount() * consumeCount 会溢出 int。
            long outputCountLong = (long) result.getCount() * consumeCount;
            int outputCount = outputCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) outputCountLong;
            ItemStack multiplied = result.copy();
            multiplied.setCount(outputCount);
            insertOutput(outputs, multiplied);
        }
    }

    /**
     * 产出容量判定：<b>只跟踪每个产出槽的占用数量</b>，不做任何 {@code ItemStack} 拷贝。
     *
     * <p>本方法按槽调用，81 并行工厂原先每次都要把全部产出槽各 {@code copy()} 一份；
     * 现在只记录「槽内物品引用 + 判定过程中的累计数量」，语义与逐份拷贝完全等价。</p>
     *
     * <p>每格的上限来自 {@link #slotCapacity}（= 槽自己的 {@code getLimit}），
     * 所以「判定说装得下」与「槽真装得下」永远是同一句话，原因见该方法上方那段说明。</p>
     *
     * <p>抽成 {@code static} 且只依赖 {@link IInventorySlot} 列表，是为了让它能被
     * 普通 JUnit 直接跑（见 {@code TestCuttingBatchPacking}）——构造一台真的机器
     * 需要 {@code BlockEntityType} 注册表，裸 JVM 里拿不到。</p>
     *
     * <p>实现已于阶段 3 Task 1 搬到 {@link MekCkBatchPacking#canFitAll}
     * （研磨工厂是第二个需要的家族）。本方法保留为薄委托，
     * 因为 {@code TestCuttingBatchPacking} 直接调它。</p>
     */
    static boolean canFitAll(List<IInventorySlot> outputs, List<ItemStack> results, int multiplier) {
        return MekCkBatchPacking.canFitAll(outputs, results, multiplier);
    }

    /**
     * 把一批产物按「先并入已有的同类槽、再往后找空槽」的顺序塞进产出区。
     *
     * <p>实现同 {@link #canFitAll}，已搬到
     * {@link MekCkBatchPacking#insertOutput}，此处只留委托给
     * {@code TestCuttingBatchPacking} 调用。</p>
     */
    static void insertOutput(List<IInventorySlot> outputs, ItemStack stack) {
        MekCkBatchPacking.insertOutput(outputs, stack);
    }

    // ── 并行数 ──────────────────────────────────────────────────────────

    /**
     * 本 tick 单槽能吃下的最大数量。
     *
     * <p>上限走 {@link MekCkUpgradeTypes#capOf} 而不是旧实现的
     * {@code MekckConfig.getFactoryStackUpgradeMax(tier)}：前者先过
     * {@code isSupportedBy} 准入闸门（{@code SINGULARITY} 明确不接受存储卡），
     * 再按 {@code Upgrade.getMax()} 钳制，而读档路径
     * （{@code TileComponentUpgrade} 的 {@code lambda$read$1} = clear + putAll）
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
        int multiplier = stackMultiplier(installed, cap, base, maxParallel);
        return CountMath.mulClamp(Integer.MAX_VALUE, base, multiplier);
    }

    /**
     * 栈倍增系数 = {@code 2^min(已安装数, 本档上限)}，再被
     * 「基础并行 × 倍增 ≤ 配置允许的最大并行」钳一次。
     *
     * <p>抽成纯函数是为了能脱离 {@code MekckUpgradeTypes}（裸 JVM 里
     * {@code MekCkUpgradeRefs.storage()} 必抛）单测这段算术。</p>
     *
     * <p>实现已于阶段 3 Task 1 搬到 {@link MekCkUpgradeTypes#stackMultiplier}</p>
     *
     * <p>研磨工厂是第二个需要它的家族。两份同源算术留在这个类里只会诱使后来者抄第三份，
     * 而两份一旦漂移，表现是「并行数与耗电量对不上」——不报错、不留日志。
     * 本方法保留为薄委托：{@code TestCuttingBatchPacking} 的 8 处断言直接调它，
     * 而那些断言正是「切菜的并行数与耗电量必须用同一个系数」这条不变量的守卫。</p>
     */
    static int stackMultiplier(int installed, int cap, int base, int maxParallel) {
        return MekCkUpgradeTypes.stackMultiplier(installed, cap, base, maxParallel);
    }
}
