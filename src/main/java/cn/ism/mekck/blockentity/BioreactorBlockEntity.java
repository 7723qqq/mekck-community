package cn.ism.mekck.blockentity;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.BioreactorBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.menu.BioreactorMenu;
import cn.ism.mekck.util.BioreactorFuels;
import cn.ism.mekck.util.MekCkMultiblock;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.RecipeInputMatcher;

import java.util.Set;
import mekanism.api.Upgrade;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.common.block.BlockBounding;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.tile.TileEntityBoundingBlock;
import mekanism.common.tile.component.TileComponentUpgrade;
import mekanism.common.tile.interfaces.IBoundingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import java.util.List;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 生物反应堆方块实体：
 * <ul>
 *   <li>16 格输入槽 + 1 格能源槽；</li>
 *   <li>物品 → 有机物流体（规则 C &gt; A &gt; B）；</li>
 *   <li>燃料流体 → FE 能量（每 tick 上限 {@value #MAX_GENERATION_PER_TICK} FE）：
 *       有机物流体 350 FE/mb、industrialforegoing:meat 8000 FE/mb、mekanism:nutritional_paste 1400 FE/mb。</li>
 * </ul>
 */
public final class BioreactorBlockEntity extends BlockEntity implements MenuProvider, IBoundingBlock , cn.ism.mekck.ae2.INetworkPullable {
    private static final Logger BIO_LOG = LogManager.getLogger("mekck.bioreactor");
    public static final int INPUT_SLOT_COUNT = 16;
    public static final int POWER_SLOT = 16;
    public static final int TANK_SLOT = 17;
    public static final int TOTAL_SLOTS = 18;

    public static final int ENERGY_CAPACITY = 100_000;
    public static final int MAX_RECEIVE = 1_000;
    public static final int MAX_EXTRACT = 14_000;

    public static final int FLUID_CAPACITY = 480_000;
    /**
     * 每 tick 最多消耗的燃料流体 mb。设为 23 以使最弱燃料（有机物流体 350 FE/mb）也能达到 8000 FE/t 的发电上限。
     */
    public static final int MAX_FLUID_CONSUME_PER_TICK = 23;
    /** 每 tick 最大发电量（FE）。 */
    public static final int MAX_GENERATION_PER_TICK = 8000;
    /** 有机物流体每 mb 发电量（FE），保留供引用与物品燃料路径使用。 */
    public static final int ENERGY_PER_MB = 350;
    /** 每 tick 向每个相邻方块最多推送的 FE（参考 Mekanism 能量立方的 CableUtils.emit 主动输出）。 */
    public static final int EMIT_PER_SIDE = 14_000;
    /** 每 tick 主动输出（推送）的总量上限，避免单 tick 向多面过量放电。 */
    public static final int MAX_ENERGY_OUTPUT_PER_TICK = 14_000;

    /** 外部燃料流体（可能未安装对应模组，此时为 null）。 */
    private static final Fluid MEAT_FLUID =
            ForgeRegistries.FLUIDS.getValue(new ResourceLocation("industrialforegoing", "meat"));
    private static final Fluid NUTRITIONAL_PASTE_FLUID =
            ForgeRegistries.FLUIDS.getValue(new ResourceLocation("mekanism", "nutritional_paste"));

    /** 燃料流体（注册名）→ 每 mb 发电量（FE）。 */
    private static final java.util.Map<ResourceLocation, Integer> FUEL_FE_PER_MB = new java.util.HashMap<>();
    static {
        FUEL_FE_PER_MB.put(UniversalCuttingMachine.ORGANIC_MATTER_SOURCE.getId(), ENERGY_PER_MB); // 350
        if (MEAT_FLUID != null) {
            FUEL_FE_PER_MB.put(ForgeRegistries.FLUIDS.getKey(MEAT_FLUID), 8000);
        }
        if (NUTRITIONAL_PASTE_FLUID != null) {
            FUEL_FE_PER_MB.put(ForgeRegistries.FLUIDS.getKey(NUTRITIONAL_PASTE_FLUID), 1400);
        }
    }

    /** 返回某流体每 mb 的发电量（FE）；非燃料流体返回 0。 */
    private static int getFEPerMb(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return 0;
        }
        return FUEL_FE_PER_MB.getOrDefault(ForgeRegistries.FLUIDS.getKey(fluid), 0);
    }

    /** 该流体是否为可发电的燃料流体（有机物流体 / 肉汤 / 营养糊）。 */
    private static boolean isFuelFluid(Fluid fluid) {
        return fluid != null && fluid != Fluids.EMPTY
                && FUEL_FE_PER_MB.containsKey(ForgeRegistries.FLUIDS.getKey(fluid));
    }

    // ContainerData 索引
    public static final int DATA_ENERGY = 0;
    public static final int DATA_ENERGY_CAPACITY = 1;
    public static final int DATA_FLUID_AMOUNT = 2;
    public static final int DATA_FLUID_TYPE = 3;
    public static final int DATA_GENERATING = 4;
    public static final int DATA_SIZE = 5;

    private final ItemStackHandler items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
@Override
public boolean isItemValid(int slot, ItemStack stack) {
if (slot == POWER_SLOT) {
return PowerSlotUtil.isValidEnergyItem(stack);
}
if (slot == TANK_SLOT) {
return true;
}
// 输入槽只接受可转化为有机物流体的物品（规则 C > A > B）
return RecipeInputMatcher.matchesBioreactorFuel(level, stack);
}

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            super.deserializeNBT(nbt);
            if (getSlots() != TOTAL_SLOTS) {
                setSize(TOTAL_SLOTS);
            }
        }

        @Override
        public CompoundTag serializeNBT() {
            CompoundTag nbt = super.serializeNBT();
            nbt.putInt("Size", TOTAL_SLOTS);
            return nbt;
        }
    };
    /** 发电储能：新增发电直充（绕过 maxReceive）；外部管线/能源槽接收仍受 MAX_RECEIVE 限制。 */
    private static final class GeneratorEnergyStorage extends EnergyStorage {
        GeneratorEnergyStorage(int capacity, int maxReceive, int maxExtract) {
            super(capacity, maxReceive, maxExtract);
        }

        /**
         * 发电直充：绕过 maxReceive（1,000 FE/t 的外部接收上限只应约束管线 / 能源槽输入）。
         * 修复发电量 8k FE/t 时每 tick 仅 1k 入库、其余被 receiveEnergy 丢弃的问题。
         * 只保证不超过储能容量，返回实际存入量。
         */
        public int receiveGenerated(int amount) {
            int accepted = Math.max(0, Math.min(amount, getMaxEnergyStored() - getEnergyStored()));
            if (accepted > 0) {
                this.energy += accepted;
            }
            return accepted;
        }
    }

    private final GeneratorEnergyStorage energy = new GeneratorEnergyStorage(ENERGY_CAPACITY, MAX_RECEIVE, MAX_EXTRACT);
    private final FluidTank fluidTank = new FluidTank(FLUID_CAPACITY, stack -> {
        Fluid f = stack.getFluid();
        return f == UniversalCuttingMachine.ORGANIC_MATTER_SOURCE.get()
                || f == MEAT_FLUID
                || f == NUTRITIONAL_PASTE_FLUID;
    });
    /** 当前每 tick 实际发电量（FE/t），用于 GUI 显示与 ACTIVE 状态。 */
    private int generatingRate;

    private static final org.apache.logging.log4j.Logger EMIT_DEBUG =
            org.apache.logging.log4j.LogManager.getLogger("mekck.EmitDebug");

    private final LazyOptional<IItemHandler> itemCapability = LazyOptional.of(() -> items);
    private final LazyOptional<IEnergyStorage> energyCapability = LazyOptional.of(() -> energy);
    private final LazyOptional<IFluidHandler> fluidCapability = LazyOptional.of(() -> fluidTank);

    /** 客户端缓存（由容器数据同步填充），服务端直接读实时字段。 */
    private final ContainerData data = new ContainerData() {
        private final int[] stored = new int[DATA_SIZE];

        @Override
        public int get(int index) {
            if (level != null && level.isClientSide) {
                if (index >= 0 && index < stored.length) {
                    return stored[index];
                }
                return 0;
            }
            int value = switch (index) {
                case DATA_ENERGY -> energy.getEnergyStored();
                case DATA_ENERGY_CAPACITY -> ENERGY_CAPACITY;
                case DATA_FLUID_AMOUNT -> fluidTank.getFluidAmount();
                case DATA_FLUID_TYPE -> fluidTypeId();
                case DATA_GENERATING -> generatingRate;
                default -> 0;
            };
            stored[index] = value;
            return value;
        }

        @Override
        public void set(int index, int value) {
            stored[index] = value;
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    public BioreactorBlockEntity(BlockPos pos, BlockState state) {
        super(UniversalCuttingMachine.BIOREACTOR_BLOCK_ENTITY.get(), pos, state);
    }

    /**
     * 区块同步携带完整 NBT（罐/物品/能量）：客户端方块实体在进区块时即持有真实数据，
     * 兜底流体条显示（GUI 打开期间另有 ContainerData 实时同步 DATA_FLUID_AMOUNT/TYPE）。
     */
    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    public ContainerData getData() {
        return data;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // AE2 网格节点销毁（未安装 AE2 时为空操作；节点 NBT 由 saveAdditional 保存，重载后 init 重建）
        cn.ism.mekck.util.AE2Compat.onRemoved(this);
    }

    public ItemStackHandler getItems() {
        return items;
    }

    // ================== AE2 通用网络拉料 ==================
    @Override public net.minecraft.world.level.block.entity.BlockEntity getNetworkPullable() { return this; }
    @Override public int[] getInputSlotRange() { return new int[]{0, INPUT_SLOT_COUNT}; }
    @Override public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() { return items; }
    @Override public boolean supportsAutoPull() { return true; } // ME 持续补料：按"每类型上限"（配置 auto_pull_stack_limit）批量补，受 LagMonitor 限流

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        ItemStack slot0 = items.getStackInSlot(0);
        if (slot0.isEmpty()) {
            // 能力边界：生物反应堆燃料集由配置/Mekanism 粉碎/食物营养动态决定，
            // 无法安全构造并集；空槽时不拉料（玩家先放入一种燃料即可拉取同类）。
            return List.of();
        }
        return List.of(new cn.ism.mekck.util.AE2InputSpec(net.minecraft.world.item.crafting.Ingredient.of(slot0.getItem())));
    }

    public int getPowerSlot() {
        return POWER_SLOT;
    }

    public int getTotalSlots() {
        return TOTAL_SLOTS;
    }

    public int getEnergyStored() {
        return energy.getEnergyStored();
    }

    public int getEnergyCapacity() {
        return ENERGY_CAPACITY;
    }

    public FluidStack getFluidStack() {
        return fluidTank.getFluid();
    }

    public int getFluidCapacity() {
        return FLUID_CAPACITY;
    }

    public int getGeneratingRate() {
        return generatingRate;
    }

    private int fluidTypeId() {
        Fluid fluid = fluidTank.getFluid().getFluid();
        return (fluid == null || fluid == Fluids.EMPTY) ? -1 : BuiltInRegistries.FLUID.getId(fluid);
    }

    // ── tick ──────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state, BioreactorBlockEntity tile) {
        // AE2 网格节点生命周期 / 联网检测（未安装 AE2 时为空操作）
        cn.ism.mekck.util.AE2Compat.serverTick(tile, level, pos);
        tile.tickServer(level, pos, state);
    }

    private void tickServer(Level level, BlockPos pos, BlockState state) {
        boolean changed = false;

        // 1. 能源槽：仅作为「输入」入口（能量物品/红石 → 机器内部能量）。
        //    生物反应堆是发电装置，不再把内部能量反向充入槽位物品，
        //    否则会抢占本应送往线缆的发电量（表现为「内部掉电、线缆只出 1k」）。
        ItemStack powerStack = items.getStackInSlot(POWER_SLOT);
        if (!powerStack.isEmpty()) {
            int needed = energy.getMaxEnergyStored() - energy.getEnergyStored();
            boolean didDrain = false;
            if (needed > 0 && PowerSlotUtil.isValidEnergyItem(powerStack)) {
                didDrain = PowerSlotUtil.drain(powerStack, energy, PowerSlotUtil.REDSTONE_PER_TICK);
                if (didDrain) {
                    changed = true;
                }
            }
            if (powerStack.isEmpty()) {
                items.setStackInSlot(POWER_SLOT, ItemStack.EMPTY);
                changed = true;
            }
        }

        // 2. 物品 → 有机物流体（规则 C > A > B；「可接受不消耗」食物（配置 eternal_foods）
        //    每 tick 每槽固定转化 1 个单位且物品保留，实现永久燃料）
        int tankSpace = FLUID_CAPACITY - fluidTank.getFluidAmount();
        if (tankSpace > 0) {
            for (int i = 0; i < INPUT_SLOT_COUNT && tankSpace > 0; i++) {
                ItemStack stack = items.getStackInSlot(i);
                if (stack.isEmpty()) {
                    continue;
                }
                int mbPerUnit = getMBPerUnit(stack);
                if (mbPerUnit <= 0) {
                    continue;
                }
                boolean eternal = MekckConfig.isBioreactorEternalFood(stack.getItem());
                int canTake = eternal ? 1 : Math.min(stack.getCount(), tankSpace / mbPerUnit);
                if (canTake <= 0) {
                    continue;
                }
                int filled = fluidTank.fill(
                        new FluidStack(UniversalCuttingMachine.ORGANIC_MATTER_SOURCE.get(), canTake * mbPerUnit),
                        net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                if (filled <= 0) {
                    continue;
                }
                int consumed = filled / mbPerUnit;
                if (consumed > 0) {
                    if (!eternal) {
                        items.extractItem(i, consumed, false);
                    }
                    tankSpace -= filled;
                    changed = true;
                }
            }
        }

        // 2.5 流体储罐物品 → 流体格（仅抽取能发电的燃料流体）
        ItemStack tankStack = items.getStackInSlot(TANK_SLOT);
        if (!tankStack.isEmpty()) {
            int space = FLUID_CAPACITY - fluidTank.getFluidAmount();
            if (space > 0) {
                LazyOptional<IFluidHandlerItem> tankCap = tankStack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM, null);
                if (tankCap.isPresent()) {
                    IFluidHandlerItem handler = tankCap.resolve().orElse(null);
                    if (handler != null) {
                        FluidStack sim = handler.drain(space,
                                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);
                        if (!sim.isEmpty() && isFuelFluid(sim.getFluid())) {
                            FluidStack real = handler.drain(space,
                                    net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                            if (!real.isEmpty()) {
                                fluidTank.fill(real,
                                        net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                                items.setStackInSlot(TANK_SLOT, handler.getContainer());
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        // 3. 燃料流体 → 能量（按各流体每 mb 发电量换算，每 tick 上限 MAX_GENERATION_PER_TICK）
        int energySpace = energy.getMaxEnergyStored() - energy.getEnergyStored();
        int rate = 0;
        int fluidAmount = fluidTank.getFluidAmount();
        if (energySpace > 0 && fluidAmount > 0) {
            int fePerMb = getFEPerMb(fluidTank.getFluid().getFluid());
            if (fePerMb > 0) {
                // 本 tick 最多可产生的 FE：受电池余量与单 tick 发电上限约束
                int genTarget = Math.min(MAX_GENERATION_PER_TICK, energySpace);
                // 对应 mb 数（向下取整，避免超过发电上限 / 电池容量）
                int mb = Math.min(genTarget / fePerMb,
                        Math.min(fluidAmount, MAX_FLUID_CONSUME_PER_TICK));
                if (mb > 0) {
                    int produced = mb * fePerMb;
                    // 发电直充（绕过 maxReceive）：genTarget 已按储能余量截断，实际可全额入库；
                    // 若走 receiveEnergy 会被 maxReceive=1,000 截断，多发电量凭空丢失（8k 只剩 1k 的根因）
                    energy.receiveGenerated(produced);
                    fluidTank.drain(mb, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                    rate = produced;
                }
            }
        }
        generatingRate = rate;
        if (rate > 0) {
            changed = true;
        }

        // 3.5 主动向外输出能量：覆盖 2×2×3 结构每个外表面的相邻方块（参考 Mekanism 能量立方 CableUtils.emit）
        emitEnergy();

        // 4. ACTIVE 状态同步（用于贴图切换）
        boolean active = generatingRate > 0;
        if (state.getValue(BioreactorBlock.ACTIVE) != active) {
            level.setBlock(pos, state.setValue(BioreactorBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
        }

        if (changed) {
            setChanged();
        }
    }

    /**
     * 主动把内部能量推送到 2×2×3 结构每个外表面相邻的方块（等效 Mekanism 能量立方的 CableUtils.emit）。
     * 与 Mekanism 绑定块不同，本机在 6 个方向上遍历整个结构足迹计算“外露相邻方块”，
     * 因此即使绑定块未实际生成也能正确向四周供电；同时跳过指向自身绑定块的相邻块以避免自循环。
     */
    private int emitEnergy() {
        int stored = energy.getEnergyStored();
        if (stored <= 0) {
            return 0;
        }
        int budget = MAX_ENERGY_OUTPUT_PER_TICK;
        // 收集结构足迹：主方块 + 11 个绑定块位置（与 MekCkMultiblock.SHAPE_2X2X3 一致）
        Set<BlockPos> structure = new java.util.HashSet<>();
        structure.add(worldPosition);
        for (BlockPos p : MekCkMultiblock.getBoundingPositions(worldPosition, getBlockState(), MekCkMultiblock.SHAPE_2X2X3)) {
            structure.add(p);
        }
        int totalOut = 0;
        java.util.Map<String, Integer> neighborTotals = new java.util.HashMap<>();
        for (BlockPos p : structure) {
            if (stored <= 0 || budget <= 0) {
                break;
            }
            for (Direction dir : cn.ism.mekck.util.Directions.VALUES) {
                if (stored <= 0 || budget <= 0) {
                    break;
                }
                BlockPos n = p.relative(dir);
                if (structure.contains(n)) {
                    continue;
                }
                BlockEntity neighbor = level.getBlockEntity(n);
                if (neighbor == null) {
                    continue;
                }
                // 跳过本机器自身的绑定块（BlockBounding 会把能力代理回自己，导致自循环充放电）
                if (neighbor instanceof TileEntityBoundingBlock) {
                    BlockPos main = BlockBounding.getMainBlockPos(level, n);
                    if (main != null && main.equals(worldPosition)) {
                        continue;
                    }
                }
                LazyOptional<IEnergyStorage> cap = neighbor.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite());
                if (!cap.isPresent()) {
                    continue;
                }
                IEnergyStorage handler = cap.resolve().orElse(null);
                if (handler == null || !handler.canReceive()) {
                    continue;
                }
                int toSend = Math.min(EMIT_PER_SIDE, Math.min(stored, budget));
                int accepted = handler.receiveEnergy(toSend, false);
                if (accepted > 0) {
                    energy.extractEnergy(accepted, false);
                    stored -= accepted;
                    budget -= accepted;
                    totalOut += accepted;
                    neighborTotals.merge(neighbor.getClass().getSimpleName(), accepted, Integer::sum);
                }
            }
        }
        // [诊断] 每 20 tick 打印一次本 tick 实际推送给各相邻方块的能量，用于核对输出是否真的“丢失”
        if (totalOut > 0 && level.getGameTime() % 20 == 0) {
            ItemStack ps = items.getStackInSlot(POWER_SLOT);
            BIO_LOG.info("Bioreactor gen={} emitted {} FE/t -> {} (powerSlot={})",
                    generatingRate, totalOut, neighborTotals,
                    ps.isEmpty() ? "empty" : ps.getItem().toString());
        }
        return totalOut;
    }

    /**
     * 单个物品可转换的有机物 mb 数（规则 C&gt;A&gt;B），委托给共享工具类。
     */
    private int getMBPerUnit(ItemStack stack) {
        return BioreactorFuels.getMBPerUnit(stack, level);
    }

    // ── 存档 ──────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        cn.ism.mekck.util.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        tag.put("Items", items.serializeNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.put("FluidTank", fluidTank.writeToNBT(new CompoundTag()));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        cn.ism.mekck.util.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
        if (tag.contains("Items")) {
            items.deserializeNBT(tag.getCompound("Items"));
        }
        energy.receiveEnergy(tag.getInt("Energy"), false);
        if (tag.contains("FluidTank")) {
            fluidTank.readFromNBT(tag.getCompound("FluidTank"));
        }
    }

    // ── 能力 ──────────────────────────────────────────────────────────

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return itemCapability.cast();
        }
        if (cap == ForgeCapabilities.ENERGY) {
            return energyCapability.cast();
        }
        if (cap == ForgeCapabilities.FLUID_HANDLER) {
            return fluidCapability.cast();
        }
        return super.getCapability(cap, side);
    }

    // ── IBoundingBlock：把能力代理到 11 个 Mekanism 绑定块面 ──────────────────
    // 生物反应堆为 2×2×3 多方块，主体以外的面均为绑定块。只有主体实现 IBoundingBlock，
    // 绑定块才会把能量/物品/流体能力代理到自身，否则周边管线只能连到唯一的主块那一面。

    /** 需要向绑定块代理的能力（其余能力默认不代理）。 */
    private static final java.util.Set<Capability<?>> PROXIED_CAPABILITIES = java.util.Set.of(
            ForgeCapabilities.ENERGY,
            ForgeCapabilities.ITEM_HANDLER,
            ForgeCapabilities.FLUID_HANDLER);

    @Override
    public boolean isOffsetCapabilityDisabled(@NotNull Capability<?> capability, @Nullable Direction side, @NotNull Vec3i offset) {
        return !PROXIED_CAPABILITIES.contains(capability);
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getOffsetCapabilityIfEnabled(@NotNull Capability<T> capability, @Nullable Direction side, @NotNull Vec3i offset) {
        // 生物反应堆不区分偏移方向，统一返回主体自身能力
        return getCapability(capability, side);
    }

    // IComparatorSupport
    @Override
    public int getRedstoneLevel() {
        return generatingRate > 0 ? 15 : 0;
    }

    @Override
    public int getCurrentRedstoneLevel() {
        return getRedstoneLevel();
    }

    // IUpgradeTile（生物反应堆不支持升级）
    @Override
    public boolean supportsUpgrades() {
        return false;
    }

    @Override
    public TileComponentUpgrade getComponent() {
        return null;
    }

    @Override
    public void recalculateUpgrades(Upgrade upgradeType) {
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
        return Component.translatable("block.mekck.bioreactor");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new BioreactorMenu(containerId, inventory, this);
    }
}
