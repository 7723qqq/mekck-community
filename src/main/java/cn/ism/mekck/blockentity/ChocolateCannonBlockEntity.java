package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.ChocolateCannonBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.entity.FerreroEntity;
import cn.ism.mekck.item.FerreroUpgradeItem;
import cn.ism.mekck.item.FerreroUpgradeTier;
import cn.ism.mekck.menu.ChocolateCannonMenu;
import cn.ism.mekck.recipe.FerreroRecipe;
import cn.ism.mekck.util.IceTargetSearch;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.UpgradeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 巧克力大炮：唯一的基础机器形态（无工厂版本）。
 * <p>
 * 加工部分：{@code mekck:ferrero} 配方（物品输入 + extra 物品输入 + 2 流体 → 费列罗巧克力），
 * 产物存入产物格，产物格同时是攻击弹药库。
 * </p>
 * <p>
 * 攻击部分：每 {@code ice_attack_interval}（默认 40 tick = 2 秒）消耗产物格中的费列罗发动一次攻击，
 * 在目标上方生成费列罗实体，索敌机制 / 射程 / 传送特效与音效同制冰工厂冷萃升级。
 * 升级效果：直击伤害按「已装费列罗升级数」查档（0..5 档依次为 250/500/600/700/800/1000，再乘配置 ferrero_damage_mult）、
 * 死神（额外 1 发，弹药消耗变 2）、
 * 脆心（移除 2 秒 AI）、霸王死神（范围 7×7×7）、超重力场（铁砧伤害 + 无死亡动画）。
 * </p>
 * <p>
 * 索敌为爆炸收益驱动而非最近目标：在射程内以每个敌人为潜在爆炸中心评估
 * （有效命中伤害 + 击杀奖励，扣距离惩罚），再叠加上飞行中弹药的伤害预留
 * （见 {@link cn.ism.mekck.util.ChocolateCannonReservations}）以避免对已被
 * 注定炸死的目标重复开火，并按剩余收益重新选择新目标。
 * </p>
 */
public final class ChocolateCannonBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable , cn.ism.mekck.ae2.INetworkPullable {
    public static final int INPUT_SLOT = 0;
    public static final int EXTRA_SLOT = 1;
    public static final int OUTPUT_SLOT = 2;
    public static final int SLOT_SPEED_UPGRADE = 3;
    public static final int SLOT_ENERGY_UPGRADE = 4;
    public static final int SLOT_CREATIVE_UPGRADE = 5;
    /** 5 个费列罗升级专属槽的起始索引，按 {@link FerreroUpgradeTier#ordinal()} 依次排列。 */
    public static final int FERRERO_SLOT_BASE = 6;
    public static final int FLUID_SLOT_1 = 11;
    public static final int FLUID_SLOT_2 = 12;
    public static final int SLOT_POWER = 13;
    public static final int TOTAL_SLOTS = 14;

    public static final int ENERGY_CAPACITY = 100_000;
    public static final int ENERGY_PER_TICK = 20;
    public static final int PROCESS_TIME = 100;
    public static final int MAX_RECEIVE = 1_000;
    public static final int TANK_CAPACITY = 8_000;

    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_SIDE_CONFIG = 3;
    public static final int DATA_SPEED_UPGRADE = 4;
    public static final int DATA_ENERGY_UPGRADE = 5;
    public static final int DATA_CREATIVE_UPGRADE = 6;
    public static final int DATA_REDSTONE_CONTROL = 7;
    public static final int DATA_TARGET_TYPE = 8;
    public static final int DATA_RADIUS = 9;
    /** 两个流体罐的量（mb）与流体注册 id：经 ContainerData 同步到客户端（流体条显示用，FluidTank 不自动进网络）。 */
    public static final int DATA_FLUID1_AMOUNT = 10;
    public static final int DATA_FLUID1_ID = 11;
    public static final int DATA_FLUID2_AMOUNT = 12;
    public static final int DATA_FLUID2_ID = 13;
    /** 升级安装进度（0~100）。 */
    public static final int DATA_UPGRADE_PROGRESS = 14;
    public static final int DATA_SIZE = 15;

    /** 目标类型：0=敌对生物（配置文件敌对列表），1=全部生物，2=非敌对生物（动物）。 */
    public static final int TARGET_HOSTILE = 0;
    public static final int TARGET_ALL = 1;
    public static final int TARGET_ANIMAL = 2;

    // -- 爆炸收益驱动索敌的评分参数 --
    /** 本发即可击杀的敌人额外奖励分（激励优先杀更多而非只数人头）。 */
    private static final float KILL_BONUS = 40.0F;
    /** 有效收益（value）的等效阈值：两个爆炸中心收益相差不足该值时视为“同等收益”，此时取距离更近者。
     *  这样距离只作同等收益下的优先级（软惩罚），而绝不会因为目标较远而完全拒绝攻击存在正收益的敌人。 */
    private static final float VALUE_EPSILON = 0.5F;
    /** 单个爆炸中心需高于此有效收益才开火：仅当所有潜在中心都没有正收益（已被覆盖/无命中）时才停止本波。
     *  距离不参与是否开火的判断，只参与同等收益下的就近选择。 */
    private static final double MIN_CENTER_VALUE = 0.0;

    private Component customName;
    private int progress;

    // ================== ME 终端下单（AE2） ==================
    private net.minecraft.resources.ResourceLocation orderRecipeId;
    private int orderQuantity;
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true;

