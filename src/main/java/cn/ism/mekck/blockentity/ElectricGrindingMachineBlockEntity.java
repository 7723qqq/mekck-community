package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.ElectricGrindingMachineBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.menu.ElectricGrindingMachineMenu;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.AutoIO;
import cn.ism.mekck.util.KaleidoscopeCompat;
import cn.ism.mekck.util.LagMonitor;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.UpgradeHelper;
import mekanism.client.sound.SoundHandler;
import mekanism.common.registries.MekanismSounds;
import net.minecraft.core.BlockPos;
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

/**
 * 电力研磨机：处理 kaleidoscope_cookery 石磨配方（每次研磨随机产出）。
 * 模板参考 UniversalCuttingMachineBlockEntity。
 */
public final class ElectricGrindingMachineBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, cn.ism.mekck.ae2.INetworkPullable {
    public static final int INPUT_SLOT = 0;
    public static final int OUTPUT_SLOT = 1;
    public static final int SLOT_SPEED_UPGRADE = 2;
    public static final int SLOT_ENERGY_UPGRADE = 3;
    public static final int SLOT_CREATIVE_UPGRADE = 4;
    public static final int SLOT_POWER = 5;
    public static final int TOTAL_SLOTS = 6;
    public static final int ENERGY_CAPACITY = 100_000;
    public static final int ENERGY_PER_TICK = 20;
    public static final int PROCESS_TIME = 200;
    public static final int MAX_RECEIVE = 1_000;

    // ContainerData indices
    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_SIDE_CONFIG = 3;
    public static final int DATA_SPEED_UPGRADE = 4;
    public static final int DATA_ENERGY_UPGRADE = 5;
    public static final int DATA_CREATIVE_UPGRADE = 6;
    public static final int DATA_REDSTONE_CONTROL = 7;
    /**
     * {@link #DATA_ENERGY} 的<b>高 16 位</b> —— 能量被拆成两个槽传输。
     *
     * <p>{@code ContainerData} 经 {@code ClientboundContainerSetDataPacket} 时对每个值
     * 只 {@code writeShort}（16 位有符号），而本机容量是 {@link #ENERGY_CAPACITY} = 10 万
     * ⇒ 不拆必然截断成负数。详见 {@link cn.ism.mekck.util.WideDataSlot}。</p>
     *
     * <p>取值 = 旧 {@code DATA_SIZE}，即<b>追加</b>到槽表末尾：现有下标一律不动。</p>
     */
    public static final int DATA_ENERGY_HI = 8;
    public static final int DATA_SIZE = 9;

    private Component customName;
    private int progress;

    // ================== ME 终端下单（AE2） ==================
    private net.minecraft.resources.ResourceLocation orderRecipeId;
    private int orderQuantity;
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true;

    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    private boolean pulseRunning = false;

    private final SideMode[] sideConfig = new SideMode[6];

