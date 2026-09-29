package cn.ism.mekck.blockentity;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import net.minecraft.core.registries.BuiltInRegistries;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.block.CookingFactoryBlock;
import cn.ism.mekck.menu.CookingFactoryMenu;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.AE2Compat;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.FastTransfer;
import cn.ism.mekck.util.FluidContainerInteract;
import cn.ism.mekck.util.FluidIngredientHelper;
import cn.ism.mekck.util.KaleidoscopeCompat;
import cn.ism.mekck.util.LagMonitor;
import cn.ism.mekck.util.MultiFluidHandler;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.StorageMerger;
import cn.ism.mekck.util.UpgradeHelper;
import mekanism.client.sound.SoundHandler;
import mekanism.common.registries.MekanismSounds;
import net.minecraft.core.BlockPos;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IMekanismHeatHandler;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

public final class CookingFactoryBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, mekanism.api.heat.IMekanismHeatHandler , cn.ism.mekck.ae2.INetworkPullable {
    // ==================== IMekanismHeatHandler ====================
    // 让本方块实体本身实现 Mekanism 热能力接口，使第三方（如气动工艺的
    // MekanismIntegration.isMekHeatHandler 按 instanceof 判定）能识别本机为热处理器；
    // 带方向的重载全部委托给 MekCkHeatComponent，行为与 capability 暴露一致。

    @Override
    public List<IHeatCapacitor> getHeatCapacitors(@Nullable Direction side) {
        return heatComponent == null ? java.util.Collections.emptyList() : heatComponent.getHeatCapacitors(side);
    }

    @Override
    public void onContentsChanged() {
        // IContentsListener 要求的回调；内容变更通知由 MekCkHeatComponent 构造时传入的
        // onChanged（本机 setChanged）负责，此处无需额外处理。
    }

    public static final int INPUT_SLOTS = 6;
    public static final int OUTPUT_SLOTS = 9;
    public static final int RETURN_SLOTS = 3;
    public static final int FLUID_PER_BOTTLE = 250;
    public static final int BASE_ENERGY_PER_TICK = 20;
    public static final int BASE_PROCESS_TIME = 200;
    public static final int FLUID_CAPACITY = Integer.MAX_VALUE;
    /** 流体输入格的槽位数（支持同时存在水/奶/多种流体）。 */
    public static final int FLUID_TANK_COUNT = 3;
    public static final int DATA_CREATIVE_UPGRADE = 11;
    public static final int DATA_REDSTONE_CONTROL = 12;
    /** 机身温度（单位 0.01 ℃）。 */
    public static final int DATA_TEMPERATURE = 19;
    public static final int DATA_SIZE = 20;

    // ContainerData indices
    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_ENERGY_CAPACITY = 3;
    public static final int DATA_SIDE_CONFIG = 4;
    public static final int DATA_SPEED_UPGRADE = 5;
    public static final int DATA_ENERGY_UPGRADE = 6;
    public static final int DATA_STACK_UPGRADE = 7;
    public static final int DATA_FLUID = 8;
    public static final int DATA_ORDER_QUANTITY = 9;
    public static final int DATA_ORDER_COMPLETED = 10;
    public static final int DATA_FLUID_AMOUNT0 = 13;
    public static final int DATA_FLUID_AMOUNT1 = 14;
    public static final int DATA_FLUID_AMOUNT2 = 15;
    public static final int DATA_FLUID_TYPE0 = 16;
    public static final int DATA_FLUID_TYPE1 = 17;
    public static final int DATA_FLUID_TYPE2 = 18;

    /** 材料存储空间格数（12 列 × 12 行）。 */
    public static final int STORAGE_SLOTS = 144;
    // 固定槽位索引（供 tooltip 等外部读取，与构造器中的实例布局一致）
    public static final int OUTPUT_SLOT_START = INPUT_SLOTS + STORAGE_SLOTS;
    public static final int RETURN_SLOT_START = OUTPUT_SLOT_START + OUTPUT_SLOTS;
    public static final int SPEED_UPGRADE_SLOT = RETURN_SLOT_START + RETURN_SLOTS;
    public static final int ENERGY_UPGRADE_SLOT = SPEED_UPGRADE_SLOT + 1;
    public static final int STACK_UPGRADE_SLOT = ENERGY_UPGRADE_SLOT + 1;

    private final CuttingMachineFactoryTier tier;
    private final int storageSlots;
    private int totalSlots;
    private final int outputSlotStart;
    private final int returnSlotStart;
    private final int speedUpgradeSlot;
    private final int energyUpgradeSlot;
    private final int stackUpgradeSlot;
    private final int creativeUpgradeSlot;
    private final int powerSlot;
    private final boolean hasStackUpgrade;

    // ================== 升级读条（Mekanism 式：20 tick 安装） ==================
    private final cn.ism.mekck.util.MekCkUpgradeTracker speedTracker;
    private final cn.ism.mekck.util.MekCkUpgradeTracker energyTracker;
    private final cn.ism.mekck.util.MekCkUpgradeTracker stackTracker;
    private final cn.ism.mekck.util.MekCkUpgradeTracker creativeTracker =
            new cn.ism.mekck.util.MekCkUpgradeTracker(1);
    private Component customName;
    private int progress = 0;

