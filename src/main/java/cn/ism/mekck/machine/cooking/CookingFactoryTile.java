package cn.ism.mekck.machine.cooking;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.block.CookingFactoryBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.machine.MekCkFactoryType;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkRecipeExecutor;
import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.machine.ports.IMekCkPorted;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.util.CountMath;
import cn.ism.mekck.util.UpgradeHelper;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 烹饪工厂的机器 —— <b>三个流体罐 + 144 格存储</b>（阶段 3 Task 7）。
 *
 * <h3>槽位形态：全部固定，与等级无关</h3>
 * <pre>
 *   [0, 6)     输入 3 列 × 2 行
 *   [6, 150)   存储 144 格（唯一的料仓）
 *   [150,159)  产物 3 列 × 3 行
 *   [159,162)  返还 3 格（被消耗物品的剩余物：空碗、空瓶）
 *   4 个升级卡 + 1 个能源槽（由 Mek 侧提供）
 *   另有 3 个流体罐（不在物品槽里）
 * </pre>
 * {@code tier.processes} 在本机<b>完全不影响槽位</b>——它只影响能量容量与
 * 存储卡倍率基数。基类四个布局钩子在这里全部覆写。
 *
 * <h3>三个流体罐：为什么不需要新开基类钩子</h3>
 * 气体那条路（种植切配）走的是 {@code IGasTile.getInitialGasTanks}——<b>接口
 * default 方法</b>，而 {@code TileEntityMekanism} 已经 implements，所以覆写即可，
 * 一个 Mixin 都不用。流体这边形式上更「私有」：{@code IFluidTile} 这个接口
 * <b>根本不存在</b>（jar 里 {@code tile/interfaces/} 下只有 chemical 四个），
 * 但 {@code TileEntityMekanism} 自己有一个 protected 的
 * {@code getInitialFluidTanks(IContentsListener)}，实测默认实现只有
 * {@code aconst_null; areturn}。而 {@code CapabilityHandlerManager} 的
 * {@code canHandleFluid} 就是 {@code holder != null}——
 * <b>返回非 null 的 holder 就挂上 {@code ForgeCapabilities.FLUID_HANDLER}，
 * 返回 null 就完全没有流体能力</b>。与气体的 {@code SingleGasTankHolder} /
 * {@code EmptyGasTankHolder} 范式逐条同构。
 *
 * <p>「水 / 奶」的二分是<b>业务约定，不是罐的类型</b>：三个罐的校验器一律
 * {@code fluid -> true}（任意流体都收），判定时「水」= {@code isSame(Fluids.WATER)}、
 * 「奶」= <b>任意非水流体</b>。所以往 1 号罐灌岩浆能顶替奶需求并被静默抽走——
 * 这是旧实现的口径（{@code MultiFluidHandler} 的 {@code isNotWater}），
 * 本轮原样保留，见执行器的类注释。</p>
 *
 * <h3>144 格存储搬到了 {@code appendExtraSlots}</h3>
 * 与穿串的 81 格同理：它<b>不进</b> {@code setupItemIOConfig}（基类只登记
 * input / output / energySlot），所以存储槽<b>没有侧配</b>。旧实现那个
 * 「抽取(存储)」的独立侧配模式没有了。另外旧 GUI 把存储挂在面板框外，
 * 这里沿用同一组坐标（见类常量），不是重新设计。
 */
public class CookingFactoryTile extends MekCkMachineTile implements IMekCkPorted {

    /** 输入槽数：3 列 × 2 行。编译期常量，与等级无关。 */
    public static final int INPUT_SLOTS = 6;
    /** 输入方阵列数。 */
    public static final int INPUT_COLS = 3;
    /** 产物槽数：3 列 × 3 行。 */
    public static final int PRODUCT_SLOTS = 9;
    /** 返还槽数：被消耗物品的剩余物。 */
    public static final int RETURN_SLOTS = 3;
    /** 存储槽数。 */
    public static final int STORAGE_SLOTS = 144;
    /** 流体罐数。 */
    public static final int FLUID_TANKS = 3;
    /** 一个批次的基础耗时（tick）。与旧 {@code BASE_PROCESS_TIME} 同值。 */
    public static final int PROCESS_TIME = 200;
    /** 单批次的基准能耗。与旧 {@code BASE_ENERGY_PER_TICK} 同值。 */
    public static final int ENERGY_PER_PROCESS = 20;

    /**
     * NBT：三个流体罐。与旧 {@code CookingFactoryBlockEntity} 逐字同名，
     * 旧存档因此零转换（旧读的也是这个键，见 {@link #load}）。
     */
    public static final String TAG_FLUID_TANKS = "FluidTanks";

