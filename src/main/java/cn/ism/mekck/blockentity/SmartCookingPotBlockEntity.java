package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.SmartCookingPotBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.menu.SmartCookingPotMenu;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.FastTransfer;
import cn.ism.mekck.util.FluidContainerInteract;
import cn.ism.mekck.util.FluidIngredientHelper;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
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
import java.util.stream.Collectors;

public final class SmartCookingPotBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, mekanism.api.heat.IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable {
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

    public static final int INPUT_SLOT_START = 0;
    public static final int INPUT_SLOT_COUNT = 6;
    public static final int INPUT_SLOT_END = 5;
    public static final int OUTPUT_SLOT = 6;
    public static final int RETURN_SLOT = 7;
    public static final int SLOT_SPEED_UPGRADE = 8;
    public static final int SLOT_ENERGY_UPGRADE = 9;
    public static final int STORAGE_SLOT_START = 10;
    public static final int STORAGE_SLOT_COUNT = 81;
    public static final int SLOT_CREATIVE_UPGRADE = STORAGE_SLOT_START + STORAGE_SLOT_COUNT;
    // 能源槽位（能量物品），追加在末尾
    public static final int SLOT_POWER = SLOT_CREATIVE_UPGRADE + 1;
    public static final int TOTAL_SLOTS = SLOT_POWER + 1;
    public static final int ENERGY_CAPACITY = 100_000;
    public static final int ENERGY_PER_TICK = 20;
    public static final int PROCESS_TIME = 200;
    public static final int MAX_RECEIVE = 1_000;
    public static final int FLUID_CAPACITY = Integer.MAX_VALUE;
    /** 流体输入格的槽位数（支持同时存在水/奶/多种流体）。 */
    public static final int FLUID_TANK_COUNT = 3;
    public static final int FLUID_PER_BOTTLE = 250;

    /** 机身温度（单位 0.01 ℃）。 */
    public static final int DATA_TEMPERATURE = 16;
    public static final int DATA_SIZE = 17;

    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_SIDE_CONFIG = 3;
    public static final int DATA_SPEED_UPGRADE = 4;
    public static final int DATA_ENERGY_UPGRADE = 5;
    public static final int DATA_ORDER_QUANTITY = 6;
    public static final int DATA_ORDER_COMPLETED = 7;
    public static final int DATA_CREATIVE_UPGRADE = 8;
    public static final int DATA_REDSTONE_CONTROL = 9;
    public static final int DATA_FLUID_AMOUNT0 = 10;
    public static final int DATA_FLUID_AMOUNT1 = 11;
    public static final int DATA_FLUID_AMOUNT2 = 12;
    public static final int DATA_FLUID_TYPE0 = 13;
    public static final int DATA_FLUID_TYPE1 = 14;
    public static final int DATA_FLUID_TYPE2 = 15;

    private Component customName;
    private int progress;

    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    // PULSE 模式：收到红石信号(上升沿)后锁存为 true，完成一次完整处理后复位。
    private boolean pulseRunning = false;

    // Order system
    private ResourceLocation orderRecipeId;
    private int orderQuantity; // total quantity to produce
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true; // how many have been produced so far

    private final SideMode[] sideConfig = new SideMode[6];

