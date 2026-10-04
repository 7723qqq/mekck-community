package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.SkeweringMachineBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.menu.SkeweringMachineMenu;
import cn.ism.mekck.machine.skewering.SkeweringFactoryExecutor;
import cn.ism.mekck.recipe.RecipeInputMatcher;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.LagMonitor;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.StorageMerger;
import cn.ism.mekck.upgrade.UpgradeHelper;
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
import cn.ism.mekck.registry.MekCkStandaloneMachines;

public final class SkeweringMachineBlockEntity extends MekCkLegacyMachine implements MenuProvider, cn.ism.mekck.ae2.INetworkPullable {
    public static final int INPUT_SLOT_START = 0;
    public static final int INPUT_SLOT_COUNT = 3;
    public static final int INPUT_SLOT_END = 2;
    public static final int OUTPUT_SLOT = 3;
    public static final int RETURN_SLOT = 4;
    public static final int SLOT_SPEED_UPGRADE = 5;
    public static final int SLOT_ENERGY_UPGRADE = 6;
    public static final int STORAGE_SLOT_START = 7;
    public static final int STORAGE_SLOT_COUNT = 81;
    public static final int SLOT_CREATIVE_UPGRADE = 7 + STORAGE_SLOT_COUNT;
    public static final int SLOT_POWER = 8 + STORAGE_SLOT_COUNT;
    public static final int TOTAL_SLOTS = 9 + STORAGE_SLOT_COUNT;
    public static final int ENERGY_CAPACITY = 100_000;
    public static final int ENERGY_PER_TICK = 20;
    public static final int PROCESS_TIME = 200;
    public static final int MAX_RECEIVE = 1_000;

    public static final int DATA_CREATIVE_UPGRADE = 8;
    public static final int DATA_REDSTONE_CONTROL = 9;
    /**
     * {@link #DATA_ENERGY} 的<b>高 16 位</b> —— 能量被拆成两个槽传输。
     *
     * <p>{@code ContainerData} 经 {@code ClientboundContainerSetDataPacket} 时对每个值
     * 只 {@code writeShort}（16 位有符号），而本机容量是 {@link #ENERGY_CAPACITY} = 10 万
     * ⇒ 不拆必然截断成负数。详见 {@link cn.ism.mekck.util.WideDataSlot}。</p>
     *
     * <p>取值 = 旧 {@code DATA_SIZE}，即<b>追加</b>到槽表末尾：下面 switch 里那些
     * {@code case 0}…{@code case 9} 字面量<b>一律不重编号</b>。顺带把能量的
     * {@code case 2} 换成命名常量，免得「下标 2 是能量」只存在于两处字面量里。</p>
     */
    public static final int DATA_ENERGY_HI = 10;
    public static final int DATA_SIZE = 11;

    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_SIDE_CONFIG = 3;
    public static final int DATA_SPEED_UPGRADE = 4;
    public static final int DATA_ENERGY_UPGRADE = 5;
    public static final int DATA_ORDER_QUANTITY = 6;
    public static final int DATA_ORDER_COMPLETED = 7;

    private Component customName;
    private int progress;

