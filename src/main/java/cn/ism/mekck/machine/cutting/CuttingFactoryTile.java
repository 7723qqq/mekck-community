package cn.ism.mekck.machine.cutting;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.block.CuttingMachineFactoryBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.factory.MekCkFactoryType;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.machine.ports.IMekCkPorted;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import cn.ism.mekck.util.CountMath;
import cn.ism.mekck.util.UpgradeHelper;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.providers.IBlockProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 切菜工厂的 tile —— 阶段 2 Task 4 的接线点。
 *
 * <p>本类只做三件事，其余全部继承：<b>①</b> 说出本家族的执行器是谁，
 * <b>②</b> 说出本家族的速率/能耗公式（基类只负责「够不够 → 扣多少 → 扣」），
 * <b>③</b> 实现 {@link IMekCkPorted} 的自动化端口声明。</p>
 *
 * <h3>能量闸门为什么落在 {@link #energyPerWorkTick()} 而不是执行器里</h3>
 * 旧 {@code CuttingMachineFactoryBlockEntity.serverTick} 的顺序是
 * 「红石 → 扣能量 → 进度条 → 满批次才 completeRecipe」。而 {@link CuttingFactoryExecutor}
 * 的契约是「本 tick 尽可能多地加工」——它必须在被调用<b>之前</b>就知道该不该调用、
 * 该扣多少。进度条与能量因此是<b>调用方</b>的责任，落在
 * {@link MekCkMachineTile#onUpdateServer()} 的闸门里。
 */
public class CuttingFactoryTile extends MekCkMachineTile implements IMekCkPorted {

    /**
     * 一个批次的基础耗时（tick）。与旧
     * {@code CuttingMachineFactoryBlockEntity.PROCESS_TIME} 同值。
     *
     * <p>实际耗时是它除以速度升级倍率，见 {@link #ticksPerWorkCycle()}。</p>
     */
    public static final int PROCESS_TIME = 200;

    /**
     * 单批次的基准能耗。与旧
     * {@code CuttingMachineFactoryBlockEntity.ENERGY_PER_PROCESS} 同值。
     *
     * <p>注意旧公式是 {@code ENERGY_PER_PROCESS × speedMult² × energyConsumptionMult}——
     * 速度倍率要<b>平方</b>。这看着像笔误，其实是刻意的：速度升级同时缩短耗时（÷speed）
     * 与放大单 tick 并行量（×speed），两次相乘才抵消，单 tick 基础能耗自然要 ×speed²。
     * 这里原样保留，不要「顺手优化」成一次方——那会让速度升级的实际收益翻倍。</p>
     */
    public static final int ENERGY_PER_PROCESS = 20;

    public CuttingFactoryTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
    }

    @Override
    protected MekCkRecipeExecutor createExecutor() {
        return new CuttingFactoryExecutor();
    }

    /**
     * {@inheritDoc}
     *
     * <p>基类默认从 {@code MekCkFactoryBlock} 反查档位；切菜方块走的是
     * {@code mekck:<tier>_cutting_factory} 那套注册（见
     * {@link CuttingMachineFactoryBlock}），基类认不出来，所以在这里改口。
     * 仍然<b>从方块反查</b>而不是读实例字段——原因见基类类注释的「构造期顺序陷阱」：
     * {@code getInitialInventory} 在父类构造器内部就被回调，此刻本类字段初始化器一个都还没跑。</p>
     */
    @Override
    protected CuttingMachineFactoryTier tierFromBlock() {
        if (blockProvider != null && blockProvider.getBlock() instanceof CuttingMachineFactoryBlock block) {
            return block.getTier();
        }
        return null;
    }

    /** 工艺类型恒为切菜：切菜工厂只有一套方块族，不从方块反查。 */
    @Override
    protected MekCkFactoryType typeFromBlock() {
        return MekCkFactoryType.CUTTING;
    }

    // ── 能量闸门参数（逐行对照旧 serverTick）─────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>旧实现：{@code int effectiveProcessTime = hasCreative ? 1
     * : Math.max(1, (int) (PROCESS_TIME / speedMult));}，且
     * {@code getEffectiveProcessTime()} 供 GUI 读进度条分母。本类去掉 creative 分支
     * （见类级报告：creative 升级不在本模组的受支持升级集内，Task 1 已把它排除）。</p>
     */
    @Override
    protected int ticksPerWorkCycle() {
        return effectiveProcessTime();
    }

    /**
     * {@inheritDoc}
     *
     * <p>旧实现（{@code CuttingMachineFactoryBlockEntity.serverTick}）的对应三行：
     * <pre>
     *   double energyConsumptionMult = getEffectiveEnergyConsumptionMultiplier();
     *   int baseEnergyPerTick = hasCreative ? 0
     *       : (int) Math.ceil(ENERGY_PER_PROCESS * speedMult * speedMult * energyConsumptionMult);
     *   if (machine.getTier().energyPerTick == 0) baseEnergyPerTick = 0;
     *   ...
     *   int energyPerTick = activeSlots &gt; 0 &amp;&amp; !hasCreative
     *       ? CountMath.mulClamp(Integer.MAX_VALUE, baseEnergyPerTick, activeSlots, stackMult) : 0;
     * </pre>
     * 四项逐条对应：
     * <ol>
     *   <li>{@code speedMult * speedMult} 的平方——语义见 {@link #ENERGY_PER_PROCESS}；</li>
     *   <li>{@code tier.energyPerTick == 0} 的免能耗档（NEBULA / SINGULARITY）先判、
     *       后算，免得免能耗档还去乘一个可能很大的倍率；</li>
     *   <li>{@code activeSlots > 0} 用基类的 {@link #activeWorkSlots()}（非空输入槽数）；</li>
     *   <li>{@code stackMult} 复用 {@link CuttingFactoryExecutor#stackMultiplier}，
     *       与执行器算并行数用的是<b>同一个纯函数</b>，两边不可能算出不同的倍增系数。</li>
     * </ol>
     * </p>
     */
    @Override
    protected int energyPerWorkTick() {
        CuttingMachineFactoryTier tier = getTier();
        // 免能耗档（NEBULA / SINGULARITY 的 energyPerTick == 0）先短路。
        if (tier == null || tier.energyPerTick == 0) {
            return 0;
        }
        int active = activeWorkSlots();
        if (active <= 0) {
            return 0;
        }
        double speedMult = effectiveSpeedMultiplier();
        double consumptionMult = effectiveEnergyConsumptionMultiplier();
        int baseEnergyPerTick = (int) Math.ceil(ENERGY_PER_PROCESS * speedMult * speedMult * consumptionMult);
        return CountMath.mulClamp(Integer.MAX_VALUE, baseEnergyPerTick, active, stackMultiplier());
    }

    // ── 速率倍率（旧 getEffectiveXxx 的等价物）────────────────────────────

    /** 旧 {@code getEffectiveSpeedMultiplier()}：{@code 10^(已装速度卡 / 8)}。 */
    public double effectiveSpeedMultiplier() {
        return UpgradeHelper.speedMultiplier(upgradeCount(Upgrade.SPEED));
    }

    /**
     * 旧 {@code getEffectiveEnergyConsumptionMultiplier()}：{@code 0.1^(已装能量卡 / 8)}。
     * 只影响<b>能耗</b>，不影响容量（容量倍率由 Mek 的能量升级自动处理）。
     */
    public double effectiveEnergyConsumptionMultiplier() {
        return UpgradeHelper.energyConsumptionMultiplier(upgradeCount(Upgrade.ENERGY));
    }

    /** 旧 {@code getEffectiveProcessTime()}：{@code max(1, 200 / 速度倍率)}。 */
    public int effectiveProcessTime() {
        return Math.max(1, (int) (PROCESS_TIME / effectiveSpeedMultiplier()));
    }

    /**
     * 旧 {@code getStackMultiplier()}。
     *
     * <p>与 {@link CuttingFactoryExecutor} 里那份逐字同源——两边都调
     * {@link CuttingFactoryExecutor#stackMultiplier} 这个纯函数。旧实现自己算一遍、
     * 执行器再算一遍，两处一旦漂移就会表现为「并行数与耗电量对不上」且无日志。</p>
     */
    public int stackMultiplier() {
        CuttingMachineFactoryTier tier = getTier();
        if (tier == null) {
            return 1;
        }
        int base = MekckConfig.getMultithreadedBase(tier);
        int maxParallel = MekckConfig.getMultithreadedMax(tier);
        int installed = upgradeCount(MekCkUpgradeRefs.storage());
        int cap = MekCkUpgradeTypes.capOf(MekCkUpgradeRefs.storage(), tier);
        return CuttingFactoryExecutor.stackMultiplier(installed, cap, base, maxParallel);
    }

    /**
     * 已装升级数。
     *
     * <p>旧实现读的是 {@code MekCkUpgradeTracker.getInstalled()}（自研 20 tick 读条），
     * 现在读 {@code TileComponentUpgrade.getUpgrades(type)}——Mek 自己的升级组件里
     * 同样有 20 tick 安装读条（实测 {@code TileComponentUpgrade.tickServer} 里
     * {@code getUpgrades(type) < getMax()} 才推进），因此不需要再单独实现一套。</p>
     */
    private int upgradeCount(Upgrade type) {
        return getComponent() == null ? 0 : getComponent().getUpgrades(type);
    }

    // ── IMekCkPorted：AE2 端口声明 ──────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>必须覆写</b>：默认实现返 {@code null}，漏覆写时消费方拿到的 tile 是 null，
     * 报错点会出现在消费方栈里而不是漏写处，排查成本极高。这是 Task 2 留下的预警 1。</p>
     */
    @Override
    public MekCkMachineTile mePortedTile() {
        return this;
    }

    @Override
    public List<IInventorySlot> mePatternItemInputs() {
        return getInputSlots();
    }

    @Override
    public List<IInventorySlot> mePatternItemOutputs() {
        return getOutputSlots();
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>必须覆写，这是 Task 2 的预警 2。</b>默认实现是「全部输入槽跨单不清空」，
     * 对切菜恰好是<b>反的</b>：切菜的每个输入槽都是一次性消耗型——每跑完一个批次
     * 就按配方结果整批吃掉（{@code completeRecipe} 里 {@code setStack(EMPTY)} 或减计数），
     * 槽里没有需要留到下一单的东西。沿用默认语义会让 AE2 每单都以为还欠着料，
     * 于是重复投料、产物翻倍。</p>
     *
     * <p>本模组没有燃料槽/催化剂槽这类跨单常驻槽，所以答案是「空」。</p>
     */
    @Override
    public List<IInventorySlot> mePersistentItemInputs() {
        return List.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>能量槽（{@code EnergyInventorySlot}）确实在 {@code getInventorySlots(null)} 里，
     * 消费方遍历槽位时会看到它，必须划出界。升级槽（{@code UpgradeInventorySlot}）
     * 由 {@code TileEntityMekanism} 单独持有、<b>不在</b>本 tile 的槽位列表里
     * （{@code getInitialInventory} 没有 addSlot 它），这里一并列出是为了给消费方一个
     * 明确的「这些别碰」白名单，而不是让它靠「不在列表里」这种隐式约定。</p>
     *
     * <p>侧配槽（{@code ConfigType.ITEM} 的目标槽）就是输入/输出方阵本身，
     * 它们已经是「配料槽 / 产物槽」，不重复排除。</p>
     */
    @Override
    public List<IInventorySlot> meManualOnlyItemSlots() {
        List<IInventorySlot> manual = new ArrayList<>(2);
        if (getEnergySlot() != null) {
            manual.add(getEnergySlot());
        }
        if (getComponent() != null && getComponent().getUpgradeSlot() != null) {
            manual.add(getComponent().getUpgradeSlot());
        }
        return manual;
    }

    /**
     * {@inheritDoc}
     *
     * <p>只置 boolean。<b>「1 个组端口 ↔ N 个物理槽」的塌缩形状由消费方负责</b>
     * （Task 2 预警 3）：本接口只表达意图，不规定 AE2 那一侧怎么把 N 个槽并成一个端口。
     * 在这里自己发明塌缩形状会把 AE2 侧的策略硬编码进机器，等对方接口定稿时反而要拆。</p>
     */
    @Override
    public boolean meGroupParallelItemInputs() {
        return true;
    }
}