    private final ItemStackHandler items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (slot == INPUT_SLOT) {
                // 石磨配方输入 或 烘焙坊筛粉配方输入 或 沉浸农艺绞碎输入 或 mekck 磨粉输入（§F19 D 半）
                return !isAnyUpgradeItem(stack)
                        && (RecipeInputMatcher.matchesMillstone(level, stack)
                        || matchesFlourSieve(level, stack)
                        || matchesMincer(level, stack)
                        || matchesGrinding(level, stack));
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
            if (slot == SLOT_POWER) {
                return 64;
            }
            return MekckConfig.getBasicSpeedUpgradeMax();
        }

        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            if (slot == INPUT_SLOT || slot == OUTPUT_SLOT) {
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
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
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

    public ElectricGrindingMachineBlockEntity(BlockPos pos, BlockState state) {
        super(UniversalCuttingMachine.GRINDING_MACHINE_BLOCK_ENTITY.get(), pos, state);
        for (int i = 0; i < 6; i++) {
            sideConfig[i] = SideMode.NONE;
        }
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ElectricGrindingMachineBlockEntity machine) {
        boolean wasActive = machine.progress > 0;
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.util.AE2Compat.serverTick(machine, level, pos);


        machine.updateRedstone();

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

        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        boolean canOperate = machine.canFunctionRedstone();
        Optional<Recipe<?>> recipe = machine.findRecipe(level);
        if (canOperate && recipe.isPresent() && machine.energy.getEnergyStored() >= energyPerTick
                && machine.canFitAll(machine.grindingOutputs(recipe.get()))) {
            machine.energy.extractEnergy(energyPerTick, false);
            machine.progress++;
            if (machine.progress >= effectiveProcessTime) {
                machine.completeRecipe(level, recipe.get());
                machine.progress = 0;
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
            if (machine.redstoneControl == RedstoneControl.PULSE && machine.pulseRunning) {
                machine.pulseRunning = false;
                machine.setChanged();
            }
        }
        if (LagMonitor.shouldRunIO(level.getGameTime(), pos)) machine.autoIO(level, pos);

        boolean isActive = machine.progress > 0;
        if (wasActive != isActive) {
            level.setBlock(pos, state.setValue(ElectricGrindingMachineBlock.ACTIVE, isActive), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, ElectricGrindingMachineBlockEntity machine) {
        if (state.getValue(ElectricGrindingMachineBlock.ACTIVE)) {
            SoundHandler.startTileSound(MekanismSounds.CRUSHER.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.random, pos);
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
        if (items.getStackInSlot(INPUT_SLOT).isEmpty()) {
            return Optional.empty();
        }
        // ME 下单：只执行订单指定的配方
        if (orderRecipeId != null) {
            Optional<Recipe<?>> ordered = findRecipeById(level, orderRecipeId);
            return ordered.filter(r -> matchesInput(r));
        }
        Optional<Recipe<?>> millstone = KaleidoscopeCompat.findMillstoneRecipe(level, items.getStackInSlot(INPUT_SLOT));
        if (millstone.isPresent()) return millstone;
        // 石磨未命中 → 烘焙坊 bakeries:flour_sieve（4 配方：筛粉类，单输入单输出）
        Optional<Recipe<?>> sieve = findFlourSieveRecipe(level);
        if (sieve.isPresent()) return sieve;
        // 仍未命中 → 沉浸农艺 farm_and_charm:mincer（53 配方：绞碎类，单输入单输出）
        Optional<Recipe<?>> mincer = findMincerRecipe(level);
        if (mincer.isPresent()) return mincer;
        // 仍未命中 → mekck:grinding（§F19 D 半：炒榛子→榛子粉等磨粉工序，单输入单输出）
        return findGrindingRecipe(level);
    }

    /** 按配方 id 在石磨 / 筛粉 / 绞碎 / mekck 磨粉四类配方中查找。 */
    private Optional<Recipe<?>> findRecipeById(Level level, net.minecraft.resources.ResourceLocation id) {
        for (String typeId : new String[]{"kaleidoscope_cookery:millstone", "bakeries:flour_sieve",
                "farm_and_charm:mincer", "mekck:grinding"}) {
            // 走 RecipeCache.type：石磨类型在森罗厨房的注册表里根本查不到（它造了匿名 RecipeType 却不注册，
            // 详见 cn.ism.mekck.util.TavernBarrelCompat#typeById），直查注册表会让石磨这一路整条静默失效。
            net.minecraft.world.item.crafting.RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(
                    new net.minecraft.resources.ResourceLocation(typeId));
            if (rt == null) continue;
            Optional<? extends Recipe<?>> found = level.getRecipeManager().byKey(id);
            if (found.isPresent() && found.get().getType() == rt) return Optional.of(found.get());
        }
        return Optional.empty();
    }

    /**
     * 供「本机下单」面板展示：输入槽里的物品能做的**全部**配方（石磨 / 筛粉 / 绞碎 / mekck 磨粉四类）。
     */
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> out = new ArrayList<>();
        if (level == null) return out;
        ItemStack input = items.getStackInSlot(INPUT_SLOT);
        if (input.isEmpty()) return out;
        for (String typeId : new String[]{"kaleidoscope_cookery:millstone", "bakeries:flour_sieve",
                "farm_and_charm:mincer", "mekck:grinding"}) {
            net.minecraft.world.item.crafting.RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(
                    new net.minecraft.resources.ResourceLocation(typeId));
            if (rt == null) continue;
            for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
                if (matchesInput(r)) out.add(r);
            }
        }
        return out;
    }

    /** 供「本机下单」面板的 Max 按钮：输入槽现有材料能做几份（每份消耗 1 个输入）。 */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        if (recipe == null || !matchesInput(recipe)) return 0;
        return Math.max(0, items.getStackInSlot(INPUT_SLOT).getCount());
    }

    /** 输入槽物品是否满足该配方。 */
    private boolean matchesInput(Recipe<?> recipe) {
        ItemStack input = items.getStackInSlot(INPUT_SLOT);
        if (input.isEmpty()) return false;
        try {
            List<Ingredient> ings = recipe.getIngredients();
            return ings.isEmpty() || ings.get(0).test(input);
        } catch (Throwable t) {
            return false;
        }
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

    /** 输入是否匹配任一沉浸农艺绞碎配方（用于 isItemValid）。 */
    private static boolean matchesMincer(Level level, ItemStack stack) {
        if (stack.isEmpty()) return false;
        net.minecraft.world.item.crafting.RecipeType<?> rt =
                cn.ism.mekck.util.RecipeCache.type(new net.minecraft.resources.ResourceLocation("farm_and_charm", "mincer"));
        if (rt == null || level == null) return false;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (!ings.isEmpty() && ings.get(0).test(stack)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 输入是否匹配任一烘焙坊筛粉配方（用于 isItemValid）。 */
    private static boolean matchesFlourSieve(Level level, ItemStack stack) {
        if (stack.isEmpty()) return false;
        net.minecraft.world.item.crafting.RecipeType<?> rt =
                cn.ism.mekck.util.RecipeCache.type(new net.minecraft.resources.ResourceLocation("bakeries", "flour_sieve"));
        if (rt == null || level == null) return false;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (!ings.isEmpty() && ings.get(0).test(stack)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 烘焙坊 bakeries:flour_sieve：单输入（槽 0 匹配 ingredient），输出为固定单物品。 */
    private Optional<Recipe<?>> findFlourSieveRecipe(Level level) {
        net.minecraft.world.item.crafting.RecipeType<?> rt =
                cn.ism.mekck.util.RecipeCache.type(new net.minecraft.resources.ResourceLocation("bakeries", "flour_sieve"));
        if (rt == null || level == null) return Optional.empty();
        ItemStack in = items.getStackInSlot(INPUT_SLOT);
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty() || !ings.get(0).test(in)) continue;
                if (r.getResultItem(level.registryAccess()).isEmpty()) continue;
                return Optional.of(r);
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    /** 判定是否为烘焙坊筛粉配方。 */
    private static boolean isFlourSieveRecipe(Recipe<?> recipe) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        return id != null && "bakeries".equals(id.getNamespace()) && "flour_sieve".equals(id.getPath());
    }

    /** 统一产出列表：石磨 = 随机产出；筛粉/绞碎/mekck 磨粉 = 固定单产出（chance 1.0，必出）。 */
    private List<KaleidoscopeCompat.MillstoneOutput> grindingOutputs(Recipe<?> recipe) {
        if (isFlourSieveRecipe(recipe) || isMincerRecipe(recipe) || isGrindingRecipe(recipe)) {
            ItemStack out = recipe.getResultItem(level != null ? level.registryAccess() : null);
            return out.isEmpty() ? List.of() : List.of(new KaleidoscopeCompat.MillstoneOutput(out.copy(), 1.0F));
        }
        return KaleidoscopeCompat.getMillstoneOutputs(recipe);
    }

    /** 判定是否为 mekck:grinding 磨粉配方（§F19 D 半）。 */
    private static boolean isGrindingRecipe(Recipe<?> recipe) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        return id != null && "mekck".equals(id.getNamespace()) && "grinding".equals(id.getPath());
    }

    /** mekck:grinding（§F19 D 半：磨粉）：单输入（槽 0 匹配 ingredient）+ 固定结果。 */
    private Optional<Recipe<?>> findGrindingRecipe(Level level) {
        net.minecraft.world.item.crafting.RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(
                new net.minecraft.resources.ResourceLocation("mekck", "grinding"));
        if (rt == null || level == null) return Optional.empty();
        ItemStack in = items.getStackInSlot(INPUT_SLOT);
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty() || !ings.get(0).test(in)) continue;
                if (r.getResultItem(level.registryAccess()).isEmpty()) continue;
                return Optional.of(r);
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    /** 输入是否匹配任一 mekck 磨粉配方（用于 isItemValid）。 */
    private static boolean matchesGrinding(Level level, ItemStack stack) {
        if (stack.isEmpty()) return false;
        net.minecraft.world.item.crafting.RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(
                new net.minecraft.resources.ResourceLocation("mekck", "grinding"));
        if (rt == null || level == null) return false;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (!ings.isEmpty() && ings.get(0).test(stack)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 沉浸农艺 farm_and_charm:mincer（53 配方）：单输入（槽 0 匹配 ingredient）+ 固定结果。 */
    private Optional<Recipe<?>> findMincerRecipe(Level level) {
        net.minecraft.world.item.crafting.RecipeType<?> rt =
                cn.ism.mekck.util.RecipeCache.type(new net.minecraft.resources.ResourceLocation("farm_and_charm", "mincer"));
        if (rt == null || level == null) return Optional.empty();
        ItemStack in = items.getStackInSlot(INPUT_SLOT);
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty() || !ings.get(0).test(in)) continue;
                if (r.getResultItem(level.registryAccess()).isEmpty()) continue;
                return Optional.of(r);
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    /** 判定是否为沉浸农艺绞碎配方。 */
    private static boolean isMincerRecipe(Recipe<?> recipe) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        return id != null && "farm_and_charm".equals(id.getNamespace()) && "mincer".equals(id.getPath());
    }

    private void completeRecipe(Level level, Recipe<?> recipe) {
        List<KaleidoscopeCompat.MillstoneOutput> outputs = grindingOutputs(recipe);
        if (outputs.isEmpty()) return;
        if (!canFitAll(outputs)) return;
        items.extractItem(INPUT_SLOT, 1, false);
        for (KaleidoscopeCompat.MillstoneOutput output : outputs) {
            if (level.random.nextFloat() < output.chance()) {
                insertOutput(items, output.stack().copy());
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

    /** 最坏情况检查：假设所有随机产出全部命中，判定输出空间是否足够。 */
    /**
     * 输出容量判定：只跟踪产物槽的累计数量，**不再克隆整个 ItemStackHandler**。
     * 原实现每次调用都要 new 一个 handler 并把每个槽 copy 一份（该方法在 tick 路径上）。
     * 语义与 {@link #insertOutput} 的模拟插入一致：产物只能进产物槽（单槽），先同物品叠加、再占空位。
     */
    private boolean canFitAll(List<KaleidoscopeCompat.MillstoneOutput> outputs) {
        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT);
        ItemStack slotItem = existing.isEmpty() ? null : existing;
        int slotCount = existing.isEmpty() ? 0 : existing.getCount();
        int slotLimit = items.getSlotLimit(OUTPUT_SLOT);
        for (KaleidoscopeCompat.MillstoneOutput output : outputs) {
            ItemStack stack = output.stack();
            if (stack == null || stack.isEmpty()) continue;
            int remaining = stack.getCount();
            if (slotItem == null) {
                int moved = Math.min(remaining, slotLimit);
                slotItem = stack;
                slotCount = moved;
                remaining -= moved;
            } else if (ItemStack.isSameItemSameTags(slotItem, stack)) {
                int space = slotLimit - slotCount;
                if (space > 0) {
                    int moved = Math.min(remaining, space);
                    slotCount += moved;
                    remaining -= moved;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack insertOutput(ItemStackHandler handler, ItemStack stack) {
        ItemStack remainder = stack.copy();
        for (int slot = OUTPUT_SLOT; slot <= OUTPUT_SLOT && !remainder.isEmpty(); slot++) {
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

    // ================== 红石控制 ==================
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
                level, new ResourceLocation("farmersdelight", "cutting"));
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
        return customName != null ? customName : Component.translatable("block.mekck.electric_grinding_machine");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ElectricGrindingMachineMenu(containerId, inventory, this, data);
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
        if (orderRecipeId != null) {
            tag.putString("OrderRecipeId", orderRecipeId.toString());
            tag.putInt("OrderQuantity", orderQuantity);
            tag.putInt("OrderCompleted", orderCompleted);
        }
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
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
        if (tag.contains("OrderRecipeId")) {
            orderRecipeId = new net.minecraft.resources.ResourceLocation(tag.getString("OrderRecipeId"));
            orderQuantity = tag.getInt("OrderQuantity");
            orderCompleted = tag.getInt("OrderCompleted");
        }
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
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
