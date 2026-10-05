package cn.ism.mekck.blockentity;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.IceFactoryBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.entity.IceCubeEntity;
import cn.ism.mekck.item.ColdBrewTier;
import cn.ism.mekck.item.ColdBrewUpgradeItem;
import cn.ism.mekck.menu.IceFactoryMenu;
import cn.ism.mekck.recipe.IceMakeRecipe;
import cn.ism.mekck.recipe.RecipeInputMatcher;
import cn.ism.mekck.item.ColdBrewHelper;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.upgrade.UpgradeHelper;
import net.minecraft.core.BlockPos;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IMekanismHeatHandler;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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

import java.util.Comparator;
import java.util.List;
import cn.ism.mekck.util.IceTargetSearch;
import cn.ism.mekck.registry.MekCkFactories;
import cn.ism.mekck.registry.MekCkRecipeTypes;

public final class IceFactoryBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, mekanism.api.heat.IMekanismHeatHandler , cn.ism.mekck.ae2.INetworkPullable {
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

    public final CuttingMachineFactoryTier tier;
    private final int processes;

    private final int INPUT_SLOTS;
    private final int OUTPUT_SLOTS;
    private final int base;
    // ================== 升级读条（Mekanism 式：20 tick 安装） ==================
    private final cn.ism.mekck.upgrade.MekCkUpgradeTracker speedTracker;
    private final cn.ism.mekck.upgrade.MekCkUpgradeTracker energyTracker;
    private final cn.ism.mekck.upgrade.MekCkUpgradeTracker stackTracker;
    private final cn.ism.mekck.upgrade.MekCkUpgradeTracker creativeTracker =
            new cn.ism.mekck.upgrade.MekCkUpgradeTracker(1);

    public final int SPEED_UPGRADE_SLOT;
    public final int ENERGY_UPGRADE_SLOT;
    public final int STACK_UPGRADE_SLOT;
    public final int CREATIVE_SLOT;
    public final int CB_SLOT_1;
    public final int CB_SLOT_2;
    public final int CB_SLOT_3;
    public final int CB_SLOT_4;
    /** 失温升级槽（冷萃⑤，需先装龙霜/女王④）。 */
    public final int CB_SLOT_5;
    public final int POWER_SLOT;
    public final int TOTAL_SLOTS;

    public static final int ENERGY_CAPACITY_BASE = 300_000;
    public static final int PROCESS_TIME = 100;

    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_SIDE_CONFIG = 3;
    public static final int DATA_SPEED_UPGRADE = 4;
    public static final int DATA_ENERGY_UPGRADE = 5;
    public static final int DATA_STACK_UPGRADE = 6;
    public static final int DATA_CREATIVE_UPGRADE = 7;
    public static final int DATA_REDSTONE_CONTROL = 8;
    public static final int DATA_CB1 = 9;
    public static final int DATA_CB2 = 10;
    public static final int DATA_CB3 = 11;
    public static final int DATA_CB4 = 12;
    public static final int DATA_CB5 = 13;
    public static final int DATA_TARGET_TYPE = 14;
    public static final int DATA_RADIUS = 15;
    /** 水罐流体量（mb）与流体注册 id：经 ContainerData 同步到客户端（流体条显示用，FluidTank 不自动进网络）。 */
    public static final int DATA_WATER_AMOUNT = 16;
    public static final int DATA_WATER_FLUID_ID = 17;
    /**
     * {@link #DATA_ENERGY} 的<b>高 16 位</b> —— 能量被拆成两个槽传输。
     *
     * <p>{@code ContainerData} 经 {@code ClientboundContainerSetDataPacket} 时对每个值
     * 只 {@code writeShort}（16 位有符号），而储能量上限远超 32767
     * ⇒ 不拆必然截断成负数。详见 {@link cn.ism.mekck.util.WideDataSlot}。</p>
     *
     * <p>取值 = 旧 {@code DATA_SIZE}，即<b>追加</b>到槽表末尾：现有下标一律不动。</p>
     */
    public static final int DATA_ENERGY_HI = 18;
    /**
     * 侧配编码（6 面 × 4 bit = 24 bit）的高 16 位，追加到槽表末尾：
     * 不拆则 WEST/EAST 两面经 16 位有符号通道后恒为 NONE（见 {@link cn.ism.mekck.util.WideDataSlot}）。
     */
    public static final int DATA_SIDE_CONFIG_HI = 19;
    /** 水罐流体量的高 16 位：容量 256,000 &gt; 32767，不拆满罐会被读成负数/空罐。 */
    public static final int DATA_WATER_AMOUNT_HI = 20;
    public static final int DATA_SIZE = 21;

