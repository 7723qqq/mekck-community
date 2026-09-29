package cn.ism.mekck.machine;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.FloatingLong;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.TileComponentUpgrade;
import mekanism.common.tile.interfaces.IRedstoneControl;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 7 个工厂家族共有的机器能力 —— 侧配、能量、槽位方阵、升级、持久化、执行器挂载。
 * 家族特有的一切（配方匹配、批量执行、订单推进）都在 {@link MekCkRecipeExecutor} 里，
 * 本类不写任何配方逻辑。
 *
 * <h3>为什么继承 Mek 的机器基类</h3>
 * 旧实现是自研 {@code BlockEntity}，只借用 Mek 的 GUI 绘制类，因此侧栏 tab 与 Mek 自己的
 * tab（能量等）分属两套布局体系，运行时要靠 {@code avoidEnergyTabY} 互相避让，导致重叠。
 * 继承 {@link TileEntityConfigurableMachine} 后：
 * <ul>
 *   <li>{@code ISideConfiguration} 由父类实现（{@code configComponent}/{@code ejectorComponent}）；</li>
 *   <li>GUI 侧 {@code GuiConfigurableTile.super.addGuiElements()} 由 Mek 自己排出
 *       侧配(6) / 传输配置(34) / 升级(6,右) / 红石(137,右)，无需手抄坐标；</li>
 *   <li>能量/升级/红石/比较器/安全等能力由 {@code TileEntityMekanism} 按<b>方块属性</b>
 *       自动开通，无需逐个覆写（实测 {@code setSupportedTypes(Block)} 全程只读
 *       {@code Attribute.has(block, X.class)}）。</li>
 * </ul>
 *
 * <h3>与 Mek 自己的工厂是平行关系，不是继承关系</h3>
 * Mek 10.4.6 的工厂基类是 {@code mekanism.common.tile.factory.TileEntityFactory}
 * （它自己才是 {@code TileEntityConfigurableMachine} 的子类）。本类<b>平行</b>于它：
 * Mek 的那一层把内容类型写死成 {@code FactoryType<FactoryRecipe>}+{@code FactoryTier}，
 * 而本模组的工艺类型是自建的 {@link MekCkFactoryType}、等级是自建的 12 档，
 * 塞进那两个封闭枚举里会失去可扩展性。因此 {@code addSlots} 这个模板方法在父类链上
 * 根本不存在，{@code @Override} 不成立——本类只能整体重写 {@link #getInitialInventory}。
 *
 * <h3>⚠️ 构造期顺序陷阱（本类所有「从方块反查」的写法都源于此）</h3>
 * {@code TileEntityMekanism} 的构造器<b>内部</b>依次回调
 * {@code presetVariables()} → {@code getInitialEnergyContainers(listener)} →
 * {@code getInitialInventory(listener)} → {@code new TileComponentUpgrade(this)}
 * （实测 {@code TileEntityMekanism} 构造器偏移 118 / 297 / 331 / 457）。
 * 这一切都发生在 {@code super(...)} 返回<b>之前</b>，此刻本类的实例字段初始化器
 * <b>一个都还没跑</b>。两个已实测踩过的坑：
 * <pre>
 *   NPE: Cannot read field "processes" because "this.tier" is null
 *   NPE: Cannot invoke "java.util.List.add(Object)" because "target" is null
 * </pre>
 * 两条铁律：
 * <ol>
 *   <li>等级/工艺类型一律走 {@link #tierFromBlock()} / {@link #typeFromBlock()}，
 *       从父类 {@code protected final blockProvider} 反查方块——它在父类构造器偏移 98
 *       就已赋好，是此刻唯一可靠的实例状态。本类因此<b>不设</b> tier/factoryType 字段。</li>
 *   <li>槽位列表在 {@link #getInitialInventory} 里 {@code new}，
 *       <b>不能</b>写成 {@code = new ArrayList<>()} 字段初始化器。</li>
 * </ol>
 *
 * <h3>⚠️ 类名与 {@code getTier()} 签名是阶段 1 的反射桥接契约，不能改</h3>
 * {@code MixinTileComponentUpgradePersistence.mekck$tier()} 沿类链按名字找
 * {@code "cn.ism.mekck.machine.MekCkMachineTile"}，再反射调 {@code getTier()} 并
 * <b>强转成 {@link CuttingMachineFactoryTier}</b>。两者必须同时对上：
 * <ul>
 *   <li>类名对不上 → 走不到，返回 {@code null} → 存储卡读档被
 *       {@code isSupportedBy(STORAGE, null)} 静默判 false，<b>没有任何日志</b>；</li>
 *   <li>返回类型对不上（返回别的等级枚举之类）→ 强转抛
 *       {@code ClassCastException}，而该 catch 只接 {@code ReflectiveOperationException}，
 *       会一路冒到读档路径上炸服。</li>
 * </ul>
 * 所以本类返回的是 {@code cn.ism.mekck.CuttingMachineFactoryTier}——与
 * {@code MekCkUpgradeTypes.isSupportedBy/capOf} 和
 * {@code MekckConfig.getFactoryStackUpgradeMax} 的入参类型一致，
 * 也与阶段 2 Task 4 里「{@code CuttingFactoryTile.getTier()} 返回
 * {@code CuttingMachineFactoryTier}」的要求兼容（同类型才允许覆写）。
 *
 * <h3>⚠️ 双枚举并存期已结束（阶段 3 Task 0）</h3>
 * 本类曾经带一段静态块，把「另一个 12 档枚举」{@code cn.ism.mekck.factory.MekCkFactoryTier}
 * 按序列化名映射成本类要用的 {@link CuttingMachineFactoryTier}，并用 fail-fast
 * 兜底「两个枚举必须同名同集合」。两个枚举（常量同名同值、类型不同）现已合并为
 * <b>只剩 {@link CuttingMachineFactoryTier}</b>，那段映射与其 fail-fast 静态块
 * 一并删除。<b>原因不是审美，是那份映射本身就是 {@code ClassCastException} 的引信</b>：
 * 阶段 1 的 Mixin 只认 {@code CuttingMachineFactoryTier}，一旦某台机器的
 * {@code getTier()} 返回了另一套枚举，读档路径会炸服而不是静默降级。
 * <b>阶段 1 的反射桥接（类名 {@code cn.ism.mekck.machine.MekCkMachineTile} 与
 * {@code getTier()} 的返回类型）一个字符都不许改</b>，否则切菜工厂存档里的存储卡
 * 会在读档时被 {@code isSupportedBy(STORAGE, null)} 静默判 false，且没有任何日志。
 */
public abstract class MekCkMachineTile extends TileEntityConfigurableMachine {

    private static final Logger LOGGER = LoggerFactory.getLogger(MekCkMachineTile.class);

    /** 输入槽方阵起始坐标（GUI 相对）。 */
    private static final int INPUT_START_X = 38;
    /** 输入方阵与输出方阵之间的水平间隔（与旧自研实现一致）。 */
    private static final int GRID_GAP = 30;
    /** 槽位间距。 */
    private static final int SLOT_STEP = 18;
    /** 输入/输出方阵的起始 y（GUI 相对）。 */
    private static final int GRID_START_Y = 41;
    /** 能量槽坐标，沿用 Mek 工厂的既定位置。 */
    private static final int ENERGY_SLOT_X = 7;
    private static final int ENERGY_SLOT_Y = 13;

    /**
     * NBT：执行器自有状态的子标签。
     *
     * <p><b>包级可见是为了让 {@link MekCkLegacyMachineNbt} 往里写</b>
     * （阶段 3 Task 1）：旧存档把订单状态写在根标签上，而新格式的订单状态归执行器所有。
     * 迁移必须落在同一个子标签里，否则 {@code executor().load(tag.getCompound(TAG_EXECUTOR))}
     * 永远看不到它——订单会在换机器的那一刻静默消失，而旧存档里明明写着单。</p>
     */
    static final String TAG_EXECUTOR = "mekckExecutor";
    /** NBT：本 tile 由 Mek 原生基类承载的存档格式版本。 */
    static final String TAG_NATIVE_VERSION = "MekCkNative";
    /** NBT：进度条已走的 tick 数。 */
    static final String TAG_WORK_PROGRESS = "MekCkWorkProgress";
    /** 当前存档格式版本。v1 是首个 Mek 原生版本，没有需要迁移的旧格式。 */
    private static final int NATIVE_VERSION = 1;

    /**
     * 家族执行器，懒加载。
     *
     * <p><b>为什么不能在字段初始化器里 {@code createExecutor()}</b>：JLS 规定的初始化
     * 顺序是「父类构造器 → <b>父类字段初始化器</b> → 父类构造器体 → 子类字段初始化器 →
     * 子类构造器体」。也就是说本类的字段初始化器跑在<b>子类</b>的字段初始化器
     * <b>之前</b>——此刻子类的配方类型、缓存、执行器实现类字段全是 {@code null}，
     * 而 {@code createExecutor()} 恰恰要拿它们。这正是本仓库已经踩过两次的
     * 「super() 内部回调子类方法」那类时序陷阱的翻版。
     * 懒加载把创建时机推到第一次真正使用它（tick / 存档 / GUI），那时整条链都已就绪。</p>
     */
    private MekCkRecipeExecutor executor;

    protected MachineEnergyContainer<MekCkMachineTile> energyContainer;
    protected EnergyInventorySlot energySlot;

    /**
     * 输入/输出槽（供执行器与 {@code IMekCkPorted} 按索引取用）。
     *
     * <p><b>不能写成 {@code = new ArrayList<>()} 字段初始化器</b>：{@code getInitialInventory}
     * 在父类构造器<b>内部</b>被回调，那一刻字段初始化器还没执行。详见类注释的构造期陷阱。</p>
     */
    protected List<IInventorySlot> inputSlots;
    protected List<IInventorySlot> outputSlots;

    protected MekCkMachineTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
        // 侧配内容必须在这里、且只能在 super(...) 之后登记：
        // setupItemIOConfig 依赖 getInitialInventory 建好的槽位列表，
        // setupInputConfig 依赖 getInitialEnergyContainers 建好的能量容器。
        // （Mek 自己的 TileEntityFactory 也是在构造器体里做同一件事，实测其构造器偏移 179-320。）
        configComponent.setupItemIOConfig(inputSlots, outputSlots, energySlot, false);
        configComponent.setupInputConfig(TransmissionType.ENERGY, energyContainer);
        // 只给 ITEM 挂弹出：ENERGY 侧的 ConfigInfo 已被 setupInputConfig 置为 setCanEject(false)。
        ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM);
    }

    // ── 等级与工艺类型 ───────────────────────────────────────────────────

    /**
     * 本机等级 —— <b>子类必须覆写</b>。
     *
     * <p><b>基类为什么给不出答案（阶段 3 Task 0 改）</b>：原先这里靠
     * {@code blockProvider.getBlock() instanceof MekCkFactoryBlock} 从方块反查等级。
     * 那条路现在<b>永远走不到</b>——唯一的 {@code MekCkFactoryBlock} 实例由
     * {@code MekCkFactoryRegistration} 的 84 个 {@code mekckfactory:<tier>_<family>_factory}
     * 方块持有，那套注册连同 {@code mekckfactory} 命名空间已在本次删除。
     * 换句话说：游戏里不再存在任何一块「能从基类反查等级」的方块，留着这个分支
     * 只会让人以为存在一条通用反查路径。
     *
     * <p>返回 {@code null} 表示「本机档位未知」：此时 {@code MekCkUpgradeTypes.isSupportedBy}
     * 对存储卡判 {@code false}（缺依据即不放行），其余升级不受影响；而且
     * {@link #getInitialInventory} 会先抛一条指明根因的 {@link IllegalStateException}，
     * 不存在「安静地少一档能力」这种失败形态。</p>
     */
    protected CuttingMachineFactoryTier tierFromBlock() {
        return null;
    }

    /**
     * 工艺家族 —— <b>子类必须覆写</b>，理由与 {@link #tierFromBlock()} 相同。
     *
     * <p>{@code null} 表示「本机未声明家族」，消费方
     * （{@code MekckAe2.portedFamily}）据此判定该机器不接 AE2 自动化端口。
     */
    protected MekCkFactoryType typeFromBlock() {
        return null;
    }

    /**
     * 本机等级。
     *
     * <p><b>返回类型是阶段 1 反射桥接的硬契约，见类注释。{@code null} 表示档位未知。</b></p>
     */
    public CuttingMachineFactoryTier getTier() {
        return tierFromBlock();
    }

    /** 本工艺家族。{@code null} 表示方块未携带工艺信息。 */
    public MekCkFactoryType getFactoryType() {
        return typeFromBlock();
    }

    // ── 槽位与能量 ───────────────────────────────────────────────────────

    /**
     * 初始化侧配组件 —— <b>必须实现</b>，否则放置时必崩。
     *
     * <p>实测崩溃：{@code NPE: Cannot invoke "TileComponentConfig.read(CompoundTag)"
     * because the return value of "ISideConfiguration.getConfig()" is null at
     * BlockMekanism.m_6402_(BlockMekanism.java:307)}。
     * 原因是 {@code TileEntityConfigurableMachine} 只声明了 {@code configComponent} 字段
     * （public、不赋值），初始化责任在子类。</p>
     *
     * <p>本方法在父类构造器内被回调，故只写入父类字段、不碰本类字段。</p>
     */
    @Override
    protected void presetVariables() {
        // 物品 + 能量两路侧配：本模组工厂只有物品槽与能量输入，没有气体/流体/矿浆。
        configComponent = new TileComponentConfig(this, TransmissionType.ITEM, TransmissionType.ENERGY);
        // 弹出组件：让侧面配置里的「弹出」模式可用（与 Mek 工厂同口径）。
        ejectorComponent = new TileComponentEjector(this);
    }

    /**
     * 能量容器（供 GUI 显示容量/存量）。
     *
     * <p>容量与能耗<b>不在这里设</b>：{@code MachineEnergyContainer.input} 会从方块的
     * {@code AttributeEnergy} 读取（实测其内部走
     * {@code validateBlock(tile).getStorage()/getUsage()}）。各等级的容量/能耗因此只能在
     * 方块注册时声明。</p>
     */
    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSideWithConfig(this::getDirection, this::getConfig);
        energyContainer = MachineEnergyContainer.input(this, listener);
        builder.addContainer(energyContainer);
        return builder.build();
    }

    /**
     * 按本等级的并行数排输入/输出方阵，外加一个能量槽。
     *
     * <p>列数取 ⌈√N⌉，与旧自研实现的 {@code (int) Math.ceil(Math.sqrt(inputSlots))}
     * 同口径，使 3/5/7/9 等非平方数的并行数也能排成紧凑方阵。</p>
     *
     * <p><b>构造期回调</b>：本方法在父类构造器内被调用，因此不能读本类字段初始化器产出的值；
     * 槽位列表必须在这里 {@code new}（见类注释）。</p>
     */
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        CuttingMachineFactoryTier tier = getTier();
        if (tier == null) {
            // 显式失败优于 NPE：旧实现在这里是 "Cannot read field processes because this.tier is null"，
            // 排查时完全看不出是「方块没带等级」。这条消息直接指明根因与修法。
            throw new IllegalStateException("工厂方块 " + getBlockType()
                    + " 未携带 MekCK 等级：MekCkMachineTile 的 tierFromBlock() 默认返回 null，"
                    + "子类必须覆写它并从自己的方块类型读出等级（参考 CuttingFactoryTile）。");
        }

        int inputCount = inputSlotCount(tier);
        int inputColumns = inputSlotColumns(inputCount);
        int outputCount = outputSlotCount(tier);
        int outputColumns = outputSlotColumns(outputCount);
        // 列数是 0 会让 addSlotGrid 里的 (i / columns) 除零，而这里的除零只会在放置时炸一次、
        // 报错信息里完全看不出是哪个家族给的 0。这里提前拦并点名。
        if (inputCount <= 0 || inputColumns <= 0 || outputCount <= 0 || outputColumns <= 0) {
            throw new IllegalStateException("工厂方块 " + getBlockType() + " 的槽位布局非法："
                    + "输入 " + inputCount + " 槽 / " + inputColumns + " 列，"
                    + "输出 " + outputCount + " 槽 / " + outputColumns + " 列（四者都必须 ≥ 1）。");
        }

        inputSlots = new ArrayList<>(inputCount);
        outputSlots = new ArrayList<>(outputCount);

        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this::getDirection, this::getConfig);
        int slotLimit = slotLimitPerSlot(tier);
        // 输入方阵在左、输出方阵右移「**输入**方阵宽度 + 间隔」，与旧实现一致。
        // 间隔按输入宽度算而不是各自宽度：并行方阵两者相等，穿串工厂的
        // 「3 宽输入 + 1 宽输出」也正好是旧版 38/130 的间距。
        addSlotGrid(builder, listener, INPUT_START_X, inputCount, inputColumns, slotLimit, true);
        addSlotGrid(builder, listener, INPUT_START_X + inputColumns * SLOT_STEP + GRID_GAP,
                outputCount, outputColumns, slotLimit, false);
        energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener,
                ENERGY_SLOT_X, ENERGY_SLOT_Y);
        builder.addSlot(energySlot);
        appendExtraSlots(builder, listener);
        return builder.build();
    }

    // ── 槽位布局（阶段 3 Task 4 引入：穿串工厂触发）────────────────────
    //
    // 并行方阵家族的四个值都取默认；「固定输入 + 存储缓冲」家族（穿串 / 烹饪）
    // 覆写其中一两个即可，不必整体重写 getInitialInventory。
    // 加钩子而不是整体覆写，理由与 appendExtraSlots 相同：
    // 已迁的四个家族必须一行都不用改。

    /**
     * 本等级的输入槽总数。
     *
     * <p>默认 {@code tier.processes}——并行方阵家族的老口径。穿串工厂返回 {@code 3}
     * （签 / 主料 / 辅料），它的槽数<b>与并行数无关</b>：一个周期把三个输入槽
     * <b>一起</b>消费掉做出一批串，不是「一槽一批」。</p>
     */
    protected int inputSlotCount(CuttingMachineFactoryTier tier) {
        return tier.processes;
    }

    /**
     * 输入槽的列数。
     *
     * <p>默认 {@code ⌈√N⌉}，即排成尽量方正的方阵。穿串工厂返回 {@code 3}（一行三格），
     * 与旧 GUI 的 {@code x = 38 + (i%3)*18, y = 41} 逐像素一致。</p>
     */
    protected int inputSlotColumns(int count) {
        return (int) Math.ceil(Math.sqrt(count));
    }

    /**
     * 本等级的输出槽总数。
     *
     * <p>默认与输入同数（并行方阵：第 i 个输入槽的第 i 个输出槽是一对）。
     * 穿串工厂返回 {@code 2}——产物槽与<b>返还槽</b>（签子复制回去）竖着叠一列。</p>
     */
    protected int outputSlotCount(CuttingMachineFactoryTier tier) {
        return inputSlotCount(tier);
    }

    /**
     * 输出槽的列数。
     *
     * <p>默认 {@code ⌈√N⌉}。穿串工厂返回 {@code 1}（一列两格）。</p>
     */
    protected int outputSlotColumns(int count) {
        return (int) Math.ceil(Math.sqrt(count));
    }

    /**
     * 在输入/输出方阵与能量槽<b>之后</b>追加家族特有槽位（阶段 3 PlantCutting 引入）。
     *
     * <h3>为什么是追加而不是整体覆写 {@code getInitialInventory}</h3>
     * 7 个工厂家族里有两类形态：① 并行方阵（切菜 / 研磨 / 种植切配 / 烧烤 / 制冰）
     * ② 固定输入 + 存储缓冲（穿串 / 烹饪）。类的形态只有真正做到第二个家族时才会暴露，
     * 而一旦要改基类，已迁的家族都要回归一遍。种植切配只需要在方阵尾巴上挂
     * 「营养液容器槽 + 生长方块槽」两格，方阵本身不动——**用追加而不是整体覆写，
     * 就是为了让已迁的切菜与研磨一行都不用改。**
     *
     * <p>要换整体形态（存储缓冲型）的家族，覆写 {@code getInitialInventory} 即可，
     * 本钩子此时不调。
     *
     * <p>默认实现什么都不加。子类追加的槽若要参与侧配，需自行
     * {@code builder.addSlot} 并在 {@link #presetVariables} 里登记对应的 config 项。
     *
     * @param builder 已含输入方阵、输出方阵、能量槽的构建器
     * @param listener 内容变更监听器
     */
    protected void appendExtraSlots(InventorySlotHelper builder, IContentsListener listener) {
    }

    /**
     * 本机单个输入/输出槽的物品数量上限（阶段 2 Task 4.9）。
     *
     * <p>取值与理由见 {@link MekckConfig#getFactorySlotLimit}。之所以在这里开一个
     * {@code protected} 钩子而不是在 {@link #getInitialInventory} 里直接调配置：将来
     * 烹饪 / 穿串这两个「非多线程」家族接上本基类时，它们的单批产出量基数与切菜不同，
     * 覆写本方法换算口径即可，不必再动 {@code getInitialInventory}。</p>
     *
     * <p>默认实现对所有家族取同一个全局配置值——它不按档位分档，见该配置项的注释。</p>
     */
    protected int slotLimitPerSlot(CuttingMachineFactoryTier tier) {
        return MekckConfig.getFactorySlotLimit(tier);
    }

    /**
     * 排一列方阵。`count` 由调用方从已校验非空的等级读出后传入，本方法不再回查等级。
     *
     * <p>槽位用 {@link MekCkSlot} 而不是 {@code InputInventorySlot} / {@code OutputInventorySlot}：
     * 后两者的单槽容量硬编码成 {@code 64} 且会再被物品自身堆叠上限截一次，
     * 详见 {@link MekCkSlot} 的类注释。</p>
     */
    private void addSlotGrid(InventorySlotHelper builder, IContentsListener listener,
                             int startX, int count, int columns, int slotLimit, boolean input) {
        List<IInventorySlot> target = input ? inputSlots : outputSlots;
        for (int i = 0; i < count; i++) {
            int x = startX + (i % columns) * SLOT_STEP;
            int y = GRID_START_Y + (i / columns) * SLOT_STEP;
            IInventorySlot slot = input
                    ? MekCkSlot.input(slotLimit, listener, x, y)
                    : MekCkSlot.output(slotLimit, listener, x, y);
            target.add(slot);
            builder.addSlot(slot);
        }
    }

    /** 输入槽列表（活列表，构造完成后由本类独占维护）。 */
    public List<IInventorySlot> getInputSlots() {
        return inputSlots;
    }

    /** 输出槽列表（活列表，构造完成后由本类独占维护）。 */
    public List<IInventorySlot> getOutputSlots() {
        return outputSlots;
    }

    /** 能量容器（供 GUI 显示容量/存量）。 */
    public MachineEnergyContainer<MekCkMachineTile> getEnergyContainer() {
        return energyContainer;
    }

    /** 能量槽（供 GUI 与 AE2 端口排除用）。 */
    public EnergyInventorySlot getEnergySlot() {
        return energySlot;
    }

    // ── 升级 ────────────────────────────────────────────────────────────

    /**
     * 本机接受的升级类型。
     *
     * <h3>⚠️ 不要照搬 {@code MekCkUpgradeTypes.all()}</h3>
     * {@code all()} 返回 {@code Upgrade.values()}，也就是「Mek 原生 7 种 + 所有已加载注入者
     * 追加的」全集（Mek Extras / Mek Energistics 一注入就是几十个）。把它整包声明成
     * 「本机支持」会让升级槽接受本机根本用不上的卡，而 {@code TileComponentUpgrade}
     * <b>不会</b>替你拦——它只认这一份清单。
     *
     * <p>正确做法是对候选集逐个问 {@code MekCkUpgradeTypes.isSupportedBy(type, getTier())}：
     * 速度/能量与档位无关恒真；存储卡按 {@code tier.supportsStackUpgrade()} 判
     * （{@code ABSOLUTE ~ NEBULA}，明确排除 {@code SINGULARITY}）；随机化卡全局开放。
     * 上限数量另由 {@code MekCkUpgradeTypes.capOf(type, tier)} 裁剪，不在本方法管。</p>
     *
     * <h3>为什么返回 {@link EnumSet} 而不是 {@code Set.of(...)}</h3>
     * {@code TileComponentUpgrade} 的构造器会做
     * {@code this.supported = EnumSet.copyOf(tile.getSupportedUpgrade())}
     * （实测 {@code TileComponentUpgrade} 构造器偏移 30）。而
     * {@code EnumSet.copyOf(Collection)} 对<b>非 EnumSet 的空集合</b>抛
     * {@code IllegalArgumentException}。返回 EnumSet 让它走「克隆」分支，
     * 空集也安全——本类不该因为「某档位不支持存储卡」就把机器炸掉。
     */
    @Override
    public Set<Upgrade> getSupportedUpgrade() {
        EnumSet<Upgrade> supported = EnumSet.noneOf(Upgrade.class);
        CuttingMachineFactoryTier tier = getTier();
        for (Upgrade candidate : candidateUpgrades()) {
            if (MekCkUpgradeTypes.isSupportedBy(candidate, tier)) {
                supported.add(candidate);
            }
        }
        return supported;
    }

    /**
     * 本模组可能给工厂装的 4 种升级。
     *
     * <p>不缓存成静态常量：静态初始化会触发 {@code Upgrade} 的 {@code <clinit>}，
     * 而 {@link MekCkUpgradeRefs#storage()} 在注入缺席时是抛异常的。
     * 放在方法里能让异常带着「正在建升级清单」的调用栈出现。</p>
     */
    private static Upgrade[] candidateUpgrades() {
        return new Upgrade[]{
                Upgrade.SPEED,
                Upgrade.ENERGY,
                MekCkUpgradeRefs.storage(),
                MekCkUpgradeRefs.randomize()
        };
    }

    // ── 执行器 ──────────────────────────────────────────────────────────

    /** 由子类实现：构造本家族的执行器。 */
    protected abstract MekCkRecipeExecutor createExecutor();

    /** 本家族的执行器，首次调用时创建。 */
    public MekCkRecipeExecutor executor() {
        MekCkRecipeExecutor result = executor;
        if (result == null) {
            result = createExecutor();
            executor = result;
        }
        return result;
    }

    /**
     * 本机是否正在工作（GUI 进度条与 AE2 忙碌态的统一口径）。
     *
     * <p><b>为什么不用 {@code executor().isBusy()}</b>：执行器只在「一个批次走完」的那一 tick
     * 被调用（见 {@link #onUpdateServer}），它内部的 {@code busy} 标志在那一 tick 置位后
     * 一直保持到下一次调用。若拿它当忙碌态，机器跑完第一批之后就会永远显示「在忙」。
     * 进度条是逐 tick 更新的，天然没有这种陈旧问题。</p>
     */
    public boolean isBusy() {
        return workProgress > 0;
    }

    // ── 能量闸门与进度条 ────────────────────────────────────────────────
    //
    // 这里是旧 serverTick 的「红石 → 能量 → 干活」三段式在 Mek 体系下的落点。
    // 执行器只负责「本 tick 尽可能多地加工」，不判断该不该加工，也不碰能量与进度条。

    /** 进度条已走的 tick 数（一个批次内单调递增）。 */
    private int workProgress;
    /** PULSE 锁存：收到上升沿后一直放行，直到跑完一个完整批次。 */
    private boolean pulseLatched;

    /**
     * 完成一个批次需要累计多少 tick —— 进度条长度。
     *
     * <p>默认 1 = 没有进度条，每 tick 就是一个批次。家族基率不同（切菜 200 / 速度升级），
     * 由子类覆写。</p>
     */
    protected int ticksPerWorkCycle() {
        return 1;
    }

    /**
     * 本 tick 应扣多少能量 —— <b>已含并行槽数与存储卡倍增</b>的最终值。
     *
     * <p>默认 0 = 不耗电。旧实现里这条公式是切菜专属的（速度倍率要平方），
     * 因此留给子类算，基类只负责「够不够 → 扣多少 → 扣」这三步动作。</p>
     */
    protected int energyPerWorkTick() {
        return 0;
    }

    /**
     * 本 tick 算作「在干活」的槽数。
     *
     * <p>默认取<b>非空输入槽</b>数。选它而不是「有配方的槽数」是有意的：
     * 各家族的输入槽在放置时就已按本家族的可加工物过滤（旧切菜机器的
     * {@code isItemValid} 就是 {@code RecipeInputMatcher.matchesCutting}），
     * 所以「非空」≈「有配方」，而「有配方」需要执行器的缓存才知道，
     * 那是调用执行器之后才能得到的结论——用它来给「要不要调执行器」做前置判断会自相矛盾。</p>
     */
    protected int activeWorkSlots() {
        if (inputSlots == null) {
            return 0;
        }
        int active = 0;
        for (IInventorySlot slot : inputSlots) {
            if (!slot.isEmpty()) {
                active++;
            }
        }
        return active;
    }

    /**
     * 本 tick 是否<b>有活可干</b>（与红石、能量无关的第三条独立条件）。
     *
     * <p>默认「有非空输入槽」。这一条是旧实现里 {@code anyValid} 的位置：旧
     * {@code serverTick} 的三条件是 {@code canOperate && anyValid && 能量够}，
     * 少一条就把进度条清零。<b>漏掉它会怎样</b>：玩家在进度条走到一半时把原料取走，
     * 机器仍会继续把进度条填满、最后跑一次空转批次，而旧实现是立刻清零重来。
     * 那不是「慢一点」，是进度条语义变了——玩家看到的进度不再代表任何真实工作。</p>
     */
    protected boolean hasWorkToDo() {
        return activeWorkSlots() > 0;
    }

    /**
     * 本 tick 是否允许推进工作。
     *
     * <p>DISABLED / HIGH / LOW 三档直接用 Mek 自己的
     * {@link MekanismUtils#canFunction}，语义与旧实现的 {@code RedstoneControl.canFunction}
     * （{@link cn.ism.mekck.RedstoneControl} 的注释里写明「与 MekanismUtils.canFunction 相同」）
     * 逐档一致。</p>
     *
     * <h3>⚠️ PULSE 单独处理：旧语义是「跑完一整个批次」，不是「跑 1 tick」</h3>
     * {@code MekanismUtils.canFunction} 对 PULSE 的实现是
     * {@code isPowered() && !wasPowered()}（实测字节码：case 4 → isPowered，false 分支跳 104，
     * true 分支再判 wasPowered 为 true 则返回 false），也就是上升沿那一 tick 放行、其余全禁。
     * 本模组的旧实现是<b>锁存</b>的：收到上升沿后 {@code pulseRunning = true}，
     * 一直放到 {@code progress >= effectiveProcessTime} 那一批做完才复位。
     * 若直接用 Mek 的口径，一次脉冲只能推进 1/200 的进度条，等于 PULSE 功能作废。
     * 这里保留旧语义：{@link #workCycle} 跑完一整批时清锁存。</p>
     */
    protected boolean allowsWork() {
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

    // ── 随机化卡的三个机械分支（阶段 2 Task 4.7）──────────────────────
    //
    // 随机化卡本体（重随本局 49 种食物）是数据包层面的全局效果、与机器无关；
    // 它装到机器上之后才附带这三个机械分支。三个分支各自独立、**默认全是
    // {@code false}**：基类不能替其余 5 个家族决定「要不要免耗电 / 要不要 1 tick
    // 批次 / 要不要自动补满」——那是平衡决定。默认「都要」会让 5 个尚未接线的
    // 家族凭空白送三个收益，默认「都不要」则只是让它们保持与不装卡时一致。

    /**
     * 本机已装的某种升级数量。
     *
     * <p>旧实现读的是自研的 {@code MekCkUpgradeTracker.getInstalled()}（20 tick 安装读条），
     * 这里改读 {@code TileComponentUpgrade.getUpgrades(type)}——Mek 自己的升级组件里
     * 同样有 20 tick 安装读条（实测 {@code TileComponentUpgrade.tickServer} 里
     * {@code getUpgrades(type) < getMax()} 才推进），因此不需要再单独实现一套。
     *
     * <p>{@code getComponent()} 在构造期与读档路径上可能为 null，一律按 0 处理，
     * 与 {@link MekCkMachineTile#installLegacyUpgrades} 里「组件缺席就什么都不做」
     * 的口径一致。
     *
     * <p>刻意不缓存：读的是 {@code TileComponentUpgrade} 内部 {@code EnumMap} 的一格，
     * 成本可忽略；而缓存会与 Mek 自己的 20 tick 安装读条打架——
     * 刚放进去还没装好的那 20 tick 内不该提前生效。
     */
    protected int installedUpgrades(Upgrade type) {
        TileComponentUpgrade component = getComponent();
        return component == null ? 0 : component.getUpgrades(type);
    }

    /** 本机是否已装随机化卡（{@code mekck:upgrade_randomize}，上限 1 张）。 */
    protected boolean hasRandomizeUpgrade() {
        return installedUpgrades(MekCkUpgradeRefs.randomize()) > 0;
    }

    /**
     * 分支一「免耗电」：本机是否<b>完全跳过</b>本 tick 的能量扣减。
     *
     * <p>旧语义见 {@code CuttingMachineFactoryBlockEntity.serverTick} 第 414 行：
     * {@code int energyPerTick = activeSlots > 0 && !hasCreative ? mulClamp(...) : 0;}
     * <b>是把扣减额整个置 0</b>，不是「消耗乘 0」，也不是「只对某个阶段免」。
     * 置 0 之后能量闸门 {@code stored >= 0} 恒真（第 422 行），
     * 于是机器在<b>能量存量为 0</b> 时照样推进。
     */
    protected boolean randomizeGrantsFreeEnergy() {
        return false;
    }

    /**
     * 分支二「1 tick 批次」：本机是否把一个批次的进度门槛压到 1 tick。
     *
     * <p>旧语义见同文件第 387 行：
     * {@code int effectiveProcessTime = hasCreative ? 1 : Math.max(1, (int) (PROCESS_TIME / speedMult));}
     * 而 {@code PROCESS_TIME == 200}。即<b>整个门槛被换掉、不是把速度倍率调大</b>：
     * 装卡后一个批次 1 tick 走完，与速度卡无关。
     */
    protected boolean randomizeCollapsesWorkCycle() {
        return false;
    }

    /**
     * 分支三「自动补满」：本机是否每 tick 把能量容器补到上限。
     *
     * <p>旧语义见同文件第 378~380 行：{@code if (hasCreative) energy.receiveEnergy(
     * energy.getMaxEnergyStored() - energy.getEnergyStored(), false);}
     *
     * <p><b>补的是能量容器，不是任何物品槽。</b>旧实现里输入槽 / 产物槽都不受创造卡
     * 任何影响，AE2 补料路径也与之无关：旧 BE 的 {@code supportsAutoPull()} 恒为
     * {@code true}、{@code getNetworkPullInputs()} 不看卡。
     *
     * <p><b>触发条件只有「装了卡」一条</b>：旧代码把它放在红石判定与 {@code anyValid}
     * 判定<b>之前</b>且不带任何其它条件 ⇒ 机器停机、没放料、红石禁用时照样每 tick
     * 补满，能量条恒满。补能的位置与无条件性都照抄。
     */
    protected boolean randomizeRefillsEnergy() {
        return false;
    }

    /**
     * 「免耗电」闸门：<b>把扣减额整体置 0</b>，不是乘 0。
     *
     * <p>包级 {@code static} 是为了能进普通 JUnit（见
     * {@code cn.ism.mekck.machine.TestRandomizeUpgradeBranches}）：
     * 真 tile 在裸 JVM 里造不出来，而这处是整个分支唯一的算术。
     * 语义逐字取自旧 {@code CuttingMachineFactoryBlockEntity.serverTick} 第 414 行。</p>
     */
    static int gatedEnergyCost(boolean freeEnergy, int energyPerWorkTick) {
        return freeEnergy ? 0 : energyPerWorkTick;
    }

    /**
     * 从能量容器里扣掉本 tick 的耗电 —— {@link #workCycle} 的扣减<b>唯一</b>入口。
     *
     * <p><b>为什么必须用 {@link AutomationType#MANUAL} 而不是 EXTERNAL</b>：
     * 本机能量容器由 {@code MachineEnergyContainer.input(tile, listener)} 建成，
     * 而该工厂方法把 {@code notExternal} 传给了 <b>canExtract</b>、{@code alwaysTrue}
     * 传给了 <b>canInsert</b>（判据见 {@link #refillEnergyBuffer} 的注释）。
     * {@code BasicEnergyContainer.extract} 的开头是
     * 「{@code if (!canExtract.test(type)) return ZERO;}」（实测偏移 14~30），
     * 而 {@code notExternal} 就是 {@code type != EXTERNAL}（静态块偏移 33 绑定的
     * {@code lambda$static$2}），所以传 EXTERNAL 会被<b>整条拒掉、一 FE 都不扣</b>
     * ——机器照常加工、照常有进度条，能量条却永远不掉。各家族的能耗设计就是这样集体落空的。</p>
     *
     * <p>语义上也对得上：机械自己烧自己的电属于内部行为，不是「外部自动化在抽电」。
     * 顺带记一条边界：{@code notExternal} 只排除 EXTERNAL，{@link AutomationType#INTERNAL}
     * 同样放行；选 MANUAL 而非 INTERNAL 是为了与 {@link #refillEnergyBuffer} 保持一致，
     * 两者都是「机器自己动自己的能量容器」。</p>
     *
     * <p>做成静态方法是为了让这条行为<b>能在裸 JVM 里跑成断言</b>（真 tile 造不出来，
     * 见 {@code TestRandomizeUpgradeBranches} 的类注释），与本文件里
     * {@link #gatedEnergyCost} / {@link #gatedTicksPerCycle} / {@link #energyToRefill}
     * 是同一个理由。调用点只剩 {@link #workCycle} 一处。</p>
     */
    static void deductEnergy(IEnergyContainer container, int cost) {
        container.extract(FloatingLong.create(cost), Action.EXECUTE, AutomationType.MANUAL);
    }

    /**
     * 「1 tick 批次」闸门：把进度门槛整个换掉，<b>不碰速度倍率</b>。
     *
     * <p>旧第 387 行是 {@code hasCreative ? 1 : max(1, PROCESS_TIME / speedMult)}——
     * 有卡的那一支连除法都不做，所以速度卡对批次长度的影响在装卡后<b>完全消失</b>。
     * 无卡那一支保留基类的 {@code max(1, ...)} 下限：{@code ticksPerWorkCycle()} 覆写
     * 可能返回 0 或负数，闸门不能让进度条变成永不触发的除零。</p>
     */
    static int gatedTicksPerCycle(boolean collapseCycle, int ticksPerWorkCycle) {
        return collapseCycle ? 1 : Math.max(1, ticksPerWorkCycle);
    }

    /**
     * 「自动补满」的缺口：<b>已满或超容时返回 {@link FloatingLong#ZERO}</b>，调用方据此不插。
     *
     * <p><b>这道比较不是防溢出，是省一次空调用</b>（这点必须说清楚，否则会被当成多余的防御）：
     * {@code IEnergyContainer.getNeeded()} 是
     * {@code max(0, maxEnergy - stored)}，<b>本来就夹到 0</b>
     * （实测其 default 方法字节码偏移 0~21：{@code ZERO.max(maxEnergy.subtract(stored))}），
     * 所以直接把负缺口交给 {@code insert} 也只会走
     * 「{@code needed.isZero()} → 原样返回」那条分支，存量不会倒扣。
     * 保留这道比较的理由有两条，都可验证：① 装卡的机器每 tick 都会走到这里，
     * 已满时省掉一次 {@code insert} 调用；② 「满了就什么都不做」写成显式条件，
     * 比依赖「负数恰好落进 isZero 分支」可读。
     * {@link TestRandomizeUpgradeBranches#rawDeficitInsertIsAHarmlessNoOp()}
     * 把「灌负缺口不会倒扣」跑成了断言，免得后人再花时间怀疑这条。</p>
     */
    static FloatingLong energyToRefill(FloatingLong max, FloatingLong stored) {
        return max.compareTo(stored) > 0 ? max.subtract(stored) : FloatingLong.ZERO;
    }

    /**
     * 把能量容器补满（随机化卡的「自动补满」）。
     *
     * <p>「已经满了就别插」的理由在 {@link #energyToRefill} 的注释里，此处不复述。
     *
     * <p><b>这里的 {@link AutomationType#MANUAL} 确实是随意的</b>——本机能量容器由
     * {@code MachineEnergyContainer.input(tile, listener)} 建成，而它的<b>插入</b>侧谓词是
     * {@code alwaysTrue}，任何 AutomationType 都灌得进去，所以「卡把能量补满」这一行
     * 即使写成 EXTERNAL 也不会被拒。<b>但同样的理由不能套到抽取侧</b>，那里的谓词是
     * {@code notExternal}，见 {@link #deductEnergy} 的注释。
     * 顺带把这条实测的判据记全（方向与直觉相反）：{@code MachineEnergyContainer.input}
     * 把 {@code notExternal} 传给了 <b>canExtract</b>、{@code alwaysTrue} 传给了
     * <b>canInsert</b>；{@code BasicEnergyContainer} 的 4 参构造器里
     * {@code canExtract = 第 3 个形参}、{@code canInsert = 第 4 个形参}
     * （字节码偏移 19~21 与 24~26），两个形参的名字由 4 参 {@code create} 工厂里那两句
     * {@code requireNonNull} 的文案坐实：「Extraction validity check」/
     * 「Insertion validity check」。</p>
     */
    private void refillEnergyBuffer() {
        FloatingLong need = energyToRefill(energyContainer.getMaxEnergy(), energyContainer.getEnergy());
        if (need.isZero()) {
            return;
        }
        energyContainer.insert(need, Action.EXECUTE, AutomationType.MANUAL);
    }

    /**
     * 每 tick 驱动执行器 —— <b>能量闸门在这里</b>。
     *
     * <p>{@code onUpdateServer} 由静态 {@code TileEntityMekanism.tickServer} 在
     * {@code upgradeComponent.tickServer()} <b>之后</b>无条件调用（实测偏移 18 → 97），
     * 所以本 tick 刚装好的存储卡能立刻影响本 tick 的并行数。</p>
     *
     * <p>三段式与旧 {@code CuttingMachineFactoryBlockEntity.serverTick} 同序：
     * <ol>
     *   <li>能量物品补能（{@code fillContainerOrConvert}，Mek 自己的
     *       {@code TileEntityFactory.onUpdateServer} 偏移 8 处也是这一句）；</li>
     *   <li>随机化卡的「自动补满」（旧第 378~380 行，位置与无条件性照抄）；</li>
     *   <li>红石放行 + 有活可干 + 能量够 → 扣能量 → 进度条 +1；</li>
     *   <li>进度条满一个批次才调执行器，否则把进度清零并顺带释放 PULSE 锁存。</li>
     * </ol>
     * 任一条件不满足时进度条清零——与旧实现 {@code else { if (progress != 0) progress = 0; } } 逐字一致。</p>
     *
     * <p>红石读数为什么是当 tick 的新值：{@code TileEntityMekanism.tickServer} 在偏移 97
     * 调 {@code onUpdateServer()}，而在偏移 184~206 才做
     * {@code if (supportsRedstone()) redstoneLastTick = redstone}。
     * 也就是说 {@link #isPowered()} / {@link #wasPowered()} 在本方法里读到的确实是
     * 「本 tick / 上一 tick」两值，PULSE 的上升沿判定成立。</p>
     */
    @Override
    protected void onUpdateServer() {
        super.onUpdateServer();
        if (energySlot != null) {
            energySlot.fillContainerOrConvert();
        }
        // 随机化卡「自动补满」：放在闸门之前，与旧 serverTick 一样无条件执行——
        // 旧实现里它同样在红石判定与 anyValid 判定之前，停机时能量条也恒满。
        if (randomizeRefillsEnergy()) {
            refillEnergyBuffer();
        }
        workCycle();
        // AE2 放在 workCycle() 之后：本 tick 刚产出的物品要先落到产物槽，
        // MEckAe2 的产物回写才能在同一 tick 看到它们（与旧
        // CuttingMachineFactoryBlockEntity.serverTick 里「先干活、后
        // AE2Compat.autoProcessTick」的次序一致）。
        cn.ism.mekck.util.AE2Compat.serverTick(this, getLevel(), worldPosition);
        // 「已勾选材料 → 持续补料 + 产物回网」的第二段，与旧 serverTick 里紧跟在
        // AE2Compat.serverTick 之后的那行逐字对应。没接上这一段的表现是：
        // 节点在网、样板在终端里看得见，但勾了材料也不会自动补料。
        cn.ism.mekck.util.AE2Compat.autoProcessTick(this);
    }

    /** 闸门 + 进度条 + 执行器调度。拆出来只为让 {@link #onUpdateServer} 保持一屏可读。 */
    private void workCycle() {
        int cycle = effectiveTicksPerWorkCycle();
        // 免耗电：整个扣减额置 0（旧第 414 行的 `activeSlots > 0 && !hasCreative`）。
        // 置 0 后下面的 hasEnergyFor(0) 恒真，于是能量为 0 时机器照样推进——与旧实现同。
        int cost = gatedEnergyCost(randomizeGrantsFreeEnergy(), energyPerWorkTick());
        // 三个条件与旧 serverTick 的 canOperate && anyValid && 能量够 一一对应，
        // 次序也照旧：红石先判（最便宜），再判有没有活干，最后才去看能量。
        boolean allowed = allowsWork() && hasWorkToDo() && hasEnergyFor(cost);
        if (allowed) {
            if (cost > 0) {
                // AutomationType 的选择与理由都收在 deductEnergy 里，别在这里另传一个：
                // 本行原先写死 EXTERNAL，被容器的 canExtract=notExternal 整条拒掉，
                // 于是机器加工了一整个阶段却一 FE 都没扣（阶段 2 Task 4 交付的既有问题）。
                deductEnergy(energyContainer, cost);
            }
            if (++workProgress >= cycle) {
                workProgress = 0;
                executor().tick(this, inputSlots.size());
                // PULSE：跑完一整个批次才解除锁存（allowsWork 的锁存语义见其注释）。
                pulseLatched = false;
            }
        } else {
            if (workProgress != 0) {
                workProgress = 0;
            }
            if (pulseLatched) {
                pulseLatched = false;
            }
        }
        // active 口径与旧实现逐字一致：旧代码在 serverTick 末尾算 isActive = progress > 0，
        // 而那时 progress 刚被清零，所以「刚跑完一批」的那一 tick 机器就是不 active 的。
        setActive(workProgress > 0);
    }

    private boolean hasEnergyFor(int cost) {
        return cost <= 0 || energyContainer.getEnergy().compareTo(FloatingLong.create(cost)) >= 0;
    }

    /**
     * 实际生效的批次长度 —— {@link #workCycle} 的闸门与 {@link #getTicksPerWorkCycle}
     * <b>同走这一个方法</b>。
     *
     * <p>为什么不只在闸门里特判：GUI 的进度条分母读的是
     * {@link #getTicksPerWorkCycle}，两处口径一旦漂移，屏幕上就会画出与实际
     * 进度无关的比例。旧实现这里是<b>漂移的</b>——第 387 行的局部变量带 creative
     * 分支，而 GUI 读的 {@code getEffectiveProcessTime()}（第 993~995 行）不带，
     * 于是装卡时 GUI 一直显示 0/200。装卡后进度条本来每 tick 走完即清零、
     * 分子恒为 0，两种分母画出来都是 0，所以这个漂移没有可见症状；
     * 但把它带进新实现只会留下一个「以后有人靠分母算东西」的坑，故此处取一致口径。
     */
    private int effectiveTicksPerWorkCycle() {
        return gatedTicksPerCycle(randomizeCollapsesWorkCycle(), ticksPerWorkCycle());
    }

    /** 进度条已走的 tick 数（GUI 用）。 */
    public int getWorkProgress() {
        return workProgress;
    }

    /** 完成一个批次需要的 tick 数（GUI 画进度条分母用）。 */
    public int getTicksPerWorkCycle() {
        return effectiveTicksPerWorkCycle();
    }

    // ── 持久化 ──────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>只写执行器自有状态、进度条、格式版本与<b>专属的 int 下标槽位数据</b>：
     * 能量、侧配、升级、频率仍然由 {@code TileEntityMekanism} 自己的
     * {@code saveAdditional} 写，重复写会互相覆盖。</p>
     *
     * <h3>为什么槽位要再写一份（MekCK 权威，阶段 2 Task 4.8）</h3>
     * Mek 的 {@code mekanism.api.DataHandlerUtils.writeContents} 用 {@code putByte} 存槽位下标，
     * 而 {@code readContents} 的 {@code getByte} 遇负值直接跳过（实测字节码
     * {@code writeContents} 偏移 50~53 {@code i2b; putByte}、
     * {@code readContents} 偏移 35~37 {@code iflt}）。{@code byte} 上限 127，
     * 于是 {@code SINGULARITY}（81 并行 ⇒ 2N = 162）的第 128~161 号那 34 个输出槽
     * 与第 162 号的能量槽<b>每次存读档静默丢失，无任何日志</b>。
     *
     * <p>因此这里在 {@code super.saveAdditional} <b>之后</b>把同一组槽位按 int 下标
     * 写进 {@link MekCkSlotNbt#TAG_SLOTS} 专属键。Mek 那份 byte 存档照写不误
     * （不破坏任何 Mek 自己的读档路径），只是对 MekCK 的机器不再是权威来源。
     * 为什么不用 Mixin 改 {@code DataHandlerUtils}，见 {@link MekCkSlotNbt} 的类注释。</p>
     */
    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.write(mekckPersistedSlots()));
        CompoundTag executorTag = new CompoundTag();
        executor().save(executorTag);
        tag.put(TAG_EXECUTOR, executorTag);
        tag.putInt(TAG_WORK_PROGRESS, workProgress);
        tag.putInt(TAG_NATIVE_VERSION, NATIVE_VERSION);
        // AE2 网格节点的 NBT 必须与节点一同存活（阶段 2 Task 4.6）：
        // 节点里存着频道占用与「已勾选的自动处理材料」，不写就等于每次重载
        // 都换一批频道。AE2Compat 未装时整个方法短路为空操作。
        cn.ism.mekck.util.AE2Compat.saveAdditional(this, tag);
        // 放置者归属（网络厨师学徒）：与旧 BE 的 saveAdditional 逐字同款，
        // 同样不依赖 AE2 是否安装。
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
    }

    /**
     * {@inheritDoc}
     *
     * <p>1.20.1 的读档入口是 {@code load}，<b>没有</b> 1.20.4+ 的 {@code loadAdditional}。</p>
     *
     * <p>{@code getCompound} 对缺失键返回<b>空标签而非 null</b>，正好落在
     * {@link MekCkRecipeExecutor#load} 的「旧存档无此键时保持默认态」契约上，
     * 因此不需要额外的 {@code contains} 分支。进度条同理：
     * {@code getInt} 对缺失键返回 0，等价于「刚放下的机器从 0 开始」。</p>
     *
     * <p><b>红石模式不在这里读</b>：{@code controlType} 是 {@code TileEntityMekanism} 的
     * 私有字段，由它自己的 {@code loadGeneralPersistentData} 负责，本类重复读会互相覆盖。</p>
     *
     * <h3>旧存档迁移（阶段 2 Task 4.5）</h3>
     * 方块注册名没变、变的是 {@code BlockEntityType} 的实现类，所以旧存档的内容
     * 不会有人去读——方块还在，里面空了。这里按 {@link MekCkLegacyMachineNbt#isLegacy}
     * 分流：旧格式先整体翻译成新格式再交给 {@code super.load}，新格式原样走。
     * 一次性翻译而不是「兼容读旧键」，理由见 {@link MekCkLegacyMachineNbt} 的类注释。
     *
     * <p>迁移必须在 {@code super.load} <b>之前</b>完成：旧格式的 {@code Items} 是
     * CompoundTag、新格式是 ListTag，同名不同型，若不先换掉，
     * {@code TileEntityMekanism.load} 里的 {@code getList("Items", 10)} 会静默取到
     * 空列表，整机槽位归零且不报错。</p>
     *
     * <p>升级计数则必须落在 {@code super.load} <b>之后</b>：
     * {@code TileComponentUpgrade.read} 的第一件事就是
     * {@code upgrades.clear(); upgrades.putAll(buildMap(tag))}（实测其
     * {@code lambda$read$1} 偏移 0~17），先灌后读会被这一次 clear 抹掉。</p>
     *
     * <h3>专属槽位数据同样必须在 {@code super.load} 之后（阶段 2 Task 4.8）</h3>
     * {@code super.load} 会让 Mek 按 byte 下标把 {@code Items} 灌进槽位，
     * 第 128 号起的那些被它整条跳过。我们随后用 int 下标的那份<b>覆盖</b>回来。
     * 顺序反了等于没写这一层——而这正是本缺陷的形态：静默丢数据、没有任何日志。
     * 另有 {@code TestMekCkSlotNbt#mekckSlotReadIsAppliedAfterSuperLoad} 把这条顺序钉死。</p>
     */
    /**
     * 本机是否接受旧格式存档的自动迁移 —— 默认是。
     *
     * <h3>为什么需要这个开关</h3>
     * {@link MekCkLegacyMachineNbt#migrate} 是<b>按并行方阵的槽位下标</b>推算的：
     * 它用 {@code inputSlotCount * 2} 划出「输入 + 输出」那一段，把紧随其后的
     * 几格当成升级卡槽、把最后一格当成能源槽。穿串工厂的排布不是这样：
     * <pre>
     *   [0, 3)   输入（签 / 主料 / 辅料）
     *   [3, 5)   输出（产物 + 返还）
     *   [5, 9)   速度 / 能量 / 堆叠 / 创造 升级卡（有没有堆叠看档位）
     *   [9, 88)  存储 81 格
     *   Size-1   能源槽
     * </pre>
     * 拿 {@code 3 * 2 = 6} 当机器段末端，<b>第 5 号格（速度升级卡）会被当成普通机器槽
     * 灌进新 tile 的能源槽</b>。这不是「迁移得不完美」，是静默把内容放错格子。
     *
     * <p>因此家族形态与并行方阵不同时必须覆写成本方法返回 {@code false}：
     * 旧存档的内容不搬（机器读档后是空的），比放错格子安全。
     * 与 {@code mekckfactory} 那 84 个死命名空间方块同款——它们本来就没有兜底，
     * 留到阶段 4 统一处理。</p>
     */
    protected boolean migratesLegacyNbt() {
        return true;
    }

    @Override
    public void load(CompoundTag tag) {
        CompoundTag legacyTag = migratesLegacyNbt() && MekCkLegacyMachineNbt.isLegacy(tag) ? tag : null;
        if (legacyTag != null) {
            // 档位为 null 时按「无机器槽位」处理：正常情况下构造器早就抛了
            // IllegalStateException，真到这里说明方块被换成了非工厂方块。
            // 此时宁可让旧内容被逐条记日志丢掉，也不要在区块加载路径上抛 NPE。
            CuttingMachineFactoryTier tier = getTier();
            tag = MekCkLegacyMachineNbt.migrate(legacyTag, getDirection(), tier == null ? 0 : tier.processes);
        }
        super.load(tag);
        // 专属键不存在时 read 返回 false、什么都不做：那种档只有 Mek 那份 byte 存档，
        // 退化到修复前的行为（低端位照常读、高端位照常丢），而不是报错或清空。
        MekCkSlotNbt.read(tag, mekckPersistedSlots());
        if (legacyTag != null) {
            installLegacyUpgrades(legacyTag);
        }
        int version = tag.getInt(TAG_NATIVE_VERSION);
        if (version > NATIVE_VERSION) {
            LOGGER.warn("工厂方块 {} 的存档格式版本为 {}，高于本版本 MekCK 支持的 {}，"
                            + "该机器的执行器进度可能不完整。", getBlockType(), version, NATIVE_VERSION);
        }
        workProgress = Math.max(0, tag.getInt(TAG_WORK_PROGRESS));
        executor().load(tag.getCompound(TAG_EXECUTOR));
        // AE2 网格节点的 NBT（阶段 2 Task 4.6）：必须在 super.load 之后——
        // 它的 loadFromNBT 只是把整个 tag 缓存成 pendingTag，真正建节点要等
        // 下一次 serverTick 的 init()，与旧 BE 的调用位置一致。
        cn.ism.mekck.util.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
    }

    /**
     * AE2 网格节点随方块卸载一并销毁（阶段 2 Task 4.6）。
     *
     * <p>与旧 {@code CuttingMachineFactoryBlockEntity.setRemoved} 逐字同款：
     * 不断的话，AE2 侧会一直认为这台机器还占着 8 个频道（主节点 + 7 内部节点），
     * 表现为拆掉再放一台就接不上网络。</p>
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        cn.ism.mekck.util.AE2Compat.onRemoved(this);
    }

    /**
     * 参与 MekCK 专属 int 下标存档的槽位组，顺序与 {@link #getInitialInventory} 里
     * 往 builder 加槽的顺序<b>逐位一致</b>：{@code [0,N) 输入、[N,2N) 输出、[2N] 能量槽}。
     *
     * <p>下标必须与 Mek 的 byte 下标指向同一个槽，所以这里不能自行重排、
     * 也不能漏掉能量槽——{@code 2N} 号那格正是随 {@code SINGULARITY} 一起越界的那一格。
     * 另注：{@code componentUpgrade} 的两张升级卡槽由 Mek 自己存（只有 2 格，
     * byte 下标绰绰有余），不在本列表内。</p>
     *
     * <p>每次都新建列表而不是缓存：一次 {@code ArrayList} 分配在存档路径上
     * 相对 NBT 序列化本身可以忽略，换来的是不可能有人往这个列表里塞脏数据的保证。</p>
     */
    private List<IInventorySlot> mekckPersistedSlots() {
        List<IInventorySlot> all = new ArrayList<>(2 * inputSlots.size() + 1);
        all.addAll(inputSlots);
        all.addAll(outputSlots);
        all.add(energySlot);
        return all;
    }

    /**
     * 把旧存档 4 个升级计数器的已装数量灌进 {@link TileComponentUpgrade}。
     *
     * <p><b>刻意走内存而不是 NBT</b>：阶段 1 的
     * {@code MixinTileComponentUpgradePersistence} 已经把
     * {@code TileComponentUpgrade.read} 的解码换成名字键，但那只在游戏内生效；
     * 走内存则这条迁移路径与 Mixin 无关，普通 JUnit 里也测得到。</p>
     *
     * <p><b>灌「补差额」而不是「直接装 N 张」</b>：同一份旧存档被 load 两次
     * （区块重载、任务 4.6 的网络包触发局部重载）时第二次补 0，不会翻倍。
     * 上限用 {@code MekCkUpgradeTypes.capOf} 裁，与原生读档路径
     * （{@code MekCkUpgradeTypes.decode}）同一道闸，存档里的越界值不会被放行。</p>
     *
     * <p>旧创造卡（{@code CreativeUpgradeTracker}）对应新的随机化卡：两者都只提供
     * 「随机化本局 49 种可用食物」这一项能力，且随机化卡在 {@code isSupportedBy} 的
     * 放行集内。卡种本身换了（{@code mekanism_extras:upgrade_creative} →
     * {@code mekck:upgrade_randomize}），能力保留、数量照搬。</p>
     */
    private void installLegacyUpgrades(CompoundTag legacyTag) {
        TileComponentUpgrade component = getComponent();
        if (component == null) {
            return;
        }
        int[] counts = MekCkLegacyMachineNbt.legacyUpgradeCounts(legacyTag);
        Upgrade[] targets = {
                Upgrade.SPEED,
                Upgrade.ENERGY,
                MekCkUpgradeRefs.storage(),
                MekCkUpgradeRefs.randomize()
        };
        for (int i = 0; i < targets.length; i++) {
            Upgrade type = targets[i];
            int cap = MekCkUpgradeTypes.capOf(type, getTier());
            int delta = MekCkLegacyMachineNbt.upgradesToInstall(component.getUpgrades(type), counts[i], cap);
            if (delta > 0) {
                component.addUpgrades(type, delta);
            }
        }
    }
}
