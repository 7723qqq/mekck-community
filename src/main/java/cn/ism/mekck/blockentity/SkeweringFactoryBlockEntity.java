package cn.ism.mekck.blockentity;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.block.SkeweringFactoryBlock;
import cn.ism.mekck.menu.SkeweringFactoryMenu;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.AE2Compat;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.FastTransfer;
import cn.ism.mekck.util.KaleidoscopeGrillingCompat;
import cn.ism.mekck.util.LagMonitor;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.StorageMerger;
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
import net.minecraft.world.item.Item;
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
import java.util.List;
import java.util.Optional;

public final class SkeweringFactoryBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable , cn.ism.mekck.ae2.INetworkPullable {
    public static final int INPUT_SLOTS = 3;
    public static final int BASE_ENERGY_PER_TICK = 20;
    public static final int BASE_PROCESS_TIME = 200;

    // ContainerData indices
    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_ENERGY_CAPACITY = 3;
    public static final int DATA_SIDE_CONFIG = 4;
    public static final int DATA_SPEED_UPGRADE = 5;
    public static final int DATA_ENERGY_UPGRADE = 6;
    public static final int DATA_STACK_UPGRADE = 7;
    public static final int DATA_ORDER_QUANTITY = 8;
    public static final int DATA_ORDER_COMPLETED = 9;
    public static final int DATA_CREATIVE_UPGRADE = 10;
    public static final int DATA_REDSTONE_CONTROL = 11;
    public static final int DATA_SIZE = 12;

    private final CuttingMachineFactoryTier tier;
    private final int storageSlots;
    private final int totalSlots;
    private final int outputSlot;
    private final int returnSlot;
    private final int speedUpgradeSlot;
    private final int energyUpgradeSlot;
    private final int storageSlotStart;
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

    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    // PULSE 模式：收到红石信号(上升沿)后锁存为 true，完成一次完整处理后复位。
    private boolean pulseRunning = false;

    public final ItemStackHandler items;
    private final EnergyStorage energy;

    private final SideMode[] sideConfig = new SideMode[6];

    // Order system
    private ResourceLocation orderRecipeId;
    /** 自定义组合穿串订单的材料（烟火未完成烤串）。 */
    private java.util.List<ItemStack> orderCustomIngredients = java.util.List.of();
    private int orderQuantity; // total quantity to produce
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true; // how many have been produced so far

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
                case DATA_ORDER_QUANTITY -> orderQuantity;
                case DATA_ORDER_COMPLETED -> orderCompleted;
                case DATA_CREATIVE_UPGRADE -> hasCreativeUpgrade() ? 1 : 0;
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