    // redstoneControl / redstonePowered / redstonePoweredLastTick 由 MekCkLegacyMachine 持有。
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
                return !isAnyUpgradeItem(stack) && RecipeInputMatcher.matchesSkewering(level, stack);
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
            if (slot >= STORAGE_SLOT_START && slot < STORAGE_SLOT_START + STORAGE_SLOT_COUNT) {
                // 存储槽只接受普通物品，不允许放入任何升级物品
                return !isAnyUpgradeItem(stack);
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

    /** 能量容器由基类持有；本机充放都允许、且每次实际充放都标脏，所以覆写 createEnergyStorage。 */
    @Override
    protected EnergyStorage createEnergyStorage(int capacity, int maxReceive) {
        // maxExtract 用 ENERGY_PER_TICK：对外抽取速率与本机自用速率同口径（迁移前逐字如此）。
        return new EnergyStorage(capacity, maxReceive, ENERGY_PER_TICK) {
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
    }

    private LazyOptional<IItemHandler> fullItemCapability;
    private LazyOptional<IItemHandler> inputItemCapability;
    private LazyOptional<IItemHandler> storageItemCapability;
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
                case 0 -> progress;
                case 1 -> getEffectiveProcessTime();
                case DATA_ENERGY -> energy.getEnergyStored() & 0xFFFF;
                case DATA_ENERGY_HI -> (energy.getEnergyStored() >>> 16) & 0xFFFF;
                case 3 -> encodeSideConfig();
                case 4 -> getSpeedUpgradeCount();
                case 5 -> getEnergyUpgradeCount();
                case 6 -> orderQuantity;
                case 7 -> orderCompleted;
                case 8 -> hasCreativeUpgrade() ? 1 : 0;
                case 9 -> redstoneControl.ordinal();
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

    public SkeweringMachineBlockEntity(BlockPos pos, BlockState state) {
        super(MekCkStandaloneMachines.SKEWERING_MACHINE_BLOCK_ENTITY.get(), pos, state,
                ENERGY_CAPACITY, MAX_RECEIVE);
        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
        }
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.storageItemCapability = LazyOptional.of(() -> new StorageItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SkeweringMachineBlockEntity machine) {
        boolean wasActive = machine.progress > 0;
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.compat.AE2Compat.serverTick(machine, level, pos);

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

        // PULSE 模式：收到红石信号(上升沿)时锁存，机器开始一次完整的处理
        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        boolean canOperate = machine.canFunctionRedstone();
        Optional<? extends Recipe<?>> recipe = machine.findRecipe(level);
        if (canOperate && recipe.isPresent() && machine.energy.getEnergyStored() >= energyPerTick
                && machine.canFitAll(recipe.get())) {
            // Check order requirements: if we have an order, verify we haven't completed it yet
            boolean canProcess = true;
            if (machine.orderQuantity > 0 && machine.orderCompleted >= machine.orderQuantity) {
                canProcess = false;
            }

            if (canProcess) {
                machine.energy.extractEnergy(energyPerTick, false);
                machine.progress++;
                if (machine.progress >= effectiveProcessTime) {
                    machine.completeRecipe(level, recipe.get());

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
            level.setBlock(pos, state.setValue(SkeweringMachineBlock.ACTIVE, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, SkeweringMachineBlockEntity machine) {
        if (state.getValue(SkeweringMachineBlock.ACTIVE)) {
            SoundHandler.startTileSound(MekanismSounds.ENRICHMENT_CHAMBER.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
        } else {
            SoundHandler.stopTileSound(pos);
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

    private RecipeType<?> getSkeweringRecipeType() {
        ResourceLocation id = new ResourceLocation("barbequesdelight", "skewering");
        return cn.ism.mekck.util.RecipeCache.type(id);
    }

    @SuppressWarnings("unchecked")
    private Optional<? extends Recipe<?>> findRecipe(Level level) {
        RecipeType<?> recipeType = getSkeweringRecipeType();
        if (recipeType == null) return Optional.empty();

        // 没有订单时不自动加工：先早退，省掉每 tick 的输入快照
        if (orderRecipeId == null) {
            return Optional.empty();
        }

        // Collect items from input slots (0-2)
        ItemStack[] inputStacks = new ItemStack[INPUT_SLOT_COUNT];
        for (int i = 0; i < INPUT_SLOT_COUNT; i++) {
            inputStacks[i] = items.getStackInSlot(INPUT_SLOT_START + i);
        }

        // If an order is active, only check the ordered recipe
        if (orderRecipeId != null) {
            for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, recipeType)) {
                if (recipe.getId().equals(orderRecipeId) && matchesSkewering(recipe, inputStacks)) {
                    return Optional.of(recipe);
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

            // slot 0 must match tool
            if (tool != null && !tool.isEmpty() && !inputStacks[0].isEmpty()) {
                if (!tool.test(inputStacks[0])) return false;
            } else if (tool != null && !tool.isEmpty() && inputStacks[0].isEmpty()) {
                return false;
            }

            // slot 1 must match ingredient
            if (ingredient != null && !ingredient.isEmpty() && !inputStacks[1].isEmpty()) {
                if (!ingredient.test(inputStacks[1])) return false;
            } else if (ingredient != null && !ingredient.isEmpty() && inputStacks[1].isEmpty()) {
                return false;
            }

            // Check side (optional, slot 2 may be empty)
            if (side != null && !side.isEmpty() && !inputStacks[2].isEmpty()) {
                if (!side.test(inputStacks[2])) return false;
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets count of items needed from a field (ingredientCount or sideCount).
     *
     * <p>无实例状态，{@code static} 是为了让 {@link #returnPreview} 也能读同一份口径
     * （预览与真扣料必须读同一个数，否则两处会各自漂移）。</p>
     */
    private static int getCountField(Recipe<?> recipe, String fieldName) {
        try {
            java.lang.reflect.Field field = cn.ism.mekck.util.Reflect.field(recipe.getClass(), fieldName);
            if (field == null) return 1;
            return field.getInt(recipe);
        } catch (Exception e) {
            return 1;
        }
    }

    private static Ingredient getIngredientField(Recipe<?> recipe, String fieldName) {
        try {
            java.lang.reflect.Field field = cn.ism.mekck.util.Reflect.field(recipe.getClass(), fieldName);
            if (field == null) return null;
            return (Ingredient) field.get(recipe);
        } catch (Exception e) {
            return null;
        }
    }

    private void completeRecipe(Level level, Recipe<?> recipe) {
        if (!canFitAll(recipe)) {
            return;
        }

        // Consume input items from input slots
        try {
            Ingredient tool = getIngredientField(recipe, "tool");
            Ingredient ingredient = getIngredientField(recipe, "ingredient");
            Ingredient side = getIngredientField(recipe, "side");
            int toolCount = getCountField(recipe, "ingredientCount");
            int sideCount = getCountField(recipe, "sideCount");

            // Consume tool (input slots first, then storage)
            List<ItemStack> consumedTool = List.of();
            if (tool != null && !tool.isEmpty()) {
                consumedTool = consumeInput(tool, toolCount);
            }

            // Consume ingredient (slot 1)
            if (ingredient != null && !ingredient.isEmpty()) {
                consumeInput(ingredient, 1);
            }

            // Consume side (slot 2)
            if (side != null && !side.isEmpty()) {
                consumeInput(side, sideCount);
            }

            // Insert output
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (!result.isEmpty()) {
                insertOutput(items, result.copy(), OUTPUT_SLOT);
            }

            // Return the tool item: 只返还本次实际扣掉的签子（类型与数量）。
            // 扣料位置无关（输入槽 + 存储槽），旧写法读槽 0 会在签子不在槽 0 时
            // 把槽 0 的另一种物品复制进返还槽（物品复制，M29 修复）；
            // 合并与工厂执行器共用 SkeweringFactoryExecutor.returnPayload，两边同口径。
            ItemStack returnStack = SkeweringFactoryExecutor.returnPayload(consumedTool);
            if (!returnStack.isEmpty()) {
                insertOutput(items, returnStack, RETURN_SLOT);
            }
        } catch (Exception e) {
            // Fallback: skip
        }
    }

    /**
     * 从输入槽与存储槽扣掉 count 个匹配 ingredient 的物品，返回实际扣掉的栈
     * （按扣料顺序；同物跨多槽会有多项）。位置无关：先输入槽、后存储槽。
     */
    private List<ItemStack> consumeInput(Ingredient ingredient, int count) {
        List<ItemStack> consumed = new ArrayList<>();
        int remaining = count;
        // Check input slots first
        for (int i = INPUT_SLOT_START; i <= INPUT_SLOT_END && remaining > 0; i++) {
            remaining = extractMatching(items, i, ingredient, remaining, consumed, false);
        }
        // Then check storage slots
        for (int i = STORAGE_SLOT_START; i < STORAGE_SLOT_START + STORAGE_SLOT_COUNT && remaining > 0; i++) {
            remaining = extractMatching(items, i, ingredient, remaining, consumed, false);
        }
        return consumed;
    }

    /**
     * 从单个槽扣掉至多 remaining 个匹配物品；返回还差多少，实际扣掉的栈记进 consumed。
     *
     * <p>{@code simulate = true} 时只预演、不改动任何槽 —— 返还预览与真扣料共用这一套
     * 走位，两处不会漂移（与工厂侧 {@code SkeweringFactoryExecutor.consumeOne} 同款）。</p>
     */
    private static int extractMatching(ItemStackHandler handler, int slot, Ingredient ingredient,
                                       int remaining, List<ItemStack> consumed, boolean simulate) {
        ItemStack stack = handler.getStackInSlot(slot);
        if (stack.isEmpty() || !ingredient.test(stack)) {
            return remaining;
        }
        int toExtract = Math.min(remaining, stack.getCount());
        ItemStack extracted = handler.extractItem(slot, toExtract, simulate);
        if (!extracted.isEmpty()) {
            consumed.add(extracted);
            remaining -= extracted.getCount();
        }
        return remaining;
    }

    /**
     * 开工前容量判定：产物槽与返还槽都要装得下。
     *
     * <p>返还槽原先不判：返还槽满（或槽里是别的物品）时机器照常开工，
     * {@link #completeRecipe} 先扣签子、{@code insertOutput} 的剩余量被静默丢弃
     * （每周期丢 toolCount 个签子）。返还量取 {@link #returnPreview} 的预演结果，
     * 与 {@code completeRecipe} 真正要落的返还物同口径 —— 与工厂侧
     * {@code SkeweringFactoryExecutor.canFitBatch} 的两段分开判同款。</p>
     *
     * <p>模拟副本必须回答与真槽相同的上限（{@code BigStackItemHandler} 默认 64，
     * 而本机 OUTPUT_SLOT / RETURN_SLOT 是 {@code Integer.MAX_VALUE}）：否则预检比
     * 真实落槽更严，返还槽堆到 64 个签子后预检永远失败、机器静默停摆
     * （与 {@code SmartCookingPotBlockEntity.canFitAll} 同款）。</p>
     */
    private boolean canFitAll(Recipe<?> recipe) {
        ItemStackHandler simulated = new cn.ism.mekck.util.BigStackItemHandler(items.getSlots()) {
            @Override
            public int getSlotLimit(int slot) {
                return items.getSlotLimit(slot);
            }
        };
        for (int slot = 0; slot < items.getSlots(); slot++) {
            simulated.setStackInSlot(slot, items.getStackInSlot(slot).copy());
        }

        // Check output slot
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (!result.isEmpty()) {
            if (!insertOutput(simulated, result.copy(), OUTPUT_SLOT).isEmpty()) {
                return false;
            }
        }

        // Check return slot: 返还槽装不下就不开工（否则签子已扣、返还落不进 ⇒ 静默丢失）
        ItemStack preview = returnPreview(recipe, items);
        if (!preview.isEmpty() && !insertOutput(simulated, preview, RETURN_SLOT).isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * 返还预览：预演一次签子扣料，返回本次会返还的栈 —— <b>不改动任何槽</b>。
     *
     * <p>与 {@link #completeRecipe} 的「返还 = 本次实际扣掉的签子」共用
     * {@link #extractMatching} 的同一套走位（先输入槽、后存储槽），所以
     * {@link #canFitAll} 判的容量与真正要落的返还物一致，不会出现
     * 「预演说装得下、落槽时却装不下」的漂移（与工厂侧
     * {@code SkeweringFactoryExecutor.returnPreview} 同口径）。</p>
     *
     * <p>闸门与 {@code completeRecipe} 相同：{@code toolCount <= 0}（自有配方签子不消耗）
     * 或 tool 配料为空时不返还，返还槽不参与判定。</p>
     */
    static ItemStack returnPreview(Recipe<?> recipe, ItemStackHandler handler) {
        Ingredient tool = getIngredientField(recipe, "tool");
        if (tool == null || tool.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int toolCount = getCountField(recipe, "ingredientCount");
        if (toolCount <= 0) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> consumed = new ArrayList<>();
        int remaining = toolCount;
        for (int i = INPUT_SLOT_START; i <= INPUT_SLOT_END && remaining > 0; i++) {
            remaining = extractMatching(handler, i, tool, remaining, consumed, true);
        }
        for (int i = STORAGE_SLOT_START; i < STORAGE_SLOT_START + STORAGE_SLOT_COUNT && remaining > 0; i++) {
            remaining = extractMatching(handler, i, tool, remaining, consumed, true);
        }
        return SkeweringFactoryExecutor.returnPayload(consumed);
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
        return cn.ism.mekck.upgrade.UpgradeHelper.speedMultiplier(getSpeedUpgradeCount());
    }

    public double getEffectiveEnergyConsumptionMultiplier() {
        return cn.ism.mekck.upgrade.UpgradeHelper.energyConsumptionMultiplier(getEnergyUpgradeCount());
    }

    public int getEffectiveProcessTime() {
        return Math.max(1, (int) (PROCESS_TIME / getEffectiveSpeedMultiplier()));
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // AE2 网格节点销毁（未安装 AE2 时为空操作；节点 NBT 由 saveAdditional 保存，重载后 init 重建）
        cn.ism.mekck.compat.AE2Compat.onRemoved(this);
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
    public List<cn.ism.mekck.ae2.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) return List.of();
        net.minecraft.world.item.crafting.RecipeType<?> type = cn.ism.mekck.util.RecipeCache.type(
                new ResourceLocation("barbequesdelight", "skewering"));
        if (type == null) return List.of();
        for (net.minecraft.world.item.crafting.Recipe<?> r :
                cn.ism.mekck.util.RecipeCache.all(level, type)) {
            List<net.minecraft.world.item.crafting.Ingredient> ings = r.getIngredients();
            if (ings.isEmpty()) continue;
            if (!ings.get(0).isEmpty() && !ings.get(0).test(items.getStackInSlot(INPUT_SLOT_START))) continue;
            List<cn.ism.mekck.ae2.AE2InputSpec> specs = new java.util.ArrayList<>();
            for (net.minecraft.world.item.crafting.Ingredient ing : ings) {
                if (!ing.isEmpty()) specs.add(new cn.ism.mekck.ae2.AE2InputSpec(ing));
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

    // ================== 红石控制 (Mekanism 逻辑) ==================
    // getRedstoneControl / setRedstoneControl 由 MekCkLegacyMachine 提供。

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

    /**
     * Gets all available skewering recipes that can be crafted with current materials.
     * Checks both input slots and storage slots.
     */
    @SuppressWarnings("unchecked")
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> available = new ArrayList<>();
        RecipeType<?> recipeType = getSkeweringRecipeType();
        if (recipeType == null) return available;

        // Collect items from input slots
        ItemStack[] inputStacks = new ItemStack[INPUT_SLOT_COUNT];
        for (int j = 0; j < INPUT_SLOT_COUNT; j++) {
            inputStacks[j] = items.getStackInSlot(INPUT_SLOT_START + j);
        }

        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, recipeType)) {
            if (matchesSkewering(recipe, inputStacks)) {
                available.add(recipe);
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

    /**
     * 下单。数量下界与 {@link MekCkOrderState#setOrder} 对齐：
     * <b>取消（{@code recipeId == null}）时清零、激活时夹到 ≥ 1</b>。
     *
     * <p>本类原先是 {@code orderQuantity = quantity} 原样存，与其余实现契约不一致 ——
     * 只靠 {@code OrderRecipePacket} / {@code NetworkOrderPacket} 入口恰好夹过才没出事。
     * 写坏之后是<b>静默</b>故障：订单门禁与推进同时失效，订单永不完成。</p>
     */
    public void setOrder(@Nullable ResourceLocation recipeId, int quantity) {
        this.orderRecipeId = recipeId;
        this.orderQuantity = recipeId == null ? 0 : Math.max(1, quantity);
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
     * Public wrapper to get the maximum number of times a recipe can be crafted with current materials.
     */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        try {
            Ingredient tool = getIngredientField(recipe, "tool");
            Ingredient ingredient = getIngredientField(recipe, "ingredient");
            Ingredient side = getIngredientField(recipe, "side");
            int toolCount = getCountField(recipe, "ingredientCount");
            int sideCount = getCountField(recipe, "sideCount");

            // Collect items from input slots + storage for matching
            List<ItemStack> availableItems = new ArrayList<>();
            for (int j = INPUT_SLOT_START; j <= INPUT_SLOT_END; j++) {
                ItemStack stack = items.getStackInSlot(j);
                if (!stack.isEmpty()) {
                    availableItems.add(stack);
                }
            }
            for (int j = STORAGE_SLOT_START; j < STORAGE_SLOT_START + STORAGE_SLOT_COUNT; j++) {
                ItemStack stack = items.getStackInSlot(j);
                if (!stack.isEmpty()) {
                    availableItems.add(stack);
                }
            }

            // Find matching items for each ingredient
            int minCount = Integer.MAX_VALUE;

            // toolCount <= 0（自有配方：签子不消耗）时该项不参与限制：
            // 原实现 toolAvailable / 0 抛 ArithmeticException，被外层 catch 吞成「Max 恒 0」。
            if (tool != null && !tool.isEmpty() && toolCount > 0) {
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

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        // 键必须与注册名一致：方块注册为 mekck:smart_skewering_machine（UniversalCuttingMachine:645），
        // 原先写的 block.mekck.skewering_machine 在语言文件里不存在 ⇒ GUI 标题显示 raw key。
        return customName != null ? customName : Component.translatable("block.mekck.smart_skewering_machine");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SkeweringMachineMenu(containerId, inventory, this, data);
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
        cn.ism.mekck.compat.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        tag.put("Items", items.serializeNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Progress", progress);
        byte[] sideBytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            sideBytes[i] = (byte) sideConfig[i].ordinal();
        }
        tag.putByteArray("SideConfig", sideBytes);
        // RedstoneControl / RedstonePowered 由 MekCkLegacyMachine.saveAdditional 统一写。
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
        cn.ism.mekck.compat.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
        items.deserializeNBT(tag.getCompound("Items"));
        // 能量与红石由 MekCkLegacyMachine.load 统一读。
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
        // Load order data
        if (tag.contains("OrderRecipeId")) {
            orderRecipeId = ResourceLocation.tryParse(tag.getString("OrderRecipeId"));
        }
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        orderQuantity = tag.getInt("OrderQuantity");
        // 读档同样过 setOrder 的契约闸门：orderRecipeId != null 时数量必须 ≥ 1，
        // 否则订单门禁与完成推进同时失效（机器无限加工、订单永不完成）。
        if (orderRecipeId != null) orderQuantity = Math.max(1, orderQuantity);
        orderCompleted = tag.getInt("OrderCompleted");
    }

    @Override
    protected <T> LazyOptional<T> exposeItemCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
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
        return super.exposeItemCapability(capability, side);
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