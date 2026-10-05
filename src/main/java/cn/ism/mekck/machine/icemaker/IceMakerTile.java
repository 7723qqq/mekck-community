package cn.ism.mekck.machine.icemaker;

import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.entity.IceCubeEntity;
import cn.ism.mekck.item.ColdBrewHelper;
import cn.ism.mekck.item.ColdBrewTier;
import cn.ism.mekck.item.ColdBrewUpgradeItem;
import cn.ism.mekck.machine.MekCkNetworkPullableTile;
import cn.ism.mekck.machine.MekCkOrderState;
import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.machine.MekCkSlotHandler;
import cn.ism.mekck.menu.slot.MekCkSlots;
import cn.ism.mekck.menu.slot.SlotDef;
import cn.ism.mekck.recipe.IceMakeRecipe;
import cn.ism.mekck.recipe.RecipeInputMatcher;
import cn.ism.mekck.recipe.RecipeRequiredInputs;
import cn.ism.mekck.registry.MekCkRecipeTypes;
import cn.ism.mekck.upgrade.MekCkUpgradeTracker;
import cn.ism.mekck.upgrade.UpgradeHelper;
import cn.ism.mekck.util.IceTargetSearch;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.FloatingLong;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.heat.BasicHeatCapacitor;
import mekanism.common.capabilities.heat.CachedAmbientTemperature;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
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
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 急冻制冰机（Mek 体系版）—— 水 + 催化剂 → 冰块，并具备「冷萃升级链 → 自动炮台」能力。
 *
 * <h3>与旧 {@code IceMakerBlockEntity} 的关系：只换能力层，玩法逐条照搬</h3>
 * 配方匹配（{@code mekck:ice_make}）、温度系统（设定温度 / 自动制冷 / 温度速度倍率）、
 * 冷萃升级的 20 tick 安装读条与链式准入、索敌（{@code TargetType} / {@code Radius}）与
 * 待生成队列（{@code pendingAttackTargets} / {@code MAX_SPAWNS_PER_TICK}）、
 * F10 增益归属（ice_maker ← bakery_oven）、订单与 ME 下单，全部与迁移前逐字同义。
 * 换掉的只是机器能力层：侧配 / 升级 / 红石 / 能量 / 弹出 / 槽位 / 流体 / 热容
 * 由 Mek 基类提供，本类不再自建 {@code LazyOptional} / {@code ContainerData} /
 * {@code EnergyStorage} / {@code SideMode} / {@code FluidTank} / {@code MekCkHeatComponent}。
 *
 * <h3>为什么继承 {@link MekCkNetworkPullableTile}</h3>
 * 带档位的工厂家族用 {@code MekCkMachineTile}（{@code createExecutor()} 抽象），
 * 本机是无档位单机，需要的是「单机基类」那两样：AE2 网格节点四件套生命周期 +
 * {@code INetworkPullable}。
 *
 * <h3>槽位顺序是存档契约</h3>
 * {@code [输入, 输出, 创造升级, 冷萃①..⑤, 能源]} = 9 格。
 * 速度 / 能量卡不再占物品槽：它们由 Mek 的 {@code TileComponentUpgrade} 持有
 * （升级 tab 的 20 tick 安装读条）。本机<b>不支持速度升级</b>（速度由机身温度决定），
 * 所以 {@code withSupportedUpgrades} 只放 {@link Upgrade#ENERGY}。
 */
public final class IceMakerTile extends MekCkNetworkPullableTile
        implements MenuProvider, ISustainedData {

    // ── 槽位下标（= 本机槽位列表里的位置，不是菜单下标）──────────────────

    /** 输入槽（催化剂，不消耗）。 */
    public static final int INPUT_SLOT = 0;
    /** 输出槽（冰块）—— 同时是攻击弹药库。 */
    public static final int OUTPUT_SLOT = 1;
    /** 创造升级槽（额外槽，Mek 的升级体系里没有这个概念）。 */
    public static final int CREATIVE_SLOT = 2;
    /** 冷萃升级槽①（冷萃，链式准入的第一环）。 */
    public static final int CB_SLOT_1 = 3;
    /** 冷萃升级槽②（低温冷萃，需先装①）。 */
    public static final int CB_SLOT_2 = 4;
    /** 冷萃升级槽③（凛冰冷萃，需先装②）。 */
    public static final int CB_SLOT_3 = 5;
    /** 冷萃升级槽④（龙霜 / 女王，需先装③）。 */
    public static final int CB_SLOT_4 = 6;
    /** 冷萃升级槽⑤（失温，需先装④）。 */
    public static final int CB_SLOT_5 = 7;
    /** 能源槽（能量物品）。 */
    public static final int POWER_SLOT = 8;
    /** 槽位总数。 */
    public static final int TOTAL_SLOTS = 9;
    /** 冷萃槽数量。 */
    public static final int COLD_BREW_SLOTS = 5;

    // ── 机器参数（逐字沿用迁移前的值）────────────────────────────────────

    /** 能量容量（FE）。由方块的 {@code AttributeEnergy} 声明，{@code MachineEnergyContainer} 构造时读它。 */
    public static final int ENERGY_CAPACITY = 300_000;
    /** 基础能耗（FE/tick）。加工耗电；制冷另按 {@link #COOLING_MAX_ENERGY_PER_TICK} 计。 */
    public static final int ENERGY_PER_TICK = 60;
    /** 基准批次长度（tick）—— 温度越低越快，见 {@link #getEffectiveProcessTime()}。 */
    public static final int PROCESS_TIME = 100;
    /** 单次接收上限（FE）。 */
    public static final int MAX_RECEIVE = 5_000;
    /** 水罐容量（mB）。 */
    public static final int WATER_CAPACITY = 256_000;

    /** 工作温度上限：机身温度必须低于该值（273.15 K = 0 ℃）才允许加工。 */
    public static final double WORK_TEMP_LIMIT_K = 273.15;
    /** 最快速度倍率（机身温度 0 K 绝对零度时）。 */
    public static final double MAX_SPEED_MULTIPLIER = 30.0;
    /** 制冷能量效率：为电阻型加热器效率（0.6）的 1/3，即 1 FE → 0.2 J 热量转移。 */
    public static final double COOLING_EFFICIENCY = 0.2;
    /** 自身制冷最大功率（FE/t）：按需调节，不超过该值。 */
    public static final int COOLING_MAX_ENERGY_PER_TICK = 4_000;

    /** 设定温度下限（单位 0.01 ℃）＝ -273.15 ℃。 */
    public static final int MIN_TARGET_TEMPERATURE = -27315;
    /** 设定温度上限（单位 0.01 ℃）＝ 0 ℃（本机只降温）。 */
    public static final int MAX_TARGET_TEMPERATURE = 0;

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
    private static final ResourceLocation ICE_MAKE_TYPE_ID =
            ResourceLocation.fromNamespaceAndPath("mekck", "ice_make");

    /** 五个冷萃槽的下标（与 {@code ColdBrewTier.slot} 的 1..5 一一对应）。 */
    private static final int[] COLD_BREW_SLOT_INDEXES = {CB_SLOT_1, CB_SLOT_2, CB_SLOT_3, CB_SLOT_4, CB_SLOT_5};

    /**
     * 五个冷萃读条器 —— <b>长度与 {@link #COLD_BREW_SLOT_INDEXES} 同源</b>。
     *
     * <p>写死成 5 个字面量也能跑，但那样「下标 i 在 {@code coldBrewSlots / installedColdBrew /
     * coldBrewTrackers} 三张表里指同一格」这条不变量就只靠人记；加一个槽时漏改一张表，
     * 表现是错位的安装/卸载（静默）。这里让它由同一个常量派生。</p>
     */
    private static MekCkUpgradeTracker[] createColdBrewTrackers() {
        MekCkUpgradeTracker[] trackers = new MekCkUpgradeTracker[COLD_BREW_SLOT_INDEXES.length];
        for (int i = 0; i < trackers.length; i++) {
            trackers[i] = new MekCkUpgradeTracker(1);
        }
        return trackers;
    }

    // ── 槽位对象（只能在 getInitialInventory 里 new，构造期陷阱见 MekCkMachineTile）──
    //
    // ⚠️ 这里的字段**一律不能带初始化式**：getInitialInventory 在父类构造器内部被回调，
    // 那一刻本类的字段初始化器还没跑（写 `= new MekCkSlot[...]` 的话，方法里对它的赋值
    // 会撞上 null —— 症状是「方块放下去建不出方块实体」）。数组也要在方法里现建。

    private MekCkSlot inputSlot;
    private MekCkSlot outputSlot;
    private MekCkSlot creativeSlot;
    /** 五个冷萃槽，索引 0..4 = 冷萃①..⑤（在 {@link #getInitialInventory} 里现建）。 */
    private MekCkSlot[] coldBrewSlots;
    private EnergyInventorySlot energySlot;
    private MachineEnergyContainer<IceMakerTile> energyContainer;
    private BasicHeatCapacitor heatCapacitor;
    private IExtendedFluidTank waterTank;

    /**
     * AE2 侧看到的槽位视图（输入 0 / 输出 1，下标与迁移前的 {@code ItemStackHandler} 一致）。
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

    /**
     * 冷萃升级读条（每槽 1 个，复刻 Mekanism 安装语义：读条 20 tick 后安装并消耗槽内物品）。
     *
     * <p>与已安装等级 {@link #installedColdBrew} <b>成对</b>落盘 —— 只写等级的话，重载后
     * {@code getInstalled()} 回到 0，而 {@link #uninstallUpgrade} 的第一道门就是它
     * ⇒ 冷萃升级<b>卸不下来</b>（静默、无日志）。见 {@code readOwnState}。</p>
     */
    private final MekCkUpgradeTracker[] coldBrewTrackers = createColdBrewTrackers();
    /** 各冷萃槽已安装的等级（null = 未安装）。攻击档案读它。 */
    private final ColdBrewTier[] installedColdBrew = new ColdBrewTier[COLD_BREW_SLOT_INDEXES.length];

    /** 设定温度（单位 0.01 ℃；默认 -27315 = -273.15 ℃，即全力制冷）。 */
    private int targetTemperature = MIN_TARGET_TEMPERATURE;
    /** 设定温度功能开关：开启后自动制冷至设定温度。 */
    private boolean temperatureControlEnabled = true;

    private int attackTimer;
    /** 本次发射的攻击档案（索敌时算好，逐 tick 生成弹射物时用）。 */
    private float pendingDamage;
    private boolean pendingAoe;
    private float pendingSplash;
    private boolean pendingSlow;
    private boolean pendingRemoveAI;
    private boolean pendingHypothermia;
    /**
     * 待生成冰块的目标分配表（目标 → 剩余冰块数）：
     * 同一目标的多块冰排队（逐 tick 依次生成），不同目标并列入队（同一 tick 一起生成）。
     */
    private final LinkedHashMap<LivingEntity, Integer> pendingAttackTargets = new LinkedHashMap<>();
    /**
     * 索敌结果缓存：创造升级让 {@code attackTimer = 1} ⇒ 每 tick 攻击一次，
     * 而半径 &gt; 64 时 {@code IceTargetSearch} 要遍历全部已加载实体 —— 不缓存就是每 tick 全服扫描。
     */
    private final IceTargetSearch.CandidateCache targetCache = new IceTargetSearch.CandidateCache();

    private int targetType = TARGET_HOSTILE;
    private int radius = MekckConfig.getIceAttackRadius();

    /** F10：buff 源（bakery_oven）位置；null = 未被增益。服务端权威，客户端经热更新包同步仅用于画线。 */
    @Nullable
    private BlockPos buffOwnerPos;
    /** F10：归属扫描冷却（每 20 tick 重扫一次）。 */
    private int buffScanCooldown;

    /**
     * PULSE 锁存：收到红石上升沿后一直放行，直到跑完一个完整批次。
     *
     * <p>{@code MekanismUtils.canFunction} 对 PULSE 的实现是
     * {@code isPowered() && !wasPowered()}（上升沿那一 tick 放行、其余全禁），
     * 而本模组的旧语义是<b>锁存</b>的。不锁存的话一次脉冲只能推进 1/100 的进度条。</p>
     */
    private boolean pulseLatched;

    // ── 客户端镜像（服务端算、随容器同步通道下发）────────────────────────

    private int clientProgress;
    private int clientCycle;
    private int clientTargetType;
    private int clientRadius;
    private int clientTargetTemperature;
    private boolean clientTemperatureControlEnabled = true;
    private int clientInstalledColdBrewMask;
    private boolean clientMeOrderEnabled = true;

    public IceMakerTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
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
     * 水罐装配 —— 只用 {@code forSide}（不挂侧配）：迁移前的水罐是「全部方向可进可出」的
     * 一个裸 {@code IFluidHandler}，没有流体侧配面板，这里保持同一口径。
     */
    @Override
    protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
        FluidTankHelper builder = FluidTankHelper.forSide(this::getDirection);
        waterTank = BasicFluidTank.create(WATER_CAPACITY,
                stack -> stack.getFluid() == Fluids.WATER, listener);
        builder.addTank(waterTank);
        return builder.build();
    }

    /**
     * 槽位装配 —— 坐标取 {@link MekCkSlots.IceMaker}，本类不写坐标字面量。
     *
     * <p><b>顺序即存档契约</b>：输入 → 输出 → 创造升级 → 冷萃①~⑤ → 能源。</p>
     */
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this::getDirection, this::getConfig);

        SlotDef in = MekCkSlots.IceMaker.INPUT;
        SlotDef out = MekCkSlots.IceMaker.OUTPUT;
        SlotDef creative = MekCkSlots.IceMaker.CREATIVE;
        SlotDef power = MekCkSlots.IceMaker.POWER;

        // 单槽容量 Integer.MAX_VALUE（本模组的大堆叠行为），与迁移前的 getSlotLimit 同值。
        // 准入沿用迁移前的 isItemValid：非升级物品 + 是 ice_make 配方原料。
        inputSlot = MekCkSlot.inputFiltered(Integer.MAX_VALUE,
                (stack, automation) -> !isAnyUpgrade(stack)
                        && RecipeInputMatcher.matchesIceMake(getLevel(), stack),
                listener, in.x(), in.y());
        builder.addSlot(inputSlot);

        outputSlot = MekCkSlot.output(Integer.MAX_VALUE, listener, out.x(), out.y());
        builder.addSlot(outputSlot);

        // 创造升级：Mek 的升级体系里没有这个概念（它由额外槽承载），因此不进 TileComponentUpgrade。
        creativeSlot = MekCkSlot.inputFiltered(1,
                (stack, automation) -> UpgradeHelper.isCreativeUpgrade(stack),
                listener, creative.x(), creative.y());
        builder.addSlot(creativeSlot);

        // 五个冷萃槽：链式准入（装了前一级才收下一级），与迁移前的 isItemValid 逐条同义。
        // 数组在这里现建 —— 字段初始化式在本方法被回调时还没跑（见字段区注释）。
        List<SlotDef> cbDefs = MekCkSlots.IceMaker.COLD_BREW;
        coldBrewSlots = new MekCkSlot[COLD_BREW_SLOT_INDEXES.length];
        for (int i = 0; i < COLD_BREW_SLOT_INDEXES.length; i++) {
            int index = i;
            SlotDef def = cbDefs.get(i);
            coldBrewSlots[i] = MekCkSlot.inputFiltered(1,
                    (stack, automation) -> acceptsColdBrew(index, stack),
                    listener, def.x(), def.y());
            builder.addSlot(coldBrewSlots[i]);
        }

        energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener,
                power.x(), power.y());
        builder.addSlot(energySlot);

        ae2View = new MekCkSlotHandler(List.of(inputSlot, outputSlot));
        return builder.build();
    }

    /**
     * 冷萃槽的链式准入 —— 与迁移前的 {@code isItemValid} 逐条同义：
     * ①只收 COLD；②收 LOW_TEMP 且①非空；③收 FROST 且②非空；
     * ④收 DRAGON_FROST / QUEEN 且③非空；⑤收 HYPOTHERMIA 且④非空。
     */
    private boolean acceptsColdBrew(int index, ItemStack stack) {
        ColdBrewTier tier = ColdBrewUpgradeItem.getTier(stack);
        if (tier == null) {
            return false;
        }
        if (index == 0) {
            return tier == ColdBrewTier.COLD;
        }
        if (index == 3) {
            return (tier == ColdBrewTier.DRAGON_FROST || tier == ColdBrewTier.QUEEN)
                    && !coldBrewSlots[2].getStack().isEmpty();
        }
        ColdBrewTier expected = switch (index) {
            case 1 -> ColdBrewTier.LOW_TEMP;
            case 2 -> ColdBrewTier.FROST;
            default -> ColdBrewTier.HYPOTHERMIA;
        };
        return tier == expected && !coldBrewSlots[index - 1].getStack().isEmpty();
    }

    /**
     * 本机热容 —— 参数与 {@code MekCkHeatComponent} 同组（热容量 100 J/K、逆传导 5.0、逆绝缘 100.0，
     * 与 Mekanism 电阻型加热器一致）。
     *
     * <p>{@code TileEntityMekanism} 在构造器里无条件回调本钩子，并把电容温度挂进容器追踪，
     * 因此 GUI 读到的温度是同步值；相邻热容器交换与环境回归由本类在 {@link #onUpdateServer()}
     * 里每 tick 显式驱动（基类只为热力线缆与多方块驱动它们，单机不自己走一遍机身温度就永远不动 ——
     * 详见 {@link #tickHeatExchange()} 里对环境回归那处刻意偏离的说明）。</p>
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

    /** 拉料只看输入槽（产物 / 创造升级 / 冷萃 / 能源槽不参与）。 */
    @Override
    protected List<IInventorySlot> networkPullSlots() {
        return inputSlot == null ? List.of() : List.of(inputSlot);
    }

    /** 空输入槽时的补料并集必须与本机实际处理的配方类型一致（取错了料会被拒收、掉在机器旁）。 */
    @Override
    protected List<String> networkPullRecipeTypeIds() {
        return List.of(ICE_MAKE_TYPE_ID.toString());
    }

    /** AE2 侧看到的槽位视图（输入 0 / 输出 1）—— {@code MekckAe2} 的投料与产物回写走它。 */
    public ItemStackHandler getItems() {
        return ae2View;
    }

    /** 水罐（屏幕的流体条直接吃它；Mek 的容器同步通道会把它送到客户端）。 */
    public IExtendedFluidTank getWaterTank() {
        return waterTank;
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
        boolean creative = hasCreativeUpgrade();
        if (creative) {
            refillEnergy();
        }

        // 温度系统：先把环境回归与相邻传导挂上（基类 tickServer 会在本方法返回后统一 update 电容）。
        tickHeatExchange();
        // 冷萃升级读条（服务端）：读满 20 tick 后安装并消耗槽内物品。
        tickColdBrewUpgrades();
        // 按设定温度自动制冷（耗电只用于降温做功，与运行速度无关）。
        tickCooling(creative);

        // F10 攻击增益：每 20 tick 解析一次 buff 源归属（ice_maker ← bakery_oven）。
        if (buffScanCooldown-- <= 0) {
            buffScanCooldown = 20;
            refreshBuffOwner(level);
        }

        workCycle(level, creative);

        // 攻击行为与加工一样受红石控制：暂停时索敌与发射队列冻结。
        if (allowsWork()) {
            spawnPendingAttack(level);
            performAttack(level);
        }
    }

    /**
     * 闸门 + 进度 + 配方执行 —— 旧 {@code serverTick} 的「红石 → 水/温度/能量 → 干活」三段式。
     *
     * <p>与旧实现逐条对应：红石闸门（PULSE 走锁存）→ 有配方且温度达标且水够且产物装得下 →
     * 电够 → 扣电 → 进度 +1 → 满一个批次才耗水、落产物并推进订单。</p>
     */
    private void workCycle(Level level, boolean creative) {
        // 创造升级：批次长度整个换成 1 tick、能耗整个置 0（不是「乘 0」）。
        int cycle = creative ? 1 : getEffectiveProcessTime();
        int cost = creative ? 0 : ENERGY_PER_TICK;

        IceMakeRecipe recipe = findRecipe(level);
        ItemStack result = recipe == null ? ItemStack.EMPTY : recipeResult(recipe);
        boolean canProcess = recipe != null
                && isTemperatureWorkable()
                && waterTank.getFluidAmount() >= recipe.getFluidAmount()
                && canFitOutput(result);

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
        }
        progress++;
        if (progress >= cycle) {
            completeRecipe(recipe);
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

    // ==================== 升级 ====================

    private int installedUpgrades(Upgrade type) {
        var component = getComponent();
        return component == null ? 0 : component.getUpgrades(type);
    }

    /** 已安装的能量升级数量（经 Mek 的 20 tick 读条生效）。 */
    public int getEnergyUpgradeCount() {
        return installedUpgrades(Upgrade.ENERGY);
    }

    /** 速度升级不支持（速度由温度决定），恒为 0。 */
    public int getSpeedUpgradeCount() {
        return 0;
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
        if (ColdBrewUpgradeItem.getTier(stack) != null) {
            return true;
        }
        if (cn.ism.mekck.item.FerreroUpgradeItem.getTier(stack) != null) {
            return true;
        }
        return UpgradeHelper.isUpgrade(stack);
    }

    /**
     * 潜行右键把升级装进对应槽 —— 与迁移前的 {@code addUpgradesFromHand} 逐条同义。
     *
     * <p>保留这条路径是刻意的（口径 §12.4.1）：冷萃升级<b>不是 Mek 的 {@code Upgrade}</b>，
     * 它由本机的额外槽 + 20 tick 读条承担，Mek 的升级 tab 碰不到它。删掉这条等于
     * 「冷萃再也装不进去」—— 与啃掉一条活功能同型。Mek 的能量 / 速度卡仍走 Mek 的升级 tab。</p>
     *
     * @return 实际装入的物品数（0 = 未装入，调用方不消耗手持物）
     */
    public int addUpgradesFromHand(ItemStack held) {
        ColdBrewTier tier = ColdBrewUpgradeItem.getTier(held);
        if (tier == null) {
            // 非冷萃（能量 / 速度 / 创造…）：能量卡交给 Mek 的 TileComponentUpgrade，
            // 创造卡进本机额外槽，其余一律拒绝。
            if (UpgradeHelper.isCreativeUpgrade(held) && creativeSlot.getStack().isEmpty()
                    && creativeSlot.isItemValid(held)) {
                creativeSlot.setStack(new ItemStack(held.getItem(), 1));
                return 1;
            }
            return 0;
        }
        int index = coldBrewIndexOf(tier);
        if (index < 0) {
            return 0;
        }
        MekCkSlot slot = coldBrewSlots[index];
        if (slot.getStack().isEmpty() && slot.isItemValid(held)) {
            slot.setStack(new ItemStack(held.getItem(), 1));
            return 1;
        }
        return 0;
    }

    /** 冷萃等级 → 槽下标（0..4）；龙霜与女王共用第 4 格。 */
    private static int coldBrewIndexOf(ColdBrewTier tier) {
        return switch (tier) {
            case COLD -> 0;
            case LOW_TEMP -> 1;
            case FROST -> 2;
            case DRAGON_FROST, QUEEN -> 3;
            case HYPOTHERMIA -> 4;
        };
    }

    /** 冷萃升级读条（服务端每 tick）：安装成功后记录该槽已安装的等级并消耗槽位物品。 */
    private void tickColdBrewUpgrades() {
        boolean changed = false;
        for (int i = 0; i < COLD_BREW_SLOT_INDEXES.length; i++) {
            ItemStack stack = coldBrewSlots[i].getStack();
            ColdBrewTier tier = stack.isEmpty() ? null : ColdBrewUpgradeItem.getTier(stack);
            int before = coldBrewTrackers[i].getInstalled();
            if (coldBrewTrackers[i].tick(stack,
                    s -> ColdBrewUpgradeItem.getTier(s) != null)) {
                changed = true;
            }
            // 读条完成 ⇒ 成对更新：等级与读条器的 Installed 同时落盘（见 readOwnState/saveAdditional）。
            if (coldBrewTrackers[i].getInstalled() > before && tier != null) {
                installedColdBrew[i] = tier;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    /** 已安装的冷萃等级（null = 未安装），供攻击档案计算与界面显示。 */
    @Nullable
    public ColdBrewTier getInstalledColdBrew(int index) {
        return index >= 0 && index < installedColdBrew.length ? installedColdBrew[index] : null;
    }

    /**
     * 卸载升级。
     *
     * <p>mode 2 = 冷萃槽（slot 为<b>槽下标</b> CB_SLOT_1..CB_SLOT_5）。
     * 第一道门读的是<b>读条器</b>的 {@code getInstalled()}（与迁移前一致）——
     * 这就是「等级与读条器必须成对落盘」的由来：只写等级的话这道门永远返回，
     * 冷萃升级<b>卸不下来</b>，静默、无日志。</p>
     *
     * <p>Mek 的能量卡不经过这里：卸载由 Mek 的升级界面直接操作
     * {@code TileComponentUpgrade}。创造卡是普通槽，玩家直接从槽里取走。</p>
     */
    public void uninstallUpgrade(byte mode, int slot) {
        if (mode != 2) {
            return;
        }
        if (slot < CB_SLOT_1 || slot > CB_SLOT_5) {
            return;
        }
        int index = slot - CB_SLOT_1;
        if (coldBrewTrackers[index].getInstalled() <= 0) {
            return;
        }
        ColdBrewTier tier = installedColdBrew[index];
        if (tier == null) {
            return;
        }
        net.minecraft.world.item.Item cbItem = ColdBrewUpgradeItem.REGISTRY.get(tier) == null
                ? null : ColdBrewUpgradeItem.REGISTRY.get(tier).get();
        if (cbItem == null || cbItem == net.minecraft.world.item.Items.AIR) {
            return;
        }
        ItemStack give = new ItemStack(cbItem);
        MekCkSlot target = coldBrewSlots[index];
        ItemStack inSlot = target.getStack();
        if (!inSlot.isEmpty() && !ItemStack.isSameItemSameTags(inSlot, give)) {
            return;
        }
        if (!inSlot.isEmpty() && inSlot.getCount() >= inSlot.getMaxStackSize()) {
            return;
        }
        coldBrewTrackers[index].uninstall(1);
        installedColdBrew[index] = null;
        if (inSlot.isEmpty()) {
            target.setStack(give);
        } else {
            ItemStack grown = inSlot.copy();
            grown.grow(1);
            target.setStack(grown);
        }
        setChanged();
    }

    /** 冷萃升级安装进度（0~1，供界面）。 */
    public double getColdBrewInstallProgress() {
        double best = 0.0;
        for (MekCkUpgradeTracker t : coldBrewTrackers) {
            best = Math.max(best, t.getProgress());
        }
        return best;
    }

    // ==================== 温度系统 ====================

    /** 当前机身温度（开尔文）。客户端读到的是 Mek 同步下来的电容温度。 */
    public double getTemperatureK() {
        return heatCapacitor == null ? mekanism.api.heat.HeatAPI.AMBIENT_TEMP : heatCapacitor.getTemperature();
    }

    /** 设定温度（单位 0.01 ℃）：本机只降温，钳制在 -27315 至 0 之间。 */
    public void setTargetTemperature(int milliCelsius) {
        this.targetTemperature = clampTargetTemperature(milliCelsius);
        setChanged();
    }

    /**
     * 设定温度的唯一钳制闸门：setter 与读档共用 —— 存档里的旧值/被改过的值同样不能绕过
     * （与 radius/targetType 的读档夹紧同型，见 {@code TestIceCombatGuards}）。
     */
    private static int clampTargetTemperature(int milliCelsius) {
        return Math.max(MIN_TARGET_TEMPERATURE, Math.min(MAX_TARGET_TEMPERATURE, milliCelsius));
    }

    /** 调整设定温度（增量，单位 0.01 ℃）。 */
    public void adjustTargetTemperature(int delta) {
        setTargetTemperature(targetTemperature + delta);
    }

    public int getTargetTemperature() {
        return clientMirroring() ? clientTargetTemperature : targetTemperature;
    }

    public void setTemperatureControlEnabled(boolean enabled) {
        this.temperatureControlEnabled = enabled;
        setChanged();
    }

    public boolean isTemperatureControlEnabled() {
        return clientMirroring() ? clientTemperatureControlEnabled : temperatureControlEnabled;
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
        if (t >= WORK_TEMP_LIMIT_K) {
            return 0.0;
        }
        double ratio = (WORK_TEMP_LIMIT_K - t) / WORK_TEMP_LIMIT_K;
        return 1.0 + (MAX_SPEED_MULTIPLIER - 1.0) * ratio;
    }

    /** 有效速度倍率：由机身温度决定（急冻制冰机不支持速度升级）。 */
    public double getEffectiveSpeedMultiplier() {
        return Math.max(0.0, getTemperatureSpeedMultiplier());
    }

    public double getEffectiveEnergyConsumptionMultiplier() {
        return UpgradeHelper.energyConsumptionMultiplier(getEnergyUpgradeCount());
    }

    public int getEffectiveProcessTime() {
        double mult = getEffectiveSpeedMultiplier();
        // 温度不满足（不加工由 workCycle 拦截），返回基准值避免除零
        if (mult <= 0.0) {
            return PROCESS_TIME;
        }
        return Math.max(1, (int) (PROCESS_TIME / mult));
    }

    /**
     * 环境回归 + 相邻热容器交换。
     *
     * <p>基类只替<b>热力线缆</b>（{@code HeatNetwork}）与多方块驱动这两个模拟，
     * 单机不自己走一遍的话机身温度<b>永远贴着初始值</b>（既不制冷也不回温）。
     * 加减的热量先累积在电容的 {@code heatToHandle}，
     * 由 {@code TileEntityMekanism.tickServer} 在本方法返回后统一 {@code updateHeatCapacitors(null)} 落账。</p>
     *
     * <h3>为什么环境回归<b>没有</b>用 Mek 的 {@code simulateEnvironment()}</h3>
     * <p>相邻传导用了（{@link mekanism.common.capabilities.heat.ITileHeatHandler#simulateAdjacent()}，
     * 公式与迁移前的 {@code MekCkHeatComponent.tick} 逐字同源）；环境回归刻意保留<b>本机自己的口径</b>。
     * 把两条公式摊开算一遍：</p>
     * <ul>
     *   <li>Mek 的模拟：{@code invConduction = AIR_INVERSE_COEFFICIENT(10000) + 逆绝缘(100) + 逆传导(5)}，
     *       每个方向 {@code ΔH = −(T − T环境)·100/10105}，六个方向合计
     *       {@code ΔT ≈ −(T − T环境)·0.00059} ⇒ 时间常数 ≈ <b>1700 tick（84 秒）</b>；</li>
     *   <li>迁移前的 {@code MekCkHeatComponent.tick}：{@code ΔH = −(T − T环境)·100·0.01}
     *       ⇒ {@code ΔT = −(T − T环境)·0.01} ⇒ 时间常数 <b>100 tick（5 秒）</b>。</li>
     * </ul>
     * <p>差 <b>17 倍</b>。而机身温度是本机<b>唯一的调速手段</b>（0 ℃ 1× → 绝对零度 30×，
     * 见 {@link #getTemperatureSpeedMultiplier()}）：回归慢 17 倍等于把「持续制冷」的电费
     * 压到近乎零、把 30× 速度白送出去 —— 那是<b>玩法变更</b>，不是架构迁移该带的东西。
     * 所以这里保留旧速率，并把 Mek 的原生模拟留给相邻传导。</p>
     *
     * <p>（如实记：这是本轮对「能用上游原生的，一律用原生的」的一处<b>刻意偏离</b>，
     * 理由如上；若要改回原生速率，改这一个方法即可，但需要先确认那是想要的平衡。）</p>
     */
    private void tickHeatExchange() {
        if (heatCapacitor == null) {
            return;
        }
        simulateAdjacent();
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        double ambient = mekanism.api.heat.HeatAPI.getAmbientTemp(level, getBlockPos());
        double diff = heatCapacitor.getTemperature() - ambient;
        if (Math.abs(diff) > 1.0e-3) {
            heatCapacitor.handleHeat(-diff * cn.ism.mekck.util.MekCkHeatComponent.HEAT_CAPACITY
                    * AMBIENT_LOSS_RATE);
        }
    }

    /** 每 tick 向环境回归的热量比例 —— 与迁移前的 {@code MekCkHeatComponent.AMBIENT_LOSS_RATE} 同值。 */
    private static final double AMBIENT_LOSS_RATE = 0.01;

    /**
     * 按设定温度自动制冷（耗电只用于降温做功，与运行速度无关）。
     *
     * <p>制冷条件：功能开启、当前温度高于设定温度、能量足够；达到设定温度即停止制冷。
     * 制冷量同时驱动涡流管式定向热交换（见 {@link #applyDirectedHeat}）。</p>
     */
    private void tickCooling(boolean creative) {
        if (heatCapacitor == null || !temperatureControlEnabled) {
            return;
        }
        double targetK = (targetTemperature / 100.0) + 273.15;
        double currentK = getTemperatureK();
        if (currentK <= targetK) {
            return;
        }
        // 本 tick 需要转移的热量：不超过「降到设定温度所需」，也不超过最大功率
        double needHeat = (currentK - targetK) * cn.ism.mekck.util.MekCkHeatComponent.HEAT_CAPACITY;
        double maxHeat = COOLING_MAX_ENERGY_PER_TICK * COOLING_EFFICIENCY;
        double coolingHeat = Math.min(needHeat, maxHeat);
        int energyNeeded = creative ? 0 : (int) Math.ceil(coolingHeat / COOLING_EFFICIENCY);
        if (energyNeeded > 0
                && energyContainer.getEnergy().compareTo(FloatingLong.create(energyNeeded)) < 0) {
            return;
        }
        if (energyNeeded > 0) {
            energyContainer.extract(FloatingLong.create(energyNeeded), Action.EXECUTE, AutomationType.MANUAL);
        }
        // 电能 → 制冷做功（效率为电阻型加热器的 1/3）
        heatCapacitor.handleHeat(-coolingHeat);
        applyDirectedHeat(coolingHeat);
    }

    /**
     * 涡流管式定向热交换（参考气动工艺涡流管：一端吸热、一端放热，热量由动力介质搬运）：
     * {@code FACING} 侧为冷端，从该方向的相邻热力设备吸热；其反向为热端，向该方向的相邻设备放热。
     * 注意 {@code FACING} = 放置时玩家朝向 = <b>背离玩家</b>的那一面；面配置面板把它标为「背面」，
     * 因此<b>面板里「背面」= 冷端、「正面」= 热端</b>（与方块物品 tooltip 的叫法相反）。
     * 每个方向转移的热量为本次制冷量的一半（另一半作用于机身自身降温）。
     */
    private void applyDirectedHeat(double coolingHeat) {
        Level lvl = getLevel();
        if (lvl == null || lvl.isClientSide || coolingHeat <= 0.0) {
            return;
        }
        Direction facing = getDirection();
        transferToNeighbour(lvl, facing, -coolingHeat * 0.5);
        transferToNeighbour(lvl, facing.getOpposite(), coolingHeat * 0.5);
    }

    /** 向指定方向的相邻热力设备注入热量（正数升温、负数降温）。 */
    private void transferToNeighbour(Level level, Direction side, double heat) {
        if (Math.abs(heat) < 1.0e-3) {
            return;
        }
        BlockPos neighbour = getBlockPos().relative(side);
        if (!level.hasChunkAt(neighbour)) {
            return;
        }
        BlockEntity be = level.getBlockEntity(neighbour);
        if (be == null) {
            return;
        }
        be.getCapability(mekanism.common.capabilities.Capabilities.HEAT_HANDLER, side.getOpposite())
                .ifPresent(handler -> {
                    double current = handler.getTotalTemperature();
                    if (!Double.isFinite(current) || current < 0.0 || current > 1.0e9) {
                        return;
                    }
                    handler.handleHeat(heat);
                });
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
     * <p>与迁移前逐字同款：上一波冰块未发完不启动新一波；未装冷萃不攻击（档案为 null）；
     * 无弹药（且非创造升级）不攻击；按档案的目标数取最近目标，装凛冰以上时
     * 目标不足则集火最高血量目标。</p>
     */
    private void performAttack(Level level) {
        if (!pendingAttackTargets.isEmpty()) {
            return;
        }
        // 以「已安装的冷萃等级」计算攻击档案（升级需经读条安装，安装后槽位物品被消耗）
        ColdBrewHelper.Profile profile = ColdBrewHelper.compute(
                installedColdBrew[0], installedColdBrew[1], installedColdBrew[2],
                installedColdBrew[3], installedColdBrew[4]);
        if (profile == null) {
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
        // 创造升级额外效果：攻速变为每 tick 攻击一轮；F10：被 bakery_oven 增益时攻速 +20%（interval ×0.8）
        attackTimer = creative ? 1 : buffedInterval(MekckConfig.getIceAttackInterval());

        List<LivingEntity> candidates = targetCache.get(
                level, worldPosition, this.radius, this.targetType, this::matchesTarget);
        if (candidates.isEmpty()) {
            return;
        }

        int count = Math.min(profile.targetCount, candidates.size());
        LivingEntity highestHp = null;
        for (LivingEntity e : candidates) {
            if (highestHp == null || e.getHealth() > highestHp.getHealth()) {
                highestHp = e;
            }
        }

        // 构建本波目标队列；目标不足时若安装了集火升级（allowExtras），剩余冰块攻击血量最高的目标
        List<LivingEntity> targets = new ArrayList<>(profile.targetCount);
        for (int i = 0; i < count; i++) {
            targets.add(candidates.get(i));
        }
        while (profile.allowExtras && targets.size() < profile.targetCount && highestHp != null) {
            targets.add(highestHp);
        }

        // 产物数量限制本波冰块数：每生成 1 个冰块消耗 1 个产物，不足时取消剩余冰块（创造升级不限）
        int budget = creative ? Integer.MAX_VALUE : output.getCount();
        if (targets.size() > budget) {
            targets = targets.subList(0, budget);
        }
        if (targets.isEmpty()) {
            return;
        }

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
        // 按目标聚合本轮冰块数：同一目标的多块冰排队（逐 tick 依次生成），不同目标并列入队
        pendingAttackTargets.clear();
        for (LivingEntity t : targets) {
            pendingAttackTargets.merge(t, 1, Integer::sum);
        }
    }

    /**
     * 逐 tick 生成冰块：每个 tick 对「仍有剩余冰块」的每个目标各生成 1 块冰 ——
     * 不同目标同一 tick 一起生成；同一目标的多块冰按队列逐 tick 依次生成。每块冰消耗 1 个产物。
     */
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
                it.remove(); // 目标已消失：跳过，不消耗产物
                continue;
            }
            if (!creative && outputSlot.getStack().isEmpty()) {
                pendingAttackTargets.clear(); // 产物耗尽：终止本波剩余冰块（创造升级不消耗）
                return;
            }
            IceCubeEntity.spawn(level, target.getX(), target.getY() + 5, target.getZ(),
                    pendingDamage, pendingAoe, pendingSplash, pendingSlow, pendingRemoveAI,
                    pendingHypothermia, worldPosition);
            spawnedThisTick++;
            if (!creative) {
                outputSlot.extractItem(1, Action.EXECUTE, AutomationType.INTERNAL);
                markedDirty = true; // 本 tick 统一置脏一次，避免每个弹射物都 setChanged()
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
    private net.minecraftforge.items.wrapper.RecipeWrapper cachedRecipeWrapper;

    private net.minecraftforge.items.wrapper.RecipeWrapper recipeWrapper() {
        if (cachedRecipeWrapper == null) {
            cachedRecipeWrapper = new net.minecraftforge.items.wrapper.RecipeWrapper(
                    new MekCkSlotHandler(List.of(inputSlot, outputSlot)));
        }
        return cachedRecipeWrapper;
    }

    /**
     * 找当前该做的配方 —— 有订单时只认订单指定的那张（与迁移前逐字同义）。
     *
     * <p>没有订单时自动吃料（本机是自动机器，不是「下单才动」的烧烤架）。</p>
     */
    private IceMakeRecipe findRecipe(Level level) {
        if (inputSlot == null || inputSlot.getStack().isEmpty()) {
            return null;
        }
        RecipeType<IceMakeRecipe> type = MekCkRecipeTypes.ICE_MAKE_RECIPE_TYPE.get();
        IceMakeRecipe found = level.getRecipeManager().getRecipeFor(type, recipeWrapper(), level).orElse(null);
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

    /** 配方产物（首项）—— 本机所有配方都是单产出。 */
    private static ItemStack recipeResult(IceMakeRecipe recipe) {
        return recipe.getResults().isEmpty() ? ItemStack.EMPTY : recipe.getResults().get(0);
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

    /** 完成一次加工：耗水、写入产物、推进订单（输入是催化剂，不消耗 —— 与迁移前一致）。 */
    private void completeRecipe(IceMakeRecipe recipe) {
        ItemStack result = recipeResult(recipe);
        waterTank.extract(recipe.getFluidAmount(), Action.EXECUTE, AutomationType.INTERNAL);
        if (!result.isEmpty()) {
            insertOutput(result.copy());
        }
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
        RecipeType<IceMakeRecipe> type = MekCkRecipeTypes.ICE_MAKE_RECIPE_TYPE.get();
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
     * 进度分母 —— 装能量卡与机身温度都会改变批次长度，进度条必须跟着变，
     * 否则条子只涨到 1/倍率就跳回 0。
     *
     * <p>分母也走同步通道（{@code clientCycle}）而不是让客户端自己按升级卡与温度算：
     * 客户端那份读数可能落后于服务端，两边一旦漂移，屏幕上就会画出与实际进度无关的比例
     * （与 {@code NutRoasterTile} 同款处理）。</p>
     */
    public int getProcessTime() {
        return clientMirroring() ? clientCycle : getEffectiveProcessTime();
    }

    /** 机身温度（单位 0.01 ℃）—— 屏幕侧除以 100.0。 */
    public int getTemperatureDeciCelsius() {
        return (int) Math.round((getTemperatureK() - 273.15) * 100.0);
    }

    /** 能量容器 —— 屏幕的能源条直接吃它（存量/上限与 tooltip 由 Mek 自己组装）。 */
    public MachineEnergyContainer<IceMakerTile> getEnergyContainer() {
        return energyContainer;
    }

    /** 第 index 个冷萃槽的槽对象（屏幕据此定位「已安装可点击卸载」的槽）。 */
    public IInventorySlot getColdBrewSlot(int index) {
        return index >= 0 && index < COLD_BREW_SLOTS ? coldBrewSlots[index] : null;
    }

    /**
     * 已安装冷萃的位掩码（客户端镜像）—— 每格 4 bit，值 = 等级序号 + 1（0 = 未安装）。
     *
     * <p>屏幕据此在冷萃槽上画「已安装」徽标。单个 int 就够（5 格 × 4 bit = 20 bit），
     * 比五条同步通道省事，也不会出现「五条通道各更新一半」的中间态。</p>
     */
    public int getInstalledColdBrewMask() {
        if (clientMirroring()) {
            return clientInstalledColdBrewMask;
        }
        int mask = 0;
        for (int i = 0; i < COLD_BREW_SLOT_INDEXES.length; i++) {
            ColdBrewTier tier = installedColdBrew[i];
            if (tier != null) {
                mask |= (tier.ordinal() + 1) << (i * 4);
            }
        }
        return mask;
    }

    /** 客户端镜像：第 index 格已安装的等级序号 + 1（0 = 未安装）。 */
    public int getInstalledColdBrewCode(int index) {
        return (getInstalledColdBrewMask() >> (index * 4)) & 0xF;
    }

    private boolean clientMirroring() {
        return level == null || level.isClientSide;
    }

    /**
     * 把进度、索敌设定、温度设定与冷萃安装态推给客户端。
     *
     * <p>能量 / 流体 / 温度不在这里同步：前两者由 Mek 的容器通道
     * （{@code SyncableFloatingLong} / {@code SyncableFluidStack}）、
     * 后者由基类对每个热容建的 {@code SyncableDouble} 负责。
     * 索敌与温度设定的判据在服务端（影响 AI 行为与制冷），而 GUI 在客户端画，
     * 必须走同步通道。</p>
     */
    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(SyncableInt.create(this::getProgress, v -> this.clientProgress = v));
        container.track(SyncableInt.create(this::getEffectiveProcessTime, v -> this.clientCycle = v));
        container.track(SyncableInt.create(this::getTargetType, v -> this.clientTargetType = v));
        container.track(SyncableInt.create(this::getRadius, v -> this.clientRadius = v));
        container.track(SyncableInt.create(this::getTargetTemperature, v -> this.clientTargetTemperature = v));
        container.track(SyncableBoolean.create(this::isTemperatureControlEnabled,
                v -> this.clientTemperatureControlEnabled = v));
        container.track(SyncableInt.create(this::getInstalledColdBrewMask,
                v -> this.clientInstalledColdBrewMask = v));
    }

    // ==================== 存档 ====================

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        // 槽位 / 能量 / 侧配 / 升级 / 热容 / 流体 / 自定义名由 TileEntityMekanism 自己写，重复写会互相覆盖。
        tag.putInt("Progress", progress);
        order.save(tag);
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        tag.putInt("TargetTemperature", targetTemperature);
        tag.putBoolean("TemperatureControl", temperatureControlEnabled);
        tag.putInt("AttackTimer", attackTimer);
        tag.putInt("TargetType", targetType);
        tag.putInt("Radius", radius);
        if (buffOwnerPos != null) {
            tag.putLong("BuffOwnerPos", buffOwnerPos.asLong());
        }
        // 冷萃：等级与读条器**成对**写。
        // 只写等级不写读条器的话，重载后 coldBrewTrackers[i].getInstalled() 回到 0，
        // 而 uninstallUpgrade 的第一道门就是它 ⇒ 冷萃升级**卸不下来**（静默、无日志）。
        for (int i = 0; i < COLD_BREW_SLOT_INDEXES.length; i++) {
            if (installedColdBrew[i] != null) {
                tag.putString("ColdBrew" + i, installedColdBrew[i].name());
            }
            tag.put("ColdBrewUpgradeTracker" + i, coldBrewTrackers[i].save());
        }
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        readOwnState(tag);
    }

    /** 本机自有状态 —— 存档与掉落物恢复（{@link #readSustainedData}）两条路径共用。 */
    private void readOwnState(CompoundTag tag) {
        progress = Math.max(0, tag.getInt("Progress"));
        order.load(tag);
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        // 读档同样过唯一钳制闸门：存档里的旧值/被改过的值不能绕过（见 TestIceCombatGuards）。
        targetTemperature = clampTargetTemperature(tag.getInt("TargetTemperature"));
        temperatureControlEnabled = !tag.contains("TemperatureControl") || tag.getBoolean("TemperatureControl");
        attackTimer = Math.max(0, tag.getInt("AttackTimer"));
        // 存档里的旧值（写入时未夹紧）也可能是越界的，读回时一并归一化 —— 与 setter 同一道闸。
        targetType = IceTargetSearch.clampTargetType(tag.getInt("TargetType"));
        radius = IceTargetSearch.clampAttackRadius(tag.getInt("Radius"));
        buffOwnerPos = tag.contains("BuffOwnerPos") ? BlockPos.of(tag.getLong("BuffOwnerPos")) : null;
        for (int i = 0; i < COLD_BREW_SLOT_INDEXES.length; i++) {
            installedColdBrew[i] = null;
            if (tag.contains("ColdBrew" + i)) {
                try {
                    installedColdBrew[i] = ColdBrewTier.valueOf(tag.getString("ColdBrew" + i));
                } catch (IllegalArgumentException ignored) {
                    // 存档里的等级名不认识（删过枚举项 / 手改）：当作未安装，机器照常工作。
                }
            }
            String trackerKey = "ColdBrewUpgradeTracker" + i;
            if (tag.contains(trackerKey, Tag.TAG_COMPOUND)) {
                coldBrewTrackers[i].load(tag.getCompound(trackerKey));
            } else if (installedColdBrew[i] != null) {
                // 只写了 ColdBrew{i}、没有读条器的存档（旧版本 / 掉落实测）：按「已装 1 件」补回 ——
                // 否则这批存档重载后同样卸不下冷萃。
                coldBrewTrackers[i].installDirect(1);
            }
        }
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
        // 能量 / 流体 / 热容不用在这里读：它们是 Mek 的 SubstanceType，
        // BlockMekanism.setPlacedBy 在本方法之前已经按 dataMap 里的
        // EnergyContainers / FluidTanks / HeatCapacitors 统一读过一遍。
        readOwnState(tag);
    }

    /**
     * <b>有意为空实现</b>：本机不往「可复制的配置数据」里写任何自有键。
     *
     * <p>本钩子同时被配置卡复制与中键取方块调用 —— 只要这里写了槽位键，
     * 配置卡就会连带复制整机库存（干净的物品复制漏洞）。
     * 需要跟着掉落物走的状态由战利品表的 {@code copy_nbt} + {@link #readSustainedData} 承担。</p>
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
        return new cn.ism.mekck.menu.IceMakerMenu(windowId, inv, this);
    }

    // getDisplayName() 刻意不覆写：TileEntityMekanism 的实现已经处理了「有自定义名 ⇒ 用自定义名，
    // 否则用 container.<命名空间>.<注册名>」这一对分支（GUI 标题因此读的是
    // lang 里的 container.mekck.ice_maker，与 block.mekck.ice_maker 各司其职）。
}
