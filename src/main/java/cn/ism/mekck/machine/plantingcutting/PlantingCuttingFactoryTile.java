package cn.ism.mekck.machine.plantingcutting;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.block.PlantingCuttingFactoryBlock;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.machine.MekCkFactoryType;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.machine.ports.IMekCkPorted;
import cn.ism.mekck.recipe.PlantingCuttingRecipe;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import cn.ism.mekck.util.UpgradeHelper;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.Upgrade;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.holder.chemical.IChemicalTankHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.api.chemical.ChemicalTankBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 种植切配工厂的机器 —— 并行方阵 + 营养液气体 + 生长方块槽 + 1×2×1 多方块。
 *
 * <h3>与切菜 / 研磨 tile 的差别</h3>
 * 槽位形态、能力结构、闸门口径都与那两个<b>逐行同构</b>，本类只加四样家族特有的东西：
 * <ol>
 *   <li><b>营养液气体罐</b>：{@link #getInitialGasTanks} 是 {@code IGasTile} 的 default 方法，
 *       {@code TileEntityMekanism} 已经 implements 它，覆写即可，<b>不需要 Mixin</b>。</li>
 *   <li><b>两个额外物品槽</b>：营养液容器槽 + 生长方块槽，走基类的
 *       {@link MekCkMachineTile#appendExtraSlots} 钩子追加在方阵与能量槽之后，
 *       <b>方阵本身不动</b>——所以切菜与研磨一行都不用改。</li>
 *   <li><b>生长方块判定</b>：神秘农业种子要求槽里放对等级的土。</li>
 *   <li><b>气体消耗倍率</b>：随档位递减（见 {@link #getGasConsumptionMultiplier}）。</li>
 * </ol>
 */
public class PlantingCuttingFactoryTile extends MekCkMachineTile implements IMekCkPorted {

    /** 一个批次的基础耗时（tick）。与旧 {@code PROCESS_TIME} 同值。 */
    public static final int PROCESS_TIME = 200;
    /** 单批次的基准能耗。与旧 {@code ENERGY_PER_PROCESS} 同值。 */
    public static final int ENERGY_PER_PROCESS = 20;

    /** 每并行格对应的营养液罐容量 mB。逐字取自旧实现的同名常量。 */
    public static final long NUTRIENT_TANK_MB_PER_PROCESS = 96_000;
    /** 营养液气体的注册名：来自 mekmm（Mekanism More Machine），不是 MekCK 自己的。 */
    public static final String NUTRIENT_GAS_NAMESPACE = "mekmm";
    public static final String NUTRIENT_GAS_ID = "nutrient_solution";

    /** 额外槽在 GUI 里的坐标，接在方阵与能量槽之后。 */
    private static final int EXTRA_SLOT_X = 8;
    private static final int EXTRA_SLOT_Y = 55;
    private static final int EXTRA_SLOT_STEP = 18;

    private IInventorySlot nutrientSlot;
    private IInventorySlot growthSlot;

    private Gas nutrientGas;
    private IGasTank nutrientTank;

    public PlantingCuttingFactoryTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
    }

    @Override
    protected MekCkRecipeExecutor createExecutor() {
        return new PlantingCuttingFactoryExecutor();
    }

    @Override
    protected CuttingMachineFactoryTier tierFromBlock() {
        if (blockProvider != null && blockProvider.getBlock() instanceof PlantingCuttingFactoryBlock block) {
            return block.getTier();
        }
        return null;
    }

    @Override
    protected MekCkFactoryType typeFromBlock() {
        return MekCkFactoryType.PLANTING_CUTTING;
    }

    // ── 营养液气体 ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>{@code IGasTile#getInitialGasTanks} 是接口上的 default 方法，
     * {@code TileEntityMekanism} 已经 implements {@code IGasTile} 并持有一个
     * {@code gasHandlerManager}——它就是从本方法取罐的。覆写本方法即可把营养液罐
     * 接进 Mek 的能力解析链，<b>不需要 Mixin</b>（ Mek 自己的
     * {@code TileEntityItemStackGasToItemStackFactory} 用的是同一个钩子）。
     *
     * <p>mekmm 未安装时 {@link #resolveNutrientGas()} 返回 {@code null}，
     * 此时返回空持有者：机器正常通电，只是永远拿不到营养液因而不出货——
     * 与旧实现「nutrientGas 为 null 则不建罐」同口径，不抛异常也不打日志
     * （未安装是常态，不是故障）。
     */
    @Override
    public IChemicalTankHolder<Gas, GasStack, IGasTank> getInitialGasTanks(IContentsListener listener) {
        this.nutrientGas = resolveNutrientGas();
        if (nutrientGas == null) {
            return new EmptyGasTankHolder();
        }
        CuttingMachineFactoryTier tier = getTier();
        long capacity = tier == null ? NUTRIENT_TANK_MB_PER_PROCESS
                : (long) tier.processes * NUTRIENT_TANK_MB_PER_PROCESS;
        this.nutrientTank = ChemicalTankBuilder.GAS.create(
                capacity, gas -> nutrientGas == gas, listener);
        return new SingleGasTankHolder(nutrientTank);
    }

    /**
     * 从 mekmm 注册表解析营养液气体。
     *
     * <p>逐字取自旧 {@code PlantingCuttingFactoryBlockEntity.resolveNutrientGas()}：
     * 查不到、或查到的是 {@code EMPTY_GAS}，都按「未安装」处理。
     */
    public static Gas resolveNutrientGas() {
        try {
            Gas gas = mekanism.api.MekanismAPI.gasRegistry().getValue(
                    new net.minecraft.resources.ResourceLocation(NUTRIENT_GAS_NAMESPACE, NUTRIENT_GAS_ID));
            return gas == null || gas == mekanism.api.MekanismAPI.EMPTY_GAS ? null : gas;
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前营养液罐；mekmm 未安装时为 {@code null}。 */
    public IGasTank getNutrientTank() {
        return this.nutrientTank;
    }

    /** 罐里够不够本次批次用。罐不存在（mekmm 未装）时恒 false。 */
    public boolean hasNutrient(long millibuckets) {
        return nutrientTank != null && millibuckets > 0 && nutrientTank.getStored() >= millibuckets;
    }

    /**
     * 抽取营养液。
     *
     * <p>用 {@link AutomationType#MANUAL} 而不是外部类型：机械自己吃原料是内部行为，
     * 标成 EXTERNAL 会被罐的 {@code notExternal} 谓词整条拒掉（这与
     * {@code MachineEnergyContainer} 的坑同源，见阶段 2 的能量抽取修复）。
     */
    public void consumeNutrient(long millibuckets) {
        if (nutrientTank == null || millibuckets <= 0) {
            return;
        }
        nutrientTank.extract(millibuckets, Action.EXECUTE, AutomationType.MANUAL);
    }

    /**
     * 营养液消耗倍率（逐字取自旧 {@code getGasConsumptionMultiplier()}）。
     *
     * <p>阶梯：BLAZE 0.1（省 90%）、CRYSTAL_MATRIX 0.05、NEBULA 0.01、
     * SINGULARITY 0.0；BLAZE 之前按是否装了气体升级给 0.1 或 1.0。
     */
    public double getGasConsumptionMultiplier() {
        CuttingMachineFactoryTier tier = getTier();
        if (tier == null) {
            return 1.0;
        }
        if (tier.ordinal() >= CuttingMachineFactoryTier.BLAZE.ordinal()) {
            return switch (tier) {
                case BLAZE -> 0.1;
                case CRYSTAL_MATRIX -> 0.05;
                case NEBULA -> 0.01;
                case SINGULARITY -> 0.0;
                default -> 0.0;
            };
        }
        return hasGasUpgradeItem() ? 0.1 : 1.0;
    }

    /**
     * 气体升级槽是否装了东西。
     *
     * <p>旧实现读的是自研升级槽（{@code gasUpgradeSlot}）。新基类把升级统一交给
     * Mek 的 {@code TileComponentUpgrade}，所以这里改读 {@link Upgrade#GAS}。
     * 语义一致：装了气体升级就按 0.1 倍率走。
     */
    private boolean hasGasUpgradeItem() {
        return installedUpgrades(Upgrade.GAS) > 0;
    }

    // ── 生长方块 ────────────────────────────────────────────────────────

    /**
     * 该配方是否满足生长土要求（配方没要求 ⇒ 恒 true）。
     *
     * <p>逐字取自旧 {@code hasValidGrowthSoil}：判定走生成器写进配方的
     * {@code getGrowthSoils()} 白名单，运行时<b>不反射 BotanyPots</b>。
     */
    public boolean hasValidGrowthSoil(PlantingCuttingRecipe recipe) {
        if (recipe == null || !recipe.requiresGrowthSoil()) {
            return true;
        }
        return growthSlot != null && recipe.getGrowthSoils().test(growthSlot.getStack());
    }

    /** 生长方块槽的当前物品（GUI 显示用）。 */
    public ItemStack getGrowthSoilStack() {
        return growthSlot == null ? ItemStack.EMPTY : growthSlot.getStack();
    }

    /**
     * 生长方块格状态（GUI 提示用）。
     *
     * <p>与 {@code PlantingCuttingStationBlockEntity} 共用同一套常量
     * （{@code GROWTH_OK} / {@code GROWTH_MISSING} / {@code GROWTH_TOO_LOW}）。
     */
    public int getGrowthStatus(PlantingCuttingRecipe recipe) {
        if (recipe == null || !recipe.requiresGrowthSoil()) {
            return PlantingCuttingStationBlockEntity.GROWTH_OK;
        }
        if (growthSlot == null || growthSlot.getStack().isEmpty()) {
            return PlantingCuttingStationBlockEntity.GROWTH_MISSING;
        }
        return recipe.getGrowthSoils().test(growthSlot.getStack())
                ? PlantingCuttingStationBlockEntity.GROWTH_OK
                : PlantingCuttingStationBlockEntity.GROWTH_TOO_LOW;
    }

    // ── 额外槽 ──────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>追加「营养液容器槽」与「生长方块槽」两格，接在方阵与能量槽之后，
     * 与旧实现的槽位顺序（{@code 2N 之后是营养液槽，再之后是升级槽，最后是能源槽）
     * 一致——升级槽与能源槽现在由基类统一提供，所以只剩这两格要补。
     */
    @Override
    protected void appendExtraSlots(InventorySlotHelper builder, IContentsListener listener) {
        nutrientSlot = MekCkSlot.input(
                slotLimitPerSlot(getTier()), listener, EXTRA_SLOT_X, EXTRA_SLOT_Y);
        builder.addSlot(nutrientSlot);
        growthSlot = MekCkSlot.input(
                slotLimitPerSlot(getTier()), listener, EXTRA_SLOT_X + EXTRA_SLOT_STEP, EXTRA_SLOT_Y);
        builder.addSlot(growthSlot);
    }

    /** 营养液容器槽（GUI 用）。 */
    public IInventorySlot getNutrientSlot() {
        return nutrientSlot;
    }

    /** 生长方块槽（GUI 用）。 */
    public IInventorySlot getGrowthSlot() {
        return growthSlot;
    }

    // ── 能量闸门参数 ────────────────────────────────────────────────────

    @Override
    protected int ticksPerWorkCycle() {
        return Math.max(1, (int) (PROCESS_TIME / effectiveSpeedMultiplier()));
    }

    @Override
    protected int energyPerWorkTick() {
        CuttingMachineFactoryTier tier = getTier();
        if (tier == null || tier.energyPerTick == 0) {
            return 0;
        }
        int active = activeWorkSlots();
        if (active <= 0) {
            return 0;
        }
        double speedMult = effectiveSpeedMultiplier();
        double consumptionMult = effectiveEnergyConsumptionMultiplier();
        int base = (int) Math.ceil(ENERGY_PER_PROCESS * speedMult * speedMult * consumptionMult
                * MekckConfig.getTierEnergyEfficiency(tier));
        return cn.ism.mekck.util.CountMath.mulClamp(Integer.MAX_VALUE, base, active, stackMultiplier());
    }

    public double effectiveSpeedMultiplier() {
        return UpgradeHelper.speedMultiplier(installedUpgrades(Upgrade.SPEED));
    }

    public double effectiveEnergyConsumptionMultiplier() {
        return UpgradeHelper.energyConsumptionMultiplier(installedUpgrades(Upgrade.ENERGY));
    }

    public int stackMultiplier() {
        CuttingMachineFactoryTier tier = getTier();
        if (tier == null) {
            return 1;
        }
        int base = MekckConfig.getMultithreadedBase(tier);
        int maxParallel = MekckConfig.getMultithreadedMax(tier);
        int installed = installedUpgrades(MekCkUpgradeRefs.storage());
        int cap = MekCkUpgradeTypes.capOf(MekCkUpgradeRefs.storage(), tier);
        return MekCkUpgradeTypes.stackMultiplier(installed, cap, base, maxParallel);
    }

    // ── IMekCkPorted ────────────────────────────────────────────────────

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
     * <p>营养液槽与生长土槽<b>只手动</b>：AE2 不该往营养液槽投普通物品，
     * 也不该从生长土槽取走土。</p>
     */
    @Override
    public List<IInventorySlot> meManualOnlyItemSlots() {
        List<IInventorySlot> out = new java.util.ArrayList<>(2);
        if (nutrientSlot != null) {
            out.add(nutrientSlot);
        }
        if (growthSlot != null) {
            out.add(growthSlot);
        }
        return out;
    }

    @Override
    public boolean meGroupParallelItemInputs() {
        return true;
    }

    // ── mekmm 未安装时的空持有者 ─────────────────────────────────────────

    /** 没有气体罐时的空持有者：所有方向都返回空列表，不抛异常。 */
    private static final class EmptyGasTankHolder
            implements IChemicalTankHolder<Gas, GasStack, IGasTank> {
        @Override
        public List<IGasTank> getTanks(net.minecraft.core.Direction side) {
            return List.of();
        }
    }

    /** 单罐持有者：所有方向都指向同一个罐（1×2×1 多方块的上下两个块共用）。 */
    private static final class SingleGasTankHolder
            implements IChemicalTankHolder<Gas, GasStack, IGasTank> {
        private final IGasTank tank;

        SingleGasTankHolder(IGasTank tank) {
            this.tank = tank;
        }

        @Override
        public List<IGasTank> getTanks(net.minecraft.core.Direction side) {
            return List.of(tank);
        }
    }

    /** 便于测试与 GUI：当前罐里的 mB 数（夹到 int 上界）。 */
    public int getNutrientCount() {
        return nutrientTank == null ? 0
                : (int) Math.min(Integer.MAX_VALUE, nutrientTank.getStored());
    }
}