    /**
     * 存储区的布局常量，与旧 GUI 逐像素同值。
     *
     * <p>左块 77 格（index 0..76）挂在面板<b>左侧</b>（x 为负），
     * 右块 67 格挂在<b>右侧</b>。旧布局曾是「12 列 × 12 行」，宽高各 216px，
     * 在 GUI 缩放 4 / 480×270 下整块排到屏幕外——2026 年的实测故障记录在
     * 旧 {@code CookingFactoryMenu} 第 59-77 行。现在是双侧 7 列布局。</p>
     */
    private static final int STORAGE_COLS = 7;
    private static final int STORAGE_LEFT_COUNT = 77;
    private static final int STORAGE_X_OFFSET = -(STORAGE_COLS * 18) - 4;
    private static final int STORAGE_Y = 34;
    /** 面板宽，与旧 GUI 同值（不是 Mek 的 176）。 */
    private static final int PANEL_WIDTH = 204;
    private static final int STORAGE_RIGHT_X = PANEL_WIDTH + 4;

    private final List<IInventorySlot> storageSlots = new ArrayList<>(STORAGE_SLOTS);
    private IFluidTankHolder fluidTankHolder;
    private IExtendedFluidTank[] fluidTanks;

    public CookingFactoryTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
    }

    @Override
    protected MekCkRecipeExecutor createExecutor() {
        return new CookingFactoryExecutor();
    }

    @Override
    protected CuttingMachineFactoryTier tierFromBlock() {
        if (blockProvider != null && blockProvider.getBlock() instanceof CookingFactoryBlock block) {
            return block.getTier();
        }
        return null;
    }

    @Override
    protected MekCkFactoryType typeFromBlock() {
        return MekCkFactoryType.COOKING;
    }

    /**
     * {@inheritDoc}
     *
     * <p>退出旧存档迁移：本机的槽位排布（6 输入 / 144 存储 / 9 产物 / 3 返还 /
     * 4 升级卡 / 能源）与迁移器假设的并行方阵排布<b>完全不同</b>。硬套的后果：
     * 拿 {@code tier.processes × 2} 划机器段末端（本机 BASIC 档是 6，
     * 而真正的输出段从 150 开始），于是 6..149 的 144 格存储会被当成「升级卡区」
     * 处理、其中只有 2 格被保留，其余 <b>142 格静默丢弃</b>。
     * 逐条下标与理由见基类该方法的注释。</p>
     *
     * <p><b>但流体罐仍然迁移</b>：{@code load} 读的键名 {@code "FluidTanks"}
     * 与旧 {@code CookingFactoryBlockEntity} 逐字相同，且本方法是在
     * {@code super.load} <b>之后</b> 读的（{@code super.load} 不会碰它）。
     * 所以换 jar 后配方槽是空的、罐里的水还在。</p>
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
        return INPUT_COLS;
    }

    @Override
    protected int outputSlotCount(CuttingMachineFactoryTier tier) {
        // 产物 9 格 + 返还 3 格。合在一列 3 宽的网格里：产物占前 3 行、返还占后 3 行。
        return PRODUCT_SLOTS + RETURN_SLOTS;
    }

    @Override
    protected int outputSlotColumns(int count) {
        return INPUT_COLS;
    }

    /**
     * {@inheritDoc}
     *
     * <p>追加 144 格存储：左 77 + 右 67，坐标与旧 GUI 同款。</p>
     */
    @Override
    protected void appendExtraSlots(InventorySlotHelper builder, IContentsListener listener) {
        storageSlots.clear();
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
            IInventorySlot slot = MekCkSlot.input(limit, listener, x, y);
            storageSlots.add(slot);
            builder.addSlot(slot);
        }
    }

    // ── 三个流体罐 ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>返回 null 就是完全没有流体能力</b>——{@code CapabilityHandlerManager}
     * 的 {@code canHandleFluid} 就是 {@code holder != null}，而
     * {@code TileEntityMekanism} 的默认实现是 {@code aconst_null; areturn}。
     * 父类构造器回调本方法（与 {@code getInitialInventory} 同一批，见类注释的
     * 「构造期顺序陷阱」），所以罐与 {@code fluidTanks} 数组都必须在这里 new，
     * <b>不能</b>写成字段初始化器。</p>
     *
     * <p><b>{@code addTank(tank)} 不给 RelativeSide</b>：罐对所有面可见，
     * 与旧实现的 {@code getCapability(FLUID_HANDLER)} 不判 side 一致。
     * 侧配也<b>没有</b>流体档（旧菜单不覆写 {@code getFluidSideMode} → 恒 NONE），
     * 见类注释。</p>
     */
    @Override
    protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
        fluidTanks = new IExtendedFluidTank[FLUID_TANKS];
        FluidTankHelper builder = FluidTankHelper.forSideWithConfig(this::getDirection, this::getConfig);
        for (int i = 0; i < FLUID_TANKS; i++) {
            // 单罐容量沿用旧的 FLUID_CAPACITY = Integer.MAX_VALUE。旧 GUI 的液位条
            // 对「amount == Integer.MAX_VALUE」有满格特判，改容量会让那套显示失真。
            IExtendedFluidTank tank =
                    BasicFluidTank.create(Integer.MAX_VALUE, fluid -> true, listener);
            fluidTanks[i] = tank;
            builder.addTank(tank);
        }
        fluidTankHolder = builder.build();
        return fluidTankHolder;
    }

    /**
     * 流体罐数组。可能为 null（没走过 {@link #getInitialFluidTanks}，只可能发生在异常路径）。
     *
     * <p>刻意不叫 {@code getFluidTanks}：父类已经有一个
     * {@code public final List<IExtendedFluidTank> getFluidTanks(Direction)}，
     * 同名不同参会让人误以为这两个是同一个东西。</p>
     */
    public IExtendedFluidTank[] cookingFluidTanks() {
        return fluidTanks;
    }

    /** 第 {@code index} 个罐；越界或未建好时返回 null，由调用方判空。 */
    public IExtendedFluidTank fluidTank(int index) {
        return fluidTanks == null || index < 0 || index >= fluidTanks.length ? null : fluidTanks[index];
    }

    // ── 流体罐的存档 ────────────────────────────────────────────────────
    //
    // 必须自己管：实测 TileEntityMekanism.saveAdditional / load 的字符串常量池里
    // 只有 CustomName / Items / activeState / redstone / controlType / updateDelay，
    // **没有流体罐**。气体那边（种植切配）也是自管，原因逐字相同。
    // 键沿用旧实现的 "FluidTanks"，旧存档因此零转换。

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (fluidTanks != null && fluidTanks.length > 0) {
            ListTag tanks = new ListTag();
            for (IExtendedFluidTank tank : fluidTanks) {
                tanks.add(tank.serializeNBT());
            }
            CompoundTag container = new CompoundTag();
            container.put("Tanks", tanks);
            container.putInt("Count", fluidTanks.length);
            tag.put(TAG_FLUID_TANKS, container);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (fluidTanks == null || !tag.contains(TAG_FLUID_TANKS, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag container = tag.getCompound(TAG_FLUID_TANKS);
        ListTag tanks = container.getList("Tanks", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(tanks.size(), fluidTanks.length); i++) {
            fluidTanks[i].deserializeNBT(tanks.getCompound(i));
        }
    }

    // ── 给执行器与界面的读取口 ──────────────────────────────────────────

    /** 存储槽列表（活列表，构造完成后由本类独占维护）。 */
    public List<IInventorySlot> getStorageSlots() {
        return storageSlots;
    }

    /**
     * 配料扫描集合：6 个输入槽 + 全部存储槽。
     *
     * <p>刻意包含存储槽——旧实现的匹配位置无关，材料放 144 格存储里一样能开工。
     * 旧 GUI 的配方列表正是靠这一点做到「摆一堆料就能看能做什么」。</p>
     */
    public List<IInventorySlot> ingredientSlots() {
        List<IInventorySlot> scan = new ArrayList<>(getInputSlots().size() + storageSlots.size());
        scan.addAll(getInputSlots());
        scan.addAll(storageSlots);
        return scan;
    }

    /** 产物槽（{@code getOutputSlots()} 的前 9 格）。 */
    public List<IInventorySlot> productSlots() {
        List<IInventorySlot> outputs = getOutputSlots();
        return outputs.size() <= PRODUCT_SLOTS
                ? outputs
                : outputs.subList(0, PRODUCT_SLOTS);
    }

    /** 返还槽（{@code getOutputSlots()} 的后 3 格）。 */
    public List<IInventorySlot> returnSlots() {
        List<IInventorySlot> outputs = getOutputSlots();
        return outputs.size() <= PRODUCT_SLOTS
                ? List.of()
                : outputs.subList(PRODUCT_SLOTS, outputs.size());
    }

    private CookingFactoryExecutor cooking() {
        return executor() instanceof CookingFactoryExecutor c ? c : null;
    }

    // ── 订单转发（网络包与界面走这几个方法）─────────────────────────────

    public boolean hasOrder() {
        CookingFactoryExecutor exec = cooking();
        return exec != null && exec.hasOrder();
    }

    public void setOrder(ResourceLocation recipeId, int quantity) {
        CookingFactoryExecutor exec = cooking();
        if (exec != null) {
            exec.setOrder(recipeId, quantity);
        }
    }

    public void clearOrder() {
        CookingFactoryExecutor exec = cooking();
        if (exec != null) {
            exec.clearOrder();
        }
    }

    public int getOrderQuantity() {
        CookingFactoryExecutor exec = cooking();
        return exec == null ? 0 : exec.getOrderQuantity();
    }

    public int getOrderCompleted() {
        CookingFactoryExecutor exec = cooking();
        return exec == null ? 0 : exec.getOrderCompleted();
    }

    // ── 能量闸门参数 ────────────────────────────────────────────────────

    @Override
    protected int ticksPerWorkCycle() {
        return Math.max(1, (int) (PROCESS_TIME / effectiveSpeedMultiplier()));
    }

    /**
     * 本 tick 的耗电。
     *
     * <h3>与另外两个家族的公式都不同，这是刻意的</h3>
     * <ul>
     *   <li><b>不乘非空输入槽数</b>：烹饪是「按订单做一批」，不是「一槽一批」。
     *       与穿串同理。</li>
     *   <li><b>不乘 {@link MekckConfig#getTierEnergyEfficiency}</b>：旧实现就没有这一项，
     *       所以「高阶更省电」这条曲线<b>对烹饪无效</b>——所有档位单位产出能耗基本相同。
     *       切菜 / 研磨 / 种植切配有那条曲线，烹饪没有；实机验证清单第 4.6 节把
     *       这条记成「与切菜的现象正好相反，是刻意的」，别当成漏乘。</li>
     * </ul>
     */
    @Override
    protected int energyPerWorkTick() {
        CuttingMachineFactoryTier tier = getTier();
        if (tier == null || tier.energyPerTick == 0) {
            return 0;
        }
        double speedMult = effectiveSpeedMultiplier();
        double consumptionMult = effectiveEnergyConsumptionMultiplier();
        int base = (int) Math.ceil(ENERGY_PER_PROCESS * speedMult * speedMult * consumptionMult);
        return CountMath.mulClamp(Integer.MAX_VALUE, base, stackMultiplier());
    }

    /**
     * {@inheritDoc}
     *
     * <p>覆写成「有订单」而非基类的「有非空输入槽」——这台机器<b>无订单不自转</b>
     * （旧实现第 729-730 行 {@code // No order set - do not auto-process}），
     * 用基类判据会让材料摆满时进度条照走、跑一个空批次。</p>
     */
    @Override
    protected boolean hasWorkToDo() {
        return hasOrder();
    }

    public double effectiveSpeedMultiplier() {
        return UpgradeHelper.speedMultiplier(installedUpgrades(Upgrade.SPEED));
    }

    public double effectiveEnergyConsumptionMultiplier() {
        return UpgradeHelper.energyConsumptionMultiplier(installedUpgrades(Upgrade.ENERGY));
    }

    /**
     * 一个周期做多少份（受存储卡倍增）。
     *
     * <p>用 {@code non-multithreaded} 基数表，逐字对齐旧实现第 1656-1667 行
     * （与穿串同源，理由相同：烹饪的基数本身按等级指数上升）。
     * 卡数上限取 {@link MekckConfig#getFactoryStackUpgradeMax} 而不是
     * {@code MekCkUpgradeTypes.capOf}——两处在 {@code SINGULARITY} 口径不同。</p>
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
     * <p>6 个输入槽 <b>加</b> 144 格存储：存储是本机唯一的料仓，
     * AE2 往里投料是正常用法。旧实现的 {@code getInputSlotRange()} 同样是
     * {@code {0, 150}}（含全部存储），口径一致。</p>
     */
    @Override
    public List<IInventorySlot> mePatternItemInputs() {
        List<IInventorySlot> all = new ArrayList<>(getInputSlots().size() + storageSlots.size());
        all.addAll(getInputSlots());
        all.addAll(storageSlots);
        return all;
    }

    /**
     * {@inheritDoc}
     *
     * <p>产物 9 格 + <b>返还 3 格</b>。返还格也算产物口是刻意的：空碗 / 空瓶
     * 做完就该回到网络里去，而不是烂在机器里。</p>
     */
    @Override
    public List<IInventorySlot> mePatternItemOutputs() {
        return getOutputSlots();
    }

    /**
     * {@inheritDoc}
     *
     * <p>刻意<b>不</b>设成 {@code true}：本机 6 个输入槽不是「N 份并行批次」，
     * 而是「一份配方的若干配料」。塌缩成组端口会让 AE2 把它们当成同物。</p>
     */
    @Override
    public boolean meGroupParallelItemInputs() {
        return false;
    }
}
