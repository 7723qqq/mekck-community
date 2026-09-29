package cn.ism.mekck.blockentity;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.block.CuttingMachineFactoryBlock;
import cn.ism.mekck.menu.CuttingMachineFactoryMenu;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.AE2Compat;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.FastTransfer;
import cn.ism.mekck.util.LagMonitor;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.UpgradeHelper;
import mekanism.client.sound.SoundHandler;
import mekanism.common.registries.MekanismSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CuttingMachineFactoryBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable , cn.ism.mekck.ae2.INetworkPullable {
    public static final int PROCESS_TIME = 200;
    public static final int ENERGY_PER_PROCESS = 20;
    public static final int MAX_RECEIVE_PER_PROCESS = 1_000;
    public static final int SLOT_STACK_LIMIT = 1024;

    // ContainerData indices
    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_ENERGY_CAPACITY = 3;
    public static final int DATA_SIDE_CONFIG = 4;
    public static final int DATA_SPEED_UPGRADE = 5;
    public static final int DATA_ENERGY_UPGRADE = 6;
    public static final int DATA_STACK_UPGRADE = 7;
    public static final int DATA_CREATIVE_UPGRADE = 8;
    public static final int DATA_AUTO_DISTRIBUTE = 9;
    public static final int DATA_REDSTONE_CONTROL = 10;
    public static final int DATA_SIZE = 11;


    private final CuttingMachineFactoryTier tier;
    private final int inputSlots;
    private final int totalSlots;
    private final boolean hasStackUpgrade;

    // ================== 升级读条（Mekanism 式：20 tick 安装） ==================
    private final cn.ism.mekck.util.MekCkUpgradeTracker speedTracker;
    private final cn.ism.mekck.util.MekCkUpgradeTracker energyTracker;
    private final cn.ism.mekck.util.MekCkUpgradeTracker stackTracker;
    private final cn.ism.mekck.util.MekCkUpgradeTracker creativeTracker =
            new cn.ism.mekck.util.MekCkUpgradeTracker(1);
    private boolean autoDistribute = false;
    // ME 自动处理：已勾选材料（registry id 列表），网络有货时持续抽取处理
    private List<String> autoSelectedItems = new ArrayList<>();
    private Component customName;
    private int progress = 0;

    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    // PULSE 模式：收到红石信号(上升沿)后锁存为 true，完成一次完整处理后复位。
    private boolean pulseRunning = false;

    private final ItemStackHandler items;

    private final EnergyStorage energy;

    private final SideMode[] sideConfig = new SideMode[6];

    // Slot indices
    private final int speedUpgradeSlot;
    private final int energyUpgradeSlot;
    private final int stackUpgradeSlot;
    private final int creativeUpgradeSlot;
    private final int powerSlot;

    private LazyOptional<IItemHandler> fullItemCapability;
    private LazyOptional<IItemHandler> inputItemCapability;
    private LazyOptional<IItemHandler> outputItemCapability;
    private LazyOptional<IEnergyStorage> energyCapability;

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
                case DATA_PROGRESS -> progress;
                case DATA_PROCESS_TIME -> getEffectiveProcessTime();
                case DATA_ENERGY -> energy.getEnergyStored();
                case DATA_ENERGY_CAPACITY -> energy.getMaxEnergyStored();
                case DATA_SIDE_CONFIG -> encodeSideConfig();
                case DATA_SPEED_UPGRADE -> getSpeedUpgradeCount();
                case DATA_ENERGY_UPGRADE -> getEnergyUpgradeCount();
                case DATA_STACK_UPGRADE -> getStackUpgradeCount();
                case DATA_CREATIVE_UPGRADE -> hasCreativeUpgrade() ? 1 : 0;
                case DATA_AUTO_DISTRIBUTE -> autoDistribute ? 1 : 0;
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
                default -> 0;
            };
            stored[index] = value;
            return value;
        }

        @Override
        public void set(int index, int value) {
            stored[index] = value;
            if (index == DATA_PROGRESS) {
                progress = value;
            }
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    public CuttingMachineFactoryBlockEntity(CuttingMachineFactoryTier tier, BlockPos pos, BlockState state) {
        super(getTileType(tier), pos, state);
        this.tier = tier;
                // 上限惰性读取 MekckConfig，故 /reload 改配置后立即生效。
                // 必须在构造器体内初始化：tier 在此处才保证已赋值，字段初始化器里无法引用。
                this.speedTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactorySpeedUpgradeMax(tier));
                this.energyTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryEnergyUpgradeMax(tier));
                this.stackTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryStackUpgradeMax(tier));
        this.inputSlots = tier.processes;
        this.hasStackUpgrade = tier.supportsStackUpgrade();
        // Without stack upgrade: 2*inputSlots + 3 (speed, energy, creative)
        // With stack upgrade: 2*inputSlots + 4
        int baseSlots = 2 * inputSlots + (hasStackUpgrade ? 4 : 3);
        this.speedUpgradeSlot = 2 * inputSlots;
        this.energyUpgradeSlot = 2 * inputSlots + 1;
        this.stackUpgradeSlot = hasStackUpgrade ? 2 * inputSlots + 2 : -1;
        this.creativeUpgradeSlot = 2 * inputSlots + (hasStackUpgrade ? 3 : 2);
        // 能源槽位（能量物品），追加在末尾
        this.powerSlot = baseSlots;
        this.totalSlots = baseSlots + 1;

        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
        }

        this.items = new cn.ism.mekck.util.BigStackItemHandler(totalSlots) {
            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                if (slot < inputSlots) {
                    // 输入槽只接受普通食材，不允许放入任何升级物品
                    return !isAnyUpgradeItem(stack) && RecipeInputMatcher.matchesCutting(level, stack);
                }
                int outputStart = inputSlots;
                int outputEnd = 2 * inputSlots;
                if (slot >= outputStart && slot < outputEnd) {
                    return false;
                }
                if (slot == speedUpgradeSlot) {
                    return isSpeedUpgrade(stack);
                }
                if (slot == energyUpgradeSlot) {
                    return isEnergyUpgrade(stack);
                }
                if (hasStackUpgrade && slot == stackUpgradeSlot) {
                    return isStackUpgrade(stack);
                }
                if (slot == creativeUpgradeSlot) {
                    return isCreativeUpgrade(stack);
                }
                if (slot == powerSlot) {
                    return PowerSlotUtil.isValidEnergyItem(stack);
                }
                return false;
            }

            @Override
            public int getSlotLimit(int slot) {
                // Input and output slots: unlimited
                if (slot < 2 * inputSlots) {
                    return Integer.MAX_VALUE;
                }
                // Stack upgrade slot: config-controlled
                if (hasStackUpgrade && slot == stackUpgradeSlot) {
                    return MekckConfig.getFactoryStackUpgradeMax(tier);
                }
                // Creative upgrade slot: max 1
                if (slot == creativeUpgradeSlot) {
                    return 1;
                }
                // Power slot: max 64
                if (slot == powerSlot) {
                    return 64;
                }
                // Speed/energy upgrade slots: config-controlled
                if (slot == speedUpgradeSlot) {
                    return MekckConfig.getFactorySpeedUpgradeMax(tier);
                }
                if (slot == energyUpgradeSlot) {
                    return MekckConfig.getFactoryEnergyUpgradeMax(tier);
                }
                return 0;
            }

            @Override
            protected int getStackLimit(int slot, ItemStack stack) {
                if (slot < 2 * inputSlots) {
                    return getSlotLimit(slot);
                }
                return super.getStackLimit(slot, stack);
            }

            @Override
            protected void onContentsChanged(int slot) {
                setChanged();
            }

            @Override
            public void deserializeNBT(CompoundTag nbt) {
                int targetSize = totalSlots;
                stacks = NonNullList.withSize(targetSize, ItemStack.EMPTY);
                ListTag tagList = nbt.getList("Items", Tag.TAG_COMPOUND);
                for (int i = 0; i < tagList.size(); i++) {
                    CompoundTag itemTags = tagList.getCompound(i);
                    int slot = itemTags.getInt("Slot");
                    if (slot >= 0 && slot < targetSize) {
                        ItemStack stack = cn.ism.mekck.util.BigStackItemHandler.readStack(itemTags);
                        if (!stack.isEmpty()) {
                            stacks.set(slot, stack);
                        }
                    }
                }
                onLoad();
            }
        };

        // Use EnergyStorage with setChanged() calls, matching the universal cutting machine pattern
        this.energy = new EnergyStorage(tier.energyCapacity, tier.processes * MAX_RECEIVE_PER_PROCESS, tier.processes * ENERGY_PER_PROCESS) {
            @Override
            public int getMaxEnergyStored() {
                // Return the boosted capacity based on energy upgrades, so the energy bar renders correctly
                return (int) (tier.energyCapacity * getEffectiveEnergyCapacityMultiplier());
            }

            @Override
            public int receiveEnergy(int maxReceive, boolean simulate) {
                int received = super.receiveEnergy(maxReceive, simulate);
                if (!simulate && received > 0) {
                    setChanged();
                }
                return received;
            }

            @Override
            public int extractEnergy(int maxExtract, boolean simulate) {
                int extracted = super.extractEnergy(maxExtract, simulate);
                if (!simulate && extracted > 0) {
                    setChanged();
                }
                return extracted;
            }

            @Override
            public boolean canReceive() {
                return true;
            }

            @Override
            public boolean canExtract() {
                return true;
            }
        };

        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
    }

    public boolean hasStackUpgradeSlot() {
        return hasStackUpgrade;
    }

    /**
     * 潜行右键时，将手持的升级模块直接装入机器对应升级槽（上限受等级配置控制）。
     * @return 实际消耗的手持升级模块数量（0 表示未装入）。
     */
    public int addUpgradesFromHand(ItemStack held) {
        boolean hasStack = hasStackUpgradeSlot();
        return UpgradeHelper.install(items, speedUpgradeSlot, MekckConfig.getFactorySpeedUpgradeMax(tier),
                energyUpgradeSlot, MekckConfig.getFactoryEnergyUpgradeMax(tier),
                hasStack ? stackUpgradeSlot : -1,
                hasStack ? MekckConfig.getFactoryStackUpgradeMax(tier) : 0,
                creativeUpgradeSlot,
                held);
    }

    private static BlockEntityType<CuttingMachineFactoryBlockEntity> getTileType(CuttingMachineFactoryTier tier) {
        // 直接查注册表，不再逐个 case 列等级。
        // 原先的 switch 只列了 11 个等级、**漏了 BLAZE** ⇒ 放置烈焰等级工厂时落到
        // default 抛 IllegalArgumentException 崩溃（2026-09-16 用户实测：放置烈焰切菜机必崩）。
        // 改为查表后，将来新增等级无需再改这里（制冰工厂一直是这么做的）。
        return cn.ism.mekck.UniversalCuttingMachine.FACTORY_BLOCK_ENTITIES.get(tier).get();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CuttingMachineFactoryBlockEntity machine) {
        // 升级读条：槽位放入升级后 20 tick 安装一次
        machine.tickUpgradeTrackers();
        // Safety check: if the item handler has an unexpected number of slots, skip ticking
        if (machine.items.getSlots() != machine.totalSlots) {
            return;
        }

        boolean wasActive = machine.progress > 0;
        boolean changed = false;

        // Update redstone powered state (Mekanism updatePower equivalent)
        machine.updateRedstone();

        // Drain energy from the power slot (energy cube / tablet / redstone) into the machine
        if (machine.drainPowerSlot()) {
            changed = true;
        }

        // Auto-distribute inputs if enabled
        if (machine.autoDistribute) {
            machine.distributeInputs();
        }

        // Cap energy to effective capacity (in case upgrades were removed)
        int effectiveCapacity = machine.energy.getMaxEnergyStored();
        int currentEnergy = machine.energy.getEnergyStored();
        if (currentEnergy > effectiveCapacity) {
            machine.energy.extractEnergy(currentEnergy - effectiveCapacity, false);
            changed = true;
        }

        // Creative upgrade: fill energy to max, no consumption, 1 tick process time
        boolean hasCreative = machine.hasCreativeUpgrade();
        if (hasCreative) {
            machine.energy.receiveEnergy(machine.energy.getMaxEnergyStored() - machine.energy.getEnergyStored(), false);
        }

        int stackMult = machine.getStackMultiplier();
        int baseProcessCount = machine.getBaseProcessCount();
        int effectiveProcessCount = cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT, baseProcessCount, stackMult);
        double speedMult = machine.getEffectiveSpeedMultiplier();
        double energyConsumptionMult = machine.getEffectiveEnergyConsumptionMultiplier();
        int effectiveProcessTime = hasCreative ? 1 : Math.max(1, (int) (PROCESS_TIME / speedMult));
        int baseEnergyPerTick = hasCreative ? 0 : (int) Math.ceil(ENERGY_PER_PROCESS * speedMult * speedMult * energyConsumptionMult);
        if (machine.getTier().energyPerTick == 0) {
            baseEnergyPerTick = 0;
        }

        // Check all input slots: count active slots with valid recipes and enough output space
        int activeSlots = 0;
        boolean anyValid = false;

        for (int i = 0; i < machine.inputSlots; i++) {
            ItemStack input = machine.items.getStackInSlot(i);
            if (input.isEmpty()) continue;

            Optional<CuttingBoardRecipe> recipe = machine.findRecipe(i);
            if (recipe.isPresent()) {
                int actualConsumeCount = Math.min(effectiveProcessCount, input.getCount());
                if (machine.canFitAll(recipe.get().getResults(), actualConsumeCount)) {
                    activeSlots++;
                    anyValid = true;
                }
                // If a slot can't fit output, simply skip it rather than resetting all progress
            }
        }

        // Calculate energy per tick based on active slots and stack multiplier (NOT effectiveProcessCount,
        // which includes the base process count and can overflow for SINGULARITY with 357913941 base count)
        int energyPerTick = activeSlots > 0 && !hasCreative ? cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, baseEnergyPerTick, activeSlots, stackMult) : 0;

        // PULSE 模式：收到红石信号(上升沿)时锁存，机器开始一次完整的处理
        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        boolean canOperate = machine.canFunctionRedstone();
        if (canOperate && anyValid && machine.energy.getEnergyStored() >= energyPerTick) {
            // Consume energy and advance the single progress bar
            machine.energy.extractEnergy(energyPerTick, false);
            machine.progress++;
            if (machine.progress >= effectiveProcessTime) {
                // Complete all recipes simultaneously (only slots that can fit their output)
                for (int i = 0; i < machine.inputSlots; i++) {
                    ItemStack input = machine.items.getStackInSlot(i);
                    if (input.isEmpty()) continue;
                    Optional<CuttingBoardRecipe> recipe = machine.findRecipe(i);
                    if (recipe.isPresent()) {
                        int actualConsumeCount = Math.min(effectiveProcessCount, input.getCount());
                        if (machine.canFitAll(recipe.get().getResults(), actualConsumeCount)) {
                            machine.completeRecipe(i, recipe.get(), actualConsumeCount);
                        }
                    }
                }
                machine.progress = 0;
                // PULSE 模式：本次完整处理结束，停止并等待下一次红石信号
                if (machine.redstoneControl == RedstoneControl.PULSE) {
                    machine.pulseRunning = false;
                }
            }
            changed = true;
        } else {
            if (machine.progress != 0) {
                // No valid recipes or not enough energy, reset progress
                machine.progress = 0;
                changed = true;
            }
            // PULSE 模式：本 tick 无法运行则解除锁存，等待下一次红石信号重新触发
            if (machine.redstoneControl == RedstoneControl.PULSE && machine.pulseRunning) {
                machine.pulseRunning = false;
                changed = true;
            }
        }

        if (changed) {
            machine.setChanged();
        }
        if (LagMonitor.shouldRunIO(level.getGameTime(), pos)) machine.autoIO(level, pos);

        // AE2 联动：网格节点生命周期 / 配方刷新（未装 AE2 时空操作）
        AE2Compat.serverTick(machine, level, pos);
        // ME 自动处理（未装 AE2 时空操作）
        AE2Compat.autoProcessTick(machine);

        // Update block state active property for sound synchronization
        boolean isActive = machine.progress > 0;
        if (wasActive != isActive) {
            // 阶段 2 Task 4：切菜方块已换成 Mek 的 BlockTile，active 属性改由
            // Attributes.ACTIVE 挂上，本类不再持有 BooleanProperty 常量。
            level.setBlock(pos, mekanism.common.block.attribute.Attribute.setActive(state, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, CuttingMachineFactoryBlockEntity machine) {
        if (mekanism.common.block.attribute.Attribute.isActive(state)) {
            SoundHandler.startTileSound(MekanismSounds.PRECISION_SAWMILL.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
        } else {
            SoundHandler.stopTileSound(pos);
        }
    }

    private AutoIO autoIO;

    private void autoIO(Level level, BlockPos pos) {
        if (autoIO == null) {
            autoIO = new AutoIO(this,
                    new int[][]{{0, inputSlots}},
                    new int[][]{{inputSlots, inputSlots}});
        }
        if (autoIO.run(level, pos, sideConfig, items)) setChanged();
    }

    private int encodeSideConfig() {
        int encoded = 0;
        for (int i = 0; i < 6; i++) {
            encoded |= (sideConfig[i].ordinal() << (i * 2));
        }
        return encoded;
    }

    private void decodeSideConfig(int encoded) {
        for (int i = 0; i < 6; i++) {
            int ordinal = (encoded >> (i * 2)) & 0x3;
            if (ordinal >= 0 && ordinal < SideMode.values().length) {
                sideConfig[i] = SideMode.values()[ordinal];
            }
        }
    }

    public void setSideMode(Direction direction, SideMode mode) {
        this.sideConfig[direction.ordinal()] = mode;
        setChanged();
    }

    public SideMode getSideMode(Direction direction) {
        return sideConfig[direction.ordinal()];
    }

    public void cycleSideMode(Direction direction) {
        SideMode current = sideConfig[direction.ordinal()];
        SideMode next = current.cycle(true, false);
        sideConfig[direction.ordinal()] = next;
        setChanged();
    }

    // ── 配方查找优化 ──
    // 原先每次 findRecipe 都 new 一个匿名 ItemStackHandler + RecipeWrapper，并且对每个输入槽
    // 做一次 O(配方数) 的 getRecipeFor；81 并行的奇点工厂等于每 tick 上百次分配 + 上万次配方测试。
    // 现在：① 复用同一个包装器实例；② 按"输入槽 + 物品指纹"记忆上一次结果，输入没变直接复用。
    private final ItemStack[] singleSlotStack = new ItemStack[]{ItemStack.EMPTY};
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
    private long[] slotRecipeKey;
    private CuttingBoardRecipe[] slotRecipeValue;
    private boolean[] slotRecipeValid;

    /** 输入槽指纹：物品注册名 + NBT（不含数量，配方匹配与数量无关）。 */
    private static long stackKey(ItemStack stack) {
        if (stack.isEmpty()) return 0L;
        net.minecraft.resources.ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        long h = (id == null ? 0 : id.hashCode());
        h = h * 31L + (stack.getTag() == null ? 0 : stack.getTag().hashCode());
        return h == 0L ? 1L : h;
    }

    private Optional<CuttingBoardRecipe> findRecipe(int inputSlot) {
        ItemStack stack = items.getStackInSlot(inputSlot);
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        if (slotRecipeValid == null || slotRecipeKey == null || slotRecipeValue == null) {
            int n = Math.max(1, items.getSlots());
            slotRecipeKey = new long[n];
            slotRecipeValue = new CuttingBoardRecipe[n];
            slotRecipeValid = new boolean[n];
        }
        if (inputSlot < slotRecipeValid.length) {
            long key = stackKey(stack);
            if (slotRecipeValid[inputSlot] && slotRecipeKey[inputSlot] == key) {
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


    private void completeRecipe(int inputSlot, CuttingBoardRecipe recipe, int consumeCount) {
        if (inputSlot < 0 || inputSlot >= items.getSlots() || inputSlot >= inputSlots) {
            return;
        }
        ItemStack input = items.getStackInSlot(inputSlot);
        if (input.isEmpty()) return;

        consumeCount = Math.min(consumeCount, input.getCount());
        if (consumeCount <= 0) return;

        List<ItemStack> results = recipe.getResults();
        if (!canFitAll(results, consumeCount)) {
            return;
        }
        // Directly modify the slot instead of using extractItem to avoid potential issues
        if (input.getCount() <= consumeCount) {
            items.setStackInSlot(inputSlot, ItemStack.EMPTY);
        } else {
            ItemStack remaining = input.copy();
            remaining.setCount(input.getCount() - consumeCount);
            items.setStackInSlot(inputSlot, remaining);
        }
        // Output the multiplied results
        for (ItemStack result : results) {
            long outputCountLong = (long) result.getCount() * consumeCount;
            int outputCount = outputCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) outputCountLong;
            ItemStack multiplied = result.copy();
            multiplied.setCount(outputCount);
            insertOutput(multiplied);
        }
    }

    /**
     * 输出容量判定：**只跟踪每个输出槽的占用数量**，不做任何 ItemStack 拷贝。
     * 本方法在 tick 里按槽调用两次，81 并行工厂原先每次都要把全部输出槽各 copy() 一份
     * （每 tick 上万次分配）；现在只记录「槽内物品引用 + 判定过程中的累计数量」，语义完全等价。
     */
    private boolean canFitAll(List<ItemStack> results, int multiplier) {
        int outputSlots = inputSlots;
        int outputStart = inputSlots;
        int outputEnd = 2 * inputSlots;
        if (outputEnd > items.getSlots()) {
            return false;
        }
        ItemStack[] slotItem = new ItemStack[outputSlots];
        int[] slotCount = new int[outputSlots];
        for (int slot = 0; slot < outputSlots; slot++) {
            int index = outputStart + slot;
            if (index >= items.getSlots()) {
                return false;
            }
            ItemStack existing = items.getStackInSlot(index);
            slotItem[slot] = existing.isEmpty() ? null : existing;
            slotCount[slot] = existing.isEmpty() ? 0 : existing.getCount();
        }

        for (ItemStack result : results) {
            if (result == null || result.isEmpty()) continue;
            long totalCountLong = (long) result.getCount() * multiplier;
            if (totalCountLong > Integer.MAX_VALUE) return false;
            int remaining = (int) totalCountLong;
            for (int slot = 0; slot < outputSlots && remaining > 0; slot++) {
                int outputSlot = outputStart + slot;
                ItemStack current = slotItem[slot];
                if (current == null) {
                    int moved = Math.min(remaining, items.getSlotLimit(outputSlot));
                    slotItem[slot] = result; // 只记引用，不拷贝
                    slotCount[slot] = moved;
                    remaining -= moved;
                } else if (ItemStack.isSameItemSameTags(current, result)) {
                    int space = items.getSlotLimit(outputSlot) - slotCount[slot];
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

    private void insertOutput(ItemStack stack) {
        int outputSlots = inputSlots;
        int outputStart = inputSlots;
        int outputEnd = 2 * inputSlots;
        if (outputEnd > items.getSlots()) {
            return;
        }
        for (int slot = 0; slot < outputSlots && !stack.isEmpty(); slot++) {
            int outputSlot = outputStart + slot;
            if (outputSlot >= items.getSlots()) {
                return;
            }
            ItemStack existing = items.getStackInSlot(outputSlot);
            if (existing.isEmpty()) {
                int moved = Math.min(stack.getCount(), items.getSlotLimit(outputSlot));
                ItemStack inserted = stack.copy();
                inserted.setCount(moved);
                items.setStackInSlot(outputSlot, inserted);
                stack.shrink(moved);
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                int limit = items.getSlotLimit(outputSlot);
                int space = limit - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(stack.getCount(), space);
                    existing.grow(moved);
                    items.setStackInSlot(outputSlot, existing);
                    stack.shrink(moved);
                }
            }
        }
    }

        /** 服务端每 tick：推进各升级槽的安装读条。 */
    private void tickUpgradeTrackers() {
        boolean changed = false;
        if (speedUpgradeSlot >= 0 && speedUpgradeSlot < items.getSlots()) {
            changed |= speedTracker.tick(items.getStackInSlot(speedUpgradeSlot),
                    cn.ism.mekck.util.UpgradeHelper::isSpeedUpgrade);
        }
        if (energyUpgradeSlot >= 0 && energyUpgradeSlot < items.getSlots()) {
            changed |= energyTracker.tick(items.getStackInSlot(energyUpgradeSlot),
                    cn.ism.mekck.util.UpgradeHelper::isEnergyUpgrade);
        }
        if (hasStackUpgrade && stackUpgradeSlot >= 0 && stackUpgradeSlot < items.getSlots()) {
            changed |= stackTracker.tick(items.getStackInSlot(stackUpgradeSlot),
                    cn.ism.mekck.util.UpgradeHelper::isStackUpgrade);
        }
        if (creativeUpgradeSlot >= 0 && creativeUpgradeSlot < items.getSlots()) {
            changed |= creativeTracker.tick(items.getStackInSlot(creativeUpgradeSlot),
                    cn.ism.mekck.util.UpgradeHelper::isCreativeUpgrade);
        }
        if (changed) setChanged();
    }

    /** 升级安装读条进度（0~1，供升级界面）。 */
    public double getUpgradeInstallProgress() {
        return Math.max(speedTracker.getProgress(),
                Math.max(energyTracker.getProgress(),
                        Math.max(stackTracker.getProgress(), creativeTracker.getProgress())));
    }


    /** 卸载升级（升级界面卸载按钮）：从已安装数量扣除并把升级物品放回原槽（放不下则不卸载）。 */
    public void uninstallUpgrade(byte mode, int slot) {
        cn.ism.mekck.util.MekCkUpgradeTracker tracker;
        String itemId;
        if (slot == speedUpgradeSlot) {
            tracker = speedTracker;
            itemId = "mekanism:upgrade_speed";
        } else if (slot == energyUpgradeSlot) {
            tracker = energyTracker;
            itemId = "mekanism:upgrade_energy";
        } else if (hasStackUpgrade && slot == stackUpgradeSlot) {
            tracker = stackTracker;
            itemId = "mekanism_extras:upgrade_stack";
        } else if (slot == creativeUpgradeSlot) {
            tracker = creativeTracker;
            itemId = "mekanism:upgrade_creative";
        } else {
            return;
        }
        if (tracker.getInstalled() <= 0) return;
        net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new net.minecraft.resources.ResourceLocation(itemId));
        if (item == null || item == net.minecraft.world.item.Items.AIR) return;
        ItemStack give = new ItemStack(item, 1);
        ItemStack inSlot = items.getStackInSlot(slot);
        if (!inSlot.isEmpty() && (!ItemStack.isSameItemSameTags(inSlot, give)
                || inSlot.getCount() >= inSlot.getMaxStackSize())) {
            return;
        }
        tracker.uninstall(1);
        if (inSlot.isEmpty()) {
            items.setStackInSlot(slot, give);
        } else {
            inSlot.grow(1);
        }
        setChanged();
    }

    public int getSpeedUpgradeCount() {
        return speedTracker.getInstalled();
    }

    public int getEnergyUpgradeCount() {
        return energyTracker.getInstalled();
    }

    public int getStackUpgradeCount() {
        return stackTracker.getInstalled();
    }

    public boolean getAutoDistribute() {
        return autoDistribute;
    }

    public void setAutoDistribute(boolean value) {
        this.autoDistribute = value;
        setChanged();
    }

    public void toggleAutoDistribute() {
        this.autoDistribute = !this.autoDistribute;
        setChanged();
    }

    // ================== 红石控制 (Mekanism 逻辑) ==================
    @Override
    public RedstoneControl getRedstoneControl() {
        return redstoneControl;
    }

    @Override
    public void setRedstoneControl(RedstoneControl control) {
        if (control == null) {
            control = RedstoneControl.DISABLED;
        }
        this.redstoneControl = control;
        setChanged();
    }

    public boolean isRedstonePowered() {
        return redstonePowered;
    }

    /**
     * 每 tick 更新红石供电状态（上一 tick 用于 PULSE 模式判定），与 Mekanism updatePower 对齐。
     */
    public void updateRedstone() {
        this.redstonePoweredLastTick = this.redstonePowered;
        this.redstonePowered = level != null && level.hasNeighborSignal(this.worldPosition);
    }

    /**
     * 当前红石模式下机器是否允许运行。
     * DISABLED/HIGH/LOW 与 Mekanism MekanismUtils.canFunction 相同；
     * PULSE 为创新功能：收到红石信号后锁存并运行一次完整的处理。
     */
    public boolean canFunctionRedstone() {
        if (redstoneControl == RedstoneControl.PULSE) {
            return pulseRunning;
        }
        return redstoneControl.canFunction(redstonePowered, redstonePoweredLastTick);
    }

    // ================== 能源槽位 ==================
    public int getPowerSlot() {
        return powerSlot;
    }

    /**
     * 从能源槽位中的能量物品抽取能量注入机器能量，返回是否发生变化。
     */
    public boolean drainPowerSlot() {
        if (powerSlot < 0 || powerSlot >= items.getSlots()) {
            return false;
        }
        ItemStack powerStack = items.getStackInSlot(powerSlot);
        return PowerSlotUtil.drain(powerStack, energy, PowerSlotUtil.REDSTONE_PER_TICK);
    }

    public static boolean isUsablePowerItem(ItemStack stack) {
        return PowerSlotUtil.isValidEnergyItem(stack);
    }

    /**
     * Distributes items across all input slots based on auto-distribute logic.
     * Collects items from ALL input slots, combines them (if same type), and redistributes.
     * Called in serverTick when autoDistribute is enabled.
     */
    private void distributeInputs() {
        // ① 先**只读**收集，确认全部输入槽都是同一种物品后再改写。
        //    原实现是"边收集边清空"，一旦遇到不同物品就直接 return —— 已经被清空的前几个槽里的
        //    物品会连同局部变量一起被丢弃（吞物品）。
        int firstSlot = -1;
        ItemStack first = ItemStack.EMPTY;
        long total = 0L;
        for (int i = 0; i < inputSlots; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (firstSlot < 0) {
                firstSlot = i;
                first = stack;
            } else if (!ItemStack.isSameItemSameTags(first, stack)) {
                return; // 混装：保持原样，绝不动任何槽位
            }
            total += stack.getCount();
        }
        if (firstSlot < 0) return;
        // 极端堆叠（> 21 亿）不参与整理：避免计数溢出造成物品丢失/复制
        if (total > Integer.MAX_VALUE - 1L) return;
        int totalItems = (int) total;

        int effectiveProcessCount = getBaseProcessCount() * getStackMultiplier();
        long threshold = (long) inputSlots * effectiveProcessCount;
        ItemStack itemType = first.copyWithCount(1);

        // ② 先算出目标布局（每槽数量），再逐槽比对
        int[] target = new int[inputSlots];
        if (totalItems > threshold) {
            // Case A: Distribute evenly across all input slots
            int perSlot = totalItems / inputSlots;
            int remainder = totalItems % inputSlots;
            for (int i = 0; i < inputSlots; i++) {
                target[i] = perSlot + (remainder > 0 ? 1 : 0);
                if (remainder > 0) remainder--;
            }
        } else {
            // Case B: Split into as few stacks as possible, each as large as possible
            int maxPerStack = Math.max(1, effectiveProcessCount);
            int numStacks = (int) Math.ceil((double) totalItems / maxPerStack);
            numStacks = Math.min(numStacks, inputSlots);
            int remaining = totalItems;
            for (int i = 0; i < inputSlots; i++) {
                if (i < numStacks) {
                    int amount = Math.min(remaining, maxPerStack);
                    target[i] = Math.max(0, amount);
                    remaining -= amount;
                }
            }
        }

        // ③ 只写"确实不符合目标布局"的槽（原先每 tick 无条件重写全部输入槽）
        boolean changed = false;
        for (int i = 0; i < inputSlots; i++) {
            ItemStack cur = items.getStackInSlot(i);
            int want = target[i];
            if (want <= 0) {
                if (!cur.isEmpty()) {
                    items.setStackInSlot(i, ItemStack.EMPTY);
                    changed = true;
                }
                continue;
            }
            if (ItemStack.isSameItemSameTags(cur, itemType) && cur.getCount() == want) continue;
            ItemStack stack = itemType.copy();
            stack.setCount(want);
            items.setStackInSlot(i, stack);
            changed = true;
        }
        if (changed) setChanged();
    }

    /**
     * Returns the stack multiplier: 2^count, capped so that
     * baseProcessCount * multiplier &lt;= maxParallel from config.
     */
    public int getStackMultiplier() {
        int base = MekckConfig.getMultithreadedBase(tier);
        int maxParallel = MekckConfig.getMultithreadedMax(tier);
        if (base >= maxParallel) {
            return 1;
        }
        int maxMult = maxParallel / base;
        int raw = 1 << Math.min(getStackUpgradeCount(), MekckConfig.getFactoryStackUpgradeMax(this.tier));
        return Math.min(raw, Math.max(1, maxMult));
    }

    /**
     * Returns the base process count (items consumed per operation without stack upgrades).
     * Read from the config file (MekckConfig).
     */
    public int getBaseProcessCount() {
        return MekckConfig.getMultithreadedBase(tier);
    }

    public double getEffectiveSpeedMultiplier() {
        return cn.ism.mekck.util.UpgradeHelper.speedMultiplier(getSpeedUpgradeCount());
    }

    public double getEffectiveEnergyConsumptionMultiplier() {
        return cn.ism.mekck.util.UpgradeHelper.energyConsumptionMultiplier(getEnergyUpgradeCount());
    }

    public double getEffectiveEnergyCapacityMultiplier() {
        return cn.ism.mekck.util.UpgradeHelper.energyCapacityMultiplier(getEnergyUpgradeCount());
    }

    public int getEffectiveProcessTime() {
        return Math.max(1, (int) (PROCESS_TIME / getEffectiveSpeedMultiplier()));
    }

    public static boolean isSpeedUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.equals(ResourceLocation.tryParse("mekanism:upgrade_speed"));
    }

    public static boolean isEnergyUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.equals(ResourceLocation.tryParse("mekanism:upgrade_energy"));
    }

    public static boolean isStackUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.equals(ResourceLocation.tryParse("mekanism_extras:upgrade_stack"));
    }

    public boolean hasCreativeUpgrade() {
        return creativeUpgradeSlot >= 0 && creativeUpgradeSlot < items.getSlots() &&
                !items.getStackInSlot(creativeUpgradeSlot).isEmpty();
    }

    public static boolean isCreativeUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.equals(ResourceLocation.tryParse("mekanism_extras:upgrade_creative"));
    }

    public static boolean isAnyUpgradeItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        ResourceLocation speed = ResourceLocation.tryParse("mekanism:upgrade_speed");
        ResourceLocation energy = ResourceLocation.tryParse("mekanism:upgrade_energy");
        ResourceLocation stackUpgrade = ResourceLocation.tryParse("mekanism_extras:upgrade_stack");
        ResourceLocation creative = ResourceLocation.tryParse("mekanism_extras:upgrade_creative");
        ResourceLocation gas = ResourceLocation.tryParse("mekanism:upgrade_gas");
        return id.equals(speed) || id.equals(energy) || id.equals(stackUpgrade) || id.equals(creative) || id.equals(gas);
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
    @Override public int[] getInputSlotRange() { return new int[]{0, getInputSlots()}; }
    @Override public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() { return items; }
    @Override public boolean supportsAutoPull() { return true; } // ME 持续补料：按"每类型上限"（配置 auto_pull_stack_limit）批量补，受 LagMonitor 限流

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) return List.of();
        return cn.ism.mekck.util.NetworkPullHelper.currentOrUnion(level, items.getStackInSlot(0),
                new ResourceLocation("farmersdelight", "cutting"));
    }

    public ContainerData getData() {
        return data;
    }

    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    public int getProgress() {
        return progress;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        if (customName != null) {
            return customName;
        }
        return Component.translatable("block.mekck." + tier.getBlockId());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        throw new UnsupportedOperationException("CuttingMachineFactoryBlockEntity 已于阶段 2 Task 4 退役，请改用 cn.ism.mekck.machine.cutting.CuttingFactoryTile");
        // 阶段 2 Task 4 起本方法不可达：mekck:<tier>_cutting_factory 的 BlockEntityType
        // 已经指向 cn.ism.mekck.machine.cutting.CuttingFactoryTile（容器也随之改成
        // MekanismTileContainer，构造器只收 CuttingFactoryTile），本类既不再被注册也再也构造不出容器。
        // 显式抛异常好过返回 null：返回 null 会在 NetworkHooks 那边以「菜单类型为 null」的形式炸，
        // 报错点离真正的原因十万八千里。Task 5 会连本类一起删掉。
        // Task 5 会连本类一起删掉；在此之前保留它是为了让对照旧实现仍然可能。
    }

    public void dropContents(Level level, BlockPos pos) {
        NonNullList<ItemStack> drops = NonNullList.create();
        for (int slot = 0; slot < items.getSlots(); slot++) {
            drops.add(items.getStackInSlot(slot));
        }
        cn.ism.mekck.util.BigStackDrops.dropAll(level, pos, drops); // 大堆叠安全：避免原版 64 分堆炸实体
    }

    public int getInputSlots() {
        return inputSlots;
    }

    public List<String> getAutoSelectedItems() {
        return autoSelectedItems;
    }

    public void toggleAutoSelectedItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) return;
        if (autoSelectedItems.contains(itemId)) {
            autoSelectedItems.remove(itemId);
        } else {
            autoSelectedItems.add(itemId);
        }
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("SpeedUpgradeTracker", speedTracker.save());
        tag.put("EnergyUpgradeTracker", energyTracker.save());
        tag.put("StackUpgradeTracker", stackTracker.save());
        tag.put("CreativeUpgradeTracker", creativeTracker.save());
        cn.ism.mekck.util.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        tag.put("Items", items.serializeNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Progress", progress);
        byte[] sideBytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            sideBytes[i] = (byte) sideConfig[i].ordinal();
        }
        tag.putByteArray("SideConfig", sideBytes);
        tag.putBoolean("AutoDistribute", autoDistribute);
        tag.putInt("RedstoneControl", redstoneControl.ordinal());
        tag.putBoolean("RedstonePowered", redstonePowered);
        net.minecraft.nbt.ListTag autoTag = new net.minecraft.nbt.ListTag();
        for (String id : autoSelectedItems) {
            autoTag.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        tag.put("AutoSelectedItems", autoTag);
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("SpeedUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) speedTracker.load(tag.getCompound("SpeedUpgradeTracker"));
        if (tag.contains("EnergyUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) energyTracker.load(tag.getCompound("EnergyUpgradeTracker"));
        if (tag.contains("StackUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) stackTracker.load(tag.getCompound("StackUpgradeTracker"));
        if (tag.contains("CreativeUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) creativeTracker.load(tag.getCompound("CreativeUpgradeTracker"));
        cn.ism.mekck.util.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
        items.deserializeNBT(tag.getCompound("Items"));
        // Ensure correct slot count for NBT migration
        if (items.getSlots() != totalSlots) {
            CompoundTag itemsTag = tag.getCompound("Items");
            net.minecraft.nbt.ListTag oldList = itemsTag.getList("Items", Tag.TAG_COMPOUND);
            net.minecraft.nbt.ListTag newList = new net.minecraft.nbt.ListTag();
            int targetSize = totalSlots;
            for (int i = 0; i < oldList.size(); i++) {
                CompoundTag itemTags = oldList.getCompound(i);
                int slot = itemTags.getInt("Slot");
                if (slot >= 0 && slot < targetSize) {
                    newList.add(itemTags);
                }
            }
            CompoundTag newTag = new CompoundTag();
            newTag.putInt("Size", targetSize);
            newTag.put("Items", newList);
            items.deserializeNBT(newTag);
        }
        int remainingEnergy = tag.getInt("Energy");
        while (remainingEnergy > 0) {
            int received = energy.receiveEnergy(remainingEnergy, false);
            if (received == 0) {
                break;
            }
            remainingEnergy -= received;
        }
        progress = tag.getInt("Progress");
        if (tag.contains("SideConfig", Tag.TAG_BYTE_ARRAY)) {
            byte[] sideBytes = tag.getByteArray("SideConfig");
            for (int i = 0; i < Math.min(sideBytes.length, 6); i++) {
                int ordinal = sideBytes[i];
                if (ordinal >= 0 && ordinal < SideMode.values().length) {
                    sideConfig[i] = SideMode.values()[ordinal];
                }
            }
        }
        if (tag.contains("AutoDistribute")) {
            autoDistribute = tag.getBoolean("AutoDistribute");
        }
        if (tag.contains("AutoSelectedItems")) {
            autoSelectedItems = new ArrayList<>();
            net.minecraft.nbt.ListTag autoTag = tag.getList("AutoSelectedItems", Tag.TAG_STRING);
            for (int i = 0; i < autoTag.size(); i++) {
                autoSelectedItems.add(autoTag.getString(i));
            }
        }
        if (tag.contains("RedstoneControl")) {
            redstoneControl = RedstoneControl.byOrdinal(tag.getInt("RedstoneControl"));
        }
        if (tag.contains("RedstonePowered")) {
            redstonePowered = tag.getBoolean("RedstonePowered");
        }
        if (tag.contains("CustomName")) {
            customName = Component.Serializer.fromJson(tag.getString("CustomName"));
        }
    }

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return energyCapability.cast();
        }
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            if (side == null) {
                return fullItemCapability.cast();
            }
            SideMode mode = sideConfig[side.ordinal()];
            if (mode == SideMode.PULL_INPUT) {
                return inputItemCapability.cast();
            } else if (mode == SideMode.PUSH_OUTPUT) {
                return outputItemCapability.cast();
            }
            return LazyOptional.empty();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        fullItemCapability.invalidate();
        inputItemCapability.invalidate();
        outputItemCapability.invalidate();
        energyCapability.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        fullItemCapability = LazyOptional.of(() -> items);
        inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        energyCapability = LazyOptional.of(() -> energy);
    }

    private final class InputItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return inputSlots;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return items.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return items.isItemValid(slot, stack);
        }
    }

    private final class OutputItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return inputSlots;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(inputSlots + slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return items.extractItem(inputSlots + slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(inputSlots + slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }
}