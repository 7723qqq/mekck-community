package cn.ism.mekck.machine.roasting;

import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.entity.RoastedHazelnutEntity;
import cn.ism.mekck.machine.MekCkNetworkPullableTile;
import cn.ism.mekck.machine.MekCkOrderState;
import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.machine.MekCkSlotHandler;
import cn.ism.mekck.menu.slot.MekCkSlots;
import cn.ism.mekck.menu.slot.SlotDef;
import cn.ism.mekck.recipe.NutRoastingRecipe;
import cn.ism.mekck.recipe.RecipeInputMatcher;
import cn.ism.mekck.recipe.RecipeRequiredInputs;
import cn.ism.mekck.registry.MekCkRecipeTypes;
import cn.ism.mekck.upgrade.UpgradeHelper;
import cn.ism.mekck.util.IceTargetSearch;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.FloatingLong;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.heat.BasicHeatCapacitor;
import mekanism.common.capabilities.heat.CachedAmbientTemperature;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.heat.HeatCapacitorHelper;
import mekanism.common.capabilities.holder.heat.IHeatCapacitorHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.interfaces.IRedstoneControl;
import mekanism.common.tile.interfaces.ISustainedData;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * 坚果爆炒机（Mek 体系版）—— 执行 {@code mekck:nut_roasting}（1 输入 → 1 输出，输入消耗），
 * 并具备射击能力：从机身向索敌目标发射炒榛子实体（产物格同时是弹药库）。
 *
 * <h3>与旧 {@code NutRoasterBlockEntity} 的关系：只换能力层，玩法逐条照搬</h3>
 * 配方匹配、进度、热系统（耗电转废热）、创造升级（免电 / 1 tick / 无限弹药 / 每 tick 开火）、
 * 索敌（{@code TargetType} / {@code Radius}）与射击队列（{@code pendingAttackTargets} /
 * {@code MAX_SPAWNS_PER_TICK}）、F10 增益归属（buff 源连线）、订单与 ME 下单，
 * 全部与迁移前逐字同义。换掉的只是机器能力层：侧配 / 升级 / 红石 / 能量 / 弹出 / 槽位
 * 由 Mek 基类提供，本类不再自建 {@code ItemStackHandler} / {@code ContainerData} /
 * {@code EnergyStorage} / {@code SideMode}。
 *
 * <h3>为什么继承 {@link MekCkNetworkPullableTile} 而不是 {@code MekCkMachineTile}</h3>
 * 后者是<b>带档位的工厂家族</b>基类（{@code createExecutor()} 抽象）。本机是无档位单机，
 * 需要的是「单机基类」提供的那两样：AE2 网格节点四件套生命周期 + {@code INetworkPullable}。
 *
 * <h3>槽位顺序是存档契约</h3>
 * {@code [输入, 输出, 创造升级, 能源]} —— 与迁移前菜单的 addSlot 顺序一致，且与
 * {@link MekCkSlots.NutRoaster} 的槽位表同源。速度 / 能量卡不再占物品槽：
 * 它们由 Mek 的 {@code TileComponentUpgrade} 持有（升级 tab 的 20 tick 安装读条）。
 */