    public static final int TARGET_HOSTILE = 0;
    public static final int TARGET_ALL = 1;
    public static final int TARGET_ANIMAL = 2;

    private Component customName;
    private final int[] progress;
    private int attackTimer = 0;
    /**
     * 待生成冰块的目标分配表（目标 → 剩余冰块数）：
     * 攻击同一目标的冰块保持排队（逐 tick 依次生成），攻击不同目标的冰块在同一 tick 一起生成。
     */
    private final java.util.LinkedHashMap<LivingEntity, Integer> pendingAttackTargets = new java.util.LinkedHashMap<>();
    /**
     * 索敌结果缓存：创造升级让 {@code attackTimer = 1} ⇒ 每 tick 攻击一次，
     * 而半径 > 64 时 {@code IceTargetSearch} 要遍历全部已加载实体 —— 不缓存就是每 tick 全服扫描。
     * 与 {@code ChocolateCannonBlockEntity} 那份同实现（抽到 {@code IceTargetSearch.CandidateCache}）。
     */
    private final cn.ism.mekck.util.IceTargetSearch.CandidateCache targetCache =
            new cn.ism.mekck.util.IceTargetSearch.CandidateCache();


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

    private final ItemStackHandler items;
    private final FluidTank waterTank = new FluidTank(256_000) {
        @Override
        public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid() == net.minecraft.world.level.material.Fluids.WATER;
        }

        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };

    private final EnergyStorage energy;

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
                case DATA_PROGRESS -> overallProgress();
                case DATA_PROCESS_TIME -> getEffectiveProcessTime();
                // 能量拆两槽：writeShort 只送低 16 位且会符号扩展，见 WideDataSlot。
                case DATA_ENERGY -> energy.getEnergyStored() & 0xFFFF;
                case DATA_ENERGY_HI -> (energy.getEnergyStored() >>> 16) & 0xFFFF;
                case DATA_SIDE_CONFIG -> encodeSideConfig() & 0xFFFF;
                case DATA_SIDE_CONFIG_HI -> (encodeSideConfig() >>> 16) & 0xFFFF;
                case DATA_SPEED_UPGRADE -> getSpeedUpgradeCount();
                case DATA_ENERGY_UPGRADE -> getEnergyUpgradeCount();
                case DATA_STACK_UPGRADE -> getStackUpgradeCount();
                case DATA_CREATIVE_UPGRADE -> hasCreativeUpgrade() ? 1 : 0;
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
                case DATA_CB1 -> items.getStackInSlot(CB_SLOT_1).isEmpty() ? 0 : 1;
                case DATA_CB2 -> items.getStackInSlot(CB_SLOT_2).isEmpty() ? 0 : 1;
                case DATA_CB3 -> items.getStackInSlot(CB_SLOT_3).isEmpty() ? 0 : 1;
                case DATA_CB4 -> items.getStackInSlot(CB_SLOT_4).isEmpty() ? 0 : 1;
                case DATA_CB5 -> items.getStackInSlot(CB_SLOT_5).isEmpty() ? 0 : 1;
                case DATA_TARGET_TYPE -> targetType;
                case DATA_RADIUS -> radius;
                case DATA_WATER_AMOUNT -> waterTank.getFluidAmount() & 0xFFFF;
                case DATA_WATER_AMOUNT_HI -> (waterTank.getFluidAmount() >>> 16) & 0xFFFF;
                case DATA_WATER_FLUID_ID -> waterTank.getFluid().isEmpty() ? -1
                        : net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(waterTank.getFluid().getFluid());
                default -> 0;
            };
            getStored()[index] = value;
            return value;
        }

        @Override
        public void set(int index, int value) {
            getStored()[index] = value;
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    /** 温度系统：Mekanism 热容量（运行时按电阻型加热器比例产热，并与相邻热力设备传导）。 */
    private cn.ism.mekck.util.MekCkHeatComponent heatComponent;
    private net.minecraftforge.common.util.LazyOptional<mekanism.api.heat.IHeatHandler> heatCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> heatComponent.getHandler());

    public IceFactoryBlockEntity(CuttingMachineFactoryTier tier, BlockPos pos, BlockState state) {
        super(MekCkFactories.ICE_FACTORY_BLOCK_ENTITIES.get(tier).get(), pos, state);
        this.tier = tier;
                // 上限惰性读取 MekckConfig，故 /reload 改配置后立即生效。
                // 必须在构造器体内初始化：tier 在此处才保证已赋值，字段初始化器里无法引用。
                this.speedTracker = new cn.ism.mekck.upgrade.MekCkUpgradeTracker(() -> MekckConfig.getFactorySpeedUpgradeMax(tier));
                this.energyTracker = new cn.ism.mekck.upgrade.MekCkUpgradeTracker(() -> MekckConfig.getFactoryEnergyUpgradeMax(tier));
                this.stackTracker = new cn.ism.mekck.upgrade.MekCkUpgradeTracker(() -> MekckConfig.getFactoryStackUpgradeMax(tier));
        this.heatComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
        this.processes = tier.processes;
        // 输入/输出格数量与其他同等级多线程工厂一致（= 并行数）
        this.INPUT_SLOTS = processes;
        this.OUTPUT_SLOTS = processes;
        this.base = INPUT_SLOTS + OUTPUT_SLOTS;
        this.SPEED_UPGRADE_SLOT = base;
        this.ENERGY_UPGRADE_SLOT = base + 1;
        this.STACK_UPGRADE_SLOT = base + 2;
        // 与其他工厂一致：所有等级（含星云/奇点）都提供创造升级槽位；创造升级免电力、1tick 完成、攻击不消耗产物。
        // 旧存档（无创造槽布局）由 load() 中的槽位迁移逻辑处理。
        this.CREATIVE_SLOT = base + 3;
        int cbStart = base + 4;
        this.CB_SLOT_1 = cbStart;
        this.CB_SLOT_2 = cbStart + 1;
        this.CB_SLOT_3 = cbStart + 2;
        this.CB_SLOT_4 = cbStart + 3;
        // 失温升级槽（冷萃⑤，需先装龙霜/女王④）
        this.CB_SLOT_5 = cbStart + 4;
        this.POWER_SLOT = cbStart + 5;
        this.TOTAL_SLOTS = cbStart + 6;

        this.progress = new int[processes];
        this.energy = new EnergyStorage(tier.energyCapacity > 0 ? tier.energyCapacity : 100_000, 5_000, tier.energyPerTick);

        this.items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                if (slot < INPUT_SLOTS) return !isAnyUpgrade(stack) && RecipeInputMatcher.matchesIceMake(level, stack);
                if (slot >= INPUT_SLOTS && slot < base) return false; // output slots
                if (slot == SPEED_UPGRADE_SLOT) return UpgradeHelper.isSpeedUpgrade(stack);
                if (slot == ENERGY_UPGRADE_SLOT) return UpgradeHelper.isEnergyUpgrade(stack);
                if (slot == STACK_UPGRADE_SLOT) return UpgradeHelper.isStackUpgrade(stack);
                if (slot == CREATIVE_SLOT) return CREATIVE_SLOT >= 0 && UpgradeHelper.isCreativeUpgrade(stack);
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
                if (slot == POWER_SLOT) return PowerSlotUtil.isValidEnergyItem(stack);
                return false;
            }

            @Override
            public int getSlotLimit(int slot) {
                if (slot < INPUT_SLOTS) return 64;
                if (slot >= INPUT_SLOTS && slot < base) return Integer.MAX_VALUE;
                if (slot == CREATIVE_SLOT) return 1;
                if (slot == STACK_UPGRADE_SLOT) return MekckConfig.getFactoryStackUpgradeMax(tier);
                if (slot == POWER_SLOT) return 64;
                if (slot == CB_SLOT_1 || slot == CB_SLOT_2 || slot == CB_SLOT_3 || slot == CB_SLOT_4 || slot == CB_SLOT_5) return 1;
                return MekckConfig.getFactorySpeedUpgradeMax(tier);
            }

            @Override
            protected int getStackLimit(int slot, ItemStack stack) {
                if (slot < INPUT_SLOTS || slot >= INPUT_SLOTS && slot < base || slot == CREATIVE_SLOT
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

        for (int i = 0; i < 6; i++) sideConfig[i] = SideMode.NONE;
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
        this.fluidCapability = LazyOptional.of(() -> waterTank);
    }

    public static boolean isAnyUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (ColdBrewUpgradeItem.getTier(stack) != null) return true;
        return UpgradeHelper.isUpgrade(stack);
    }

    /** 卸载升级（升级界面卸载按钮）。 */
    public void uninstallUpgrade(byte mode, int slot) {
        cn.ism.mekck.upgrade.MekCkUpgradeTracker tracker;
        String itemId;
        if (slot == SPEED_UPGRADE_SLOT) {
            tracker = speedTracker;
            itemId = "mekanism:upgrade_speed";
        } else if (slot == ENERGY_UPGRADE_SLOT) {
            tracker = energyTracker;
            itemId = "mekanism:upgrade_energy";
        } else if (slot == STACK_UPGRADE_SLOT) {
            tracker = stackTracker;
            itemId = "mekanism_extras:upgrade_stack";
        } else if (slot == CREATIVE_SLOT) {
            tracker = creativeTracker;
            itemId = "mekanism:upgrade_creative";
        } else {
            return;
        }
        if (tracker.getInstalled() <= 0) return;
        // itemId 是上面的固定字面量，拆成 ns/path 走非空的 fromNamespaceAndPath
        // （单参 tryParse 返回可空类型，注册表查询不接受 null）。
        int sep = itemId.indexOf(':');
        ResourceLocation upgradeId = ResourceLocation.fromNamespaceAndPath(
                itemId.substring(0, sep), itemId.substring(sep + 1));
        net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(upgradeId);
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

    /** 服务端每 tick：推进各升级槽的安装读条。 */
    private void tickUpgradeTrackers() {
        boolean changed = false;
        if (SPEED_UPGRADE_SLOT >= 0 && SPEED_UPGRADE_SLOT < items.getSlots()) {
            changed |= speedTracker.tick(items.getStackInSlot(SPEED_UPGRADE_SLOT),
                    cn.ism.mekck.upgrade.UpgradeHelper::isSpeedUpgrade);
        }
        if (ENERGY_UPGRADE_SLOT >= 0 && ENERGY_UPGRADE_SLOT < items.getSlots()) {
            changed |= energyTracker.tick(items.getStackInSlot(ENERGY_UPGRADE_SLOT),
                    cn.ism.mekck.upgrade.UpgradeHelper::isEnergyUpgrade);
        }
        if (STACK_UPGRADE_SLOT >= 0 && STACK_UPGRADE_SLOT < items.getSlots()) {
            changed |= stackTracker.tick(items.getStackInSlot(STACK_UPGRADE_SLOT),
                    cn.ism.mekck.upgrade.UpgradeHelper::isStackUpgrade);
        }
        if (CREATIVE_SLOT >= 0 && CREATIVE_SLOT < items.getSlots()) {
            changed |= creativeTracker.tick(items.getStackInSlot(CREATIVE_SLOT),
                    cn.ism.mekck.upgrade.UpgradeHelper::isCreativeUpgrade);
        }
        if (changed) setChanged();
    }

    /** 升级安装读条进度（0~1，供升级界面）。 */
    public double getUpgradeInstallProgress() {
        return Math.max(speedTracker.getProgress(),
                Math.max(energyTracker.getProgress(),
                        Math.max(stackTracker.getProgress(), creativeTracker.getProgress())));
    }

    public int getStackUpgradeCount() {
        // 与其余 6 个工厂一致，读「已安装数量」而非槽内物品数：
        // MekCkUpgradeTracker.tick() 安装时会把槽内堆叠 shrink() 掉，
        // 因此读槽内数量在安装完成后恒为 0（GUI 与数据包同步都会显示 0）。
        if (STACK_UPGRADE_SLOT < 0) return 0;
        return stackTracker.getInstalled();
    }

    public boolean hasCreativeUpgrade() {
        // 与 IceMaker/ChocolateCannon 同口径：读「已安装数量」而非槽内物品数 ——
        // MekCkUpgradeTracker.tick() 安装时会把槽内堆叠 shrink() 掉，
        // 读槽位在安装完成后恒为 false（创造升级装完即失效）。
        return creativeTracker.getInstalled() > 0;
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
            if (items.getStackInSlot(slot).isEmpty() && items.isItemValid(slot, held)) {
                items.setStackInSlot(slot, new ItemStack(held.getItem(), 1));
                return 1;
            }
            return 0;
        }
        int speedMax = MekckConfig.getFactorySpeedUpgradeMax(tier);
        int energyMax = MekckConfig.getFactoryEnergyUpgradeMax(tier);
        int stackMax = MekckConfig.getFactoryStackUpgradeMax(tier);
        int creativeSlot = CREATIVE_SLOT;
        return UpgradeHelper.install(items, SPEED_UPGRADE_SLOT, speedMax, ENERGY_UPGRADE_SLOT, energyMax,
                STACK_UPGRADE_SLOT, stackMax, creativeSlot, held);
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
        // 与半径同一道闸：值来自网络包，越界值虽被 matchesTarget 的 default 兜底不会崩，
        // 但会写进存档并让 GUI 显示与实际行为不一致。见 IceTargetSearch#clampTargetType。
        this.targetType = cn.ism.mekck.util.IceTargetSearch.clampTargetType(type);
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

    private int overallProgress() {
        int max = 0;
        for (int v : progress) max = Math.max(max, v);
        return max;
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
        return POWER_SLOT;
    }

    public boolean drainPowerSlot() {
        ItemStack powerStack = items.getStackInSlot(POWER_SLOT);
        return PowerSlotUtil.drain(powerStack, energy, PowerSlotUtil.REDSTONE_PER_TICK);
    }

    public static boolean isUsablePowerItem(ItemStack stack) {
        return PowerSlotUtil.isValidEnergyItem(stack);
    }

    public int getInputSlots() {
        return INPUT_SLOTS;
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
    @Override public net.minecraft.world.level.block.entity.BlockEntity getNetworkPullable() { return this; }
    @Override public int[] getInputSlotRange() { return new int[]{0, getInputSlots()}; }
    @Override public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() { return items; }
    @Override public boolean supportsAutoPull() { return true; } // ME 持续补料：按"每类型上限"（配置 auto_pull_stack_limit）批量补，受 LagMonitor 限流

    @Override
    public List<cn.ism.mekck.ae2.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) return List.of();
        return cn.ism.mekck.ae2.NetworkPullHelper.currentOrUnion(level, items.getStackInSlot(0),
                ResourceLocation.fromNamespaceAndPath("mekck", "ice_make"));
    }

    public int getProcesses() {
        return processes;
    }

    public ContainerData getData() {
        return data;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        return customName != null ? customName : Component.translatable("block.mekck.ice_factory");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new IceFactoryMenu(containerId, inventory, this, data);
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
    public static void clientTick(Level level, BlockPos pos, BlockState state, IceFactoryBlockEntity machine) {
        // 客户端无需额外逻辑
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, IceFactoryBlockEntity machine) {
        // 升级读条：槽位放入升级后 20 tick 安装一次
        machine.tickUpgradeTrackers();
        // 温度系统：每 tick 自然回归环境并与相邻 Mekanism 热力设备传导
        machine.heatComponent.tick(level, pos);
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.compat.AE2Compat.serverTick(machine, level, pos);
        machine.updateRedstone();
        if (machine.drainPowerSlot()) machine.setChanged();

        boolean hasCreative = machine.hasCreativeUpgrade();
        if (hasCreative) {
            machine.energy.receiveEnergy(machine.energy.getMaxEnergyStored() - machine.energy.getEnergyStored(), false);
        }

        double speedMult = machine.getEffectiveSpeedMultiplier();
        double energyConsumptionMult = machine.getEffectiveEnergyConsumptionMultiplier();
        int effectiveProcessTime = hasCreative ? 1 : machine.getEffectiveProcessTime();
        int energyPerTick = hasCreative ? 0 : (int) Math.ceil((tier_energyPerTick(machine.tier)) * speedMult * speedMult * energyConsumptionMult);

        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        boolean canFunction = machine.canFunctionRedstone();
        int completed = 0;
        if (canFunction) {
            for (int i = 0; i < machine.processes; i++) {
                IceMakeRecipe recipe = machine.getRecipeForSlot(level, i);
                if (recipe == null) {
                    machine.progress[i] = 0;
                    continue;
                }
                int inSlot = i;
                int outSlot = machine.INPUT_SLOTS + i;
                if (machine.waterTank.getFluidAmount() < recipe.getFluidAmount()
                        || !machine.canInsertOutput(outSlot, recipe.getResults().isEmpty() ? ItemStack.EMPTY : recipe.getResults().get(0))) {
                    continue;
                }
                boolean hasEnergy = hasCreative || machine.energy.getEnergyStored() >= energyPerTick;
                if (!hasEnergy) continue;
                if (machine.redstoneControl == RedstoneControl.PULSE) machine.pulseRunning = false;
                if (!hasCreative) machine.energy.extractEnergy(energyPerTick, false);
                // 温度系统：按消耗电能产热（与电阻型加热器比例完全相同：1 FE → 0.6 J 热量）
                machine.heatComponent.addHeatFromEnergy(energyPerTick);
                machine.progress[i]++;
                if (machine.progress[i] >= effectiveProcessTime) {
                    machine.waterTank.drain(recipe.getFluidAmount(), IFluidHandler.FluidAction.EXECUTE);
                    if (!recipe.getResults().isEmpty()) {
                        machine.insertOutputDirectly(outSlot, recipe.getResults().get(0));
                    }
                    machine.progress[i] = 0;
                    completed++;
                }
            }
        }

        // 攻击行为与加工一样受红石控制（忽略/高/低/脉冲）：被红石暂停时索敌与待生成队列都冻结
        if (machine.canFunctionRedstone()) {
            machine.spawnPendingAttack(level);
            machine.performAttack(level);
        }

        boolean active = machine.overallProgress() > 0;
        BlockState newState = state.setValue(IceFactoryBlock.ACTIVE, active);
        if (newState != state) level.setBlock(pos, newState, 3);
    }

    private static int tier_energyPerTick(CuttingMachineFactoryTier tier) {
        return tier.energyPerTick > 0 ? tier.energyPerTick : 60;
    }

    /**
     * 每槽复用的配方包装器。
     *
     * <p>原实现每次调用都 {@code new RecipeWrapper(new SingleSlotHandler(inSlot))}：本方法是
     * {@code serverTick} 里 {@code for (i < processes)} 的循环体，而奇点档 {@code processes = 81}
     * ⇒ <b>每 tick 每机器 162 次分配 + 81 次 getRecipeFor</b>。
     * {@code SingleSlotHandler} 每次都从 {@code items} 实时读该槽（不缓存内容），
     * 因此按槽缓存一个实例与每次新建<b>行为等价</b>。同 {@code IceMakerTile#recipeWrapper}。</p>
     */
    private final java.util.Map<Integer, net.minecraftforge.items.wrapper.RecipeWrapper> slotProbeCache =
            new java.util.HashMap<>();

    private net.minecraftforge.items.wrapper.RecipeWrapper slotProbe(int inSlot) {
        return slotProbeCache.computeIfAbsent(inSlot,
                s -> new net.minecraftforge.items.wrapper.RecipeWrapper(new SingleSlotHandler(s)));
    }

    private IceMakeRecipe getRecipeForSlot(Level level, int inSlot) {
        if (level == null) return null;
        // 仅检查该输入槽
        var holder = level.getRecipeManager().getRecipeFor(
                MekCkRecipeTypes.ICE_MAKE_RECIPE_TYPE.get(), slotProbe(inSlot), level);
        IceMakeRecipe r = holder.orElse(null);
        if (r == null) return null;
        return r;
    }

    /** 仅暴露单个输入槽的 IItemHandler，用于配方匹配。 */
    private final class SingleSlotHandler implements net.minecraftforge.items.IItemHandlerModifiable {
        private final int slot;

        SingleSlotHandler(int slot) {
            this.slot = slot;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int index) {
            return items.getStackInSlot(slot);
        }

        @Override
        public void setStackInSlot(int index, ItemStack stack) {
            items.setStackInSlot(slot, stack);
        }

        @Override
        public ItemStack insertItem(int index, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int index, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int index) {
            return items.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int index, ItemStack stack) {
            return true;
        }
    }

    private boolean canInsertOutput(int outSlot, ItemStack result) {
        if (result.isEmpty()) return true;
        ItemStack existing = items.getStackInSlot(outSlot);
        if (existing.isEmpty()) return true;
        if (ItemStack.isSameItemSameTags(existing, result)) {
            // §F31：上限取 handler 的 getSlotLimit（产物槽 = Integer.MAX_VALUE）而非 stack 的 getMaxStackSize（64），
            // 否则产物堆到 ≥64 时这里恒判「放不下」⇒ AE2 下单/自动生产停摆，与写入口径 insertOutputDirectly(grow) 分裂。
            return cn.ism.mekck.util.CountMath.canStack(existing.getCount(), result.getCount(), items.getSlotLimit(outSlot));
        }
        return false;
    }

    /**
     * 机器内部产物写入：直接 set/grow 产物格。
     * 不能走 {@code items.insertItem}——Forge 的 ItemStackHandler.insertItem 会先调
     * {@code isItemValid}，产物槽对玩家禁入（false）会连机器自己的产出一起拦截
     * （2026-08-29 排查巧克力大炮无产物时 javap 反汇编确认，制冰工厂为同款隐患一并修复）。
     */
    private void insertOutputDirectly(int outSlot, ItemStack result) {
        if (result.isEmpty()) return;
        ItemStack existing = items.getStackInSlot(outSlot);
        if (existing.isEmpty()) {
            items.setStackInSlot(outSlot, result.copy());
        } else if (ItemStack.isSameItemSameTags(existing, result)) {
            existing.grow(result.getCount());
        }
    }

    private void performAttack(Level level) {
        // 上一波冰块尚未全部生成时不启动新一波（攻击计时器保持）
        if (!pendingAttackTargets.isEmpty()) return;

        ColdBrewHelper.Profile profile = ColdBrewHelper.compute(
                items.getStackInSlot(CB_SLOT_1), items.getStackInSlot(CB_SLOT_2),
                items.getStackInSlot(CB_SLOT_3), items.getStackInSlot(CB_SLOT_4),
                items.getStackInSlot(CB_SLOT_5));
        if (profile == null) return;

        // 与急冻制冰机一致：必须消耗输出格产物才能发动攻击（扫描全部输出格）；安装创造升级后不消耗产物（相当于无限弹药）
        boolean hasCreative = hasCreativeUpgrade();
        int budget = hasCreative ? Integer.MAX_VALUE : countOutputProducts();
        if (budget <= 0) return;

        attackTimer--;
        if (attackTimer > 0) return;
        attackTimer = MekckConfig.getIceAttackInterval();

        // 索敌：小半径走 AABB 快速路径，超大半径遍历已加载实体（避免巨型 AABB 导致 section key 溢出崩溃）
        List<LivingEntity> candidates = targetCache.get(
                level, worldPosition, this.radius, this.targetType, this::matchesTarget);

        int count = Math.min(profile.targetCount, candidates.size());
        LivingEntity highestHp = null;
        for (LivingEntity e : candidates) {
            if (highestHp == null || e.getHealth() > highestHp.getHealth()) highestHp = e;
        }

        // 构建本波目标队列；目标不足时若安装了集火升级（allowExtras），剩余冰块攻击血量最高的目标（可重复同一目标）
        List<LivingEntity> targets = new java.util.ArrayList<>(profile.targetCount);
        for (int i = 0; i < count; i++) {
            targets.add(candidates.get(i));
        }
        while (profile.allowExtras && targets.size() < profile.targetCount && highestHp != null) {
            targets.add(highestHp);
        }

        // 产物数量限制本波冰块数：每生成 1 个冰块消耗 1 个产物，不足时取消剩余冰块并终止本波
        if (targets.size() > budget) targets = targets.subList(0, budget);
        if (targets.isEmpty()) return;

        // 锁定标记：本波锁定的每个目标添加 1 次 1 秒发光（minecraft:glowing），标记索敌结果
        for (net.minecraft.world.entity.LivingEntity lockedTarget : targets) {
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
    private void spawnPendingAttack(Level level) {
        if (pendingAttackTargets.isEmpty()) return;
        var it = pendingAttackTargets.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            LivingEntity target = entry.getKey();
            if (!target.isAlive() || target.isRemoved()) {
                it.remove(); // 目标已消失：跳过，不消耗产物
                continue;
            }
            if (!hasCreativeUpgrade() && !consumeOneOutputProduct()) {
                pendingAttackTargets.clear(); // 产物耗尽：终止本波剩余冰块（创造升级不消耗）
                return;
            }
            IceCubeEntity.spawn(level, target.getX(), target.getY() + 5, target.getZ(), pendingDamage, pendingAoe, pendingSplash, pendingSlow, pendingRemoveAI, pendingHypothermia, worldPosition);
            setChanged();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
    }

    /** 统计全部输出格中的产物总数（攻击弹药）。 */
    private int countOutputProducts() {
        int total = 0;
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            total += items.getStackInSlot(INPUT_SLOTS + i).getCount();
        }
        return total;
    }

    /** 从第一个非空输出格消耗 1 个产物；无产物可消耗时返回 false。 */
    private boolean consumeOneOutputProduct() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            int slot = INPUT_SLOTS + i;
            if (!items.getStackInSlot(slot).isEmpty()) {
                items.extractItem(slot, 1, false);
                return true;
            }
        }
        return false;
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
        tag.put("SpeedUpgradeTracker", speedTracker.save());
        tag.put("EnergyUpgradeTracker", energyTracker.save());
        tag.put("StackUpgradeTracker", stackTracker.save());
        tag.put("CreativeUpgradeTracker", creativeTracker.save());
        if (heatComponent != null) tag.put("HeatCapacitor", heatComponent.save());
        cn.ism.mekck.compat.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        tag.put("Items", items.serializeNBT());
        tag.putInt("SlotLayoutVersion", 2);
        tag.put("Fluid", waterTank.writeToNBT(new CompoundTag()));
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putIntArray("Progress", progress);
        tag.putInt("AttackTimer", attackTimer);
        tag.putInt("TargetType", targetType);
        tag.putInt("Radius", radius);
        byte[] sideBytes = new byte[6];
        for (int i = 0; i < 6; i++) sideBytes[i] = (byte) sideConfig[i].ordinal();
        tag.putByteArray("SideConfig", sideBytes);
        tag.putInt("RedstoneControl", redstoneControl.ordinal());
        tag.putBoolean("RedstonePowered", redstonePowered);
        if (customName != null) tag.putString("CustomName", Component.Serializer.toJson(customName));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("SpeedUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) speedTracker.load(tag.getCompound("SpeedUpgradeTracker"));
        if (tag.contains("EnergyUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) energyTracker.load(tag.getCompound("EnergyUpgradeTracker"));
        if (tag.contains("StackUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) stackTracker.load(tag.getCompound("StackUpgradeTracker"));
        if (tag.contains("CreativeUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) creativeTracker.load(tag.getCompound("CreativeUpgradeTracker"));
        if (heatComponent != null && tag.contains("HeatCapacitor", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            heatComponent.load(tag.getCompound("HeatCapacitor"));
        }
        cn.ism.mekck.compat.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
        // 槽位迁移：旧星云/奇点工厂存档无创造升级槽（冷萃槽在 base+3 起），新布局在 base+3 插入创造槽，
        // 需将旧槽位 >= CREATIVE_SLOT 的内容后移一格，避免错位（仅对未带布局版本标记的旧存档生效）。
        CompoundTag itemsTag = tag.getCompound("Items");
        if (!tag.contains("SlotLayoutVersion") && itemsTag.getInt("Size") == TOTAL_SLOTS - 1) {
            net.minecraft.nbt.ListTag oldList = itemsTag.getList("Items", Tag.TAG_COMPOUND);
            net.minecraft.nbt.ListTag newList = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < oldList.size(); i++) {
                CompoundTag itemTags = oldList.getCompound(i);
                int slot = itemTags.getInt("Slot");
                if (slot >= CREATIVE_SLOT && slot + 1 < TOTAL_SLOTS) slot++;
                if (slot >= 0 && slot < TOTAL_SLOTS) {
                    itemTags.putByte("Slot", (byte) slot);
                    newList.add(itemTags);
                }
            }
            itemsTag.putInt("Size", TOTAL_SLOTS);
            itemsTag.put("Items", newList);
        }
        items.deserializeNBT(itemsTag);
        if (items.getSlots() != TOTAL_SLOTS) {
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
        if (tag.contains("Progress", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("Progress");
            for (int i = 0; i < Math.min(arr.length, progress.length); i++) progress[i] = arr[i];
        }
        attackTimer = tag.getInt("AttackTimer");
        // 存档里的旧值（写入时未夹紧）也可能是越界的，读回时一并归一化。
        targetType = cn.ism.mekck.util.IceTargetSearch.clampTargetType(tag.getInt("TargetType"));
        // 读档半径同样必须过唯一钳制闸门：存档里的旧值/被改过的值不能绕过上限（见 TestAttackRadiusClamp）。
        radius = IceTargetSearch.clampAttackRadius(tag.getInt("Radius"));
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
    }

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) return energyCapability.cast();
        if (capability == ForgeCapabilities.FLUID_HANDLER) return fluidCapability.cast();
        // 温度系统：暴露 Mekanism 热能力，供热力设备传导
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
        // 热能力同样要随方块实体失效/复活收口（对齐 CentralKitchenBlockEntity）。
        heatCapability.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        fullItemCapability = LazyOptional.of(() -> items);
        inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        energyCapability = LazyOptional.of(() -> energy);
        fluidCapability = LazyOptional.of(() -> waterTank);
        heatCapability = LazyOptional.of(() -> heatComponent.getHandler());
    }

    private final class InputItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return INPUT_SLOTS;
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
            return OUTPUT_SLOTS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(INPUT_SLOTS + slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return items.extractItem(INPUT_SLOTS + slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(INPUT_SLOTS + slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }
}
