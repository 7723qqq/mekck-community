package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.PlantingCuttingStationBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.menu.PlantingCuttingStationMenu;
import cn.ism.mekck.recipe.PlantingCuttingRecipe;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.LagMonitor;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.UpgradeHelper;
import mekanism.client.sound.SoundHandler;
import mekanism.common.registries.MekanismSounds;
import mekanism.common.tile.interfaces.IBoundingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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

import java.util.List;
import java.util.Optional;

public final class PlantingCuttingStationBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, IBoundingBlock , cn.ism.mekck.ae2.INetworkPullable {
    public static final int INPUT_SLOT = 0;
    public static final int NUTRIENT_SLOT = 1;
    public static final int OUTPUT_SLOT = 2;
    public static final int SLOT_SPEED_UPGRADE = 3;
    public static final int SLOT_ENERGY_UPGRADE = 4;
    public static final int SLOT_CREATIVE_UPGRADE = 5;
    public static final int SLOT_GAS_UPGRADE = 6;
    public static final int SLOT_POWER = 7;
    /**
     * 「生长方块格」—— 所有配方共用 1 格。
     * <p>只有<b>神秘农业</b>种子受它约束：要求来自 BotanyPots 的 soil categories（生成器写进 plantcut 配方的
     * {@code soils} 白名单，见 {@code PlantingCuttingRecipe#getGrowthSoils()}）。其余配方的种植不受影响。</p>
     * <p>追加在最后（索引 8），老存档靠 {@code load()} 里的槽位迁移补齐。</p>
     */
    public static final int GROWTH_SLOT = 8;
    public static final int TOTAL_SLOTS = 9;
    public static final int ENERGY_CAPACITY = 100_000;
    public static final int ENERGY_PER_TICK = 20;
    public static final int PROCESS_TIME = 200;
    public static final int MAX_RECEIVE = 1_000;

    // ContainerData indices
    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    /**
     * {@link #DATA_ENERGY} 的<b>高 16 位</b>。
     *
     * <p><b>这个槽原本是 {@code DATA_ENERGY_CAPACITY}，现已改用途。</b>
     * 原先这里同步 {@code energy.getMaxEnergyStored()}，而容量是本类的 static final 常量、
     * 客户端本来就知道 ⇒ 纯冗余槽，且同样会被 16 位通道截断成负数（10 万 → -31072）。
     * 现在改存能量高 16 位：<b>值不变（3）、{@code DATA_SIZE} 不动</b>，不留下死槽位、
     * 也不必重编号后面 {@code DATA_NUTRIENT} / {@code DATA_REDSTONE_CONTROL} 等下标。</p>
     *
     * <p>详见 {@link cn.ism.mekck.util.WideDataSlot}。</p>
     */
    public static final int DATA_ENERGY_HI = 3;
    public static final int DATA_SIDE_CONFIG = 4;
    public static final int DATA_SPEED_UPGRADE = 5;
    public static final int DATA_ENERGY_UPGRADE = 6;
    public static final int DATA_CREATIVE_UPGRADE = 7;
    public static final int DATA_NUTRIENT = 8;
    public static final int DATA_REDSTONE_CONTROL = 9;
    /** 生长方块格状态：见 {@link #GROWTH_OK} / {@link #GROWTH_MISSING} / {@link #GROWTH_TOO_LOW}。 */
    public static final int DATA_GROWTH_STATUS = 10;
    /** 要求的生长方块档位（0~4 = 神秘农业五级；-1 = 无要求 / 未知），只用于 GUI 文案。 */
    public static final int DATA_GROWTH_TIER = 11;
    public static final int DATA_SIZE = 12;

    /** 生长方块格状态口径（同步给 GUI）。 */
    public static final int GROWTH_OK = 0;
    public static final int GROWTH_MISSING = 1;
    public static final int GROWTH_TOO_LOW = 2;

    /** 神秘农业五级耕地名（BotanyPots 的 category 名与之一致；仅供 GUI 文案与档位换算）。 */
    public static final String[] GROWTH_TIER_NAMES = {"inferium", "prudentium", "tertium", "imperium", "supremium"};

    private Component customName;
    private int progress;
    private double nutrientAccumulator;
    /** 生长方块格状态（每 tick 由 serverTick 重算；GUI 通过 ContainerData 读）。 */
    private int growthStatus = GROWTH_OK;
    /** 当前配方要求的生长方块档位（-1 = 无要求）。 */
    private int growthTierIndex = -1;

    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    // PULSE 模式：收到红石信号(上升沿)后锁存为 true，完成一次完整处理后复位。
    private boolean pulseRunning = false;