public final class NutRoasterTile extends MekCkNetworkPullableTile
        implements MenuProvider, ISustainedData {

    // ── 槽位下标（= 本机槽位列表里的位置，不是菜单下标）──────────────────

    /** 输入槽（坚果）。 */
    public static final int INPUT_SLOT = 0;
    /** 输出槽（炒榛子）——同时是攻击弹药库。 */
    public static final int OUTPUT_SLOT = 1;
    /** 创造升级槽（额外槽，Mek 的升级体系里没有这个概念）。 */
    public static final int CREATIVE_SLOT = 2;
    /** 能源槽（能量物品）。 */
    public static final int POWER_SLOT = 3;
    /** 槽位总数。 */
    public static final int TOTAL_SLOTS = 4;

    // ── 机器参数（逐字沿用迁移前的值）────────────────────────────────────

    /** 能量容量（FE）。由方块的 {@code AttributeEnergy} 声明，{@code MachineEnergyContainer} 构造时读它。 */
    public static final int ENERGY_CAPACITY = 100_000;
    /** 基础能耗（FE/tick）。同上，是声明值；真实扣电量见 {@link #energyPerTick()}。 */
    public static final int ENERGY_PER_TICK = 20;
    /** 单次炒制时间：原 100 tick 的 1/3（100/3 ≈ 33）。 */
    public static final int PROCESS_TIME = 33;
    /** 单次接收上限（FE）。 */
    public static final int MAX_RECEIVE = 1_000;
    /** 攻击间隔（tick）：1 秒。 */
    public static final int ATTACK_INTERVAL = 20;
    /** 每轮最多发射的炒榛子总数。 */
    public static final int ATTACK_SHOTS = 5;
    /** 每 tick 最多生成的弹射物数量（余下排队，避免瞬时实体/特效峰值）。 */
    private static final int MAX_SPAWNS_PER_TICK = 8;

    /** 目标类型：0=敌对生物（配置文件敌对列表），1=全部生物，2=非敌对生物（动物）。 */
    public static final int TARGET_HOSTILE = 0;
    public static final int TARGET_ALL = 1;
    public static final int TARGET_ANIMAL = 2;

    /**
     * 本机处理的配方类型注册名。
     *
     * <p>两处消费方：空输入槽时「网络拉料该拉什么」的并集，以及 ME 终端样板的配方来源
     * （{@code MekckAe2} 的单机分支）。取错了会让抽出来的料被 {@code isItemValid} 拒收。</p>
     */
    private static final ResourceLocation NUT_ROASTING_TYPE_ID =
            ResourceLocation.fromNamespaceAndPath("mekck", "nut_roasting");

    // ── 槽位对象（只能在 getInitialInventory 里 new，构造期陷阱见 MekCkMachineTile）──

    private MekCkSlot inputSlot;
    private MekCkSlot outputSlot;
    private MekCkSlot creativeSlot;
    private EnergyInventorySlot energySlot;
    private MachineEnergyContainer<NutRoasterTile> energyContainer;
    private BasicHeatCapacitor heatCapacitor;

    /**
     * AE2 侧看到的槽位视图（输入 + 输出，下标与迁移前的 {@code ItemStackHandler} 一致）。
     *
     * <p>与 {@code MekCkNetworkPullableTile} 的拉料视图分开：那条路的视图只有输入槽
     * （拉料只往输入槽插），而 ME 终端下单那条路要按「输入 0 / 输出 1」的下标回写产物。</p>
     */
    private ItemStackHandler ae2View;

    // ── 运行态 ──────────────────────────────────────────────────────────

    private final MekCkOrderState order = new MekCkOrderState();
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true;
    private int progress;
    private int attackTimer;
    private final LinkedHashMap<LivingEntity, Integer> pendingAttackTargets = new LinkedHashMap<>();
    private final IceTargetSearch.CandidateCache targetCache = new IceTargetSearch.CandidateCache();

    private int targetType = TARGET_HOSTILE;
    private int radius = MekckConfig.getIceAttackRadius();

    /** F10：buff 源（juicer）位置；null = 未被增益。服务端权威，客户端经热更新包同步仅用于画线。 */
    @Nullable
    private BlockPos buffOwnerPos;
    /** F10：归属扫描冷却（每 20 tick 重扫一次）。 */
    private int buffScanCooldown;

    /**
     * PULSE 锁存：收到红石上升沿后一直放行，直到跑完一个完整批次。
     *
     * <p>{@code MekanismUtils.canFunction} 对 PULSE 的实现是
     * {@code isPowered() && !wasPowered()}（上升沿那一 tick 放行、其余全禁），
     * 而本模组的旧语义是<b>锁存</b>的。不锁存的话一次脉冲只能推进 1/33 的进度条。</p>
     */
    private boolean pulseLatched;

    // ── 客户端镜像（服务端算、随容器同步通道下发）────────────────────────

    private int clientProgress;
    private int clientTargetType;
    private int clientRadius;
    private int clientCycle;
    private boolean clientMeOrderEnabled = true;

    public NutRoasterTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
        // 侧配内容必须在 super(...) 之后、且只能在这里登记：
        // setupItemIOConfig 依赖 getInitialInventory 建好的槽位对象，
        // setupInputConfig 依赖 getInitialEnergyContainers 建好的能量容器。
        configComponent.setupItemIOConfig(List.of(inputSlot), List.of(outputSlot), energySlot, false);
        configComponent.setupInputConfig(TransmissionType.ENERGY, energyContainer);
        // 只给 ITEM 挂弹出：ENERGY 侧的 ConfigInfo 已被 setupInputConfig 置为 setCanEject(false)。
        ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM);
    }

    // ==================== 构造期钩子 ====================

    @Override
    protected void presetVariables() {
        configComponent = new TileComponentConfig(this, TransmissionType.ITEM, TransmissionType.ENERGY);
        ejectorComponent = new TileComponentEjector(this);
    }

    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSideWithConfig(this::getDirection, this::getConfig);
        energyContainer = MachineEnergyContainer.input(this, listener);
        builder.addContainer(energyContainer);
        return builder.build();
    }

    /**
     * 槽位装配 —— 坐标取 {@link MekCkSlots.NutRoaster}，本类不写坐标字面量。
     *
     * <p><b>顺序即存档契约</b>：输入 → 输出 → 创造升级 → 能源。</p>
     */
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this::getDirection, this::getConfig);

        SlotDef in = MekCkSlots.NutRoaster.INPUT;
        SlotDef out = MekCkSlots.NutRoaster.OUTPUT;
        SlotDef creative = MekCkSlots.NutRoaster.CREATIVE;
        SlotDef power = MekCkSlots.NutRoaster.POWER;

        // 单槽容量 Integer.MAX_VALUE（本模组的大堆叠行为），与迁移前的 getSlotLimit 同值。
        // 准入沿用迁移前的 isItemValid：非升级物品 + 是 nut_roasting 配方原料。
        inputSlot = MekCkSlot.inputFiltered(Integer.MAX_VALUE,
                (stack, automation) -> !isAnyUpgrade(stack)
                        && RecipeInputMatcher.matchesNutRoasting(getLevel(), stack),
                listener, in.x(), in.y());
        builder.addSlot(inputSlot);

        outputSlot = MekCkSlot.output(Integer.MAX_VALUE, listener, out.x(), out.y());
        builder.addSlot(outputSlot);

        // 创造升级：Mek 的升级体系里没有这个概念（它由额外槽承载），因此不进 TileComponentUpgrade。
        creativeSlot = MekCkSlot.inputFiltered(1,
                (stack, automation) -> UpgradeHelper.isCreativeUpgrade(stack),
                listener, creative.x(), creative.y());
        builder.addSlot(creativeSlot);

        energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener,
                power.x(), power.y());
        builder.addSlot(energySlot);

        ae2View = new MekCkSlotHandler(List.of(inputSlot, outputSlot));
        return builder.build();
    }

    /**
     * 本机热容 —— 参数与 {@code MekCkHeatComponent} 同组（热容量 100 J/K、逆传导 5.0、逆绝缘 100.0，
     * 与 Mekanism 电阻型加热器一致）。
     *
     * <p>{@code TileEntityMekanism} 在构造器里无条件回调本钩子，并把电容温度挂进容器追踪，
     * 因此 GUI 读到的温度是同步值；环境回归与相邻热容器交换由基类的
     * {@code updateHeatCapacitors} 负责，本类不再自己 tick 热系统。</p>
     */
    @Override
    protected IHeatCapacitorHolder getInitialHeatCapacitors(IContentsListener listener,
                                                            CachedAmbientTemperature ambient) {
        HeatCapacitorHelper builder = HeatCapacitorHelper.forSideWithConfig(this::getDirection, this::getConfig);
        heatCapacitor = BasicHeatCapacitor.create(
                cn.ism.mekck.util.MekCkHeatComponent.HEAT_CAPACITY,
                INVERSE_CONDUCTION,
                INVERSE_INSULATION,
                ambient,
                listener::onContentsChanged);
        builder.addCapacitor(heatCapacitor);
        return builder.build();
    }

    /** 与 {@code MekCkHeatComponent} 同组的热学参数。 */
    private static final double INVERSE_CONDUCTION = 5.0;
    /** 与 {@code MekCkHeatComponent} 同组的热学参数。 */
    private static final double INVERSE_INSULATION = 100.0;

    // ==================== AE2 网络拉料 ====================

    /** 拉料只看输入槽（产物 / 创造升级 / 能源槽不参与）。 */
    @Override
    protected List<IInventorySlot> networkPullSlots() {
        return inputSlot == null ? List.of() : List.of(inputSlot);
    }

    /** 空输入槽时的补料并集必须与本机实际处理的配方类型一致（取错了料会被拒收、掉在机器旁）。 */
    @Override
    protected List<String> networkPullRecipeTypeIds() {
        return List.of(NUT_ROASTING_TYPE_ID.toString());
    }

    /** AE2 侧看到的槽位视图（输入 0 / 输出 1）—— {@code MekckAe2} 的投料与产物回写走它。 */
    public ItemStackHandler getItems() {
        return ae2View;
    }

    // ==================== 每 tick ====================

    /**
     * 服务端 tick。
     *
     * <p>AE2 网格节点四件套生命周期由 {@link MekCkNetworkPullableTile#onUpdateServer()}
     * 统一收口（super 调用即完成 serverTick 接线）。</p>
     */
    @Override
    protected void onUpdateServer() {
        super.onUpdateServer();
        Level level = getLevel();
        if (level == null) {
            return;
        }

        // 能源槽补能（Mek 自己的 TileEntityElectricMachine.onUpdateServer 第一句也是它）。
        if (energySlot != null) {
            energySlot.fillContainerOrConvert();
        }
        // 创造升级「能量恒满」：旧 serverTick 里它排在红石判定与「有没有活干」之前，
        // 不带任何其它条件 ⇒ 机器停机、没放料、红石禁用时照样每 tick 补满。
        if (hasCreativeUpgrade()) {
            refillEnergy();
        }

        // F10 攻击增益：每 20 tick 解析一次 buff 源归属（nut_roaster ← juicer）。
        if (buffScanCooldown-- <= 0) {
            buffScanCooldown = 20;
            refreshBuffOwner(level);
        }

        workCycle(level);

        // 攻击行为与加工一样受红石控制：暂停时索敌与发射队列冻结。
        if (allowsWork()) {
            spawnPendingAttack(level);
            performAttack(level);
        }
    }

    /**
     * 闸门 + 进度 + 配方执行 —— 旧 {@code serverTick} 的「红石 → 能量 → 干活」三段式。
     *
     * <p>与旧实现逐条对应：红石闸门（PULSE 走锁存）→ 有配方且产物装得下 → 电够 →
     * 扣电 → 耗电转废热 → 进度 +1 → 满一个批次才落产物并推进订单。</p>
     */
    private void workCycle(Level level) {
        boolean creative = hasCreativeUpgrade();
        // 创造升级：批次长度整个换成 1 tick、能耗整个置 0（不是「乘 0」）。
        int cycle = creative ? 1 : getEffectiveProcessTime();
        int cost = creative ? 0 : energyPerTick();

        Recipe<?> recipe = findRecipe(level);
        boolean canProcess = recipe != null && canFitOutput(recipe.getResultItem(level.registryAccess()));

        boolean allowed = allowsWork() && canProcess;
        boolean hasEnergy = cost <= 0
                || energyContainer.getEnergy().compareTo(FloatingLong.create(cost)) >= 0;

        if (!allowed || !hasEnergy) {
            if (progress != 0) {
                progress = 0;
                setChanged();
            }
            // PULSE：本 tick 无法运行则解除锁存，等待下一次红石信号重新触发。
            if (pulseLatched) {
                pulseLatched = false;
                setChanged();
            }
            setActive(false);
            return;
        }

        if (cost > 0) {
            // AutomationType 必须是 MANUAL 而不是 EXTERNAL：MachineEnergyContainer.input(...)
            // 把 notExternal 传给了 canExtract，传 EXTERNAL 会被整条拒掉、一 FE 都不扣。
            energyContainer.extract(FloatingLong.create(cost), Action.EXECUTE, AutomationType.MANUAL);
            addHeatFromEnergy(cost);
        }
        progress++;
        if (progress >= cycle) {
            completeRecipe(level, recipe);
            progress = 0;
            // PULSE：跑完一整个批次才解除锁存。
            pulseLatched = false;
        }
        setActive(progress > 0);
        setChanged();
    }

    /**
     * 本 tick 是否允许推进工作 / 开火。
     *
     * <p>DISABLED / HIGH / LOW 三档直接用 Mek 自己的 {@link MekanismUtils#canFunction}
     * （与旧的 {@code cn.ism.mekck.RedstoneControl.canFunction} 逐档一致）；
     * PULSE 单独走 {@link #pulseLatched}。</p>
     */
    private boolean allowsWork() {
        if (getControlType() == IRedstoneControl.RedstoneControl.PULSE) {
            if (pulseLatched) {
                return true;
            }
            if (isPowered() && !wasPowered()) {
                pulseLatched = true;
                return true;
            }
            return false;
        }
        return MekanismUtils.canFunction(this);
    }

    /** 把能量容器补满（创造升级的「能量恒满」）。 */
    private void refillEnergy() {
        FloatingLong max = energyContainer.getMaxEnergy();
        FloatingLong stored = energyContainer.getEnergy();
        if (max.compareTo(stored) <= 0) {
            return;
        }
        energyContainer.insert(max.subtract(stored), Action.EXECUTE, AutomationType.MANUAL);
    }

    /**
     * 把本 tick 耗掉的电按发电效率转成废热。
     *
     * <p>与迁移前逐字同款：{@code heatComponent.addHeatFromEnergy(energyPerTick)}，
     * 而它内部按 {@code HEAT_EFFICIENCY = 0.6} 折算 —— 这里显式用同一个常数，避免两处漂移。</p>
     */
    private void addHeatFromEnergy(int energyUsed) {
        if (energyUsed > 0 && heatCapacitor != null) {
            heatCapacitor.handleHeat(energyUsed * cn.ism.mekck.util.MekCkHeatComponent.HEAT_EFFICIENCY);
        }
    }

    /** 当前机身温度（开尔文）。客户端读到的是 Mek 同步下来的电容温度。 */
    public double getTemperature() {
        return heatCapacitor == null ? mekanism.api.heat.HeatAPI.AMBIENT_TEMP : heatCapacitor.getTemperature();
    }

    // ==================== 升级 ====================

    private int installedUpgrades(Upgrade type) {
        var component = getComponent();
        return component == null ? 0 : component.getUpgrades(type);
    }

    public double getEffectiveSpeedMultiplier() {
        return UpgradeHelper.speedMultiplier(installedUpgrades(Upgrade.SPEED));
    }

    public double getEffectiveEnergyConsumptionMultiplier() {
        return UpgradeHelper.energyConsumptionMultiplier(installedUpgrades(Upgrade.ENERGY));
    }

    /** 实际生效的批次长度（tick）—— 旧实现的 PROCESS_TIME / 速度倍率。 */
    public int getEffectiveProcessTime() {
        return Math.max(1, (int) (PROCESS_TIME / getEffectiveSpeedMultiplier()));
    }

    /** 本 tick 的真实扣电量（FE）—— 速度倍率平方 × 能量卡倍率，与旧实现同式。 */
    private int energyPerTick() {
        double speedMult = getEffectiveSpeedMultiplier();
        return (int) Math.ceil(ENERGY_PER_TICK * speedMult * speedMult
                * getEffectiveEnergyConsumptionMultiplier());
    }

    /** 创造升级是否已装（额外槽里有没有卡）。 */
    public boolean hasCreativeUpgrade() {
        return creativeSlot != null && !creativeSlot.getStack().isEmpty();
    }

    /** 该物品是否是「任何升级模块」—— 输入槽据此拒绝升级卡（它们有自己该去的地方）。 */
    private static boolean isAnyUpgrade(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (cn.ism.mekck.item.ColdBrewUpgradeItem.getTier(stack) != null) {
            return true;
        }
        if (cn.ism.mekck.item.FerreroUpgradeItem.getTier(stack) != null) {
            return true;
        }
        return UpgradeHelper.isUpgrade(stack);
    }

    // ==================== 索敌与攻击 ====================

    public int getRadius() {
        return clientMirroring() ? clientRadius : radius;
    }

    public int getTargetType() {
        return clientMirroring() ? clientTargetType : targetType;
    }

    public void setTargetType(int type) {
        // 与半径同一道闸：值来自网络包，越界值虽被 matchesTarget 的 default 兜底不会崩，
        // 但会写进存档并让 GUI 显示与实际行为不一致。见 IceTargetSearch#clampTargetType。
        this.targetType = IceTargetSearch.clampTargetType(type);
        targetCache.invalidate();
        setChanged();
    }

    public void adjustRadius(int delta) {
        // 上下限都过 IceTargetSearch.clampAttackRadius：半径来自网络包且原本无上限，
        // 大到一定程度会退化成「每 tick 遍历全服实体」（见 IceTargetSearch 的类级说明）。
        // delta 走 long 再收窄，避免 radius + delta 在 MAX_VALUE 处回绕成负。
        applyRadius(IceTargetSearch.clampAttackRadius(
                (int) Math.min((long) IceTargetSearch.MAX_ATTACK_RADIUS, (long) this.radius + delta)));
    }

    public void setRadius(int r) {
        applyRadius(IceTargetSearch.clampAttackRadius(r));
    }

    private void applyRadius(int clamped) {
        this.radius = clamped;
        targetCache.invalidate();
        setChanged();
    }

    /** 目标是否命中本机的类型筛选。 */
    private boolean matchesTarget(LivingEntity e) {
        boolean hostile = MekckConfig.isIceAttackHostile(e.getType());
        return switch (targetType) {
            case TARGET_ALL -> true;
            case TARGET_ANIMAL -> !hostile;
            default -> hostile;
        };
    }

    /**
     * 索敌并生成待发射队列。
     *
     * <p>与迁移前逐字同款：上一轮未发完不启动新一轮；无弹药（且非创造升级）不启动；
     * 每轮最多 {@link #ATTACK_SHOTS} 颗，按最近目标顺序分配，每个目标的发数按其生命值决定。</p>
     */
    private void performAttack(Level level) {
        if (!pendingAttackTargets.isEmpty()) {
            return;
        }
        boolean creative = hasCreativeUpgrade();
        ItemStack output = outputSlot.getStack();
        if (!creative && output.isEmpty()) {
            return;
        }
        attackTimer--;
        if (attackTimer > 0) {
            return;
        }
        // 创造升级额外效果：攻速变为每 tick 攻击一轮；F10：被 juicer 增益时攻速 +20%（interval ×0.8）。
        attackTimer = creative ? 1 : buffedInterval(ATTACK_INTERVAL);

        List<LivingEntity> candidates = targetCache.get(level, worldPosition, radius, targetType, this::matchesTarget);
        if (candidates.isEmpty()) {
            return;
        }

        // 目标生命值低于 x*单发伤害 时最多发射 x 颗（x = floor(生命值/单发伤害) + 1，足以击杀）。
        float perNutDamage = RoastedHazelnutEntity.DAMAGE;
        List<LivingEntity> targets = new ArrayList<>();
        for (int i = 0; i < candidates.size() && targets.size() < ATTACK_SHOTS; i++) {
            LivingEntity t = candidates.get(i);
            int x = (int) (t.getHealth() / perNutDamage) + 1;
            for (int j = 0; j < x && targets.size() < ATTACK_SHOTS; j++) {
                targets.add(t);
            }
        }

        // 弹药数量限制本波发数（每发消耗 1 个炒榛子；创造升级不限）。
        int budget = creative ? Integer.MAX_VALUE : output.getCount();
        if (targets.size() > budget) {
            targets = targets.subList(0, budget);
        }
        if (targets.isEmpty()) {
            return;
        }

        // 同一目标的多枚炒榛子在队列中排队（逐 tick 依次发射），不同目标并列入队（同一 tick 一起发射）。
        pendingAttackTargets.clear();
        for (LivingEntity t : targets) {
            pendingAttackTargets.merge(t, 1, Integer::sum);
        }
    }

    /** 逐 tick 发射：每个 tick 对「仍有剩余发数」的每个目标各发射 1 枚，每发消耗 1 个弹药。 */
    private void spawnPendingAttack(Level level) {
        if (pendingAttackTargets.isEmpty()) {
            return;
        }
        boolean creative = hasCreativeUpgrade();
        boolean markedDirty = false;
        int spawnedThisTick = 0;
        var it = pendingAttackTargets.entrySet().iterator();
        while (it.hasNext()) {
            if (spawnedThisTick >= MAX_SPAWNS_PER_TICK) {
                return; // 本 tick 配额用尽
            }
            var entry = it.next();
            LivingEntity target = entry.getKey();
            if (!target.isAlive() || target.isRemoved()) {
                it.remove(); // 目标已消失：跳过，不消耗弹药
                continue;
            }
            if (!creative && outputSlot.getStack().isEmpty()) {
                pendingAttackTargets.clear(); // 产物耗尽：终止本轮剩余发射
                return;
            }
            // 从机器中心向目标发射炒榛子：存活上限 tick = 射程 / 2（速度 2 格/tick，恰好飞满射程）。
            Vec3 from = new Vec3(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.75D,
                    worldPosition.getZ() + 0.5D);
            RoastedHazelnutEntity.spawn(level, from, target, RoastedHazelnutEntity.DAMAGE,
                    Math.max(1, this.radius / 2), worldPosition);
            spawnedThisTick++;
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.POOF, from.x, from.y, from.z, 4, 0.15, 0.15, 0.15, 0.01);
            }
            if (!creative) {
                outputSlot.extractItem(1, Action.EXECUTE, AutomationType.INTERNAL);
                markedDirty = true;
            }
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
        if (markedDirty) {
            setChanged();
        }
    }

    /** buff 生效时的攻击间隔（Q2a：base × 0.8，四舍五入且至少 1）。 */
    private int buffedInterval(int base) {
        return buffOwnerPos == null ? base : Math.max(1, Math.round(base * 0.8f));
    }

    /** 是否正被 buff 源增益（供查询 / 客户端画线）。 */
    public boolean isBuffed() {
        return buffOwnerPos != null;
    }

    /** 服务端周期性解析 buff 源归属：创造升级下禁用且不连线（Q6b）；否则取范围内最近的合法源。 */
    private void refreshBuffOwner(Level level) {
        if (level.isClientSide) {
            return;
        }
        if (hasCreativeUpgrade()) {
            setBuffOwner(null);
            return;
        }
        var source = cn.ism.mekck.buff.MekckBuffRegistry.sourceFor(getBlockState().getBlock());
        BlockPos np = source == null ? null
                : cn.ism.mekck.buff.MekckBuffRegistry.resolve(level, worldPosition, source, buffOwnerPos);
        setBuffOwner(np);
    }

    private void setBuffOwner(@Nullable BlockPos np) {
        if (java.util.Objects.equals(np, buffOwnerPos)) {
            return;
        }
        buffOwnerPos = np;
        setChanged();
        if (level != null && !level.isClientSide) {
            // 走 Mek 自己的热更新包通道（PacketUpdateTile 携带 getReducedUpdateTag 的结果），
            // 与旧的 level.sendBlockUpdated + ClientboundBlockEntityDataPacket 同义。
            sendUpdatePacket();
        }
    }

    /** 客户端：把当前 owner 反映到渲染索引（有 owner ⇒ 登记连线；无 ⇒ 撤销）。 */
    private void syncClientLink() {
        if (level != null && !level.isClientSide) {
            return;
        }
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

    /** 热更新包在 Mek 的容器数据之外，额外带上 F10 增益归属（客户端据此画连线）。 */
    @Override
    public CompoundTag getReducedUpdateTag() {
        CompoundTag tag = super.getReducedUpdateTag();
        if (buffOwnerPos != null) {
            tag.putLong("BuffOwnerPos", buffOwnerPos.asLong());
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
        buffOwnerPos = tag.contains("BuffOwnerPos") ? BlockPos.of(tag.getLong("BuffOwnerPos")) : null;
        syncClientLink();
    }

    // ==================== 配方 ====================

    /** 复用的配方包装器（原先每次配方查找都 new 一个）。 */
    private RecipeWrapper cachedRecipeWrapper;

    private RecipeWrapper recipeWrapper() {
        if (cachedRecipeWrapper == null) {
            cachedRecipeWrapper = new RecipeWrapper(new MekCkSlotHandler(List.of(inputSlot, outputSlot)));
        }
        return cachedRecipeWrapper;
    }

    /**
     * 找当前该做的配方 —— 有订单时只认订单指定的那张（与迁移前逐字同义）。
     *
     * <p>没有订单时自动吃料（本机是自动机器，不是「下单才动」的烧烤架）。</p>
     */
    private Recipe<?> findRecipe(Level level) {
        if (inputSlot == null || inputSlot.getStack().isEmpty()) {
            return null;
        }
        RecipeType<NutRoastingRecipe> type = MekCkRecipeTypes.NUT_ROASTING_RECIPE_TYPE.get();
        NutRoastingRecipe found = level.getRecipeManager().getRecipeFor(type, recipeWrapper(), level).orElse(null);
        if (found == null) {
            return null;
        }
        // ME 下单：只执行订单指定的配方。
        ResourceLocation ordered = order.getRecipeId();
        if (ordered != null && !ordered.equals(found.getId())) {
            return null;
        }
        return found;
    }

    /** 产物槽能不能放下（同种物品堆叠到 Integer.MAX_VALUE 为止，与迁移前的 canInsertOutput 同义）。 */
    private boolean canFitOutput(ItemStack result) {
        if (result.isEmpty()) {
            return true;
        }
        ItemStack existing = outputSlot.getStack();
        if (existing.isEmpty()) {
            return true;
        }
        if (ItemStack.isSameItemSameTags(existing, result)) {
            return cn.ism.mekck.util.CountMath.canStack(existing.getCount(), result.getCount(), Integer.MAX_VALUE);
        }
        return false;
    }

    /** 完成一次加工：消耗 1 输入、写入产物、推进订单。 */
    private void completeRecipe(Level level, Recipe<?> recipe) {
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) {
            return;
        }
        inputSlot.extractItem(1, Action.EXECUTE, AutomationType.INTERNAL);
        insertOutput(result.copy());
        // 订单推进走 MekCkOrderState（加法在 long 上做，满单即清）。
        if (order.advance(1)) {
            order.clear();
        }
    }

    /**
     * 把产物并入输出槽。
     *
     * <p>走 {@code setStack} 而不是改 {@code getStack()} 返回的活引用：后者会绕过槽的
     * {@code onContentsChanged}，区块不会被标记为脏。</p>
     */
    private void insertOutput(ItemStack stack) {
        ItemStack existing = outputSlot.getStack();
        if (existing.isEmpty()) {
            outputSlot.setStack(stack);
        } else if (ItemStack.isSameItemSameTags(existing, stack)) {
            long total = (long) existing.getCount() + stack.getCount();
            ItemStack merged = existing.copy();
            merged.setCount(total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total);
            outputSlot.setStack(merged);
        }
    }

    // ==================== 订单（AE2 / 面板） ====================

    @Override
    public boolean isMeOrderEnabled() {
        return clientMirroring() ? clientMeOrderEnabled : meOrderEnabled;
    }

    @Override
    public void setMeOrderEnabled(boolean enabled) {
        this.meOrderEnabled = enabled;
        setChanged();
    }

    /** 下单：取消（id 为 null）时清零、激活时夹到 ≥ 1 —— 与 {@link MekCkOrderState#setOrder} 同契约。 */
    public void setOrder(@Nullable ResourceLocation recipeId, int quantity) {
        order.setOrder(recipeId, quantity);
        setChanged();
    }

    /** AE2 下单：当前订单配方 id（无订单为 null）。 */
    @Nullable
    public ResourceLocation getOrderRecipeId() {
        return order.getRecipeId();
    }

    /** AE2 下单：当前订单剩余份数（0 = 无订单）。 */
    public int getOrderQuantity() {
        return order.getQuantity();
    }

    /** 已完成的份数。 */
    public int getOrderCompleted() {
        return order.getCompleted();
    }

    /**
     * 输入槽里的物品能做的全部配方。
     *
     * <p>材料表走 {@link RecipeRequiredInputs} 而<b>不是</b> {@code recipe.getIngredients()}：
     * 本机的自有配方不覆写后者，默认返回空表 ⇒ 判据会退化成「输入槽非空就算匹配」。</p>
     */
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> out = new ArrayList<>();
        Level level = getLevel();
        if (level == null || inputSlot == null || inputSlot.getStack().isEmpty()) {
            return out;
        }
        RecipeType<NutRoastingRecipe> type = MekCkRecipeTypes.NUT_ROASTING_RECIPE_TYPE.get();
        for (Recipe<?> recipe : cn.ism.mekck.recipe.RecipeCache.all(level, type)) {
            if (matchesInput(recipe)) {
                out.add(recipe);
            }
        }
        return out;
    }

    /** 供「本机下单」面板的 Max 按钮：按输入槽现有材料最多可做几份。 */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        if (recipe == null || inputSlot == null || !matchesInput(recipe)) {
            return 0;
        }
        int max = Integer.MAX_VALUE;
        for (var ing : RecipeRequiredInputs.of(recipe)) {
            if (ing == null || ing.isEmpty()) {
                continue;
            }
            int have = ing.test(inputSlot.getStack()) ? inputSlot.getStack().getCount() : 0;
            max = Math.min(max, have);
            if (max <= 0) {
                return 0;
            }
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    /** 输入槽里的材料能否满足这张配方（与 Max 计数共用同一份材料表）。 */
    private boolean matchesInput(Recipe<?> recipe) {
        if (recipe == null || inputSlot == null || inputSlot.getStack().isEmpty()) {
            return false;
        }
        for (var ing : RecipeRequiredInputs.of(recipe)) {
            if (ing == null || ing.isEmpty()) {
                continue;
            }
            if (!ing.test(inputSlot.getStack())) {
                return false;
            }
        }
        return true;
    }

    // ==================== 客户端读侧 ====================

    /** 菜单/屏幕读进度（按端分流：服务端读权威值、客户端读镜像）。 */
    public int getProgress() {
        return clientMirroring() ? clientProgress : progress;
    }

    /**
     * 进度分母 —— 装速度卡后批次变短，进度条必须跟着变，否则条子只涨到 1/倍率就跳回 0。
     *
     * <p>分母也走同步通道（{@code clientCycle}）而不是让客户端自己按升级卡算：
     * 客户端那份升级计数可能落后于服务端的 20 tick 安装读条，两边一旦漂移，
     * 屏幕上就会画出与实际进度无关的比例（与 {@code GrillBlockEntity} 同款处理）。</p>
     */
    public int getProcessTime() {
        return clientMirroring() ? clientCycle : getEffectiveProcessTime();
    }

    /** 机身温度（单位 0.01 ℃）—— 屏幕侧除以 100.0。 */
    public int getTemperatureDeciCelsius() {
        return (int) Math.round((getTemperature() - 273.15) * 100.0);
    }

    /** 能量容器 —— 屏幕的能源条直接吃它（存量/上限与 tooltip 由 Mek 自己组装）。 */
    public MachineEnergyContainer<NutRoasterTile> getEnergyContainer() {
        return energyContainer;
    }

    private boolean clientMirroring() {
        return level == null || level.isClientSide;
    }

    /**
     * 把进度、索敌设定与 ME 开关推给客户端。
     *
     * <p>能量与温度不在这里同步：前者由 Mek 的能量容器通道、后者由基类对每个热容建的
     * {@code SyncableDouble} 负责。索敌设定的判据在服务端（目标类型影响 AI 行为），
     * 而 GUI 在客户端画，必须走同步通道。</p>
     */
    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(SyncableInt.create(this::getProgress, v -> this.clientProgress = v));
        container.track(SyncableInt.create(this::getEffectiveProcessTime, v -> this.clientCycle = v));
        container.track(SyncableInt.create(this::getTargetType, v -> this.clientTargetType = v));
        container.track(SyncableInt.create(this::getRadius, v -> this.clientRadius = v));
        container.track(SyncableBoolean.create(this::isMeOrderEnabled, v -> this.clientMeOrderEnabled = v));
    }

    // ==================== 存档 ====================

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        // 槽位 / 能量 / 侧配 / 升级 / 热容 / 自定义名由 TileEntityMekanism 自己写，重复写会互相覆盖。
        tag.putInt("Progress", progress);
        order.save(tag);
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        tag.putInt("AttackTimer", attackTimer);
        tag.putInt("TargetType", targetType);
        tag.putInt("Radius", radius);
        if (buffOwnerPos != null) {
            tag.putLong("BuffOwnerPos", buffOwnerPos.asLong());
        }
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        readOwnState(tag);
    }

    /** 本机自有状态（订单 / 进度 / 索敌 / 增益归属）—— 存档与掉落物恢复两条路径共用。 */
    private void readOwnState(CompoundTag tag) {
        progress = Math.max(0, tag.getInt("Progress"));
        order.load(tag);
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        attackTimer = Math.max(0, tag.getInt("AttackTimer"));
        // 存档里的旧值（写入时未夹紧）也可能是越界的，读回时一并归一化 —— 与 setter 同一道闸。
        targetType = IceTargetSearch.clampTargetType(tag.getInt("TargetType"));
        radius = IceTargetSearch.clampAttackRadius(tag.getInt("Radius"));
        buffOwnerPos = tag.contains("BuffOwnerPos") ? BlockPos.of(tag.getLong("BuffOwnerPos")) : null;
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
    }

    // ==================== ISustainedData（挖掉再放下的状态恢复）====================

    /**
     * 读回战利品表搬进 {@code mekData.*} 的内容。
     *
     * <p>槽位不在 {@code SubstanceType} 里（枚举只有 ENERGY/FLUID/GAS/…），
     * 而 {@code BlockMekanism.setPlacedBy} 的槽位分支要求方块物品实现
     * {@code IItemSustainedInventory}（本模组的 {@code MekCkBlockItem} 没有实现）
     * ⇒ 少了这一句的表现是「挖掉再放下，机器里的料全没了」。</p>
     */
    @Override
    public void readSustainedData(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        if (tag.contains("Items", Tag.TAG_LIST)) {
            mekanism.api.DataHandlerUtils.readContainers(getInventorySlots(null),
                    tag.getList("Items", Tag.TAG_LIST));
        }
        readOwnState(tag);
    }

    /**
     * <b>有意为空实现</b>：本机不往「可复制的配置数据」里写任何自有键。
     *
     * <p>本钩子同时被配置卡复制与中键取方块调用 —— 只要这里写了槽位键，
     * 配置卡就会连带复制整机库存（干净的物品复制漏洞）。</p>
     */
    @Override
    public void writeSustainedData(CompoundTag tag) {
    }

    /** 详见 {@code GrillBlockEntity#getTileDataRemap}：Mek 10.4.6 里没有外部消费方，照接口返回空表。 */
    @Override
    public java.util.Map<String, String> getTileDataRemap() {
        return java.util.Map.of();
    }

    // ==================== 界面 ====================

    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        return new cn.ism.mekck.menu.NutRoasterMenu(windowId, inv, this);
    }

    // getDisplayName() 刻意不覆写：TileEntityMekanism 的实现已经处理了「有自定义名 ⇒ 用自定义名，
    // 否则用 container.<命名空间>.<注册名>」这一对分支（GUI 标题因此读的是
    // lang 里的 container.mekck.nut_roaster，与 block.mekck.nut_roaster 各司其职）。
}
