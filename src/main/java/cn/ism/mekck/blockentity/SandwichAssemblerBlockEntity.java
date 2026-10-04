package cn.ism.mekck.blockentity;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

/**
 * 三明治组装机（联动 Some Assembly Required）。
 *
 * <p>三明治在 SAR 中本质是「有序的 ItemStack 列表」，存在物品 NBT 的
 * {@code BlockEntityTag.Sandwich}（ListTag）里，没有配方类型。因此本机器提供两种模式：</p>
 * <ul>
 *   <li><b>复制样品</b>：样品槽放一个手工三明治，解码其有序材料清单，材料区齐备即持续量产，
 *       成品与样品逐字节一致；</li>
 *   <li><b>自定义组装</b>：32 个有序输入格（槽位顺序 = 叠层顺序），玩家设定数量后按量组装。</li>
 * </ul>
 */
public class SandwichAssemblerBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity
        implements MenuProvider, cn.ism.mekck.ae2.INetworkPullable {

    public static final String SAR_MODID = "someassemblyrequired";
    public static final ResourceLocation SAR_SANDWICH_ID = new ResourceLocation(SAR_MODID, "sandwich");
    /** SAR 中三明治层数上限的默认值（其配置 maximum_sandwich_height 默认 32）。 */
    public static final int MAX_LAYERS = 32;
    /** 每层加工耗时（tick）。 */
    public static final int TICKS_PER_LAYER = 5;
    /** 超大堆叠上限。 */
    public static final int BIG_STACK = Integer.MAX_VALUE - 1;

    // ── 槽位布局 ─────────────────────────────
    public static final int ORDERED_START = 0;
    public static final int ORDERED_SLOTS = MAX_LAYERS;           // 0~31 有序输入格（自定义模式）
    public static final int SAMPLE_SLOT = ORDERED_START + ORDERED_SLOTS;   // 32 样品槽（复制模式）
    public static final int MATERIAL_START = SAMPLE_SLOT + 1;     // 33 材料区
    public static final int MATERIAL_SLOTS = 27;
    public static final int RETURN_START = MATERIAL_START + MATERIAL_SLOTS; // 60 返还槽
    public static final int RETURN_SLOTS = 3;
    public static final int OUTPUT_SLOT = RETURN_START + RETURN_SLOTS;      // 63 输出槽
    public static final int SLOT_SPEED_UPGRADE = OUTPUT_SLOT + 1;           // 64
    public static final int SLOT_ENERGY_UPGRADE = SLOT_SPEED_UPGRADE + 1;   // 65
    public static final int SLOT_CREATIVE_UPGRADE = SLOT_ENERGY_UPGRADE + 1; // 66
    public static final int SLOT_POWER = SLOT_CREATIVE_UPGRADE + 1;         // 67
    public static final int TOTAL_SLOTS = SLOT_POWER + 1;                   // 68

    // ── 模式 ─────────────────────────────────
    public static final int MODE_COPY = 0;
    public static final int MODE_CUSTOM = 1;
    /** 序列组模式（F11 §四.3）：读 {@code create:sequenced_assembly}，如 oreo（有序格物品 + 流体→独立物品）。 */
    public static final int MODE_SEQUENCED = 2;
    /** 序列组模式单次加工时长（tick）。 */
    public static final int SEQUENCED_PROCESS_TIME = 100;
    /** 序列组模式流体输入罐容量（mB）。 */
    public static final int SEQUENCED_TANK_CAPACITY = 4 * 1000;

    // ── 数值 ─────────────────────────────────
    public static final int ENERGY_CAPACITY = 100_000;
    public static final int MAX_RECEIVE = 5_000;
    public static final int ENERGY_PER_TICK = 20;

    /** 侧面配置（物品）。 */
    private final cn.ism.mekck.SideMode[] itemSideConfig = new cn.ism.mekck.SideMode[6];
    /** 主动 IO：抽取至材料区、弹出输出槽。 */
    private final cn.ism.mekck.util.AutoIO autoIO;

    private int mode = MODE_COPY;
    /** 自定义模式**剩余待产数量**（0 = 已完成/待机；调整数量即重新计数）。 */
    private int targetCount = 1;
    private int progress = 0;

    private final net.minecraftforge.energy.EnergyStorage energy =
            new net.minecraftforge.energy.EnergyStorage(ENERGY_CAPACITY, MAX_RECEIVE, MAX_RECEIVE) {
                @Override
                public int receiveEnergy(int maxReceive, boolean simulate) {
                    int received = super.receiveEnergy(maxReceive, simulate);
                    if (!simulate && received > 0) setChanged();
                    return received;
                }
            };
    private final LazyOptional<net.minecraftforge.energy.IEnergyStorage> energyCapability =
            LazyOptional.of(() -> energy);

    /** 序列组模式（F11 §四.3）的饮品/酱流体输入罐；由管道/AE2 灌入，本侧面均可填入。 */
    private final net.minecraftforge.fluids.capability.templates.FluidTank sequencedTank =
            new net.minecraftforge.fluids.capability.templates.FluidTank(SEQUENCED_TANK_CAPACITY);
    private final LazyOptional<net.minecraftforge.fluids.capability.IFluidHandler> fluidCapability =
            LazyOptional.of(() -> (net.minecraftforge.fluids.capability.IFluidHandler) sequencedTank);

    public final ItemStackHandler items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
        @Override
        public int getSlotLimit(int slot) {
            // 输出槽尊重物品自身的堆叠上限，其余用超大堆叠
            return slot == OUTPUT_SLOT ? 64 : BIG_STACK;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final LazyOptional<net.minecraftforge.items.IItemHandler> itemCapability = LazyOptional.of(() -> items);

    public SandwichAssemblerBlockEntity(BlockPos pos, BlockState state) {
        super(MekCkStandaloneMachines.SANDWICH_ASSEMBLER_BLOCK_ENTITY.get(), pos, state);
        for (int i = 0; i < 6; i++) itemSideConfig[i] = cn.ism.mekck.SideMode.NONE;
        this.autoIO = new cn.ism.mekck.util.AutoIO(this,
                new int[][]{{MATERIAL_START, MATERIAL_SLOTS}},
                new int[][]{{OUTPUT_SLOT, 1}});
    }

    // ================== SAR 联动 ==================

    /** 是否已安装 Some Assembly Required。 */
    public static boolean hasSar() {
        return ModList.get().isLoaded(SAR_MODID);
    }

    /** SAR 的三明治物品；未安装时返回空。 */
    public static ItemStack sarSandwich() {
        Item item = ForgeRegistries.ITEMS.getValue(SAR_SANDWICH_ID);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** 从样品中解码有序材料清单；不是三明治或层数为 0 时返回空列表。 */
    public static List<ItemStack> decodeSample(ItemStack sample) {
        List<ItemStack> layers = new ArrayList<>();
        if (sample == null || sample.isEmpty()) return layers;
        CompoundTag beTag = sample.getTagElement("BlockEntityTag");
        if (beTag == null || !beTag.contains("Sandwich", Tag.TAG_LIST)) return layers;
        ListTag list = beTag.getList("Sandwich", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && i < MAX_LAYERS; i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) layers.add(stack);
        }
        return layers;
    }

    /** 用有序材料列表构造一个三明治（与 SAR 的 NBT 格式一致）。 */
    public static ItemStack buildSandwich(List<ItemStack> layers) {
        ItemStack out = sarSandwich();
        if (out.isEmpty() || layers.isEmpty()) return ItemStack.EMPTY;
        ListTag list = new ListTag();
        for (ItemStack layer : layers) {
            list.add(layer.copy().save(new CompoundTag()));
        }
        out.getOrCreateTagElement("BlockEntityTag").put("Sandwich", list);
        return out;
    }

    /**
     * SAR 的 {@code Ingredients} 类句柄；未安装 SAR（或类名变动）时为 {@code null}。
     * 解析一次后缓存，避免在 {@link #containerOf} 的逐槽位循环里反复抛 ClassNotFoundException。
     */
    private static volatile Class<?> sarIngredientsClass;
    private static volatile boolean sarIngredientsClassResolved;

    private static Class<?> sarIngredientsClass() {
        if (!sarIngredientsClassResolved) {
            sarIngredientsClassResolved = true;
            if (hasSar()) {
                try {
                    sarIngredientsClass = Class.forName("someassemblyrequired.ingredient.Ingredients");
                } catch (Throwable t) {
                    cn.ism.mekck.util.Reflect.logMissingOnce("someassemblyrequired.ingredient.Ingredients", t);
                }
            }
        }
        return sarIngredientsClass;
    }

    /** 是否带容器（优先用 SAR 的判定，失败时回退到原版合成剩余物）。 */
    public static ItemStack containerOf(ItemStack stack) {
        Class<?> cls = sarIngredientsClass();
        if (cls != null) {
            // 方法句柄由 Reflect 缓存（原先每次调用都重新解析签名）
            Object has = cn.ism.mekck.util.Reflect.callStatic(cls, "hasContainer",
                    new Class<?>[]{ItemStack.class}, stack);
            if (Boolean.TRUE.equals(has)) {
                Object container = cn.ism.mekck.util.Reflect.callStatic(cls, "getContainer",
                        new Class<?>[]{ItemStack.class}, stack);
                if (container instanceof ItemStack cs && !cs.isEmpty()) return cs.copy();
            }
            return ItemStack.EMPTY;
        }
        return stack.getCraftingRemainingItem();
    }

    // ================== AE2 网络拉料 ==================

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity getNetworkPullable() {
        return this;
    }

    /** 网络拉料目标：当前清单所需材料（复制模式取样品清单，自定义模式取有序格）。 */
    @Override
    public java.util.List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        java.util.List<cn.ism.mekck.util.AE2InputSpec> specs = new java.util.ArrayList<>();
        if (mode == MODE_SEQUENCED) return specs; // 序列组：物品走有序格、流体走管道，不做网络拉料
        List<ItemStack> layers = mode == MODE_COPY
                ? decodeSample(items.getStackInSlot(SAMPLE_SLOT)) : orderedLayers();
        for (ItemStack layer : layers) {
            if (layer.isEmpty()) continue;
            specs.add(new cn.ism.mekck.util.AE2InputSpec(
                    net.minecraft.world.item.crafting.Ingredient.of(layer), 1));
        }
        return specs;
    }

    @Override
    public boolean supportsAutoPull() {
        return true;
    }

    /** 拉料落点：材料区。 */
    @Override
    public int[] getInputSlotRange() {
        return new int[]{MATERIAL_START, MATERIAL_START + MATERIAL_SLOTS};
    }

    @Override
    public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() {
        return items;
    }

    private boolean meOrderEnabled = true;

    @Override
    public boolean isMeOrderEnabled() {
        return meOrderEnabled;
    }

    @Override
    public void setMeOrderEnabled(boolean enabled) {
        this.meOrderEnabled = enabled;
        setChanged();
    }

    // ================== 状态 ==================

    public void setItemSideMode(Direction dir, cn.ism.mekck.SideMode mode) {
        if (dir == null) return;
        itemSideConfig[dir.ordinal()] = mode;
        setChanged();
    }

    public cn.ism.mekck.SideMode getItemSideMode(Direction dir) {
        return dir == null ? cn.ism.mekck.SideMode.NONE : itemSideConfig[dir.ordinal()];
    }

    /** 侧配编码（供菜单数据槽同步）。 */
    public int encodeSideConfig() {
        int v = 0;
        for (Direction d : cn.ism.mekck.util.Directions.VALUES) {
            v |= (itemSideConfig[d.ordinal()].ordinal() & 0x3) << (d.ordinal() * 2);
        }
        return v;
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        this.mode = (mode >= MODE_COPY && mode <= MODE_SEQUENCED) ? mode : MODE_COPY;
        this.progress = 0;
        setChanged();
    }

    public void toggleMode() {
        setMode((mode + 1) % 3);
    }

    /** 序列组模式的流体输入罐（供菜单/渲染读取）。 */
    public net.minecraftforge.fluids.capability.templates.FluidTank getSequencedTank() {
        return sequencedTank;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public void setTargetCount(int count) {
        this.targetCount = Math.max(0, Math.min(1_000_000, count));
        setChanged();
    }

    public void adjustTargetCount(int delta) {
        setTargetCount(targetCount + delta);
    }

    public int getProgress() {
        return progress;
    }

    public net.minecraftforge.energy.IEnergyStorage getEnergyStorage() {
        return energy;
    }

    /** 当前模式下的总层数（复制模式 = 样品层数；自定义模式 = 有序格中非空层数；序列模式 = 0）。 */
    public int currentLayers() {
        if (mode == MODE_SEQUENCED) return 0;
        if (mode == MODE_COPY) {
            return decodeSample(items.getStackInSlot(SAMPLE_SLOT)).size();
        }
        return orderedLayers().size();
    }

    /** 加工总耗时（每层 5 tick；序列模式固定 {@link #SEQUENCED_PROCESS_TIME}），无配方时为 0。 */
    public int totalProcessTime() {
        if (mode == MODE_SEQUENCED) return SEQUENCED_PROCESS_TIME;
        int layers = currentLayers();
        return layers <= 0 ? 0 : layers * TICKS_PER_LAYER;
    }

    /** 自定义模式：按槽位顺序取出非空材料。 */
    public List<ItemStack> orderedLayers() {
        List<ItemStack> layers = new ArrayList<>();
        for (int i = ORDERED_START; i < ORDERED_START + ORDERED_SLOTS; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!stack.isEmpty()) layers.add(stack.copy());
        }
        return layers;
    }

    // ================== 主循环 ==================

    public static void serverTick(Level level, BlockPos pos, BlockState state, SandwichAssemblerBlockEntity machine) {
        if (level.isClientSide) return;
        if (machine.autoIO.run(level, pos, machine.itemSideConfig, machine.items)) machine.setChanged();
        boolean active = machine.tick(level, pos);
        if (state.hasProperty(cn.ism.mekck.block.SandwichAssemblerBlock.ACTIVE)
                && state.getValue(cn.ism.mekck.block.SandwichAssemblerBlock.ACTIVE) != active) {
            level.setBlock(pos, state.setValue(cn.ism.mekck.block.SandwichAssemblerBlock.ACTIVE, active), 3);
        }
    }

    /** 返回本 tick 是否在加工。 */
    private boolean tick(Level level, BlockPos pos) {
        if (mode == MODE_SEQUENCED) return tickSequenced(level);
        if (!hasSar()) {
            progress = 0;
            return false;
        }
        if (mode == MODE_CUSTOM && targetCount <= 0) {
            progress = 0;
            return false;
        }
        List<ItemStack> layers = mode == MODE_COPY ? decodeSample(items.getStackInSlot(SAMPLE_SLOT)) : orderedLayers();
        if (layers.isEmpty()) {
            progress = 0;
            return false;
        }
        int total = layers.size() * TICKS_PER_LAYER;
        if (progress >= total) progress = 0;

        // 能耗检查
        int need = ENERGY_PER_TICK;
        if (energy.getEnergyStored() < need) return progress > 0;
        energy.extractEnergy(need, false);

        if (progress == 0 && !canProduce(layers)) return false;
        progress++;
        if (progress >= total) {
            progress = 0;
            produce(layers);
        }
        setChanged();
        return true;
    }

    /** 单次生产的数量：复制模式 1 个；自定义模式取「剩余目标数量」与「物品堆叠上限」的较小值。 */
    private int batchSize(List<ItemStack> layers) {
        if (mode != MODE_CUSTOM) return 1;
        ItemStack probe = buildSandwich(layers);
        int maxStack = probe.isEmpty() ? 1 : Math.max(1, probe.getMaxStackSize());
        return Math.max(1, Math.min(targetCount, maxStack));
    }

    /** 材料与输出空间是否满足一次生产。 */
    private boolean canProduce(List<ItemStack> layers) {
        int batches = batchSize(layers);
        ItemStack out = buildSandwich(layers);
        if (out.isEmpty()) return false;
        ItemStack outStack = out.copy();
        outStack.setCount(batches);
        if (!canInsertOutput(outStack)) return false;
        for (ItemStack layer : layers) {
            if (countMaterial(layer) < batches) return false;
        }
        return true;
    }

    /** 统计材料数量（复制模式查材料区；自定义模式查有序格本身）。 */
    private int countMaterial(ItemStack layer) {
        int total = 0;
        if (mode == MODE_CUSTOM) {
            for (int i = ORDERED_START; i < ORDERED_START + ORDERED_SLOTS; i++) {
                ItemStack stack = items.getStackInSlot(i);
                if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, layer)) total += stack.getCount();
            }
        } else {
            for (int i = MATERIAL_START; i < MATERIAL_START + MATERIAL_SLOTS; i++) {
                ItemStack stack = items.getStackInSlot(i);
                if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, layer)) total += stack.getCount();
            }
        }
        return total;
    }

    /** 消耗材料（精确匹配），并把容器放进返还槽。 */
    private void consumeMaterials(List<ItemStack> layers, int batches) {
        for (ItemStack layer : layers) {
            int remaining = batches;
            int start = (mode == MODE_CUSTOM) ? ORDERED_START : MATERIAL_START;
            int end = (mode == MODE_CUSTOM) ? ORDERED_START + ORDERED_SLOTS : MATERIAL_START + MATERIAL_SLOTS;
            for (int i = start; i < end && remaining > 0; i++) {
                ItemStack stack = items.getStackInSlot(i);
                if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, layer)) continue;
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
                ItemStack container = containerOf(layer);
                if (!container.isEmpty()) {
                    for (int c = 0; c < take; c++) insertReturn(container.copy());
                }
            }
        }
    }

    /** 产物与容器写入；自定义模式下递减剩余目标数量。 */
    private void produce(List<ItemStack> layers) {
        int batches = batchSize(layers);
        consumeMaterials(layers, batches);
        ItemStack out = buildSandwich(layers);
        if (!out.isEmpty()) {
            out.setCount(batches);
            insertOutput(out);
        }
        // 自定义模式：递减剩余目标，产完归零即停
        if (mode == MODE_CUSTOM) {
            targetCount = Math.max(0, targetCount - batches);
        }
        setChanged();
    }

    private boolean canInsertOutput(ItemStack result) {
        if (result.isEmpty()) return true;
        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT);
        if (existing.isEmpty()) return true;
        if (ItemStack.isSameItemSameTags(existing, result)) {
            return cn.ism.mekck.util.CountMath.canStack(existing.getCount(), result.getCount(), existing.getMaxStackSize());
        }
        return false;
    }

    private void insertOutput(ItemStack result) {
        if (result.isEmpty()) return;
        int max = Math.max(1, result.getMaxStackSize());
        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT);
        if (existing.isEmpty()) {
            ItemStack copy = result.copy();
            copy.setCount(Math.min(max, result.getCount()));
            items.setStackInSlot(OUTPUT_SLOT, copy);
        } else if (ItemStack.isSameItemSameTags(existing, result)) {
            existing.grow(Math.min(result.getCount(), max - existing.getCount()));
        }
    }

    private void insertReturn(ItemStack stack) {
        for (int i = RETURN_START; i < RETURN_START + RETURN_SLOTS && !stack.isEmpty(); i++) {
            ItemStack existing = items.getStackInSlot(i);
            if (existing.isEmpty()) {
                items.setStackInSlot(i, stack.copy());
                return;
            }
            if (ItemStack.isSameItemSameTags(existing, stack)
                    && cn.ism.mekck.util.CountMath.canStack(existing.getCount(), stack.getCount(), existing.getMaxStackSize())) {
                existing.grow(stack.getCount());
                return;
            }
        }
        // 三个返还槽都被异类物品占位：旧实现在这里直接返回 ⇒ 返还物（奶桶→桶、瓶→空瓶）
        // 静默消失，而且没有任何日志。掉落物是最后一道兜底：宁可掉在地上也不吞。
        // 只做服务端（与各方块 onRemove 的掉落约定一致，客户端没有权威槽位状态）。
        if (!stack.isEmpty() && level != null && !level.isClientSide) {
            cn.ism.mekck.util.BigStackDrops.dropAbove(level, worldPosition, stack.copy());
            setChanged();
        }
    }

    // ================== 序列组（F11 §四.3：create:sequenced_assembly → 独立物品） ==================

    /** 一次序列组装配的消耗/产出计划。 */
    private static final class SequencedPlan {
        int[] take;                                   // 每个有序槽需扣除数量
        net.minecraftforge.fluids.FluidStack fluidReq; // 流体需求（类型+总量）
        ItemStack result;                              // 独立物品产物
    }

    /** 序列组模式主循环（与 SAR 三明治逻辑完全隔离）。 */
    private boolean tickSequenced(Level level) {
        SequencedPlan plan = matchSequenced(level);
        if (plan == null) {
            progress = 0;
            return false;
        }
        int need = ENERGY_PER_TICK;
        if (energy.getEnergyStored() < need) return progress > 0;
        energy.extractEnergy(need, false);
        if (progress >= SEQUENCED_PROCESS_TIME) progress = 0;
        progress++;
        if (progress >= SEQUENCED_PROCESS_TIME) {
            progress = 0;
            commitSequenced(level);
        }
        setChanged();
        return true;
    }

    /** 提交：重新校验并扣物品/流体、写入产物（全部通过才提交，不凭空返还）。 */
    private void commitSequenced(Level level) {
        SequencedPlan p = matchSequenced(level);
        if (p == null) return;
        for (int s = ORDERED_START; s < ORDERED_START + ORDERED_SLOTS; s++) {
            if (p.take[s] <= 0) continue;
            ItemStack st = items.getStackInSlot(s);
            if (!st.isEmpty()) st.shrink(Math.min(p.take[s], st.getCount()));
        }
        if (p.fluidReq != null && !p.fluidReq.isEmpty()) {
            sequencedTank.drain(p.fluidReq.getAmount(),
                    net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        }
        insertOutput(p.result);
        setChanged();
    }

    /** 遍历 create:sequenced_assembly 配方，找第一个当前输入（有序格物品 + 流体罐）可完全满足的。 */
    private SequencedPlan matchSequenced(Level level) {
        if (level == null) return null;
        net.minecraft.world.item.crafting.RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("create", "sequenced_assembly"));
        if (rt == null) return null;
        for (net.minecraft.world.item.crafting.Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                net.minecraft.world.item.crafting.Ingredient base =
                        (net.minecraft.world.item.crafting.Ingredient) cn.ism.mekck.util.Reflect.call(r, "getIngredient");
                if (base == null || base.isEmpty()) continue;
                int loops = intOf(cn.ism.mekck.util.Reflect.call(r, "getLoops"), 1);
                if (loops <= 0) loops = 1;
                Object transObj = cn.ism.mekck.util.Reflect.call(r, "getTransitionalItem");
                ItemStack transitional = transObj instanceof ItemStack is ? is : ItemStack.EMPTY;

                // 汇总非过渡物品的额外消耗（每遗），以及每遍流体需求量
                List<net.minecraft.world.item.crafting.Ingredient> extrasPerLoop = new ArrayList<>();
                int fluidPerLoop = 0;
                net.minecraftforge.fluids.FluidStack fluidSample = net.minecraftforge.fluids.FluidStack.EMPTY;
                Object seqObj = cn.ism.mekck.util.Reflect.call(r, "getSequence");
                if (seqObj instanceof List<?> seq) {
                    for (Object sq : seq) {
                        Object pr = sq == null ? null : cn.ism.mekck.util.Reflect.call(sq, "getRecipe");
                        if (pr == null) continue;
                        Object ings = cn.ism.mekck.util.Reflect.call(pr, "getIngredients");
                        if (ings instanceof List<?> il) {
                            for (Object o : il) {
                                if (!(o instanceof net.minecraft.world.item.crafting.Ingredient ing) || ing.isEmpty()) continue;
                                if (!transitional.isEmpty() && ing.test(transitional)) continue; // 跳过过渡物流入项
                                extrasPerLoop.add(ing);
                            }
                        }
                        Object fis = cn.ism.mekck.util.Reflect.call(pr, "getFluidIngredients");
                        if (fis instanceof List<?> fl) {
                            for (Object fi : fl) {
                                net.minecraftforge.fluids.FluidStack req = firstSeqFluid(fi);
                                if (req != null && !req.isEmpty()) {
                                    fluidPerLoop += req.getAmount();
                                    if (fluidSample.isEmpty()) fluidSample = req.copy();
                                }
                            }
                        }
                    }
                }
                int totalFluid = fluidPerLoop * loops;

                ItemStack result = sequencedResult(r);
                if (result.isEmpty()) continue;

                // 构建需求清单：base×1 + extras×loops
                List<net.minecraft.world.item.crafting.Ingredient> req = new ArrayList<>();
                req.add(base);
                for (int l = 0; l < loops; l++) req.addAll(extrasPerLoop);

                // 在有序格内分配消耗（同槽可堆多个时允许同槽取多份）
                int[] take = new int[TOTAL_SLOTS];
                boolean ok = true;
                for (net.minecraft.world.item.crafting.Ingredient ing : req) {
                    boolean placed = false;
                    for (int s = ORDERED_START; s < ORDERED_START + ORDERED_SLOTS && !placed; s++) {
                        int avail = items.getStackInSlot(s).getCount() - take[s];
                        if (avail > 0 && ing.test(items.getStackInSlot(s))) {
                            take[s]++;
                            placed = true;
                        }
                    }
                    if (!placed) { ok = false; break; }
                }
                if (!ok) continue;

                // 流体校验：类型一致且总量充足
                if (totalFluid > 0) {
                    if (fluidSample.isEmpty()) continue;
                    net.minecraftforge.fluids.FluidStack stored = sequencedTank.getFluid();
                    if (stored.isEmpty() || !stored.isFluidEqual(fluidSample)) continue;
                    if (stored.getAmount() < totalFluid) continue;
                }
                fluidSample = fluidSample.copy();
                fluidSample.setAmount(totalFluid);

                if (!canInsertOutput(result.copy())) continue;

                SequencedPlan plan = new SequencedPlan();
                plan.take = take;
                plan.fluidReq = fluidSample;
                plan.result = result;
                return plan;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 从 resultPool 首项取产物 ItemStack（反射读公共字段 + getStack）。 */
    private ItemStack sequencedResult(net.minecraft.world.item.crafting.Recipe<?> r) {
        try {
            java.lang.reflect.Field f = cn.ism.mekck.util.Reflect.field(r.getClass(), "resultPool");
            if (f == null) return ItemStack.EMPTY;
            Object pool = f.get(r);
            if (pool instanceof List<?> pl && !pl.isEmpty()) {
                Object out0 = pl.get(0);
                Object st = cn.ism.mekck.util.Reflect.call(out0, "getStack");
                if (st instanceof ItemStack is && !is.isEmpty()) return is.copy();
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    /** FluidIngredient → 样本 FluidStack（取首个匹配流体，数量=所需量）。 */
    private net.minecraftforge.fluids.FluidStack firstSeqFluid(Object fluidIngredient) {
        if (fluidIngredient == null) return null;
        Object stacks = cn.ism.mekck.util.Reflect.call(fluidIngredient, "getMatchingFluidStacks");
        net.minecraftforge.fluids.FluidStack sample = net.minecraftforge.fluids.FluidStack.EMPTY;
        if (stacks instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof net.minecraftforge.fluids.FluidStack fs) {
            sample = fs.copy();
        }
        int amount = intOf(cn.ism.mekck.util.Reflect.call(fluidIngredient, "getRequiredAmount"), 0);
        if (sample.isEmpty() || amount <= 0) return null;
        sample.setAmount(amount);
        return sample;
    }

    private static int intOf(Object v, int fallback) {
        return v instanceof Integer i ? i : fallback;
    }

    // ================== 其它 ==================

    public void dropContents() {
        Level level = getLevel();
        if (level == null) return;
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!stack.isEmpty()) {
                // 大堆叠感知：避免原版 64 分堆造成的实体爆炸
                cn.ism.mekck.util.BigStackDrops.drop(level, getBlockPos().getX() + 0.5D, getBlockPos().getY() + 0.5D,
                        getBlockPos().getZ() + 0.5D, stack);
                items.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
    }

    /**
     * 必须注销 AE2 网格宿主，否则 {@code MekckAe2.HOSTS} 条目永久残留。
     *
     * <p>本类是 {@code INetworkPullable}，因此 {@code NetworkChefProgress.isAe2Machine} 判定通过，
     * {@code MekckAe2.attachCapabilities} 会 {@code HOSTS.computeIfAbsent(be, ...)} 建条目。而
     * {@code HOSTS} 是 WeakHashMap、其 value 又强引用 key（owner），条目<b>无法被 GC 回收</b>，
     * 只能靠 {@code AE2Compat.onRemoved → destroy → HOSTS.remove} 显式清理。其余 12 个同类 BE
     * 都有这个覆写，本类此前缺失 ⇒ 每放置一台就把 BlockEntity 永久钉在静态 map 里。</p>
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        cn.ism.mekck.compat.AE2Compat.onRemoved(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Items", items.serializeNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.put("SequencedFluid", sequencedTank.writeToNBT(new CompoundTag()));
        tag.putInt("Mode", mode);
        tag.putInt("TargetCount", targetCount);
        tag.putInt("Progress", progress);
        byte[] side = new byte[6];
        for (int i = 0; i < 6; i++) side[i] = (byte) itemSideConfig[i].ordinal();
        tag.putByteArray("ItemSideConfig", side);
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Items")) items.deserializeNBT(tag.getCompound("Items"));
        if (tag.contains("Energy")) energy.receiveEnergy(tag.getInt("Energy"), false);
        if (tag.contains("SequencedFluid")) sequencedTank.readFromNBT(tag.getCompound("SequencedFluid"));
        if (tag.contains("Mode")) mode = tag.getInt("Mode");
        if (tag.contains("TargetCount")) targetCount = Math.max(0, tag.getInt("TargetCount"));
        if (tag.contains("Progress")) progress = tag.getInt("Progress");
        if (tag.contains("MeOrderEnabled")) meOrderEnabled = tag.getBoolean("MeOrderEnabled");
        if (tag.contains("ItemSideConfig", Tag.TAG_BYTE_ARRAY)) {
            byte[] side = tag.getByteArray("ItemSideConfig");
            var values = cn.ism.mekck.SideMode.values();
            for (int i = 0; i < Math.min(side.length, 6); i++) {
                int ord = side[i];
                if (ord >= 0 && ord < values.length) itemSideConfig[i] = values[ord];
            }
        }
    }

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) return itemCapability.cast();
        if (capability == ForgeCapabilities.ENERGY) return energyCapability.cast();
        if (capability == ForgeCapabilities.FLUID_HANDLER) return fluidCapability.cast();
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemCapability.invalidate();
        energyCapability.invalidate();
        fluidCapability.invalidate();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.mekck.sandwich_assembler");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new cn.ism.mekck.menu.SandwichAssemblerMenu(containerId, inventory, this);
    }
}
