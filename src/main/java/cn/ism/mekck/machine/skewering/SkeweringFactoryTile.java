package cn.ism.mekck.machine.skewering;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.machine.IFactoryTierProvider;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.machine.MekCkFactoryType;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.machine.ports.IMekCkPorted;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.util.CountMath;
import cn.ism.mekck.upgrade.UpgradeHelper;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 穿串工厂的机器 —— <b>第一个「固定输入 + 存储缓冲」形态的家族</b>（阶段 3 Task 5）。
 *
 * <h3>槽位形态：为什么基类需要那三个钩子</h3>
 * 并行方阵家族的输入 = 输出 = ⌈√N⌉ 方阵 ×N；穿串是
 * <b>3 个输入排成一行、2 个输出竖成一列（产物 + 返还）、外加 81 格存储</b>，
 * 且这三组数字<b>都不随等级变</b>。所以覆写基类的
 * {@link #inputSlotCount} / {@link #inputSlotColumns} /
 * {@link #outputSlotCount} / {@link #outputSlotColumns} 四个钩子，
 * 而不是整体重写 {@code getInitialInventory}——
 * 理由与 {@code appendExtraSlots} 相同：已迁的四个家族必须一行都不用改。
 *
 * <h3>两条「已知坑」<b>原样保留</b>，改它们是行为变更不是迁移</h3>
 * <ol>
 *   <li><b>无订单绝不加工</b>。{@code hasWorkToDo()} 被覆写成「有订单」，
 *       覆盖基类「有非空输入槽」的默认——否则材料摆满会推着进度条走空批次。</li>
 *   <li><b>{@code processingTime} 被完全忽略</b>。配方里解析了它（数据里 Beef 写 100），
 *       机器只用 {@code 200 / 速度倍率}，从不读 {@code getProcessTime()}。</li>
 * </ol>
 *
 * <h3>返还语义（M29 修复，不再是坑）</h3>
 * 返还槽只落<b>本次实际消耗的签子</b>（类型与数量）：扣料位置无关（3 输入槽 + 81 存储槽），
 * 返还按扣料记录落槽。此前返还复制「输入槽 0 的整叠 × 批量」，签子不在槽 0 时复制的是
 * 槽 0 里<b>另一种物品</b>（物品复制）；M24 只关掉了 toolCount=0 那一路，
 * M29 修掉 toolCount&gt;0 的错配。旧实现第 904-910 行同款。
 *
 * <h3>81 格存储搬到了 {@code appendExtraSlots}</h3>
 * 它<b>不进</b> {@code configComponent.setupItemIOConfig}（基类只登记
 * input / output / energySlot），所以存储槽<b>没有侧配</b>——旧实现那个
 * 「抽取(存储)」的独立侧配模式没有了。管道插得进、抽不出
 * （{@code mePatternItemInputs} 声明它们可被 AE2 投递，但 {@code meManualOnlyItemSlots}
 * 之外的自配侧 Mek 只认它登记过的那三组）。这条差异记在这里，别当成 bug 顺手「修」掉。
 */
public class SkeweringFactoryTile extends MekCkMachineTile implements IMekCkPorted {

    /** 输入槽数：签 / 主料 / 辅料。编译期常量，与等级无关。 */
    public static final int INPUT_SLOTS = 3;
    /** 存储槽数。编译期常量，与等级无关。 */
    public static final int STORAGE_SLOTS = 81;
    /** 一个批次的基础耗时（tick）。与旧 {@code BASE_PROCESS_TIME} 同值。 */
    public static final int PROCESS_TIME = 200;
    /** 单批次的基准能耗。与旧 {@code BASE_ENERGY_PER_TICK} 同值。 */
    public static final int ENERGY_PER_PROCESS = 20;

    /**
     * 存储区的布局常量，与旧 GUI 逐像素同值。
     *
     * <p>左块 42 格（index 0..41）挂在面板<b>左侧</b>（x 为负），
     * 右块 39 格挂在<b>右侧</b>（x 超过面板宽）。旧 GUI 高 244、宽 176，
     * 在 480×270 缩放下 81 格挂不下面板内——这是 2026 年那次「GUI 放不下」
     * 故障后的布局，本轮沿用，不重新设计。</p>
     */
    private static final int STORAGE_COLS = 7;
    private static final int STORAGE_LEFT_COUNT = 42;
    private static final int STORAGE_X_OFFSET = -(STORAGE_COLS * 18) - 4;
    private static final int STORAGE_Y = 62;
    private static final int STORAGE_RIGHT_X = 176 + 4;

    /**
     * 81 格存储。
     *
     * <p><b>不能写成 {@code = new ArrayList<>(...)} 字段初始化器</b>：本列表由
     * {@link #appendExtraSlots} 填充，而它在<b>父类构造器内部</b>经 {@code getInitialInventory}
     * 回调（见 {@code MekCkMachineTile} 类注释的构造期顺序陷阱），那一刻本类的字段初始化器
     * 还没跑。写成字段初始化器 + 方法里 {@code clear()} 会在构造期对 {@code null} 调
     * {@code clear()} 抛 NPE，症状是「方块放下去建不出方块实体」。</p>
     */
    private List<IInventorySlot> storageSlots;

    public SkeweringFactoryTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
    }

    @Override
    protected MekCkRecipeExecutor createExecutor() {
        return new SkeweringFactoryExecutor();
    }

    @Override
    protected CuttingMachineFactoryTier tierFromBlock() {
        if (blockProvider != null && blockProvider.getBlock() instanceof IFactoryTierProvider block) {
            return block.getTier();
        }
        return null;
    }

    @Override
    protected MekCkFactoryType typeFromBlock() {
        return MekCkFactoryType.SKEWERING;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>穿串工厂不提供自动分选</b>：本机是整机批次操作（{@code processCount} 恒 1），
     * 没有「多路并行」可分；而配方匹配位置无关（材料放哪个输入槽都能开工），
     * 分选只会把材料在等价位置之间搬来搬去，没有收益。</p>
     *
     * <p>历史注记：M29 之前返还槽复制「输入槽 0 的整叠」，分选会把签子挪出槽 0、
     * 让错配从「可达」变成「常态」；M29 把返还改成「按实际消耗的签子记录落槽」后，
     * 这条危险不再存在，但上面的「无收益」理由仍然成立。</p>
     */
    @Override
    public boolean supportsSorting() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>退出旧存档迁移：本机的槽位排布（3 输入 / 2 输出 / 81 存储 / 4 升级卡）
     * 与 {@link MekCkLegacyMachineNbt} 假设的并行方阵排布不同，硬套会把
     * 速度升级卡灌进能源槽。理由与逐条下标见基类该方法的注释。</p>
     */
    @Override
    protected boolean migratesLegacyNbt() {
        return false;
    }

    // ── 槽位布局（阶段 3 Task 4 的四个钩子）─────────────────────────────

    @Override
    protected int inputSlotCount(CuttingMachineFactoryTier tier) {
        return INPUT_SLOTS;
    }

    @Override
    protected int inputSlotColumns(int count) {
        // 一行三格，与旧 GUI 的 x = 38 + (i%3)*18, y = 41 逐像素一致。
        return INPUT_SLOTS;
    }

    @Override
    protected int outputSlotCount(CuttingMachineFactoryTier tier) {
        // 产物槽 + 返还槽，竖着叠一列。
        return 2;
    }

    @Override
    protected int outputSlotColumns(int count) {
        return 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>追加 81 格存储：左块 42 格 + 右块 39 格，坐标与旧 GUI 同款。
     * 用 {@code MekCkSlot.input} 而不是 output —— 存储是<b>入料</b>用的，
     * 侧配与 AE2 都把它当输入面看待（见类注释的侧配差异说明）。</p>
     */
    @Override
    protected void appendExtraSlots(InventorySlotHelper builder, IContentsListener listener) {
        // 见字段注释：本方法跑在父类构造器内部，此刻字段初始化器还没执行。
        if (storageSlots == null) {
            storageSlots = new ArrayList<>(STORAGE_SLOTS);
        } else {
            storageSlots.clear();
        }
        int limit = slotLimitPerSlot(getTier());
        for (int i = 0; i < STORAGE_SLOTS; i++) {
            int x;
            if (i < STORAGE_LEFT_COUNT) {
                x = STORAGE_X_OFFSET + (i % STORAGE_COLS) * 18;
            } else {
                int j = i - STORAGE_LEFT_COUNT;
                x = STORAGE_RIGHT_X + (j % STORAGE_COLS) * 18;
            }
            int y = STORAGE_Y + (i / STORAGE_COLS) * 18;
            // 存储槽走「悬浮窗虚拟槽」：主面板不再放这 81 格，改由 GuiStorageWindow 分页显示。
            // x/y 保留仅为可读性（VirtualInventoryContainerSlot 的容器槽坐标恒为 0,0，
            // 实际渲染位置由 GuiVirtualSlot 动态写入，见 MekCkSlot#storage 的注释）。
            IInventorySlot slot = MekCkSlot.storage(limit, SLOT_WINDOW, listener, x, y);
            storageSlots.add(slot);
            builder.addSlot(slot);
        }
    }

    /**
     * <b>刻意返回空</b>：81 格存储是<b>悬浮窗虚拟槽</b>，不登记为 {@code DataType.EXTRA}。
     *
     * <p>理由与 {@code CookingFactoryTile#extraSlotsForConfig()} 逐字相同：登记会让
     * {@code GuiMekanism.addSlots()} 给每个虚拟槽建一个 {@code GuiSlot(SlotType.EXTRA)}，
     * 而虚拟槽坐标恒为 {@code (0,0)} ⇒ 81 个槽框叠在面板左上角 {@code (-1,-1)}；
     * 不登记则 {@code ContainerSlotType.IGNORED} 让它们被跳过，正是「收进悬浮窗」的效果。</p>
     */
    @Override
    protected List<IInventorySlot> extraSlotsForConfig() {
        return List.of();
    }

    // ── 给执行器与界面的读取口 ──────────────────────────────────────────

    /** 存储槽列表（活列表，构造完成后由本类独占维护）。 */
    public List<IInventorySlot> getStorageSlots() {
        return storageSlots;
    }

    /**
     * 配料扫描集合：3 个输入槽 + 全部存储槽。
     *
     * <p>刻意包含存储槽——旧实现的匹配位置无关，材料放存储里一样能开工。
     * 每 tick 新建一个列表是这台机器唯一的分配点；批次间隔是 {@code 200/speed}，
     * 远小于前四个家族「每 tick × 每槽」的密度。</p>
     */
    public List<IInventorySlot> ingredientSlots() {
        List<IInventorySlot> scan = new ArrayList<>(getInputSlots().size() + storageSlots.size());
        scan.addAll(getInputSlots());
        scan.addAll(storageSlots);
        return scan;
    }

    /** 产物槽（{@code getOutputSlots().get(0)} 的语义别名）。 */
    public IInventorySlot productSlot() {
        List<IInventorySlot> outputs = getOutputSlots();
        return outputs.isEmpty() ? null : outputs.get(0);
    }

    /** 返还槽（签子复制回去的那个）。 */
    public IInventorySlot returnSlot() {
        List<IInventorySlot> outputs = getOutputSlots();
        return outputs.size() < 2 ? null : outputs.get(1);
    }

    private SkeweringFactoryExecutor skewering() {
        return executor() instanceof SkeweringFactoryExecutor s ? s : null;
    }

    // ── 订单转发（网络包与界面走这几个方法）─────────────────────────────

    public boolean hasOrder() {
        return super.hasOrder();
    }

    public void setOrder(ResourceLocation recipeId, int quantity) {
        SkeweringFactoryExecutor exec = skewering();
        if (exec != null) {
            exec.setOrder(recipeId, quantity);
        }
    }

    public void setCustomOrder(List<String> itemIds, int quantity) {
        SkeweringFactoryExecutor exec = skewering();
        if (exec != null) {
            exec.setCustomOrder(itemIds, quantity);
        }
    }

    public void clearOrder() {
        SkeweringFactoryExecutor exec = skewering();
        if (exec != null) {
            exec.clearOrder();
        }
    }

    public int getOrderQuantity() {
        return super.getOrderQuantity();
    }

    public int getOrderCompleted() {
        return super.getOrderCompleted();
    }

    // ── 能量闸门参数 ────────────────────────────────────────────────────

    @Override
    protected int ticksPerWorkCycle() {
        return Math.max(1, (int) (PROCESS_TIME / effectiveSpeedMultiplier()));
    }

    /**
     * 本 tick 的耗电。
     *
     * <p>与并行家族的差别：<b>没有「非空输入槽数」这个乘数</b>。
     * 并行家族是「N 个槽各跑各的、每跑一个收一份电」，穿串是
     * 「三个槽一起做一个批次」——按非空槽数乘等于「材料越全电费越贵」，
     * 而旧实现的公式里根本没有这一项（逐字对齐第 478 行）。</p>
     */
    @Override
    protected int energyPerLanePerTick() {
        CuttingMachineFactoryTier tier = getTier();
        if (tier == null || tier.energyPerTick == 0) {
            return 0;
        }
        double speedMult = effectiveSpeedMultiplier();
        double consumptionMult = effectiveEnergyConsumptionMultiplier();
        int base = (int) Math.ceil(ENERGY_PER_PROCESS * speedMult * speedMult * consumptionMult
                * MekckConfig.getTierEnergyEfficiency(tier));
        return CountMath.mulClamp(Integer.MAX_VALUE, base, stackMultiplier());
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>本机的「在干活」槽数按 {@link #ingredientSlots()}（3 个输入槽 + 81 格存储）
     * 数，不是基类的「非空输入槽」</b>：存储区才是本机真正的料仓，旧实现的
     * {@code getMaxConsumableCount} 就是「先扫输入槽、再扫存储区」。只数输入槽会让
     * 「材料全放在存储区」的机器永不启动 —— 而执行器明明扫得到那些料
     * （{@code canProcess} 用的就是 {@code ingredientSlots()}）。</p>
     */
    @Override
    protected int activeWorkSlots() {
        return countNonEmpty(ingredientSlots());
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>本机的判据是「有订单 <u>且</u> 配料槽里有料」</b>，而不是基类的「有非空输入槽」，
     * 也不是单纯的「有订单」：</p>
     * <ul>
     *   <li>不带订单判据时，材料摆满就会推着进度条走空批次（本机无订单绝不加工，
     *       见类注释的已知坑第 2 条）；</li>
     *   <li>只带订单判据时，<b>下了单却没投料</b>的机器会照常走满一个周期并按 tick 扣电，
     *       而执行器 {@code batch <= 0} 直接返回、什么也不做。旧实现不是这样：
     *       {@code SkeweringFactoryBlockEntity.serverTick} 先算 {@code canProcess}
     *       （有配方 + {@code maxConsumable > 0} + 产物装得下），再
     *       {@code energyPerTick = canProcess ? mulClamp(...) : 0}（旧文件第 478 行），
     *       第 486 行才 {@code extractEnergy}。缺料时旧机器一滴电都不扣。</li>
     * </ul>
     *
     * <p>「有料」由覆写后的 {@link #activeWorkSlots()} 给出 —— 它数的是
     * {@link #ingredientSlots()}（输入 + 存储），见该方法的注释。</p>
     *
     * <p>更细的「有料但都不是订单要的那张配方 / 产物槽满」由执行器的 {@code canProcess}
     * 逐 tick 判定：{@code workCycle} 对每一路先问它，判 false 的那一路进度清零、
     * <b>不扣电</b>，并点亮该路的告警位。</p>
     */
    @Override
    protected boolean hasWorkToDo() {
        return hasOrder() && activeWorkSlots() > 0;
    }

    public double effectiveSpeedMultiplier() {
        return UpgradeHelper.speedMultiplier(installedUpgrades(Upgrade.SPEED));
    }

    public double effectiveEnergyConsumptionMultiplier() {
        return UpgradeHelper.energyConsumptionMultiplier(installedUpgrades(Upgrade.ENERGY));
    }

    /**
     * 一个周期做多少个串（受存储卡倍增）。
     *
     * <p>用 {@code non-multithreaded} 基数表而不是并行家族的 multithreaded 表
     * ——逐字对齐旧实现第 1196-1206 行：穿串的基数本身按等级指数上升，
     * 再乘 2^卡数 才不会在高档位把批次撑到荒谬。</p>
     */
    public int stackMultiplier() {
        CuttingMachineFactoryTier tier = getTier();
        if (tier == null) {
            return 1;
        }
        int base = MekckConfig.getNonMultithreadedBase(tier);
        int maxParallel = MekckConfig.getNonMultithreadedMax(tier);
        if (base >= maxParallel) {
            return base;
        }
        int maxMult = maxParallel / base;
        // 用 long 运算避免溢出（奇点创世的基础并行可达 21 亿级）。
        // 卡数上限取 MekckConfig 那一档（逐字对齐旧实现第 1203 行），
        // 不是 MekCkUpgradeTypes.capOf —— 两者在 SINGULARITY 等档位口径不同。
        long raw = (long) base * (1L << Math.min(installedUpgrades(MekCkUpgradeRefs.storage()),
                MekckConfig.getFactoryStackUpgradeMax(tier)));
        return (int) Math.min(raw, Math.max(base, (long) maxMult * base));
    }

    // ── IMekCkPorted ────────────────────────────────────────────────────

    @Override
    public MekCkMachineTile mePortedTile() {
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * <p>3 个输入槽 <b>加</b> 81 格存储：存储是本机真正的料仓，
     * AE2 往里投料是正常用法（不投料就开不了工）。</p>
     */
    @Override
    public List<IInventorySlot> mePatternItemInputs() {
        List<IInventorySlot> all = new ArrayList<>(getInputSlots().size() + storageSlots.size());
        all.addAll(getInputSlots());
        all.addAll(storageSlots);
        return all;
    }

    @Override
    public List<IInventorySlot> mePatternItemOutputs() {
        return getOutputSlots();
    }

    /**
     * {@inheritDoc}
     *
     * <p>刻意<b>不</b>设成 {@code true}：本机三个输入槽不是「N 份并行批次」，
     * 而是「一份配方的三种料」。塌缩成组端口会让 AE2 把它们当成同物，
     * 投进来三种不同东西被塞进同一个口。</p>
     */
    @Override
    public boolean meGroupParallelItemInputs() {
        return false;
    }
}
