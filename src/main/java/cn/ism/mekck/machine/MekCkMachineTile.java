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
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.config.slot.InventorySlotInfo;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.TileComponentUpgrade;
import mekanism.common.tile.interfaces.IRedstoneControl;
import mekanism.common.tile.interfaces.ISustainedData;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
public abstract class MekCkMachineTile extends TileEntityConfigurableMachine
        implements ISustainedData, cn.ism.mekck.ae2.INetworkPullable {

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
    /** NBT：进度条已走的 tick 数 —— <b>旧格式</b>的单个 int，只读兼容（见 {@link #readMekckPersistentState}）。 */
    static final String TAG_WORK_PROGRESS = "MekCkWorkProgress";
    /** NBT：每路并行各自的进度（int 数组）。 */
    static final String TAG_WORK_PROGRESS_ARRAY = "MekCkWorkProgressArray";
    /** NBT：输入槽自动分选开关。与 Mek 的 {@code NBTConstants.SORTING} 同义（键名不同，避免与 Mek 的键撞车）。 */
    static final String TAG_SORTING = "MekCkSorting";
    /** 当前存档格式版本。v1 是首个 Mek 原生版本；v2 把进度条从单个 int 换成每路一个 int。 */
    private static final int NATIVE_VERSION = 2;

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

    /**
     * 本机是否走「一行式」布局 —— 在 {@link #getInitialInventory} 里按槽数判定并赋值。
     *
     * <p>给 {@link #appendExtraSlots} 的子类用：一行式下槽区只占 y=13..75，
     * 家族专属槽（调味料 / 营养液 / 生长土）必须挪到 y=77 那一行，否则会和输出槽重叠。
     * 也供屏幕侧查（面板尺寸要跟着变）。</p>
     *
     * <p>值在构造期（父类构造器回调 {@code getInitialInventory}）就定好，
     * 之后不再变，所以读取是安全的。</p>
     */
    protected boolean oneRowLayout;

    /**
     * 本机是否走「输入/输出进悬浮窗」布局 —— 见 {@link #getInitialInventory} 里的判定。
     *
     * <p>为 true 时输入/输出槽的容器槽是虚拟槽（{@code ContainerSlotType.IGNORED}），
     * 不参与主面板渲染，由 {@code MekCkSlotWindow} 以 9 列区块显示。</p>
     */
    protected boolean windowLayout;

    /**
     * 悬浮窗的窗口身份 —— 存储槽与「输入/输出槽」<b>共用同一个实例</b>。
     *
     * <h3>为什么必须共享</h3>
     * {@code VirtualInventoryContainerSlot.exists(windowData)} 的实现是
     * {@code this.windowData.equals(windowData)}，而 {@code MekanismContainer.quickMoveStack}
     * 与 {@code insertItem} 都拿「当前选中的窗口」去过滤候选槽。共用一份身份，
     * 才能让「窗口里看得见的槽」与「shift-click 能进的槽」严格一致。
     *
     * <h3>为什么不能拆成两个（原先拆过，是无效的）</h3>
     * {@code SelectedWindowData.equals} 只比 {@code type} 与 {@code extraData}
     * （实测字节码偏移 29-56），而 {@code WindowType.UNSPECIFIED} 的 {@code maxData == 1}
     * ⇒ {@code isValid(extraData)} 只接受 0 ⇒ <b>两个 UNSPECIFIED 实例必然相等</b>。
     * 拆成两个常量只是让读者以为它们不同，实际行为一模一样。
     *
     * <p>而且拆开是<b>错的</b>：{@code MekCkSlotWindowTab} 会把非空的组（输入 / 输出 / 存储）
     * 放进<b>同一扇窗</b>，所以三类槽必须共享身份——否则窗口里看得见、shift-click 却进不去。
     * 今天没有任何家族同时具备两类窗口槽（{@code windowLayout} 要求输入输出对称且 &gt;17，
     * 而带存储槽的烹饪 6/12、穿串 3/2 都不对称），但共享身份在那种情况下同样是正确的。</p>
     *
     * <p>为什么用 {@code WindowType.UNSPECIFIED}：该枚举是 Mek 的固定值
     * （{@code COLOR/CRAFTING/SIDE_CONFIG/UPGRADE/...}），加不进去；
     * 参照实现 productive-bees-genesis 的 {@code FeederInventorySlot} 也是这么绕的。
     * 代价是窗口位置按 {@code "unspecified"} 这一个键共享
     * （{@code getSaveName} 返回的是字符串 {@code "unspecified"}，<b>不是</b> null），
     * 不会按机器分别记忆。</p>
     */
    public static final SelectedWindowData SLOT_WINDOW =
            new SelectedWindowData(SelectedWindowData.WindowType.UNSPECIFIED);

    /** 本机是否走一行式布局（构造完成后恒定）。 */
    public boolean usesOneRowLayout() {
        return oneRowLayout;
    }

    /** 本机是否把输入/输出槽放进悬浮窗（构造完成后恒定）。 */
    public boolean usesWindowLayout() {
        return windowLayout;
    }

    /**
     * 本机输入槽数（构造完成后恒定）。
     *
     * <p>给换档路径用：{@link MekCkSlotNbt} 的 int 下标存档在换档后必须按角色重映射，
     * 而重映射需要知道新旧两套布局的输入/输出边界。</p>
     */
    public int inputSlotCount() {
        return inputSlots == null ? 0 : inputSlots.size();
    }

    /** 本机输出槽数（构造完成后恒定）。见 {@link #inputSlotCount()}。 */
    public int outputSlotCount() {
        return outputSlots == null ? 0 : outputSlots.size();
    }

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

    // ── AE2 网络拉料（docs/2026-09-30-功能实现口径.md §2.4：基类统一实现，不给每个家族各写一份）──
    //
    // INetworkPullable 是旧方块实体时代的契约，{@code getNetworkPullItems()} 的返回类型被
    // 写死成 Forge 的 {@code ItemStackHandler}；本 tile 的槽是 Mek 的 {@link IInventorySlot}，
    // 中间用 {@link MekCkSlotHandler} 只读视图适配（不持有 ItemStack，读写直接落到槽对象，
    // 视图与真身永不失同步）。接口补齐后：6 个工厂家族 + 电力烧烤架自动拿回「网络拉料 /
    // 自动补料」tab（{@code NetworkPullButton} 按 {@code instanceof} 判定）与面板 ME 下单。

    /** {@link MekCkSlotHandler} 视图缓存。{@code getInitialInventory} 每实例只跑一次，懒加载即可。 */
    private MekCkSlotHandler pullItemsView;

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity getNetworkPullable() {
        return this;
    }

    @Override
    public int[] getInputSlotRange() {
        // 区间是 **MekCkSlotHandler 视图的下标**（视图只含输入槽），不是 menu.slots 下标。
        // inputSlots 在父类构造器内经 getInitialInventory 赋值；null 只出现在构造期窗口。
        return inputSlots == null ? new int[]{0, 0} : new int[]{0, inputSlots.size()};
    }

    @Override
    public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() {
        MekCkSlotHandler view = pullItemsView;
        if (view == null) {
            view = new MekCkSlotHandler(getInputSlots());
            pullItemsView = view;
        }
        return view;
    }

    @Override
    public boolean supportsAutoPull() {
        // 与制冰工厂的遗留实现同口径：按「每类型上限」（配置 auto_pull_stack_limit）批量补，
        // LagMonitor 限流在 MekckAe2 侧。
        return true;
    }

    @Override
    public List<cn.ism.mekck.ae2.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) {
            return List.of();
        }
        MekCkFactoryType family = getFactoryType();
        ResourceLocation typeId = family == null ? null : networkPullRecipeTypeId(family);
        if (typeId == null) {
            return List.of();
        }
        List<IInventorySlot> inputs = getInputSlots();
        ItemStack slot0 = inputs.isEmpty() ? ItemStack.EMPTY : inputs.get(0).getStack();
        return cn.ism.mekck.ae2.NetworkPullHelper.currentOrUnion(level, slot0, typeId);
    }

    /**
     * 家族 → 拉料配方类型 id（{@code NetworkPullHelper.currentOrUnion} 的「当前或并集」口径
     * 需要一个类型 id）。注册名与各家族 Executor 的实际查找逐字对齐：
     * 切菜走 FarmersDelight 的 cutting、烹饪走 FarmersDelight 的 cooking
     * （烹饪家族另有 farm_and_charm / avaritia_delight 扩展类型，拉料口径暂取主类型，
     * 与遗留单机的单类型局限一致）；种植切配的注册名是 {@code plantcut}（不是
     * {@code planting_cutting}），制冰是 {@code ice_make} —— 都以 MekCkRecipeTypes 的
     * 注册名为准，不能由家族名推导。
     */
    protected ResourceLocation networkPullRecipeTypeId(MekCkFactoryType family) {
        return switch (family) {
            case CUTTING -> new ResourceLocation("farmersdelight", "cutting");
            case PLANTING_CUTTING -> new ResourceLocation("mekck", "plantcut");
            case COOKING -> new ResourceLocation("farmersdelight", "cooking");
            case SKEWERING -> new ResourceLocation("mekck", "skewering");
            case GRILLING -> new ResourceLocation("mekck", "grilling");
            case GRINDING -> new ResourceLocation("mekck", "grinding");
            case ICE -> new ResourceLocation("mekck", "ice_make");
        };
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
        // ⚠️ 这里把 Mek 传进来的 listener 包一层，好让槽位变化也能触发家族钩子
        // （种植切配靠它把营养液槽里的容器灌进罐）。
        //
        // 刻意**不**用「字段保存一个 IContentsListener 再传下去」：本方法由
        // TileEntityMekanism 的构造器调用，而字段初始化器在 super(...) 之后才跑，
        // 那一刻读到的 final 字段是 null ⇒ 所有槽位都拿到 null listener
        // ⇒ 内容变化回调静默失效。局部变量没有这个时序问题。
        final IContentsListener combined = () -> {
            listener.onContentsChanged();
            onFamilyContentsChanged();
        };
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

        // ── 两种布局，判据只有一个 ─────────────────────────────────────
        // 「一行式」= Mek 与 Mekanism Extras 的做法（真源码见 MekCkFactoryLayout）：
        // 输入一行 y=13、输出在其正下方 y=57，同 x 成对，间距随档位压缩 38→26→19。
        // 它只覆盖 ≤17 并行（Mek 的 4 档 + MekExtras 的 4 档 = 我们前 8 档）。
        //
        // 判据额外要求「输入槽数 == 输出槽数」：烹饪（6 输入 / 12 输出）与穿串
        // （3 输入 / 2 输出）的槽数与并行数**脱钩**，套一行式会把它们错位；
        // 这两个家族因此自动落回方阵分支。
        this.oneRowLayout = inputCount == outputCount
                && cn.ism.mekck.menu.MekCkFactoryLayout.useOneRow(inputCount);

        // 「进悬浮窗」= 输入输出对称、但格数超过一行式上限（>17 并行：烈焰炽焱 25 /
        // 晶钛矩阵 36 / 星云塑造 49 / 奇点创世 81）。奇点创世是 81 进 + 81 出 = 162 格，
        // 留在主面板会把面板撑到 412×310；放进悬浮窗后按 9 列分两块并排，一页显示完。
        //
        // 烹饪(6 进/12 出)与穿串(3 进/2 出)不对称 ⇒ 不进窗口，仍走主面板方阵。
        this.windowLayout = !oneRowLayout
                && inputCount == outputCount
                && inputCount > cn.ism.mekck.menu.MekCkFactoryLayout.ONE_ROW_MAX_PROCESSES;

        if (oneRowLayout) {
            // ⚠️ 必须「先加完全部输入、再加全部输出」：Mek 的 byte 下标存档与
            // {@link #mekckPersistedSlots()} 都按「加入 builder 的顺序」编号，
            // 交叉加入（进0/出0/进1/出1…）会让两份下标对不上 → 存读档错位。
            for (int i = 0; i < inputCount; i++) {
                int x = cn.ism.mekck.menu.MekCkFactoryLayout.oneRowSlotX(i, inputCount);
                inputSlots.add(builder.addSlot(MekCkSlot.input(slotLimit, combined, x,
                        cn.ism.mekck.menu.MekCkFactoryLayout.ONE_ROW_INPUT_Y)));
            }
            for (int i = 0; i < outputCount; i++) {
                int x = cn.ism.mekck.menu.MekCkFactoryLayout.oneRowSlotX(i, outputCount);
                outputSlots.add(builder.addSlot(MekCkSlot.output(slotLimit, combined, x,
                        cn.ism.mekck.menu.MekCkFactoryLayout.ONE_ROW_OUTPUT_Y)));
            }
        } else if (windowLayout) {
            // 输入/输出整块进悬浮窗。这里仍按方阵坐标给：虚拟槽的容器槽坐标恒为 (0,0)，
            // 实际渲染位置由 GuiVirtualSlot 写入，所以这些坐标只是调试时可读的「第几路」。
            // ⚠️ 与一行式同一条铁律：**先全部输入、再全部输出**，否则容器槽下标
            // 与 mekckPersistedSlots() 对不上 → 存读档错位。
            for (int i = 0; i < inputCount; i++) {
                int x = INPUT_START_X + (i % inputColumns) * SLOT_STEP;
                int y = GRID_START_Y + (i / inputColumns) * SLOT_STEP;
                inputSlots.add(builder.addSlot(
                        MekCkSlot.windowInput(slotLimit, SLOT_WINDOW, combined, x, y)));
            }
            for (int i = 0; i < outputCount; i++) {
                int x = INPUT_START_X + (i % outputColumns) * SLOT_STEP;
                int y = GRID_START_Y + (i / outputColumns) * SLOT_STEP;
                outputSlots.add(builder.addSlot(
                        MekCkSlot.windowOutput(slotLimit, SLOT_WINDOW, combined, x, y)));
            }
        } else {
            // 方阵：输入在左、输出整体右移「**输入**方阵宽度 + 间隔」。
            // 间隔按输入宽度算而不是各自宽度：并行方阵两者相等，穿串工厂的
            // 「3 宽输入 + 1 宽输出」也正好是旧版 38/130 的间距。
            addSlotGrid(builder, combined, INPUT_START_X, inputCount, inputColumns, slotLimit, true);
            addSlotGrid(builder, combined, INPUT_START_X + inputColumns * SLOT_STEP + GRID_GAP,
                    outputCount, outputColumns, slotLimit, false);
        }
        energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, combined,
                ENERGY_SLOT_X, ENERGY_SLOT_Y);
        builder.addSlot(energySlot);
        appendExtraSlots(builder, combined);
        registerExtraSlotsAsDataType(extraSlotsForConfig());
        return builder.build();
    }

    /**
     * 家族专属槽（调味料 / 营养液 / 生长土 / 存储）—— 供 {@link #registerExtraSlotsAsDataType} 登记。
     *
     * <p>默认空。**家族必须覆写它并返回 {@code appendExtraSlots} 里加的那些槽**，
     * 否则它们不属于任何 {@code DataType}（后果见 {@link #registerExtraSlotsAsDataType}）。</p>
     *
     * <p>返回活列表即可（{@code appendExtraSlots} 已在本方法调用前填好）；
     * 本方法只读不存。</p>
     */
    protected List<IInventorySlot> extraSlotsForConfig() {
        return List.of();
    }

    /**
     * 把家族专属槽登记为 {@code DataType.EXTRA} —— <b>照 Mek 的 {@code TileEntityFactory} 抄的</b>。
     *
     * <h3>不登记会怎样（两条都是静默的）</h3>
     * <ol>
     *   <li><b>贴图错</b>：{@code GuiMekanism.addSlots()} 选槽位贴图的优先级是
     *       「先按侧配 {@code DataType}（{@code findDataType}），查不到再退回
     *       {@code ContainerSlotType}」——而退回时 {@code INPUT/OUTPUT/EXTRA} 一律映射成
     *       {@code SlotType.NORMAL}（见其真源码第 512-513 行）。所以未登记的槽
     *       <b>无论 {@code setSlotType} 设成什么，都画成 {@code normal.png}</b>。</li>
     *   <li><b>侧配里看不见</b>：{@code ISideConfiguration.getActiveDataType(container)} 是按
     *       「槽在哪个 {@code SlotInfo} 里」反查的，不在任何列表里就返回 {@code null}，
     *       于是这些槽在侧配 GUI 中不存在、外部自动化（漏斗 / AE2）对它们的可见性也不明确。</li>
     * </ol>
     *
     * <p>Mek 自己的 {@code TileEntityFactory}（真源码 133-139 行）就是这么做的：
     * {@code addSlotInfo(DataType.EXTRA, new InventorySlotInfo(true, true, extraSlot))}
     * 并 {@code setDataType(DataType.EXTRA, RelativeSide.BOTTOM)}。
     * 这里只做前半句（登记），<b>不设默认面</b>：默认面会改变管道/漏斗对这几个槽的既有行为，
     * 属于需要单独决策的变更，不顺手打开。</p>
     *
     * @param extraSlots 额外槽；空列表时什么都不做
     */
    protected void registerExtraSlotsAsDataType(List<IInventorySlot> extraSlots) {
        if (extraSlots == null || extraSlots.isEmpty()) {
            return;
        }
        TileComponentConfig config = getConfig();
        if (config == null) {
            return;
        }
        ConfigInfo itemConfig = config.getConfig(TransmissionType.ITEM);
        if (itemConfig == null) {
            return;
        }
        // canInput = true, canOutput = true：与 Mek 对 EXTRA 槽的取值一致（两侧都放行），
        // 具体能否被自动化访问仍由槽自己的 canInsert/canExtract 谓词把关。
        itemConfig.addSlotInfo(DataType.EXTRA, new InventorySlotInfo(true, true, extraSlots));
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
     * <p><b>为什么不用 {@code executor().isBusy()}</b>：执行器只在「某一路跑完一个批次」的
     * 那一 tick 被调用（见 {@link #workCycle}），它内部的 {@code busy} 标志在那一 tick 置位后
     * 一直保持到下一次调用。若拿它当忙碌态，机器跑完第一批之后就会永远显示「在忙」。
     * 进度是逐 tick 更新的，天然没有这种陈旧问题。</p>
     *
     * <p>「任一路有进度」= 在忙。客户端读的是同步过来的那一份数组，
     * 否则 {@code GuiProgress.isActive()} 在客户端恒为 false，进度条连底图都不画。</p>
     */
    public boolean isBusy() {
        int[] progress = workProgress;
        if (progress == null) {
            return false;
        }
        for (int value : progress) {
            if (value > 0) {
                return true;
            }
        }
        return false;
    }

    // ── 能量闸门与进度条 ────────────────────────────────────────────────
    //
    // 这里是旧 serverTick 的「红石 → 能量 → 干活」三段式在 Mek 体系下的落点。
    // 执行器只负责「本 tick 尽可能多地加工」，不判断该不该加工，也不碰能量与进度条。

    /**
     * 每路并行各自的进度（tick）—— <b>一路一个独立计时器</b>，与 Mek 的
     * {@code TileEntityFactory.progress} 同构。
     *
     * <p>长度由 {@link MekCkRecipeExecutor#processCount} 定下，之后<b>永不重新分配</b>：
     * {@link #addContainerTrackers} 里 {@code container.trackArray} 的每个
     * {@code SyncableInt} 都直接持有这个数组的引用，换一个实例等于把同步通道指向
     * 一块没人再写的内存。</p>
     *
     * <p>服务端与客户端<b>共用同一个字段</b>（Mek 的做法）：服务端它是权威值，
     * 客户端由同步 setter 写进来。所以读取侧不需要按端分流 —— 这与
     * {@code clientOrderActive} 那几个镜像字段不同。</p>
     */
    private int[] workProgress;
    /** PULSE 锁存：收到上升沿后一直放行，直到跑完一个完整批次。 */
    private boolean pulseLatched;

    /**
     * 逐路告警位 —— {@code true} 表示「这一路喂了料却做不出产物」。
     *
     * <p>与 {@link #workProgress} 同构：服务端在 {@link #workCycle} 里写，客户端由
     * {@code container.trackArray} 的同步 setter 写进来，读取侧不按端分流。
     * 长度与进度数组一致，同样<b>只分配一次</b>（理由见 {@link #progressArray()}）。</p>
     *
     * <h3>为什么在服务端算而不是让 GUI 自己调 canProcess</h3>
     * 这是 Mek 的做法（{@code TileEntityFactory.ErrorTracker} +
     * {@code container.trackArray(trackedErrors)}）。GUI 的告警供给器每帧都会求值，
     * 而 {@code canProcess} 会写执行器的 {@code tile} / {@code busy} 字段并做配方查找；
     * 让客户端每帧跑一遍既浪费又可能污染执行器状态。服务端每 tick 算一次、按脏值下发，
     * 代价恒定。</p>
     */
    private boolean[] laneWarnings;

    /**
     * 整机能量告警 —— {@code true} 表示「有活干但电不够」。
     *
     * <p>与 Mek 的 {@code RecipeError.NOT_ENOUGH_ENERGY} 同义，挂在竖直能源条上。
     * 服务端与客户端共用同一个字段（Mek 的做法）。</p>
     */
    private boolean notEnoughEnergy;

    /**
     * 输入槽自动分选开关 —— 与 Mek 的 {@code TileEntityFactory.sorting} 同义。
     *
     * <p>默认<b>关</b>（Mek 也是默认关）：分选会挪动玩家亲手摆好的槽位，
     * 必须由玩家显式打开。服务端与客户端共用同一个字段，客户端由
     * {@code container.track} 的同步 setter 写进来（标签页要显示 On/Off）。</p>
     */
    private boolean sorting;
    /**
     * 「输入槽变过、需要重新分选」的脏标记 —— 与 Mek 的 {@code sortingNeeded} 同义。
     *
     * <p>初值 true：机器刚放下时也要分选一次（Mek 同款）。</p>
     */
    private boolean sortingNeeded = true;

    /**
     * 上一 tick 实际消耗的能量 —— 能源 tab 的「使用量」读数。
     *
     * <p>与 Mek 的 {@code TileEntityFactory.lastUsage} 同款：{@code 在干活 ? 本 tick 的能量差 : 0}。
     * 此前六个工厂屏传的是 {@code tier.energyPerTick}（等级<b>声明值</b>），与机器真实扣电量
     * 无关 —— 免能耗档（星云 / 奇点）会显示一个非零读数，而装了速度卡之后真实耗电翻倍、
     * 读数却纹丝不动。</p>
     *
     * <p>服务端与客户端共用同一个字段（Mek 的做法），所以读取侧不按端分流。</p>
     */
    private FloatingLong lastUsage = FloatingLong.ZERO;

    /**
     * 把进度条数据挂进 Mek 的容器同步通道。
     *
     * <h3>为什么用 {@code container.track} 而不是自己发包</h3>
     * 这是 Mek 机器同步数据的<b>唯一</b>正规入口：{@code MekanismTileContainer.addContainerTrackers()}
     * 会调 {@code tile.addContainerTrackers(this)}，而 {@code MekanismContainer.track(ISyncableData)}
     * 把条目收进 {@code trackedData}，由 Mek 自己的容器属性包按脏值增量下发。
     * 自己发包要另写一套「谁在什么时候发、玩家关屏后怎么办」的状态机，
     * 而 Mek 这套已经处理好了开屏/关屏/重开屏。
     *
     * <p>进度数组走 {@code container.trackArray(int[])} —— 与 Mek 的
     * {@code TileEntityFactory.addContainerTrackers} 里 {@code container.trackArray(progress)}
     * 逐字同款。它给每个下标建一个直接读写该数组的 {@code SyncableInt}，
     * 所以服务端读到的就是权威值、客户端写进去的就是镜像，<b>不需要按端分流</b>。</p>
     */
    @Override
    public void addContainerTrackers(mekanism.common.inventory.container.MekanismContainer container) {
        super.addContainerTrackers(container);
        container.trackArray(progressArray());
        // 逐路告警位 —— 与 Mek 的 {@code TileEntityFactory} 里 errorTracker.track(container) 同款。
        container.trackArray(warningArray());
        // 整机能量告警 —— Mek 把它挂在竖直能源条上（GuiFactory 的
        // getWarningCheck(RecipeError.NOT_ENOUGH_ENERGY, 0)），这里同样。
        container.track(mekanism.common.inventory.container.sync.SyncableBoolean.create(
                this::isNotEnoughEnergy, value -> this.notEnoughEnergy = value));
        // 自动分选开关 —— 标签页要画 On/Off，客户端必须看得到服务端的权威值。
        container.track(mekanism.common.inventory.container.sync.SyncableBoolean.create(
                this::isSorting, value -> this.sorting = value));
        // 能源 tab 的「使用量」读数 —— 与 Mek 的 TileEntityFactory 同款（同一个字段双端共用）。
        container.track(mekanism.common.inventory.container.sync.SyncableFloatingLong.create(
                this::getLastUsage, value -> this.lastUsage = value));
        // 执行器的展示态（订单三件套 + 家族自定义位）。见 syncEx*() 的注释。
        container.track(mekanism.common.inventory.container.sync.SyncableInt.create(
                this::syncOrderActiveFlag, value -> this.clientOrderActive = value));
        container.track(mekanism.common.inventory.container.sync.SyncableInt.create(
                this::getOrderQuantityForSync, value -> this.clientOrderQuantity = value));
        container.track(mekanism.common.inventory.container.sync.SyncableInt.create(
                this::getOrderCompletedForSync, value -> this.clientOrderCompleted = value));
        container.track(mekanism.common.inventory.container.sync.SyncableInt.create(
                this::syncFamilyExtraBits, value -> this.clientFamilyExtraBits = value));
    }

    // ── 执行器展示态的双端镜像（订单 + 家族自定义位）────────────────────
    //
    // ⚠️ 此前这些数据**没有任何同步通道**。原来的注释断言
    // 「saveAdditional 会被 getUpdateTag 复用、因而随方块更新包到达客户端」——
    // **这条断言经 javap 复核不成立**，实测（扫 Forge 1.20.1-47.4.16 全部 class 的常量池）：
    // ① machine/** 全包 grep `setChanged|sendBlockUpdated` = 0 命中，
    //    GrillSeasoningTogglePacket.handle 只调 toggleSeasoningEnabled，不触发任何更新；
    // ② LevelChunk#setBlockState / ServerLevel#sendBlockUpdated 只发 ClientboundBlockUpdatePacket
    //    （只有方块状态，不含 BE 数据）；
    // ③ 引用 ClientboundBlockEntityDataPacket 的类只有 19 个，服务端侧发送点仅
    //    ServerPlayer#openCommandBlock（命令方块）加随区块下发。
    // ⇒ 客户端 tile 上的执行器状态只是**区块（重新）加载时的快照**。
    // 症状：点「开/关」后按钮颜色不变（每帧读到旧值），离开再进区块才刷新；
    // 烹饪 / 穿串工厂的「当前订单 x/y」在订单推进过程中完全不更新。
    //
    // 现在走与 {@link #getWorkProgress} 同一套机制：Mek 的容器追踪 + 按端分流。
    // 菜单侧一行都不用改（它们本来就经 getTileEntity() 调本类的方法）。

    /** 客户端镜像：是否有一张单在跑（0/1）。 */
    private int clientOrderActive;
    /** 客户端镜像：订单总份数。 */
    private int clientOrderQuantity;
    /** 客户端镜像：订单已完成份数。 */
    private int clientOrderCompleted;
    /** 客户端镜像：家族自定义位（烧烤的 3 个调味料启用位等）。 */
    private int clientFamilyExtraBits;

    // ── 订单读数的**唯一公共出口** ──────────────────────────────────────
    //
    // ⚠️ 第三轮修正：这三个方法原先只有「写」的一侧（SyncableInt + 三个 clientXxx 字段），
    // 而各家族 tile 的同名方法**直接读执行器**，于是镜像写进去了没人读 ——
    // 客户端拿到的仍是区块加载快照，症状与修复前完全一致。
    // 变异测试实测：把 CookingFactoryTile.getOrderQuantity() 改成 return 999999，
    // 两个护栏**全绿** —— 因为它们只读写入侧文本，从不看读取侧。
    //
    // 现在这三个方法是读取侧的**唯一**出口：家族 tile 一律委托到基类，
    // 而菜单本来就调 tile 的同名方法（CookingFactoryMenu / SkeweringFactoryMenu 同款），
    // 所以**菜单一行都不用改**。服务端由 clientMirroring()==false 走权威值兜住。

    /** 本机是否有一张单在跑（GUI 与 AE2 共用口径）。 */
    public boolean hasOrder() {
        return executor().hasOrder();
    }

    /** 订单总份数（无订单为 0）。按端分流。 */
    public int getOrderQuantity() {
        return getOrderQuantityForSync();
    }

    /** 订单已完成份数（按端分流）。 */
    public int getOrderCompleted() {
        return getOrderCompletedForSync();
    }

    /** 执行器是否「有单」—— 服务端读权威值、客户端读镜像。 */
    protected int syncOrderActiveFlag() {
        if (clientMirroring()) {
            return clientOrderActive;
        }
        return executor().hasOrder() ? 1 : 0;
    }

    /** 订单总份数（按端分流）。 */
    public int getOrderQuantityForSync() {
        if (clientMirroring()) {
            return clientOrderQuantity;
        }
        return executor().getOrderQuantity();
    }

    /** 订单已完成份数（按端分流）。 */
    public int getOrderCompletedForSync() {
        if (clientMirroring()) {
            return clientOrderCompleted;
        }
        return executor().getOrderCompleted();
    }

    /**
     * 家族自定义展示位 —— 默认 0。
     *
     * <p>烧烤用它装 3 个调味料启用位（{@code bit0..bit2}）。刻意做成钩子而不是
     * 让基类认识每个家族的私有概念：基类只负责「把一个 int 同步过去」这一件事。</p>
     */
    protected int syncFamilyExtraBits() {
        if (clientMirroring()) {
            return clientFamilyExtraBits;
        }
        return 0;
    }

    /**
     * 此刻是否应读客户端镜像。
     *
     * <p>与 {@link #getWorkProgress} 用的是同一条判据，但这里多考虑了
     * {@code level == null}（GUI 构造期可能早于 BE 绑定）：拿不到世界就按客户端处理，
     * 返回镜像值而不是去碰权威状态 —— 宁可显示 0 也不能在 GUI 构造期抛异常。</p>
     */
    private boolean clientMirroring() {
        return level == null || level.isClientSide;
    }

    /** 客户端镜像值：订单是否激活。GUI 读数用。 */
    public int getClientOrderActive() {
        return clientMirroring() ? clientOrderActive : syncOrderActiveFlag();
    }

    /** 客户端镜像值：家族自定义位。 */
    public int getClientFamilyExtraBits() {
        return clientMirroring() ? clientFamilyExtraBits : syncFamilyExtraBits();
    }

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
     * <b>单路</b>每 tick 应扣多少能量 —— 已含存储卡倍增，但<b>不含并行槽数</b>。
     *
     * <p>并行槽数由 {@link #workCycle} 的逐路扣减自然乘出来：N 路在跑就扣 N 份。
     * 这与 Mek 同构 —— 那边每条 {@code CachedRecipe.updateAndProcess} 各自
     * {@code extract} 一次，总耗电同样是「跑了几路就几份」。旧实现是整机一次扣
     * {@code base × 非空槽数}，于是「有料但没配方」的槽也在收费，而且电量只够跑一半时
     * 全部路一起停。</p>
     *
     * <p>默认 0 = 不耗电。旧实现里这条公式是切菜专属的（速度倍率要平方），
     * 因此留给子类算，基类只负责「够不够 → 扣多少 → 扣」这三步动作。</p>
     */
    protected int energyPerLanePerTick() {
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
        return countNonEmpty(inputSlots);
    }

    /**
     * 槽列表里的非空槽数 —— {@link #activeWorkSlots()} 的公共算术。
     *
     * <p>抽成 {@code static} 纯函数是为了能在裸 JVM 里断言：真 tile 造不出来
     * （构造链要 {@code BlockEntityType} 与 Mek 的注册表），而这条判据的全部输入
     * 就是一个槽列表。见 {@code TestFactoryStorageOnlyStart}。
     * {@code protected} 而不是包级可见：家族 tile 在子包里（{@code machine.cooking} /
     * {@code machine.skewering}），包级可见够不到。</p>
     *
     * <p><b>存储型家族（烹饪 / 穿串）覆写 {@link #activeWorkSlots()} 时拿它数
     * {@code ingredientSlots()}</b>，不能退回只数输入槽：这两家的存储区
     * （144 / 81 格）才是真正的料仓 —— 材料只放在存储区时会被判成「没活干」，
     * 机器永不启动，而执行器明明扫得到那些料。</p>
     */
    protected static int countNonEmpty(List<IInventorySlot> slots) {
        if (slots == null) {
            return 0;
        }
        int active = 0;
        for (IInventorySlot slot : slots) {
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
    static int gatedEnergyCost(boolean freeEnergy, int energyPerLanePerTick) {
        return freeEnergy ? 0 : energyPerLanePerTick;
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
    /**
     * 本机是否带热能力 —— <b>默认不带</b>，需要发热的家族覆写。
     *
     * <p>刻意不放上基类：阶段 3 迁移前只有<b>烧烤</b>与<b>烹饪</b>两个工厂有真正的
     * 热容（切菜 / 研磨 / 种植切配 / 穿串从来没有）。给另外 4 个白送一套热容与散热
     * 行为是<b>平衡变更</b>，不是修复。</p>
     */
    protected boolean hasHeatSupport() {
        return false;
    }

    /**
     * 把本 tick 耗掉的电转成废热 —— <b>默认什么都不做</b>。
     *
     * <p>有热容的家族（烧烤 / 烹饪）覆写它，把 {@code energyUsed} 按发电效率注入热容。
     * 调用点在 {@link #workCycle} 的扣能量之后，顺序与迁移前逐字一致
     * （旧 BE：{@code extractEnergy(...); addHeatFromEnergy(...); progress++;}）。</p>
     */
    protected void addHeatFromEnergy(int energyUsed) {
    }

    /**
     * 构造本机的热容容器 —— <b>Mek 原生钩子</b>，与 {@link #getInitialFluidTanks} /
     * {@link #getInitialEnergyContainers} 同一族。
     *
     * <h3>为什么不需要自己实现 {@code getHeatCapacitors}</h3>
     * {@code TileEntityMekanism} 已经：① {@code implements ITileHeatHandler}
     * （而 {@code ITileHeatHandler extends IMekanismHeatHandler}，
     * 所以「第三方按 {@code blockEntity instanceof IMekanismHeatHandler} 识别本机为热处理器」
     * 这条路径<b>一直是通的</b>，例如气动工艺 PNC:R）；② 在构造器里<b>无条件</b>调本方法
     * 并据此建 {@code HeatHandlerManager}；③ 声明了 {@code final} 的
     * {@code getHeatCapacitors(Direction)}（所以子类<b>不能</b>覆写它，只能提供容器）；
     * ④ 通过 {@code addContainerTrackers} 把电容温度自动同步给 GUI。
     *
     * <p>所以迁移真正丢掉的只是<b>热容本身</b>：默认实现给出空容器 ⇒ 机器「声称能处理热」
     * 却没有任何容量，旁边的加热线圈灌进来的热量无处可去、机器也不向环境散热。
     * 覆写本方法即可把整条链路接回来。</p>
     *
     * <p>返回 {@code null} 表示「本机不处理热」，与 {@link #hasHeatSupport()} 一致。</p>
     */
    protected mekanism.common.capabilities.holder.heat.IHeatCapacitorHolder getInitialHeatCapacitors(
            IContentsListener listener,
            mekanism.common.capabilities.heat.CachedAmbientTemperature ambient) {
        return null;
    }

    /**
     * 每 tick 推进热系统（环境回归 + 与相邻热容器交换）。
     *
     * <p>{@code TileEntityMekanism} 的 {@code ITileHeatHandler.updateHeatCapacitors} 已经在
     * 它自己的 tick 链里做这件事，所以默认实现是 no-op；本钩子只为需要额外处理的家族留口。</p>
     */
    protected void tickHeatSupport() {
    }

    /**
     * 槽位内容变化时的家族钩子。
     *
     * <p>用途：种植切配靠它把「营养液槽里容器的内容」自动灌进营养液罐 ——
     * 那是玩家喂这台机器的主要入口，迁移时漏掉了（见
     * {@code PlantingCuttingFactoryTile#fillTankFromSlot}）。
     * 挂在内容变化回调而不是 tick 里，是因为只在「真的有人动了那个槽」时才需要灌，
     * 逐 tick 轮询是白烧 CPU。</p>
     */
    protected void onFamilyContentsChanged() {
    }

    /**
     * 本机温度（K）。默认环境温度 —— 供 GUI 读数用，避免各家族各写一份。
     */
    public double getTemperatureK() {
        return mekanism.api.heat.HeatAPI.AMBIENT_TEMP;
    }

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
        // 自动分选排在 workCycle() 之前：本 tick 刚摆好的槽位要立刻参与加工判定。
        // 脏标记在调用前清掉（Mek 同款）—— 分选本身会改槽位、从而再次点亮它，
        // 但下一次分选算出的摆法不变、一个字节都不写，所以不会每 tick 空转。
        if (sortingNeeded && sorting) {
            sortingNeeded = false;
            sortInputs();
        }
        workCycle();
        // 热推进排在 workCycle() 之后：热是「本 tick 耗能的副产物」，
        // 迁移前旧 BE 的次序同样是先 extractEnergy/addHeatFromEnergy 再 progress++。
        tickHeatSupport();
        // AE2 放在 workCycle() 之后：本 tick 刚产出的物品要先落到产物槽，
        // MEckAe2 的产物回写才能在同一 tick 看到它们（与旧
        // CuttingMachineFactoryBlockEntity.serverTick 里「先干活、后
        // AE2Compat.autoProcessTick」的次序一致）。
        cn.ism.mekck.compat.AE2Compat.serverTick(this, getLevel(), worldPosition);
        // 「已勾选材料 → 持续补料 + 产物回网」的第二段，与旧 serverTick 里紧跟在
        // AE2Compat.serverTick 之后的那行逐字对应。没接上这一段的表现是：
        // 节点在网、样板在终端里看得见，但勾了材料也不会自动补料。
        cn.ism.mekck.compat.AE2Compat.autoProcessTick(this);
    }

    /** 闸门 + 逐路进度 + 执行器调度。拆出来只为让 {@link #onUpdateServer} 保持一屏可读。 */
    private void workCycle() {
        int cycle = effectiveTicksPerWorkCycle();
        // 免耗电：整个扣减额置 0（旧第 414 行的 `activeSlots > 0 && !hasCreative`）。
        // 置 0 后下面的 hasEnergyFor(0) 恒真，于是能量为 0 时机器照样推进——与旧实现同。
        int perLaneCost = gatedEnergyCost(randomizeGrantsFreeEnergy(), energyPerLanePerTick());
        // 红石与「有没有活干」两条与旧 serverTick 的 canOperate && anyValid 一一对应。
        // <b>能量不再参与这道总闸</b>：它改成逐路各判各扣（见下方循环），
        // 与 Mek 的 CachedRecipe.updateAndProcess 逐路 extract 同款。
        boolean work = hasWorkToDo();
        boolean allowed = allowsWork() && work;
        // 能量告警：有活干、却连一路都推不动 —— 与 Mek 的
        // RecipeError.NOT_ENOUGH_ENERGY 同义（GuiFactory 把它挂在竖直能源条上）。
        // 判据里带 work 是必须的：空转的机器能量为 0 也不该报警。
        notEnoughEnergy = work && !hasEnergyFor(perLaneCost);
        int[] progress = progressArray();
        boolean[] warnings = warningArray();
        if (!allowed) {
            // 条件不满足 ⇒ 全部路清零重来（旧实现是单个计数器清零，语义相同）。
            java.util.Arrays.fill(progress, 0);
            // 逐路告警一并清掉：机器整台停着（红石关 / 没料）时，
            // 「这一路做不出东西」不是玩家需要看到的信息。
            java.util.Arrays.fill(warnings, false);
            if (pulseLatched) {
                pulseLatched = false;
            }
            setActive(false);
            lastUsage = FloatingLong.ZERO;
            return;
        }
        // 本 tick 的能量差就是能源 tab 要显示的「使用量」（Mek 的 TileEntityFactory 同款）。
        FloatingLong energyBefore = energyContainer.getEnergy().copy();
        MekCkRecipeExecutor exec = executor();
        boolean anyProgress = false;
        for (int i = 0; i < progress.length; i++) {
            boolean can = exec.canProcess(this, i);
            // 逐路告警：这一路喂了料、却做不出产物（没配方 / 产物装不下 / 缺辅料）。
            // 与 Mek 的 RecipeError.INPUT_DOESNT_PRODUCE_OUTPUT 同义，挂在同一条进度条上。
            warnings[i] = !can && laneHasInput(i);
            if (!can) {
                // 这一路没活干（没输入 / 没配方 / 产物装不下）⇒ 只清它自己的进度，
                // 其余路照常推进。这正是「逐路独立」与旧「整批一起停」的区别。
                progress[i] = 0;
                continue;
            }
            // 逐路扣电：这一路自己付得起才推进。付不起的那一路清零重来，其余路照常 ——
            // 与 Mek 同款（那边是每条 CachedRecipe 各自 extract，不够就 resetProgress）。
            // 旧实现是「整机一次扣 base × 非空槽数」，于是「有料但没配方」的槽也在收费，
            // 而且电量只够跑一半时全部路一起停。
            if (!hasEnergyFor(perLaneCost)) {
                progress[i] = 0;
                continue;
            }
            if (perLaneCost > 0) {
                // AutomationType 的选择与理由都收在 deductEnergy 里，别在这里另传一个：
                // 本行原先写死 EXTERNAL，被容器的 canExtract=notExternal 整条拒掉，
                // 于是机器加工了一整个阶段却一 FE 都没扣（阶段 2 Task 4 交付的既有问题）。
                deductEnergy(energyContainer, perLaneCost);
                // 耗能转废热：迁移前旧 BE 在「extractEnergy 之后、progress++ 之前」
                // 调 addHeatFromEnergy(energyPerTick)，这里保持同一次序。
                // 没有热能力的家族该钩子是 no-op。
                addHeatFromEnergy(perLaneCost);
            }
            if (advanceLane(progress, i, cycle)) {
                exec.process(this, i);
                // PULSE：跑完一整个批次才解除锁存（allowsWork 的锁存语义见其注释）。
                pulseLatched = false;
            }
            if (progress[i] > 0) {
                anyProgress = true;
            }
        }
        // active 口径与旧实现逐字一致：旧代码在 serverTick 末尾算 isActive = progress > 0，
        // 而那时 progress 刚被清零，所以「刚跑完一批」的那一 tick 机器就是不 active 的。
        setActive(anyProgress);
        lastUsage = anyProgress ? energyBefore.minusEqual(energyContainer.getEnergy()) : FloatingLong.ZERO;
    }

    /**
     * 第 {@code index} 路是否喂了料 —— 逐路告警的判据。
     *
     * <p>多路家族（切菜 / 研磨 / 烧烤 / 种植切配）输入槽与路一一对应，直接看第
     * {@code index} 格。整机一次家族（烹饪 / 穿串，{@link #getProcessCount()} 为 1
     * 而输入槽有多格）只有一路，判「任意输入槽非空」。</p>
     */
    private boolean laneHasInput(int index) {
        if (inputSlots == null || inputSlots.isEmpty()) {
            return false;
        }
        if (getProcessCount() == 1) {
            for (IInventorySlot slot : inputSlots) {
                if (!slot.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
        return index >= 0 && index < inputSlots.size() && !inputSlots.get(index).isEmpty();
    }

    /**
     * 进度数组 —— 长度由 {@link MekCkRecipeExecutor#processCount} 定下，<b>只分配一次</b>。
     *
     * <p>为什么不能重新分配：{@link #addContainerTrackers} 里 {@code container.trackArray}
     * 给每个下标建的 {@code SyncableInt} 直接持有这个数组的引用。换实例之后同步通道
     * 仍在写旧数组，GUI 读到的进度会永远停在换实例那一刻。</p>
     */
    private int[] progressArray() {
        if (workProgress == null) {
            workProgress = new int[Math.max(1, executor().processCount(this))];
        }
        return workProgress;
    }

    /**
     * 告警数组 —— 与 {@link #progressArray()} 同长度、同样<b>只分配一次</b>。
     *
     * <p>长度取进度数组而不是执行器的 {@code processCount}：两者必须一致，
     * 否则 {@code container.trackArray} 建出的同步条目数与 GUI 读的下标对不上。</p>
     */
    private boolean[] warningArray() {
        if (laneWarnings == null) {
            laneWarnings = new boolean[progressArray().length];
        }
        return laneWarnings;
    }

    /**
     * 推进一路的进度。
     *
     * <p>抽成 {@code static} 纯函数是为了能脱离 tile 单测「各路互不影响」这条不变量 ——
     * 造一台真的机器需要 {@code BlockEntityType} 注册表，裸 JVM 里拿不到。</p>
     *
     * @return true 表示这一路刚好走完一个批次（进度已归零，调用方该加工它了）
     */
    static boolean advanceLane(int[] progress, int index, int cycle) {
        if (++progress[index] >= cycle) {
            progress[index] = 0;
            return true;
        }
        return false;
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

    /**
     * 第 {@code index} 路已走的 tick 数（GUI 用）。
     *
     * <p>服务端与客户端读的是<b>同一个数组</b>：服务端它是权威值，客户端由
     * {@code container.trackArray} 的同步 setter 写进来（见 {@link #workProgress}）。
     * 所以这里不需要按端分流 —— 与 {@code getOrderQuantity()} 那几个镜像字段不同。</p>
     */
    public int getWorkProgress(int index) {
        int[] progress = workProgress;
        if (progress == null || index < 0 || index >= progress.length) {
            return 0;
        }
        return progress[index];
    }

    /** 本机有几路并行（= 进度条条数）。 */
    public int getProcessCount() {
        return progressArray().length;
    }

    /**
     * 第 {@code index} 路是否有告警（喂了料却做不出产物）—— 进度条的
     * {@code WarningType.INPUT_DOESNT_PRODUCE_OUTPUT} 供给器。
     */
    public boolean hasLaneWarning(int index) {
        boolean[] warnings = laneWarnings;
        return warnings != null && index >= 0 && index < warnings.length && warnings[index];
    }

    /** 全部路里是否有任一路告警 —— 悬浮窗布局那条汇总进度条用。 */
    public boolean hasAnyLaneWarning() {
        boolean[] warnings = laneWarnings;
        if (warnings == null) {
            return false;
        }
        for (boolean warning : warnings) {
            if (warning) {
                return true;
            }
        }
        return false;
    }

    /** 是否有「有活干但电不够」的告警 —— 竖直能源条的 {@code WarningType.NOT_ENOUGH_ENERGY} 供给器。 */
    public boolean isNotEnoughEnergy() {
        return notEnoughEnergy;
    }

    // ── 输入槽自动分选（Mek 的 GuiSortingTab / TileEntityFactory.sortInventory 对应物）──

    /** 自动分选开关（GUI 标签页读它画 On/Off）。 */
    public boolean isSorting() {
        return sorting;
    }

    /** 翻转自动分选开关；打开时立刻标脏，下一 tick 就分选一次。 */
    public void toggleSorting() {
        sorting = !sorting;
        if (sorting) {
            sortingNeeded = true;
        }
    }

    /**
     * 本家族是否支持自动分选。
     *
     * <p>默认支持。穿串工厂覆写成 {@code false}：本机是整机批次操作（{@code processCount} 恒 1），
     * 没有「多路并行」可分；而配方匹配位置无关（材料放哪个输入槽都能开工），
     * 分选只会把材料在等价位置之间搬来搬去，没有收益。</p>
     *
     * <p>历史注记：M29 之前返还槽复制「输入槽 0 的整叠」，分选会把签子挪出槽 0、
     * 让错配从「可达」变成「常态」；M29 把返还改成「按实际消耗的签子记录落槽」后，
     * 这条危险不再存在，但上面的「无收益」理由仍然成立。</p>
     */
    public boolean supportsSorting() {
        return true;
    }

    /**
     * 输入槽自动分选 —— 把同种物品在<b>能接受它的槽</b>之间摊平。
     *
     * <h3>为什么是「摊平」而不是「归并」</h3>
     * 工厂的并行度就是「有几路在干活」。把 3 槽各 10 个归并成 1 槽 30 个会让并行度
     * 从 3 掉到 1，正好与工厂的意义相反。所以这里做的是反过来的事：同种物品摊到
     * 尽可能多的槽上，让每一路都有活干。
     *
     * <h3>为什么不会丢东西</h3>
     * 每种物品的总量在分组时就已固定，回填时按「目标槽数」均分（每槽
     * {@code ceil(剩余 / 剩余目标槽数)}），<b>总量逐字守恒</b>。目标槽集合一定包含
     * 该物品原本占用的每一个槽，所以即使某个槽的准入谓词只认特定物品，那些物品也
     * 一定有地方可放。
     *
     * <h3>为什么先算目标摆法、再比对、最后才写回</h3>
     * {@code IInventorySlot.setStack} / {@code setEmpty} 会触发
     * {@code onContentsChanged} → 本类的 {@code sortingNeeded = true}。若每次都无条件
     * 清空再回填，即使摆法没变也会把脏标记重新点亮，于是<b>每 tick 都分选一次</b>、
     * 每 tick 都把方块标脏。先算后比可以保证「摆法不变就一个字节都不写」。
     */
    private void sortInputs() {
        List<IInventorySlot> inputs = getInputSlots();
        if (inputs == null || inputs.size() < 2) {
            return;
        }
        List<ItemStack> current = new ArrayList<>(inputs.size());
        for (IInventorySlot slot : inputs) {
            current.add(slot.getStack());
        }
        // 输入槽的单槽容量在本模组里是统一的（slotLimitPerSlot(tier) 对全档位取同一个配置值），
        // 所以取第 0 格的口径即可。MekCkSlot 的 obeyStackLimit 是 false，
        // getLimit 与传进去的栈无关，传 EMPTY 也拿得到真实容量。
        List<ItemStack> layout = sortedLayout(current, inputs.get(0).getLimit(ItemStack.EMPTY));
        // 摆法没变就一个字节都不写（理由见方法注释）。
        boolean changed = false;
        for (int i = 0; i < inputs.size(); i++) {
            if (!ItemStack.matches(inputs.get(i).getStack(), layout.get(i))) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            return;
        }
        for (int i = 0; i < inputs.size(); i++) {
            ItemStack stack = layout.get(i);
            if (stack.isEmpty()) {
                inputs.get(i).setEmpty();
            } else {
                inputs.get(i).setStack(stack);
            }
        }
    }

    /**
     * 分选的目标摆法 —— 纯函数，不碰任何槽位。
     *
     * <p>抽成 {@code static} 是为了能脱离 tile 单测「总量守恒」这条不变量：
     * 造一台真的机器需要 {@code BlockEntityType} 注册表，裸 JVM 里拿不到。
     * 见 {@code TestMekCkInputSorting}。</p>
     *
     * @param current   当前每个输入槽的内容
     * @param slotLimit 单槽容量（本模组全档位统一，见 {@code slotLimitPerSlot}）
     * @return 与 {@code current} 等长的目标摆法；<b>物品总量与 {@code current} 逐字相等</b>
     */
    static List<ItemStack> sortedLayout(List<ItemStack> current, int slotLimit) {
        int size = current.size();
        List<ItemStack> layout = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            layout.add(ItemStack.EMPTY);
        }
        if (size < 2) {
            return layout;
        }
        // ① 分组：同物品同 NBT 算一种，同时记下它原本占用的槽下标。
        //    用 List 保序 —— HashMap 的迭代顺序会让同样的输入每次分选出不同的摆法，
        //    玩家会看到槽位自己乱跳。
        List<ItemStack> kinds = new ArrayList<>();
        List<List<Integer>> targets = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = current.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int found = -1;
            for (int k = 0; k < kinds.size(); k++) {
                if (ItemStack.isSameItemSameTags(kinds.get(k), stack)) {
                    found = k;
                    break;
                }
            }
            if (found < 0) {
                kinds.add(stack.copy());
                List<Integer> home = new ArrayList<>();
                home.add(i);
                targets.add(home);
            } else {
                kinds.get(found).grow(stack.getCount());
                targets.get(found).add(i);
            }
        }
        if (kinds.isEmpty()) {
            return layout;
        }
        // ② 把空槽按顺序补给「还想摊开」的物品：够几个就摊几槽（每槽至少 1 个）。
        //    判据与 Mek 的 addEmptySlotsAsTargets 同源（那边是 totalCount / minPerSlot，
        //    本模组的配方每份至少吃 1 个，所以 minPerSlot 取 1）。先到先得，
        //    没抢到空槽的物品保留它原有的槽 —— 一个都不会丢。
        boolean[] claimed = new boolean[size];
        for (List<Integer> target : targets) {
            for (int i : target) {
                claimed[i] = true;
            }
        }
        for (int k = 0; k < kinds.size(); k++) {
            List<Integer> target = targets.get(k);
            int want = Math.min(kinds.get(k).getCount(), size);
            for (int i = 0; i < size && target.size() < want; i++) {
                if (!claimed[i]) {
                    claimed[i] = true;
                    target.add(i);
                }
            }
        }
        // ③ 均分：每槽取 ceil(剩余 / 剩余目标槽数)，总量逐字守恒。
        //    每槽上限取 slotLimit 本身，不再与物品自身堆叠上限取小：MekCkSlot 的
        //    obeyStackLimit = false，槽的真实容量就是 slotLimit（配置注释写明
        //    「该值同时是执行器判定产物装不装得下的依据」）。取小会让「总量没超总容量」
        //    的摆法提前触发下面的兜底，把余量一股脑倒进第一格。
        for (int k = 0; k < kinds.size(); k++) {
            ItemStack kind = kinds.get(k);
            List<Integer> target = targets.get(k);
            int remaining = kind.getCount();
            for (int t = 0; t < target.size() && remaining > 0; t++) {
                int free = target.size() - t;
                // long 中间量：remaining 可接近 Integer.MAX_VALUE，+ free - 1 会溢出成负数。
                int perSlot = (int) Math.min(Integer.MAX_VALUE, ((long) remaining + free - 1) / free);
                int put = Math.min(remaining, Math.min(perSlot, slotLimit));
                if (put <= 0) {
                    continue;
                }
                layout.set(target.get(t), kind.copyWithCount(put));
                remaining -= put;
            }
            if (remaining > 0) {
                // 走到这里 ⟺ 总量 > 目标槽总容量（每槽上限 slotLimit，循环已把每槽填满）。
                // 宁可超容量堆叠也不销毁：MekCkSlot 的 obeyStackLimit = false 允许槽里
                // 存在超过 getLimit 的叠，少一个都是玩家的损失。
                int first = target.get(0);
                ItemStack existing = layout.get(first);
                long merged = (long) (existing.isEmpty() ? 0 : existing.getCount()) + remaining;
                layout.set(first, kind.copyWithCount((int) Math.min(Integer.MAX_VALUE, merged)));
            }
        }
        return layout;
    }

    /**
     * 上一 tick 实际消耗的能量 —— 能源 tab 的「使用量」读数。
     *
     * <p>与 Mek 的 {@code TileEntityFactory.getLastUsage()} 同款。此前六个工厂屏传的是
     * {@code tier.energyPerTick}（等级声明值），与真实扣电量无关。</p>
     */
    public FloatingLong getLastUsage() {
        return lastUsage;
    }

    /** 完成一个批次需要的 tick 数（GUI 画进度条分母用）。 */
    public int getTicksPerWorkCycle() {
        return effectiveTicksPerWorkCycle();
    }

    /**
     * 第 {@code index} 路的进度比例（0..1）—— <b>GUI 进度条的唯一取数口</b>。
     *
     * <p>六个菜单的 {@code getProgressRatio(int)} 与槽位悬浮窗的逐路填充都调这里，
     * 免得同一个除法在七处各写一遍、日后分母口径漂移。</p>
     */
    public double getProgressRatio(int index) {
        int cycle = getTicksPerWorkCycle();
        return cycle <= 0 ? 0 : getWorkProgress(index) / (double) cycle;
    }

    /**
     * 全部路里最大的进度比例 —— 悬浮窗布局（&gt;17 并行）主面板那条汇总进度条用。
     *
     * <p>为什么是 max 而不是平均：那条条要回答的是「这台机器在不在干活」。
     * 平均在「只喂了 1 路」时会显示 1/81 的进度，看起来像卡住；max 则如实
     * 反映那一路的推进。全部路同步推进时两者相等。</p>
     */
    public double getMaxProgressRatio() {
        int lanes = getProcessCount();
        double max = 0;
        for (int i = 0; i < lanes; i++) {
            max = Math.max(max, getProgressRatio(i));
        }
        return max;
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
        tag.putIntArray(TAG_WORK_PROGRESS_ARRAY, progressArray());
        tag.putBoolean(TAG_SORTING, sorting);
        tag.putInt(TAG_NATIVE_VERSION, NATIVE_VERSION);
        // AE2 网格节点的 NBT 必须与节点一同存活（阶段 2 Task 4.6）：
        // 节点里存着频道占用与「已勾选的自动处理材料」，不写就等于每次重载
        // 都换一批频道。AE2Compat 未装时整个方法短路为空操作。
        cn.ism.mekck.compat.AE2Compat.saveAdditional(this, tag);
        // 放置者归属（网络厨师学徒）：与旧 BE 的 saveAdditional 逐字同款，
        // 同样不依赖 AE2 是否安装。
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
    }

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
    @Override
    public void load(CompoundTag tag) {
        CompoundTag legacyTag = migratesLegacyNbt() && MekCkLegacyMachineNbt.isLegacy(tag) ? tag : null;
        if (legacyTag != null) {
            // 档位为 null 时按「无机器槽位」处理：正常情况下构造器早就抛了
            // IllegalStateException，真到这里说明方块被换成了非工厂方块。
            // 此时宁可让旧内容被逐条记日志丢掉，也不要在区块加载路径上抛 NPE。
            CuttingMachineFactoryTier tier = getTier();
            // 第 4 个参数是「新机器真实的槽位总数」（含 appendExtraSlots 追加的家族专属槽）：
            // 迁移写出的 MekCkSlotCount 必须与读档时的槽位数一致，否则每次读档都多一条 WARN。
            tag = MekCkLegacyMachineNbt.migrate(legacyTag, getDirection(),
                    tier == null ? 0 : tier.processes, mekckPersistedSlots().size());
        }
        super.load(tag);
        // ⚠️ 这一次显式调用不能省，理由不是「顺序好看」而是实测的调用点次序：
        // 本类实现的 {@link ISustainedData#readSustainedData} 也会被父类的读档链调到
        // （{@code TileEntityMekanism.load} 偏移 59 → {@code loadGeneralPersistentData} → 钩子），
        // 但那一处在 {@code DataHandlerUtils.readContainers}（偏移 90，Mek 自己那份 byte 下标
        // Items）<b>之前</b>。也就是说钩子那一次跑完，0..127 号槽还会被 Mek 用同一批值覆盖一遍；
        // 只有本条（在 super.load 之后）能保证「int 下标那份是最后落地的权威来源」。
        // 顺序不变量由 {@code TestMekCkSlotNbt#mekckSlotReadIsAppliedAfterSuperLoad} 钉住。
        readMekckPersistentState(tag);
        if (legacyTag != null) {
            installLegacyUpgrades(legacyTag);
        }
    }

    // ── ISustainedData：掉落 → 再放置 的 MekCK 状态恢复 ─────────────────
    //
    // 背景：迁到 Mek 的 BlockTile 之后，破坏方块走的是战利品表，BE 的内容不会自动跟着
    // 物品走；恢复走 BlockMekanism.setPlacedBy（实测偏移 102 取 ItemDataUtils.getDataMapIfPresent，
    // 299~361 读 SubstanceType 容器，364~391 调 ISustainedData.readSustainedData）。
    // 因此 MekCK 自有的键必须自己接这一钩子，否则「挖掉再放下」等于整机清零。
    //
    // ⚠️ 键契约（与战利品表 copy_nbt 的 target 路径逐字对齐，全部在 BE 存档根 / mekData 根）：
    //   MekCkSlots             (CompoundTag)  int 下标槽位存档，见 MekCkSlotNbt
    //   mekckExecutor          (CompoundTag)  执行器自有状态（订单等）
    //   MekCkWorkProgressArray (int[])        每路并行各自的进度（v2 权威键）
    //   MekCkSorting           (boolean)      输入槽自动分选开关
    //   MekCkNative            (int)          存档格式版本
    //   GasTank                (CompoundTag)  种植切配的营养液罐（家族钩子）
    //   FluidTanks             (CompoundTag)  烹饪的三个流体罐（家族钩子）
    //   MekckPlacerUuid        (UUID)         放置者归属（PlacerPersist，可选）
    // v1 的 MekCkWorkProgress（单个 int）**不在**本契约内：写侧自 v2 起只写
    // MekCkWorkProgressArray，战利品表若仍复制它只会把一个读侧从不认领的键搬进掉落物；
    // 只有读侧 readWorkProgress 为旧档保留那一次 fallback。
    // AE2 节点键（MekckAe2Main / MekckAe2Extra1..7 / MekCkAutoSel）<b>刻意不搬</b>：
    // 节点在拆机时已被 AE2Compat.onRemoved 销毁，重新放置时应按新节点重新入网。

    /**
     * MekCK 自有键的读取 —— {@link #load} 与 {@link #readSustainedData} <b>共用这一段</b>。
     *
     * <p>传进来的标签有两种来源，但<b>键名与层级完全相同</b>：方块实体的存档根
     * （{@code load} 路径）与物品的 {@code mekData} 层（{@code BlockMekanism.setPlacedBy} 路径，
     * 战利品表的 {@code copy_nbt} 把 BE 的键原样拷进 {@code mekData.<同名>}）。</p>
     *
     * <p>两条路径都幂等：{@link MekCkSlotNbt#read} 是「先清空再灌」，重复读同一份标签结果不变；
     * 执行器 {@code load}、AE2 缓存、放置者 UUID 同理。因此读档路径上钩子与本方法各跑一次
     * 不会产生差异（代价只是多一次反序列化）。</p>
     */
    private void readMekckPersistentState(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        // 专属键不存在时 read 返回 false、什么都不做：那种档只有 Mek 那份 byte 存档，
        // 退化到修复前的行为（低端位照常读、高端位照常丢），而不是报错或清空。
        MekCkSlotNbt.read(tag, mekckPersistedSlots());
        readExtraSustainedData(tag);
        int version = tag.getInt(TAG_NATIVE_VERSION);
        if (version > NATIVE_VERSION) {
            LOGGER.warn("工厂方块 {} 的存档格式版本为 {}，高于本版本 MekCK 支持的 {}，"
                            + "该机器的执行器进度可能不完整。", getBlockType(), version, NATIVE_VERSION);
        }
        readWorkProgress(tag);
        // 分选开关：缺失键取 false（= 默认关），与 Mek 的 NBTUtils.setBooleanIfPresent 同款。
        sorting = tag.getBoolean(TAG_SORTING);
        executor().load(tag.getCompound(TAG_EXECUTOR));
        // AE2 网格节点的 NBT（阶段 2 Task 4.6）：必须在 super.load 之后——
        // 它的 loadFromNBT 只是把整个 tag 缓存成 pendingTag，真正建节点要等
        // 下一次 serverTick 的 init()，与旧 BE 的调用位置一致。
        cn.ism.mekck.compat.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
    }

    /**
     * 读回每路进度。
     *
     * <p>v1 存档写的是单个 int（{@link #TAG_WORK_PROGRESS}），v2 起是 int 数组
     * （{@link #TAG_WORK_PROGRESS_ARRAY}）。旧档的单个值落到第 0 路 —— 进度是
     * 「半批次」的瞬时状态，落到哪一路都不影响后续行为，但直接丢掉会让升级后的机器
     * 凭空少半批。</p>
     */
    private void readWorkProgress(CompoundTag tag) {
        int[] progress = progressArray();
        java.util.Arrays.fill(progress, 0);
        int[] saved = tag.getIntArray(TAG_WORK_PROGRESS_ARRAY);
        if (saved.length > 0) {
            System.arraycopy(saved, 0, progress, 0, Math.min(saved.length, progress.length));
            return;
        }
        if (tag.contains(TAG_WORK_PROGRESS)) {
            progress[0] = Math.max(0, tag.getInt(TAG_WORK_PROGRESS));
        }
    }

    /**
     * 家族专属状态的读取钩子（营养液罐 / 流体罐）。默认什么都不做。
     *
     * <p>基类只认得自己写的那几个键；{@code GasTank} / {@code FluidTanks} 由各自家族写出，
     * 因此也由各自家族读回，但读的时机必须与基类那几个键同批（读档、以及再放置时）。</p>
     */
    protected void readExtraSustainedData(CompoundTag tag) {
    }

    /**
     * {@inheritDoc} —— 掉落物再放置时，把 MekCK 自有状态读回来。
     *
     * <h3>为什么先判「载荷里有 MekCK 的键」再动手</h3>
     * 本钩子的调用方<b>不止</b>「再放置」一条（见 {@link #writeSustainedData} 的调用点清单）：
     * {@code ItemConfigurationCard}（配置卡）粘贴时走
     * {@code setConfigurationData → loadGeneralPersistentData → 本方法}，而配置卡的载荷里
     * <b>不会有</b> MekCK 的任何键（写侧有意为空）。此时必须整段跳过，否则会出现配置卡
     * 本不该有的副作用：
     * <ul>
     *   <li>{@code executor().load(空标签)} → 清掉目标机器<b>正在执行的订单</b>
     *       （执行器把「键不存在」定义为「订单清空」，那是给真读档用的语义）；</li>
     *   <li>{@code AE2Compat.load} → {@code FactoryGridHost.loadFromNBT} 在没有任何节点键时
     *       会 {@code selectedAutoItems.clear()}，<b>清掉目标机器的自动补料勾选</b>；</li>
     *   <li>{@code workProgress} 被重置为 0。</li>
     * </ul>
     * 判据取「四个基类键里任意一个存在」，而不是某一个特定键：这样战利品表少写一项时
     * 仍然是「能恢复多少恢复多少」，而不是全有或全无。
     */
    @Override
    public void readSustainedData(CompoundTag tag) {
        if (!hasMekckKeys(tag)) {
            return;
        }
        readMekckPersistentState(tag);
    }

    /** 载荷里是否出现了任何一个 MekCK 自有键（见 {@link #readSustainedData} 的判据说明）。 */
    private static boolean hasMekckKeys(CompoundTag tag) {
        if (tag == null) {
            return false;
        }
        return tag.contains(MekCkSlotNbt.TAG_SLOTS, net.minecraft.nbt.Tag.TAG_COMPOUND)
                || tag.contains(TAG_EXECUTOR, net.minecraft.nbt.Tag.TAG_COMPOUND)
                || tag.contains(TAG_WORK_PROGRESS, net.minecraft.nbt.Tag.TAG_INT)
                || tag.contains(TAG_NATIVE_VERSION, net.minecraft.nbt.Tag.TAG_INT);
    }

    /**
     * <b>有意为空实现</b>：MekCK 不往「可复制的配置数据」里写任何自有键。
     *
     * <h3>为什么不能在这里写（这是一个已经查证的复用陷阱）</h3>
     * 本钩子<b>不是</b>「只在挖掉时调用」——实测 {@code javap -p -c} 的全部调用点是：
     * <ol>
     *   <li>{@code TileEntityMekanism.addGeneralPersistentData} 偏移 21~34 ——
     *       被 {@code saveAdditional}（偏移 57）与
     *       <b>{@code getConfigurationData(Player)}（偏移 10）</b>调用；</li>
     *   <li>{@code BlockMekanism.getCloneItemStack} 偏移 184~211 —— 中键取方块的复制品。</li>
     * </ol>
     * 而 {@code TileEntityMekanism implements mekanism.api.IConfigCardAccess}，
     * {@code ItemConfigurationCard}（配置卡）复制时调 {@code getConfigurationData}、
     * 粘贴时调 {@code setConfigurationData} → {@code loadGeneralPersistentData} →
     * {@link #readSustainedData}。于是：<b>只要这里写了槽位/流体键，配置卡就会连带复制整机库存</b>
     * —— 从一台满机器复制一张卡，再往空机器上反复粘贴，就是一次干净的物品复制漏洞
     * （粘贴侧 {@code MekCkSlotNbt.read} 还会先清空目标机器自己的槽位）。
     *
     * <p>而「挖掉再放下」这条路径<b>不需要</b>本方法出力：它由战利品表的 {@code copy_nbt}
     * 直接把 BE 存档键拷进 {@code mekData.*}，{@link #readSustainedData} 侧照读即可。
     * 对称性因此落在「读侧对缺键一律无操作」上，而不在写侧。</p>
     *
     * <p>将来若确实想让配置卡复制「订单 / 工作模式」这类<b>纯设置</b>，正确做法是只在这里写
     * {@code mekckExecutor} 这类不含内容的键，而把 {@code MekCkSlots} / {@code GasTank} /
     * {@code FluidTanks} 永远排除在外——那是一条需要单独决策的变更，不要顺手打开。</p>
     */
    @Override
    public void writeSustainedData(CompoundTag tag) {
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Mek 10.4.6 里没有任何外部消费方</b>。对 {@code Mekanism-1.20.1-10.4.6.20} 全 jar 做
     * 常量池扫描 + 逐类 {@code javap -p -c} 复核，结论要写全免得后来者以为本条断言写错了：
     * <ul>
     *   <li>含 {@code getTileDataRemap} 常量的类共 <b>14</b> 个：接口本身 + 13 个实现类；</li>
     *   <li>其中确实有 <b>4 处真实方法调用</b>，但全是 {@code invokespecial}
     *       ——{@code TileEntityQIOExporter → TileEntityQIOFilterHandler →
     *       TileEntityQIOComponent} 这条继承链上，子类在自己的覆写里调
     *       {@code super.getTileDataRemap()} 合并父类表。那是**实现内部**，不是消费方；</li>
     *   <li>按接口调用（{@code invokeinterface}）或虚调用（{@code invokevirtual}）的
     *       <b>外部消费方为 0</b>：{@code BlockMekanism} 与 {@code TileEntityMekanism}
     *       都不含该字符串。</li>
     * </ul>
     * 也就是说这个钩子是「留给键名在 BE 与物品之间需要改名的场合」的，而本模组的键名
     * 两侧同名（战利品表 {@code copy_nbt} 的 target 就是 {@code mekData.<同名键>}），
     * 因此照接口返回空表。</p>
     */
    @Override
    public Map<String, String> getTileDataRemap() {
        return Map.of();
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
        cn.ism.mekck.compat.AE2Compat.onRemoved(this);
    }

    /**
     * 参与 MekCK 专属 int 下标存档的槽位组 —— <b>就是 Mek 写 {@code Items} 用的那一份列表</b>。
     *
     * <h3>为什么由 {@code getInventorySlots(null)} 给出，而不是自己拼 {@code [输入][输出][能量槽]}</h3>
     * 自己拼的版本<b>漏掉了 {@link #appendExtraSlots} 追加的家族专属槽</b>
     * （烹饪 144 格存储 / 穿串 81 格存储 / 烧烤 3 个调味料槽 / 种植切配的营养液 + 生长土），
     * 而那些槽只走 Mek 自己的 byte 下标存档，于是下标 ≥ 128 的槽每次存读档都被静默丢掉：
     * 烹饪工厂 144 格存储里有 35 格落在 128..162 <b>（全部 12 档，新建机器就中招）</b>，
     * 烧烤/种植切配在 {@code SINGULARITY} 档各丢 3 / 2 格。实测：
     * {@code DataHandlerUtils.writeContents} 偏移 48~53 `iload_3; i2b; putByte`、
     * {@code readContents} 偏移 35~49 `iflt 64`（负下标整条跳过）—— 与
     * {@link MekCkSlotNbt} 类注释里那条是同一段字节码。
     *
     * <h3>为什么这份列表的下标一定等于 Mek 的 byte 下标</h3>
     * {@code TileEntityMekanism.saveAdditional} 写的是
     * {@code DataHandlerUtils.writeContainers(getInventorySlots(null))}（偏移 74~86），
     * 读的是同一个调用（偏移 76~90）。而 {@code getInventorySlots(null)} 最终走到
     * {@code ConfigHolder.getSlots(side, fn)}，其第一支就是
     * {@code if (side == null) return this.slots;} —— {@code side} 为 null 时直接返回
     * {@code addSlot} 的插入序 {@code ArrayList}。所以「取同一个方法的结果」是唯一
     * 结构上不可能错位的写法：任何手工重排都只是把错位风险从编译器手里拿回来。
     *
     * <p>每次调用重新取列表（不缓存）：一次调用在存档路径上相对 NBT 序列化可以忽略，
     * 换来的是不可能有人往这个列表里塞脏数据、也不会与 Mek 换列表实现脱节。</p>
     */
    private List<IInventorySlot> mekckPersistedSlots() {
        return getInventorySlots(null);
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