    private final ItemStackHandler items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (slot >= INPUT_SLOT_START && slot <= INPUT_SLOT_END) {
                // 输入槽只接受普通食材，不允许放入任何升级物品
                return !isAnyUpgradeItem(stack) && (RecipeInputMatcher.matchesCooking(level, stack)
                        || RecipeInputMatcher.matchesPotCooking(level, stack));
            }
            if (slot == OUTPUT_SLOT || slot == RETURN_SLOT) {
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
            if (slot >= STORAGE_SLOT_START && slot < STORAGE_SLOT_START + STORAGE_SLOT_COUNT) {
                // 存储槽只接受普通物品，不允许放入任何升级物品
                return !isAnyUpgradeItem(stack);
            }
            if (slot == SLOT_POWER) {
                return PowerSlotUtil.isValidEnergyItem(stack);
            }
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot >= INPUT_SLOT_START && slot <= INPUT_SLOT_END) {
                return Integer.MAX_VALUE;
            }
            if (slot == OUTPUT_SLOT || slot == RETURN_SLOT) {
                return Integer.MAX_VALUE;
            }
            if (slot >= STORAGE_SLOT_START && slot < STORAGE_SLOT_START + STORAGE_SLOT_COUNT) {
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
            if (slot >= INPUT_SLOT_START && slot <= INPUT_SLOT_END) {
                return getSlotLimit(slot);
            }
            if (slot == OUTPUT_SLOT || slot == RETURN_SLOT) {
                return getSlotLimit(slot);
            }
            if (slot >= STORAGE_SLOT_START && slot < STORAGE_SLOT_START + STORAGE_SLOT_COUNT) {
                return getSlotLimit(slot);
            }
            if (slot == SLOT_CREATIVE_UPGRADE) {
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
            int targetSize = TOTAL_SLOTS;
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

    private final MultiFluidHandler fluidTank =
            new MultiFluidHandler(FLUID_TANK_COUNT, FLUID_CAPACITY, this::setChanged);

    private LazyOptional<IItemHandler> fullItemCapability;
    private LazyOptional<IItemHandler> inputItemCapability;
    private LazyOptional<IItemHandler> storageItemCapability;
    private LazyOptional<IItemHandler> outputItemCapability;
    private LazyOptional<IEnergyStorage> energyCapability;
    private LazyOptional<IFluidHandler> fluidCapability;

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
                case 0 -> progress;
                case 1 -> getEffectiveProcessTime();
                case 2 -> energy.getEnergyStored();
                case 3 -> encodeSideConfig();
                case 4 -> getSpeedUpgradeCount();
                case 5 -> getEnergyUpgradeCount();
                case 6 -> orderQuantity;
                case 7 -> orderCompleted;
                case 8 -> hasCreativeUpgrade() ? 1 : 0;
                case 9 -> redstoneControl.ordinal();
                case DATA_FLUID_AMOUNT0 -> fluidAmount(0);
                case DATA_FLUID_AMOUNT1 -> fluidAmount(1);
                case DATA_FLUID_AMOUNT2 -> fluidAmount(2);
                case DATA_FLUID_TYPE0 -> fluidTypeId(0);
                case DATA_FLUID_TYPE1 -> fluidTypeId(1);
                case DATA_FLUID_TYPE2 -> fluidTypeId(2);
                case DATA_TEMPERATURE -> (int) Math.round((getTemperature() - 273.15) * 100.0);
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

        /** 温度系统：Mekanism 热容量（运行时按电阻型加热器比例产热，并与相邻热力设备传导）。 */
    private cn.ism.mekck.util.MekCkHeatComponent heatComponent;
    private final net.minecraftforge.common.util.LazyOptional<mekanism.api.heat.IHeatHandler> heatCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> heatComponent.getHandler());

    /** 当前机身温度（开尔文）。 */
    public double getTemperature() {
        return heatComponent == null ? mekanism.api.heat.HeatAPI.AMBIENT_TEMP : heatComponent.getTemperature();
    }

public SmartCookingPotBlockEntity(BlockPos pos, BlockState state) {
        super(UniversalCuttingMachine.COOKING_POT_BLOCK_ENTITY.get(), pos, state);
        this.heatComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
        }
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.storageItemCapability = LazyOptional.of(() -> new StorageItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
        this.fluidCapability = LazyOptional.of(() -> fluidTank);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SmartCookingPotBlockEntity machine) {
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

        // Creative upgrade: fill energy to max, no consumption, 1 tick process time
        boolean hasCreative = machine.hasCreativeUpgrade();
        if (hasCreative) {
            machine.energy.receiveEnergy(machine.energy.getMaxEnergyStored() - machine.energy.getEnergyStored(), false);
        }

        double speedMult = machine.getEffectiveSpeedMultiplier();
        double energyConsumptionMult = machine.getEffectiveEnergyConsumptionMultiplier();
        int energyPerTick = hasCreative ? 0 : (int) Math.ceil(ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);

        // PULSE 模式：收到红石信号(上升沿)时锁存，机器开始一次完整的处理
        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        boolean canOperate = machine.canFunctionRedstone();
        Optional<Recipe<?>> recipeOpt = machine.findRecipe(level);
        Recipe<?> recipe = recipeOpt.orElse(null);

        // 基于当前配方计算处理时间（不同配方 cook time 可能不同）
        int recipeBaseTime = (recipe != null) ? machine.getRecipeProcessTime(recipe) : PROCESS_TIME;
        int effectiveProcessTime = hasCreative ? 1 : Math.max(1, (int) (recipeBaseTime / speedMult));

        if (canOperate && recipe != null && machine.energy.getEnergyStored() >= energyPerTick
                && machine.canFitAll(recipe)) {
            // Check order requirements: if we have an order, verify we haven't completed it yet
            boolean canProcess = true;
            if (machine.orderQuantity > 0 && machine.orderCompleted >= machine.orderQuantity) {
                canProcess = false;
            }
            
            if (canProcess) {
                machine.energy.extractEnergy(energyPerTick, false);
                // 温度系统：按消耗电能产热（与电阻型加热器比例完全相同：1 FE → 0.6 J 热量）
                machine.heatComponent.addHeatFromEnergy(energyPerTick);
                machine.progress++;
                if (machine.progress >= effectiveProcessTime) {
                    machine.completeRecipe(level, recipe);
                    
                    // Update order tracking
                    if (machine.orderQuantity > 0) {
                        machine.orderCompleted++;
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
                }
                machine.setChanged();
            }
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
        if (level.getGameTime() % 20 == 0) machine.convertStoredFluidContainers();
        if (LagMonitor.shouldRunIO(level.getGameTime(), pos)) machine.autoIO(level, pos);
        // 存储空间自动合并：相同物品合并至靠前的格子
        if (StorageMerger.shouldRunMerge(level.getGameTime(), pos, 20)) {
            if (StorageMerger.merge(machine.items, STORAGE_SLOT_START, STORAGE_SLOT_COUNT)) {
                machine.setChanged();
            }
        }

        // Update block state active property for sound synchronization
        boolean isActive = machine.progress > 0;
        if (wasActive != isActive) {
            level.setBlock(pos, state.setValue(SmartCookingPotBlock.ACTIVE, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, SmartCookingPotBlockEntity machine) {
        if (state.getValue(SmartCookingPotBlock.ACTIVE)) {
            SoundHandler.startTileSound(MekanismSounds.ENRICHMENT_CHAMBER.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
        } else {
            SoundHandler.stopTileSound(pos);
        }
    }

    /**
     * 扫描存储槽，将水瓶/水桶/奶瓶/奶桶自动转换为流体注入流体槽，
     * 空容器（玻璃瓶/铁桶）退回 RETURN_SLOT。
     */
    private void convertStoredFluidContainers() {
        for (int i = STORAGE_SLOT_START; i < STORAGE_SLOT_START + STORAGE_SLOT_COUNT; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            FluidContainerInteract.ContainerFluidInfo info = FluidContainerInteract.getFluidInfo(stack);
            if (info == null || !info.isValid()) continue;
            // 尝试注入流体
            int filled = fluidTank.fill(info.fluid(), net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);
            if (filled < info.fluid().getAmount()) continue; // 没有足够空间
            // 注入流体，消耗 1 个容器
            fluidTank.fill(info.fluid(), net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            stack.shrink(1);
            if (stack.isEmpty()) {
                items.setStackInSlot(i, ItemStack.EMPTY);
            }
            // 退回空容器到 RETURN_SLOT
            ItemStack remainder = items.insertItem(RETURN_SLOT, info.emptyContainer(), false);
            if (!remainder.isEmpty() && level != null && !level.isClientSide) {
                Containers.dropItemStack(level, worldPosition.getX() + 0.5,
                        worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, remainder);
            }
            setChanged();
            break; // 每 tick 只转换 1 个
        }
    }

    private final AutoIO autoIO = new AutoIO(this,
            new int[][]{{INPUT_SLOT_START, INPUT_SLOT_COUNT}},
            new int[][]{{STORAGE_SLOT_START, STORAGE_SLOT_COUNT}},
            new int[][]{{OUTPUT_SLOT, 1}, {RETURN_SLOT, 1}});

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
        SideMode next = current.cycle(true, true);
        sideConfig[direction.ordinal()] = next;
        setChanged();
    }

    private Optional<Recipe<?>> findRecipe(Level level) {
        // 没有订单时不自动加工：先早退，省掉每 tick 的输入摊平与流式拼接
        if (orderRecipeId == null) return Optional.empty();
        List<ItemStack> inputs = new ArrayList<>();
        for (int i = INPUT_SLOT_START; i <= INPUT_SLOT_END; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!stack.isEmpty()) inputs.add(stack);
        }
        for (int i = STORAGE_SLOT_START; i < STORAGE_SLOT_START + STORAGE_SLOT_COUNT; i++) {
            ItemStack stack = items.getStackInSlot(i);
            if (!stack.isEmpty()) inputs.add(stack);
        }
        if (inputs.isEmpty()) return Optional.empty();

        // 有订单：仅找订单 ID 对应的那条配方
        if (orderRecipeId != null) {
            java.util.stream.Stream<Recipe<?>> stream =
                    java.util.stream.Stream.concat(
                            java.util.stream.Stream.concat(
                                    cn.ism.mekck.util.RecipeCache.all(level, ModRecipeTypes.COOKING.get()).stream(),
                                    cn.ism.mekck.util.RecipeInputMatcher.getPotCookingRecipes(level).stream()),
                            KaleidoscopeCompat.isLoaded()
                                    ? KaleidoscopeCompat.getAllKaleidoscopeRecipes(level).stream()
                                    : java.util.stream.Stream.empty()
                    );
            return stream.filter(r -> r.getId().equals(orderRecipeId)
                            && canMatchAllIngredientsWithExtras(r, inputs))
                    .findFirst();
        }
        return Optional.empty();
    }

    /**
     * Custom matching algorithm that handles extra items in the input list.
     * Unlike RecipeMatcher.findMatches, this does NOT require inputs.size() == ingredients.size().
     */
    private static boolean canMatch(List<ItemStack> inputs, List<Ingredient> ingredients) {
        // Build a list of matching input indices for each ingredient
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

        // Sort by match count (fewest first) for better performance
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> matches.get(i).size()));

        // Try to find a matching using backtracking
        boolean[] used = new boolean[inputs.size()];
        return backtrack(matches, order, 0, used);
    }

    private static boolean backtrack(List<List<Integer>> matches, List<Integer> order, int depth, boolean[] used) {
        if (depth >= order.size()) {
            return true;
        }
        int ingredientIdx = order.get(depth);
        for (int inputIdx : matches.get(ingredientIdx)) {
            if (!used[inputIdx]) {
                used[inputIdx] = true;
                if (backtrack(matches, order, depth + 1, used)) {
                    return true;
                }
                used[inputIdx] = false;
            }
        }
        return false;
    }

    private boolean isWaterBottleIngredient(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (isWaterBottle(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWaterBottle(ItemStack stack) {
        if (stack.getItem() == Items.POTION || stack.getItem() == Items.SPLASH_POTION || stack.getItem() == Items.LINGERING_POTION) {
            return PotionUtils.getPotion(stack) == Potions.WATER;
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

    // ================================================================
    //  统一配方元数据（兼容农夫乐事 CookingPotRecipe + 森罗物语 Pot/Stockpot/Flex*）
    // ================================================================

    /**
     * 返回配方的"固体食材" ingredient 列表（剔除空）。
     * 农夫乐事：直接剔除水/奶瓶（它们由流体系统处理），其余保留。
     * 森罗物语：直接返回非空 ingredient 列表。
     */
    private List<Ingredient> getSolidIngredients(Recipe<?> recipe) {
        if (recipe instanceof CookingPotRecipe cooking) {
            List<Ingredient> solid = new ArrayList<>();
            for (Ingredient ing : cooking.getIngredients()) {
                if (ing == null || ing.isEmpty()) continue;
                if (isWaterBottleIngredient(ing) || isMilkBottleIngredient(ing)) continue;
                solid.add(ing);
            }
            return solid;
        }
        if (KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)) {
            return KaleidoscopeCompat.getSolidIngredients(recipe);
        }
        if (cn.ism.mekck.util.RecipeInputMatcher.isPotCookingRecipe(recipe)) {
            return new ArrayList<>(recipe.getIngredients());
        }
        return List.of();
    }

    /**
     * 返回配方所需的"容器" ingredient（农夫乐事 CookingPotRecipe 用
     * {@link CookingPotRecipe#getOutputContainer()}，其是"盛出物品需要
     * 消耗的容器"——比如肉菜汤=碗；本模组现在改为**消耗**而不是返还）。
     * 森罗物语 carrier。
     */
    private Ingredient getContainerIngredient(Recipe<?> recipe) {
        if (recipe instanceof CookingPotRecipe cooking) {
            ItemStack container = cooking.getOutputContainer();
            if (container == null || container.isEmpty()) return Ingredient.EMPTY;
            return Ingredient.of(container);
        }
        if (KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)) {
            return KaleidoscopeCompat.getCarrier(recipe);
        }
        return Ingredient.EMPTY;
    }

    /**
     * 返回配方产物。
     */
    private ItemStack getResultStack(Recipe<?> recipe) {
        if (recipe instanceof CookingPotRecipe cooking) {
            return cooking.getResultItem(level.registryAccess());
        }
        if (KaleidoscopeCompat.isKaleidoscopeRecipe(recipe)) {
            return recipe.getResultItem(level.registryAccess());
        }
        if (cn.ism.mekck.util.RecipeInputMatcher.isPotCookingRecipe(recipe)) {
            return recipe.getResultItem(level.registryAccess());
        }
        return ItemStack.EMPTY;
    }

    /**
     * 配方的默认处理时间（tick）。速度升级乘数之后再应用。
     * 农夫乐事：200 tick（PROCESS_TIME，与电力熔炼炉一致）；森罗：配方内 time()；未知：默认 200。
     */
    private int getRecipeProcessTime(Recipe<?> recipe) {
        if (recipe instanceof CookingPotRecipe) {
            return PROCESS_TIME;
        }
        int kt = KaleidoscopeCompat.getCookTime(recipe);
        if (kt > 0) return kt;
        return PROCESS_TIME;
    }

    /**
     * Returns the total fluid requirement per single unit of {@code recipe}.
     * Delegates to {@link FluidIngredientHelper#sumFluids(Iterable)} so water
     * bottle (250mb), water bucket (1000mb), milk bottle (250mb) and milk
     * bucket (1000mb) are all correctly distinguished.
     * Applies to any recipe (FD CookingPotRecipe, Kaleidoscope variants).
     */
    private FluidIngredientHelper.FluidInfo getFluidPerUnit(Recipe<?> recipe) {
        return FluidIngredientHelper.sumFluids(recipe.getIngredients());
    }

    /**
     * Checks if the fluid tank currently holds enough of the correct fluids for
     * one unit of {@code recipe}. Multiplied by {@code multiplier} if the
     * caller is processing more than one unit at a time.
     *
     * <p>The single-tank validator is permissive (accepts any fluid), so both
     * water and milk can live inside at different times. We therefore verify
     * contents by <b>fluid type</b> when a specific type is required:
     * <ul>
     *   <li>If the tank is empty → nothing available.</li>
     *   <li>If the tank contains WATER but we need WATER → OK (amount-based).</li>
     *   <li>If the tank contains MILK but we need MILK → OK (amount-based).</li>
     *   <li>Otherwise type mismatches → return false.</li>
     * </ul>
     */
    private boolean hasRequiredFluid(Recipe<?> recipe, int multiplier) {
        FluidIngredientHelper.FluidInfo need = getFluidPerUnit(recipe);
        if (need.isEmpty()) return true;
        if (multiplier <= 0) return true;

        // 3 个独立流体槽：水和奶分别从对应类型的槽判定，可同时满足。
        if (need.waterMb > 0) {
            if (!fluidTank.hasEnoughOf(true, cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, need.waterMb, multiplier))) return false;
        }
        if (need.milkMb > 0) {
            if (!fluidTank.hasEnoughOf(false, cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, need.milkMb, multiplier))) return false;
        }
        return true;
    }

    /** Convenience overload for the one-unit call sites. */
    private boolean hasRequiredFluid(Recipe<?> recipe) {
        return hasRequiredFluid(recipe, 1);
    }

    /**
     * Drains the fluid requirement for {@code multiplier} units of
     * {@code recipe} from the tank.
     */
    private void consumeFluidForRecipe(Recipe<?> recipe, int multiplier) {
        FluidIngredientHelper.FluidInfo need = getFluidPerUnit(recipe);
        if (need.isEmpty() || multiplier <= 0) return;
        if (need.waterMb > 0) {
            fluidTank.drainOf(true, cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, need.waterMb, multiplier));
        }
        if (need.milkMb > 0) {
            // Milk fluid is identified as the non-water content in the tank.
            // Drain by amount only (the user already asserted the fluid type
            // via hasRequiredFluid before the recipe was started).
            fluidTank.drainOf(false, cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, need.milkMb, multiplier));
        }
    }

    private void consumeFluidForRecipe(Recipe<?> recipe) {
        consumeFluidForRecipe(recipe, 1);
    }

    /**
     * 在输入+存储展平库存里寻找 1 份 ingredient，返回"展平的索引"（-1 没找到）。
     * 展平顺序: 输入(0..5) → 存储(0..80)，共 87 项。
     * simulate=false 时真实抽 1 件。
     *
     * <p>If {@code consumedOut} is a non-null array of length ≥ 1, the single
     * item that was actually consumed (a 1-count copy of the matched stack,
     * taken before shrinking) is written into slot 0. This is used by
     * consumeAllMaterials to return empty containers (bucket / glass bottle /
     * bowl from intermediate items like ketchup) to RETURN_SLOT.
     *
     * <p>Additionally, when {@code ingredient} is a fluid-bearing item and we
     * consume it literally from storage (i.e. the item itself is an
     * ingredient, the machine is NOT using its own fluid tank to satisfy that
     * ingredient — which only happens for Kaleidoscope recipes that list e.g.
     * a water bucket as a solid ingredient), the caller is responsible for
     * the container return; this helper simply records the match in
     * {@code consumedOut}.
     */
    private int findAndConsumeOne(Ingredient ing, boolean simulate,
                                   @Nullable ItemStack[] consumedOut) {
        if (consumedOut != null) consumedOut[0] = ItemStack.EMPTY;
        // 遍历输入槽
        for (int i = INPUT_SLOT_START; i <= INPUT_SLOT_END; i++) {
            ItemStack s = items.getStackInSlot(i);
            if (!s.isEmpty() && ing.test(s) && s.getCount() > 0) {
                if (consumedOut != null) consumedOut[0] = s.copyWithCount(1);
                if (!simulate) items.extractItem(i, 1, false);
                return i;
            }
        }
        int storageBase = INPUT_SLOT_END - INPUT_SLOT_START + 1; // =6
        for (int j = 0; j < STORAGE_SLOT_COUNT; j++) {
            int realSlot = STORAGE_SLOT_START + j;
            ItemStack s = items.getStackInSlot(realSlot);
            if (!s.isEmpty() && ing.test(s) && s.getCount() > 0) {
                if (consumedOut != null) consumedOut[0] = s.copyWithCount(1);
                if (!simulate) items.extractItem(realSlot, 1, false);
                return storageBase + j;
            }
        }
        return -1;
    }

    /**
     * 一次性扣 N 份配方所需的"全部消耗"（固体食材+容器+附加 oil/carrier）。
     * 仅在 simulate=false 时真实扣除。返回 true 表示材料够。
     *
     * <p>Ingredients that have a {@link Item#getCraftingRemainingItem(ItemStack)
     * remaining item} (e.g. {@code water_bucket → bucket}) plus a few
     * hand-rolled return mappings ({@code water_potion → glass_bottle},
     * {@code farmersdelight:milk_bottle → glass_bottle}) are routed to
     * {@link #RETURN_SLOT} automatically on non-simulate runs. This matches
     * the original FarmersDelight Cooking Pot behaviour where "using a
     * container as an ingredient returns the empty variant".
     */
    private boolean consumeAllMaterials(Recipe<?> recipe, int n, boolean simulate) {
        if (n <= 0) return true;
        List<Ingredient> solids = getSolidIngredients(recipe);
        Ingredient containerIng = getContainerIngredient(recipe);
        List<Ingredient> extras = KaleidoscopeCompat.getExtraConsumables(recipe);

        // 流体判定（基于 FluidIngredientHelper：水瓶/水桶 250/1000mb 水 + 奶瓶/奶桶 250/1000mb 奶）
        if (!hasRequiredFluid(recipe)) return false;

        ItemStack[] consumedHolder = simulate ? null : new ItemStack[1];
        List<Runnable> deferredReturns = simulate ? null : new ArrayList<>();

        // 扣 n 次
        for (int k = 0; k < n; k++) {
            // 固体
            for (Ingredient ing : solids) {
                int idx = findAndConsumeOne(ing, simulate, consumedHolder);
                if (idx < 0) return false;
                if (!simulate) queueReturnFor(consumedHolder[0], ing, deferredReturns);
            }
            // 容器（若配方显式提供了单独 container 且不是 bowl-in-carrier 的
            // 森罗语义，则也需消耗一份；注意森罗 carrier 可能被 extras 也列了，
            // 用 isEmpty + 不含 bowl 才真的此处再扣，防重复）
            if (!containerIng.isEmpty()) {
                // 农夫乐事：container 不是空就扣（农夫乐事没有 carrier 已在 solids
                // 里的写法；森罗则 container=carrier，若它"不含 bowl 且非空"会被
                // KaleidoscopeCompat.getExtraConsumables 再附加一次——为了防重复，
                // 这里只在"农夫乐事类型（即非森罗）"时才在容器里消耗；森罗走 extras。
                if (!(KaleidoscopeCompat.isKaleidoscopeRecipe(recipe))) {
                    int idx = findAndConsumeOne(containerIng, simulate, consumedHolder);
                    if (idx < 0) return false;
                    if (!simulate) queueReturnFor(consumedHolder[0], containerIng, deferredReturns);
                }
            }
            // 附加消耗：森罗的 carrier(非bowl)/pot oil
            for (Ingredient ing : extras) {
                if (ing.isEmpty()) continue;
                int idx = findAndConsumeOne(ing, simulate, consumedHolder);
                if (idx < 0) return false;
                if (!simulate) queueReturnFor(consumedHolder[0], ing, deferredReturns);
            }
        }

        // Flush container returns. Runs AFTER the entire loop so partial
        // failures (idx<0 mid-loop) never leave behind a half-returned state.
        if (!simulate) {
            for (Runnable r : deferredReturns) r.run();
        }
        return true;
    }

    /**
     * Inspects {@code consumedStack} (the actual item we just removed from a
     * storage/input slot to satisfy {@code ingredient}) and queues the
     * remainder-container item (empty bucket / glass bottle / bowl from a
     * processed ingredient) into RETURN_SLOT via a deferred runnable.
     *
     * <p>IMPORTANT: The "盛出容器" (output container) of a CookingPotRecipe
     * is NEVER returned here — that item is explicitly consumed by the
     * machine as part of "dishing up the food" to match FarmersDelight
     * CookingPotBlockEntity semantics. We ONLY return containers for items
     * that themselves declare a remaining item (bucket → empty bucket) or
     * are one of the hand-crafted bottle returns. The distinction is made
     * in the caller (only ingredients passed to findAndConsumeOne are
     * eligible; CookingPotRecipe.outputContainer is treated as consumed and
     * never produces a return).
     */
    private void queueReturnFor(ItemStack consumedStack, Ingredient ingredient,
                                List<Runnable> deferredReturns) {
        if (consumedStack == null || consumedStack.isEmpty()) return;
        for (ItemStack ret : FluidIngredientHelper.getReturnStacksForConsumed(consumedStack, ingredient)) {
            if (ret.isEmpty()) continue;
            deferredReturns.add(() -> {
                // Prefer RETURN_SLOT. If the slot is full and cannot merge,
                // fall back to dropping on top of the block so the item is not
                // silently destroyed. Merging is done with the ItemStackHandler
                // directly (via insertItem).
                ItemStack remainder = items.insertItem(RETURN_SLOT, ret, false);
                if (!remainder.isEmpty() && level != null && !level.isClientSide) {
                    Containers.dropItemStack(level, worldPosition.getX() + 0.5,
                            worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, remainder);
                }
            });
        }
    }

    /**
     * 仅用于"能匹配吗？（不扣）"的固体材料匹配：同时包含 extras（油/容器）
     */
    private boolean canMatchAllIngredientsWithExtras(Recipe<?> recipe, List<ItemStack> inputs) {
        // 收集要匹配的 ingredients：固体 + 农夫乐事容器 + 森罗 extras
        List<Ingredient> need = new ArrayList<>();
        need.addAll(getSolidIngredients(recipe));
        if (recipe instanceof CookingPotRecipe) {
            Ingredient c = getContainerIngredient(recipe);
            if (!c.isEmpty()) need.add(c);
        }
        need.addAll(KaleidoscopeCompat.getExtraConsumables(recipe));
        if (need.isEmpty()) return false; // 没有材料需求的配方不算可合成
        if (inputs.size() < need.size()) return false;
        return canMatch(inputs, need);
    }

    // ==================================================================
    //   完整配方流程：匹配-完成-可放入 （Recipe<?> 通用：农夫 + 森罗 4 种）
    // ==================================================================

    /**
     * 完成一次处理：扣材料 + 流体 + 放入产物到 OUTPUT_SLOT。
     * 重要：不再返还 bowl/container 到 RETURN_SLOT；容器在 consumeAllMaterials
     *       里已经被**消耗**，对应农夫乐事的 CookingPotBlockEntity 盛出逻辑。
     */
    private void completeRecipe(Level level, Recipe<?> recipe) {
        if (!canFitAll(recipe)) return;
        consumeFluidForRecipe(recipe);
        // 固体+容器+附加油/carrier 一并扣 1 份
        consumeAllMaterials(recipe, 1, false);
        // 产物
        ItemStack result = getResultStack(recipe);
        if (!result.isEmpty()) insertOutput(items, result.copy(), OUTPUT_SLOT);
    }

    /**
     * 模拟一次产物放入：只看 OUTPUT_SLOT 能否容纳（不再写入 RETURN_SLOT）。
     */
    private boolean canFitAll(Recipe<?> recipe) {
        ItemStackHandler simulated = new cn.ism.mekck.util.BigStackItemHandler(items.getSlots());
        for (int slot = 0; slot < items.getSlots(); slot++) {
            simulated.setStackInSlot(slot, items.getStackInSlot(slot).copy());
        }
        ItemStack result = getResultStack(recipe);
        if (!result.isEmpty()) {
            return insertOutput(simulated, result.copy(), OUTPUT_SLOT).isEmpty();
        }
        return true;
    }

    private static ItemStack insertOutput(ItemStackHandler handler, ItemStack stack, int slot) {
        ItemStack remainder = stack.copy();
        ItemStack existing = handler.getStackInSlot(slot);
        if (existing.isEmpty()) {
            int moved = Math.min(remainder.getCount(), handler.getSlotLimit(slot));
            ItemStack inserted = remainder.copy();
            inserted.setCount(moved);
            handler.setStackInSlot(slot, inserted);
            remainder.shrink(moved);
        } else if (ItemStack.isSameItemSameTags(existing, remainder)) {
            int limit = handler.getSlotLimit(slot);
            int moved = Math.min(remainder.getCount(), limit - existing.getCount());
            if (moved > 0) {
                ItemStack merged = existing.copy();
                merged.grow(moved);
                handler.setStackInSlot(slot, merged);
                remainder.shrink(moved);
            }
        }
        return remainder;
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
    @Override public int[] getInputSlotRange() { return new int[]{INPUT_SLOT_START, OUTPUT_SLOT}; }
    @Override public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() { return items; }
    @Override public boolean supportsAutoPull() { return true; } // ME 持续补料：按"每类型上限"（配置 auto_pull_stack_limit）批量补，受 LagMonitor 限流

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) return List.of();
        // 取第一个当前可下料的配方（含农夫乐事/沉浸农艺 pot_cooking/森罗），返回其固体材料
        for (Recipe<?> recipe : getAvailableRecipes()) {
            List<Ingredient> solid = getSolidIngredients(recipe);
            if (solid.isEmpty()) continue;
            List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
            for (Ingredient ing : solid) {
                if (!ing.isEmpty()) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
            }
            return specs;
        }
        return List.of();
    }

    public int getStorageSlots() {
        return STORAGE_SLOT_COUNT;
    }

    public ContainerData getData() {
        return data;
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

    /**
     * Gets all available cooking recipes that can be crafted with current materials.
     * Checks both input slots and storage slots. Includes Kaleidoscope pot/stockpot
     * recipes if the Kaleidoscope mod is loaded.
     */
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> available = new ArrayList<>();
        List<ItemStack> allItems = new ArrayList<>();
        for (int j = INPUT_SLOT_START; j <= INPUT_SLOT_END; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) allItems.add(stack);
        }
        for (int j = STORAGE_SLOT_START; j < STORAGE_SLOT_START + STORAGE_SLOT_COUNT; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) allItems.add(stack);
        }
        if (allItems.isEmpty()) return available;

        // 农夫乐事 COOKING
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, ModRecipeTypes.COOKING.get())) {
            List<Ingredient> solidIngredients = getSolidIngredients(recipe);
            if (solidIngredients.isEmpty()) continue;
            if (!hasRequiredFluid(recipe)) continue;
            if (canMatchAllIngredientsWithExtras(recipe, allItems)) available.add(recipe);
        }
        // 沉浸农艺 pot_cooking
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeInputMatcher.getPotCookingRecipes(level)) {
            List<Ingredient> solidIngredients = getSolidIngredients(recipe);
            if (solidIngredients.isEmpty()) continue;
            if (canMatchAllIngredientsWithExtras(recipe, allItems)) available.add(recipe);
        }
        // 森罗物语 stockpot/pot + flex
        if (KaleidoscopeCompat.isLoaded()) {
            for (Recipe<?> recipe : KaleidoscopeCompat.getAllKaleidoscopeRecipes(level)) {
                List<Ingredient> solidIngredients = getSolidIngredients(recipe);
                if (solidIngredients.isEmpty()) continue;
                if (canMatchAllIngredientsWithExtras(recipe, allItems)) available.add(recipe);
            }
        }
        return dedupeRecipes(available);
    }

    /**
     * 按 Recipe 的 ResourceLocation 去重，避免同一配方因存储空间多格存放
     * 相同食材、或来自多个 RecipeType 被多次收集而在下单列表中重复显示。
     */
    private List<Recipe<?>> dedupeRecipes(List<Recipe<?>> recipes) {
        Set<ResourceLocation> seen = new HashSet<>();
        recipes.removeIf(recipe -> !seen.add(recipe.getId()));
        return recipes;
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
     * 返回当前材料下最多能合成该配方多少次。
     * 把固体食材 + 农夫乐事容器 + 森罗 carrier(非bowl)/pot oil
     * 统一视为 ingredient demand；对每种 item 计算 count/demand 取最小。
     * 流体上限基于 FluidIngredientHelper 的精确 waterMb/milkMb 分类：
     * - 水瓶: 250mb / 水桶: 1000mb water
     * - 奶瓶(#forge:milk / farmersdelight:milk_bottle): 250mb / 奶桶(milk_bucket): 1000mb milk
     */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        List<Ingredient> allNeeds = new ArrayList<>();
        allNeeds.addAll(getSolidIngredients(recipe));
        if (recipe instanceof CookingPotRecipe) {
            Ingredient c = getContainerIngredient(recipe);
            if (!c.isEmpty()) allNeeds.add(c);
        }
        allNeeds.addAll(KaleidoscopeCompat.getExtraConsumables(recipe));
        if (allNeeds.isEmpty()) return 0;

        // 流体限制：对水和奶分别除以"每份所需 mb"得到独立的份数上限，再取最小
        int fluidCap = Integer.MAX_VALUE;
        FluidIngredientHelper.FluidInfo fluid = getFluidPerUnit(recipe);
        if (!fluid.isEmpty()) {
            if (!hasRequiredFluid(recipe, 1)) fluidCap = 0;
            else {
                if (fluid.waterMb > 0) fluidCap = Math.min(fluidCap, fluidTank.totalOf(true) / fluid.waterMb);
                if (fluid.milkMb  > 0) fluidCap = Math.min(fluidCap, fluidTank.totalOf(false) / fluid.milkMb);
            }
        }

        // 聚合所有槽位的每种 item 总数（按 Item 聚合；忽略 tag 里不同 item 都能满足 ingredient 的情况——
        // 采取"对每个 ingredient，统计它可以用哪些 item，然后把这些 item 都记一份需求 1"，
        // 最后用每种 item 的可用量 / 该 item 对应需求数，取最小值——这是上界估算，
        // 是"可下单次数的保守上界"，与原版实现保持一致（且不扣真实物品）。
        Map<Item, Integer> itemCounts = new HashMap<>();
        List<ItemStack> allItems = new ArrayList<>();
        for (int j = INPUT_SLOT_START; j <= INPUT_SLOT_END; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) {
                itemCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                allItems.add(stack);
            }
        }
        for (int j = STORAGE_SLOT_START; j < STORAGE_SLOT_START + STORAGE_SLOT_COUNT; j++) {
            ItemStack stack = items.getStackInSlot(j);
            if (!stack.isEmpty()) {
                itemCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                allItems.add(stack);
            }
        }
        if (itemCounts.isEmpty() && !allNeeds.isEmpty()) return 0;

        Map<Item, Integer> ingredientDemand = new HashMap<>();
        for (Ingredient ing : allNeeds) {
            Set<Item> matchedItems = new HashSet<>();
            for (ItemStack stack : allItems) {
                if (ing.test(stack)) matchedItems.add(stack.getItem());
            }
            if (matchedItems.isEmpty()) return 0;
            for (Item item : matchedItems) ingredientDemand.merge(item, 1, Integer::sum);
        }

        int minCount = Integer.MAX_VALUE;
        for (Map.Entry<Item, Integer> entry : itemCounts.entrySet()) {
            Item item = entry.getKey();
            int totalCount = entry.getValue();
            int demand = ingredientDemand.getOrDefault(item, 0);
            if (demand > 0) {
                minCount = Math.min(minCount, totalCount / demand);
            }
        }
        minCount = Math.min(minCount, fluidCap);
        return minCount == Integer.MAX_VALUE ? 0 : minCount;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        return customName != null ? customName : Component.translatable("block.mekck.smart_cooking_pot");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SmartCookingPotMenu(containerId, inventory, this, data);
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
        int remainingEnergy = tag.getInt("Energy");
        while (remainingEnergy > 0) {
            int received = energy.receiveEnergy(remainingEnergy, false);
            if (received == 0) {
                break;
            }
            remainingEnergy -= received;
        }
        progress = tag.getInt("Progress");
        if (tag.contains("FluidTanks")) {
            fluidTank.readFromNBT(tag.getCompound("FluidTanks"));
        } else if (tag.contains("FluidAmount", Tag.TAG_INT)) {
            // 旧版单槽数据回退
            int fluidAmount = tag.getInt("FluidAmount");
            if (fluidAmount > 0 && tag.contains("Fluid", Tag.TAG_COMPOUND)) {
                FluidStack fluid = FluidStack.loadFluidStackFromNBT(tag.getCompound("Fluid"));
                if (!fluid.isEmpty()) {
                    fluidTank.getTank(0).setFluid(fluid);
                }
            }
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
        if (tag.contains("CustomName")) {
            customName = Component.Serializer.fromJson(tag.getString("CustomName"));
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
            } else if (mode == SideMode.PULL_INPUT_STORAGE) {
                return storageItemCapability.cast();
            } else if (mode == SideMode.PUSH_OUTPUT) {
                return outputItemCapability.cast();
            }
            return LazyOptional.empty();
        }
        if (capability == ForgeCapabilities.FLUID_HANDLER) {
            return fluidCapability.cast();
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
            return STORAGE_SLOT_COUNT;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= STORAGE_SLOT_COUNT) return ItemStack.EMPTY;
            return items.getStackInSlot(STORAGE_SLOT_START + slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= STORAGE_SLOT_COUNT) return stack;
            return items.insertItem(STORAGE_SLOT_START + slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot < 0 || slot >= STORAGE_SLOT_COUNT) return 0;
            return items.getSlotLimit(STORAGE_SLOT_START + slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot < 0 || slot >= STORAGE_SLOT_COUNT) return false;
            return items.isItemValid(STORAGE_SLOT_START + slot, stack);
        }
    }

    private final class InputItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return INPUT_SLOT_COUNT;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= INPUT_SLOT_COUNT) return ItemStack.EMPTY;
            return items.getStackInSlot(INPUT_SLOT_START + slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= INPUT_SLOT_COUNT) return stack;
            return items.insertItem(INPUT_SLOT_START + slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot < 0 || slot >= INPUT_SLOT_COUNT) return 0;
            return items.getSlotLimit(INPUT_SLOT_START + slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot < 0 || slot >= INPUT_SLOT_COUNT) return false;
            return items.isItemValid(INPUT_SLOT_START + slot, stack);
        }
    }

    private final class OutputItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return 2;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot == 0) return items.getStackInSlot(OUTPUT_SLOT);
            if (slot == 1) return items.getStackInSlot(RETURN_SLOT);
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == 0) return items.extractItem(OUTPUT_SLOT, amount, simulate);
            if (slot == 1) return items.extractItem(RETURN_SLOT, amount, simulate);
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot == 0) return items.getSlotLimit(OUTPUT_SLOT);
            if (slot == 1) return items.getSlotLimit(RETURN_SLOT);
            return 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }
}