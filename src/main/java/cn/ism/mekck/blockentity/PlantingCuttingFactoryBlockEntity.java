package cn.ism.mekck.blockentity;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.block.PlantingCuttingFactoryBlock;
import cn.ism.mekck.menu.PlantingCuttingFactoryMenu;
import cn.ism.mekck.recipe.PlantingCuttingRecipe;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.FastTransfer;
import cn.ism.mekck.util.LagMonitor;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.UpgradeHelper;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalTankBuilder;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.gas.IGasTank;
import mekanism.client.sound.SoundHandler;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.registries.MekanismSounds;
import mekanism.common.tile.interfaces.IBoundingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
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

import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PlantingCuttingFactoryBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, IBoundingBlock , cn.ism.mekck.ae2.INetworkPullable {
    public static final int PROCESS_TIME = 200; // 与 mekmm 种植站一致（系列统一 200 tick）
    public static final int ENERGY_PER_PROCESS = 20;
    public static final int MAX_RECEIVE_PER_PROCESS = 1_000;
    public static final int SLOT_STACK_LIMIT = 1024;

    // 营养液(气体)相关，参考 mekmm:ultimate_planting_factory
    public static final long NUTRIENT_MB_PER_SLOT = 100;            // 每格每次处理消耗的营养液 mB
    public static final long NUTRIENT_TANK_MB_PER_PROCESS = 96_000;  // 每并行格对应的营养液罐容量 mB
    public static final String NUTRIENT_GAS_ID = "nutrient_solution";
    public static final String NUTRIENT_GAS_NAMESPACE = "mekmm";

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
    public static final int DATA_NUTRIENT = 9;
    public static final int DATA_AUTO_DISTRIBUTE = 10;
    public static final int DATA_REDSTONE_CONTROL = 11;
    /** 生长方块格状态：见 {@link PlantingCuttingStationBlockEntity#GROWTH_OK} 一组的三个口径。 */
    public static final int DATA_GROWTH_STATUS = 12;
    /** 要求的生长方块档位（0~4 = 神秘农业五级；-1 = 无要求），只用于 GUI 文案。 */
    public static final int DATA_GROWTH_TIER = 13;
    public static final int DATA_SIZE = 14;


    private final CuttingMachineFactoryTier tier;
    private final int inputSlots;
    private final int totalSlots;
    /** 生长方块格状态（每 tick 重算；GUI 通过 ContainerData 读）。 */
    private int growthStatus = PlantingCuttingStationBlockEntity.GROWTH_OK;
    /** 并行格里最严的档位要求（-1 = 无要求）。 */
    private int growthTierIndex = -1;
    private final boolean hasStackUpgrade;

    // ================== ME 终端下单（AE2） ==================
    private net.minecraft.resources.ResourceLocation orderRecipeId;
    private int orderQuantity;
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true;

    // ================== 升级读条（Mekanism 式：20 tick 安装） ==================
    private final cn.ism.mekck.util.MekCkUpgradeTracker speedTracker;
    private final cn.ism.mekck.util.MekCkUpgradeTracker energyTracker;
    private final cn.ism.mekck.util.MekCkUpgradeTracker stackTracker;
    private final cn.ism.mekck.util.MekCkUpgradeTracker creativeTracker =
            new cn.ism.mekck.util.MekCkUpgradeTracker(1);
    /** 奇点创世等级不需要营养液容器/气体存储，其余等级保留。 */
    private final boolean hasNutrient;
    private boolean autoDistribute = false;
    private Component customName;
    private int progress = 0;

    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    // PULSE 模式：收到红石信号(上升沿)后锁存为 true，完成一次完整处理后复位。
    private boolean pulseRunning = false;

    private final ItemStackHandler items;

    private final EnergyStorage energy;

    // 营养液气体存储罐，参考 mekmm:ultimate_planting_factory 的 gasTank
    private final IGasTank gasTank;
    private final IGasHandler gasHandler;
    private final Gas nutrientGas;
    private LazyOptional<IGasHandler> gasCapability;
    /** 气体侧面配置（营养液注入方向）。 */
    private final cn.ism.mekck.SideMode[] gasSideConfig = new cn.ism.mekck.SideMode[6];
    /** 气体自动输入输出（抽取/弹出）。 */
    private cn.ism.mekck.util.AutoGasIO gasAutoIO;

    private final SideMode[] sideConfig = new SideMode[6];

    // Slot indices
    private final int nutrientSlot;
    private final int speedUpgradeSlot;
    private final int energyUpgradeSlot;
    private final int stackUpgradeSlot;
    private final int creativeUpgradeSlot;
    private final int gasUpgradeSlot;
    private final int powerSlot;
    /** 生长方块格（追加在最后；所有并行格共用这一格）。 */
    private final int growthSlot;

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
                case DATA_NUTRIENT -> getNutrientCount();
                case DATA_AUTO_DISTRIBUTE -> autoDistribute ? 1 : 0;
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
                case DATA_GROWTH_STATUS -> growthStatus;
                case DATA_GROWTH_TIER -> growthTierIndex;
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

    public PlantingCuttingFactoryBlockEntity(CuttingMachineFactoryTier tier, BlockPos pos, BlockState state) {
        super(getTileType(tier), pos, state);
        this.tier = tier;
                // 上限惰性读取 MekckConfig，故 /reload 改配置后立即生效。
                // 必须在构造器体内初始化：tier 在此处才保证已赋值，字段初始化器里无法引用。
                this.speedTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactorySpeedUpgradeMax(tier));
                this.energyTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryEnergyUpgradeMax(tier));
                this.stackTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryStackUpgradeMax(tier));
        this.inputSlots = tier.processes;
        this.hasStackUpgrade = tier.supportsStackUpgrade();
        // 营养液：奇点创世等级不需要营养液容器/气体存储，其余等级保留。
        this.hasNutrient = tier != CuttingMachineFactoryTier.SINGULARITY;
        // Total slots: inputs + outputs + [nutrient] + 升级槽(速度/能量/[堆叠]/创造) + 1 (能源)
        this.nutrientSlot = hasNutrient ? 2 * inputSlots : -1;
        int base = 2 * inputSlots + (hasNutrient ? 1 : 0);
        this.speedUpgradeSlot = base;
        this.energyUpgradeSlot = base + 1;
        this.stackUpgradeSlot = hasStackUpgrade ? base + 2 : -1;
        this.creativeUpgradeSlot = hasStackUpgrade ? base + 3 : base + 2;
        // 能源槽位（能量物品），追加在末尾
        this.powerSlot = creativeUpgradeSlot + 1;
        // 气体升级槽：仅 晶钛矩阵 之前的等级提供（装入 Mekanism 气体升级降低 90% 营养液消耗）；
        // 晶钛矩阵 / 星云塑造 / 奇点创世 已内置减免，不提供气体槽。
        // 气体升级槽仅「烈焰炽焱之前」的等级才有（烈焰炽焱及以上已内置减免，无需该槽）
        this.gasUpgradeSlot = (tier.ordinal() < CuttingMachineFactoryTier.BLAZE.ordinal()) ? powerSlot + 1 : -1;
        // 生长方块格：**追加在最后**，不动任何已有索引 ⇒ 老存档靠 load() 的槽位迁移自动补齐
        this.growthSlot = (gasUpgradeSlot >= 0 ? gasUpgradeSlot : powerSlot) + 1;
        this.totalSlots = growthSlot + 1;

        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
            gasSideConfig[i] = SideMode.NONE;
        }

        this.items = new cn.ism.mekck.util.BigStackItemHandler(totalSlots) {
            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                if (slot < inputSlots) {
                    // 输入槽只接受普通食材/种子，不允许放入任何升级物品
                    return !isAnyUpgradeItem(stack) && RecipeInputMatcher.matchesPlantingSeed(level, stack);
                }
                int outputStart = inputSlots;
                int outputEnd = 2 * inputSlots;
                if (slot >= outputStart && slot < outputEnd) {
                    return false;
                }
                if (hasNutrient && slot == nutrientSlot) {
                    return isValidGasContainer(stack);
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
                if (gasUpgradeSlot >= 0 && slot == gasUpgradeSlot) {
                    return isGasUpgrade(stack);
                }
                if (slot == growthSlot) {
                    // 生长方块格：什么都收（神秘农业种子由配方白名单判合格与否）
                    return true;
                }
                return false;
            }

            @Override
            public int getSlotLimit(int slot) {
                // Input slots: stack limit 1 (seed is a catalyst, not consumed)
                if (slot < inputSlots) {
                    return 1;
                }
                // Output slots: unlimited
                if (slot < 2 * inputSlots) {
                    return Integer.MAX_VALUE;
                }
                // Nutrient slot: unlimited
                if (slot == nutrientSlot) {
                    return Integer.MAX_VALUE;
                }
                // Speed upgrade slot: config-controlled
                if (slot == speedUpgradeSlot) {
                    return MekckConfig.getFactorySpeedUpgradeMax(tier);
                }
                // Energy upgrade slot: config-controlled
                if (slot == energyUpgradeSlot) {
                    return MekckConfig.getFactoryEnergyUpgradeMax(tier);
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
                // Gas upgrade slot: max 1
                if (gasUpgradeSlot >= 0 && slot == gasUpgradeSlot) {
                    return 1;
                }
                // 生长方块格：单块
                if (slot == growthSlot) {
                    return 1;
                }
                return 0;
            }

            @Override
            protected int getStackLimit(int slot, ItemStack stack) {
                if (slot < 2 * inputSlots || slot == nutrientSlot || slot == growthSlot) {
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

        // Energy storage with setChanged() calls
        this.energy = new EnergyStorage(tier.energyCapacity, tier.processes * MAX_RECEIVE_PER_PROCESS, tier.processes * ENERGY_PER_PROCESS) {
            @Override
            public int getMaxEnergyStored() {
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

        this.gasHandler = new GasHandlerSingle();

        if (hasNutrient) {
            this.nutrientGas = resolveNutrientGas();
            this.gasTank = ChemicalTankBuilder.GAS.create((long) tier.processes * NUTRIENT_TANK_MB_PER_PROCESS, gas -> {
                // 仅接受 mekmm 营养液气体；未安装 mekmm 时不接受任何气体
                return nutrientGas != null && nutrientGas == gas;
            }, this::setChanged);
            this.gasCapability = LazyOptional.of(() -> gasHandler);
        } else {
            // 奇点创世：无营养液/气体存储
            this.nutrientGas = null;
            this.gasTank = ChemicalTankBuilder.GAS.create(1, gas -> false, this::setChanged);
            this.gasCapability = LazyOptional.empty();
        }
        this.gasAutoIO = new cn.ism.mekck.util.AutoGasIO(this.gasTank);

        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
    }

    public boolean hasStackUpgradeSlot() {
        return hasStackUpgrade;
    }

    public int addUpgradesFromHand(ItemStack held) {
        boolean hasStack = hasStackUpgradeSlot();
        return UpgradeHelper.install(getItems(), speedUpgradeSlot, MekckConfig.getFactorySpeedUpgradeMax(tier),
                energyUpgradeSlot, MekckConfig.getFactoryEnergyUpgradeMax(tier),
                hasStack ? stackUpgradeSlot : -1,
                hasStack ? MekckConfig.getFactoryStackUpgradeMax(tier) : 0,
                creativeUpgradeSlot,
                gasUpgradeSlot,
                held);
    }

    private static BlockEntityType<PlantingCuttingFactoryBlockEntity> getTileType(CuttingMachineFactoryTier tier) {
        // 直接查注册表，不再逐个 case 列等级。
        // 原先的 switch 只列了 11 个等级、**漏了 BLAZE** ⇒ 放置烈焰等级工厂时落到
        // default 抛 IllegalArgumentException 崩溃（2026-09-16 用户实测：放置烈焰切菜机必崩）。
        // 改为查表后，将来新增等级无需再改这里（制冰工厂一直是这么做的）。
        return cn.ism.mekck.UniversalCuttingMachine.PLANTING_CUTTING_FACTORY_BLOCK_ENTITIES.get(tier).get();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PlantingCuttingFactoryBlockEntity machine) {
        // 升级读条：槽位放入升级后 20 tick 安装一次
        machine.tickUpgradeTrackers();
        // Safety check: if the item handler has an unexpected number of slots, skip ticking
        if (machine.items.getSlots() != machine.totalSlots) {
            return;
        }

        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.util.AE2Compat.serverTick(machine, level, pos);

        boolean wasActive = machine.progress > 0;
        boolean changed = false;

        // Update redstone powered state (Mekanism updatePower equivalent)
        machine.updateRedstone();

        // Drain energy from the power slot (energy cube / tablet / redstone) into the machine
        if (machine.drainPowerSlot()) {
            changed = true;
        }

        // Fill the nutrient gas tank from the container slot (参考 mekmm GasInventorySlot.fillTankOrConvert)
        if (machine.hasNutrient && machine.fillTankFromSlot()) {
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

        // 生长方块格：所有并行格共用一格，按「最严」的需求算状态（0 = 无需/满足，1 = 缺方块，2 = 等级不足）
        machine.updateGrowthStatus(level);

        // Check all input slots: count active slots with valid planting recipes and enough output space
        int activeSlots = 0;
        boolean anyValid = false;

        for (int i = 0; i < machine.inputSlots; i++) {
            ItemStack input = machine.items.getStackInSlot(i);
            if (input.isEmpty()) continue;

            Optional<PlantingCuttingRecipe> plantingOpt = machine.findPlantingRecipe(level, input);
            if (plantingOpt.isPresent()) {
                int actualConsumeCount = effectiveProcessCount;
                // Get all potential outputs after planting + cutting
                List<ItemStack> allOutputs = machine.getAllPotentialOutputs(level, plantingOpt.get(), actualConsumeCount);
                // 需要生长方块的种子（神秘农业）必须槽里放对等级，否则这一格不参与本 tick
                if (machine.canFitAll(allOutputs) && machine.hasValidGrowthSoil(plantingOpt.get())) {
                    activeSlots++;
                    anyValid = true;
                }
            }
        }

        // Check if we have enough nutrient gas for all active slots (with tier consumption multiplier)
        long nutrientStored = machine.gasTank.getStored();
        double gasMult = machine.getGasConsumptionMultiplier();
        long nutrientNeeded = hasCreative ? 0 : (long) Math.ceil(activeSlots * gasMult * NUTRIENT_MB_PER_SLOT);
        boolean enoughNutrient = nutrientStored >= nutrientNeeded || hasCreative || gasMult == 0.0;

        // Calculate energy per tick based on active slots and stack multiplier
        int energyPerTick = activeSlots > 0 && !hasCreative ? cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, baseEnergyPerTick, activeSlots, stackMult) : 0;

        // PULSE 模式：收到红石信号(上升沿)时锁存，机器开始一次完整的处理
        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        boolean canOperate = machine.canFunctionRedstone();
        if (canOperate && anyValid && enoughNutrient && machine.energy.getEnergyStored() >= energyPerTick) {
            // Consume energy and advance the single progress bar
            machine.energy.extractEnergy(energyPerTick, false);
            machine.progress++;
            if (machine.progress >= effectiveProcessTime) {
                // Consume nutrient and complete all recipes simultaneously
                if (!hasCreative && nutrientNeeded > 0) {
                    machine.consumeNutrient(nutrientNeeded);
                }
                for (int i = 0; i < machine.inputSlots; i++) {
                    ItemStack input = machine.items.getStackInSlot(i);
                    if (input.isEmpty()) continue;
                    Optional<PlantingCuttingRecipe> plantingOpt = machine.findPlantingRecipe(level, input);
                    if (plantingOpt.isPresent()) {
                        int actualConsumeCount = effectiveProcessCount;
                        List<ItemStack> allOutputs = machine.getAllPotentialOutputs(level, plantingOpt.get(), actualConsumeCount);
                        if (machine.canFitAll(allOutputs) && machine.hasValidGrowthSoil(plantingOpt.get())) {
                            // Execute planting then cutting
                            machine.executePlantingAndCutting(i, level, plantingOpt.get(), actualConsumeCount);
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
            // PULSE 模式：本 tick 无法运行则解除锁存，等待下一次红石信号重新触发
            if (machine.redstoneControl == RedstoneControl.PULSE && machine.pulseRunning) {
                machine.pulseRunning = false;
                changed = true;
            }
            if (machine.progress != 0) {
                // No valid recipes, not enough energy, or not enough nutrient, reset progress
                machine.progress = 0;
                changed = true;
            }
        }

        if (changed) {
            machine.setChanged();
        }
        if (LagMonitor.shouldRunIO(level.getGameTime(), pos)) machine.autoIO(level, pos);

        // Update block state active property for sound synchronization
        boolean isActive = machine.progress > 0;
        if (wasActive != isActive) {
            level.setBlock(pos, state.setValue(PlantingCuttingFactoryBlock.ACTIVE, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, PlantingCuttingFactoryBlockEntity machine) {
        if (state.getValue(PlantingCuttingFactoryBlock.ACTIVE)) {
            SoundHandler.startTileSound(MekanismSounds.PRECISION_SAWMILL.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
        } else {
            SoundHandler.stopTileSound(pos);
        }
    }

    // ────────────── 生长方块格（与种植切配站同一套口径） ──────────────

    /**
     * 每 tick 重算生长方块格状态：并行格共用一个生长方块格，所以取**最严**的那个需求。
     * <p>判定走生成器写进配方的 {@code soils} 白名单，运行时**不反射 BotanyPots**。</p>
     */
    private void updateGrowthStatus(Level level) {
        int status = PlantingCuttingStationBlockEntity.GROWTH_OK;
        int tier = -1;
        ItemStack soil = growthSlot >= 0 && growthSlot < items.getSlots() ? items.getStackInSlot(growthSlot) : ItemStack.EMPTY;
        for (int i = 0; i < inputSlots; i++) {
            ItemStack input = items.getStackInSlot(i);
            if (input.isEmpty()) {
                continue;
            }
            Optional<PlantingCuttingRecipe> plantingOpt = findPlantingRecipe(level, input);
            if (plantingOpt.isEmpty() || !plantingOpt.get().requiresGrowthSoil()) {
                continue;
            }
            tier = Math.max(tier, PlantingCuttingStationBlockEntity.growthTierIndexOf(plantingOpt.get().getRequiredSoilCategories()));
            int slotStatus = soil.isEmpty() ? PlantingCuttingStationBlockEntity.GROWTH_MISSING
                    : (plantingOpt.get().getGrowthSoils().test(soil) ? PlantingCuttingStationBlockEntity.GROWTH_OK
                    : PlantingCuttingStationBlockEntity.GROWTH_TOO_LOW);
            status = Math.max(status, slotStatus);
        }
        this.growthStatus = status;
        this.growthTierIndex = tier;
    }

    /** 生长方块格是否满足该配方（配方没要求 ⇒ 恒 true）。 */
    public boolean hasValidGrowthSoil(PlantingCuttingRecipe recipe) {
        if (recipe == null || !recipe.requiresGrowthSoil()) {
            return true;
        }
        return growthSlot >= 0 && growthSlot < items.getSlots()
                && recipe.getGrowthSoils().test(items.getStackInSlot(growthSlot));
    }

    /** 生长方块格在物品栏里的下标（GUI 用）。 */
    public int getGrowthSlot() {
        return growthSlot;
    }

    /** 复用的单槽包装（避免每次配方查找都分配 ItemStackHandler + RecipeWrapper）。 */
    private final ItemStack[] singleSlotStack = new ItemStack[]{ItemStack.EMPTY};
    private final RecipeWrapper singleSlotWrapper = new RecipeWrapper(new ItemStackHandler(1) {
        @Override
        public int getSlots() {
            return 1;
        }

        @NotNull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return singleSlotStack[0];
        }

        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack s) {
            singleSlotStack[0] = s;
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

    /**
     * Finds a mekck:plantcut recipe for the given seed.
     */
    // ================== 本机下单（面板「本机 / ME」的本机一侧） ==================

    /** 供「本机下单」面板展示：各输入槽里的种子能做的 plantcut 配方（去重）。 */
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> out = new ArrayList<>();
        if (level == null) return out;
        var type = UniversalCuttingMachine.PLANTING_CUTTING_RECIPE_TYPE.get();
        if (type == null) return out;
        java.util.Set<net.minecraft.resources.ResourceLocation> seen = new java.util.HashSet<>();
        for (int s = 0; s < getInputSlots(); s++) {
            ItemStack seed = items.getStackInSlot(s);
            if (seed.isEmpty()) continue;
            singleSlotStack[0] = seed;
            for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                if (!recipeMatches(r)) continue;
                if (seen.add(r.getId())) out.add(r);
            }
        }
        singleSlotStack[0] = ItemStack.EMPTY; // 复位，避免污染后续 findPlantingRecipe
        return out;
    }

    /** 供「本机下单」面板的 Max 按钮：所有输入槽的种子能支撑几份。 */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        if (recipe == null || level == null) return 0;
        try {
            int max = Integer.MAX_VALUE;
            for (Ingredient ing : recipe.getIngredients()) {
                if (ing == null || ing.isEmpty()) continue;
                int have = 0;
                for (int s = 0; s < getInputSlots(); s++) {
                    ItemStack st = items.getStackInSlot(s);
                    if (!st.isEmpty() && ing.test(st)) have += st.getCount();
                }
                max = Math.min(max, have);
                if (max <= 0) return 0;
            }
            return max == Integer.MAX_VALUE ? 0 : max;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 配方是否匹配当前 singleSlotWrapper（通配符 Recipe 需原始类型调用）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean recipeMatches(Recipe<?> recipe) {
        try {
            return ((Recipe) recipe).matches(singleSlotWrapper, level);
        } catch (Throwable t) {
            return false;
        }
    }

    private Optional<PlantingCuttingRecipe> findPlantingRecipe(Level level, ItemStack seed) {
        if (seed.isEmpty()) return Optional.empty();
        var plantCutType = UniversalCuttingMachine.PLANTING_CUTTING_RECIPE_TYPE.get();
        if (plantCutType == null) return Optional.empty();
        // 复用同一个包装器实例（原先每次调用都 new 一个匿名 ItemStackHandler + RecipeWrapper）
        singleSlotStack[0] = seed;
        Optional<PlantingCuttingRecipe> found =
                level.getRecipeManager().getRecipeFor(plantCutType, singleSlotWrapper, level);
        // ME 下单：只执行订单指定的配方
        if (orderRecipeId != null) {
            if (found.isEmpty() || !orderRecipeId.equals(found.get().getId())) return Optional.empty();
        }
        return found;
    }

    @Override
    public boolean isMeOrderEnabled() {
        return meOrderEnabled;
    }

    @Override
    public void setMeOrderEnabled(boolean enabled) {
        this.meOrderEnabled = enabled;
        setChanged();
    }

    /** ME 终端下单。 */

    /** AE2 下单：当前订单配方 id（无订单为 null）。 */
    public net.minecraft.resources.ResourceLocation getOrderRecipeId() {
        return this.orderRecipeId;
    }

    /** AE2 下单：当前订单剩余数量（0 = 无订单）。 */
    public int getOrderQuantity() {
        return this.orderQuantity;
    }

    public void setOrder(net.minecraft.resources.ResourceLocation recipeId, int quantity) {
        this.orderRecipeId = recipeId;
        this.orderQuantity = Math.max(1, quantity);
        this.orderCompleted = 0;
        setChanged();
    }

    /**
     * Gets all potential outputs from the recipe.
     * The results are already pre-computed (including cutting if applicable).
     */
    private List<ItemStack> getAllPotentialOutputs(Level level, PlantingCuttingRecipe recipe, int multiplier) {
        List<ItemStack> outputs = new ArrayList<>();

        // Main results (already pre-computed with cutting if applicable)
        for (ItemStack result : recipe.getResults()) {
            long totalCountLong = (long) result.getCount() * multiplier;
            int totalCount = totalCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCountLong;
            ItemStack multiplied = result.copy();
            multiplied.setCount(totalCount);
            outputs.add(multiplied);
        }

        // Secondary results (assume worst case for canFit check)
        for (ItemStack result : recipe.getSecondaryResults()) {
            long totalCountLong = (long) result.getCount() * multiplier;
            int totalCount = totalCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCountLong;
            ItemStack multiplied = result.copy();
            multiplied.setCount(totalCount);
            outputs.add(multiplied);
        }

        return outputs;
    }

    /**
     * Executes the recipe: produces the pre-computed outputs. The seed is not consumed (acts as a catalyst).
     */
    private void executePlantingAndCutting(int inputSlot, Level level, PlantingCuttingRecipe recipe, int consumeCount) {
        if (inputSlot < 0 || inputSlot >= items.getSlots() || inputSlot >= inputSlots) {
            return;
        }
        ItemStack input = items.getStackInSlot(inputSlot);
        if (input.isEmpty()) return;

        // Seed is not consumed (acts as a catalyst)

        // Main results (already pre-computed with cutting)
        for (ItemStack result : recipe.getResults()) {
            long totalCountLong = (long) result.getCount() * consumeCount;
            int totalCount = totalCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCountLong;
            ItemStack multiplied = result.copy();
            multiplied.setCount(totalCount);
            insertOutput(multiplied);
        }

        // Secondary results (with chance)
        if (level.random.nextFloat() < recipe.getSecondaryChance()) {
            for (ItemStack result : recipe.getSecondaryResults()) {
                long totalCountLong = (long) result.getCount() * consumeCount;
                int totalCount = totalCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCountLong;
                ItemStack multiplied = result.copy();
                multiplied.setCount(totalCount);
                insertOutput(multiplied);
            }
        }
        // ME 下单进度
        if (orderRecipeId != null) {
            orderCompleted++;
            if (orderCompleted >= orderQuantity) {
                orderRecipeId = null;
                orderQuantity = 0;
                orderCompleted = 0;
            }
        }
    }

    /**
     * Returns the amount of nutrient gas (mB) currently stored in the gas tank.
     * Used for ContainerData synchronization (clamped to int range).
     */
    public int getNutrientCount() {
        long stored = gasTank.getStored();
        return (int) Math.min(Integer.MAX_VALUE, stored);
    }

    /**
     * Returns the amount of nutrient gas (mB) stored in the gas tank.
     */
    public long getNutrientMb() {
        return gasTank.getStored();
    }

    /**
     * Consumes the given amount of nutrient gas (mB) from the gas tank.
     */
    private void consumeNutrient(long countMb) {
        if (countMb <= 0) return;
        gasTank.extract(countMb, Action.EXECUTE, AutomationType.EXTERNAL);
        setChanged();
    }

    /**
     * Tries to fill the nutrient gas tank from the container placed in the nutrient slot
     * (net 参考 mekmm GasInventorySlot.fillTankFromItem)。返回是否发生变化。
     */
    public boolean fillTankFromSlot() {
        if (nutrientSlot < 0 || nutrientSlot >= items.getSlots()) {
            return false;
        }
        ItemStack container = items.getStackInSlot(nutrientSlot);
        if (container.isEmpty() || gasTank.getNeeded() <= 0) {
            return false;
        }
        IGasHandler handler = container.getCapability(Capabilities.GAS_HANDLER).resolve().orElse(null);
        if (handler == null) {
            return false;
        }
        boolean didTransfer = false;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            GasStack chemicalInItem = handler.getChemicalInTank(tank);
            if (chemicalInItem.isEmpty() || !gasTank.isValid(chemicalInItem)) {
                continue;
            }
            GasStack simulatedRemainder = gasTank.insert(chemicalInItem, Action.SIMULATE, AutomationType.INTERNAL);
            long amt = chemicalInItem.getAmount();
            long remainder = simulatedRemainder.getAmount();
            if (remainder < amt) {
                GasStack extracted = handler.extractChemical(tank, amt - remainder, Action.EXECUTE);
                if (!extracted.isEmpty()) {
                    gasTank.insert(extracted, Action.EXECUTE, AutomationType.INTERNAL);
                    didTransfer = true;
                    if (gasTank.getNeeded() == 0) {
                        break;
                    }
                }
            }
        }
        if (didTransfer) {
            setChanged();
            return true;
        }
        return false;
    }

    /**
     * 是否为可接受的营养液气体容器(拥有 IGasHandler 能力的物品)。
     */
    public static boolean isValidGasContainer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getCapability(Capabilities.GAS_HANDLER).isPresent();
    }

    /**
     * 运行时从 mekmm 注册表解析营养液气体。若未安装 mekmm 则返回 null。
     */
    public static Gas resolveNutrientGas() {
        try {
            Gas gas = MekanismAPI.gasRegistry().getValue(new ResourceLocation(NUTRIENT_GAS_NAMESPACE, NUTRIENT_GAS_ID));
            return gas == null || gas == MekanismAPI.EMPTY_GAS ? null : gas;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取营养液气体罐(供 GUI 与能力使用)。
     */
    public IGasTank getGasTank() {
        return gasTank;
    }

    private AutoIO autoIO;

    private void autoIO(Level level, BlockPos pos) {
        if (autoIO == null) {
            autoIO = new AutoIO(this,
                    new int[][]{{0, inputSlots}},
                    new int[][]{{inputSlots, inputSlots}});
        }
        if (autoIO.run(level, pos, sideConfig, items)) setChanged();
        // 气体自动输入输出（营养液抽取/弹出）
        if (gasAutoIO != null && gasAutoIO.run(level, pos, gasSideConfig)) setChanged();
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

    /** 气体侧面配置（营养液注入方向）。 */
    public void setGasSideMode(Direction direction, SideMode mode) {
        this.gasSideConfig[direction.ordinal()] = mode;
        setChanged();
    }

    public SideMode getGasSideMode(Direction direction) {
        return gasSideConfig[direction.ordinal()];
    }

    public void cycleSideMode(Direction direction) {
        SideMode current = sideConfig[direction.ordinal()];
        SideMode next = current.cycle(true, false);
        sideConfig[direction.ordinal()] = next;
        setChanged();
    }

    /**
     * Checks if all the given item stacks can fit in the output slots.
     */
    private boolean canFitAll(List<ItemStack> outputs) {
        int outputSlots = inputSlots;
        int outputStart = inputSlots;
        int outputEnd = 2 * inputSlots;
        if (outputEnd > items.getSlots()) {
            return false;
        }
        // 只跟踪「槽内物品引用 + 判定中的累计数量」，不拷贝任何 ItemStack（本方法在 tick 里按槽调用）
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

        for (ItemStack result : outputs) {
            int remaining = result.getCount();
            if (remaining <= 0) continue;
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

    /**
     * Inserts an item stack into the output slots.
     */
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

    /**
     * Distributes items across all input slots based on auto-distribute logic.
     * Collects items from ALL input slots, combines them (if same type), and redistributes.
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

    public static boolean isGasUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        ResourceLocation gas = ResourceLocation.tryParse("mekanism:upgrade_gas");
        ResourceLocation gasOld = ResourceLocation.tryParse("mekanism:gasupgrade");
        return id.equals(gas) || id.equals(gasOld);
    }

    /**
     * 营养液消耗倍率（由等级内置生效，阶梯 90% → 95% → 99% → 100%）：
     * - 烈焰炽焱 0.1（降低 90%，相当于白送一个气体升级）、晶钛矩阵 0.05（95%）、
     *   星云塑造 0.01（99%）、奇点创世 0.0（无需营养液）；
     * - 烈焰炽焱 之前的等级：装入 Mekanism 气体升级则降低 90%（倍率 0.1），否则满消耗 1.0。
     */
    public double getGasConsumptionMultiplier() {
        if (tier.ordinal() >= CuttingMachineFactoryTier.BLAZE.ordinal()) {
            return switch (tier) {
                case BLAZE -> 0.1;
                case CRYSTAL_MATRIX -> 0.05;
                case NEBULA -> 0.01;
                case SINGULARITY -> 0.0;
                default -> 0.0;
            };
        }
        if (gasUpgradeSlot >= 0 && !items.getStackInSlot(gasUpgradeSlot).isEmpty()) {
            return 0.1;
        }
        return 1.0;
    }

    /**
     * 是否为任意升级物品（速度/能量/堆叠/创造）。通用输入/存储槽禁止放入升级物品，
     * 只有对应的升级槽才能放入对应升级（见 isItemValid）。
     */
    public static boolean isAnyUpgradeItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        ResourceLocation speed = ResourceLocation.tryParse("mekanism:upgrade_speed");
        ResourceLocation energy = ResourceLocation.tryParse("mekanism:upgrade_energy");
        ResourceLocation stackUpgrade = ResourceLocation.tryParse("mekanism_extras:upgrade_stack");
        ResourceLocation creative = ResourceLocation.tryParse("mekanism_extras:upgrade_creative");
        ResourceLocation gas = ResourceLocation.tryParse("mekanism:upgrade_gas");
        ResourceLocation gasOld = ResourceLocation.tryParse("mekanism:gasupgrade");
        return id.equals(speed) || id.equals(energy) || id.equals(stackUpgrade)
                || id.equals(creative) || id.equals(gas) || id.equals(gasOld);
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
     * 当前红石模式下机器是否允许运行，与 Mekanism MekanismUtils.canFunction 相同。
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

    public int getSpeedUpgradeSlot() {
        return speedUpgradeSlot;
    }

    public int getEnergyUpgradeSlot() {
        return energyUpgradeSlot;
    }

    public int getStackUpgradeSlot() {
        return stackUpgradeSlot;
    }

    public int getGasUpgradeSlot() {
        return gasUpgradeSlot;
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

    public int getInputSlots() {
        return inputSlots;
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
                new ResourceLocation("mekck", "plantcut"));
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

    public int getNutrientSlot() {
        return nutrientSlot;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        if (customName != null) {
            return customName;
        }
        return Component.translatable("block.mekck." + tier.name + "_planting_cutting_factory");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PlantingCuttingFactoryMenu(containerId, inventory, this, data);
    }

    public void dropContents(Level level, BlockPos pos) {
        NonNullList<ItemStack> drops = NonNullList.create();
        for (int slot = 0; slot < items.getSlots(); slot++) {
            drops.add(items.getStackInSlot(slot));
        }
        cn.ism.mekck.util.BigStackDrops.dropAll(level, pos, drops); // 大堆叠安全：避免原版 64 分堆炸实体
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (orderRecipeId != null) {
            tag.putString("OrderRecipeId", orderRecipeId.toString());
            tag.putInt("OrderQuantity", orderQuantity);
            tag.putInt("OrderCompleted", orderCompleted);
        }
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        tag.put("SpeedUpgradeTracker", speedTracker.save());
        tag.put("EnergyUpgradeTracker", energyTracker.save());
        tag.put("StackUpgradeTracker", stackTracker.save());
        tag.put("CreativeUpgradeTracker", creativeTracker.save());
        cn.ism.mekck.util.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        tag.put("Items", items.serializeNBT());
        if (hasNutrient) {
            tag.put("GasTank", gasTank.serializeNBT());
        }
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Progress", progress);
        byte[] sideBytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            sideBytes[i] = (byte) sideConfig[i].ordinal();
        }
        tag.putByteArray("SideConfig", sideBytes);
        byte[] gasSideBytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            gasSideBytes[i] = (byte) gasSideConfig[i].ordinal();
        }
        tag.putByteArray("GasSideConfig", gasSideBytes);
        tag.putBoolean("AutoDistribute", autoDistribute);
        tag.putInt("RedstoneControl", redstoneControl.ordinal());
        tag.putBoolean("RedstonePowered", redstonePowered);
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("OrderRecipeId")) {
            orderRecipeId = new net.minecraft.resources.ResourceLocation(tag.getString("OrderRecipeId"));
            orderQuantity = tag.getInt("OrderQuantity");
            orderCompleted = tag.getInt("OrderCompleted");
        }
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        if (tag.contains("SpeedUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) speedTracker.load(tag.getCompound("SpeedUpgradeTracker"));
        if (tag.contains("EnergyUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) energyTracker.load(tag.getCompound("EnergyUpgradeTracker"));
        if (tag.contains("StackUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) stackTracker.load(tag.getCompound("StackUpgradeTracker"));
        if (tag.contains("CreativeUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) creativeTracker.load(tag.getCompound("CreativeUpgradeTracker"));
        cn.ism.mekck.util.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
        if (hasNutrient && tag.contains("GasTank", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            gasTank.deserializeNBT(tag.getCompound("GasTank"));
        }
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
        if (tag.contains("GasSideConfig", Tag.TAG_BYTE_ARRAY)) {
            byte[] gasSideBytes = tag.getByteArray("GasSideConfig");
            for (int i = 0; i < Math.min(gasSideBytes.length, 6); i++) {
                int ordinal = gasSideBytes[i];
                if (ordinal >= 0 && ordinal < SideMode.values().length) {
                    gasSideConfig[i] = SideMode.values()[ordinal];
                }
            }
        }
        if (tag.contains("AutoDistribute")) {
            autoDistribute = tag.getBoolean("AutoDistribute");
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
        if (capability == Capabilities.GAS_HANDLER) {
            // 气体侧配：只有标记为 PULL_INPUT 的面接受营养液注入
            if (side != null && gasSideConfig[side.ordinal()] != cn.ism.mekck.SideMode.PULL_INPUT) {
                return LazyOptional.empty();
            }
            return gasCapability.cast();
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

    // ── IBoundingBlock：把能力代理到上方绑定块面（1×2×1 多方块） ──────────────────
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
        return getCapability(capability, side);
    }

    // IComparatorSupport（IBoundingBlock 要求实现）
    @Override
    public int getRedstoneLevel() {
        return 0;
    }

    @Override
    public int getCurrentRedstoneLevel() {
        return 0;
    }

    // IUpgradeTile（IBoundingBlock 要求实现；本模组升级走自有升级槽，不接入 Mekanism 升级组件）
    @Override
    public boolean supportsUpgrades() {
        return false;
    }

    @Override
    public mekanism.common.tile.component.TileComponentUpgrade getComponent() {
        return null;
    }

    @Override
    public void recalculateUpgrades(mekanism.api.Upgrade upgradeType) {
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        fullItemCapability.invalidate();
        inputItemCapability.invalidate();
        outputItemCapability.invalidate();
        energyCapability.invalidate();
        gasCapability.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        fullItemCapability = LazyOptional.of(() -> items);
        inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        energyCapability = LazyOptional.of(() -> energy);
        gasCapability = hasNutrient ? LazyOptional.of(() -> gasHandler) : LazyOptional.empty();
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

    /**
     * 单气体罐处理器，委托给唯一的 gasTank。参考 mekmm:ultimate_planting_factory 的气体能力暴露方式。
     */
    private class GasHandlerSingle implements IGasHandler {

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public GasStack getChemicalInTank(int tank) {
            return gasTank.getStack();
        }

        @Override
        public void setChemicalInTank(int tank, GasStack stack) {
            gasTank.setStack(stack);
        }

        @Override
        public long getTankCapacity(int tank) {
            return gasTank.getCapacity();
        }

        @Override
        public boolean isValid(int tank, GasStack stack) {
            return gasTank.isValid(stack);
        }

        @Override
        public GasStack insertChemical(int tank, GasStack stack, Action action) {
            return gasTank.insert(stack, action, AutomationType.EXTERNAL);
        }

        @Override
        public GasStack extractChemical(int tank, long amount, Action action) {
            return gasTank.extract(amount, action, AutomationType.EXTERNAL);
        }

        @Override
        public GasStack getEmptyStack() {
            return GasStack.EMPTY;
        }
    }
}