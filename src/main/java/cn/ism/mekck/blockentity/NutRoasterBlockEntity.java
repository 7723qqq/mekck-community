package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.NutRoasterBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.entity.RoastedHazelnutEntity;
import cn.ism.mekck.menu.NutRoasterMenu;
import cn.ism.mekck.recipe.NutRoastingRecipe;
import cn.ism.mekck.recipe.RecipeInputMatcher;
import cn.ism.mekck.util.IceTargetSearch;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.upgrade.UpgradeHelper;
import net.minecraft.core.BlockPos;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IMekanismHeatHandler;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import cn.ism.mekck.registry.MekCkRecipeTypes;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

/**
 * 坚果爆炒机：执行 {@code mekck:nut_roasting} 配方（1 输入 → 1 输出，输入消耗），
 * 并具备与制冰机 / 巧克力大炮类似的攻击能力——从机器自身位置向索敌目标发射炒榛子实体
 * （速度 2 格/tick、无重力、弹射物伤害 20 点），产物格同时是攻击弹药库。
 * 索敌（目标类型 / 半径）与制冰机一致，攻击与加工同受红石控制。
 */
public final class NutRoasterBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, mekanism.api.heat.IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable {
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
    public static final int SLOT_POWER = 5;
    public static final int TOTAL_SLOTS = 6;

    public static final int ENERGY_CAPACITY = 100_000;
    public static final int ENERGY_PER_TICK = 20;
    /** 单次炒制时间：原 100 tick 的 1/3（100/3 ≈ 33）。 */
    public static final int PROCESS_TIME = 33;
    public static final int MAX_RECEIVE = 1_000;
    /** 攻击间隔（tick）：1 秒。 */
    public static final int ATTACK_INTERVAL = 20;
    /** 每轮最多发射的炒榛子总数。 */
    public static final int ATTACK_SHOTS = 5;

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
    /** 机身温度（单位 0.01 ℃）。 */
    public static final int DATA_TEMPERATURE = 10;
    /**
     * {@link #DATA_ENERGY} 的<b>高 16 位</b> —— 能量被拆成两个槽传输。
     *
     * <p>{@code ContainerData} 经 {@code ClientboundContainerSetDataPacket} 时对每个值
     * 只 {@code writeShort}（16 位有符号），而本机容量是 {@link #ENERGY_CAPACITY} = 10 万
     * ⇒ 不拆必然截断成负数。详见 {@link cn.ism.mekck.util.WideDataSlot}。</p>
     *
     * <p>取值 = 旧 {@code DATA_SIZE}，即<b>追加</b>到槽表末尾：现有下标一律不动。</p>
     */
    public static final int DATA_ENERGY_HI = 11;
    /**
     * 侧配编码（6 面 × 4 bit = 24 bit）的高 16 位，追加到槽表末尾：
     * 不拆则 WEST/EAST 两面经 16 位有符号通道后恒为 NONE（见 {@link cn.ism.mekck.util.WideDataSlot}）。
     */
    public static final int DATA_SIDE_CONFIG_HI = 12;
    public static final int DATA_SIZE = 13;

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
    private int attackTimer = 0;
    /** F10：buff 源（juicer）位置；null = 未被增益。服务端权威，客户端经 getUpdatePacket 同步仅用于画线。 */
    @Nullable
    private BlockPos buffOwnerPos;
    /** F10：归属扫描冷却（每 20 tick 重扫一次）。 */
    private int buffScanCooldown = 0;
    /**
     * 待发射炒榛子的目标分配表（目标 → 剩余发数）：
     * 攻击同一目标的炒榛子保持排队（逐 tick 依次发射），攻击不同目标的炒榛子在同一 tick 一起发射。
     */
    private final java.util.LinkedHashMap<LivingEntity, Integer> pendingAttackTargets = new java.util.LinkedHashMap<>();
    /**
     * 索敌结果缓存：创造升级让 {@code attackTimer = 1} ⇒ 每 tick 攻击一次，
     * 而半径 > 64 时 {@code IceTargetSearch} 要遍历全部已加载实体 —— 不缓存就是每 tick 全服扫描。
     * 与 {@code ChocolateCannonBlockEntity} 那份同实现（抽到 {@code IceTargetSearch.CandidateCache}）。
     */
    private final cn.ism.mekck.util.IceTargetSearch.CandidateCache targetCache =
            new cn.ism.mekck.util.IceTargetSearch.CandidateCache();


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
            if (slot == INPUT_SLOT) return !isAnyUpgrade(stack) && RecipeInputMatcher.matchesNutRoasting(level, stack);
            if (slot == OUTPUT_SLOT) return false;
            if (slot == SLOT_SPEED_UPGRADE) return UpgradeHelper.isSpeedUpgrade(stack);
            if (slot == SLOT_ENERGY_UPGRADE) return UpgradeHelper.isEnergyUpgrade(stack);
            if (slot == SLOT_CREATIVE_UPGRADE) return UpgradeHelper.isCreativeUpgrade(stack);
            if (slot == SLOT_POWER) return PowerSlotUtil.isValidEnergyItem(stack);
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot == INPUT_SLOT || slot == OUTPUT_SLOT) return Integer.MAX_VALUE;
            if (slot == SLOT_CREATIVE_UPGRADE) return 1;
            if (slot == SLOT_POWER) return 64;
            return MekckConfig.getBasicSpeedUpgradeMax();
        }

        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            if (slot == INPUT_SLOT || slot == OUTPUT_SLOT || slot == SLOT_CREATIVE_UPGRADE) {
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
                case DATA_SIDE_CONFIG -> encodeSideConfig() & 0xFFFF;
                case DATA_SIDE_CONFIG_HI -> (encodeSideConfig() >>> 16) & 0xFFFF;
                case DATA_SPEED_UPGRADE -> getSpeedUpgradeCount();
                case DATA_ENERGY_UPGRADE -> getEnergyUpgradeCount();
                case DATA_CREATIVE_UPGRADE -> hasCreativeUpgrade() ? 1 : 0;
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
                case DATA_TEMPERATURE -> (int) Math.round((getTemperature() - 273.15) * 100.0);
                case DATA_TARGET_TYPE -> targetType;
                case DATA_RADIUS -> radius;
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

    /** 温度系统：Mekanism 热容量（运行时按电阻型加热器比例产热，并与相邻热力设备传导）。 */
    private cn.ism.mekck.util.MekCkHeatComponent heatComponent;
    private net.minecraftforge.common.util.LazyOptional<mekanism.api.heat.IHeatHandler> heatCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> heatComponent.getHandler());

        /** 当前机身温度（开尔文）。 */
    public double getTemperature() {
        return heatComponent == null ? mekanism.api.heat.HeatAPI.AMBIENT_TEMP : heatComponent.getTemperature();
    }

