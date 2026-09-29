package cn.ism.mekck.blockentity;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.block.GrillFactoryBlock;
import cn.ism.mekck.menu.GrillFactoryMenu;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.AE2Compat;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.BarbequesDelightCompat;
import cn.ism.mekck.util.FastTransfer;
import cn.ism.mekck.util.KaleidoscopeGrillingCompat;
import cn.ism.mekck.util.LagMonitor;
import cn.ism.mekck.util.PowerSlotUtil;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
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
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class GrillFactoryBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, mekanism.api.heat.IMekanismHeatHandler , cn.ism.mekck.ae2.INetworkPullable {
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

    public static final int BASE_PROCESS_TIME = 200;
    public static final int BASE_ENERGY_PER_TICK = 20;

    // ContainerData indices
    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_ENERGY_CAPACITY = 3;
    public static final int DATA_SIDE_CONFIG = 4;
    public static final int DATA_SPEED_UPGRADE = 5;
    public static final int DATA_ENERGY_UPGRADE = 6;
    public static final int DATA_STACK_UPGRADE = 7;
    public static final int DATA_AUTO_DISTRIBUTE = 8;
    public static final int DATA_CREATIVE_UPGRADE = 9;
    public static final int DATA_REDSTONE_CONTROL = 10;
    /** 当前订单调味料在 BarbequesDelightCompat.getAllSeasonings() 中的下标，-1 表示无。 */
    public static final int DATA_ORDER_SEASONING = 11;
    public static final int DATA_ORDER_QUANTITY = 12;
    public static final int DATA_ORDER_COMPLETED = 13;
    /** 工作模式：0=默认（直接加工），1=下单（材料入存储），经 ContainerData 同步。 */
    public static final int DATA_WORK_MODE = 14;
    /** 调味料启用位图（bit i = 第 i 个调味料槽启用自动调味）。 */
    public static final int DATA_SEASONING_ENABLED = 15;
    /** 机身温度（单位 0.01 ℃）。 */
    public static final int DATA_TEMPERATURE = 16;
    public static final int DATA_SIZE = 17;
    /**
     * 原版食物类烹饪配方（烟熏炉 / 篝火）——全部档位可处理。
     * 二者在 1.20.1 内容完全相同（同 9 条：熟肉 ×7、烤马铃薯、干燥海带），语义上就是"烧烤"。
     */
    private static final RecipeType<?>[] FOOD_COOKING_TYPES = {
            RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING
    };
    /**
     * 原版熔炼类配方（熔炉 / 高炉）——仅晶钛矩阵以上等级额外可处理。
     * 这两族共 94 条里绝大多数是矿石与建材，与 Mek 富集腔功能重叠，
     * 因此不提升为全档位，只作为高阶档位的额外能力。
     */
    private static final RecipeType<?>[] FURNACE_FAMILY_TYPES = {
            RecipeType.SMELTING, RecipeType.BLASTING
    };
    /** 调味料存储槽数量（追加在能源槽之后）。 */
    public static final int SEASONING_SLOTS = 3;
    /** 存储槽数量（下单模式缓冲：材料从管道/shift 进入存储区）。 */
    public static final int STORAGE_SLOTS = 45;
    /** 存储区每行槽数（5 行 x 9 列）。 */
    public static final int STORAGE_ROWS = 9;

    /** 工作模式：默认（直接加工）/ 下单（材料先入存储，下单后自动送入输入格）。 */
    public enum WorkMode {
        DEFAULT, ORDER;

        public static WorkMode byOrdinal(int ordinal) {
            WorkMode[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : DEFAULT;
        }
    }

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
    private WorkMode workMode = WorkMode.DEFAULT;
    /** 每个调味料槽的启用开关（默认工作模式下自动调味，多个启用时均匀使用）。 */
    private final boolean[] seasoningEnabled = new boolean[SEASONING_SLOTS];
    // ME 自动处理：已勾选材料（registry id 列表），网络有货时持续抽取处理
    private List<String> autoSelectedItems = new ArrayList<>();
    private Component customName;
    private int progress = 0;

    // ================== 温度系统（Mekanism 热容量；运行产热，不影响运行条件） ==================
    /** 热容量（J/K）：与 Mekanism 电阻型加热器一致。 */
    public static final double HEAT_CAPACITY = 100.0;
    private static final double HEAT_INVERSE_CONDUCTION = 5.0;
    private static final double HEAT_INVERSE_INSULATION = 100.0;
    /** 电能→热量转换效率：与 Mekanism 电阻型加热器完全相同（1 FE → 0.6 J 热量）。 */
    public static final double HEAT_EFFICIENCY = 0.6;
    private static final double AMBIENT_LOSS_RATE = 0.01;
    private cn.ism.mekck.util.MekCkHeatComponent heatComponent;
    private final net.minecraftforge.common.util.LazyOptional<mekanism.api.heat.IHeatHandler> heatCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> heatComponent.getHandler());

    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    // PULSE 模式：收到红石信号(上升沿)后锁存为 true，完成一次完整处理后复位。
    private boolean pulseRunning = false;

    // Order system（下单 + 调味）
    @Nullable
    private ResourceLocation orderRecipeId;
    private int orderQuantity;   // 订单总份数
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true;  // 已完成份数
    /** 当前订单使用的调味料（BBQSeasoning 枚举名，大写）；null 表示不调味。 */
    @Nullable
    private String orderSeasoning;

    public final ItemStackHandler items;
    private final EnergyStorage energy;

    private final SideMode[] sideConfig = new SideMode[6];

    // Slot indices
    private final int speedUpgradeSlot;
    private final int energyUpgradeSlot;
    private final int stackUpgradeSlot;
    private final int creativeUpgradeSlot;
    private final int powerSlot;
    /** 调味料槽起始索引（powerSlot + 1 起，共 SEASONING_SLOTS 格）。 */
    private final int seasoningSlotStart;
    /** 存储槽起始索引（调味料槽之后，共 STORAGE_SLOTS 格）。 */
    private final int storageSlotStart;

    private LazyOptional<IItemHandler> fullItemCapability;
    private LazyOptional<IItemHandler> inputItemCapability;
    private LazyOptional<IItemHandler> storageItemCapability;
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
                case DATA_AUTO_DISTRIBUTE -> autoDistribute ? 1 : 0;
                case DATA_CREATIVE_UPGRADE -> hasCreativeUpgrade() ? 1 : 0;
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
                case DATA_TEMPERATURE -> (int) Math.round((getTemperature() - 273.15) * 100.0);
                case DATA_ORDER_SEASONING -> orderSeasoningIndex();
                case DATA_ORDER_QUANTITY -> orderQuantity;
                case DATA_ORDER_COMPLETED -> orderCompleted;
                case DATA_WORK_MODE -> workMode.ordinal();
                case DATA_SEASONING_ENABLED -> seasoningFlags();
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

    public GrillFactoryBlockEntity(CuttingMachineFactoryTier tier, BlockPos pos, BlockState state) {
        super(getTileType(tier), pos, state);
        this.tier = tier;
                // 上限惰性读取 MekckConfig，故 /reload 改配置后立即生效。
                // 必须在构造器体内初始化：tier 在此处才保证已赋值，字段初始化器里无法引用。
                this.speedTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactorySpeedUpgradeMax(tier));
                this.energyTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryEnergyUpgradeMax(tier));
                this.stackTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryStackUpgradeMax(tier));
        // 温度系统：Mekanism 热容量（环境温度取自 Mekanism HeatAPI）
        this.heatComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
        this.inputSlots = tier.processes;
        this.hasStackUpgrade = tier.supportsStackUpgrade();
        this.speedUpgradeSlot = 2 * inputSlots;
        this.energyUpgradeSlot = 2 * inputSlots + 1;
        this.stackUpgradeSlot = hasStackUpgrade ? 2 * inputSlots + 2 : -1;
        this.creativeUpgradeSlot = hasStackUpgrade ? stackUpgradeSlot + 1 : 2 * inputSlots + 2;
        // 能源槽位（能量物品），追加在末尾
        this.powerSlot = 2 * inputSlots + (hasStackUpgrade ? 4 : 3);
        // 调味料槽追加在能源槽之后（不移动任何既有槽位，兼容旧存档）
        this.seasoningSlotStart = this.powerSlot + 1;
        // 存储槽追加在调味料槽之后
        this.storageSlotStart = this.seasoningSlotStart + SEASONING_SLOTS;
        this.totalSlots = this.storageSlotStart + STORAGE_SLOTS;

        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
        }

        this.items = new cn.ism.mekck.util.BigStackItemHandler(totalSlots) {
            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                if (slot < inputSlots) {
                    // 输入槽只接受普通食材，不允许放入任何升级物品；
                    // 原版烟熏炉 / 篝火烹饪（熟肉、烤马铃薯、干燥海带）全档位可处理；
                    // 晶钛矩阵以上等级（按配置）额外接受可熔炼（熔炉 / 高炉）的食材
                    return !isAnyUpgradeItem(stack) && (RecipeInputMatcher.matchesGrilling(level, stack)
                            || RecipeInputMatcher.matchesFoodCooking(level, stack)
                            || (furnaceFamilyEnabled() && RecipeInputMatcher.matchesFurnaceFamily(level, stack)));
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
                if (slot >= seasoningSlotStart && slot < seasoningSlotStart + SEASONING_SLOTS) {
                    // 调味料槽只接受烧烤乐事的调味料物品
                    return BarbequesDelightCompat.isSeasoning(stack) || KaleidoscopeGrillingCompat.isSeasoningBottle(stack);
                }
                if (slot >= storageSlotStart && slot < storageSlotStart + STORAGE_SLOTS) {
                    // 存储槽只接受普通物品（升级物品走升级槽）
                    return !isAnyUpgradeItem(stack);
                }
                return false;
            }

            @Override
            public int getSlotLimit(int slot) {
                if (slot < 2 * inputSlots) {
                    return Integer.MAX_VALUE;
                }
                if (slot >= seasoningSlotStart && slot < seasoningSlotStart + SEASONING_SLOTS) {
                    // 调味料带耐久，单格 1 个
                    return 1;
                }
                if (slot >= storageSlotStart && slot < storageSlotStart + STORAGE_SLOTS) {
                    // 存储槽无堆叠上限
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
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (stack.isEmpty()) return stack;
                if (!isItemValid(slot, stack)) return stack;
                if (slot < 2 * inputSlots
                        || (slot >= storageSlotStart && slot < storageSlotStart + STORAGE_SLOTS)) {
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
                // Input, output and storage slots: unlimited
                if (slot < 2 * inputSlots
                        || (slot >= storageSlotStart && slot < storageSlotStart + STORAGE_SLOTS)) {
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

        // Energy storage
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

    private static BlockEntityType<GrillFactoryBlockEntity> getTileType(CuttingMachineFactoryTier tier) {
        // 直接查注册表，不再逐个 case 列等级。
        // 原先的 switch 只列了 11 个等级、**漏了 BLAZE** ⇒ 放置烈焰等级工厂时落到
        // default 抛 IllegalArgumentException 崩溃（2026-09-16 用户实测：放置烈焰切菜机必崩）。
        // 改为查表后，将来新增等级无需再改这里（制冰工厂一直是这么做的）。
        return cn.ism.mekck.UniversalCuttingMachine.GRILL_FACTORY_BLOCK_ENTITIES.get(tier).get();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, GrillFactoryBlockEntity machine) {
        // 升级读条：槽位放入升级后 20 tick 安装一次
        machine.tickUpgradeTrackers();
        // 温度系统：每 tick 自然散热并应用热量（与是否运行无关）
        machine.tickHeat();
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

        // 下单模式：从存储区为当前订单补充输入格（先于配方扫描）
        if (machine.workMode == WorkMode.ORDER) {
            machine.feedInputsFromStorage();
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
        int baseProcessCount = machine.getBaseProcessCount();
        int effectiveProcessCount = cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT, baseProcessCount, stackMult);
        double speedMult = machine.getEffectiveSpeedMultiplier();
        double energyConsumptionMult = machine.getEffectiveEnergyConsumptionMultiplier();
        int effectiveProcessTime = hasCreative ? 1 : Math.max(1, (int) (BASE_PROCESS_TIME / speedMult));
        int baseEnergyPerTick = hasCreative ? 0 : (int) Math.ceil(BASE_ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);
        if (machine.getTier().energyPerTick == 0) {
            baseEnergyPerTick = 0;
        }

        // Check all input slots
        int activeSlots = 0;
        boolean anyValid = false;
        boolean hasOrder = machine.orderRecipeId != null;
        int orderRemaining = hasOrder ? machine.orderQuantity - machine.orderCompleted : 0;

        for (int i = 0; i < machine.inputSlots; i++) {
            ItemStack input = machine.items.getStackInSlot(i);
            if (input.isEmpty()) continue;

            Optional<Recipe<?>> recipe = machine.findRecipe(i);
            if (recipe.isPresent() && hasOrder
                    && !recipe.get().getId().equals(machine.orderRecipeId)) {
                // 有订单时只加工订单指定的配方
                continue;
            }
            if (recipe.isPresent()) {
                ItemStack result = recipe.get().getResultItem(level.registryAccess());
                if (!result.isEmpty()) {
                    int count = machine.effectiveCountFor(i, result, effectiveProcessCount,
                            hasOrder ? Math.max(0, orderRemaining) : Integer.MAX_VALUE);
                    if (count > 0 && machine.canFitAll(machine.buildResultPreview(result), count)) {
                        activeSlots++;
                        anyValid = true;
                    }
                }
            }
        }

        int energyPerTick = activeSlots > 0 ? cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, baseEnergyPerTick, activeSlots, stackMult) : 0;

        // PULSE 模式：收到红石信号(上升沿)时锁存，机器开始一次完整的处理
        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        boolean canOperate = machine.canFunctionRedstone();
        if (canOperate && anyValid && machine.energy.getEnergyStored() >= energyPerTick) {
            machine.energy.extractEnergy(energyPerTick, false);
            // 温度系统：消耗的电能按电阻型加热器效率转为热量（运行条件与温度无关）
            machine.addHeatFromEnergy(energyPerTick);
            machine.progress++;
            if (machine.progress >= effectiveProcessTime) {
                int producedThisCycle = 0;
                for (int i = 0; i < machine.inputSlots; i++) {
                    ItemStack input = machine.items.getStackInSlot(i);
                    if (input.isEmpty()) continue;
                    Optional<Recipe<?>> recipe = machine.findRecipe(i);
                    if (recipe.isPresent() && hasOrder
                            && !recipe.get().getId().equals(machine.orderRecipeId)) {
                        continue;
                    }
                    if (recipe.isPresent()) {
                        ItemStack result = recipe.get().getResultItem(level.registryAccess());
                        if (!result.isEmpty()) {
                            int count = machine.effectiveCountFor(i, result, effectiveProcessCount,
                                    hasOrder ? Math.max(0, orderRemaining) : Integer.MAX_VALUE);
                            if (count > 0) {
                                producedThisCycle += machine.completeRecipe(i, recipe.get(), count);
                            }
                        }
                    }
                }
                // Update order tracking
                if (hasOrder && machine.orderQuantity > 0) {
                    machine.orderCompleted += producedThisCycle;
                    if (machine.orderCompleted >= machine.orderQuantity) {
                        // 订单完成，清空（含调味料）
                        machine.orderRecipeId = null;
                        machine.orderQuantity = 0;
                        machine.orderCompleted = 0;
                        machine.orderSeasoning = null;
                    }
                    changed = true;
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
                machine.progress = 0;
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

        boolean isActive = machine.progress > 0;
        if (wasActive != isActive) {
            level.setBlock(pos, state.setValue(GrillFactoryBlock.ACTIVE, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, GrillFactoryBlockEntity machine) {
        if (state.getValue(GrillFactoryBlock.ACTIVE)) {
            SoundHandler.startTileSound(MekanismSounds.ENRICHMENT_CHAMBER.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
        } else {
            SoundHandler.stopTileSound(pos);
        }
    }

    private AutoIO autoIO;

    private void autoIO(Level level, BlockPos pos) {
        if (autoIO == null) {
            autoIO = new AutoIO(this,
                    new int[][]{{0, inputSlots}},
                    new int[][]{{storageSlotStart, STORAGE_SLOTS}},
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
        sideConfig[direction.ordinal()] = current.cycle(true, true);
        setChanged();
    }

    // ── 每槽配方记忆 ──
    // 高等级烧烤工厂会额外扫描熔炉/烟熏炉/高炉配方（合计数千条），而本方法在 tick 里按槽调用，
    // 且同一 tick 会被调用两次（"是否有可加工槽"与"完成时逐个结算"）。按输入指纹记忆结果即可，
    // 输入一变指纹就变，配方重载由 RecipeCache 侧负责失效。
    private long[] slotRecipeKey;
    private Recipe<?>[] slotRecipeValue;
    private boolean[] slotRecipeValid;

    private Optional<Recipe<?>> findRecipe(int inputSlot) {
        ItemStack stack = items.getStackInSlot(inputSlot);
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        if (slotRecipeValid == null) {
            int n = Math.max(1, items.getSlots());
            slotRecipeKey = new long[n];
            slotRecipeValue = new Recipe<?>[n];
            slotRecipeValid = new boolean[n];
        }
        if (inputSlot >= 0 && inputSlot < slotRecipeValid.length) {
            long key = cn.ism.mekck.util.MatchKey.of(stack);
            if (slotRecipeValid[inputSlot] && slotRecipeKey[inputSlot] == key) {
                return Optional.ofNullable(slotRecipeValue[inputSlot]);
            }
            Optional<Recipe<?>> found = findRecipeUncached(inputSlot, stack);
            slotRecipeValid[inputSlot] = true;
            slotRecipeKey[inputSlot] = key;
            slotRecipeValue[inputSlot] = found.orElse(null);
            return found;
        }
        return findRecipeUncached(inputSlot, stack);
    }

    private Optional<Recipe<?>> findRecipeUncached(int inputSlot, ItemStack stack) {
        ResourceLocation grillingTypeId = new ResourceLocation("barbequesdelight", "grilling");
        RecipeType<?> grillingType = cn.ism.mekck.util.RecipeCache.type(grillingTypeId);
        if (grillingType == null) return Optional.empty();

        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, grillingType)) {
            if (matchesInput(recipe, stack)) {
                return Optional.of(recipe);
            }
        }
        for (KaleidoscopeGrillingCompat.VirtualRecipe vr : KaleidoscopeGrillingCompat.getGrillingVirtualRecipes()) {
            if (matchesInput(vr, stack)) {
                return Optional.of(vr);
            }
        }
        // 原版烟熏炉 / 篝火烹饪配方——全部档位可处理，是低阶档位的主要配方来源
        for (RecipeType<?> type : FOOD_COOKING_TYPES) {
            for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                if (matchesInput(recipe, stack)) {
                    return Optional.of(recipe);
                }
            }
        }
        // 晶钛矩阵~奇点创世等级（按配置）额外处理原版熔炉 / 高炉配方
        if (furnaceFamilyEnabled()) {
            for (RecipeType<?> type : FURNACE_FAMILY_TYPES) {
                for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, type)) {
                    if (matchesInput(recipe, stack)) {
                        return Optional.of(recipe);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 该等级是否启用原版熔炉 / 高炉配方（晶钛矩阵~星云与奇点创世分开配置）。
     * 烟熏炉 / 篝火烹饪不受此门禁约束——那是全档位的基础能力。
     */
    private boolean furnaceFamilyEnabled() {
        if (tier == CuttingMachineFactoryTier.CRYSTAL_MATRIX || tier == CuttingMachineFactoryTier.NEBULA) {
            return MekckConfig.isCrystalMatrixGrillFurnaceEnabled();
        }
        if (tier == CuttingMachineFactoryTier.SINGULARITY) {
            return MekckConfig.isSingularityGrillFurnaceEnabled();
        }
        return false;
    }

    /**
     * Checks if the recipe's ingredient matches the given input stack.
     * Uses reflection to access the 'ingredient' field for SimpleGrillingRecipe,
     * or falls back to getIngredients() for standard Recipe interface.
     */
    private static boolean matchesInput(Recipe<?> recipe, ItemStack input) {
        try {
            java.lang.reflect.Field field = recipe.getClass().getField("ingredient");
            Ingredient ingredient = (Ingredient) field.get(recipe);
            return ingredient.test(input);
        } catch (Exception ignored) {
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        if (!ingredients.isEmpty()) {
            return ingredients.get(0).test(input);
        }
        return false;
    }

    /**
     * 完成一次加工：扣取输入、按订单调味（消耗 1 点耐久/个）并输出产物。
     *
     * @return 实际消耗的输入份数（用于订单进度统计）。
     */
    private int completeRecipe(int inputSlot, Recipe<?> recipe, int consumeCount) {
        if (inputSlot < 0 || inputSlot >= items.getSlots() || inputSlot >= inputSlots) {
            return 0;
        }
        ItemStack input = items.getStackInSlot(inputSlot);
        if (input.isEmpty()) return 0;

        consumeCount = Math.min(consumeCount, input.getCount());
        if (consumeCount <= 0) return 0;

        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) return 0;

        String seasoning = currentSeasoningFor(result);
        // 预览带调味 NBT 的产物，保证 canFitAll 与实际插入的合并行为一致
        ItemStack preview = result.copy();
        if (seasoning != null) {
            BarbequesDelightCompat.applySeasoning(preview, seasoning);
        }

        if (!canFitAll(preview, consumeCount)) {
            return 0;
        }

        // Consume input
        if (input.getCount() <= consumeCount) {
            items.setStackInSlot(inputSlot, ItemStack.EMPTY);
        } else {
            ItemStack remaining = input.copy();
            remaining.setCount(input.getCount() - consumeCount);
            items.setStackInSlot(inputSlot, remaining);
        }

        // Output the multiplied result
        long outputCountLong = (long) result.getCount() * consumeCount;
        int outputCount = outputCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) outputCountLong;
        ItemStack multiplied = result.copy();
        multiplied.setCount(outputCount);

        // 应用调味：写入 NBT + 按产出个数消耗调味料耐久
        if (seasoning != null && outputCount > 0) {
            if (seasoning.startsWith(KaleidoscopeGrillingCompat.MOD_ID)) {
                KaleidoscopeGrillingCompat.applySeasoningToSkewer(multiplied, seasoning);
            } else {
                BarbequesDelightCompat.applySeasoning(multiplied, seasoning);
            }
            consumeSeasoningUses(seasoning, outputCount, orderSeasoning == null || orderSeasoning.isEmpty());
        }

        insertOutput(multiplied);
        return consumeCount;
    }

    // ================== 调味料系统 ==================

    /** 当前对该产物应使用的调味料 id：订单调味优先；否则默认模式自动选已启用的调味料。 */
    @Nullable
    private String currentSeasoningFor(ItemStack result) {
        if (!BarbequesDelightCompat.isSeasonable(result)) return null;
        if (orderSeasoning != null && !orderSeasoning.isEmpty()) {
            return orderSeasoning;
        }
        if (workMode == WorkMode.DEFAULT) {
            return pickDefaultSeasoning();
        }
        return null;
    }

    /** 默认模式：在已启用的调味料槽中选剩余可用次数最多的（多个启用时均匀消耗）。 */
    @Nullable
    private String pickDefaultSeasoning() {
        String bestId = null;
        int bestUses = 0;
        for (int i = 0; i < SEASONING_SLOTS; i++) {
            if (!seasoningEnabled[i]) continue;
            int slot = seasoningSlotStart + i;
            if (slot >= items.getSlots()) break;
            ItemStack stack = items.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            String id = seasoningIdOf(stack);
            if (id == null) continue;
            int uses = seasoningUsesOf(stack);
            if (uses > bestUses) {
                bestUses = uses;
                bestId = id;
            }
        }
        return bestId;
    }

    /** 调味料 id：烟火瓶返回其配方 id；烧烤乐事返回枚举名。 */
    @Nullable
    private String seasoningIdOf(ItemStack stack) {
        if (KaleidoscopeGrillingCompat.isSeasoningBottle(stack)) {
            return KaleidoscopeGrillingCompat.getSeasoningId(stack);
        }
        return BarbequesDelightCompat.getSeasoningId(stack);
    }

    /** 调味料剩余可用次数。 */
    private int seasoningUsesOf(ItemStack stack) {
        if (KaleidoscopeGrillingCompat.isSeasoningBottle(stack)) {
            return KaleidoscopeGrillingCompat.getSeasoningUses(stack);
        }
        int maxDamage = stack.getMaxDamage();
        if (maxDamage <= 0) return 0;
        return Math.max(0, maxDamage - stack.getDamageValue());
    }

    /** 带调味 NBT 的产物预览（用于输出槽容量判定）。 */
    private ItemStack buildResultPreview(ItemStack result) {
        ItemStack preview = result.copy();
        String seasoning = currentSeasoningFor(result);
        if (seasoning != null) {
            BarbequesDelightCompat.applySeasoning(preview, seasoning);
        }
        return preview;
    }

    /**
     * 单个输入槽本次可加工的份数上限：
     * min(基准并行数, 输入存量, 订单剩余份数, 可用调味料用量折算份数)。
     */
    private int effectiveCountFor(int inputSlotIndex, ItemStack result, int baseCount, int orderRemaining) {
        ItemStack input = items.getStackInSlot(inputSlotIndex);
        int n = Math.min(baseCount, input.getCount());
        if (orderRemaining < n) n = orderRemaining;
        if (n <= 0) return 0;
        String seasoning = currentSeasoningFor(result);
        if (seasoning != null) {
            // 默认模式自动调味只统计已启用槽位；订单调味统计全部槽位
            boolean enabledOnly = orderSeasoning == null || orderSeasoning.isEmpty();
            int uses = availableSeasoningUses(seasoning, enabledOnly);
            n = Math.min(n, uses / Math.max(1, result.getCount()));
        }
        return Math.max(0, n);
    }

    /** 全部调味料槽中，指定调味料的剩余可用次数（每点耐久 = 1 次）。 */
    public int availableSeasoningUses(String seasoningId) {
        return availableSeasoningUses(seasoningId, false);
    }

    /** 指定调味料的可用次数；enabledOnly=true 时只统计已启用槽位（默认模式自动调味）。 */
    public int availableSeasoningUses(String seasoningId, boolean enabledOnly) {
        int total = 0;
        for (int i = 0; i < SEASONING_SLOTS; i++) {
            if (enabledOnly && !seasoningEnabled[i]) continue;
            int slot = seasoningSlotStart + i;
            if (slot >= items.getSlots()) break;
            ItemStack stack = items.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            String id = seasoningIdOf(stack);
            if (id != null && id.equals(seasoningId)) {
                total += seasoningUsesOf(stack);
            }
        }
        return total;
    }

    /** 消耗指定数量的调味料耐久（耐久耗尽的物品销毁，与原版 hurtAndBreak 一致）。 */
    private void consumeSeasoningUses(String seasoningId, int uses) {
        consumeSeasoningUses(seasoningId, uses, false);
    }

    /** enabledOnly=true 时只消耗已启用槽位（默认模式自动调味，不碰禁用调味料）。 */
    private void consumeSeasoningUses(String seasoningId, int uses, boolean enabledOnly) {
        int need = uses;
        for (int i = 0; i < SEASONING_SLOTS && need > 0; i++) {
            if (enabledOnly && !seasoningEnabled[i]) continue;
            int slot = seasoningSlotStart + i;
            if (slot >= items.getSlots()) break;
            ItemStack stack = items.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (KaleidoscopeGrillingCompat.isSeasoningBottle(stack)) {
                if (KaleidoscopeGrillingCompat.getSeasoningId(stack).equals(seasoningId) && need > 0) {
                    KaleidoscopeGrillingCompat.consumeSeasoningUse(stack);
                    items.setStackInSlot(slot, stack);
                    need--;
                }
                continue;
            }
            String id = BarbequesDelightCompat.getSeasoningId(stack);
            if (id == null || !id.equals(seasoningId)) continue;
            int maxDamage = stack.getMaxDamage();
            if (maxDamage <= 0) continue;
            int damageable = maxDamage - stack.getDamageValue();
            if (damageable <= 0) continue;
            int dmg = Math.min(need, damageable);
            int newDamage = stack.getDamageValue() + dmg;
            if (newDamage >= maxDamage) {
                items.setStackInSlot(slot, ItemStack.EMPTY);
            } else {
                stack.setDamageValue(newDamage);
                items.setStackInSlot(slot, stack);
            }
            need -= dmg;
        }
    }

    /**
     * 下单模式：把存储区中匹配当前订单配方的材料移入输入格（每格补充到并行数上限）。
     * 仅在服务端 serverTick 调用。
     */
    private void feedInputsFromStorage() {
        if (workMode != WorkMode.ORDER || orderRecipeId == null) return;
        Recipe<?> orderRecipe = findOrderRecipe();
        if (orderRecipe == null) return;
        int fillTarget = Math.max(1, getBaseProcessCount() * getStackMultiplier());
        int orderRemaining = Math.max(0, orderQuantity - orderCompleted);
        fillTarget = Math.min(fillTarget, Math.max(1, orderRemaining));
        for (int i = 0; i < inputSlots; i++) {
            if (!items.getStackInSlot(i).isEmpty()) continue;
            for (int s = storageSlotStart; s < storageSlotStart + STORAGE_SLOTS; s++) {
                ItemStack candidate = items.getStackInSlot(s);
                if (candidate.isEmpty() || !matchesInput(orderRecipe, candidate)) continue;
                int take = Math.min(candidate.getCount(), fillTarget);
                if (take <= 0) continue;
                ItemStack extracted = items.extractItem(s, take, false);
                if (extracted.isEmpty()) continue;
                ItemStack leftover = items.insertItem(i, extracted, false);
                if (!leftover.isEmpty()) {
                    items.insertItem(s, leftover, false);
                }
                setChanged();
                break;
            }
        }
    }

    /** 按 id 查找订单配方（含烟火虚拟配方）。 */
    @Nullable
    private Recipe<?> findOrderRecipe() {
        if (orderRecipeId == null) return null;
        ResourceLocation grillingTypeId = new ResourceLocation("barbequesdelight", "grilling");
        RecipeType<?> grillingType = cn.ism.mekck.util.RecipeCache.type(grillingTypeId);
        if (grillingType != null) {
            for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, grillingType)) {
                if (recipe.getId().equals(orderRecipeId)) return recipe;
            }
        }
        for (KaleidoscopeGrillingCompat.VirtualRecipe vr : KaleidoscopeGrillingCompat.getGrillingVirtualRecipes()) {
            if (vr.getId().equals(orderRecipeId)) return vr;
        }
        return null;
    }

    private boolean canFitAll(ItemStack result, int multiplier) {
        int outputSlots = inputSlots;
        int outputStart = inputSlots;
        int outputEnd = 2 * inputSlots;
        if (outputEnd > items.getSlots()) {
            return false;
        }

        long totalCountLong = (long) result.getCount() * multiplier;
        if (totalCountLong > Integer.MAX_VALUE) return false;
        int totalCount = (int) totalCountLong;

        ItemStack remainder = result.copy();
        remainder.setCount(totalCount);

        for (int slot = 0; slot < outputSlots && !remainder.isEmpty(); slot++) {
            int outputSlot = outputStart + slot;
            if (outputSlot >= items.getSlots()) return false;
            ItemStack existing = items.getStackInSlot(outputSlot);
            if (existing.isEmpty()) {
                int moved = Math.min(remainder.getCount(), items.getSlotLimit(outputSlot));
                remainder.shrink(moved);
            } else if (ItemStack.isSameItemSameTags(existing, remainder)) {
                int limit = items.getSlotLimit(outputSlot);
                int space = limit - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(remainder.getCount(), space);
                    remainder.shrink(moved);
                }
            }
        }
        return remainder.isEmpty();
    }

    private void insertOutput(ItemStack stack) {
        int outputSlots = inputSlots;
        int outputStart = inputSlots;
        for (int slot = 0; slot < outputSlots && !stack.isEmpty(); slot++) {
            int outputSlot = outputStart + slot;
            if (outputSlot >= items.getSlots()) return;
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

    /** 调味料槽起始索引（共 {@link #SEASONING_SLOTS} 格）。 */
    public int getSeasoningSlotStart() {
        return seasoningSlotStart;
    }

    /** 存储槽起始索引（共 {@link #STORAGE_SLOTS} 格）。 */
    public int getStorageSlotStart() {
        return storageSlotStart;
    }

    public int getStorageSlots() {
        return STORAGE_SLOTS;
    }

    public WorkMode getWorkMode() {
        return workMode;
    }

    public void toggleWorkMode() {
        workMode = workMode == WorkMode.DEFAULT ? WorkMode.ORDER : WorkMode.DEFAULT;
        setChanged();
    }

    public boolean getSeasoningEnabled(int index) {
        return index >= 0 && index < SEASONING_SLOTS && seasoningEnabled[index];
    }

    public void toggleSeasoningEnabled(int index) {
        if (index < 0 || index >= SEASONING_SLOTS) return;
        seasoningEnabled[index] = !seasoningEnabled[index];
        setChanged();
    }

    private int seasoningFlags() {
        int flags = 0;
        for (int i = 0; i < SEASONING_SLOTS; i++) {
            if (seasoningEnabled[i]) flags |= (1 << i);
        }
        return flags;
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

    public int getBaseProcessCount() {
        return MekckConfig.getMultithreadedBase(tier);
    }

    // ================== 下单系统（含调味） ==================

    /**
     * 当前订单调味料在 BarbequesDelightCompat.getAllSeasonings() 中的下标；
     * 无订单调味料返回 -1。用于客户端 ContainerData 同步。
     */
    private int orderSeasoningIndex() {
        if (orderSeasoning == null || orderSeasoning.isEmpty()) return -1;
        List<BarbequesDelightCompat.SeasoningInfo> all = BarbequesDelightCompat.getAllSeasonings();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(orderSeasoning)) return i;
        }
        return -1;
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

    /** 当前订单的调味料名（大写枚举名），无则返回 null。 */
    @Nullable
    public String getOrderSeasoning() {
        return orderSeasoning;
    }

    /**
     * 设置订单（配方 + 数量 + 可选调味料）。recipeId 为 null 表示取消订单。
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

    public void setOrder(@Nullable ResourceLocation recipeId, int quantity, @Nullable String seasoningId) {
        this.orderRecipeId = recipeId;
        this.orderQuantity = Math.max(0, quantity);
        this.orderCompleted = 0;
        this.orderSeasoning = (recipeId == null || seasoningId == null || seasoningId.isEmpty())
                ? null : seasoningId;
        setChanged();
    }

    /**
     * 供下单 GUI 展示：当前材料可加工的全部烧烤配方（按输入槽匹配，去重）。
     */
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> available = new ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();

        ResourceLocation grillingTypeId = new ResourceLocation("barbequesdelight", "grilling");
        RecipeType<?> grillingType = cn.ism.mekck.util.RecipeCache.type(grillingTypeId);
        if (grillingType == null) return available;

        // 输入槽 + 存储槽（下单模式材料存放在存储区）
        int[] scanSlots = new int[inputSlots + STORAGE_SLOTS];
        for (int i = 0; i < inputSlots; i++) scanSlots[i] = i;
        for (int i = 0; i < STORAGE_SLOTS; i++) scanSlots[inputSlots + i] = storageSlotStart + i;
        for (int recipeIndex : scanSlots) {
            ItemStack stack = items.getStackInSlot(recipeIndex);
            if (stack.isEmpty()) continue;
            for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, grillingType)) {
                if (seen.add(recipe.getId()) && matchesInput(recipe, stack)) {
                    available.add(recipe);
                }
            }
        }
        for (KaleidoscopeGrillingCompat.VirtualRecipe vr : KaleidoscopeGrillingCompat.getGrillingVirtualRecipes()) {
            if (seen.add(vr.getId())) {
                for (int slotIndex : scanSlots) {
                    ItemStack st = items.getStackInSlot(slotIndex);
                    if (!st.isEmpty() && matchesInput(vr, st)) { available.add(vr); break; }
                }
            }
        }
        return available;
    }

    /**
     * 供下单 GUI 的 Max 按钮：当前材料 + 调味料耐久下最多可完成的份数
     * （保守上界：逐输入槽求和）。
     */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        int base = getBaseProcessCount() * getStackMultiplier();
        boolean hasOrderSeasoning = orderSeasoning != null && !orderSeasoning.isEmpty();
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) return 0;

        int total = 0;
        int[] scanSlots = new int[inputSlots + STORAGE_SLOTS];
        for (int i = 0; i < inputSlots; i++) scanSlots[i] = i;
        for (int i = 0; i < STORAGE_SLOTS; i++) scanSlots[inputSlots + i] = storageSlotStart + i;
        for (int slotIndex : scanSlots) {
            ItemStack input = items.getStackInSlot(slotIndex);
            if (!input.isEmpty() && matchesInput(recipe, input)) {
                int n = Math.min(base, input.getCount());
                if (hasOrderSeasoning && BarbequesDelightCompat.isSeasonable(result)) {
                    n = Math.min(n, availableSeasoningUses(orderSeasoning) / Math.max(1, result.getCount()));
                }
                total += Math.max(0, n);
            }
        }
        return total;
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

    /**
     * Distributes items across all input slots based on auto-distribute logic.
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

    /**
     * 是否任意升级物品（速度/能量/堆叠/创造/气体）。通用输入槽禁止放入升级物品，
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
                new ResourceLocation("barbequesdelight", "grilling"));
    }

    public ContainerData getData() {
        return data;
    }

    public CuttingMachineFactoryTier getTier() {
        return tier;
    }

    public int getInputSlots() {
        return inputSlots;
    }

    public int getOutputSlots() {
        return inputSlots;
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

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        if (customName != null) {
            return customName;
        }
        return Component.translatable("block.mekck." + getTierName() + "_grill_factory");
    }

    private String getTierName() {
        return tier.name;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new GrillFactoryMenu(containerId, inventory, this, data);
    }

    public void dropContents(Level level, BlockPos pos) {
        NonNullList<ItemStack> drops = NonNullList.create();
        for (int slot = 0; slot < items.getSlots(); slot++) {
            drops.add(items.getStackInSlot(slot));
        }
        cn.ism.mekck.util.BigStackDrops.dropAll(level, pos, drops); // 大堆叠安全：避免原版 64 分堆炸实体
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
        if (orderRecipeId != null) {
            tag.putString("OrderRecipeId", orderRecipeId.toString());
        }
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        if (orderQuantity > 0) {
            tag.putInt("OrderQuantity", orderQuantity);
            tag.putInt("OrderCompleted", orderCompleted);
        }
        if (orderSeasoning != null) {
            tag.putString("OrderSeasoning", orderSeasoning);
        }
        tag.putInt("WorkMode", workMode.ordinal());
        tag.putInt("SeasoningEnabled", seasoningFlags());
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName));
        }
        // 温度系统：热容量持久化
        if (heatComponent != null) {
            tag.put("HeatCapacitor", heatComponent.save());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        if (tag.contains("SpeedUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) speedTracker.load(tag.getCompound("SpeedUpgradeTracker"));
        if (tag.contains("EnergyUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) energyTracker.load(tag.getCompound("EnergyUpgradeTracker"));
        if (tag.contains("StackUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) stackTracker.load(tag.getCompound("StackUpgradeTracker"));
        if (tag.contains("CreativeUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) creativeTracker.load(tag.getCompound("CreativeUpgradeTracker"));
        cn.ism.mekck.util.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
        items.deserializeNBT(tag.getCompound("Items"));
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
        // 温度系统：热容量恢复
        if (heatComponent != null && tag.contains("HeatCapacitor", Tag.TAG_COMPOUND)) {
            heatComponent.load(tag.getCompound("HeatCapacitor"));
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
        if (tag.contains("OrderRecipeId")) {
            orderRecipeId = ResourceLocation.tryParse(tag.getString("OrderRecipeId"));
            if (orderRecipeId == null) {
                orderQuantity = 0;
                orderCompleted = 0;
                orderSeasoning = null;
            }
        }
        if (tag.contains("OrderQuantity")) {
            orderQuantity = tag.getInt("OrderQuantity");
            orderCompleted = tag.getInt("OrderCompleted");
        }
        if (tag.contains("OrderSeasoning")) {
            String s = tag.getString("OrderSeasoning");
            orderSeasoning = s.isEmpty() ? null : s;
        }
        if (tag.contains("WorkMode")) {
            workMode = WorkMode.byOrdinal(tag.getInt("WorkMode"));
        }
        if (tag.contains("SeasoningEnabled")) {
            int flags = tag.getInt("SeasoningEnabled");
            for (int i = 0; i < SEASONING_SLOTS; i++) {
                seasoningEnabled[i] = (flags & (1 << i)) != 0;
            }
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
        // 温度系统：暴露 Mekanism 热能力，供热力设备（电阻加热器/热导管等）传导
        if (capability == mekanism.common.capabilities.Capabilities.HEAT_HANDLER) {
            return heatCapability.cast();
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
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        fullItemCapability = LazyOptional.of(() -> items);
        inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        storageItemCapability = LazyOptional.of(() -> new StorageItemHandler());
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
            // 默认模式：进输入格直接处理；下单模式：进存储区待下单
            if (workMode == WorkMode.ORDER) {
                return insertIntoStorage(stack, simulate);
            }
            return items.insertItem(slot, stack, simulate);
        }

        private ItemStack insertIntoStorage(ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return stack;
            ItemStack remaining = stack.copy();
            for (int s = storageSlotStart; s < storageSlotStart + STORAGE_SLOTS && !remaining.isEmpty(); s++) {
                ItemStack current = items.getStackInSlot(s);
                if (current.isEmpty() || ItemStack.isSameItemSameTags(current, remaining)) {
                    remaining = items.insertItem(s, remaining, simulate);
                }
            }
            return remaining;
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

    /** 存储空间专用能力：只允许把物品放进存储槽（PULL_INPUT_STORAGE 侧面）。 */
    private final class StorageItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return STORAGE_SLOTS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= STORAGE_SLOTS) return ItemStack.EMPTY;
            return items.getStackInSlot(storageSlotStart + slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= STORAGE_SLOTS) return stack;
            return items.insertItem(storageSlotStart + slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot < 0 || slot >= STORAGE_SLOTS) return 0;
            return items.getSlotLimit(storageSlotStart + slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot < 0 || slot >= STORAGE_SLOTS) return false;
            return items.isItemValid(storageSlotStart + slot, stack);
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