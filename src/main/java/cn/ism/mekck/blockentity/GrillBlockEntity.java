package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.GrillBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.menu.GrillMenu;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.FastTransfer;
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

public final class GrillBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, mekanism.api.heat.IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable {
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

    public static final int INPUT_SLOT = 0;
    public static final int OUTPUT_SLOT = 1;
    public static final int SLOT_SPEED_UPGRADE = 2;
    public static final int SLOT_ENERGY_UPGRADE = 3;
    public static final int SLOT_CREATIVE_UPGRADE = 4;
    // 能源槽位（能量物品），追加在末尾
    public static final int SLOT_POWER = SLOT_CREATIVE_UPGRADE + 1;
    public static final int TOTAL_SLOTS = SLOT_POWER + 1;
    public static final int ENERGY_CAPACITY = 100_000;
    public static final int ENERGY_PER_TICK = 20;
    public static final int PROCESS_TIME = 200;
    public static final int MAX_RECEIVE = 1_000;

    public static final int DATA_CREATIVE_UPGRADE = 8;
    public static final int DATA_REDSTONE_CONTROL = 9;
    /** 机身温度（单位 0.01 ℃）。 */
    public static final int DATA_TEMPERATURE = 10;
    public static final int DATA_SIZE = 11;

    // ContainerData indices
    private static final int DATA_PROGRESS = 0;
    private static final int DATA_PROCESS_TIME = 1;
    private static final int DATA_ENERGY = 2;
    private static final int DATA_SIDE_CONFIG = 3;
    private static final int DATA_SPEED_UPGRADE = 4;
    private static final int DATA_ENERGY_UPGRADE = 5;
    private static final int DATA_ORDER_QUANTITY = 6;
    private static final int DATA_ORDER_COMPLETED = 7;

    private Component customName;
    private int progress;

    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    // PULSE 模式：收到红石信号(上升沿)后锁存为 true，完成一次完整处理后复位。
    private boolean pulseRunning = false;

    // Order system
    private ResourceLocation orderRecipeId;
    private int orderQuantity;
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true;

    private final SideMode[] sideConfig = new SideMode[6];

    private final ItemStackHandler items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (slot == INPUT_SLOT) {
                // 输入槽只接受普通食材，不允许放入任何升级物品
                return !isAnyUpgradeItem(stack) && RecipeInputMatcher.matchesGrilling(level, stack);
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
            if (slot == SLOT_POWER) {
                return PowerSlotUtil.isValidEnergyItem(stack);
            }
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot == INPUT_SLOT || slot == OUTPUT_SLOT) {
                return Integer.MAX_VALUE;
            }
            if (slot == SLOT_CREATIVE_UPGRADE) {
                return 1;
            }
            // Power slot: max 64
            if (slot == SLOT_POWER) {
                return 64;
            }
            return 8;
        }

        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            if (slot == INPUT_SLOT || slot == OUTPUT_SLOT) {
                return getSlotLimit(slot);
            }
            if (slot == SLOT_CREATIVE_UPGRADE) {
                return getSlotLimit(slot);
            }
            if (slot == SLOT_POWER) {
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
                case DATA_SIDE_CONFIG -> encodeSideConfig();
                case DATA_SPEED_UPGRADE -> getSpeedUpgradeCount();
                case DATA_ENERGY_UPGRADE -> getEnergyUpgradeCount();
                case DATA_ORDER_QUANTITY -> orderQuantity;
                case DATA_ORDER_COMPLETED -> orderCompleted;
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

    /** 温度系统：Mekanism 热容量（运行时按电阻型加热器比例产热，并与相邻热力设备传导）。 */
    private cn.ism.mekck.util.MekCkHeatComponent heatComponent;
    private final net.minecraftforge.common.util.LazyOptional<mekanism.api.heat.IHeatHandler> heatCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> heatComponent.getHandler());

        /** 当前机身温度（开尔文）。 */
    public double getTemperature() {
        return heatComponent == null ? mekanism.api.heat.HeatAPI.AMBIENT_TEMP : heatComponent.getTemperature();
    }

