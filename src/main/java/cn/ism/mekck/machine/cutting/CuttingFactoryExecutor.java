package cn.ism.mekck.machine.cutting;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
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
 * <h3>⚠️ {@link #tick} 当前<b>没有能量与进度条闸门</b></h3>
 * 本方法的语义是「本 tick 尽可能多地加工」，调用方必须先确认机器<b>本 tick 该不该干活</b>。
 * 旧 {@code CuttingMachineFactoryBlockEntity.serverTick} 的顺序是
 * 「红石 → 扣能量 → 累进度 → 进度满才 completeRecipe」；其中红石三件套按计划归 tile、
 * 能量与进度条属于 Task 4 的机器基类接线，<b>不在本任务的搬运清单里</b>。
 * {@code MekCkMachineTile.onUpdateServer} 会无条件调本方法，因此 Task 4 落地时
 * <b>必须</b>在调用前加闸门（覆写 {@code onUpdateServer} 或改基类），
 * 否则机器会每 tick 空转且不耗电。
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
    private static int slotCapacity(IInventorySlot slot, ItemStack stack) {
        return slot.getLimit(stack);
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
            Optional<CuttingBoardRecipe> found = findRecipe(i);
            if (found.isEmpty()) {
                continue;
            }
            int consumeCount = Math.min(budget, input.getCount());
            if (consumeCount <= 0) {
                continue;
            }
            if (!canFitAll(outputs, found.get().getResults(), consumeCount)) {
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
     */
    static boolean canFitAll(List<IInventorySlot> outputs, List<ItemStack> results, int multiplier) {
        int outputSlots = outputs.size();
        ItemStack[] slotItem = new ItemStack[outputSlots];
        int[] slotCount = new int[outputSlots];
        for (int slot = 0; slot < outputSlots; slot++) {
            ItemStack existing = outputs.get(slot).getStack();
            slotItem[slot] = existing.isEmpty() ? null : existing;
            slotCount[slot] = existing.isEmpty() ? 0 : existing.getCount();
        }

        for (ItemStack result : results) {
            if (result == null || result.isEmpty()) {
                continue;
            }
            long totalCountLong = (long) result.getCount() * multiplier;
            if (totalCountLong > Integer.MAX_VALUE) {
                return false;
            }
            int remaining = (int) totalCountLong;
            for (int slot = 0; slot < outputSlots && remaining > 0; slot++) {
                ItemStack current = slotItem[slot];
                // 上限按需问，不提前算：被跳过的槽（装着别的物品）用不到它，
                // 而 81 并行时这一层循环每 tick 要跑上万次。
                if (current == null) {
                    int moved = Math.min(remaining, slotCapacity(outputs.get(slot), result));
                    slotItem[slot] = result; // 只记引用，不拷贝
                    slotCount[slot] = moved;
                    remaining -= moved;
                } else if (ItemStack.isSameItemSameTags(current, result)) {
                    int space = slotCapacity(outputs.get(slot), result) - slotCount[slot];
                    if (space > 0) {
                        int moved = Math.min(remaining, space);
                        slotCount[slot] += moved;
                        remaining -= moved;
                    }
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把一批产物按「先并入已有的同类槽、再往后找空槽」的顺序塞进产出区。
     *
     * <p>上限同样取自槽自己的 {@code getLimit}，与 {@link #canFitAll} 同一口径——
     * 两处一旦漂移，表现就是「预演说装得下、落槽时却只塞进去一部分，剩下凭空消失」，
     * 而且不报错、不留日志。</p>
     */
    static void insertOutput(List<IInventorySlot> outputs, ItemStack stack) {
        for (int slot = 0; slot < outputs.size() && !stack.isEmpty(); slot++) {
            IInventorySlot outputSlot = outputs.get(slot);
            ItemStack existing = outputSlot.getStack();
            int capacity = slotCapacity(outputSlot, stack);
            if (existing.isEmpty()) {
                int moved = Math.min(stack.getCount(), capacity);
                ItemStack inserted = stack.copy();
                inserted.setCount(moved);
                outputSlot.setStack(inserted);
                stack.shrink(moved);
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                int space = capacity - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(stack.getCount(), space);
                    existing.grow(moved);
                    outputSlot.setStack(existing);
                    stack.shrink(moved);
                }
            }
        }
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
     */
    static int stackMultiplier(int installed, int cap, int base, int maxParallel) {
        if (base <= 0 || base >= maxParallel) {
            // base 为 0 时下面的 maxParallel / base 会除零；base >= maxParallel 时已经追平上限。
            return 1;
        }
        int maxMult = maxParallel / base;
        // 上限再钳一道 30：Java 的移位按 mod 32 处理，1<<31 是负数、1<<32 直接绕回 1，
        // 后者会让「装满卡」静默变成「不倍增」。当前 STORAGE 的 getMax() 是 6，走不到这里，
        // 但这段算术已经被提成可单测的纯函数，将来上限调大时不会有人记得回来补。
        int safeCap = Math.min(cap, 30);
        int raw = 1 << Math.min(installed, safeCap);
        return Math.min(raw, Math.max(1, maxMult));
    }
}