    // ================== 升级读条（Mekanism 式） ==================
    private final cn.ism.mekck.util.MekCkUpgradeTracker speedTracker =
            new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getBasicSpeedUpgradeMax());
    private final cn.ism.mekck.util.MekCkUpgradeTracker energyTracker =
            new cn.ism.mekck.util.MekCkUpgradeTracker(() -> MekckConfig.getBasicEnergyUpgradeMax());
    private final cn.ism.mekck.util.MekCkUpgradeTracker creativeTracker =
            new cn.ism.mekck.util.MekCkUpgradeTracker(1);
    /** 费列罗升级各槽读条（每槽 1 个）。 */
    private final cn.ism.mekck.util.MekCkUpgradeTracker[] ferreroTrackers =
            new cn.ism.mekck.util.MekCkUpgradeTracker[cn.ism.mekck.item.FerreroUpgradeTier.values().length];
    {
        for (int i = 0; i < ferreroTrackers.length; i++) {
            ferreroTrackers[i] = new cn.ism.mekck.util.MekCkUpgradeTracker(1);
        }
    }
    private int attackTimer = 0;
    /**
     * 本轮剩余可发射的颗数（= 升级允许最大弹数 × 弹药预算）。
     * 采用“逐发重选当前最优目标”的单一流程：不做预选队列，每颗生成前都基于当前合法敌人+
     * 当前全局预留重新 pick 一个最优爆炸中心；预算只限制本轮的发射次数（死神 2、普通 1），不预选目标。
     */
    private int pendingShotsLeft = 0;
    private float pendingDamage;
    private byte pendingFlags;
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
            if (slot == INPUT_SLOT) {
                // 输入格只接受费列罗配方的物品输入
                return !isAnyUpgrade(stack) && cn.ism.mekck.util.RecipeInputMatcher.matchesFerreroInput(level, stack);
            }
            if (slot == EXTRA_SLOT) {
                // extra 格只接受费列罗配方的 extra 输入
                return !isAnyUpgrade(stack) && cn.ism.mekck.util.RecipeInputMatcher.matchesFerreroExtra(level, stack);
            }
            if (slot == OUTPUT_SLOT) return false;
            if (slot == SLOT_SPEED_UPGRADE) return UpgradeHelper.isSpeedUpgrade(stack);
            if (slot == SLOT_ENERGY_UPGRADE) return UpgradeHelper.isEnergyUpgrade(stack);
            if (slot == SLOT_CREATIVE_UPGRADE) return UpgradeHelper.isCreativeUpgrade(stack);
            if (slot >= FERRERO_SLOT_BASE && slot < FERRERO_SLOT_BASE + FerreroUpgradeTier.values().length) {
                FerreroUpgradeTier tier = FerreroUpgradeTier.values()[slot - FERRERO_SLOT_BASE];
                if (FerreroUpgradeItem.getTier(stack) != tier) return false;
                // 链式安装：必须先安装前一档升级（同冷萃升级的链式槽位规则）
                int idx = slot - FERRERO_SLOT_BASE;
                return idx == 0 || !items.getStackInSlot(FERRERO_SLOT_BASE + idx - 1).isEmpty();
            }
            if (slot == FLUID_SLOT_1 || slot == FLUID_SLOT_2) return isFluidContainer(stack);
            if (slot == SLOT_POWER) return PowerSlotUtil.isValidEnergyItem(stack);
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot == INPUT_SLOT || slot == EXTRA_SLOT || slot == OUTPUT_SLOT) return Integer.MAX_VALUE;
            if (slot == SLOT_CREATIVE_UPGRADE) return 1;
            if (slot == SLOT_POWER) return 64;
            if (slot >= FERRERO_SLOT_BASE && slot < FERRERO_SLOT_BASE + FerreroUpgradeTier.values().length) return 1;
            if (slot == FLUID_SLOT_1 || slot == FLUID_SLOT_2) return 1;
            return MekckConfig.getBasicSpeedUpgradeMax();
        }

        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            if (slot == INPUT_SLOT || slot == EXTRA_SLOT || slot == OUTPUT_SLOT || slot == SLOT_CREATIVE_UPGRADE
                    || slot == FLUID_SLOT_1 || slot == FLUID_SLOT_2
                    || (slot >= FERRERO_SLOT_BASE && slot < FERRERO_SLOT_BASE + FerreroUpgradeTier.values().length)) {
                return getSlotLimit(slot);
            }
            return super.getStackLimit(slot, stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    /** 两个独立流体输入罐（流体 1 / 流体 2）。任意流体均可注入，由配方匹配时校验；同时对外暴露给管道。 */
    private final cn.ism.mekck.util.MultiFluidHandler fluidTanks = new cn.ism.mekck.util.MultiFluidHandler(2, TANK_CAPACITY, this::setChanged);

    private final EnergyStorage energy = new EnergyStorage(ENERGY_CAPACITY, MAX_RECEIVE, ENERGY_PER_TICK) {
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
                case DATA_ENERGY -> energy.getEnergyStored();
                case DATA_SIDE_CONFIG -> encodeSideConfig();
                case DATA_SPEED_UPGRADE -> getSpeedUpgradeCount();
                case DATA_ENERGY_UPGRADE -> getEnergyUpgradeCount();
                case DATA_CREATIVE_UPGRADE -> hasCreativeUpgrade() ? 1 : 0;
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
                case DATA_TARGET_TYPE -> targetType;
                case DATA_RADIUS -> radius;
                case DATA_FLUID1_AMOUNT -> fluidTanks.getTank(0).getFluidAmount();
                case DATA_FLUID1_ID -> fluidTanks.getTank(0).getFluid().isEmpty() ? -1
                        : net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(fluidTanks.getTank(0).getFluid().getFluid());
                case DATA_FLUID2_AMOUNT -> fluidTanks.getTank(1).getFluidAmount();
                case DATA_FLUID2_ID -> fluidTanks.getTank(1).getFluid().isEmpty() ? -1
                        : net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(fluidTanks.getTank(1).getFluid().getFluid());
                case DATA_UPGRADE_PROGRESS -> (int) Math.round(getUpgradeInstallProgress() * 100.0);
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

    public ChocolateCannonBlockEntity(BlockPos pos, BlockState state) {
        super(UniversalCuttingMachine.CHOCOLATE_CANNON_BLOCK_ENTITY.get(), pos, state);
        for (int i = 0; i < 6; i++) sideConfig[i] = SideMode.NONE;
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
        this.fluidCapability = LazyOptional.of(() -> fluidTanks);
    }

    public static boolean isAnyUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (FerreroUpgradeItem.getTier(stack) != null) return true;
        if (cn.ism.mekck.item.ColdBrewUpgradeItem.getTier(stack) != null) return true;
        return UpgradeHelper.isUpgrade(stack);
    }

    public static boolean isFluidContainer(ItemStack stack) {
        return !stack.isEmpty() && stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM, null).isPresent();
    }

    /** 已安装数量（读条完成后生效）。 */
    public int getSpeedUpgradeCount() {
        return speedTracker.getInstalled();
    }

    public int getEnergyUpgradeCount() {
        return energyTracker.getInstalled();
    }

    public boolean hasCreativeUpgrade() {
        return creativeTracker.getInstalled() > 0;
    }

    /** 判断某个费列罗升级是否已安装（读条完成后生效）。 */
    public boolean isFerreroInstalled(FerreroUpgradeTier tier) {
        return ferreroTrackers[tier.ordinal()].getInstalled() > 0;
    }

    /** 升级安装读条进度（0~1，供升级界面）。 */
    public double getUpgradeInstallProgress() {
        double best = Math.max(speedTracker.getProgress(),
                Math.max(energyTracker.getProgress(), creativeTracker.getProgress()));
        for (cn.ism.mekck.util.MekCkUpgradeTracker t : ferreroTrackers) {
            best = Math.max(best, t.getProgress());
        }
        return best;
    }

    /**
     * 卸载升级（复刻 Mekanism removeUpgrade）：
     * mode 0/1 作用于速度/能量/创造升级（slot 指定槽位）；mode 2 作用于费列罗升级槽（slot = 槽位索引）。
     * 卸载出的物品优先放回原槽，放不下则不卸载。
     */
    public void uninstallUpgrade(byte mode, int slot) {
        if (mode == 2) {
            int base = FERRERO_SLOT_BASE;
            if (slot < base || slot >= base + cn.ism.mekck.item.FerreroUpgradeTier.values().length) return;
            int idx = slot - base;
            if (ferreroTrackers[idx].getInstalled() <= 0) return;
            cn.ism.mekck.item.FerreroUpgradeTier tier = cn.ism.mekck.item.FerreroUpgradeTier.values()[idx];
            net.minecraft.world.item.Item item = cn.ism.mekck.item.FerreroUpgradeItem.REGISTRY.get(tier) == null
                    ? null : cn.ism.mekck.item.FerreroUpgradeItem.REGISTRY.get(tier).get();
            if (item == null || item == net.minecraft.world.item.Items.AIR) return;
            ItemStack give = new ItemStack(item);
            ItemStack inSlot = items.getStackInSlot(slot);
            if (!inSlot.isEmpty() && (!ItemStack.isSameItemSameTags(inSlot, give)
                    || inSlot.getCount() >= inSlot.getMaxStackSize())) {
                return;
            }
            ferreroTrackers[idx].uninstall(1);
            if (inSlot.isEmpty()) {
                items.setStackInSlot(slot, give);
            } else {
                inSlot.grow(1);
            }
            setChanged();
            return;
        }
        cn.ism.mekck.util.MekCkUpgradeTracker tracker;
        String itemId;
        if (slot == SLOT_SPEED_UPGRADE) {
            tracker = speedTracker;
            itemId = "mekanism:upgrade_speed";
        } else if (slot == SLOT_ENERGY_UPGRADE) {
            tracker = energyTracker;
            itemId = "mekanism:upgrade_energy";
        } else if (slot == SLOT_CREATIVE_UPGRADE) {
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

    /** 服务端每 tick：推进各升级槽的安装读条。 */
    private void tickUpgrades() {
        boolean changed = false;
        changed |= speedTracker.tick(items.getStackInSlot(SLOT_SPEED_UPGRADE), UpgradeHelper::isSpeedUpgrade);
        changed |= energyTracker.tick(items.getStackInSlot(SLOT_ENERGY_UPGRADE), UpgradeHelper::isEnergyUpgrade);
        changed |= creativeTracker.tick(items.getStackInSlot(SLOT_CREATIVE_UPGRADE), UpgradeHelper::isCreativeUpgrade);
        for (cn.ism.mekck.item.FerreroUpgradeTier tier : cn.ism.mekck.item.FerreroUpgradeTier.values()) {
            int slot = FERRERO_SLOT_BASE + tier.ordinal();
            changed |= ferreroTrackers[tier.ordinal()].tick(items.getStackInSlot(slot),
                    s -> cn.ism.mekck.item.FerreroUpgradeItem.getTier(s) == tier);
        }
        if (changed) setChanged();
    }

    public int addUpgradesFromHand(ItemStack held) {
        FerreroUpgradeTier tier = FerreroUpgradeItem.getTier(held);
        if (tier != null) {
            int slot = FERRERO_SLOT_BASE + tier.ordinal();
            if (items.getStackInSlot(slot).isEmpty() && items.isItemValid(slot, held)) {
                items.setStackInSlot(slot, new ItemStack(held.getItem(), 1));
                return 1;
            }
            return 0;
        }
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

    public FluidTank getFluidTank1() {
        return fluidTanks.getTank(0);
    }

    public FluidTank getFluidTank2() {
        return fluidTanks.getTank(1);
    }

    public cn.ism.mekck.util.MultiFluidHandler getFluidTanks() {
        return fluidTanks;
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
        // AE2 网格节点销毁（未安装 AE2 时为空操作；节点 NBT 由 saveAdditional 保存，重载后 init 重建）
        cn.ism.mekck.util.AE2Compat.onRemoved(this);
    }

    // ================== 本机下单（面板「本机 / ME」的本机一侧） ==================

    /** 本机下单涉及的输入槽。 */
    private int[] orderInputSlots() {
        return new int[]{INPUT_SLOT, EXTRA_SLOT};
    }

    /** 供「本机下单」面板展示：输入槽里的物品能做的全部配方。 */
    public List<net.minecraft.world.item.crafting.Recipe<?>> getAvailableRecipes() {
        List<net.minecraft.world.item.crafting.Recipe<?>> out = new ArrayList<>();
        if (level == null) return out;
        net.minecraft.world.item.crafting.RecipeType<?> type =
                cn.ism.mekck.util.RecipeCache.type(new net.minecraft.resources.ResourceLocation("mekck:ferrero"));
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
    @Override public net.minecraft.world.level.block.entity.BlockEntity getNetworkPullable() { return this; }
    @Override public int[] getInputSlotRange() { return new int[]{0, INPUT_SLOT + 1}; }
    @Override public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() { return items; }
    @Override public boolean supportsAutoPull() { return true; } // ME 持续补料：按"每类型上限"（配置 auto_pull_stack_limit）批量补，受 LagMonitor 限流

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) return List.of();
        return cn.ism.mekck.util.NetworkPullHelper.currentOrUnion(level, items.getStackInSlot(0),
                new net.minecraft.resources.ResourceLocation("mekck", "ferrero"));
    }

    public ContainerData getData() {
        return data;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        return customName != null ? customName : Component.translatable("block.mekck.chocolate_cannon");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ChocolateCannonMenu(containerId, inventory, this, data);
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

    private void decodeSideConfig(int v) {
        for (int i = 0; i < 6; i++) {
            int ord = (v >> (i * 4)) & 0xF;
            if (ord >= 0 && ord < SideMode.values().length) sideConfig[i] = SideMode.values()[ord];
        }
    }

    // ================== 处理与攻击 ==================
    public static void clientTick(Level level, BlockPos pos, BlockState state, ChocolateCannonBlockEntity machine) {
        // 客户端无需额外逻辑
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ChocolateCannonBlockEntity machine) {
        // 升级读条：槽位放入升级后 20 tick 安装一次（复刻 Mekanism TileComponentUpgrade）
        machine.tickUpgrades();
        boolean wasActive = machine.progress > 0;
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.util.AE2Compat.serverTick(machine, level, pos);

        machine.updateRedstone();

        if (machine.drainPowerSlot()) machine.setChanged();

        boolean hasCreative = machine.hasCreativeUpgrade();
        if (hasCreative) {
            machine.energy.receiveEnergy(machine.energy.getMaxEnergyStored() - machine.energy.getEnergyStored(), false);
        }

        // 流体容器槽：把桶/储罐中的流体抽进对应的流体罐（空容器留在槽内）
        machine.handleFluidSlot(FLUID_SLOT_1, machine.fluidTanks.getTank(0));
        machine.handleFluidSlot(FLUID_SLOT_2, machine.fluidTanks.getTank(1));

        double speedMult = machine.getEffectiveSpeedMultiplier();
        double energyConsumptionMult = machine.getEffectiveEnergyConsumptionMultiplier();
        int effectiveProcessTime = hasCreative ? 1 : machine.getEffectiveProcessTime();
        int energyPerTick = hasCreative ? 0 : (int) Math.ceil(ENERGY_PER_TICK * speedMult * speedMult * energyConsumptionMult);

        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        FerreroRecipe recipe = machine.getRecipe(level);
        // 两个流体罐允许互换（流体1/流体2 在任一分配下满足即可）；先判空再查罐，无配方（如材料未放/已取走）时不能访问 recipe
        boolean normalOrder = false;
        boolean swappedOrder = false;
        if (recipe != null) {
            normalOrder = machine.tankContains(machine.fluidTanks.getTank(0), recipe.getFluid1())
                    && machine.tankContains(machine.fluidTanks.getTank(1), recipe.getFluid2());
            swappedOrder = machine.tankContains(machine.fluidTanks.getTank(0), recipe.getFluid2())
                    && machine.tankContains(machine.fluidTanks.getTank(1), recipe.getFluid1());
        }
        boolean canWork = recipe != null && machine.canFunctionRedstone()
                && (normalOrder || swappedOrder)
                && machine.canInsertOutput(recipe.getResultItem(level.registryAccess()));

        if (canWork) {
            boolean hasEnergy = hasCreative || machine.energy.getEnergyStored() >= energyPerTick;
            if (hasEnergy) {
                if (machine.redstoneControl == RedstoneControl.PULSE) {
                    machine.pulseRunning = false;
                }
                if (!hasCreative) machine.energy.extractEnergy(energyPerTick, false);
                machine.progress++;
                if (machine.progress >= effectiveProcessTime) {
                    // 按校验通过的分配方向排水（两罐可互换）
                    if (normalOrder) {
                        machine.fluidTanks.getTank(0).drain(recipe.getFluid1().getAmount(), IFluidHandler.FluidAction.EXECUTE);
                        machine.fluidTanks.getTank(1).drain(recipe.getFluid2().getAmount(), IFluidHandler.FluidAction.EXECUTE);
                    } else {
                        machine.fluidTanks.getTank(0).drain(recipe.getFluid2().getAmount(), IFluidHandler.FluidAction.EXECUTE);
                        machine.fluidTanks.getTank(1).drain(recipe.getFluid1().getAmount(), IFluidHandler.FluidAction.EXECUTE);
                    }
                    machine.items.extractItem(INPUT_SLOT, 1, false);
                    machine.items.extractItem(EXTRA_SLOT, 1, false);
                    machine.insertOutputDirectly(recipe.getResultItem(level.registryAccess()));
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
                    machine.setChanged();
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

        BlockState newState = state.setValue(ChocolateCannonBlock.ACTIVE, machine.progress > 0);
        if (newState != state) level.setBlock(pos, newState, 3);

        if (!level.isClientSide) {
            machine.data.get(DATA_ENERGY);
        }
    }

    private void handleFluidSlot(int slot, FluidTank tank) {
        ItemStack container = items.getStackInSlot(slot);
        if (container.isEmpty()) return;
        int space = tank.getCapacity() - tank.getFluidAmount();
        if (space <= 0) return;
        LazyOptional<IFluidHandlerItem> cap = container.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM, null);
        if (!cap.isPresent()) return;
        IFluidHandlerItem handler = cap.resolve().orElse(null);
        if (handler == null) return;
        FluidStack sim = handler.drain(space, IFluidHandler.FluidAction.SIMULATE);
        if (sim.isEmpty() || !tank.isFluidValid(sim)) return;
        // 罐内已有不同流体时不注入（单流体罐）
        FluidStack current = tank.getFluid();
        if (!current.isEmpty() && !current.isFluidEqual(sim)) return;
        FluidStack real = handler.drain(space, IFluidHandler.FluidAction.EXECUTE);
        if (!real.isEmpty()) {
            tank.fill(real, IFluidHandler.FluidAction.EXECUTE);
            items.setStackInSlot(slot, handler.getContainer());
            setChanged();
        }
    }

    private boolean tankContains(FluidTank tank, FluidStack required) {
        FluidStack current = tank.getFluid();
        return !current.isEmpty() && current.isFluidEqual(required) && current.getAmount() >= required.getAmount();
    }

    /** 复用的配方包装器：原先每次配方查找都 new RecipeWrapper(items)，而这是每 tick 调用的路径。 */
    private RecipeWrapper cachedRecipeWrapper;

    private RecipeWrapper recipeWrapper() {
        if (cachedRecipeWrapper == null) cachedRecipeWrapper = new RecipeWrapper(items);
        return cachedRecipeWrapper;
    }

    private FerreroRecipe getRecipe(Level level) {
        if (level == null) return null;
        var manager = level.getRecipeManager();
        var opts = recipeWrapper();
        var holder = manager.getRecipeFor(UniversalCuttingMachine.FERRERO_RECIPE_TYPE.get(), opts, level);
        FerreroRecipe found = holder.orElse(null);
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
        this.orderQuantity = Math.max(1, quantity);
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
     * {@code isItemValid}，而产物槽对玩家禁入（返回 false）会连机器自己的产出一起拦截，
     * 导致「消耗了材料却没有产物」（2026-08-29 实测问题根因，javap 反汇编确认）。
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
        // 本轮仍剩余发射预算（含可能因目标失效而动态重索敌的部分）时不启动新一波（攻击计时器保持）
        if (pendingShotsLeft > 0) return;

        boolean coconut = isFerreroInstalled(FerreroUpgradeTier.COCONUT);
        boolean reaper = isFerreroInstalled(FerreroUpgradeTier.REAPER);
        boolean crispy = isFerreroInstalled(FerreroUpgradeTier.CRISPY);
        boolean overlord = isFerreroInstalled(FerreroUpgradeTier.OVERLORD);
        boolean gravity = isFerreroInstalled(FerreroUpgradeTier.GRAVITY);

        // 必须消耗产物格费列罗才能发动攻击：无产物时不攻击（计时器保持，产物就绪后立即恢复）；创造升级后不消耗（无限弹药）
        boolean hasCreative = hasCreativeUpgrade();
        ItemStack output = items.getStackInSlot(OUTPUT_SLOT);
        if (!hasCreative && output.isEmpty()) return;

        attackTimer--;
        if (attackTimer > 0) return;
        // 创造升级额外效果：攻速变为每 10 tick 攻击一轮
        attackTimer = hasCreative ? 10 : MekckConfig.getIceAttackInterval();

        // 计算本轮攻击参数（伤害/flags）与发射预算。预算 = 升级允许最大弹数 × 弹药约束。
        int shots = reaper ? 2 : 1;
        // §F17 直击伤害按「已装费列罗升级数」查档（阶梯单点定义见 FerreroUpgradeProfile），
        // 再于此处一处乘 ferrero_damage_mult——下游 reserve/pick/处决/applyHit 共用 pendingDamage，天然一致。
        int installedTiers = (coconut ? 1 : 0) + (reaper ? 1 : 0) + (crispy ? 1 : 0)
                + (overlord ? 1 : 0) + (gravity ? 1 : 0);
        pendingDamage = cn.ism.mekck.util.FerreroUpgradeProfile.damageOf(installedTiers)
                * (float) MekckConfig.getFerreroDamageMult();
        byte flags = 0;
        if (crispy) flags |= FerreroEntity.FLAG_CRISPY;
        if (gravity) flags |= FerreroEntity.FLAG_GRAVITY;
        if (overlord) flags |= FerreroEntity.FLAG_OVERLORD;
        pendingFlags = flags;

        // 产物数量限制本轮发数上限：每生成 1 发消耗 1 个费列罗（死神升级一发消耗 2 个）。
        int budget = hasCreative ? Integer.MAX_VALUE : output.getCount() / (reaper ? 2 : 1);
        // 本轮预算以升级允许的最大弹数为准（非预选目标数）；实际发射颗数由后续逐发重选在预算内决定。
        pendingShotsLeft = Math.max(0, Math.min(shots, budget));
        if (pendingShotsLeft <= 0) return;
    }

    /**
     * 逐 tick 发射至多一颗费列罗（受 {@link #pendingShotsLeft} 本轮预算约束）。
     * <p><b>逐发重选当前最优目标</b>：不做预选队列。每颗生成前都基于当前合法候选（{@link IceTargetSearch}，
     * 受本机半径/目标类型过滤）与全局有效预留（{@link ChocolateCannonReservations#reservedDamageOn}，含本波已发射的真实预留）
     * 重新 pick 一个最优爆炸中心。因此：第二发必然读取第一发的预留；若第一发足以清空某敌群则第二发不再选它，
     * 转而选另一仍有正收益的敌群；若全场无正收益则结束本轮。</p>
     * <p>发射成功（实体入世界）后才扣弹药并登记预留；spawn 失败 → 结束本轮（不扣弹药、不登记、无幽霗预留），
     * 等待下轮冷却后由 performAttack 重新开局，避免每 tick 无限重试。</p>
     */
    private void spawnPendingAttack(Level level) {
        if (pendingShotsLeft <= 0) return;
        boolean reaper = isFerreroInstalled(FerreroUpgradeTier.REAPER);
        int ammoCost = reaper ? 2 : 1;
        if (!hasCreativeUpgrade() && items.getStackInSlot(OUTPUT_SLOT).getCount() < ammoCost) {
            pendingShotsLeft = 0; // 弹药耗尽：结束本轮
            return;
        }

        // 先清理本世界已过期/已卸载的预留（超时兜底）
        cn.ism.mekck.util.ChocolateCannonReservations.pruneExpired(level);

        // 每发重新索敌：候选已由 IceTargetSearch 按当前半径/类型过滤；pick 会扣除当前全局预留选最优中心。
        java.util.List<LivingEntity> candidates = candidatesFor(level);
        java.util.List<LivingEntity> best = pickExplosionTargets(level, candidates, pendingDamage, pendingFlags, 1);
        if (best.isEmpty()) {
            // 全场无正收益敌群（含被当前预留覆盖到无可剩的）：结束本轮，不发不扣，避免无限空扫。
            pendingShotsLeft = 0;
            return;
        }
        LivingEntity target = best.get(0);
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return; // 竞态：目标刚失效；下一 tick 再重选（预算保留）
        }
        // 生成前复核：目标区域在扣除当前预留后仍应有正收益（pick 已保证，此处防 spawn 前瞬间的变更）
        if (!hasRemainingTargetValue(level, target, pendingDamage, pendingFlags)) {
            return; // 已无正收益：下一 tick 重选（预算保留）
        }

        FerreroEntity projectile = FerreroEntity.spawn(level, target.getX(), target.getY() + 5, target.getZ(), pendingDamage, pendingFlags, worldPosition);
        if (projectile == null) {
            pendingShotsLeft = 0; // 发射失败：结束本轮，不扣弹药、不登记；由下轮冷却后重新开局
            return;
        }
        // 发射成功：登记预留（精确目标位置 = 预测爆炸中心）+ 锁定标记（1 秒发光）
        cn.ism.mekck.util.ChocolateCannonReservations.reserve(this, projectile,
                target.position(), pendingDamage, pendingFlags);
        if (target.isAlive()) {
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.GLOWING, 20, 0, false, false));
        }
        if (!hasCreativeUpgrade()) {
            items.extractItem(OUTPUT_SLOT, ammoCost, false);
            setChanged();
        }
        pendingShotsLeft--; // 已发射一颗
    }

    /**
     * 复核某目标爆炸中心的当前有效收益：该范围内是否存在扣除现有飞行中预留后仍可命中的本炮目标敌人。
     * 查询使用与弹丸 {@code impact()} 一致的 AABB 范围判定，并受本机目标类型过滤。
     */
    private boolean hasRemainingTargetValue(Level level, LivingEntity target, float damage, byte flags) {
        float range = FerreroEntity.getExplosionRange(flags);
        Vec3 center = target.position(); // 预测爆炸中心 = 目标精确坐标（与 impact() 用弹丸精确坐标的几何一致）
        AABB area = AABB.ofSize(center, range, range, range);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            if (!matchesTarget(e)) continue;
            float reserved = cn.ism.mekck.util.ChocolateCannonReservations.reservedDamageOn(level, e); // 保守预留
            float rem = e.getHealth() - reserved;
            if (rem > 0.0F && Math.min(damage, rem) > 0.0F) return true;
        }
        return false;
    }

    // ================== 爆炸收益驱动的群体索敌 ==================
    // ── 索敌结果缓存 ──
    // 开火期间每 tick 都要选一次目标，而 findTargets 在半径较大时要遍历全部已加载实体
    // （半径 > 512 时走的就是"全实体扫描"路径）。候选列表按 4 tick 复用一次：
    // 命中判定与收益评分（pickExplosionTargets / scoreCenter）仍然每 tick 重新计算，
    // 死亡的实体也会在评分时被跳过，因此只影响"新进入射程的敌人最多晚 0.2 秒被发现"。
    private static final int CANDIDATE_CACHE_TICKS = 4;
    private java.util.List<LivingEntity> cachedCandidates;
    private long cachedCandidatesTick = Long.MIN_VALUE;
    private int cachedCandidatesRadius = -1;
    private int cachedCandidatesTargetType = -1;

    /** 取本 tick 的候选目标（半径 / 目标类型变化或缓存过期时重新扫描）。 */
    private java.util.List<LivingEntity> candidatesFor(Level level) {
        long now = level.getGameTime();
        if (cachedCandidates != null
                && cachedCandidatesRadius == this.radius
                && cachedCandidatesTargetType == this.targetType
                && now - cachedCandidatesTick < CANDIDATE_CACHE_TICKS
                && now >= cachedCandidatesTick) {
            return cachedCandidates;
        }
        java.util.List<LivingEntity> found = IceTargetSearch.findTargets(
                level, worldPosition, this.radius, this::matchesTarget);
        cachedCandidates = found;
        cachedCandidatesTick = now;
        cachedCandidatesRadius = this.radius;
        cachedCandidatesTargetType = this.targetType;
        return found;
    }

    /**
     * 从 {@code candidates} 中为每发挑选一个爆炸中心，返回本波要锁定并发射的目标（顺序无关）。
     * 以每个候选敌人为潜在爆炸中心，累加其爆炸范围内每个敌人的实际伤害
     * （扣掉飞行中预留与已选发数后，超出剩余生命视为溢出不再浪费）与击杀奖励作为有效收益 value；
     * 选择时按 value 排序，收益近似（差距 < {@link #VALUE_EPSILON}）时取距离更近者——
     * 距离只作同等收益下的软优先级，绝不因目标较远而拒绝攻击存在正收益的敌人。
     * 已选中心覆盖到的敌人计入预留，使下一发自动转向未被覆盖的区域。
     */
    private java.util.List<LivingEntity> pickExplosionTargets(Level level,
                                                             java.util.List<LivingEntity> candidates,
                                                             float damage, byte flags, int shots) {
        java.util.List<LivingEntity> chosen = new java.util.ArrayList<>(Math.max(0, shots));
        if (candidates == null || candidates.isEmpty() || shots <= 0) return chosen;
        float range = FerreroEntity.getExplosionRange(flags);
        Vec3 cannonCenter = new Vec3(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5);
        // 每个敌人已被预留（飞行中弹药 + 本波已选发数）的伤害量。
        java.util.Map<UUID, Float> reserved = computeExistingReservations(level, candidates);
        // 预取每个候选的碰撞箱（每 tick 每个候选只取一次）。原先 scoreCenter 的内层对每个
        // (爆炸中心 × 候选) 组合都要 AABB.ofSize + getBoundingBox + position()，n 个候选即 O(n²)
        // 次对象分配；半径较大时（可覆盖全部已加载实体）这是开火期间最大的一笔开销。
        double[] bounds = candidateBounds(candidates);

        for (int s = 0; s < shots; s++) {
            LivingEntity best = null;
            double bestValue = Double.NEGATIVE_INFINITY;
            double bestDist = Double.POSITIVE_INFINITY;
            for (LivingEntity center : candidates) {
                if (center == null || !center.isAlive() || center.isRemoved()) continue;
                double value = scoreCenter(center, candidates, bounds, damage, range, reserved);
                if (value <= 0.0) continue; // 该中心已无可获益敌人（全被覆盖/无命中）
                if (value > bestValue + VALUE_EPSILON) {
                    // 收益明显更高 → 直接领先
                    best = center;
                    bestValue = value;
                    bestDist = cannonCenter.distanceTo(center.position());
                } else if (value > bestValue - VALUE_EPSILON) {
                    // 收益近乎相同（差距 < VALUE_EPSILON）→ 就近优先，距离仅作同等收益下的软优先级
                    double dist = cannonCenter.distanceTo(center.position());
                    if (dist < bestDist) {
                        best = center;
                        bestValue = value;
                        bestDist = dist;
                    }
                }
            }
            if (best == null || bestValue <= MIN_CENTER_VALUE) break; // 全场无正收益中心，停止本波（不浪费弹药）
            chosen.add(best);
            // 把本发覆盖到的所有敌人计入预留，让下一发自动转向未被覆盖的区域
            for (int i = 0; i < candidates.size(); i++) {
                LivingEntity e = candidates.get(i);
                if (e != null && blastHits(bounds, i, best.getX(), best.getY(), best.getZ(), range)) {
                    reserved.merge(e.getUUID(), damage, Float::sum);
                }
            }
        }
        return chosen;
    }

    /**
     * 计算以 {@code center} 为爆炸中心的“有效命中收益”：仅累加仍存在剩余生命（扣掉预留伤害后）的敌人。
     * 每个敌人取“本发实际能造成的伤害”（cap 在剩余生命，溢出不算），击杀额外叠加 {@link #KILL_BONUS}。
     * 返回值只表达收益大小，距离由调用方作为同等收益下的就近优先级处理，不作为负分门槛。
     */
    private double scoreCenter(LivingEntity center,
                               java.util.List<LivingEntity> candidates, double[] bounds, float damage,
                               float range, java.util.Map<UUID, Float> reserved) {
        double value = 0.0;
        double cx = center.getX();
        double cy = center.getY();
        double cz = center.getZ();
        for (int i = 0; i < candidates.size(); i++) {
            LivingEntity e = candidates.get(i);
            if (e == null || !e.isAlive()) continue;
            if (!blastHits(bounds, i, cx, cy, cz, range)) continue;
            float rem = Math.max(0.0F, e.getHealth() - reserved.getOrDefault(e.getUUID(), 0.0F));
            if (rem <= 0.0F) continue; // 已被注定炸死/覆盖，不重复计收益
            float dealt = Math.min(damage, rem); // 实际能造成的伤害（超出生命部分视为溢出，不浪费收益）
            value += dealt;
            if (dealt >= rem) value += KILL_BONUS; // 本发即可击杀，叠加击杀奖励
        }
        return value;
    }

    /** 预取候选碰撞箱边界（minX,minY,minZ,maxX,maxY,maxZ；死亡/无效候选记 NaN）。 */
    private static double[] candidateBounds(java.util.List<LivingEntity> candidates) {
        double[] bounds = new double[candidates.size() * 6];
        for (int i = 0; i < candidates.size(); i++) {
            LivingEntity e = candidates.get(i);
            if (e == null || !e.isAlive() || e.isRemoved()) {
                bounds[i * 6] = Double.NaN;
                continue;
            }
            net.minecraft.world.phys.AABB box = e.getBoundingBox();
            bounds[i * 6] = box.minX;
            bounds[i * 6 + 1] = box.minY;
            bounds[i * 6 + 2] = box.minZ;
            bounds[i * 6 + 3] = box.maxX;
            bounds[i * 6 + 4] = box.maxY;
            bounds[i * 6 + 5] = box.maxZ;
        }
        return bounds;
    }

    /**
     * 与 {@code AABB.ofSize(center, range, range, range).intersects(entity.getBoundingBox())} **完全等价**的
     * 纯算术判定（AABB.intersects 用的就是这组严格不等号），但**零分配**：
     * 不再为每一对 (中心 × 候选) 新建 Vec3 / AABB。中心坐标由调用方直接传实体坐标（等价于 entity.position()）。
     */
    private static boolean blastHits(double[] bounds, int index, double cx, double cy, double cz, float range) {
        int o = index * 6;
        double minX = bounds[o];
        if (Double.isNaN(minX)) return false;
        double minY = bounds[o + 1];
        double minZ = bounds[o + 2];
        double maxX = bounds[o + 3];
        double maxY = bounds[o + 4];
        double maxZ = bounds[o + 5];
        double half = range / 2.0;
        return minX < cx + half && maxX > cx - half
                && minY < cy + half && maxY > cy - half
                && minZ < cz + half && maxZ > cz - half;
    }

    /** 累加当前飞行中弹药对每个候选的预留伤害（汇总各台大炮的未爆炸弹药）。 */
    private java.util.Map<UUID, Float> computeExistingReservations(Level level, java.util.List<LivingEntity> candidates) {
        java.util.Map<UUID, Float> reserved = new HashMap<>();
        for (LivingEntity e : candidates) {
            if (e == null) continue;
            float r = cn.ism.mekck.util.ChocolateCannonReservations.reservedDamageOn(level, e);
            if (r > 0.0F) reserved.put(e.getUUID(), r);
        }
        return reserved;
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
        // 大堆叠感知：21 亿若按原版 64 分堆会瞬间生成数千万个物品实体
        cn.ism.mekck.util.BigStackDrops.dropAll(level, pos, drops);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        cn.ism.mekck.util.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        tag.put("Items", items.serializeNBT());
        tag.put("Fluids", fluidTanks.writeToNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Progress", progress);
        if (orderRecipeId != null) {
            tag.putString("OrderRecipeId", orderRecipeId.toString());
            tag.putInt("OrderQuantity", orderQuantity);
            tag.putInt("OrderCompleted", orderCompleted);
        }
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        tag.put("SpeedUpgrade", speedTracker.save());
        tag.put("EnergyUpgrade", energyTracker.save());
        tag.put("CreativeUpgrade", creativeTracker.save());
        for (int i = 0; i < ferreroTrackers.length; i++) {
            tag.put("Ferrero" + i, ferreroTrackers[i].save());
        }
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
        if (tag.contains("Fluids")) fluidTanks.readFromNBT(tag.getCompound("Fluids"));
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
        if (tag.contains("SpeedUpgrade", net.minecraft.nbt.Tag.TAG_COMPOUND)) speedTracker.load(tag.getCompound("SpeedUpgrade"));
        if (tag.contains("EnergyUpgrade", net.minecraft.nbt.Tag.TAG_COMPOUND)) energyTracker.load(tag.getCompound("EnergyUpgrade"));
        if (tag.contains("CreativeUpgrade", net.minecraft.nbt.Tag.TAG_COMPOUND)) creativeTracker.load(tag.getCompound("CreativeUpgrade"));
        for (int i = 0; i < ferreroTrackers.length; i++) {
            if (tag.contains("Ferrero" + i, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                ferreroTrackers[i].load(tag.getCompound("Ferrero" + i));
            }
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
    }

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) return energyCapability.cast();
        if (capability == ForgeCapabilities.FLUID_HANDLER) {
            // 管道注入：两个流体罐以多流体槽形式统一暴露（同类型流体只存于一个子槽）
            return fluidCapability.cast();
        }
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
        fluidCapability = LazyOptional.of(() -> fluidTanks);
    }

    private final class InputItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return 2;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(slot == 0 ? INPUT_SLOT : EXTRA_SLOT);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return items.insertItem(slot == 0 ? INPUT_SLOT : EXTRA_SLOT, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(slot == 0 ? INPUT_SLOT : EXTRA_SLOT);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return items.isItemValid(slot == 0 ? INPUT_SLOT : EXTRA_SLOT, stack);
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
