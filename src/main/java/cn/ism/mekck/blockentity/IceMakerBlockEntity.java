package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.IceMakerBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.entity.IceCubeEntity;
import cn.ism.mekck.item.ColdBrewTier;
import cn.ism.mekck.item.ColdBrewUpgradeItem;
import cn.ism.mekck.menu.IceMakerMenu;
import cn.ism.mekck.recipe.IceMakeRecipe;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.util.ColdBrewHelper;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.UpgradeHelper;
import net.minecraft.core.BlockPos;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IMekanismHeatHandler;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import cn.ism.mekck.util.IceTargetSearch;

public final class IceMakerBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, mekanism.api.heat.IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable {
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
    public static final int CB_SLOT_1 = 5;
    public static final int CB_SLOT_2 = 6;
    public static final int CB_SLOT_3 = 7;
    public static final int CB_SLOT_4 = 8;
    /** 失温升级槽（冷萃⑤，需先装龙霜/女王④）。 */
    public static final int CB_SLOT_5 = 9;
    public static final int SLOT_POWER = 10;
    public static final int TOTAL_SLOTS = 11;

    public static final int ENERGY_CAPACITY = 300_000;
    public static final int ENERGY_PER_TICK = 60;
    public static final int PROCESS_TIME = 100;

    // ================== 温度系统（Mekanism 热容量） ==================
    /** 工作温度上限：机身温度必须低于该值（273.15 K = 0 ℃）才允许加工。 */
    public static final double WORK_TEMP_LIMIT_K = 273.15;
    /** 最快速度倍率（机身温度 0 K 绝对零度时）；数值可按配置调整。 */
    public static final double MAX_SPEED_MULTIPLIER = 30.0;
    /** 热容量（J/K）：与 Mekanism 电阻型加热器一致。 */
    public static final double HEAT_CAPACITY = 100.0;
    /** 制冷能量效率：为电阻型加热器效率（0.6）的 1/3，即 1 FE → 0.2 J 热量转移。 */
    public static final double COOLING_EFFICIENCY = 0.2;
    /** 自身制冷最大功率（FE/t）：按需调节，不超过该值。 */
    public static final int COOLING_MAX_ENERGY_PER_TICK = 4000;
    private cn.ism.mekck.util.MekCkHeatComponent heatComponent;
    private final net.minecraftforge.common.util.LazyOptional<mekanism.api.heat.IHeatHandler> heatCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> heatComponent.getHandler());
    public static final int MAX_RECEIVE = 5_000;
    public static final int WATER_CAPACITY = 256_000;

    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_SIDE_CONFIG = 3;
    public static final int DATA_SPEED_UPGRADE = 4;
    public static final int DATA_ENERGY_UPGRADE = 5;
    public static final int DATA_CREATIVE_UPGRADE = 6;
    public static final int DATA_REDSTONE_CONTROL = 7;
    public static final int DATA_CB1 = 8;
    public static final int DATA_CB2 = 9;
    public static final int DATA_CB3 = 10;
    public static final int DATA_CB4 = 11;
    public static final int DATA_CB5 = 12;
    public static final int DATA_TARGET_TYPE = 13;
    public static final int DATA_RADIUS = 14;
    /** 水罐流体量（mb）与流体注册 id：经 ContainerData 同步到客户端（流体条显示用，FluidTank 不自动进网络）。 */
    public static final int DATA_WATER_AMOUNT = 15;
    public static final int DATA_WATER_FLUID_ID = 16;
    /** 当前机身温度（单位：0.01 ℃，便于显示小数）。 */
    public static final int DATA_CURRENT_TEMP = 17;
    /** 设定温度（单位：0.01 ℃）。 */
    public static final int DATA_TARGET_TEMP = 18;
    /** 设定温度功能开关（0 关 / 1 开）。 */
    public static final int DATA_TEMP_CONTROL = 19;
    /**
     * {@link #DATA_ENERGY} 的<b>高 16 位</b> —— 能量被拆成两个槽传输。
     *
     * <p>{@code ContainerData} 经 {@code ClientboundContainerSetDataPacket} 时对每个值
     * 只 {@code writeShort}（16 位有符号），而本机容量是 {@link #ENERGY_CAPACITY} = 30 万
     * ⇒ 不拆必然截断成负数。详见 {@link cn.ism.mekck.util.WideDataSlot}。</p>
     *
     * <p>取值 = 旧 {@code DATA_SIZE}，即<b>追加</b>到槽表末尾：现有下标一律不动。</p>
     */
    public static final int DATA_ENERGY_HI = 20;
    public static final int DATA_SIZE = 21;

    /** 目标类型：0=敌对生物（配置文件敌对列表），1=全部生物，2=非敌对生物（动物）。 */
    public static final int TARGET_HOSTILE = 0;
    public static final int TARGET_ALL = 1;
    public static final int TARGET_ANIMAL = 2;

    private Component customName;
    private int progress;

    // ================== ME 终端下单（AE2） ==================
    private net.minecraft.resources.ResourceLocation orderRecipeId;
    private int orderQuantity;
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true;
    // 冷萃升级读条（每槽 1 个，复刻 Mekanism 安装语义：读条完成后安装并消耗槽位物品）
    /** 常规升级读条（速度不支持；能量 / 创造）。 */
    private final cn.ism.mekck.util.MekCkUpgradeTracker energyTracker =
            new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getBasicEnergyUpgradeMax());
    private final cn.ism.mekck.util.MekCkUpgradeTracker creativeTracker =
            new cn.ism.mekck.util.MekCkUpgradeTracker(1);
    private final cn.ism.mekck.util.MekCkUpgradeTracker[] coldBrewTrackers = {
            new cn.ism.mekck.util.MekCkUpgradeTracker(1), new cn.ism.mekck.util.MekCkUpgradeTracker(1),
            new cn.ism.mekck.util.MekCkUpgradeTracker(1), new cn.ism.mekck.util.MekCkUpgradeTracker(1),
            new cn.ism.mekck.util.MekCkUpgradeTracker(1)};
    /** 各冷萃槽已安装的等级（null = 未安装）。 */
    private final cn.ism.mekck.item.ColdBrewTier[] installedColdBrew = new cn.ism.mekck.item.ColdBrewTier[5];

    /** 设定温度（单位 0.01 ℃；默认 -27315 = -273.15 ℃，即全力制冷）。 */
    private int targetTemperature = -27315;
    /** 设定温度功能开关：开启后自动制冷至设定温度。 */
    private boolean temperatureControlEnabled = true;
    private int attackTimer = 0;
    /** F10：buff 源（bakery_oven）位置；null = 未被增益。服务端权威，客户端经 getUpdatePacket 同步仅用于画线。 */
    @Nullable
    private BlockPos buffOwnerPos;
    /** F10：归属扫描冷却（每 20 tick 重扫一次）。 */
    private int buffScanCooldown = 0;
    /**
     * 待生成冰块的目标分配表（目标 → 剩余冰块数）：
     * 攻击同一目标的冰块保持排队（逐 tick 依次生成），攻击不同目标的冰块在同一 tick 一起生成。
     */
    private final java.util.LinkedHashMap<LivingEntity, Integer> pendingAttackTargets = new java.util.LinkedHashMap<>();
    private float pendingDamage;
    private boolean pendingAoe;
    /** 本次发射的溅射伤害（低温 5 / 凛冰 20 / 龙霜 40）。 */
    private float pendingSplash;
    private boolean pendingSlow;
    private boolean pendingRemoveAI;
    private boolean pendingHypothermia;
    private int targetType = TARGET_HOSTILE;
    private int radius = MekckConfig.getIceAttackRadius();

    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    private boolean pulseRunning = false;

    private final SideMode[] sideConfig = new SideMode[6];

    private final ItemStackHandler items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (slot == INPUT_SLOT) return !isAnyUpgrade(stack) && RecipeInputMatcher.matchesIceMake(level, stack);
            if (slot == OUTPUT_SLOT) return false;
            if (slot == SLOT_SPEED_UPGRADE) return false; // 急冻制冰机不支持速度升级（速度由机身温度决定）
            if (slot == SLOT_ENERGY_UPGRADE) return UpgradeHelper.isEnergyUpgrade(stack);
            if (slot == SLOT_CREATIVE_UPGRADE) return UpgradeHelper.isCreativeUpgrade(stack);
            if (slot == CB_SLOT_1) return ColdBrewUpgradeItem.getTier(stack) == ColdBrewTier.COLD;
            if (slot == CB_SLOT_2) return ColdBrewUpgradeItem.getTier(stack) == ColdBrewTier.LOW_TEMP && !items.getStackInSlot(CB_SLOT_1).isEmpty();
            if (slot == CB_SLOT_3) return ColdBrewUpgradeItem.getTier(stack) == ColdBrewTier.FROST && !items.getStackInSlot(CB_SLOT_2).isEmpty();
            if (slot == CB_SLOT_4) {
                ColdBrewTier t = ColdBrewUpgradeItem.getTier(stack);
                return (t == ColdBrewTier.DRAGON_FROST || t == ColdBrewTier.QUEEN) && !items.getStackInSlot(CB_SLOT_3).isEmpty();
            }
            if (slot == CB_SLOT_5) {
                return ColdBrewUpgradeItem.getTier(stack) == ColdBrewTier.HYPOTHERMIA && !items.getStackInSlot(CB_SLOT_4).isEmpty();
            }
            if (slot == SLOT_POWER) return PowerSlotUtil.isValidEnergyItem(stack);
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot == INPUT_SLOT || slot == OUTPUT_SLOT) return Integer.MAX_VALUE;
            if (slot == SLOT_CREATIVE_UPGRADE) return 1;
            if (slot == SLOT_POWER) return 64;
            if (slot == CB_SLOT_1 || slot == CB_SLOT_2 || slot == CB_SLOT_3 || slot == CB_SLOT_4 || slot == CB_SLOT_5) return 1;
            return MekckConfig.getBasicSpeedUpgradeMax();
        }

        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            if (slot == INPUT_SLOT || slot == OUTPUT_SLOT || slot == SLOT_CREATIVE_UPGRADE
                    || slot == CB_SLOT_1 || slot == CB_SLOT_2 || slot == CB_SLOT_3 || slot == CB_SLOT_4 || slot == CB_SLOT_5) {
                return getSlotLimit(slot);
            }
            return super.getStackLimit(slot, stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final FluidTank waterTank = new FluidTank(WATER_CAPACITY) {
        @Override
        public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid() == net.minecraft.world.level.material.Fluids.WATER;
        }

        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };

    // 第三参数为最大提取速率：必须覆盖制冷峰值（4000 FE/t），否则制冷耗电被截断为 60 FE/t
    private final EnergyStorage energy = new EnergyStorage(ENERGY_CAPACITY, MAX_RECEIVE, COOLING_MAX_ENERGY_PER_TICK) {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = super.receiveEnergy(maxReceive, simulate);
            if (!simulate && received > 0) setChanged();
            return received;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int extracted = super.extractEnergy(maxExtract, simulate);
            if (!simulate && extracted > 0) setChanged();
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
    private LazyOptional<IFluidHandler> fluidCapability;

    private final ContainerData data = new ContainerData() {
        private int[] stored;

        private int[] getStored() {
            if (stored == null) stored = new int[DATA_SIZE];
            return stored;
        }

        @Override
        public int get(int index) {
            if (level != null && level.isClientSide) {
                if (stored != null && index >= 0 && index < stored.length) return stored[index];
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
                case DATA_CB1 -> items.getStackInSlot(CB_SLOT_1).isEmpty() ? 0 : 1;
                case DATA_CB2 -> items.getStackInSlot(CB_SLOT_2).isEmpty() ? 0 : 1;
                case DATA_CB3 -> items.getStackInSlot(CB_SLOT_3).isEmpty() ? 0 : 1;
                case DATA_CB4 -> items.getStackInSlot(CB_SLOT_4).isEmpty() ? 0 : 1;
                case DATA_CB5 -> items.getStackInSlot(CB_SLOT_5).isEmpty() ? 0 : 1;
                case DATA_TARGET_TYPE -> targetType;
                case DATA_RADIUS -> radius;
                case DATA_WATER_AMOUNT -> waterTank.getFluidAmount();
                case DATA_WATER_FLUID_ID -> waterTank.getFluid().isEmpty() ? -1
                        : net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(waterTank.getFluid().getFluid());
                case DATA_CURRENT_TEMP -> (int) Math.round((getTemperatureK() - 273.15) * 100.0);
                case DATA_TARGET_TEMP -> targetTemperature;
                case DATA_TEMP_CONTROL -> temperatureControlEnabled ? 1 : 0;
                default -> 0;
            };
            getStored()[index] = value;
            return value;
        }

        @Override
        public void set(int index, int value) {
            getStored()[index] = value;
            if (index == DATA_PROGRESS) progress = value;
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    public IceMakerBlockEntity(BlockPos pos, BlockState state) {
        super(UniversalCuttingMachine.ICE_MAKER_BLOCK_ENTITY.get(), pos, state);
        for (int i = 0; i < 6; i++) sideConfig[i] = SideMode.NONE;
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
        this.fluidCapability = LazyOptional.of(() -> waterTank);
        // 温度系统：Mekanism 热容量（初始温度 = 环境温度，约 300 K）
        this.heatComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
    }

    /** 当前机身温度（开尔文）。 */
    public double getTemperatureK() {
        return heatComponent == null ? mekanism.api.heat.HeatAPI.AMBIENT_TEMP : heatComponent.getTemperature();
    }

    /** 设定温度（单位 0.01 ℃）：本机只降温，钳制在 -27315 至 0 之间（服务端兜底，防越界包）。 */
    public void setTargetTemperature(int milliCelsius) {
        this.targetTemperature = Math.max(-27315, Math.min(0, milliCelsius));
        setChanged();
    }

    /** 调整设定温度（增量，单位 0.01 ℃）。 */
    public void adjustTargetTemperature(int delta) {
        setTargetTemperature(targetTemperature + delta);
    }

    public int getTargetTemperature() {
        return targetTemperature;
    }

    public void setTemperatureControlEnabled(boolean enabled) {
        this.temperatureControlEnabled = enabled;
        setChanged();
    }

    public boolean isTemperatureControlEnabled() {
        return temperatureControlEnabled;
    }

    /** 温度是否满足加工条件（低于 0 ℃）。 */
    public boolean isTemperatureWorkable() {
        return getTemperatureK() < WORK_TEMP_LIMIT_K;
    }

    /**
     * 温度速度倍率：0 ℃ 时为 1×，绝对零度（0 K）时为 {@link #MAX_SPEED_MULTIPLIER}×（线性）。
     * 温度高于工作上限时返回 0（不加工）。
     */
    public double getTemperatureSpeedMultiplier() {
        double t = getTemperatureK();
        if (t >= WORK_TEMP_LIMIT_K) return 0.0;
        double ratio = (WORK_TEMP_LIMIT_K - t) / WORK_TEMP_LIMIT_K; // 0 ℃ → 0，0 K → 1
        return 1.0 + (MAX_SPEED_MULTIPLIER - 1.0) * ratio;
    }

    /**
     * 每 tick 温度更新：按设定温度自动制冷（耗电只用于降温做功，与运行速度无关）+ 自然回归 + 相邻传导。
     * 制冷条件：功能开启、当前温度高于设定温度、能量足够；达到设定温度即停止制冷。
     */
    public void tickTemperature(boolean running) {
        if (heatComponent == null) return;
        double targetK = (targetTemperature / 100.0) + 273.15;
        double currentK = getTemperatureK();
        if (temperatureControlEnabled && currentK > targetK) {
            // 本 tick 需要转移的热量：不超过“降到设定温度所需”，也不超过最大功率
            double needHeat = (currentK - targetK) * HEAT_CAPACITY;
            double maxHeat = COOLING_MAX_ENERGY_PER_TICK * COOLING_EFFICIENCY;
            double coolingHeat = Math.min(needHeat, maxHeat);
            int energyNeeded = hasCreativeUpgrade() ? 0
                    : (int) Math.ceil(coolingHeat / COOLING_EFFICIENCY);
            if (energyNeeded == 0 || energy.getEnergyStored() >= energyNeeded) {
                if (energyNeeded > 0) energy.extractEnergy(energyNeeded, false);
                // 电能 → 制冷做功（效率为电阻型加热器的 1/3）
                heatComponent.handleHeat(-coolingHeat);
                // 涡流管式定向热交换：FACING 侧为冷端（从相邻吸热）、其反向为热端（向相邻放热）
                // 注意：FACING = 放置时玩家朝向 = 背离玩家的那一面；面配置面板把它标为「背面」
                applyDirectedHeat(coolingHeat);
            }
        }
        // 环境回归 + 相邻传导（Mekanism 热力设备联动）
        heatComponent.tick(getLevel(), getBlockPos());
    }

    /**
     * 涡流管式定向热交换（参考气动工艺涡流管：一端吸热、一端放热，热量由动力介质搬运）：
     * {@code FACING} 侧为冷端，从该方向的相邻热力设备吸热；其反向为热端，向该方向的相邻设备放热。
     * 注意 {@code FACING} = 放置时玩家朝向 = <b>背离玩家</b>的那一面；面配置面板把它标为「背面」，
     * 因此**面板里「背面」= 冷端、「正面」= 热端**（与方块物品 tooltip 的叫法相反）。
     * 每个方向转移的热量为本次制冷量的一半（另一半作用于机身自身降温）。
     */
    private void applyDirectedHeat(double coolingHeat) {
        Level lvl = getLevel();
        if (lvl == null || lvl.isClientSide || coolingHeat <= 0.0) return;
        BlockState state = getBlockState();
        Direction facing = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;
        transferToNeighbour(lvl, facing, -coolingHeat * 0.5);           // 冷端：邻居吸热（降温）
        transferToNeighbour(lvl, facing.getOpposite(), coolingHeat * 0.5); // 热端：邻居放热（升温）
    }

    /** 向指定方向的相邻热力设备注入热量（正数升温、负数降温）。 */
    private void transferToNeighbour(Level level, Direction side, double heat) {
        if (Math.abs(heat) < 1.0e-3) return;
        BlockPos neighbour = getBlockPos().relative(side);
        if (!level.hasChunkAt(neighbour)) return;
        BlockEntity be = level.getBlockEntity(neighbour);
        if (be == null) return;
        be.getCapability(mekanism.common.capabilities.Capabilities.HEAT_HANDLER, side.getOpposite())
                .ifPresent(handler -> {
                    double current = handler.getTotalTemperature();
                    if (!Double.isFinite(current) || current < 0.0 || current > 1.0e9) return;
                    handler.handleHeat(heat);
                });
    }

    /** 冷萃升级读条（服务端每 tick）：安装成功后记录该槽已安装的等级并消耗槽位物品。 */
    private void tickColdBrewUpgrades() {
        int[] slots = {CB_SLOT_1, CB_SLOT_2, CB_SLOT_3, CB_SLOT_4, CB_SLOT_5};
        boolean changed = false;
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = items.getStackInSlot(slots[i]);
            cn.ism.mekck.item.ColdBrewTier tier = stack.isEmpty()
                    ? null : cn.ism.mekck.item.ColdBrewUpgradeItem.getTier(stack);
            int before = coldBrewTrackers[i].getInstalled();
            if (coldBrewTrackers[i].tick(stack, s -> cn.ism.mekck.item.ColdBrewUpgradeItem.getTier(s) != null)) {
                changed = true;
            }
            if (coldBrewTrackers[i].getInstalled() > before && tier != null) {
                installedColdBrew[i] = tier;
            }
        }
        if (changed) setChanged();
    }

    /** 已安装的冷萃等级（null = 未安装），供攻击档案计算。 */
    public cn.ism.mekck.item.ColdBrewTier getInstalledColdBrew(int index) {
        return index >= 0 && index < installedColdBrew.length ? installedColdBrew[index] : null;
    }

    /** 冷萃升级安装进度（0~1，供升级界面）。 */
    public double getColdBrewInstallProgress() {
        double best = 0.0;
        for (cn.ism.mekck.util.MekCkUpgradeTracker t : coldBrewTrackers) {
            best = Math.max(best, t.getProgress());
        }
        return best;
    }

    /**
     * 卸载升级（复刻 Mekanism removeUpgrade）：
     * mode 0/1 作用于速度/能量/创造升级（slot 指定槽位）；mode 2 作用于冷萃升级槽（slot = 槽位索引）。
     * 卸载出的物品优先放回原槽，放不下则不卸载。
     */
    public void uninstallUpgrade(byte mode, int slot) {
        if (mode == 2) {
            if (slot < CB_SLOT_1 || slot > CB_SLOT_5) return;
            int idx = slot - CB_SLOT_1;
            if (coldBrewTrackers[idx].getInstalled() <= 0) return;
            cn.ism.mekck.item.ColdBrewTier tier = installedColdBrew[idx];
            if (tier == null) return;
            net.minecraft.world.item.Item cbItem = cn.ism.mekck.item.ColdBrewUpgradeItem.REGISTRY.get(tier) == null
                    ? null : cn.ism.mekck.item.ColdBrewUpgradeItem.REGISTRY.get(tier).get();
            if (cbItem == null || cbItem == net.minecraft.world.item.Items.AIR) return;
            ItemStack give = new ItemStack(cbItem);
            ItemStack inSlot = items.getStackInSlot(slot);
            if (!inSlot.isEmpty() && !ItemStack.isSameItemSameTags(inSlot, give)) return;
            if (!inSlot.isEmpty() && inSlot.getCount() >= inSlot.getMaxStackSize()) return;
            coldBrewTrackers[idx].uninstall(1);
            installedColdBrew[idx] = null;
            if (inSlot.isEmpty()) {
                items.setStackInSlot(slot, give);
            } else {
                inSlot.grow(1);
            }
            setChanged();
            return;
        }
        // 常规升级槽：速度不支持；能量 / 创造
        cn.ism.mekck.util.MekCkUpgradeTracker tracker;
        String itemId;
        if (slot == SLOT_ENERGY_UPGRADE) {
            tracker = energyTracker;
            itemId = "mekanism:upgrade_energy";
        } else if (slot == SLOT_CREATIVE_UPGRADE) {
            tracker = creativeTracker;
            itemId = "mekanism:upgrade_creative";
        } else {
            return;
        }
        if (tracker == null || tracker.getInstalled() <= 0) return;
        net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new net.minecraft.resources.ResourceLocation(itemId));
        if (item == null || item == net.minecraft.world.item.Items.AIR) return;
        ItemStack inSlot = items.getStackInSlot(slot);
        ItemStack give = new ItemStack(item, 1);
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

    public static boolean isAnyUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (ColdBrewUpgradeItem.getTier(stack) != null) return true;
        return UpgradeHelper.isUpgrade(stack);
    }

    /** 速度升级不支持（速度由温度决定），恒为 0。 */
    public int getSpeedUpgradeCount() {
        return 0;
    }

    /** 已安装的能量升级数量（读条完成后生效）。 */
    public int getEnergyUpgradeCount() {
        return energyTracker.getInstalled();
    }

    public boolean hasCreativeUpgrade() {
        return creativeTracker.getInstalled() > 0;
    }

    /** 升级安装读条进度（0~1，供升级界面）。 */
    public double getUpgradeInstallProgress() {
        return Math.max(energyTracker.getProgress(),
                Math.max(creativeTracker.getProgress(), getColdBrewInstallProgress()));
    }

    public int addUpgradesFromHand(ItemStack held) {
        ColdBrewTier cb = ColdBrewUpgradeItem.getTier(held);
        if (cb != null) {
            int slot = switch (cb) {
                case COLD -> CB_SLOT_1;
                case LOW_TEMP -> CB_SLOT_2;
                case FROST -> CB_SLOT_3;
                case DRAGON_FROST, QUEEN -> CB_SLOT_4;
                case HYPOTHERMIA -> CB_SLOT_5;
            };
            if (items.getStackInSlot(slot).isEmpty() && isItemValidForSlot(slot, held)) {
                items.setStackInSlot(slot, new ItemStack(held.getItem(), 1));
                return 1;
            }
            return 0;
        }
        // 急冻制冰机不支持速度升级：速度槽上限传 0（不安装速度升级）
        return UpgradeHelper.install(items, SLOT_SPEED_UPGRADE, 0,
                SLOT_ENERGY_UPGRADE, MekckConfig.getBasicEnergyUpgradeMax(), -1, 0, SLOT_CREATIVE_UPGRADE, held);
    }

    private boolean isItemValidForSlot(int slot, ItemStack stack) {
        return items.isItemValid(slot, stack);
    }

    /** 有效速度倍率：由机身温度决定（急冻制冰机不支持速度升级）。 */
    public double getEffectiveSpeedMultiplier() {
        return Math.max(0.0, getTemperatureSpeedMultiplier());
    }

    public double getEffectiveEnergyConsumptionMultiplier() {
        return cn.ism.mekck.util.UpgradeHelper.energyConsumptionMultiplier(getEnergyUpgradeCount());
    }

    public int getEffectiveProcessTime() {
        double mult = getEffectiveSpeedMultiplier();
        if (mult <= 0.0) return PROCESS_TIME; // 温度不满足（不加工由 canWork 拦截），返回基准值避免除零
        return Math.max(1, (int) (PROCESS_TIME / mult));
    }

    public FluidTank getWaterTank() {
        return waterTank;
    }

    public int getRadius() {
        return radius;
    }

    public int getTargetType() {
        return targetType;
    }

    public void setTargetType(int type) {
        this.targetType = type;
        setChanged();
    }

    public void adjustRadius(int delta) {
        // 上下限都过 IceTargetSearch.clampAttackRadius：半径来自网络包且原本无上限，
        // 大到一定程度会退化成「每 tick 遍历全服实体」（见 IceTargetSearch 的类级说明）。
        // delta 走 long 再收窄，避免 radius + delta 在 MAX_VALUE 处回绕成负。
        this.radius = IceTargetSearch.clampAttackRadius((int) Math.min((long) IceTargetSearch.MAX_ATTACK_RADIUS, (long) this.radius + delta));
        setChanged();
    }

    public void setRadius(int r) {
        this.radius = IceTargetSearch.clampAttackRadius(r);
        setChanged();
    }

    // ================== 红石控制 ==================
    @Override
    public RedstoneControl getRedstoneControl() {
        return redstoneControl;
    }

    @Override
    public void setRedstoneControl(RedstoneControl control) {
        if (control == null) control = RedstoneControl.DISABLED;
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
        if (redstoneControl == RedstoneControl.PULSE) return pulseRunning;
        return redstoneControl.canFunction(redstonePowered, redstonePoweredLastTick);
    }

    // ================== 能源槽位 ==================
    public int getPowerSlot() {
        return SLOT_POWER;
    }

    public boolean drainPowerSlot() {
        ItemStack powerStack = items.getStackInSlot(SLOT_POWER);
        return PowerSlotUtil.drain(powerStack, energy, PowerSlotUtil.REDSTONE_PER_TICK);
    }

    public static boolean isUsablePowerItem(ItemStack stack) {
        return PowerSlotUtil.isValidEnergyItem(stack);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        cn.ism.mekck.buff.BuffLinkIndex.remove(worldPosition);
        // AE2 网格节点销毁（未安装 AE2 时为空操作；节点 NBT 由 saveAdditional 保存，重载后 init 重建）
        cn.ism.mekck.util.AE2Compat.onRemoved(this);
    }

    // ================== 本机下单（面板「本机 / ME」的本机一侧） ==================

    /** 本机下单涉及的输入槽。 */
    private int[] orderInputSlots() {
        return new int[]{INPUT_SLOT};
    }

    /** 供「本机下单」面板展示：输入槽里的物品能做的全部配方。 */
    public List<net.minecraft.world.item.crafting.Recipe<?>> getAvailableRecipes() {
        List<net.minecraft.world.item.crafting.Recipe<?>> out = new ArrayList<>();
        if (level == null) return out;
        net.minecraft.world.item.crafting.RecipeType<?> type =
                cn.ism.mekck.util.RecipeCache.type(new net.minecraft.resources.ResourceLocation("mekck:ice_make"));
        if (type == null) return out;
        for (net.minecraft.world.item.crafting.Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, type)) {
            if (matchesInput(r)) out.add(r);
        }
        return out;
    }

    /** 供「本机下单」面板的 Max 按钮：按输入槽现有材料算最多可做几份。 */
    public int getMaxConsumableCountForOrder(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        if (recipe == null || !matchesInput(recipe)) return 0;
        try {
            int max = Integer.MAX_VALUE;
            for (net.minecraft.world.item.crafting.Ingredient ing : recipe.getIngredients()) {
                if (ing == null || ing.isEmpty()) continue;
                int have = 0;
                for (int s : orderInputSlots()) {
                    net.minecraft.world.item.ItemStack st = items.getStackInSlot(s);
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

    /** 配方需求是否都能被 {@link #orderInputSlots()} 里的材料满足。 */
    private boolean matchesInput(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        if (recipe == null) return false;
        try {
            boolean any = false;
            for (int s : orderInputSlots()) {
                if (!items.getStackInSlot(s).isEmpty()) { any = true; break; }
            }
            if (!any) return false;
            for (net.minecraft.world.item.crafting.Ingredient ing : recipe.getIngredients()) {
                if (ing == null || ing.isEmpty()) continue;
                boolean ok = false;
                for (int s : orderInputSlots()) {
                    net.minecraft.world.item.ItemStack st = items.getStackInSlot(s);
                    if (!st.isEmpty() && ing.test(st)) { ok = true; break; }
                }
                if (!ok) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
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
                level, new ResourceLocation("mekck", "ice_make"));
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
        return customName != null ? customName : Component.translatable("block.mekck.ice_maker");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new IceMakerMenu(containerId, inventory, this, data);
    }

    // ================== 侧面配置 ==================
    public void setSideMode(Direction dir, SideMode mode) {
        if (dir == null) return;
        sideConfig[dir.ordinal()] = mode;
        setChanged();
    }

    public SideMode getSideMode(Direction dir) {
        return sideConfig[dir.ordinal()];
    }

    private int encodeSideConfig() {
        int v = 0;
        for (int i = 0; i < 6; i++) v |= (sideConfig[i].ordinal() & 0xF) << (i * 4);
        return v;
    }

    // ================== 处理与攻击 ==================
    public static void clientTick(Level level, BlockPos pos, BlockState state, IceMakerBlockEntity machine) {
        // 客户端无需额外逻辑
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, IceMakerBlockEntity machine) {
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.util.AE2Compat.serverTick(machine, level, pos);
        // F10 攻击增益：每 20 tick 解析一次 buff 源归属（ice_maker ← bakery_oven）
        if (machine.buffScanCooldown-- <= 0) {
            machine.buffScanCooldown = 20;
            machine.refreshBuffOwner(level);
        }
        boolean wasActive = machine.progress > 0;
        machine.updateRedstone();

        if (machine.drainPowerSlot()) machine.setChanged();

        // 温度系统：自身制冷降温 + 自然回归环境（每 tick）
        machine.tickTemperature(false);

        // 升级读条：常规升级（能量/创造）+ 冷萃升级，20 tick 安装一次
        machine.tickColdBrewUpgrades();
        if (machine.energyTracker.tick(machine.items.getStackInSlot(SLOT_ENERGY_UPGRADE),
                cn.ism.mekck.util.UpgradeHelper::isEnergyUpgrade)) {
            machine.setChanged();
        }
        if (machine.creativeTracker.tick(machine.items.getStackInSlot(SLOT_CREATIVE_UPGRADE),
                cn.ism.mekck.util.UpgradeHelper::isCreativeUpgrade)) {
            machine.setChanged();
        }

        boolean hasCreative = machine.hasCreativeUpgrade();
        if (hasCreative) {
            machine.energy.receiveEnergy(machine.energy.getMaxEnergyStored() - machine.energy.getEnergyStored(), false);
        }

        int effectiveProcessTime = hasCreative ? 1 : machine.getEffectiveProcessTime();
        // 加工本身不耗电：急冻制冰机的全部耗电只用于制冷做功（与运行速度无关，见 tickTemperature）

        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        IceMakeRecipe recipe = machine.getRecipe(level);
        boolean canWork = machine.canFunctionRedstone() && recipe != null
                && machine.isTemperatureWorkable() // 机身温度必须低于 0 ℃
                && machine.waterTank.getFluidAmount() >= recipe.getFluidAmount()
                && machine.canInsertOutput(recipe.getResults().isEmpty() ? ItemStack.EMPTY : recipe.getResults().get(0));

        // 加工耗电：与其他基础机器的无升级耗电一致（60 FE/t），不随运行速度（温度倍率）变化；
        // 仅运行配方时消耗，只制冷不加工时不消耗加工电。
        int energyPerTick = hasCreative ? 0 : ENERGY_PER_TICK;
        if (canWork) {
            boolean hasEnergy = hasCreative || machine.energy.getEnergyStored() >= energyPerTick;
            if (hasEnergy) {
                if (!hasCreative) machine.energy.extractEnergy(energyPerTick, false);
                if (machine.redstoneControl == RedstoneControl.PULSE) {
                    machine.pulseRunning = false;
                }
                machine.progress++;
                if (machine.progress >= effectiveProcessTime) {
                    machine.waterTank.drain(recipe.getFluidAmount(), net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                    if (!recipe.getResults().isEmpty()) {
                        machine.insertOutputDirectly(recipe.getResults().get(0));
                    }
                    machine.progress = 0;
                    // ME 下单进度
                    if (machine.orderRecipeId != null) {
                        machine.orderCompleted++;
                        if (machine.orderCompleted >= machine.orderQuantity) {
                            machine.orderRecipeId = null;
                            machine.orderQuantity = 0;
                            machine.orderCompleted = 0;
                        }
                    }
                }
            }
        } else if (machine.redstoneControl != RedstoneControl.PULSE) {
            machine.progress = Math.min(machine.progress, effectiveProcessTime);
        }

        // 攻击行为与加工一样受红石控制（忽略/高/低/脉冲）：被红石暂停时索敌与待生成队列都冻结
        if (machine.canFunctionRedstone()) {
            machine.spawnPendingAttack(level);
            machine.performAttack(level);
        }

        BlockState newState = state.setValue(IceMakerBlock.ACTIVE, machine.progress > 0);
        if (newState != state) level.setBlock(pos, newState, 3);

        if (!level.isClientSide) {
            machine.data.get(DATA_ENERGY);
        }
    }

    /** 复用的配方包装器：原先每次配方查找都要 new RecipeWrapper(items)，而这是每 tick 调用的路径。 */
    private RecipeWrapper cachedRecipeWrapper;

    private RecipeWrapper recipeWrapper() {
        if (cachedRecipeWrapper == null) cachedRecipeWrapper = new RecipeWrapper(items);
        return cachedRecipeWrapper;
    }

    private IceMakeRecipe getRecipe(Level level) {
        if (level == null) return null;
        var manager = level.getRecipeManager();
        var opts = recipeWrapper();
        var holder = manager.getRecipeFor(UniversalCuttingMachine.ICE_MAKE_RECIPE_TYPE.get(), opts, level);
        IceMakeRecipe found = holder.orElse(null);
        // ME 下单：只执行订单指定的配方
        if (orderRecipeId != null) {
            if (found == null || !orderRecipeId.equals(found.getId())) return null;
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
        this.orderQuantity = recipeId == null ? 0 : Math.max(1, quantity);
        this.orderCompleted = 0;
        setChanged();
    }

    private boolean canInsertOutput(ItemStack result) {
        if (result.isEmpty()) return true;
        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT);
        if (existing.isEmpty()) return true;
        if (ItemStack.isSameItemSameTags(existing, result)) {
            // §F31：上限取 handler 的 getSlotLimit（产物槽 = Integer.MAX_VALUE）而非 stack 的 getMaxStackSize（64），
            // 否则产物堆到 ≥64 时这里恒判「放不下」⇒ AE2 下单/自动生产停摆，与写入口径 insertOutputDirectly(grow) 分裂。
            return cn.ism.mekck.util.CountMath.canStack(existing.getCount(), result.getCount(), items.getSlotLimit(OUTPUT_SLOT));
        }
        return false;
    }

    /**
     * 机器内部产物写入：直接 set/grow 产物格。
     * 不能走 {@code items.insertItem}——Forge 的 ItemStackHandler.insertItem 会先调
     * {@code isItemValid}，产物槽对玩家禁入（false）会连机器自己的产出一起拦截
     * （2026-08-29 排查巧克力大炮无产物时 javap 反汇编确认，制冰机为同款隐患一并修复）。
     */
    private void insertOutputDirectly(ItemStack result) {
        if (result.isEmpty()) return;
        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT);
        if (existing.isEmpty()) {
            items.setStackInSlot(OUTPUT_SLOT, result.copy());
        } else if (ItemStack.isSameItemSameTags(existing, result)) {
            existing.grow(result.getCount());
        }
    }

    private void performAttack(Level level) {
        // 上一波冰块尚未全部生成时不启动新一波（攻击计时器保持）
        if (!pendingAttackTargets.isEmpty()) return;

        // 以「已安装的冷萃等级」计算攻击档案（升级需经读条安装，安装后槽位物品被消耗）
        ColdBrewHelper.Profile profile = ColdBrewHelper.compute(
                installedColdBrew[0], installedColdBrew[1], installedColdBrew[2],
                installedColdBrew[3], installedColdBrew[4]);
        if (profile == null) return;

        // 必须消耗输出格产物才能发动攻击：无产物时不攻击（计时器保持，产物就绪后立即恢复）；安装创造升级后不消耗产物（相当于无限弹药）
        boolean hasCreative = hasCreativeUpgrade();
        ItemStack output = items.getStackInSlot(OUTPUT_SLOT);
        if (!hasCreative && output.isEmpty()) return;

        attackTimer--;
        if (attackTimer > 0) return;
        // 创造升级额外效果：攻速变为每 tick 攻击一轮；F10：被 bakery_oven 增益时攻速 +20%（interval ×0.8）
        attackTimer = hasCreative ? 1 : buffedInterval(MekckConfig.getIceAttackInterval());

        // 索敌：小半径走 AABB 快速路径，超大半径遍历已加载实体（避免巨型 AABB 导致 section key 溢出崩溃）
        java.util.List<LivingEntity> candidates = cn.ism.mekck.util.IceTargetSearch.findTargets(
                level, worldPosition, this.radius, this::matchesTarget);

        int count = Math.min(profile.targetCount, candidates.size());
        LivingEntity highestHp = null;
        for (LivingEntity e : candidates) {
            if (highestHp == null || e.getHealth() > highestHp.getHealth()) highestHp = e;
        }

        // 构建本波目标队列；目标不足时若安装了集火升级（allowExtras），剩余冰块攻击血量最高的目标（可重复同一目标）
        java.util.List<LivingEntity> targets = new java.util.ArrayList<>(profile.targetCount);
        for (int i = 0; i < count; i++) {
            targets.add(candidates.get(i));
        }
        while (profile.allowExtras && targets.size() < profile.targetCount && highestHp != null) {
            targets.add(highestHp);
        }

        // 产物数量限制本波冰块数：每生成 1 个冰块消耗 1 个产物，不足时取消剩余冰块并终止本波（创造升级不限）
        int budget = hasCreative ? Integer.MAX_VALUE : output.getCount();
        if (targets.size() > budget) targets = targets.subList(0, budget);
        if (targets.isEmpty()) return;

        // 锁定标记：本波锁定的每个目标添加 1 次 1 秒发光（minecraft:glowing），标记索敌结果
        for (LivingEntity lockedTarget : targets) {
            lockedTarget.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.GLOWING, 20, 0, false, false));
        }

        pendingDamage = profile.damage;
        pendingAoe = profile.aoe;
        pendingSplash = profile.splashDamage;
        pendingSlow = profile.slow;
        pendingRemoveAI = profile.removeAI;
        pendingHypothermia = profile.hypothermia;
        // 按目标聚合本轮冰块数：同一目标的多块冰在队列中排队（逐 tick 依次生成），不同目标并列入队（同一 tick 一起生成）
        pendingAttackTargets.clear();
        for (LivingEntity t : targets) {
            pendingAttackTargets.merge(t, 1, Integer::sum);
        }
    }

    /**
     * 逐 tick 生成冰块：每个 tick 对「仍有剩余冰块」的每个目标各生成 1 块冰——
     * 不同目标同一 tick 一起生成；同一目标的多块冰按队列逐 tick 依次生成。每块冰消耗 1 个产物。
     */
    /** 每 tick 最多生成的弹射物数量：避免高并行/集火时一次性生成大量实体与特效（余下排队到下个 tick）。 */
    private static final int MAX_SPAWNS_PER_TICK = 8;

    private void spawnPendingAttack(Level level) {
        if (pendingAttackTargets.isEmpty()) return;
        boolean markedDirty = false;
        int spawnedThisTick = 0;
        var it = pendingAttackTargets.entrySet().iterator();
        while (it.hasNext()) {
            if (spawnedThisTick >= MAX_SPAWNS_PER_TICK) return; // 本 tick 配额用尽
            var entry = it.next();
            LivingEntity target = entry.getKey();
            if (!target.isAlive() || target.isRemoved()) {
                it.remove(); // 目标已消失：跳过，不消耗产物
                continue;
            }
            if (!hasCreativeUpgrade() && items.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
                pendingAttackTargets.clear(); // 产物耗尽：终止本波剩余冰块（创造升级不消耗）
                return;
            }
            IceCubeEntity.spawn(level, target.getX(), target.getY() + 5, target.getZ(), pendingDamage, pendingAoe, pendingSplash, pendingSlow, pendingRemoveAI, pendingHypothermia, worldPosition);
            spawnedThisTick++;
            if (!hasCreativeUpgrade()) {
                items.extractItem(OUTPUT_SLOT, 1, false);
                markedDirty = true; // 本 tick 统一置脏一次，避免每个弹射物都 setChanged()
            }
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
        if (markedDirty) setChanged();
    }

    private boolean matchesTarget(LivingEntity e) {
        boolean hostile = MekckConfig.isIceAttackHostile(e.getType());
        return switch (targetType) {
            case TARGET_ALL -> true;
            case TARGET_ANIMAL -> !hostile;
            default -> hostile;
        };
    }

    public void dropContents(Level level, BlockPos pos) {
        NonNullList<ItemStack> drops = NonNullList.create();
        for (int slot = 0; slot < items.getSlots(); slot++) drops.add(items.getStackInSlot(slot));
        cn.ism.mekck.util.BigStackDrops.dropAll(level, pos, drops); // 大堆叠安全：避免原版 64 分堆炸实体
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        cn.ism.mekck.util.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        tag.put("Items", items.serializeNBT());
        tag.put("Fluid", waterTank.writeToNBT(new CompoundTag()));
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Progress", progress);
        if (orderRecipeId != null) {
            tag.putString("OrderRecipeId", orderRecipeId.toString());
            tag.putInt("OrderQuantity", orderQuantity);
            tag.putInt("OrderCompleted", orderCompleted);
        }
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        tag.putInt("TargetTemperature", targetTemperature);
        tag.put("EnergyUpgradeTracker", energyTracker.save());
        tag.put("CreativeUpgradeTracker", creativeTracker.save());
        // 冷萃已安装等级
        for (int i = 0; i < installedColdBrew.length; i++) {
            if (installedColdBrew[i] != null) {
                tag.putString("ColdBrew" + i, installedColdBrew[i].name());
            }
        }
        tag.putBoolean("TemperatureControl", temperatureControlEnabled);
        if (heatComponent != null) tag.put("HeatCapacitor", heatComponent.save());
        tag.putInt("AttackTimer", attackTimer);
        tag.putInt("TargetType", targetType);
        tag.putInt("Radius", radius);
        byte[] sideBytes = new byte[6];
        for (int i = 0; i < 6; i++) sideBytes[i] = (byte) sideConfig[i].ordinal();
        tag.putByteArray("SideConfig", sideBytes);
        tag.putInt("RedstoneControl", redstoneControl.ordinal());
        tag.putBoolean("RedstonePowered", redstonePowered);
        if (customName != null) tag.putString("CustomName", Component.Serializer.toJson(customName));
        if (buffOwnerPos != null) tag.putLong("BuffOwnerPos", buffOwnerPos.asLong());
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
                if (slot >= 0 && slot < TOTAL_SLOTS) newList.add(itemTags);
            }
            CompoundTag newTag = new CompoundTag();
            newTag.putInt("Size", TOTAL_SLOTS);
            newTag.put("Items", newList);
            items.deserializeNBT(newTag);
        }
        if (tag.contains("Fluid")) waterTank.readFromNBT(tag.getCompound("Fluid"));
        int remainingEnergy = tag.getInt("Energy");
        while (remainingEnergy > 0) {
            int received = energy.receiveEnergy(remainingEnergy, false);
            if (received == 0) break;
            remainingEnergy -= received;
        }
        progress = tag.getInt("Progress");
        if (tag.contains("OrderRecipeId")) {
            orderRecipeId = new net.minecraft.resources.ResourceLocation(tag.getString("OrderRecipeId"));
            orderQuantity = tag.getInt("OrderQuantity");
            orderCompleted = tag.getInt("OrderCompleted");
        }
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        targetTemperature = tag.contains("TargetTemperature") ? tag.getInt("TargetTemperature") : -27315;
        if (tag.contains("EnergyUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            energyTracker.load(tag.getCompound("EnergyUpgradeTracker"));
        }
        if (tag.contains("CreativeUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            creativeTracker.load(tag.getCompound("CreativeUpgradeTracker"));
        }
        for (int i = 0; i < installedColdBrew.length; i++) {
            installedColdBrew[i] = null;
            if (tag.contains("ColdBrew" + i)) {
                try {
                    installedColdBrew[i] = cn.ism.mekck.item.ColdBrewTier.valueOf(tag.getString("ColdBrew" + i));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        temperatureControlEnabled = !tag.contains("TemperatureControl") || tag.getBoolean("TemperatureControl");
        if (heatComponent != null && tag.contains("HeatCapacitor", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            heatComponent.load(tag.getCompound("HeatCapacitor"));
        }
        attackTimer = tag.getInt("AttackTimer");
        targetType = tag.getInt("TargetType");
        radius = tag.getInt("Radius");
        if (tag.contains("SideConfig", Tag.TAG_BYTE_ARRAY)) {
            byte[] sideBytes = tag.getByteArray("SideConfig");
            for (int i = 0; i < Math.min(sideBytes.length, 6); i++) {
                int ord = sideBytes[i];
                if (ord >= 0 && ord < SideMode.values().length) sideConfig[i] = SideMode.values()[ord];
            }
        }
        if (tag.contains("CustomName")) customName = Component.Serializer.fromJson(tag.getString("CustomName"));
        if (tag.contains("RedstoneControl")) redstoneControl = RedstoneControl.byOrdinal(tag.getInt("RedstoneControl"));
        if (tag.contains("RedstonePowered")) redstonePowered = tag.getBoolean("RedstonePowered");
        buffOwnerPos = tag.contains("BuffOwnerPos") ? BlockPos.of(tag.getLong("BuffOwnerPos")) : null;
    }

    // ==================== F10 攻击增益（buff 源归属 / 连线同步） ====================

    /** 是否正被 buff 源增益（供攻速计算 / 查询）。 */
    public boolean isBuffed() {
        return buffOwnerPos != null;
    }

    /** buff 生效时的攻击间隔（Q2a：base × 0.8，四舍五入且至少 1）。 */
    private int buffedInterval(int base) {
        return buffOwnerPos == null ? base : Math.max(1, Math.round(base * 0.8f));
    }

    /** 服务端周期性解析 buff 源归属：创造升级下禁用且不连线（Q6b）；否则取范围内最近的合法源。 */
    public void refreshBuffOwner(Level level) {
        if (level.isClientSide) return;
        if (hasCreativeUpgrade()) {
            setBuffOwner(level, null);
            return;
        }
        Block source = cn.ism.mekck.buff.MekckBuffRegistry.sourceFor(getBlockState().getBlock());
        BlockPos np = source == null ? null
                : cn.ism.mekck.buff.MekckBuffRegistry.resolve(level, worldPosition, source, buffOwnerPos);
        setBuffOwner(level, np);
    }

    private void setBuffOwner(Level level, @Nullable BlockPos np) {
        if (java.util.Objects.equals(np, buffOwnerPos)) return;
        buffOwnerPos = np;
        setChanged();
        if (!level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** 客户端：把当前 owner 反映到渲染索引（有 owner ⇒ 登记连线；无 ⇒ 撤销）。 */
    private void syncClientLink() {
        if (level != null && !level.isClientSide) return;
        if (buffOwnerPos != null) {
            cn.ism.mekck.buff.BuffLinkIndex.put(worldPosition, buffOwnerPos);
        } else {
            cn.ism.mekck.buff.BuffLinkIndex.remove(worldPosition);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        syncClientLink();
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        if (buffOwnerPos != null) tag.putLong("BuffOwnerPos", buffOwnerPos.asLong());
        else tag.remove("BuffOwnerPos");
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net,
                             net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        buffOwnerPos = (tag != null && tag.contains("BuffOwnerPos"))
                ? BlockPos.of(tag.getLong("BuffOwnerPos")) : null;
        syncClientLink();
    }

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) return energyCapability.cast();
        if (capability == ForgeCapabilities.FLUID_HANDLER) return fluidCapability.cast();
        // 温度系统：暴露 Mekanism 热能力，供热力设备传导（联动）
        if (capability == mekanism.common.capabilities.Capabilities.HEAT_HANDLER) return heatCapability.cast();
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            if (side == null) return fullItemCapability.cast();
            SideMode mode = sideConfig[side.ordinal()];
            if (mode == SideMode.PULL_INPUT) return inputItemCapability.cast();
            if (mode == SideMode.PUSH_OUTPUT) return outputItemCapability.cast();
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
        fluidCapability.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        fullItemCapability = LazyOptional.of(() -> items);
        inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        energyCapability = LazyOptional.of(() -> energy);
        fluidCapability = LazyOptional.of(() -> waterTank);
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