    private final SideMode[] sideConfig = new SideMode[6];

    private final ItemStackHandler items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (slot == INPUT_SLOT) {
                // 种子格只接受 plantcut 配方的种子
                return RecipeInputMatcher.matchesPlantingSeed(level, stack);
            }
            if (slot == NUTRIENT_SLOT) {
                return true;
            }
            if (slot == OUTPUT_SLOT) {
                return false;
            }
            if (slot == SLOT_SPEED_UPGRADE) {
                return isSpeedUpgrade(stack);
            }
            if (slot == SLOT_ENERGY_UPGRADE) {
                return isEnergyUpgrade(stack);
            }
            if (slot == SLOT_CREATIVE_UPGRADE) {
                return isCreativeUpgrade(stack);
            }
            if (slot == SLOT_GAS_UPGRADE) {
                return isGasUpgrade(stack);
            }
            if (slot == SLOT_POWER) {
                return PowerSlotUtil.isValidEnergyItem(stack);
            }
            if (slot == GROWTH_SLOT) {
                // 生长方块格：什么都收 —— 非神秘农业配方无视它，神秘农业种子则由配方白名单判合格与否
                return true;
            }
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot == INPUT_SLOT || slot == GROWTH_SLOT) {
                return 1;
            }
            if (slot == OUTPUT_SLOT) {
                return Integer.MAX_VALUE;
            }
            if (slot == SLOT_CREATIVE_UPGRADE || slot == SLOT_GAS_UPGRADE) {
                return 1;
            }
            if (slot == SLOT_POWER) {
                return 64;
            }
            return MekckConfig.getBasicSpeedUpgradeMax();
        }

        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            if (slot == INPUT_SLOT || slot == GROWTH_SLOT) {
                return 1;
            }
            if (slot == OUTPUT_SLOT) {
                return getSlotLimit(slot);
            }
            if (slot == SLOT_CREATIVE_UPGRADE || slot == SLOT_GAS_UPGRADE) {
                return getSlotLimit(slot);
            }
            return super.getStackLimit(slot, stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final EnergyStorage energy = new EnergyStorage(ENERGY_CAPACITY, MAX_RECEIVE, ENERGY_PER_TICK) {
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

    private LazyOptional<IItemHandler> fullItemCapability;
    private LazyOptional<IItemHandler> inputItemCapability;
    private LazyOptional<IItemHandler> outputItemCapability;
    private LazyOptional<IEnergyStorage> energyCapability;

    private final ContainerData data = new ContainerData() {
        private int[] stored;

        private int[] getStored() {
            if (stored == null) {
                stored = new int[DATA_SIZE];
            }
            return stored;
        }

        @Override
        public int get(int index) {
            // On client side, return the cached value from server sync
            if (level != null && level.isClientSide) {
                if (stored != null && index >= 0 && index < stored.length) {
                    return stored[index];
                }
                return 0;
            }
            int value = switch (index) {
                case DATA_PROGRESS -> progress;
                case DATA_PROCESS_TIME -> getEffectiveProcessTime();
                // 能量拆两槽：writeShort 只送低 16 位且会符号扩展，见 WideDataSlot。
                case DATA_ENERGY -> energy.getEnergyStored() & 0xFFFF;
                case DATA_ENERGY_HI -> (energy.getEnergyStored() >>> 16) & 0xFFFF;
                case DATA_SIDE_CONFIG -> encodeSideConfig();
                case DATA_SPEED_UPGRADE -> getSpeedUpgradeCount();
                case DATA_ENERGY_UPGRADE -> getEnergyUpgradeCount();
                case DATA_CREATIVE_UPGRADE -> hasCreativeUpgrade() ? 1 : 0;
                case DATA_NUTRIENT -> hasNutrient() ? 1 : 0;
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
                case DATA_GROWTH_STATUS -> growthStatus;
                case DATA_GROWTH_TIER -> growthTierIndex;
                default -> 0;
            };
            getStored()[index] = value;
            return value;
        }

        @Override
        public void set(int index, int value) {
            getStored()[index] = value;
            if (index == 0) {
                progress = value;
            }
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    public PlantingCuttingStationBlockEntity(BlockPos pos, BlockState state) {
        super(UniversalCuttingMachine.PLANTING_CUTTING_STATION_BLOCK_ENTITY.get(), pos, state);
        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
        }
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PlantingCuttingStationBlockEntity machine) {
        boolean wasActive = machine.progress > 0;
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.util.AE2Compat.serverTick(machine, level, pos);

        boolean changed = false;

        // Update redstone powered state (Mekanism updatePower equivalent)
        machine.updateRedstone();

        // Drain energy from the power slot (energy cube / tablet / redstone) into the machine
        if (machine.drainPowerSlot()) {
            changed = true;
        }

        // Creative upgrade: fill energy to max, no consumption, 1 tick process time
        boolean hasCreative = machine.hasCreativeUpgrade();
        if (hasCreative) {
            machine.energy.receiveEnergy(machine.energy.getMaxEnergyStored() - machine.energy.getEnergyStored(), false);
        }

        double speedMult = machine.getEffectiveSpeedMultiplier();
        double energyConsumptionMult = machine.getEffectiveEnergyConsumptionMultiplier();
        int effectiveProcessTime = hasCreative ? 1 : machine.getEffectiveProcessTime();
        int energyPerTick = hasCreative ? 0 : (int) Math.ceil(ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);

        ItemStack seed = machine.items.getStackInSlot(INPUT_SLOT);
        boolean hasNutrient = machine.hasNutrient();

        Optional<PlantingCuttingRecipe> plantingRecipe = machine.findPlantingRecipe(level, seed);

        // PULSE 模式：收到红石信号(上升沿)时锁存，机器开始一次完整的处理
        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        // 生长方块格：每 tick 重算状态（0 = 无需/已满足，1 = 缺方块，2 = 等级不足），并作为开工条件之一
        machine.updateGrowthStatus(plantingRecipe.orElse(null));

        boolean canOperate = machine.canFunctionRedstone();
        if (canOperate && plantingRecipe.isPresent() && hasNutrient && !seed.isEmpty()
                && machine.energy.getEnergyStored() >= energyPerTick
                && machine.growthStatus == GROWTH_OK
                && machine.canFitOutputs(level, plantingRecipe.get())) {
            machine.energy.extractEnergy(energyPerTick, false);
            machine.progress++;
            if (machine.progress >= effectiveProcessTime) {
                machine.completeRecipe(level, plantingRecipe.get());
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

        // Update block state active property for sound synchronization
        boolean isActive = machine.progress > 0;
        if (wasActive != isActive) {
            level.setBlock(pos, state.setValue(PlantingCuttingStationBlock.ACTIVE, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, PlantingCuttingStationBlockEntity machine) {
        if (state.getValue(PlantingCuttingStationBlock.ACTIVE)) {
            SoundHandler.startTileSound(MekanismSounds.PRECISION_SAWMILL.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
        } else {
            SoundHandler.stopTileSound(pos);
        }
    }

    // ────────────── 生长方块格（神秘农业种子按 BotanyPots 的 categories 判定） ──────────────

    /**
     * 每 tick 重算生长方块格状态与档位（只在服务端跑；GUI 通过 ContainerData 读到）。
     * <p>判定用配方里生成好的 {@code soils} 白名单 —— <b>运行时不需要反射 BotanyPots</b>。</p>
     */
    private void updateGrowthStatus(PlantingCuttingRecipe recipe) {
        if (recipe == null || !recipe.requiresGrowthSoil()) {
            this.growthStatus = GROWTH_OK;
            this.growthTierIndex = -1;
            return;
        }
        this.growthTierIndex = growthTierIndexOf(recipe.getRequiredSoilCategories());
        ItemStack soil = items.getStackInSlot(GROWTH_SLOT);
        if (soil.isEmpty()) {
            this.growthStatus = GROWTH_MISSING;
        } else {
            this.growthStatus = recipe.getGrowthSoils().test(soil) ? GROWTH_OK : GROWTH_TOO_LOW;
        }
    }

    /** 生长方块格是否满足该配方（配方没要求 ⇒ 恒 true）。供测试与外部查询。 */
    public boolean hasValidGrowthSoil(PlantingCuttingRecipe recipe) {
        return recipe == null || !recipe.requiresGrowthSoil()
                || recipe.getGrowthSoils().test(items.getStackInSlot(GROWTH_SLOT));
    }

    /** 把配方的 categories 换算成神秘农业档位下标（认不出返回 -1）。 */
    public static int growthTierIndexOf(java.util.List<String> categories) {
        if (categories == null) {
            return -1;
        }
        for (int tier = 0; tier < GROWTH_TIER_NAMES.length; tier++) {
            if (categories.contains(GROWTH_TIER_NAMES[tier])) {
                return tier;
            }
        }
        return -1;
    }

    // ────────────── Recipe finding ──────────────

    /** 复用的单槽包装器（种子匹配用，避免每 tick 分配 ItemStackHandler + RecipeWrapper）。 */
    private final ItemStack[] seedSlotStack = new ItemStack[]{ItemStack.EMPTY};
    private final RecipeWrapper seedSlotWrapper = new RecipeWrapper(new ItemStackHandler(1) {
        @Override
        public int getSlots() {
            return 1;
        }

        @NotNull
        @Override
        public ItemStack getStackInSlot(int slot) {
            return seedSlotStack[0];
        }

        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack s) {
            seedSlotStack[0] = s;
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
    });

    private Optional<PlantingCuttingRecipe> findPlantingRecipe(Level level, ItemStack seed) {
        if (seed.isEmpty()) return Optional.empty();
        RecipeType<PlantingCuttingRecipe> plantCutType = UniversalCuttingMachine.PLANTING_CUTTING_RECIPE_TYPE.get();
        if (plantCutType == null) return Optional.empty();
        // 复用单槽包装器：原先每次都 new ItemStackHandler + RecipeWrapper（该方法在 tick 路径上）
        seedSlotStack[0] = seed;
        return level.getRecipeManager().getRecipeFor(plantCutType, seedSlotWrapper, level);
    }

    /**
     * Check if the recipe's outputs can all fit in the output slot.
     */
    private boolean canFitOutputs(Level level, PlantingCuttingRecipe recipe) {
        NonNullList<ItemStack> allResults = getFinalOutputs(recipe);
        if (allResults.isEmpty()) return false;

        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT).copy();
        for (ItemStack result : allResults) {
            ItemStack remainder = tryInsert(existing, result.copy());
            if (!remainder.isEmpty()) return false;
        }
        return true;
    }

    /**
     * Get all output items from the recipe (main results + secondary results, if chance applies).
     */
    private NonNullList<ItemStack> getFinalOutputs(PlantingCuttingRecipe recipe) {
        NonNullList<ItemStack> outputs = NonNullList.create();

        // Main results (already pre-computed with cutting if applicable)
        for (ItemStack result : recipe.getResults()) {
            outputs.add(result.copy());
        }

        // Secondary results (with chance)
        if (level != null && level.random.nextFloat() < recipe.getSecondaryChance()) {
            for (ItemStack result : recipe.getSecondaryResults()) {
                outputs.add(result.copy());
            }
        }

        return outputs;
    }

    private void completeRecipe(Level level, PlantingCuttingRecipe recipe) {
        // Seed is not consumed (acts as a catalyst)
        // Consume nutrient
        consumeNutrient();

        // Get final outputs and insert
        NonNullList<ItemStack> outputs = getFinalOutputs(recipe);
        for (ItemStack output : outputs) {
            insertOutput(output.copy());
        }
    }

    private void insertOutput(ItemStack stack) {
        for (int slot = OUTPUT_SLOT; slot <= OUTPUT_SLOT && !stack.isEmpty(); slot++) {
            ItemStack existing = items.getStackInSlot(slot);
            if (existing.isEmpty()) {
                int moved = Math.min(stack.getCount(), items.getSlotLimit(slot));
                ItemStack inserted = stack.copy();
                inserted.setCount(moved);
                items.setStackInSlot(slot, inserted);
                stack.shrink(moved);
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                int limit = items.getSlotLimit(slot);
                int space = limit - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(stack.getCount(), space);
                    existing.grow(moved);
                    items.setStackInSlot(slot, existing);
                    stack.shrink(moved);
                }
            }
        }
    }

    private static ItemStack tryInsert(ItemStack existing, ItemStack stack) {
        ItemStack remainder = stack.copy();
        if (existing.isEmpty()) {
            return ItemStack.EMPTY; // fits
        } else if (ItemStack.isSameItemSameTags(existing, remainder)) {
            int space = Integer.MAX_VALUE - existing.getCount();
            if (remainder.getCount() <= space) {
                return ItemStack.EMPTY;
            }
            remainder.shrink(space);
        }
        return remainder;
    }

    // ────────────── Nutrient ──────────────

    public boolean hasNutrient() {
        // If gas upgrade eliminates consumption, always return true
        if (getGasConsumptionMultiplier() == 0.0) return true;
        return !items.getStackInSlot(NUTRIENT_SLOT).isEmpty();
    }

    private void consumeNutrient() {
        double mult = getGasConsumptionMultiplier();
        if (mult == 0.0) return; // No consumption needed
        nutrientAccumulator += mult;
        if (nutrientAccumulator >= 1.0) {
            int toConsume = (int) nutrientAccumulator;
            nutrientAccumulator -= toConsume;
            items.extractItem(NUTRIENT_SLOT, toConsume, false);
        }
    }

    // ────────────── Auto IO ──────────────

    private static final int[][] IO_PULL_RANGES = {{INPUT_SLOT, 1}, {NUTRIENT_SLOT, 1}};
    private static final int[][] IO_PUSH_RANGES = {{OUTPUT_SLOT, 1}};

    private final AutoIO autoIO = new AutoIO(this, IO_PULL_RANGES, IO_PUSH_RANGES);

    private void autoIO(Level level, BlockPos pos) {
        if (autoIO.run(level, pos, sideConfig, items)) setChanged();
    }

    // ────────────── Side config ──────────────

    private int encodeSideConfig() {
        int encoded = 0;
        for (int i = 0; i < 6; i++) {
            encoded |= (sideConfig[i].ordinal() << (i * 2));
        }
        return encoded;
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

    // ────────────── Upgrades ──────────────

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

    public boolean hasCreativeUpgrade() {
        return SLOT_CREATIVE_UPGRADE >= 0 && SLOT_CREATIVE_UPGRADE < items.getSlots() &&
                !items.getStackInSlot(SLOT_CREATIVE_UPGRADE).isEmpty();
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
        // Mekanism's gas upgrade
        return id.equals(ResourceLocation.tryParse("mekanism:upgrade_gas")) ||
                id.equals(ResourceLocation.tryParse("mekanism:gasupgrade"));
    }

    /**
     * Returns the gas consumption multiplier based on the installed gas upgrade.
     * <ul>
     *   <li>No upgrade: 1.0 (full consumption)</li>
     *   <li>Mekanism upgrade_gas: 0.1 (10%)</li>
     * </ul>
     */
    public double getGasConsumptionMultiplier() {
        if (SLOT_GAS_UPGRADE < 0 || SLOT_GAS_UPGRADE >= items.getSlots()) return 1.0;
        ItemStack stack = items.getStackInSlot(SLOT_GAS_UPGRADE);
        if (stack.isEmpty()) return 1.0;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return 1.0;
        String path = id.getPath();
        // Mekanism or any other gas upgrade
        return 0.1; // 10%
    }

    /**
     * 是否任意升级物品（速度/能量/堆叠/创造/气体）。通用输入/营养液槽禁止放入升级物品，
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
        return id.equals(speed) || id.equals(energy) || id.equals(stackUpgrade) || id.equals(creative) || isGasUpgrade(stack);
    }

    public int getSpeedUpgradeCount() {
        if (SLOT_SPEED_UPGRADE < items.getSlots()) {
            return items.getStackInSlot(SLOT_SPEED_UPGRADE).getCount();
        }
        return 0;
    }

    public int getEnergyUpgradeCount() {
        if (SLOT_ENERGY_UPGRADE < items.getSlots()) {
            return items.getStackInSlot(SLOT_ENERGY_UPGRADE).getCount();
        }
        return 0;
    }

    public int addUpgradesFromHand(ItemStack held) {
        return UpgradeHelper.install(items, SLOT_SPEED_UPGRADE, MekckConfig.getBasicSpeedUpgradeMax(),
                SLOT_ENERGY_UPGRADE, MekckConfig.getBasicEnergyUpgradeMax(), -1, 0,
                SLOT_CREATIVE_UPGRADE, SLOT_GAS_UPGRADE, held);
    }

    public double getEffectiveSpeedMultiplier() {
        return cn.ism.mekck.util.UpgradeHelper.speedMultiplier(getSpeedUpgradeCount());
    }

    public double getEffectiveEnergyConsumptionMultiplier() {
        return cn.ism.mekck.util.UpgradeHelper.energyConsumptionMultiplier(getEnergyUpgradeCount());
    }

    public int getEffectiveProcessTime() {
        return Math.max(1, (int) (PROCESS_TIME / getEffectiveSpeedMultiplier()));
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

    public void updateRedstone() {
        this.redstonePoweredLastTick = this.redstonePowered;
        this.redstonePowered = level != null && level.hasNeighborSignal(this.worldPosition);
    }

    public boolean canFunctionRedstone() {
        if (redstoneControl == RedstoneControl.PULSE) {
            return pulseRunning;
        }
        return redstoneControl.canFunction(redstonePowered, redstonePoweredLastTick);
    }

    // ================== 能源槽位 ==================
    public int getPowerSlot() {
        return SLOT_POWER;
    }

    public boolean drainPowerSlot() {
        if (SLOT_POWER < 0 || SLOT_POWER >= items.getSlots()) {
            return false;
        }
        ItemStack powerStack = items.getStackInSlot(SLOT_POWER);
        return PowerSlotUtil.drain(powerStack, energy, PowerSlotUtil.REDSTONE_PER_TICK);
    }

    public static boolean isUsablePowerItem(ItemStack stack) {
        return PowerSlotUtil.isValidEnergyItem(stack);
    }

    // ────────────── Getters ──────────────

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
    @Override public int[] getInputSlotRange() { return new int[]{0, INPUT_SLOT + 1}; }
    @Override public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() { return items; }
    @Override public boolean supportsAutoPull() { return true; } // ME 持续补料：按"每类型上限"（配置 auto_pull_stack_limit）批量补，受 LagMonitor 限流

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) return List.of();
        return cn.ism.mekck.util.NetworkPullHelper.currentOrUnion(level, items.getStackInSlot(0),
                new net.minecraft.resources.ResourceLocation("mekck", "plantcut"));
    }

    public ContainerData getData() {
        return data;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        return customName != null ? customName : Component.translatable("block.mekck.planting_cutting_station");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PlantingCuttingStationMenu(containerId, inventory, this, data);
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
        cn.ism.mekck.util.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        tag.put("Items", items.serializeNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Progress", progress);
        tag.putDouble("NutrientAccumulator", nutrientAccumulator);
        byte[] sideBytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            sideBytes[i] = (byte) sideConfig[i].ordinal();
        }
        tag.putByteArray("SideConfig", sideBytes);
        tag.putInt("RedstoneControl", redstoneControl.ordinal());
        tag.putBoolean("RedstonePowered", redstonePowered);
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        cn.ism.mekck.util.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
        items.deserializeNBT(tag.getCompound("Items"));
        // Ensure correct slot count for NBT migration
        if (items.getSlots() != TOTAL_SLOTS) {
            CompoundTag itemsTag = tag.getCompound("Items");
            net.minecraft.nbt.ListTag oldList = itemsTag.getList("Items", Tag.TAG_COMPOUND);
            net.minecraft.nbt.ListTag newList = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < oldList.size(); i++) {
                CompoundTag itemTags = oldList.getCompound(i);
                int slot = itemTags.getInt("Slot");
                if (slot >= 0 && slot < TOTAL_SLOTS) {
                    newList.add(itemTags);
                }
            }
            CompoundTag newTag = new CompoundTag();
            newTag.putInt("Size", TOTAL_SLOTS);
            newTag.put("Items", newList);
            items.deserializeNBT(newTag);
        }
        nutrientAccumulator = tag.getDouble("NutrientAccumulator");
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
        if (tag.contains("CustomName")) {
            customName = Component.Serializer.fromJson(tag.getString("CustomName"));
        }
        if (tag.contains("RedstoneControl")) {
            redstoneControl = RedstoneControl.byOrdinal(tag.getInt("RedstoneControl"));
        }
        if (tag.contains("RedstonePowered")) {
            redstonePowered = tag.getBoolean("RedstonePowered");
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

    // ── IBoundingBlock：把能力代理到上方绑定块面（1×2×1 多方块） ──────────────────
    // 只有主体实现 IBoundingBlock，Mekanism 绑定块才会把能力/交互代理回主块，
    // 否则日志报 "Found tile ... instead of an IBoundingBlock. Multiblock cannot function"。
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
            return 2; // input + nutrient
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot == 0) return items.getStackInSlot(INPUT_SLOT);
            return items.getStackInSlot(NUTRIENT_SLOT);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot == 0) return items.insertItem(INPUT_SLOT, stack, simulate);
            return items.insertItem(NUTRIENT_SLOT, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot == 0) return items.getSlotLimit(INPUT_SLOT);
            return items.getSlotLimit(NUTRIENT_SLOT);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == 0) return items.isItemValid(INPUT_SLOT, stack);
            return items.isItemValid(NUTRIENT_SLOT, stack);
        }
    }

    private final class OutputItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(OUTPUT_SLOT);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return items.extractItem(OUTPUT_SLOT, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(OUTPUT_SLOT);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }
}