public NutRoasterBlockEntity(BlockPos pos, BlockState state) {
        super(MekCkStandaloneMachines.NUT_ROASTER_BLOCK_ENTITY.get(), pos, state);
        this.heatComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
        for (int i = 0; i < 6; i++) sideConfig[i] = SideMode.NONE;
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);
    }

    public static boolean isAnyUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (cn.ism.mekck.item.ColdBrewUpgradeItem.getTier(stack) != null) return true;
        if (cn.ism.mekck.item.FerreroUpgradeItem.getTier(stack) != null) return true;
        return UpgradeHelper.isUpgrade(stack);
    }

    public int getSpeedUpgradeCount() {
        return items.getStackInSlot(SLOT_SPEED_UPGRADE).getCount();
    }

    public int getEnergyUpgradeCount() {
        return items.getStackInSlot(SLOT_ENERGY_UPGRADE).getCount();
    }

    public boolean hasCreativeUpgrade() {
        return !items.getStackInSlot(SLOT_CREATIVE_UPGRADE).isEmpty();
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
    public void setRemoved() {
        super.setRemoved();
        cn.ism.mekck.buff.BuffLinkIndex.remove(worldPosition);
        // AE2 网格节点销毁（未安装 AE2 时为空操作；节点 NBT 由 saveAdditional 保存，重载后 init 重建）
        cn.ism.mekck.compat.AE2Compat.onRemoved(this);
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
                cn.ism.mekck.recipe.RecipeCache.type(ResourceLocation.tryParse("mekck:nut_roasting"));
        if (type == null) return out;
        for (net.minecraft.world.item.crafting.Recipe<?> r : cn.ism.mekck.recipe.RecipeCache.all(level, type)) {
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
    public List<cn.ism.mekck.ae2.AE2InputSpec> getNetworkPullInputs() {
        ItemStack slot0 = items.getStackInSlot(INPUT_SLOT);
        if (!slot0.isEmpty()) {
            return List.of(new cn.ism.mekck.ae2.AE2InputSpec(net.minecraft.world.item.crafting.Ingredient.of(slot0.getItem())));
        }
        net.minecraft.world.item.crafting.Ingredient union = cn.ism.mekck.recipe.RecipeInputMatcher.unionFirstIngredients(
                level, ResourceLocation.fromNamespaceAndPath("mekck", "nut_roasting"));
        return union.isEmpty() ? List.of() : List.of(new cn.ism.mekck.ae2.AE2InputSpec(union));
    }

    public ContainerData getData() {
        return data;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        return customName != null ? customName : Component.translatable("block.mekck.nut_roaster");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new NutRoasterMenu(containerId, inventory, this, data);
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
    public static void clientTick(Level level, BlockPos pos, BlockState state, NutRoasterBlockEntity machine) {
        // 客户端无需额外逻辑
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, NutRoasterBlockEntity machine) {
        // 温度系统：每 tick 自然回归环境并与相邻 Mekanism 热力设备传导
        machine.heatComponent.tick(level, pos);
        machine.updateRedstone();
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.compat.AE2Compat.serverTick(machine, level, pos);

        // F10 攻击增益：每 20 tick 解析一次 buff 源归属（nut_roaster ← juicer）
        if (machine.buffScanCooldown-- <= 0) {
            machine.buffScanCooldown = 20;
            machine.refreshBuffOwner(level);
        }

        if (machine.drainPowerSlot()) machine.setChanged();

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

        NutRoastingRecipe recipe = machine.getRecipe(level);
        boolean canWork = machine.canFunctionRedstone() && recipe != null
                && machine.canInsertOutput(recipe.getResultItem(level.registryAccess()));

        if (canWork) {
            boolean hasEnergy = hasCreative || machine.energy.getEnergyStored() >= energyPerTick;
            if (hasEnergy) {
                if (machine.redstoneControl == RedstoneControl.PULSE) {
                    machine.pulseRunning = false;
                }
                if (!hasCreative) machine.energy.extractEnergy(energyPerTick, false);
                // 温度系统：按消耗电能产热（与电阻型加热器比例完全相同：1 FE → 0.6 J 热量）
                machine.heatComponent.addHeatFromEnergy(energyPerTick);
                machine.progress++;
                if (machine.progress >= effectiveProcessTime) {
                    machine.items.extractItem(INPUT_SLOT, 1, false);
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

        // 攻击行为与加工一样受红石控制：暂停时索敌与发射队列冻结
        if (machine.canFunctionRedstone()) {
            machine.spawnPendingAttack(level);
            machine.performAttack(level);
        }

        BlockState newState = state.setValue(NutRoasterBlock.ACTIVE, machine.progress > 0);
        if (newState != state) level.setBlock(pos, newState, 3);
    }

    /** 复用的配方包装器（原先每次配方查找都 new 一个）。 */
    private RecipeWrapper cachedRecipeWrapper;

    private RecipeWrapper recipeWrapper() {
        if (cachedRecipeWrapper == null) cachedRecipeWrapper = new RecipeWrapper(items);
        return cachedRecipeWrapper;
    }

    private NutRoastingRecipe getRecipe(Level level) {
        if (level == null) return null;
        var manager = level.getRecipeManager();
        var opts = recipeWrapper();
        var holder = manager.getRecipeFor(MekCkRecipeTypes.NUT_ROASTING_RECIPE_TYPE.get(), opts, level);
        NutRoastingRecipe found = holder.orElse(null);
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

    /** 机器内部产物写入：直接 set/grow 产物格（insertItem 会被产物槽的 isItemValid=false 拦截，见 14.14 陷阱记录）。 */
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
        // 上一轮炒榛子尚未全部发射时不启动新一轮（攻击计时器保持）
        if (!pendingAttackTargets.isEmpty()) return;

        // 必须消耗产物格炒榛子才能攻击：无产物时不攻击（计时器保持，产物就绪后立即恢复）；创造升级=无限弹药
        boolean hasCreative = hasCreativeUpgrade();
        ItemStack output = items.getStackInSlot(OUTPUT_SLOT);
        if (!hasCreative && output.isEmpty()) return;

        attackTimer--;
        if (attackTimer > 0) return;
        // 创造升级额外效果：攻速变为每 tick 攻击一轮；F10：被 juicer 增益时攻速 +20%（interval ×0.8）
        attackTimer = hasCreative ? 1 : buffedInterval(ATTACK_INTERVAL);

        // 索敌：与制冰机一致（小半径 AABB 快路径，超大半径遍历已加载实体）
        List<LivingEntity> candidates = targetCache.get(level, worldPosition, this.radius, this.targetType, this::matchesTarget);
        if (candidates.isEmpty()) return;

        // 每轮最多发射 ATTACK_SHOTS 颗：按最近目标顺序逐个分配，每个目标的发数按其生命值决定——
        // 目标生命值低于 x*单发伤害 时最多发射 x 颗（x = floor(生命值/单发伤害) + 1，足以击杀）；
        // 分配总数到达上限后不再给后续目标分配
        float perNutDamage = RoastedHazelnutEntity.DAMAGE;
        java.util.List<LivingEntity> targets = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size() && targets.size() < ATTACK_SHOTS; i++) {
            LivingEntity t = candidates.get(i);
            int x = (int) (t.getHealth() / perNutDamage) + 1;
            for (int j = 0; j < x && targets.size() < ATTACK_SHOTS; j++) {
                targets.add(t);
            }
        }

        // 弹药数量限制本波发数（每发消耗 1 个炒榛子；创造升级不限）
        int budget = hasCreative ? Integer.MAX_VALUE : output.getCount();
        if (targets.size() > budget) targets = targets.subList(0, budget);
        if (targets.isEmpty()) return;

        // 按目标聚合本轮发数：同一目标的多枚炒榛子在队列中排队（逐 tick 依次发射），不同目标并列入队（同一 tick 一起发射）
        pendingAttackTargets.clear();
        for (LivingEntity t : targets) {
            pendingAttackTargets.merge(t, 1, Integer::sum);
        }
    }

    /**
     * 逐 tick 发射：每个 tick 对「仍有剩余发数」的每个目标各发射 1 枚炒榛子——
     * 不同目标同一 tick 一起发射；同一目标的多枚炒榛子按队列逐 tick 依次发射。每发消耗 1 个弹药。
     */
    /** 每 tick 最多生成的弹射物数量（余下排队，避免瞬时实体/特效峰值）。 */
    private static final int MAX_SPAWNS_PER_TICK = 8;

    private void spawnPendingAttack(Level level) {
        if (pendingAttackTargets.isEmpty()) return;
        boolean hasCreative = hasCreativeUpgrade();
        boolean markedDirty = false;
        int spawnedThisTick = 0;
        var it = pendingAttackTargets.entrySet().iterator();
        while (it.hasNext()) {
            if (spawnedThisTick >= MAX_SPAWNS_PER_TICK) return; // 本 tick 配额用尽
            var entry = it.next();
            LivingEntity target = entry.getKey();
            if (!target.isAlive() || target.isRemoved()) {
                it.remove(); // 目标已消失：跳过，不消耗弹药
                continue;
            }
            if (!hasCreative && items.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
                pendingAttackTargets.clear(); // 产物耗尽：终止本轮剩余发射（创造升级不消耗）
                return;
            }
            // 从机器中心向目标发射炒榛子：存活上限 tick = 射程 / 2（速度 2 格/tick，恰好飞满射程）
            Vec3 from = new Vec3(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.75D, worldPosition.getZ() + 0.5D);
            RoastedHazelnutEntity.spawn(level, from, target, RoastedHazelnutEntity.DAMAGE, Math.max(1, this.radius / 2), worldPosition);
            spawnedThisTick++;
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.POOF, from.x, from.y, from.z, 4, 0.15, 0.15, 0.15, 0.01);
            }
            if (!hasCreative) {
                items.extractItem(OUTPUT_SLOT, 1, false);
                markedDirty = true; // 本 tick 统一置脏一次
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
        if (heatComponent != null) tag.put("HeatCapacitor", heatComponent.save());
        cn.ism.mekck.compat.AE2Compat.saveAdditional(this, tag);
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
        if (heatComponent != null && tag.contains("HeatCapacitor", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            heatComponent.load(tag.getCompound("HeatCapacitor"));
        }
        cn.ism.mekck.compat.AE2Compat.load(this, tag);
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
        int remainingEnergy = tag.getInt("Energy");
        while (remainingEnergy > 0) {
            int received = energy.receiveEnergy(remainingEnergy, false);
            if (received == 0) break;
            remainingEnergy -= received;
        }
        progress = tag.getInt("Progress");
        if (tag.contains("OrderRecipeId")) {
            // 存档里的 id 可能是坏数据（旧版、手改、跨模组迁移）：旧构造器在这里抛异常会让整台机器
            // 加载失败进而丢存档。改成解析失败就当作「无订单」，机器照常工作。
            orderRecipeId = net.minecraft.resources.ResourceLocation.tryParse(tag.getString("OrderRecipeId"));
            if (orderRecipeId != null) {
                orderQuantity = tag.getInt("OrderQuantity");
                orderCompleted = tag.getInt("OrderCompleted");
            }
        }
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
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
        buffOwnerPos = tag.contains("BuffOwnerPos") ? BlockPos.of(tag.getLong("BuffOwnerPos")) : null;
    }

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) return energyCapability.cast();
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
        heatCapability = LazyOptional.of(() -> heatComponent.getHandler());
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