    public SkeweringFactoryBlockEntity(CuttingMachineFactoryTier tier, BlockPos pos, BlockState state) {
        super(getTileType(tier), pos, state);
        this.tier = tier;
                // 上限惰性读取 MekckConfig，故 /reload 改配置后立即生效。
                // 必须在构造器体内初始化：tier 在此处才保证已赋值，字段初始化器里无法引用。
                this.speedTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactorySpeedUpgradeMax(tier));
                this.energyTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryEnergyUpgradeMax(tier));
                this.stackTracker = new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getFactoryStackUpgradeMax(tier));
        this.storageSlots = 81;
        this.hasStackUpgrade = tier.supportsStackUpgrade();

        // Slot layout:
        // 0-2: input slots (3 inputs)
        // 3: output slot
        // 4: return slot
        // 5: speed upgrade
        // 6: energy upgrade
        // 7 to 7+storageSlots-1: storage slots (81 slots)
        // stackUpgradeSlot: 7 + storageSlots (only if hasStackUpgrade)
        // creativeUpgradeSlot: last slot
        this.outputSlot = INPUT_SLOTS;
        this.returnSlot = INPUT_SLOTS + 1;
        this.speedUpgradeSlot = INPUT_SLOTS + 2;
        this.energyUpgradeSlot = INPUT_SLOTS + 3;
        this.storageSlotStart = INPUT_SLOTS + 4;
        this.stackUpgradeSlot = hasStackUpgrade ? storageSlotStart + storageSlots : -1;
        this.creativeUpgradeSlot = hasStackUpgrade ? stackUpgradeSlot + 1 : storageSlotStart + storageSlots;
        // 能源槽位（能量物品），追加在末尾
        this.powerSlot = INPUT_SLOTS + storageSlots + 2 + (hasStackUpgrade ? 4 : 3);
        this.totalSlots = this.powerSlot + 1;

        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
        }

        this.items = new cn.ism.mekck.util.BigStackItemHandler(totalSlots) {
            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                if (slot < INPUT_SLOTS) {
                    // 输入槽只接受普通食材，不允许放入任何升级物品
                    return !isAnyUpgradeItem(stack) && RecipeInputMatcher.matchesSkewering(level, stack);
                }
                if (slot == outputSlot || slot == returnSlot) {
                    // Output and return slots: cannot insert
                    return false;
                }
                if (slot >= storageSlotStart && slot < storageSlotStart + storageSlots) {
                    // 存储槽只接受普通物品，不允许放入任何升级物品
                    return !isAnyUpgradeItem(stack);
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
                // Input slots: unlimited
                if (slot < INPUT_SLOTS) {
                    return Integer.MAX_VALUE;
                }
                // Output and return slots: unlimited
                if (slot == outputSlot || slot == returnSlot) {
                    return Integer.MAX_VALUE;
                }
                // Storage slots: unlimited
                if (slot >= storageSlotStart && slot < storageSlotStart + storageSlots) {
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
                if (slot < INPUT_SLOTS || slot == outputSlot || slot == returnSlot || (slot >= storageSlotStart && slot < storageSlotStart + storageSlots)) {
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
                // Input slots: unlimited
                if (slot < INPUT_SLOTS) {
                    return getSlotLimit(slot);
                }
                // Output and return slots: unlimited
                if (slot == outputSlot || slot == returnSlot) {
                    return getSlotLimit(slot);
                }
                // Storage slots: unlimited
                if (slot >= storageSlotStart && slot < storageSlotStart + storageSlots) {
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

    private static BlockEntityType<SkeweringFactoryBlockEntity> getTileType(CuttingMachineFactoryTier tier) {
        // 直接查注册表，不再逐个 case 列等级。
        // 原先的 switch 只列了 11 个等级、**漏了 BLAZE** ⇒ 放置烈焰等级工厂时落到
        // default 抛 IllegalArgumentException 崩溃（2026-09-16 用户实测：放置烈焰切菜机必崩）。
        // 改为查表后，将来新增等级无需再改这里（制冰工厂一直是这么做的）。
        return cn.ism.mekck.UniversalCuttingMachine.SKEWERING_FACTORY_BLOCK_ENTITIES.get(tier).get();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SkeweringFactoryBlockEntity machine) {
        // 升级读条：槽位放入升级后 20 tick 安装一次
        machine.tickUpgradeTrackers();
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
                if (machine.canFitAll(result, multiplier)) {
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
            machine.progress++;

            if (machine.progress >= effectiveProcessTime) {
                Recipe<?> recipe = recipeOpt.get();
                int multiplier = Math.min(maxConsumable, stackMult);
                if (machine.orderQuantity > 0) {
                    multiplier = Math.min(multiplier, machine.orderQuantity - machine.orderCompleted);
                }
                machine.consumeIngredients(recipe, multiplier);
                machine.completeRecipe(recipe, multiplier);

                // Update order tracking
                if (machine.orderQuantity > 0) {
                    machine.orderCompleted += multiplier;
                    if (machine.orderCompleted >= machine.orderQuantity) {
                            machine.orderQuantity = 0;
                            machine.orderCompleted = 0;
                            machine.orderRecipeId = null;
                            machine.orderCustomIngredients = java.util.List.of();
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
        if (LagMonitor.shouldRunIO(level.getGameTime(), pos)) machine.autoIO(level, pos);
        // 存储空间自动合并：相同物品合并至靠前的格子
        if (StorageMerger.shouldRunMerge(level.getGameTime(), pos, 20)) {
            if (StorageMerger.merge(machine.items, machine.storageSlotStart, machine.storageSlots)) {
                machine.setChanged();
            }
        }

        // AE2 联动：网格节点生命周期 / 动态配方刷新 / 任务回写（未安装 AE2 时为空操作）
        AE2Compat.serverTick(machine, level, pos);

        // Update block state active property
        boolean isActive = machine.progress > 0;
        if (wasActive != isActive) {
            level.setBlock(pos, state.setValue(SkeweringFactoryBlock.ACTIVE, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, SkeweringFactoryBlockEntity machine) {
        if (state.getValue(SkeweringFactoryBlock.ACTIVE)) {
            SoundHandler.startTileSound(MekanismSounds.ENRICHMENT_CHAMBER.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
        } else {
            SoundHandler.stopTileSound(pos);
        }
    }

    private RecipeType<?> getSkeweringRecipeType() {
        ResourceLocation id = new ResourceLocation("barbequesdelight", "skewering");
        return cn.ism.mekck.util.RecipeCache.type(id);
    }

    /**
     * Finds a matching skewering recipe.
     * Checks input slots (0-2) for the recipe matching.
     * If an order is active, only checks the ordered recipe.
     * Items from storage can be used to fill empty input slots.
     */
    @SuppressWarnings("unchecked")
    private Optional<Recipe<?>> findRecipe() {
        RecipeType<?> recipeType = getSkeweringRecipeType();
        if (recipeType == null) return Optional.empty();

        // Get items from input slots, supplemented by storage if empty.
        // Each empty input slot gets a DIFFERENT storage item (track used slots).
        ItemStack[] inputStacks = new ItemStack[INPUT_SLOTS];
        boolean[] usedStorageSlots = new boolean[storageSlots];
        for (int i = 0; i < INPUT_SLOTS; i++) {
            inputStacks[i] = items.getStackInSlot(i);
            if (inputStacks[i].isEmpty()) {
                for (int j = storageSlotStart; j < storageSlotStart + storageSlots; j++) {
                    int storageIndex = j - storageSlotStart;
                    if (usedStorageSlots[storageIndex]) continue;
                    ItemStack storageStack = items.getStackInSlot(j);
                    if (!storageStack.isEmpty()) {
                        inputStacks[i] = storageStack;
                        usedStorageSlots[storageIndex] = true;
                        break;
                    }
                }
            }
        }

        // If an order is active, only check the ordered recipe
        if (orderRecipeId != null) {
            for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, recipeType)) {
                if (recipe.getId().equals(orderRecipeId) && matchesSkewering(recipe, inputStacks)) {
                    return Optional.of(recipe);
                }
            }
            // 森罗物语：烟火固定穿串配方（虚拟配方）
            for (KaleidoscopeGrillingCompat.VirtualRecipe vr : KaleidoscopeGrillingCompat.getThreadingVirtualRecipes()) {
                if (vr.getId().equals(orderRecipeId) && matchesSkewering(vr, inputStacks)) {
                    return Optional.of(vr);
                }
            }
            // 自定义组合穿串
            if (!orderCustomIngredients.isEmpty()) {
                KaleidoscopeGrillingCompat.VirtualRecipe custom = buildCustomThreadingRecipe();
                if (custom != null && matchesSkewering(custom, inputStacks)) {
                    return Optional.of(custom);
                }
            }
            return Optional.empty();
        }

        // No order set - do not auto-process
        return Optional.empty();
    }

    /**
     * Matches a skewering recipe using reflection to access tool, ingredient, side fields.
     * slot 0 -> tool, slot 1 -> ingredient, slot 2 -> side
     */
    private boolean matchesSkewering(Recipe<?> recipe, ItemStack[] inputStacks) {
        try {
            Ingredient tool = getIngredientField(recipe, "tool");
            Ingredient ingredient = getIngredientField(recipe, "ingredient");
            Ingredient side = getIngredientField(recipe, "side");

            // Position-independent matching against the ENTIRE machine inventory
            // (input slots + storage), consistent with getMaxConsumableCount. This
            // ensures ordering always starts crafting when the required materials are
            // present anywhere, regardless of which slot they occupy. Using only the
            // 3 input stacks could fail when a non-matching item occupies an input slot
            // or when materials are spread across storage.
            boolean hasTool = false;
            boolean hasIngredient = false;
            boolean hasSide = false;
            for (int j = 0; j < INPUT_SLOTS; j++) {
                ItemStack stack = items.getStackInSlot(j);
                if (stack.isEmpty()) {
                    continue;
                }
                if (tool != null && !tool.isEmpty() && tool.test(stack)) {
                    hasTool = true;
                }
                if (ingredient != null && !ingredient.isEmpty() && ingredient.test(stack)) {
                    hasIngredient = true;
                }
                if (side != null && !side.isEmpty() && side.test(stack)) {
                    hasSide = true;
                }
            }
            for (int j = storageSlotStart; j < storageSlotStart + storageSlots; j++) {
                ItemStack stack = items.getStackInSlot(j);
                if (stack.isEmpty()) {
                    continue;
                }
                if (tool != null && !tool.isEmpty() && tool.test(stack)) {
                    hasTool = true;
                }
                if (ingredient != null && !ingredient.isEmpty() && ingredient.test(stack)) {
                    hasIngredient = true;
                }
                if (side != null && !side.isEmpty() && side.test(stack)) {
                    hasSide = true;
                }
            }

            if (tool != null && !tool.isEmpty() && !hasTool) {
                return false;
            }
            if (ingredient != null && !ingredient.isEmpty() && !hasIngredient) {
                return false;
            }
            if (side != null && !side.isEmpty() && !hasSide) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets count of items needed from a field (ingredientCount or sideCount).
     */
    public static int getCountField(Recipe<?> recipe, String fieldName) {
        try {
            java.lang.reflect.Field field = cn.ism.mekck.util.Reflect.field(recipe.getClass(), fieldName);
            if (field == null) return 1;
            return field.getInt(recipe);
        } catch (Exception e) {
            return 1;
        }
    }

    public static Ingredient getIngredientField(Recipe<?> recipe, String fieldName) {
        try {
            java.lang.reflect.Field field = cn.ism.mekck.util.Reflect.field(recipe.getClass(), fieldName);
            if (field == null) return null;
            return (Ingredient) field.get(recipe);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the maximum number of recipe sets that can be consumed.
     * Checks input slots first, then storage slots.
     */
    private int getMaxConsumableCount(Recipe<?> recipe) {
        try {
            Ingredient tool = getIngredientField(recipe, "tool");
            Ingredient ingredient = getIngredientField(recipe, "ingredient");
            Ingredient side = getIngredientField(recipe, "side");
            int toolCount = getCountField(recipe, "ingredientCount");
            int sideCount = getCountField(recipe, "sideCount");

            // Collect items from input slots + storage for matching
            List<ItemStack> availableItems = new ArrayList<>();
            List<Integer> slotIndices = new ArrayList<>();
            for (int j = 0; j < INPUT_SLOTS; j++) {
                ItemStack stack = items.getStackInSlot(j);
                if (!stack.isEmpty()) {
                    availableItems.add(stack);
                    slotIndices.add(j);
                }
            }
            for (int j = storageSlotStart; j < storageSlotStart + storageSlots; j++) {
                ItemStack stack = items.getStackInSlot(j);
                if (!stack.isEmpty()) {
                    availableItems.add(stack);
                    slotIndices.add(j);
                }
            }

            // Find matching items for each ingredient
            int minCount = Integer.MAX_VALUE;

            if (tool != null && !tool.isEmpty()) {
                int toolAvailable = 0;
                for (ItemStack stack : availableItems) {
                    if (tool.test(stack)) {
                        toolAvailable += stack.getCount();
                    }
                }
                if (toolAvailable < toolCount) return 0;
                minCount = Math.min(minCount, toolAvailable / toolCount);
            }

            if (ingredient != null && !ingredient.isEmpty()) {
                int ingredientAvailable = 0;
                for (ItemStack stack : availableItems) {
                    if (ingredient.test(stack)) {
                        ingredientAvailable += stack.getCount();
                    }
                }
                if (ingredientAvailable < 1) return 0;
                minCount = Math.min(minCount, ingredientAvailable);
            }

            if (side != null && !side.isEmpty()) {
                int sideAvailable = 0;
                for (ItemStack stack : availableItems) {
                    if (side.test(stack)) {
                        sideAvailable += stack.getCount();
                    }
                }
                if (sideAvailable < sideCount) return 0;
                minCount = Math.min(minCount, sideAvailable / sideCount);
            }

            return minCount == Integer.MAX_VALUE ? 0 : minCount;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Checks if the output slot can fit the recipe results.
     */
    private boolean canFitAll(ItemStack result, int multiplier) {
        // Check output slot
        if (!result.isEmpty()) {
            long totalCountLong = (long) result.getCount() * multiplier;
            if (totalCountLong > Integer.MAX_VALUE) return false;
            int totalCount = (int) totalCountLong;
            ItemStack existing = items.getStackInSlot(outputSlot);
            if (!existing.isEmpty()) {
                if (!ItemStack.isSameItemSameTags(existing, result)) return false;
                if ((long) existing.getCount() + totalCount > Integer.MAX_VALUE) return false;
            }
        }
        return true;
    }

    /**
     * Consumes ingredients from input slots + storage slots for the given recipe.
     * Prefers input slots first, then storage slots.
     */
    private void consumeIngredients(Recipe<?> recipe, int consumeCount) {
        if (consumeCount <= 0) return;

        try {
            Ingredient tool = getIngredientField(recipe, "tool");
            Ingredient ingredient = getIngredientField(recipe, "ingredient");
            Ingredient side = getIngredientField(recipe, "side");
            int toolCount = getCountField(recipe, "ingredientCount");
            int sideCount = getCountField(recipe, "sideCount");

            for (int unit = 0; unit < consumeCount; unit++) {
                // Consume tool
                if (tool != null && !tool.isEmpty()) {
                    consumeIngredient(tool, toolCount);
                }

                // Consume ingredient
                if (ingredient != null && !ingredient.isEmpty()) {
                    consumeIngredient(ingredient, 1);
                }

                // Consume side
                if (side != null && !side.isEmpty()) {
                    consumeIngredient(side, sideCount);
                }
            }
        } catch (Exception e) {
            // Fallback: skip
        }
    }

    /**
     * Consumes a specific ingredient from input slots first, then storage slots.
     */
    private void consumeIngredient(Ingredient ingredient, int count) {
        int remaining = count;
        // Check input slots first
        for (int i = 0; i < INPUT_SLOTS && remaining > 0; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                int toExtract = Math.min(remaining, stack.getCount());
                items.extractItem(i, toExtract, false);
                remaining -= toExtract;
            }
        }
        // Then check storage slots
        for (int i = storageSlotStart; i < storageSlotStart + storageSlots && remaining > 0; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                int toExtract = Math.min(remaining, stack.getCount());
                items.extractItem(i, toExtract, false);
                remaining -= toExtract;
            }
        }
    }

    /**
     * Produces output and return items for the recipe.
     */
    private void completeRecipe(Recipe<?> recipe, int multiplier) {
        // Produce output
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (!result.isEmpty()) {
            long totalCountLong = (long) result.getCount() * multiplier;
            int totalCount = totalCountLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCountLong;
            ItemStack multiplied = result.copy();
            multiplied.setCount(totalCount);
            insertIntoSlot(outputSlot, multiplied);
        }

        // Return the tool item (from input slot 0) to return slot
        ItemStack toolStack = items.getStackInSlot(0);
        if (!toolStack.isEmpty()) {
            ItemStack returnStack = toolStack.copy();
            returnStack.setCount(cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT, 1, multiplier));
            insertIntoSlot(returnSlot, returnStack);
        }
    }

    private void insertIntoSlot(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.getSlots()) return;
        ItemStack existing = items.getStackInSlot(slot);
        if (existing.isEmpty()) {
            items.setStackInSlot(slot, stack);
        } else if (ItemStack.isSameItemSameTags(existing, stack)) {
            long total = (long) existing.getCount() + stack.getCount();
            int merged = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
            existing.setCount(merged);
            items.setStackInSlot(slot, existing);
        }
    }

    /**
     * Gets all available skewering recipes that can be crafted with current materials.
     * Checks both input slots and storage slots. Only returns recipes where materials
     * are sufficient to craft at least one unit.
     */
    @SuppressWarnings("unchecked")
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> available = new ArrayList<>();
        RecipeType<?> recipeType = getSkeweringRecipeType();
        if (recipeType == null) return available;

        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, recipeType)) {
            if (getMaxConsumableCount(recipe) > 0) {
                available.add(recipe);
            }
        }
        // 森罗物语：烟火固定穿串配方（仅列出材料足够的）
        for (KaleidoscopeGrillingCompat.VirtualRecipe vr : KaleidoscopeGrillingCompat.getThreadingVirtualRecipes()) {
            if (getMaxConsumableCount(vr) > 0 && !available.contains(vr)) {
                available.add(vr);
            }
        }
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
        setOrder(recipeId, quantity, null);
    }

    /**
     * 设置订单（含烟火自定义组合穿串）。customIngredients 非空时为"自选组合"订单：
     * 每串消耗各材料 1 个，产物为烟火的未完成烤串（材料写入其 NBT）。
     */
    public void setOrder(@Nullable ResourceLocation recipeId, int quantity, @Nullable List<ItemStack> customIngredients) {
        this.orderRecipeId = recipeId;
        this.orderQuantity = quantity;
        this.orderCompleted = 0;
        this.orderCustomIngredients = customIngredients == null ? List.of() : List.copyOf(customIngredients);
        setChanged();
    }

    /** 自定义组合订单的材料列表（空 = 非自定义订单）。 */
    public List<ItemStack> getOrderCustomIngredients() {
        return orderCustomIngredients;
    }

    /** 构造自定义组合穿串的虚拟配方。 */
    @Nullable
    private KaleidoscopeGrillingCompat.VirtualRecipe buildCustomThreadingRecipe() {
        if (orderCustomIngredients.isEmpty()) return null;
        return KaleidoscopeGrillingCompat.makeCustomThreadingRecipe(orderCustomIngredients, 1);
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

    private AutoIO autoIO;

    private void autoIO(Level level, BlockPos pos) {
        if (autoIO == null) {
            autoIO = new AutoIO(this,
                    new int[][]{{0, INPUT_SLOTS}},
                    new int[][]{{storageSlotStart, storageSlots}},
                    new int[][]{{outputSlot, 1}, {returnSlot, 1}});
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
        return (int) Math.min(raw, Math.max(base, (long) maxMult * base));
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
    @Override public int[] getInputSlotRange() { return new int[]{0, INPUT_SLOTS}; }
    @Override public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() { return items; }
    @Override public boolean supportsAutoPull() { return true; } // ME 持续补料：按"每类型上限"（配置 auto_pull_stack_limit）批量补，受 LagMonitor 限流

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) return List.of();
        return cn.ism.mekck.util.NetworkPullHelper.currentOrUnion(level, items.getStackInSlot(0),
                new ResourceLocation("barbequesdelight", "skewering"));
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
        return outputSlot;
    }

    public int getReturnSlot() {
        return returnSlot;
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

    public int getStorageSlotStart() {
        return storageSlotStart;
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
        return Component.translatable("block.mekck." + tier.name + "_skewering_factory");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SkeweringFactoryMenu(containerId, inventory, this, data);
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
        if (!orderCustomIngredients.isEmpty()) {
            net.minecraft.nbt.ListTag custom = new net.minecraft.nbt.ListTag();
            for (ItemStack ing : orderCustomIngredients) {
                ResourceLocation ingId = ForgeRegistries.ITEMS.getKey(ing.getItem());
                if (ingId != null) custom.add(net.minecraft.nbt.StringTag.valueOf(ingId.toString()));
            }
            tag.put("OrderCustomIngredients", custom);
        }
        tag.putInt("OrderQuantity", orderQuantity);
        tag.putInt("OrderCompleted", orderCompleted);
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName));
        }
        AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
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
            int ordinal = tag.getInt("RedstoneControl");
            if (ordinal >= 0 && ordinal < RedstoneControl.values().length) {
                redstoneControl = RedstoneControl.values()[ordinal];
            }
        }
        if (tag.contains("RedstonePowered")) {
            redstonePowered = tag.getBoolean("RedstonePowered");
        }
        // Load order data
        if (tag.contains("OrderRecipeId")) {
            orderRecipeId = ResourceLocation.tryParse(tag.getString("OrderRecipeId"));
        }
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        if (tag.contains("OrderCustomIngredients")) {
            java.util.List<ItemStack> custom = new ArrayList<>();
            net.minecraft.nbt.ListTag list = tag.getList("OrderCustomIngredients", net.minecraft.nbt.Tag.TAG_STRING);
            for (int ci = 0; ci < list.size(); ci++) {
                Item ing = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(list.getString(ci)));
                if (ing != null) custom.add(new ItemStack(ing));
            }
            orderCustomIngredients = custom;
        }
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

    private final class StorageItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return storageSlots;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= storageSlots) return ItemStack.EMPTY;
            return items.getStackInSlot(storageSlotStart + slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= storageSlots) return stack;
            return items.insertItem(storageSlotStart + slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot < 0 || slot >= storageSlots) return 0;
            return items.getSlotLimit(storageSlotStart + slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot < 0 || slot >= storageSlots) return false;
            return items.isItemValid(storageSlotStart + slot, stack);
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
            return 2; // output + return slots
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot == 0) {
                return items.getStackInSlot(outputSlot);
            }
            return items.getStackInSlot(returnSlot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == 0) {
                return items.extractItem(outputSlot, amount, simulate);
            }
            return items.extractItem(returnSlot, amount, simulate);
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