    // ================== 温度系统（Mekanism 热容量；运行产热，不影响运行条件） ==================
    /** 热容量（J/K）：与 Mekanism 电阻型加热器一致。 */
    public static final double HEAT_CAPACITY = 100.0;
    /** 热传导系数：与电阻型加热器一致。 */
    private static final double HEAT_INVERSE_CONDUCTION = 5.0;
    /** 热绝缘系数：与电阻型加热器一致。 */
    private static final double HEAT_INVERSE_INSULATION = 100.0;
    /** 电能→热量转换效率：与 Mekanism 电阻型加热器完全相同（1 FE → 0.6 J 热量）。 */
    public static final double HEAT_EFFICIENCY = 0.6;
    /** 每 tick 向环境回归的热量比例（无外部热传导时的自然散热）。 */
    private static final double AMBIENT_LOSS_RATE = 0.01;
    private cn.ism.mekck.util.MekCkHeatComponent heatComponent;
    private final net.minecraftforge.common.util.LazyOptional<mekanism.api.heat.IHeatHandler> heatCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> heatComponent.getHandler());
    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    // PULSE 模式：收到红石信号(上升沿)后锁存为 true，完成一次完整处理后复位。
    private boolean pulseRunning = false;

    public final ItemStackHandler items;
    private final MultiFluidHandler fluidTank;
    private final EnergyStorage energy;

    private final SideMode[] sideConfig = new SideMode[6];

    // Order system
    private ResourceLocation orderRecipeId;
    private int orderQuantity; // total quantity to produce
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true; // how many have been produced so far

    private LazyOptional<IItemHandler> fullItemCapability;
    private LazyOptional<IItemHandler> inputItemCapability;
    private LazyOptional<IItemHandler> storageItemCapability;
    private LazyOptional<IItemHandler> outputItemCapability;
    private LazyOptional<IEnergyStorage> energyCapability;
    private LazyOptional<IFluidHandler> fluidCapability;

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
                case DATA_FLUID -> fluidTank.totalFluidAmount();
                case DATA_ORDER_QUANTITY -> orderQuantity;
                case DATA_ORDER_COMPLETED -> orderCompleted;
                case DATA_FLUID_AMOUNT0 -> fluidAmount(0);
                case DATA_FLUID_AMOUNT1 -> fluidAmount(1);
                case DATA_FLUID_AMOUNT2 -> fluidAmount(2);
                case DATA_FLUID_TYPE0 -> fluidTypeId(0);
                case DATA_FLUID_TYPE1 -> fluidTypeId(1);
                case DATA_FLUID_TYPE2 -> fluidTypeId(2);
                case DATA_CREATIVE_UPGRADE -> hasCreativeUpgrade() ? 1 : 0;
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
                case DATA_TEMPERATURE -> (int) Math.round((getTemperature() - 273.15) * 100.0);
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

    /** 运行时按消耗电能产热（与 Mekanism 电阻型加热器比例完全相同：1 FE → 0.6 J 热量）。 */
    public void addHeatFromEnergy(int energyUsed) {
        if (heatComponent == null || energyUsed <= 0) return;
        heatComponent.addHeatFromEnergy(energyUsed);
    }

    /** 每 tick 的自然散热与环境回归，并应用累积热量（由 serverTick 每 tick 调用，空闲时也会散热）。 */
    public void tickHeat() {
        if (heatComponent == null) return;
        heatComponent.tick(getLevel(), getBlockPos());
    }

    /** 当前机身温度（开尔文）。 */
    public double getTemperature() {
        return heatComponent == null ? mekanism.api.heat.HeatAPI.AMBIENT_TEMP : heatComponent.getTemperature();
    }

    public CookingFactoryBlockEntity(CuttingMachineFactoryTier tier, BlockPos pos, BlockState state) {
        super(getTileType(tier), pos, state);
        this.tier = tier;
                // 上限惰性读取 MekckConfig，故 /reload 改配置后立即生效。
                // 必须在构造器体内初始化：tier 在此处才保证已赋值，字段初始化器里无法引用。
                this.speedTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactorySpeedUpgradeMax(tier));
                this.energyTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryEnergyUpgradeMax(tier));
                this.stackTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryStackUpgradeMax(tier));
        // 温度系统：Mekanism 热容量（环境温度取自 Mekanism HeatAPI，随生物群系变化）
        this.heatComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
        // 材料存储空间：12 列 × 12 行 = 144 格
        this.storageSlots = STORAGE_SLOTS;
        this.hasStackUpgrade = tier.supportsStackUpgrade();

        // Slot layout:
        // 0-5: input slots
        // 6 to 6+storageSlots-1: storage slots (144 slots: 6-149)
        // outputSlotStart: 6 + storageSlots (87) to 95 (9 output slots)
        // returnSlotStart: 96 to 98 (3 return slots)
        // speedUpgradeSlot: 99
        // energyUpgradeSlot: 100
        // stackUpgradeSlot: 101 (only if hasStackUpgrade)
        // creativeUpgradeSlot: last slot
        this.outputSlotStart = INPUT_SLOTS + storageSlots;
        this.returnSlotStart = outputSlotStart + OUTPUT_SLOTS;
        this.speedUpgradeSlot = returnSlotStart + RETURN_SLOTS;
        this.energyUpgradeSlot = speedUpgradeSlot + 1;
        this.stackUpgradeSlot = hasStackUpgrade ? energyUpgradeSlot + 1 : -1;
        this.creativeUpgradeSlot = hasStackUpgrade ? stackUpgradeSlot + 1 : energyUpgradeSlot + 1;
        this.totalSlots = INPUT_SLOTS + storageSlots + OUTPUT_SLOTS + RETURN_SLOTS + (hasStackUpgrade ? 4 : 3);
        // 能源槽位（能量物品），追加在末尾
        this.powerSlot = this.totalSlots;
        this.totalSlots = this.totalSlots + 1;

        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
        }

        // Fluid input slots: 3 个独立流体槽，各槽独立容量，可同时装水/奶/其它流体
        this.fluidTank = new MultiFluidHandler(FLUID_TANK_COUNT, FLUID_CAPACITY, this::setChanged);

        this.items = new cn.ism.mekck.util.BigStackItemHandler(totalSlots) {
            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                if (slot < INPUT_SLOTS) {
                    // 输入槽只接受普通食材，不允许放入任何升级物品
                    return !isAnyUpgradeItem(stack) && (tier == CuttingMachineFactoryTier.SINGULARITY
                    ? RecipeInputMatcher.matchesCooking(level, stack) || RecipeInputMatcher.matchesExtremeCooking(level, stack)
                            || RecipeInputMatcher.matchesPotCooking(level, stack)
                    : (RecipeInputMatcher.matchesCooking(level, stack) || RecipeInputMatcher.matchesPotCooking(level, stack)));
                }
                if (slot >= INPUT_SLOTS && slot < outputSlotStart) {
                    // 存储槽只接受普通物品，不允许放入任何升级物品
                    return !isAnyUpgradeItem(stack);
                }
                // Output and return slots: cannot insert
                if (slot >= outputSlotStart && slot < speedUpgradeSlot) {
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
                // Input, storage, output, and return slots: unlimited
                if (slot < speedUpgradeSlot) {
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
                // Speed/energy upgrade slots: config-controlled
                if (slot == speedUpgradeSlot) {
                    return MekckConfig.getFactorySpeedUpgradeMax(tier);
                }
                if (slot == energyUpgradeSlot) {
                    return MekckConfig.getFactoryEnergyUpgradeMax(tier);
                }
                // Power slot: max 64
                if (slot == powerSlot) {
                    return 64;
                }
                return 0;
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (stack.isEmpty()) return stack;
                if (!isItemValid(slot, stack)) return stack;
                if (slot < speedUpgradeSlot) {
                    ItemStack current = getStackInSlot(slot);
                    int limit = getStackLimit(slot, stack);
                    if (current.isEmpty()) {
                        int toInsert = Math.min(limit, stack.getCount());
                        if (!simulate) {
                            setStackInSlot(slot, stack.copyWithCount(toInsert));
                        }
                        if (toInsert >= stack.getCount()) return ItemStack.EMPTY;
                        return stack.copyWithCount(stack.getCount() - toInsert);
                    }
                    if (!ItemStack.isSameItemSameTags(current, stack)) return stack;
                    int maxInsert = limit - current.getCount();
                    if (maxInsert <= 0) return stack;
                    int toInsert = Math.min(maxInsert, stack.getCount());
                    if (!simulate) {
                        current.grow(toInsert);
                        setStackInSlot(slot, current);
                    }
                    if (toInsert >= stack.getCount()) return ItemStack.EMPTY;
                    return stack.copyWithCount(stack.getCount() - toInsert);
                }
                return super.insertItem(slot, stack, simulate);
            }

            @Override
            protected int getStackLimit(int slot, ItemStack stack) {
                // Input, storage, output, and return slots: use getSlotLimit (Integer.MAX_VALUE)
                if (slot < speedUpgradeSlot) {
                    return getSlotLimit(slot);
                }
                // Upgrade slots: use default behavior
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
        this.energy = new EnergyStorage(tier.energyCapacity, tier.processes * 1000, tier.processes * BASE_ENERGY_PER_TICK) {
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

        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.storageItemCapability = LazyOptional.of(() -> new StorageItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
        this.fluidCapability = LazyOptional.of(() -> fluidTank);
    }

    public boolean hasStackUpgradeSlot() {
        return hasStackUpgrade;
    }

    public int addUpgradesFromHand(ItemStack held) {
        boolean hasStack = hasStackUpgradeSlot();
        return UpgradeHelper.install(getItems(), getSpeedUpgradeSlot(), MekckConfig.getFactorySpeedUpgradeMax(getTier()),
                getEnergyUpgradeSlot(), MekckConfig.getFactoryEnergyUpgradeMax(getTier()),
                hasStack ? getStackUpgradeSlot() : -1,
                hasStack ? MekckConfig.getFactoryStackUpgradeMax(getTier()) : 0,
                creativeUpgradeSlot,
                held);
    }

    private static BlockEntityType<CookingFactoryBlockEntity> getTileType(CuttingMachineFactoryTier tier) {
        // 直接查注册表，不再逐个 case 列等级。
        // 原先的 switch 只列了 11 个等级、**漏了 BLAZE** ⇒ 放置烈焰等级工厂时落到
        // default 抛 IllegalArgumentException 崩溃（2026-09-16 用户实测：放置烈焰切菜机必崩）。
        // 改为查表后，将来新增等级无需再改这里（制冰工厂一直是这么做的）。
        return cn.ism.mekck.UniversalCuttingMachine.COOKING_FACTORY_BLOCK_ENTITIES.get(tier).get();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CookingFactoryBlockEntity machine) {
        // 升级读条：槽位放入升级后 20 tick 安装一次
        machine.tickUpgradeTrackers();
        // 温度系统：每 tick 自然散热并应用热量（与是否运行无关）
        machine.tickHeat();
        // Safety check
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

        // Cap energy to effective capacity
        int effectiveCapacity = machine.energy.getMaxEnergyStored();
        int currentEnergy = machine.energy.getEnergyStored();
        if (currentEnergy > effectiveCapacity) {
            machine.energy.extractEnergy(currentEnergy - effectiveCapacity, false);
            changed = true;
        }

        boolean hasCreative = machine.hasCreativeUpgrade();
        if (hasCreative) {
            machine.energy.receiveEnergy(machine.energy.getMaxEnergyStored() - machine.energy.getEnergyStored(), false);
        }

        int stackMult = machine.getStackMultiplier();
        double speedMult = machine.getEffectiveSpeedMultiplier();
        double energyConsumptionMult = machine.getEffectiveEnergyConsumptionMultiplier();
        int effectiveProcessTime = hasCreative ? 1 : Math.max(1, (int) (BASE_PROCESS_TIME / speedMult));
        int baseEnergyPerTick = hasCreative ? 0 : (int) Math.ceil(BASE_ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);
        if (machine.getTier().energyPerTick == 0) {
            baseEnergyPerTick = 0;
        }

        // Check if we have a valid recipe and can process
        Optional<Recipe<?>> recipeOpt = machine.findRecipe();
        boolean canProcess = false;
        int maxConsumable = 0;

        if (recipeOpt.isPresent()) {
            Recipe<?> recipe = recipeOpt.get();
            maxConsumable = machine.getMaxConsumableCount(recipe);
            if (maxConsumable > 0) {
                int multiplier = Math.min(maxConsumable, stackMult);
                if (machine.orderQuantity > 0) {
                    multiplier = Math.min(multiplier, machine.orderQuantity - machine.orderCompleted);
                }
                ItemStack result = recipe.getResultItem(machine.level.registryAccess());
                // canFitAll no longer checks return slots. Use EMPTY to make explicit we
                // no longer try to return the consumed container here (it's consumed upfront).
                if (machine.canFitAll(result, ItemStack.EMPTY, multiplier)) {
                    canProcess = true;
                }
            }
        }

        // Check order requirements: if we have an order, verify we haven't completed it yet
        if (canProcess && machine.orderQuantity > 0 && machine.orderCompleted >= machine.orderQuantity) {
            // Order completed, stop processing
            canProcess = false;
        }

        int energyPerTick = canProcess ? cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, baseEnergyPerTick, stackMult) : 0;

        // PULSE 模式：收到红石信号(上升沿)时锁存，机器开始一次完整的处理
        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        boolean canOperate = machine.canFunctionRedstone();
        if (canOperate && canProcess && machine.energy.getEnergyStored() >= energyPerTick) {
            machine.energy.extractEnergy(energyPerTick, false);
            // 温度系统：消耗的电能按电阻型加热器效率转为热量（运行条件与温度无关）
            machine.addHeatFromEnergy(energyPerTick);
            machine.progress++;

            if (machine.progress >= effectiveProcessTime) {
                Recipe<?> recipe = recipeOpt.get();
                int multiplier = Math.min(maxConsumable, stackMult);
                if (machine.orderQuantity > 0) {
                    multiplier = Math.min(multiplier, machine.orderQuantity - machine.orderCompleted);
                }
                machine.consumeFluidForRecipe(recipe, multiplier);
                machine.consumeIngredients(recipe, multiplier);
                machine.completeRecipe(recipe, multiplier);

                // Update order tracking
                if (machine.orderQuantity > 0) {
                    machine.orderCompleted += multiplier;
                    if (machine.orderCompleted >= machine.orderQuantity) {
                        machine.orderQuantity = 0;
                        machine.orderCompleted = 0;
                        machine.orderRecipeId = null;
                    }
                }

                machine.progress = 0;
                // PULSE 模式：本次完整处理结束，停止并等待下一次红石信号
                if (machine.redstoneControl == RedstoneControl.PULSE) {
                    machine.pulseRunning = false;
                }
                changed = true;
            }
            changed = true;
        } else {
            // PULSE 模式：本 tick 无法运行则解除锁存，等待下一次红石信号重新触发
            if (machine.redstoneControl == RedstoneControl.PULSE && machine.pulseRunning) {
                machine.pulseRunning = false;
                changed = true;
            }
            if (machine.progress != 0) {
                machine.progress = 0;
                changed = true;
            }
        }

        if (changed) {
            machine.setChanged();
        }
        if (level.getGameTime() % 20 == 0) machine.convertStoredFluidContainers();
        if (LagMonitor.shouldRunIO(level.getGameTime(), pos)) machine.autoIO(level, pos);
        // 存储空间自动合并：相同物品合并至靠前的格子
        if (StorageMerger.shouldRunMerge(level.getGameTime(), pos, 20)) {
            if (StorageMerger.merge(machine.items, INPUT_SLOTS, machine.outputSlotStart - INPUT_SLOTS)) {
                machine.setChanged();
            }
        }

        // AE2 联动：网格节点生命周期 / 动态配方刷新 / 任务回写（未安装 AE2 时为空操作）
        AE2Compat.serverTick(machine, level, pos);

        // Update block state active property
        boolean isActive = machine.progress > 0;
        if (wasActive != isActive) {
            level.setBlock(pos, state.setValue(CookingFactoryBlock.ACTIVE, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, CookingFactoryBlockEntity machine) {
        if (state.getValue(CookingFactoryBlock.ACTIVE)) {
            SoundHandler.startTileSound(MekanismSounds.ENRICHMENT_CHAMBER.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
        } else {
            SoundHandler.stopTileSound(pos);
        }
    }

    /**
     * Finds a matching cooking recipe from storage slots + input slots.
     * First checks farmersdelight cooking recipes, then avaritia_delight extreme_cooking recipes.
     * Uses RecipeMatcher.findMatches for unordered ingredient matching.
     * Also checks fluid requirements (water bottle ingredients consume 250mb of water/milk).
     */
    // ── 配方查找短路缓存 ──
    // 烹饪工厂的 findRecipe 每次都要把所有输入/存储槽摊平成列表再逐条试配方；
    // 输入没变、也没在下单时，直接复用上一次的结果。
    private long recipeCacheKey = Long.MIN_VALUE;
    private Object recipeCacheManager;
    private Recipe<?> recipeCacheValue;

    /** 输入槽 + 存储槽的物品指纹（物品注册名 + 数量 + NBT）。 */
    private long storageFingerprint() {
        long h = 1125899906842597L;
        int end = Math.min(outputSlotStart, items.getSlots());
        for (int i = 0; i < end; i++) {
            ItemStack st = items.getStackInSlot(i);
            long itemHash = 0L;
            if (!st.isEmpty()) {
                net.minecraft.resources.ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
                itemHash = (id == null ? 0 : id.hashCode()) * 31L
                        + (st.getTag() == null ? 0 : st.getTag().hashCode());
            }
            h = h * 31L + itemHash;
            h = h * 31L + st.getCount();
        }
        return h;
    }

    private Optional<Recipe<?>> findRecipe() {
        Object manager = level.getRecipeManager();
        long key = storageFingerprint();
        if (orderRecipeId == null && manager == recipeCacheManager && key == recipeCacheKey) {
            return Optional.ofNullable(recipeCacheValue);
        }
        Optional<Recipe<?>> found = findRecipeUncached();
        if (orderRecipeId == null) {
            recipeCacheManager = manager;
            recipeCacheKey = key;
            recipeCacheValue = found.orElse(null);
        }
        return found;
    }

    private Optional<Recipe<?>> findRecipeUncached() {
        List<ItemStack> availableItems = new ArrayList<>();
        // Collect items from input slots first
        for (int j = 0; j < INPUT_SLOTS; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) {
                availableItems.add(stack);
            }
        }
        // Then from storage slots
        for (int j = INPUT_SLOTS; j < outputSlotStart; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) {
                availableItems.add(stack);
            }
        }
        if (availableItems.isEmpty()) {
            return Optional.empty();
        }

        // If an order is active, only check the ordered recipe
        if (orderRecipeId != null) {
            Recipe<?> orderedRecipe = findRecipeById(orderRecipeId);
            if (orderedRecipe != null && canMatchRecipe(orderedRecipe, availableItems)) {
                return Optional.of(orderedRecipe);
            }
            return Optional.empty();
        }

        // No order set - do not auto-process
        return Optional.empty();
    }

    /**
     * Fluid requirement per single unit of {@code recipe}. Delegates to
     * {@link FluidIngredientHelper#sumFluids(Iterable)} so water bottle (250mb),
     * water bucket (1000mb), milk bottle (#forge:milk / 250mb) and milk bucket
     * (1000mb) are all correctly distinguished. Works for all recipe classes
     * supported by the factory (FD CookingPotRecipe, Avaritia extreme cooking,
     * Kaleidoscope stockpot/pot/flex variants).
     */
    private FluidIngredientHelper.FluidInfo getFluidPerUnit(Recipe<?> recipe) {
        return FluidIngredientHelper.sumFluids(recipe.getIngredients());
    }

    /**
     * Checks whether the single-tank FluidTank holds enough of the correct
     * fluids to run {@code multiplier} units of {@code recipe}.
     *
     * <p>Because the tank validator accepts any fluid, it can only hold one
     * carrier at a time. We therefore enforce type matching:
     * <ul>
     *   <li>When WATER is required → some tank must be WATER with enough mb.</li>
     *   <li>When MILK is required → some tank must be a non-WATER fluid with enough mb.</li>
     * </ul>
     * 3 个独立流体槽允许同一次配方同时需要水和奶。
     */
    private boolean hasRequiredFluid(Recipe<?> recipe, int multiplier) {
        FluidIngredientHelper.FluidInfo need = getFluidPerUnit(recipe);
        if (need.isEmpty() || multiplier <= 0) return true;

        if (need.waterMb > 0) {
            if (!fluidTank.hasEnoughOf(true, cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, need.waterMb, multiplier))) return false;
        }
        if (need.milkMb > 0) {
            if (!fluidTank.hasEnoughOf(false, cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, need.milkMb, multiplier))) return false;
        }
        return true;
    }

    private boolean hasRequiredFluid(Recipe<?> recipe) {
        return hasRequiredFluid(recipe, 1);
    }

    /**
     * Drains the exact water/milk requirement for {@code multiplier} units of
     * {@code recipe}. Mirror of {@link #hasRequiredFluid(Recipe, int)}; callers
     * MUST call hasRequiredFluid first.
     */
    private void consumeFluidForRecipe(Recipe<?> recipe, int multiplier) {
        FluidIngredientHelper.FluidInfo need = getFluidPerUnit(recipe);
        if (need.isEmpty() || multiplier <= 0) return;
        if (need.waterMb > 0) {
            fluidTank.drainOf(true, cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, need.waterMb, multiplier));
        }
        if (need.milkMb > 0) {
            // The tank is asserted non-WATER by hasRequiredFluid. Drain the
            // exact amount regardless of the concrete milk fluid registry id.
            fluidTank.drainOf(false, cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, need.milkMb, multiplier));
        }
    }

    /**
     * Checks if the given recipe can be crafted with the available items.
     * Uses unified recipe metadata to support FD CookingPotRecipe, Avaritia extreme,
     * and Kaleidoscope stockpot/pot/flex variants.
     */
    private boolean canMatchRecipe(Recipe<?> recipe, List<ItemStack> availableItems) {
        List<Ingredient> solidIngredients = getSolidIngredients(recipe);
        if (solidIngredients.isEmpty() && recipe.getIngredients().isEmpty()) return false;

        List<Ingredient> extraConsumables = getExtraConsumables(recipe);
        ItemStack consumedContainer = getConsumedContainer(recipe);

        int totalIngredientCount = solidIngredients.size() + extraConsumables.size();
        if (!consumedContainer.isEmpty()) totalIngredientCount += 1;

        if (availableItems.size() < totalIngredientCount) return false;

        // Fluid requirements (exact bucket/bottle size classification via helper).
        if (!hasRequiredFluid(recipe)) return false;

        // Build combined list: solid ingredients + extra consumables + consumed container
        List<Ingredient> allToMatch = new ArrayList<>(solidIngredients.size() + extraConsumables.size() + 1);
        allToMatch.addAll(solidIngredients);
        allToMatch.addAll(extraConsumables);
        if (!consumedContainer.isEmpty()) {
            allToMatch.add(Ingredient.of(consumedContainer.getItem()));
        }
        if (allToMatch.isEmpty()) {
            return false;
        }

        return canMatchItems(availableItems, allToMatch);
    }

    // -----------------------------
    // Unified recipe metadata helpers (shared with Kaleidoscope compat)
    // -----------------------------
    public static List<Ingredient> getSolidIngredients(Recipe<?> recipe) {
        List<Ingredient> list = new ArrayList<>();
        if (KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)) {
            list.addAll(KaleidoscopeCompat.getSolidIngredients(recipe));
        } else {
            for (Ingredient ing : recipe.getIngredients()) {
                // 过滤空材料（九宫格 shaped 配方的空格），否则回溯匹配必然失败
                if (!ing.isEmpty() && !isWaterBottleIngredient(ing) && !isMilkBottleIngredient(ing)) {
                    list.add(ing);
                }
            }
        }
        return list;
    }

    public static List<Ingredient> getExtraConsumables(Recipe<?> recipe) {
        if (KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)) {
            return KaleidoscopeCompat.getExtraConsumables(recipe);
        }
        return new ArrayList<>();
    }

    /**
     * Returns the item stack that must be CONSUMED from storage when running this recipe
     * (bowl / glass bottle / etc.). This is the "output container" of CookingPotRecipe,
     * the "carrier" of Kaleidoscope recipes, or empty for avaritia extreme recipes that
     * do not define a container. For Kaleidoscope recipes the carrier is already included
     * in getExtraConsumables(), so for those recipes we intentionally return EMPTY here.
     */
    public static ItemStack getConsumedContainer(Recipe<?> recipe) {
        if (KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)) {
            return ItemStack.EMPTY;
        }
        if (recipe instanceof vectorwing.farmersdelight.common.crafting.CookingPotRecipe cooking) {
            return cooking.getOutputContainer();
        }
        // 方法句柄由 Reflect 缓存（原先每次调用都重新解析签名）
        Object container = cn.ism.mekck.util.Reflect.call(recipe, "getOutputContainer");
        return container instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    /**
     * Custom matching algorithm that handles extra items in the input list.
     * Unlike RecipeMatcher.findMatches, this does NOT require inputs.size() == ingredients.size().
     */
    private static boolean canMatchItems(List<ItemStack> inputs, List<Ingredient> ingredients) {
        List<List<Integer>> matches = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            List<Integer> matching = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                if (ing.test(inputs.get(i))) {
                    matching.add(i);
                }
            }
            if (matching.isEmpty()) {
                return false;
            }
            matches.add(matching);
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> matches.get(i).size()));

        boolean[] used = new boolean[inputs.size()];
        return backtrackMatch(matches, order, 0, used);
    }

    private static boolean backtrackMatch(List<List<Integer>> matches, List<Integer> order, int depth, boolean[] used) {
        if (depth >= order.size()) {
            return true;
        }
        int ingredientIdx = order.get(depth);
        for (int inputIdx : matches.get(ingredientIdx)) {
            if (!used[inputIdx]) {
                used[inputIdx] = true;
                if (backtrackMatch(matches, order, depth + 1, used)) {
                    return true;
                }
                used[inputIdx] = false;
            }
        }
        return false;
    }

    /**
     * Custom matching algorithm that returns the matching array.
     * Unlike RecipeMatcher.findMatches, this does NOT require inputs.size() == ingredients.size().
     * Returns an array mapping each ingredient index to the input index that matches it,
     * or null if no matching is found.
     */
    @Nullable
    private static int[] findCustomMatches(List<ItemStack> inputs, List<Ingredient> ingredients) {
        List<List<Integer>> matches = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            List<Integer> matching = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                if (ing.test(inputs.get(i))) {
                    matching.add(i);
                }
            }
            if (matching.isEmpty()) {
                return null;
            }
            matches.add(matching);
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> matches.get(i).size()));

        int[] result = new int[ingredients.size()];
        boolean[] used = new boolean[inputs.size()];
        if (backtrackCustomMatches(matches, order, 0, used, result)) {
            return result;
        }
        return null;
    }

    private static boolean backtrackCustomMatches(List<List<Integer>> matches, List<Integer> order, int depth, boolean[] used, int[] result) {
        if (depth >= order.size()) {
            return true;
        }
        int ingredientIdx = order.get(depth);
        for (int inputIdx : matches.get(ingredientIdx)) {
            if (!used[inputIdx]) {
                used[inputIdx] = true;
                result[ingredientIdx] = inputIdx;
                if (backtrackCustomMatches(matches, order, depth + 1, used, result)) {
                    return true;
                }
                used[inputIdx] = false;
            }
        }
        return false;
    }

    /**
     * Finds a specific recipe by its ID, checking farmersdelight cooking,
     * avaritia_delight extreme_cooking recipes, and Kaleidoscope stockpot/pot/flex.
     */
    @Nullable
    private Recipe<?> findRecipeById(ResourceLocation recipeId) {
        for (CookingPotRecipe recipe : (java.util.List<CookingPotRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, ModRecipeTypes.COOKING.get())) {
            if (recipe.getId().equals(recipeId)) {
                return recipe;
            }
        }
        // 沉浸农艺 pot_cooking
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeInputMatcher.getPotCookingRecipes(level)) {
            if (recipe.getId().equals(recipeId)) {
                return recipe;
            }
        }
        // 终焉烹饪仅奇点创世等级支持
        if (tier == CuttingMachineFactoryTier.SINGULARITY) {
            RecipeType<?> extremeShapedType = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("avaritia_delight", "extreme_cooking_shaped"));
            if (extremeShapedType != null) {
                for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, extremeShapedType)) {
                    if (recipe.getId().equals(recipeId)) {
                        return recipe;
                    }
                }
            }
            RecipeType<?> extremeShapelessType = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("avaritia_delight", "extreme_cooking_shapeless"));
            if (extremeShapelessType != null) {
                for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, extremeShapelessType)) {
                    if (recipe.getId().equals(recipeId)) {
                        return recipe;
                    }
                }
            }
        }
        // Kaleidoscope cookery: stockpot / pot / flex variants
        if (KaleidoscopeCompat.isLoaded()) {
            for (Recipe<?> recipe : KaleidoscopeCompat.getAllKaleidoscopeRecipes(level)) {
                if (recipe.getId().equals(recipeId)) {
                    return recipe;
                }
            }
        }
        return null;
    }

    /**
     * 配方中需要以"流体容器"形式提供的原材料（水瓶/奶瓶等）。
     * 这些物品会被 {@link #convertStoredFluidContainers()} 转化为流体槽，
     * 因此 AE2 加工样板必须把它们也计入输入。
     */
    public static List<Ingredient> getFluidBottleIngredients(Recipe<?> recipe) {
        List<Ingredient> list = new ArrayList<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (isWaterBottleIngredient(ing) || isMilkBottleIngredient(ing)) {
                list.add(ing);
            }
        }
        return list;
    }

    private static boolean isWaterBottleIngredient(Ingredient ingredient) {
        ItemStack[] stacks = ingredient.getItems();
        for (ItemStack stack : stacks) {
            if (stack.is(Items.POTION) || stack.is(Items.WATER_BUCKET)) {
                CompoundTag tag = stack.getTag();
                if (tag != null && "minecraft:water".equals(tag.getString("Potion"))) {
                    return true;
                }
                if (stack.is(Items.WATER_BUCKET)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isMilkBottleIngredient(Ingredient ingredient) {
        ItemStack[] stacks = ingredient.getItems();
        for (ItemStack stack : stacks) {
            if (stack.is(Items.MILK_BUCKET)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the maximum number of recipe sets that can be consumed.
     * Groups items by type and counts ingredient demand per item type,
     * correctly handling the case where multiple ingredients match the same item.
     * Includes solid ingredients, extra consumables (carrier / pot oil) and
     * consumed output container.
     */
    private int getMaxConsumableCount(Recipe<?> recipe) {
        // Collect items from input slots first, then storage
        List<ItemStack> availableItems = new ArrayList<>();
        for (int j = 0; j < INPUT_SLOTS; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) {
                availableItems.add(stack);
            }
        }
        for (int j = INPUT_SLOTS; j < outputSlotStart; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) {
                availableItems.add(stack);
            }
        }

        List<Ingredient> solidIngredients = getSolidIngredients(recipe);
        List<Ingredient> extraConsumables = getExtraConsumables(recipe);
        ItemStack consumedContainer = getConsumedContainer(recipe);

        List<Ingredient> allConsumables = new ArrayList<>(solidIngredients.size() + extraConsumables.size() + 1);
        allConsumables.addAll(solidIngredients);
        allConsumables.addAll(extraConsumables);
        if (!consumedContainer.isEmpty()) {
            allConsumables.add(Ingredient.of(consumedContainer.getItem()));
        }

        if (availableItems.isEmpty() || allConsumables.isEmpty()) return 0;

        // Group available items by item type and sum their counts
        Map<Item, Integer> itemCounts = new HashMap<>();
        for (ItemStack stack : availableItems) {
            itemCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }

        // Count how many ingredients match each item type
        Map<Item, Integer> ingredientDemand = new HashMap<>();
        for (Ingredient ing : allConsumables) {
            Set<Item> matchedItems = new HashSet<>();
            for (ItemStack stack : availableItems) {
                if (ing.test(stack)) {
                    matchedItems.add(stack.getItem());
                }
            }
            for (Item item : matchedItems) {
                ingredientDemand.merge(item, 1, Integer::sum);
            }
        }

        // Calculate max sets for each item type: totalCount / demand
        int minCount = Integer.MAX_VALUE;
        for (Map.Entry<Item, Integer> entry : itemCounts.entrySet()) {
            Item item = entry.getKey();
            int totalCount = entry.getValue();
            int demand = ingredientDemand.getOrDefault(item, 0);
            if (demand > 0) {
                minCount = Math.min(minCount, totalCount / demand);
            }
        }

        // Apply fluid-based caps using exact per-unit mb classification.
        // 3 个独立流体槽允许水和奶各自的可用量分别计算生产份数。
        FluidIngredientHelper.FluidInfo fluid = getFluidPerUnit(recipe);
        if (!fluid.isEmpty()) {
            if (!hasRequiredFluid(recipe, 1)) return 0;
            if (fluid.waterMb > 0) minCount = Math.min(minCount, fluidTank.totalOf(true) / fluid.waterMb);
            if (fluid.milkMb  > 0) minCount = Math.min(minCount, fluidTank.totalOf(false) / fluid.milkMb);
        }

        return minCount == Integer.MAX_VALUE ? 0 : minCount;
    }

    /**
     * Checks if the output slots can fit the recipe results.
     * Return slots are no longer used (containers are now consumed from inputs,
     * not returned after crafting). The second {@code container} parameter is
     * kept for backward compatibility with call sites but is intentionally unused.
     */
    @SuppressWarnings("unused")
    private boolean canFitAll(ItemStack result, ItemStack container, int multiplier) {
        // Check output slots (9 slots)
        if (!result.isEmpty()) {
            long totalCountLong = (long) result.getCount() * multiplier;
            if (totalCountLong > Integer.MAX_VALUE) return false;
            int totalCount = (int) totalCountLong;
            int remaining = totalCount;
            for (int i = 0; i < OUTPUT_SLOTS; i++) {
                int slot = outputSlotStart + i;
                ItemStack existing = items.getStackInSlot(slot);
                if (existing.isEmpty()) {
                    remaining = 0;
                    break;
                } else if (ItemStack.isSameItemSameTags(existing, result)) {
                    remaining -= Integer.MAX_VALUE - existing.getCount();
                    if (remaining <= 0) {
                        remaining = 0;
                        break;
                    }
                }
            }
            if (remaining > 0) return false;
        }

        return true;
    }

    /**
     * Consumes ingredients from storage + input slots for the given recipe.
     * Prefers input slots first, then storage slots.
     *
     * <p>Uses unified metadata so Kaleidoscope carrier/oil + FD consumed output
     * container are all taken from storage (not returned as they were in the
     * past). Fluid drains are handled separately in
     * {@link #consumeFluidForRecipe(Recipe, int)} which runs before this
     * method in the recipe lifecycle.
     *
     * <p>For every concrete item stack consumed as an ingredient, empty
     * containers (water_bucket → bucket, water_potion → glass_bottle,
     * farmersdelight:milk_bottle → glass_bottle, plus items declaring their
     * own remaining-item via {@link Item#getCraftingRemainingItem(ItemStack)})
     * are routed to the 3 return slots via {@link #distributeToSlots}. This
     * matches the original CookingFactory return-slot behaviour where e.g. a
     * consumed ketchup ingredient "ketchup(bowl)" would drop its bowl back.
     */
    private void consumeIngredients(Recipe<?> recipe, int consumeCount) {
        if (consumeCount <= 0) return;

        List<Ingredient> solidIngredients = getSolidIngredients(recipe);
        List<Ingredient> extraConsumables = getExtraConsumables(recipe);
        ItemStack consumedContainer = getConsumedContainer(recipe);

        // Build flat list of everything to consume per craft unit
        List<Ingredient> allToConsume = new ArrayList<>(solidIngredients.size() + extraConsumables.size() + 1);
        allToConsume.addAll(solidIngredients);
        allToConsume.addAll(extraConsumables);
        if (!consumedContainer.isEmpty()) {
            allToConsume.add(Ingredient.of(consumedContainer.getItem()));
        }

        // Container returns collected for all units, then distributed at the
        // end. This avoids an N^2 reinsert into the return slots mid-loop and
        // keeps the input-scan stable within each unit.
        List<ItemStack> pendingReturns = new ArrayList<>();

        // Consume ingredients for each unit
        for (int unit = 0; unit < consumeCount; unit++) {
            // Collect items from input slots first, then storage slots
            List<ItemStack> availableItems = new ArrayList<>();
            List<Integer> slotIndices = new ArrayList<>();

            for (int j = 0; j < INPUT_SLOTS; j++) {
                ItemStack stack = items.getStackInSlot(j);
                if (!stack.isEmpty()) {
                    availableItems.add(stack);
                    slotIndices.add(j);
                }
            }
            for (int j = INPUT_SLOTS; j < outputSlotStart; j++) {
                ItemStack stack = items.getStackInSlot(j);
                if (!stack.isEmpty()) {
                    availableItems.add(stack);
                    slotIndices.add(j);
                }
            }

            if (allToConsume.isEmpty()) break;

            // 用与 canMatchItems 完全一致的回溯匹配分配槽位：保证"能匹配"就一定"能消耗"，
            // 避免旧贪心首匹配与回溯校验结果不一致，导致材料被错误消耗、进度条空转、
            // 订单永不完成（AE2 网络下单按精确物品抽料时最容易触发）。
            int[] assignment = findCustomMatches(availableItems, allToConsume);
            if (assignment == null) {
                break; // 防御：正常不会发生（canProcess 已用同款回溯校验）
            }
            for (int i = 0; i < allToConsume.size(); i++) {
                int inputIdx = assignment[i];
                int slotIdx = slotIndices.get(inputIdx);
                Ingredient ing = allToConsume.get(i);
                ItemStack stackBefore = items.getStackInSlot(slotIdx);
                ItemStack snapshot = stackBefore.copyWithCount(1);
                stackBefore.shrink(1);
                if (stackBefore.isEmpty()) {
                    items.setStackInSlot(slotIdx, ItemStack.EMPTY);
                }
                // Determine returns for the consumed item. Snapshot is
                // the "before shrink" view of what was removed.
                for (ItemStack ret : FluidIngredientHelper.getReturnStacksForConsumed(snapshot, ing)) {
                    if (!ret.isEmpty()) pendingReturns.add(ret);
                }
            }
        }

        // Distribute all pending returns across the 3 return slots. Anything
        // that still cannot fit will drop on top of the block so items are
        // never silently lost (matches auto-IO fail-safe convention used
        // elsewhere in this mod).
        if (!pendingReturns.isEmpty()) {
            for (ItemStack ret : pendingReturns) {
                distributeToSlotsOrDrop(ret, returnSlotStart, RETURN_SLOTS);
            }
        }
    }

    /**
     * Same contract as {@link #distributeToSlots(ItemStack, int, int)} but any
     * remainder that does not fit is dropped on top of the machine's position
     * so the container is never silently lost. Safe no-op on the client side.
     */
    private void distributeToSlotsOrDrop(ItemStack stack, int slotStart, int slotCount) {
        distributeToSlots(stack, slotStart, slotCount);
        if (!stack.isEmpty() && level != null && !level.isClientSide) {
            Containers.dropItemStack(level,
                    worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5,
                    stack.copy());
            stack.setCount(0);
        }
    }

    /**
     * Produces output items for the recipe. Containers / carriers / oils are
     * consumed from storage at the start of processing (see consumeIngredients)
     * and are NOT returned anymore — matching the SmartCookingPot behaviour.
     */
    private void completeRecipe(Recipe<?> recipe, int multiplier) {
        // Produce output
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (!result.isEmpty()) {
            long totalCountLong = (long) result.getCount() * multiplier;
            int totalCount = totalCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCountLong;
            ItemStack multiplied = result.copy();
            multiplied.setCount(totalCount);
            distributeToSlots(multiplied, outputSlotStart, OUTPUT_SLOTS);
        }
    }

    /**
     * Distributes an item stack across multiple slots, merging with existing stacks of the same type.
     */
    private void distributeToSlots(ItemStack stack, int slotStart, int slotCount) {
        if (stack.isEmpty()) return;
        for (int i = 0; i < slotCount && !stack.isEmpty(); i++) {
            int slot = slotStart + i;
            ItemStack existing = items.getStackInSlot(slot);
            if (existing.isEmpty()) {
                items.setStackInSlot(slot, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                long total = (long) existing.getCount() + stack.getCount();
                int merged = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
                int moved = merged - existing.getCount();
                existing.setCount(merged);
                items.setStackInSlot(slot, existing);
                stack.shrink(moved);
            }
        }
    }

    /**
     * Gets all available cooking recipes that can be crafted with current materials.
     * Includes farmersdelight cooking, avaritia_delight extreme_cooking recipes,
     * and — when Kaleidoscope Cookery is loaded — stockpot/pot/flex variants.
     * Uses {@link #canMatchRecipe(Recipe, List)} which performs the unified
     * metadata check (solids, extra consumables, consumed container, fluids).
     */
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> available = new ArrayList<>();
        List<ItemStack> allItems = new ArrayList<>();
        for (int j = 0; j < INPUT_SLOTS; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) {
                allItems.add(stack);
            }
        }
        for (int j = INPUT_SLOTS; j < outputSlotStart; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) {
                allItems.add(stack);
            }
        }
        if (allItems.isEmpty()) return available;

        // 1) FarmersDelight cooking recipes
        for (CookingPotRecipe recipe : (java.util.List<CookingPotRecipe>) (java.util.List<?>) cn.ism.mekck.util.RecipeCache.all(level, ModRecipeTypes.COOKING.get())) {
            if (canMatchRecipe(recipe, allItems)) available.add(recipe);
        }

        // 1.5) 沉浸农艺 pot_cooking
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeInputMatcher.getPotCookingRecipes(level)) {
            if (canMatchRecipe(recipe, allItems)) available.add(recipe);
        }

        // 2) Avaritia-Delight 终焉烹饪（extreme_cooking）——仅奇点创世等级支持
        if (tier == CuttingMachineFactoryTier.SINGULARITY) {
            RecipeType<?> extremeShapedType = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("avaritia_delight", "extreme_cooking_shaped"));
            if (extremeShapedType != null) {
                for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, extremeShapedType)) {
                    if (canMatchRecipe(recipe, allItems)) available.add(recipe);
                }
            }
            RecipeType<?> extremeShapelessType = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("avaritia_delight", "extreme_cooking_shapeless"));
            if (extremeShapelessType != null) {
                for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, extremeShapelessType)) {
                    if (canMatchRecipe(recipe, allItems)) available.add(recipe);
                }
            }
        }

        // 3) Kaleidoscope Cookery: stockpot / pot / flex variants (optional)
        if (KaleidoscopeCompat.isLoaded()) {
            for (Recipe<?> recipe : KaleidoscopeCompat.getAllKaleidoscopeRecipes(level)) {
                if (canMatchRecipe(recipe, allItems)) available.add(recipe);
            }
        }

        // 按 Recipe id 去重，避免存储空间多格相同食材 / 多 RecipeType 收集导致重复显示
        Set<ResourceLocation> seen = new HashSet<>();
        available.removeIf(recipe -> !seen.add(recipe.getId()));

        // 按产物去重：同一产物只保留一个可下单选项
        // （不同配方 id 可能产出相同物品，例如 FD 与森罗厨房/终焉烹饪的同款食物）
        Set<String> resultSeen = new HashSet<>();
        available.removeIf(recipe -> {
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty()) return false;
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(result.getItem());
            if (itemId == null) return false;
            String key = itemId.toString() + (result.getTag() == null ? "" : "#" + result.getTag());
            return !resultSeen.add(key);
        });

        return available;
    }

    /**
     * Sets the order for automated crafting.
     */
    @Override
    public boolean isMeOrderEnabled() {
        return meOrderEnabled;
    }

    @Override
    public void setMeOrderEnabled(boolean enabled) {
        this.meOrderEnabled = enabled;
        setChanged();
    }

    public void setOrder(@Nullable ResourceLocation recipeId, int quantity) {
        this.orderRecipeId = recipeId;
        this.orderQuantity = quantity;
        this.orderCompleted = 0;
        setChanged();
    }

    @Nullable
    public ResourceLocation getOrderRecipeId() {
        return orderRecipeId;
    }

    public int getOrderQuantity() {
        return orderQuantity;
    }

    public int getOrderCompleted() {
        return orderCompleted;
    }

    /**
     * Public wrapper around getMaxConsumableCount for use by the order UI.
     */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        return getMaxConsumableCount(recipe);
    }

    /**
     * 扫描存储槽，将水瓶/水桶/奶瓶/奶桶自动转换为流体注入流体槽，
     * 空容器（玻璃瓶/铁桶）退回 returnSlotStart 的返回槽。
     */
    private void convertStoredFluidContainers() {
        for (int i = INPUT_SLOTS; i < outputSlotStart; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            FluidContainerInteract.ContainerFluidInfo info = FluidContainerInteract.getFluidInfo(stack);
            if (info == null || !info.isValid()) continue;
            int filled = fluidTank.fill(info.fluid(), net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);
            if (filled < info.fluid().getAmount()) continue;
            fluidTank.fill(info.fluid(), net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            stack.shrink(1);
            if (stack.isEmpty()) {
                items.setStackInSlot(i, ItemStack.EMPTY);
            }
            // 退回空容器到返回槽
            ItemStack remainder = info.emptyContainer();
            for (int r = 0; r < RETURN_SLOTS && !remainder.isEmpty(); r++) {
                remainder = items.insertItem(returnSlotStart + r, remainder, false);
            }
            if (!remainder.isEmpty() && level != null && !level.isClientSide) {
                Containers.dropItemStack(level, worldPosition.getX() + 0.5,
                        worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, remainder);
            }
            setChanged();
            break;
        }
    }

    private AutoIO autoIO;

    private void autoIO(Level level, BlockPos pos) {
        if (autoIO == null) {
            autoIO = new AutoIO(this,
                    new int[][]{{0, INPUT_SLOTS}},
                    new int[][]{{INPUT_SLOTS, storageSlots}},
                    new int[][]{{outputSlotStart, OUTPUT_SLOTS}, {returnSlotStart, RETURN_SLOTS}});
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
        SideMode next = current.cycle(true, true);
        sideConfig[direction.ordinal()] = next;
        setChanged();
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

    public int getBaseParallel() {
        return MekckConfig.getNonMultithreadedBase(tier);
    }

    public int getStackMultiplier() {
        int base = MekckConfig.getNonMultithreadedBase(tier);
        int maxParallel = MekckConfig.getNonMultithreadedMax(tier);
        if (base >= maxParallel) {
            return base;
        }
        int maxMult = maxParallel / base;
        // 用 long 运算避免溢出（奇点创世的基础并行可达 21 亿级）
        long raw = (long) base * (1L << Math.min(getStackUpgradeCount(), MekckConfig.getFactoryStackUpgradeMax(this.tier)));
        long cap = Math.max(base, (long) maxMult * base);
        return (int) Math.min(raw, cap);
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
        return Math.max(1, (int) (BASE_PROCESS_TIME / getEffectiveSpeedMultiplier()));
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

    public ItemStackHandler getItems() {
        return items;
    }

    // ================== AE2 通用网络拉料 ==================
    @Override public net.minecraft.world.level.block.entity.BlockEntity getNetworkPullable() { return this; }
    @Override public int[] getInputSlotRange() { return new int[]{0, OUTPUT_SLOT_START}; }
    @Override public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() { return items; }
    @Override public boolean supportsAutoPull() { return true; } // ME 持续补料：按"每类型上限"（配置 auto_pull_stack_limit）批量补，受 LagMonitor 限流

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) return List.of();
        return cn.ism.mekck.util.NetworkPullHelper.currentOrUnion(level, items.getStackInSlot(0),
                new ResourceLocation("farmersdelight", "cooking"));
    }

    public ContainerData getData() {
        return data;
    }

    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    public int getStorageSlots() {
        return storageSlots;
    }

    public int getOutputSlot() {
        return outputSlotStart;
    }

    public int getReturnSlot() {
        return returnSlotStart;
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

    public int getTotalSlots() {
        return totalSlots;
    }

    public int getProgress() {
        return progress;
    }

    public MultiFluidHandler getFluidTank() {
        return fluidTank;
    }

    /** 指定槽位的流体量（mb），越界返回 0。 */
    private int fluidAmount(int index) {
        FluidTank tank = fluidTank.getTank(index);
        return tank == null ? 0 : tank.getFluidAmount();
    }

    /** 指定槽位流体的注册表 id（ForgeRegistries.FLUIDS），越界或空槽返回 -1。 */
    private int fluidTypeId(int index) {
        FluidTank tank = fluidTank.getTank(index);
        if (tank == null || tank.isEmpty()) return -1;
        return BuiltInRegistries.FLUID.getId(tank.getFluid().getFluid());
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        if (customName != null) {
            return customName;
        }
        return Component.translatable("block.mekck." + tier.name + "_cooking_factory");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new CookingFactoryMenu(containerId, inventory, this, data);
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
        tag.put("SpeedUpgradeTracker", speedTracker.save());
        tag.put("EnergyUpgradeTracker", energyTracker.save());
        tag.put("StackUpgradeTracker", stackTracker.save());
        tag.put("CreativeUpgradeTracker", creativeTracker.save());
        tag.put("Items", items.serializeNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Progress", progress);
        tag.put("FluidTanks", fluidTank.writeToNBT());
        byte[] sideBytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            sideBytes[i] = (byte) sideConfig[i].ordinal();
        }
        tag.putByteArray("SideConfig", sideBytes);
        tag.putInt("RedstoneControl", redstoneControl.ordinal());
        tag.putBoolean("RedstonePowered", redstonePowered);
        // Save order data
        if (orderRecipeId != null) {
            tag.putString("OrderRecipeId", orderRecipeId.toString());
        }
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        tag.putInt("OrderQuantity", orderQuantity);
        tag.putInt("OrderCompleted", orderCompleted);
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName));
        }
        AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        // 温度系统：热容量持久化（Mekanism IHeatCapacitor NBT）
        if (heatComponent != null) {
            tag.put("HeatCapacitor", heatComponent.save());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("SpeedUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) speedTracker.load(tag.getCompound("SpeedUpgradeTracker"));
        if (tag.contains("EnergyUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) energyTracker.load(tag.getCompound("EnergyUpgradeTracker"));
        if (tag.contains("StackUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) stackTracker.load(tag.getCompound("StackUpgradeTracker"));
        if (tag.contains("CreativeUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) creativeTracker.load(tag.getCompound("CreativeUpgradeTracker"));
        items.deserializeNBT(tag.getCompound("Items"));
        int remainingEnergy = tag.getInt("Energy");
        while (remainingEnergy > 0) {
            int received = energy.receiveEnergy(remainingEnergy, false);
            if (received == 0) {
                break;
            }
            remainingEnergy -= received;
        }
        progress = tag.getInt("Progress");
        // 温度系统：热容量恢复
        if (heatComponent != null && tag.contains("HeatCapacitor", Tag.TAG_COMPOUND)) {
            heatComponent.load(tag.getCompound("HeatCapacitor"));
        }
        if (tag.contains("FluidTanks")) {
            fluidTank.readFromNBT(tag.getCompound("FluidTanks"));
        } else if (tag.contains("FluidTank")) {
            // 旧版单槽数据回退
            fluidTank.readFromNBT(tag.getCompound("FluidTank"));
        }
        if (tag.contains("SideConfig", Tag.TAG_BYTE_ARRAY)) {
            byte[] sideBytes = tag.getByteArray("SideConfig");
            for (int i = 0; i < Math.min(sideBytes.length, 6); i++) {
                int ordinal = sideBytes[i];
                if (ordinal >= 0 && ordinal < SideMode.values().length) {
                    sideConfig[i] = SideMode.values()[ordinal];
                }
            }
        }
        if (tag.contains("RedstoneControl")) {
            redstoneControl = RedstoneControl.byOrdinal(tag.getInt("RedstoneControl"));
        }
        if (tag.contains("RedstonePowered")) {
            redstonePowered = tag.getBoolean("RedstonePowered");
        }
        // Load order data
        if (tag.contains("OrderRecipeId")) {
            orderRecipeId = ResourceLocation.tryParse(tag.getString("OrderRecipeId"));
        }
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        orderQuantity = tag.getInt("OrderQuantity");
        orderCompleted = tag.getInt("OrderCompleted");
        if (tag.contains("CustomName")) {
            customName = Component.Serializer.fromJson(tag.getString("CustomName"));
        }
        AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // AE2 网格节点销毁（未安装 AE2 时为空操作）
        AE2Compat.onRemoved(this);
    }

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return energyCapability.cast();
        }
        // 温度系统：暴露 Mekanism 热能力，供热力设备（电阻加热器/热导管等）传导
        if (capability == mekanism.common.capabilities.Capabilities.HEAT_HANDLER) {
            return heatCapability.cast();
        }
        if (capability == ForgeCapabilities.FLUID_HANDLER) {
            return fluidCapability.cast();
        }
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            if (side == null) {
                return fullItemCapability.cast();
            }
            SideMode mode = sideConfig[side.ordinal()];
            if (mode == SideMode.PULL_INPUT) {
                return inputItemCapability.cast();
            } else if (mode == SideMode.PULL_INPUT_STORAGE) {
                return storageItemCapability.cast();
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
        storageItemCapability.invalidate();
        outputItemCapability.invalidate();
        energyCapability.invalidate();
        fluidCapability.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        fullItemCapability = LazyOptional.of(() -> items);
        inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        storageItemCapability = LazyOptional.of(() -> new StorageItemHandler());
        outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        energyCapability = LazyOptional.of(() -> energy);
        fluidCapability = LazyOptional.of(() -> fluidTank);
    }

    private final class StorageItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return storageSlots;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= storageSlots) return ItemStack.EMPTY;
            return items.getStackInSlot(INPUT_SLOTS + slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= storageSlots) return stack;
            return items.insertItem(INPUT_SLOTS + slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot < 0 || slot >= storageSlots) return 0;
            return items.getSlotLimit(INPUT_SLOTS + slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot < 0 || slot >= storageSlots) return false;
            return items.isItemValid(INPUT_SLOTS + slot, stack);
        }
    }

    private final class InputItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return INPUT_SLOTS + storageSlots; // input + storage slots
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < INPUT_SLOTS + storageSlots) {
                return items.getStackInSlot(slot);
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < INPUT_SLOTS + storageSlots) {
                return items.insertItem(slot, stack, simulate);
            }
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot < INPUT_SLOTS + storageSlots) {
                return items.isItemValid(slot, stack);
            }
            return false;
        }
    }

    private final class OutputItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return OUTPUT_SLOTS + RETURN_SLOTS; // 9 output + 3 return slots
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot >= 0 && slot < OUTPUT_SLOTS) {
                return items.getStackInSlot(outputSlotStart + slot);
            }
            int returnIdx = slot - OUTPUT_SLOTS;
            if (returnIdx >= 0 && returnIdx < RETURN_SLOTS) {
                return items.getStackInSlot(returnSlotStart + returnIdx);
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot >= 0 && slot < OUTPUT_SLOTS) {
                return items.extractItem(outputSlotStart + slot, amount, simulate);
            }
            int returnIdx = slot - OUTPUT_SLOTS;
            if (returnIdx >= 0 && returnIdx < RETURN_SLOTS) {
                return items.extractItem(returnSlotStart + returnIdx, amount, simulate);
            }
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }
}