public GrillBlockEntity(BlockPos pos, BlockState state) {
        super(UniversalCuttingMachine.GRILL_BLOCK_ENTITY.get(), pos, state);
        this.heatComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
        }
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, GrillBlockEntity machine) {
        // 温度系统：每 tick 自然回归环境并与相邻 Mekanism 热力设备传导
        machine.heatComponent.tick(level, pos);
        boolean wasActive = machine.progress > 0;
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.util.AE2Compat.serverTick(machine, level, pos);

        // Update redstone powered state (Mekanism updatePower equivalent)
        machine.updateRedstone();

        // Drain energy from the power slot (energy cube / tablet / redstone) into the machine
        if (machine.drainPowerSlot()) {
            machine.setChanged();
        }

        boolean hasCreative = machine.hasCreativeUpgrade();
        if (hasCreative) {
            machine.energy.receiveEnergy(machine.energy.getMaxEnergyStored() - machine.energy.getEnergyStored(), false);
        }

        double speedMult = machine.getEffectiveSpeedMultiplier();
        double energyConsumptionMult = machine.getEffectiveEnergyConsumptionMultiplier();
        int effectiveProcessTime = hasCreative ? 1 : machine.getEffectiveProcessTime();
        int energyPerTick = hasCreative ? 0 : (int) Math.ceil(ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);

        Optional<Recipe<?>> recipe = machine.findRecipe(level);
        boolean canProcess = false;

        if (recipe.isPresent()) {
            Recipe<?> r = recipe.get();
            ItemStack result = r.getResultItem(level.registryAccess());
            if (!result.isEmpty() && machine.canFitOutput(result)) {
                canProcess = true;
            }
        }

        // Check order requirements
        if (canProcess && machine.orderQuantity > 0 && machine.orderCompleted >= machine.orderQuantity) {
            canProcess = false;
        }

        // PULSE 模式：收到红石信号(上升沿)时锁存，机器开始一次完整的处理
        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        boolean canOperate = machine.canFunctionRedstone();
        if (canOperate && canProcess && machine.energy.getEnergyStored() >= energyPerTick) {
            machine.energy.extractEnergy(energyPerTick, false);
                // 温度系统：按消耗电能产热（与电阻型加热器比例完全相同：1 FE → 0.6 J 热量）
                machine.heatComponent.addHeatFromEnergy(energyPerTick);
            machine.progress++;
            if (machine.progress >= effectiveProcessTime) {
                Recipe<?> r = recipe.get();
                machine.completeRecipe(level, r);
                machine.progress = 0;
                // PULSE 模式：本次完整处理结束，停止并等待下一次红石信号
                if (machine.redstoneControl == RedstoneControl.PULSE) {
                    machine.pulseRunning = false;
                }
            }
            machine.setChanged();
        } else {
            if (machine.progress != 0) {
                machine.progress = 0;
                machine.setChanged();
            }
            // PULSE 模式：本 tick 无法运行则解除锁存，等待下一次红石信号重新触发
            if (machine.redstoneControl == RedstoneControl.PULSE && machine.pulseRunning) {
                machine.pulseRunning = false;
                machine.setChanged();
            }
        }
        if (LagMonitor.shouldRunIO(level.getGameTime(), pos)) machine.autoIO(level, pos);

        boolean isActive = machine.progress > 0;
        if (wasActive != isActive) {
            level.setBlock(pos, state.setValue(GrillBlock.ACTIVE, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, GrillBlockEntity machine) {
        if (state.getValue(GrillBlock.ACTIVE)) {
            SoundHandler.startTileSound(MekanismSounds.PRECISION_SAWMILL.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
        } else {
            SoundHandler.stopTileSound(pos);
        }
    }

    private static final int[][] IO_PULL_RANGES = {{INPUT_SLOT, 1}};
    private static final int[][] IO_PUSH_RANGES = {{OUTPUT_SLOT, 1}};

    private final AutoIO autoIO = new AutoIO(this, IO_PULL_RANGES, IO_PUSH_RANGES);

    private void autoIO(Level level, BlockPos pos) {
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

    private Optional<Recipe<?>> findRecipe(Level level) {
        ItemStack input = items.getStackInSlot(INPUT_SLOT);
        if (input.isEmpty()) {
            return Optional.empty();
        }

        // If an order is active, only check the ordered recipe
        if (orderRecipeId != null) {
            Recipe<?> orderedRecipe = findRecipeById(level, orderRecipeId);
            if (orderedRecipe != null && matchesInput(orderedRecipe, input)) {
                return Optional.of(orderedRecipe);
            }
            return Optional.empty();
        }

        // No order set - do not auto-process
        return Optional.empty();
    }

    /**
     * Checks if the recipe's ingredient matches the given input stack.
     * Uses reflection to access the 'ingredient' field for SimpleGrillingRecipe,
     * or falls back to getIngredients() for standard Recipe interface.
     */
    private static boolean matchesInput(Recipe<?> recipe, ItemStack input) {
        // Try reflection to get the ingredient field (SimpleGrillingRecipe)
        try {
            java.lang.reflect.Field field = recipe.getClass().getField("ingredient");
            Ingredient ingredient = (Ingredient) field.get(recipe);
            return ingredient.test(input);
        } catch (Exception ignored) {
        }
        // Fallback: use standard getIngredients()
        List<Ingredient> ingredients = recipe.getIngredients();
        if (!ingredients.isEmpty()) {
            return ingredients.get(0).test(input);
        }
        return false;
    }

    @Nullable
    private Recipe<?> findRecipeById(Level level, ResourceLocation recipeId) {
        ResourceLocation grillingTypeId = new ResourceLocation("barbequesdelight", "grilling");
        RecipeType<?> grillingType = cn.ism.mekck.util.RecipeCache.type(grillingTypeId);
        if (grillingType == null) return null;

        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, grillingType)) {
            if (recipe.getId().equals(recipeId)) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * Gets all available grilling recipes that can be crafted with the current input.
     */
    @SuppressWarnings("unchecked")
    /** 供「本机下单」面板的 Max 按钮：输入槽现有材料能做几份（烧烤配方 1 输入 = 1 份）。 */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        if (recipe == null || level == null) return 0;
        ItemStack input = items.getStackInSlot(INPUT_SLOT);
        if (input.isEmpty() || !matchesInput(recipe, input)) return 0;
        return Math.max(0, input.getCount());
    }

    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> available = new ArrayList<>();
        ItemStack input = items.getStackInSlot(INPUT_SLOT);
        if (input.isEmpty()) return available;

        ResourceLocation grillingTypeId = new ResourceLocation("barbequesdelight", "grilling");
        RecipeType<?> grillingType = cn.ism.mekck.util.RecipeCache.type(grillingTypeId);
        if (grillingType == null) return available;

        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, grillingType)) {
            if (matchesInput(recipe, input)) {
                available.add(recipe);
            }
        }
        return available;
    }

    private void completeRecipe(Level level, Recipe<?> recipe) {
        ItemStack input = items.getStackInSlot(INPUT_SLOT);
        if (input.isEmpty()) return;

        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) return;

        // Consume 1 input
        items.extractItem(INPUT_SLOT, 1, false);

        // Output result
        ItemStack output = result.copy();
        insertOutput(output);

        // Update order tracking
        if (orderQuantity > 0) {
            orderCompleted++;
            if (orderCompleted >= orderQuantity) {
                orderQuantity = 0;
                orderCompleted = 0;
                orderRecipeId = null;
            }
        }
    }

    private boolean canFitOutput(ItemStack result) {
        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT);
        if (existing.isEmpty()) {
            return true;
        }
        if (ItemStack.isSameItemSameTags(existing, result)) {
            long total = (long) existing.getCount() + result.getCount();
            return total <= Integer.MAX_VALUE;
        }
        return false;
    }

    private void insertOutput(ItemStack stack) {
        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT);
        if (existing.isEmpty()) {
            items.setStackInSlot(OUTPUT_SLOT, stack);
        } else if (ItemStack.isSameItemSameTags(existing, stack)) {
            long total = (long) existing.getCount() + stack.getCount();
            int merged = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
            existing.setCount(merged);
            items.setStackInSlot(OUTPUT_SLOT, existing);
        }
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
                SLOT_ENERGY_UPGRADE, MekckConfig.getBasicEnergyUpgradeMax(), -1, 0, SLOT_CREATIVE_UPGRADE, held);
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
        return SLOT_POWER;
    }

    /**
     * 从能源槽位中的能量物品抽取能量注入机器能量，返回是否发生变化。
     */
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
    @Override public BlockEntity getNetworkPullable() { return this; }
    @Override public int[] getInputSlotRange() { return new int[]{INPUT_SLOT, OUTPUT_SLOT}; }
    @Override public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() { return items; }
    @Override public boolean supportsAutoPull() { return true; }

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        ItemStack slot0 = items.getStackInSlot(INPUT_SLOT);
        if (!slot0.isEmpty()) {
            return List.of(new cn.ism.mekck.util.AE2InputSpec(net.minecraft.world.item.crafting.Ingredient.of(slot0.getItem())));
        }
        net.minecraft.world.item.crafting.Ingredient union = cn.ism.mekck.util.RecipeInputMatcher.unionFirstIngredients(
                level, new ResourceLocation("barbequesdelight", "grilling"));
        return union.isEmpty() ? List.of() : List.of(new cn.ism.mekck.util.AE2InputSpec(union));
    }

    public ContainerData getData() {
        return data;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        // 键必须与注册名一致：方块注册为 mekck:electric_grill（UniversalCuttingMachine:655），
        // 原先写的 block.mekck.grill 在语言文件里不存在 ⇒ GUI 标题显示 raw key。
        return customName != null ? customName : Component.translatable("block.mekck.electric_grill");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new GrillMenu(containerId, inventory, this, data);
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
        if (heatComponent != null) tag.put("HeatCapacitor", heatComponent.save());
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
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (heatComponent != null && tag.contains("HeatCapacitor", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            heatComponent.load(tag.getCompound("HeatCapacitor"));
        }
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
        // Load order data
        if (tag.contains("OrderRecipeId")) {
            orderRecipeId = ResourceLocation.tryParse(tag.getString("OrderRecipeId"));
        }
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        orderQuantity = tag.getInt("OrderQuantity");
        orderCompleted = tag.getInt("OrderCompleted");
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
        // 温度系统：暴露 Mekanism 热能力，供热力设备传导
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
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(INPUT_SLOT);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return items.insertItem(INPUT_SLOT, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(INPUT_SLOT);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return items.isItemValid(INPUT_SLOT, stack);
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