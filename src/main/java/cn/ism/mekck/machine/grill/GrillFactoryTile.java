package cn.ism.mekck.machine.grill;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.block.GrillFactoryBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.machine.MekCkFactoryType;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.machine.ports.IMekCkPorted;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import cn.ism.mekck.util.UpgradeHelper;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 烧烤工厂的机器 —— 并行方阵 + 3 个调味料槽。
 *
 * <h3>与切菜 / 研磨 / 种植切配 tile 的差别</h3>
 * 能量闸门、端口声明、速率倍率全部<b>逐行同构</b>，本类只加一样东西：
 * **3 个调味料槽**，走基类的 {@link MekCkMachineTile#appendExtraSlots} 追加在方阵之后。
 *
 * <p>调味料的语义（启用开关、默认模式下挑剩余次数最多的、工作模式、订单指定）
 * 全在 {@link GrillFactoryExecutor} 里——它们是配方侧的决策，不是机器侧的能力。
 */
public class GrillFactoryTile extends MekCkMachineTile implements IMekCkPorted {

    /** 一个批次的基础耗时（tick）。与旧 {@code BASE_PROCESS_TIME} 同值。 */
    public static final int PROCESS_TIME = 200;
    /** 单批次的基准能耗。与旧 {@code BASE_ENERGY_PER_TICK} 同值。 */
    public static final int ENERGY_PER_PROCESS = 20;

    /** 调味料槽数。与旧实现同名常量同值。 */
    public static final int SEASONING_SLOTS = GrillFactoryExecutor.SEASONING_SLOTS;

    private static final int SEASONING_SLOT_X = 8;
    private static final int SEASONING_SLOT_Y = 55;
    private static final int SEASONING_SLOT_STEP = 18;

    /**
     * 3 个调味料槽。
     *
     * <p><b>不能写成 {@code = new ArrayList<>(...)} 字段初始化器</b>：本列表由
     * {@link #appendExtraSlots} 填充，而该方法在 <b>父类构造器内部</b>经
     * {@code getInitialInventory} 回调（见 {@code MekCkMachineTile} 类注释的构造期顺序陷阱），
     * 那一刻本类的字段初始化器<b>一个都还没跑</b>。字段初始化器写在这里、方法里再
     * {@code clear()}，就会在构造期对 {@code null} 调 {@code clear()} 抛 NPE ——
     * 症状是「这台方块放下去就建不出方块实体」。所以由 {@link #appendExtraSlots}
     * 自己负责 {@code new}（并保留重复调用时的 {@code clear}）。</p>
     */
    private List<IInventorySlot> seasoningSlots;

    public GrillFactoryTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
    }

    @Override
    protected MekCkRecipeExecutor createExecutor() {
        return new GrillFactoryExecutor();
    }

    @Override
    protected CuttingMachineFactoryTier tierFromBlock() {
        if (blockProvider != null && blockProvider.getBlock() instanceof GrillFactoryBlock block) {
            return block.getTier();
        }
        return null;
    }

    @Override
    protected MekCkFactoryType typeFromBlock() {
        return MekCkFactoryType.GRILLING;
    }

    // ── 调味料槽 ────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>追加 3 个调味料槽，接在方阵与能量槽之后。旧实现的槽位顺序是
     * {@code 2N 之后是调味料、然后才是升级槽与能源槽}；升级与能源现在由基类统一提供，
     * 所以只剩这 3 格要补。</p>
     */
    @Override
    protected void appendExtraSlots(InventorySlotHelper builder, IContentsListener listener) {
        // 见字段注释：本方法跑在父类构造器内部，此刻字段初始化器还没执行。
        // 这里 new 而不是 clear()，重复调用（理论上不会发生）时才走 clear。
        if (seasoningSlots == null) {
            seasoningSlots = new ArrayList<>(SEASONING_SLOTS);
        } else {
            seasoningSlots.clear();
        }
        // 位置统一由 MekCkFactoryLayout 决定（单一出处）：左边缘一列 x=8，纵向排列。
        // 之前这里是「一行式横排 / 方阵竖排」两套算法，而屏幕侧的开关用的是第三套坐标，
        // 三者对不上。现在 tile 与屏幕都读同一份几何。
        boolean oneRow = usesOneRowLayout();
        for (int i = 0; i < SEASONING_SLOTS; i++) {
            IInventorySlot slot = MekCkSlot.input(slotLimitPerSlot(getTier()), listener,
                    cn.ism.mekck.menu.MekCkFactoryLayout.EXTRA_SLOT_X, cn.ism.mekck.menu.MekCkFactoryLayout.extraSlotY(i, oneRow));
            seasoningSlots.add(slot);
            builder.addSlot(slot);
        }
    }

    /**
     * 3 个调味料槽登记为 {@code DataType.EXTRA}（基类注释解释了不登记的两条后果：
     * 贴图一律退化成 {@code normal.png}、侧配 GUI 里看不见）。
     */
    @Override
    protected List<IInventorySlot> extraSlotsForConfig() {
        return seasoningSlots == null ? List.of() : seasoningSlots;
    }

    /**
     * 第 {@code index} 个调味料槽的物品。
     *
     * <p>槽不存在时返回空栈——执行器据此「跳过该槽」，而不是抛 NPE。
     * 槽不存在只可能发生在构造期没跑到 {@code appendExtraSlots}，
     * 那时执行器也还没开始 tick。</p>
     */
    public ItemStack getSeasoningSlotStack(int index) {
        if (index < 0 || index >= seasoningSlots.size()) {
            return ItemStack.EMPTY;
        }
        return seasoningSlots.get(index).getStack();
    }

    /** 写回第 {@code index} 个调味料槽（消耗耐久后调用）。 */
    public void writeSeasoningSlot(int index, ItemStack stack) {
        if (index >= 0 && index < seasoningSlots.size()) {
            seasoningSlots.get(index).setStack(stack);
        }
    }

    /**
     * 清空第 {@code index} 个调味料槽。
     *
     * <p>用清空而不是「写一个空栈」：耐久耗尽的调味料物品在
     * {@code BarbequesDelightCompat} 的口径下不可用，留一个空壳只会让
     * 「剩余次数」的算法读到 {@code maxDamage - 0} 而误判为还有满耐久。</p>
     */
    public void clearSeasoningSlot(int index) {
        if (index >= 0 && index < seasoningSlots.size()) {
            seasoningSlots.get(index).setStack(ItemStack.EMPTY);
        }
    }

    // ── 调味料启用开关（常驻状态，不属于订单） ─────────────────────────

    /**
     * 第 {@code index} 个调味料槽是否启用自动调味。
     *
     * <p>客户端可读：{@code saveAdditional} 会被 {@code getUpdateTag} 复用，
     * 执行器的位标志随方块更新包同步到客户端，界面因此不需要额外的
     * {@code ContainerData} 同步整数。</p>
     */
    public boolean isSeasoningEnabled(int index) {
        return executor() instanceof GrillFactoryExecutor grill && grill.isSeasoningEnabled(index);
    }

    /** 切换第 {@code index} 个调味料槽的启用状态（只服务端调用，由网络包驱动）。 */
    public void toggleSeasoningEnabled(int index) {
        if (executor() instanceof GrillFactoryExecutor grill) {
            grill.toggleSeasoningEnabled(index);
        }
    }

    // ── 订单与工作模式 ──────────────────────────────────────────────────

    /**
     * 下单：把配方、数量与调味料写进执行器的订单状态。
     *
     * <p>订单<b>引擎</b>随旧 BE 一起迁到了 {@link GrillFactoryExecutor}，
     * 这里只是把 GUI 侧的网络包接到它上面。</p>
     */
    public void setOrder(ResourceLocation recipeId, int quantity, String seasoningId) {
        if (executor() instanceof GrillFactoryExecutor grill) {
            grill.setOrder(recipeId, quantity, seasoningId);
        }
    }

    /** 取消订单。 */
    public void clearOrder() {
        if (executor() instanceof GrillFactoryExecutor grill) {
            grill.clearOrder();
        }
    }

    /** 切换工作模式：默认（自动挑已启用的调味料）↔ 订单（只按订单指定的调味）。 */
    public void toggleWorkMode() {
        if (executor() instanceof GrillFactoryExecutor grill) {
            grill.toggleWorkMode();
        }
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
        // 与切菜/研磨的 baseEnergyPerTick 同一个夹紧口径：ceil 之后再夹到 [0, int 上界]，
        // 免得「配置被调成天文数字/负数」这种输入在整数转换处产生与另两个家族不同的行为。
        int base = (int) Math.min(Integer.MAX_VALUE, Math.max(0.0,
                Math.ceil(ENERGY_PER_PROCESS * speedMult * speedMult * consumptionMult
                        * MekckConfig.getTierEnergyEfficiency(tier))));
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
     * <p>3 个调味料槽<b>只手动</b>：AE2 不该往里投普通物品，也不该从里取走
     * 已用掉耐久的调味料。</p>
     */
    @Override
    public List<IInventorySlot> meManualOnlyItemSlots() {
        return List.copyOf(seasoningSlots);
    }

    @Override
    public boolean meGroupParallelItemInputs() {
        return true;
    }
}
