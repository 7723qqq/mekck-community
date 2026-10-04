package cn.ism.mekck.blockentity;

import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.machine.MekCkSlotHandler;
import cn.ism.mekck.util.RecipeInputMatcher;
import cn.ism.mekck.upgrade.UpgradeHelper;
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
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentUpgrade;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.interfaces.IRedstoneControl;
import mekanism.common.tile.interfaces.ISustainedData;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 电力烧烤架 —— 阶段 3 把它从自研 {@code BlockEntity} 换成 Mek 的
 * {@link TileEntityConfigurableMachine}。
 *
 * <h3>为什么必须换基类（而不是继续借用 Mek 的 GUI 绘制类）</h3>
 * Mek 的升级三件套签名全部要求 {@code TileEntityMekanism}：
 * {@code TileComponentUpgrade(TileEntityMekanism)}（升级数据 + 20 tick 安装读条）、
 * {@code GuiUpgradeWindowTab(IGuiWrapper, TileEntityMekanism, Supplier)}（升级 tab）、
 * {@code MekanismTileContainer.getUpgradeSlot()/getUpgradeOutputSlot()}（两个虚拟槽）。
 * 旧实现是自研 {@code BlockEntity}，一个都接不了，于是只能自研
 * {@code GuiUpgradeWindow} / {@code IUpgradeMenu} / 每类型一个 {@code UpgradeSlot}——
 * 那套 tab 与 Mek 自己的 tab 分属两套坐标系，运行时只能靠 {@code avoidEnergyTabY}
 * 互相避让，表现为侧栏元素重叠。继承本基类后：
 * <ul>
 *   <li>{@code ISideConfiguration} 由父类实现（{@code configComponent}/{@code ejectorComponent}）；</li>
 *   <li>{@code GuiConfigurableTile.super.addGuiElements()} 由 Mek 自己排出
 *       侧配(6) / 传输配置(34) / 升级(6,右) / 红石(137,右)，无需手抄坐标；</li>
 *   <li>能量/升级/红石/比较器/安全等能力由 {@code TileEntityMekanism} 按<b>方块属性</b>
 *       自动开通（实测 {@code setSupportedTypes(Block)} 全程只读 {@code Attribute.has(block, X.class)}）。</li>
 * </ul>
 *
 * <h3>⚠️ 构造期顺序陷阱</h3>
 * {@code TileEntityMekanism} 的构造器<b>内部</b>依次回调
 * {@code presetVariables()} → {@code getInitialEnergyContainers(listener)} →
 * {@code getInitialInventory(listener)} → {@code new TileComponentUpgrade(this)}。
 * 这一切都发生在 {@code super(...)} 返回<b>之前</b>，此刻本类的实例字段初始化器
 * <b>一个都还没跑</b>。两条铁律（与 {@code MekCkMachineTile} 同款）：
 * <ol>
 *   <li>槽位对象一律在 {@link #getInitialInventory} 里 {@code new}，
 *       <b>不能</b>写成字段初始化器；</li>
 *   <li>热容一律在 {@link #getInitialHeatCapacitors} 里建，同理。</li>
 * </ol>
 * 违反任一条的症状都是「这台方块放下去就建不出方块实体」，而报错信息里
 * 完全看不出是构造期顺序问题。
 *
 * <h3>订单驱动：没下单就不加工（刻意保留）</h3>
 * {@link #findRecipe} 只在 {@code orderRecipeId != null} 时返回配方。这不是缺陷，
 * 是这台机器的产品定义：它由 ME 终端 / 本机下单面板驱动，不自动吃料。
 * 迁到 Mek 体系后订单状态仍归本类所有（不走 {@code MekCkRecipeExecutor}）——
 * 那套执行器是给「并行方阵 + 每路独立计时」的工厂用的，本机只有一路。
 *
 * <h3>被删掉的两条旧能力（与 {@code GrillFactoryBlock} 逐条对应）</h3>
 * <ul>
 *   <li><b>「潜行 + 手持升级模块直接装进对应槽」</b>（旧 {@code addUpgradesFromHand}）。
 *       由 Mek 升级 tab 取代；卸载升级走升级 tab 的卸载按钮
 *       （{@code TileComponentUpgrade} 自带，不依赖方块侧的快捷键）。</li>
 *   <li><b>方块侧 {@code SideMode} 枚举与 {@code AutoIO}</b>。由
 *       {@code TileComponentConfig} + {@code TileComponentEjector} 取代。</li>
 * </ul>
 */
public final class GrillBlockEntity extends TileEntityConfigurableMachine
        implements ISustainedData, cn.ism.mekck.ae2.INetworkPullable {

    // ── 槽位下标 ────────────────────────────────────────────────────────
    //
    // 这三个下标是「本机槽位列表（getInventorySlots(null)）里的位置」，不是菜单下标。
    // MekanismTileContainer.addSlots() 会先排玩家背包/快捷栏/副手，再排两个升级虚拟槽，
    // 最后才遍历本列表，所以菜单下标与它们无关——旧实现那套「手写菜单下标」的补偿逻辑
    // 因此整段作废。

    /** 输入槽在槽位列表里的下标。 */
    public static final int INPUT_SLOT = 0;
    /** 输出槽在槽位列表里的下标。 */
    public static final int OUTPUT_SLOT = 1;
    /**
     * 创造升级槽在槽位列表里的下标。
     *
     * <p><b>刻意不进 {@code TileComponentUpgrade}</b>：Mek 的升级体系里没有「创造升级」
     * 这个概念（{@code Upgrade} 枚举由 Mek 与各注入者定义，本模组注入的是存储卡与随机化卡），
     * 硬塞进去只会得到一个「升级 tab 里认不出来、槽位却占着」的格子。
     * 它作为<b>额外槽</b>保留：装上后能量恒满、耗时 1 tick、不耗电。</p>
     */
    public static final int CREATIVE_SLOT = 2;
    /** 能源槽（能量物品）在槽位列表里的下标。 */
    public static final int ENERGY_SLOT = 3;

    // ── 槽位坐标（GUI 相对）────────────────────────────────────────────
    //
    // 逐一对齐 Mek 基础电力熔炼炉（TileEntityElectricMachine#getInitialInventory）：
    // 输入 (64,17) / 输出 (116,35) / 能源槽 (64,53)。这三个值同时决定
    // GuiMekanism.addSlots() 画槽位框的位置与菜单槽的 x/y，改一处就会两处错位。

    private static final int INPUT_SLOT_X = 64;
    private static final int INPUT_SLOT_Y = 17;
    private static final int OUTPUT_SLOT_X = 116;
    private static final int OUTPUT_SLOT_Y = 35;
    private static final int ENERGY_SLOT_X = 64;
    private static final int ENERGY_SLOT_Y = 53;
    /**
     * 创造升级槽坐标。
     *
     * <p>旧实现把它藏在自研升级窗口里（菜单根本没 addSlot），而那条通路
     * （潜行手持安装 + 自研升级 tab）本次一并删除，所以必须给它一个主界面位置，
     * 否则「保留这个槽」等于保留一个永远够不着的格子。
     * 取 x=8 与本模组「额外槽」的既有约定一致（{@code MekCkFactoryLayout.EXTRA_SLOT_X}），
     * y=17 与输入槽同行，且与 y=74 的温度读数、y=84 起的玩家背包都不重叠。</p>
     */
    private static final int CREATIVE_SLOT_X = 8;
    private static final int CREATIVE_SLOT_Y = 17;

    // ── 机器参数 ────────────────────────────────────────────────────────

    /** 能量容量（FE）。由方块的 {@code AttributeEnergy} 声明，{@code MachineEnergyContainer} 构造时读它。 */
    public static final int ENERGY_CAPACITY = 100_000;
    /** 基础能耗（FE/tick）。同上，是<b>声明值</b>；真实扣电量见 {@link #energyPerTick()}。 */
    public static final int ENERGY_PER_TICK = 20;
    /** 一个批次的基础耗时（tick）。 */
    public static final int PROCESS_TIME = 200;

    /** 烧烤配方类型 id（Barbeque's Delight）。 */
    private static final ResourceLocation GRILLING_TYPE_ID = new ResourceLocation("barbequesdelight", "grilling");

    // ── 槽位对象 ────────────────────────────────────────────────────────

    private IInventorySlot inputSlot;
    private IInventorySlot outputSlot;
    private IInventorySlot creativeSlot;
    private EnergyInventorySlot energySlot;
    private MachineEnergyContainer<GrillBlockEntity> energyContainer;

    /**
     * AE2 侧看到的槽位视图（{@code INetworkPullable} 的 {@code ItemStackHandler} 契约）。
     *
     * <p><b>为什么是视图而不是另存一份</b>：{@code INetworkPullable} 是旧方块实体时代的
     * AE2 拉料契约，返回类型被写死成 {@code ItemStackHandler}，而 {@code MekckAe2} 里
     * 所有消费代码（{@code IntHandlerBulkView} 的批量插入、{@code FactoryGridHost.getItems()}
     * 的产物回写）都按这个类型写。改接口会波及 12 个仍是普通 {@code BlockEntity} 的实现，
     * 所以这里用 {@link MekCkSlotHandler} 把 Mek 槽位<b>适配</b>成那个类型——
     * 改动面为零，且视图直接读写真实槽对象，不存在「视图与真身不同步」。</p>
     *
     * <p>字段在 {@link #getInitialInventory} 里赋值（构造期回调），不能写成字段初始化器。</p>
     */
    private ItemStackHandler ae2View;

    // ── 热系统 ──────────────────────────────────────────────────────────

    /**
     * 本机热容。
     *
     * <p><b>只能在 {@link #getInitialHeatCapacitors} 里赋值</b>，不能写成字段初始化器 ——
     * 该钩子由 {@code TileEntityMekanism} 的构造器调用，而字段初始化器在
     * {@code super(...)} <b>之后</b>才跑，所以初始化器里建的电容永远是 null。</p>
     */
    @Nullable
    private BasicHeatCapacitor heatCapacitor;

    // ── 订单状态 ────────────────────────────────────────────────────────
    //
    // 方法签名是外部契约，不能改：NetworkOrderPanel / OrderRecipePacket / MekckAe2
    // 分别按名调用 setOrder / getOrderRecipeId / getOrderQuantity / getOrderCompleted /
    // isMeOrderEnabled / setMeOrderEnabled / getMaxConsumableCountForOrder / getAvailableRecipes。

    @Nullable
    private ResourceLocation orderRecipeId;
    private int orderQuantity;
    private int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true;

    // ── 进度与红石 ──────────────────────────────────────────────────────

    /** 本批次已走的 tick 数（服务端权威值）。 */
    private int progress;
    /** 客户端镜像：进度条已走的 tick 数。 */
    private int clientProgress;
    /** 客户端镜像：本机一个批次的 tick 数（进度条分母）。 */
    private int clientCycle;

    /**
     * PULSE 锁存：收到红石上升沿后一直放行，直到跑完一个完整批次。
     *
     * <p>{@code MekanismUtils.canFunction} 对 PULSE 的实现是
     * {@code isPowered() && !wasPowered()}，也就是上升沿那一 tick 放行、其余全禁。
     * 本模组的旧语义是<b>锁存</b>的：收到上升沿后一直放到整批做完才复位。
     * 若直接用 Mek 的口径，一次脉冲只能推进 1/200 的进度条，等于 PULSE 功能作废。</p>
     */
    private boolean pulseLatched;

    // ── 存档格式 ────────────────────────────────────────────────────────

    /**
     * 本机存档格式版本键。
     *
     * <p><b>刻意与工厂的 {@code MekCkNative} 不同名</b>：两者是不同家族各自的格式版本，
     * 共用一个键会让「工厂的版本号」与「烧烤架的版本号」互相冒充。缺这个键 = 旧格式
     * （自研 {@code BlockEntity} 写的），需要走 {@link #readLegacyState} 翻译。</p>
     */
    private static final String TAG_NATIVE_VERSION = "MekCkGrillNative";
    /** 当前存档格式版本。v1 是首个 Mek 原生版本。 */
    private static final int NATIVE_VERSION = 1;

    // 旧格式（自研 BlockEntity）的键，逐字取自迁移前的 GrillBlockEntity.saveAdditional。
    private static final String LEGACY_ITEMS = "Items";
    private static final String LEGACY_ENERGY = "Energy";
    private static final String LEGACY_SIDE_CONFIG = "SideConfig";
    private static final String LEGACY_REDSTONE_CONTROL = "RedstoneControl";
    private static final String LEGACY_HEAT_CAPACITOR = "HeatCapacitor";
    /** 旧 {@code ItemStackHandler.serializeNBT()} 内部的列表键（与外层同名不是笔误）。 */
    private static final String LEGACY_ITEMS_LIST = "Items";
    /** 旧槽位条目里的下标键。 */
    private static final String LEGACY_SLOT_INDEX = "Slot";

    // 本机自有状态（订单 / 进度 / ME 开关）的键，新旧格式<b>同名同型</b>，
    // 因此旧存档不需要翻译，直接读即可。
    private static final String TAG_ORDER_RECIPE = "OrderRecipeId";
    private static final String TAG_ORDER_QUANTITY = "OrderQuantity";
    private static final String TAG_ORDER_COMPLETED = "OrderCompleted";
    private static final String TAG_ME_ORDER_ENABLED = "MeOrderEnabled";
    private static final String TAG_PROGRESS = "Progress";

    public GrillBlockEntity(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
        // 侧配内容必须在这里、且只能在 super(...) 之后登记：
        // setupItemIOConfig 依赖 getInitialInventory 建好的槽位对象，
        // setupInputConfig 依赖 getInitialEnergyContainers 建好的能量容器。
        // （Mek 自己的 TileEntityElectricMachine 也是在构造器体里做同一件事。）
        configComponent.setupItemIOConfig(List.of(inputSlot), List.of(outputSlot), energySlot, false);
        configComponent.setupInputConfig(TransmissionType.ENERGY, energyContainer);
        // 只给 ITEM 挂弹出：ENERGY 侧的 ConfigInfo 已被 setupInputConfig 置为 setCanEject(false)。
        ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM);
    }

    // ── 构造期钩子 ──────────────────────────────────────────────────────

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
        // 物品 + 能量两路侧配：本机只有物品槽与能量输入，没有气体/流体/矿浆。
        configComponent = new mekanism.common.tile.component.TileComponentConfig(
                this, TransmissionType.ITEM, TransmissionType.ENERGY);
        ejectorComponent = new mekanism.common.tile.component.TileComponentEjector(this);
    }

    /**
     * 能量容器。
     *
     * <p>容量与能耗<b>不在这里设</b>：{@code MachineEnergyContainer.input} 会从方块的
     * {@code AttributeEnergy} 读取（实测其内部走
     * {@code validateBlock(tile).getStorage()/getUsage()}），所以两个值只能在
     * {@code GrillBlock.blockTypeFor} 里声明。</p>
     */
    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSideWithConfig(this::getDirection, this::getConfig);
        energyContainer = MachineEnergyContainer.input(this, listener);
        builder.addContainer(energyContainer);
        return builder.build();
    }

    /**
     * 排槽位：输入 / 输出 / 创造升级 / 能源槽。
     *
     * <p><b>构造期回调</b>：本方法在父类构造器内被调用，因此不能读本类字段初始化器产出的值；
     * 槽位对象必须在这里 {@code new}（见类注释的构造期顺序陷阱）。</p>
     *
     * <p>输入槽用 {@link MekCkSlot#inputFiltered} 而不是 {@link MekCkSlot#input}：
     * 旧实现的 {@code isItemValid} 对输入槽要求
     * {@code !isAnyUpgradeItem(stack) && RecipeInputMatcher.matchesGrilling(level, stack)}，
     * 而 {@code input} 的 {@code canInsert} 是 {@code alwaysTrueBi}（任何东西都收）。
     * 不校验的后果是玩家/管道能把升级卡与无关物品丢进输入槽，界面照收不误、
     * 却永远不参与加工——比直接拒绝更让人困惑。</p>
     */
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this::getDirection, this::getConfig);

        // 大堆叠：单槽容量 Integer.MAX_VALUE。MekCkSlot 的 obeyStackLimit = false
        // 使这个上限不被物品自身堆叠上限（桶 = 1）截回去，见该类注释。
        inputSlot = MekCkSlot.inputFiltered(Integer.MAX_VALUE,
                (stack, automation) -> !isAnyUpgradeItem(stack)
                        && RecipeInputMatcher.matchesGrilling(getLevel(), stack),
                listener, INPUT_SLOT_X, INPUT_SLOT_Y);
        builder.addSlot(inputSlot);

        outputSlot = MekCkSlot.output(Integer.MAX_VALUE, listener, OUTPUT_SLOT_X, OUTPUT_SLOT_Y);
        builder.addSlot(outputSlot);

        // 创造升级：单格、只收 mekanism_extras:upgrade_creative。
        creativeSlot = MekCkSlot.inputFiltered(1,
                (stack, automation) -> isCreativeUpgrade(stack),
                listener, CREATIVE_SLOT_X, CREATIVE_SLOT_Y);
        builder.addSlot(creativeSlot);

        // 能源槽（能量立方 / 能量板 / 红石）：与旧 PowerSlotUtil.drain 的行为对齐
        // （该类注释自己写着「行为与 Mekanism 的 EnergyInventorySlot.fillOrConvert 对齐」）。
        energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener,
                ENERGY_SLOT_X, ENERGY_SLOT_Y);
        builder.addSlot(energySlot);

        ae2View = new MekCkSlotHandler(List.of(inputSlot, outputSlot));
        return builder.build();
    }

    /**
     * 本机热容。
     *
     * <p>参数与 {@code MekCkHeatComponent} 同组（该类的 44/46 行是 private，这里取同值）：
     * 热容量 100 J/K、逆传导 5.0、逆绝缘 100.0，与 Mekanism 电阻型加热器一致。</p>
     *
     * <p><b>不需要自己实现 {@code getHeatCapacitors}</b> —— 它在
     * {@code TileEntityMekanism} 里是 {@code final}。基类已经：
     * ① {@code implements ITileHeatHandler}（而它 {@code extends IMekanismHeatHandler}，
     * 所以「第三方按 {@code blockEntity instanceof IMekanismHeatHandler} 识别本机为热处理器」
     * 这条路径<b>一直是通的</b>，例如气动工艺 PNC:R）；② 在构造器里<b>无条件</b>调本钩子
     * 并据此建 {@code HeatHandlerManager}；③ 已把电容温度挂进容器追踪
     * （{@code addContainerTrackers} 里对每个电容建 {@code SyncableDouble}），
     * 所以 GUI 读到的温度是同步值、不需要另开通道。</p>
     *
     * <p>环境回归与相邻热容器交换由 {@code TileEntityMekanism.tickServer} 里的
     * {@code updateHeatCapacitors(null)} 负责（实测偏移 114~116，条件是
     * {@code persists(SubstanceType.HEAT)}），因此本类不再需要旧
     * {@code MekCkHeatComponent.tick} 那一段。</p>
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

    /** 与 {@code MekCkHeatComponent} 同组的热学参数（该类的 44/46 行是 private，这里取同值）。 */
    private static final double INVERSE_CONDUCTION = 5.0;
    private static final double INVERSE_INSULATION = 100.0;

    // ── 每 tick ─────────────────────────────────────────────────────────

    /**
     * 服务端 tick。
     *
     * <p>由静态 {@code TileEntityMekanism.tickServer} 在
     * {@code upgradeComponent.tickServer()} <b>之后</b>无条件调用（实测偏移 18 → 97），
     * 所以本 tick 刚装好的速度卡能立刻影响本 tick 的批次长度。</p>
     *
     * <p>红石读数为什么是当 tick 的新值：{@code tickServer} 在偏移 97 调
     * {@code onUpdateServer()}，而在偏移 184~206 才做
     * {@code if (supportsRedstone()) redstoneLastTick = redstone}。
     * 也就是说 {@link #isPowered()} / {@link #wasPowered()} 在本方法里读到的确实是
     * 「本 tick / 上一 tick」两值，PULSE 的上升沿判定成立。</p>
     */
    @Override
    protected void onUpdateServer() {
        super.onUpdateServer();
        // 能源槽补能（Mek 自己的 TileEntityElectricMachine.onUpdateServer 第一句也是它）。
        if (energySlot != null) {
            energySlot.fillContainerOrConvert();
        }
        // 创造升级「能量恒满」：旧 serverTick 里它排在红石判定与「有没有活干」之前、
        // 不带任何其它条件 ⇒ 机器停机、没放料、红石禁用时照样每 tick 补满，能量条恒满。
        if (hasCreativeUpgrade()) {
            refillEnergy();
        }
        workCycle();
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）。
        // 排在 workCycle() 之后：本 tick 刚产出的物品要先落到产物槽，
        // MekckAe2 的产物回写才能在同一 tick 看到它们。
        cn.ism.mekck.compat.AE2Compat.serverTick(this, getLevel(), worldPosition);
    }

    /**
     * 闸门 + 进度 + 配方执行 —— 旧 {@code serverTick} 的「红石 → 能量 → 干活」三段式。
     *
     * <p>与旧实现逐条对应：
     * <ol>
     *   <li>{@code canOperate} = {@link #allowsWork()}（红石，PULSE 走锁存）；</li>
     *   <li>{@code canProcess} = 有订单 + 配方匹配 + 产物装得下；</li>
     *   <li>能量够 → 扣电 → 耗电转废热 → 进度 +1；</li>
     *   <li>进度满一个批次才执行配方，否则把进度清零并顺带释放 PULSE 锁存。</li>
     * </ol>
     * 任一条件不满足时进度清零——与旧实现
     * {@code else { if (progress != 0) progress = 0; } } 逐字一致。</p>
     */
    private void workCycle() {
        Level level = getLevel();
        if (level == null) {
            return;
        }
        boolean creative = hasCreativeUpgrade();
        // 创造升级：批次长度整个换成 1 tick、能耗整个置 0（不是「乘 0」，也不是「只对某个阶段免」）。
        int cycle = creative ? 1 : getEffectiveProcessTime();
        int cost = creative ? 0 : energyPerTick();

        Recipe<?> recipe = findRecipe(level).orElse(null);
        boolean canProcess = recipe != null && canFitOutput(recipe.getResultItem(level.registryAccess()));
        // 订单门禁：findRecipe 只在有订单时返回配方，这里再挡一次「订单已完成但还没清空」的窗口。
        if (canProcess && orderQuantity > 0 && orderCompleted >= orderQuantity) {
            canProcess = false;
        }
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
            // AutomationType 必须是 MANUAL 而不是 EXTERNAL：本机能量容器由
            // MachineEnergyContainer.input(tile, listener) 建成，而该工厂方法把
            // notExternal 传给了 canExtract（实测 BasicEnergyContainer.extract 开头是
            // if (!canExtract.test(type)) return ZERO）。传 EXTERNAL 会被整条拒掉、
            // 一 FE 都不扣——机器照常加工、照常有进度条，能量条却永远不掉。
            energyContainer.extract(FloatingLong.create(cost), Action.EXECUTE, AutomationType.MANUAL);
            // 耗电转废热：与旧实现同一次序（extractEnergy 之后、progress++ 之前）。
            addHeatFromEnergy(cost);
        }
        progress++;
        if (progress >= cycle) {
            completeRecipe(level, recipe);
            progress = 0;
            // PULSE：跑完一整个批次才解除锁存（allowsWork 的锁存语义见 pulseLatched）。
            pulseLatched = false;
        }
        // active 口径与旧实现逐字一致：旧代码在 serverTick 末尾算 isActive = progress > 0，
        // 而那时 progress 刚被清零，所以「刚跑完一批」的那一 tick 机器就是不 active 的。
        setActive(progress > 0);
        setChanged();
    }

    /**
     * 本 tick 是否允许推进工作。
     *
     * <p>DISABLED / HIGH / LOW 三档直接用 Mek 自己的
     * {@link MekanismUtils#canFunction}，语义与旧实现的
     * {@code RedstoneControl.canFunction} 逐档一致（{@code cn.ism.mekck.RedstoneControl}
     * 的注释里写明「与 MekanismUtils.canFunction 相同」）。</p>
     *
     * <p>PULSE 单独处理，理由见 {@link #pulseLatched}。</p>
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
        // 已满或超容时什么都不做：IEnergyContainer.getNeeded() 本来就夹到 0，
        // 这里显式判一次是为了省掉每 tick 一次空 insert，并让「满了就不动」可读。
        if (max.compareTo(stored) <= 0) {
            return;
        }
        energyContainer.insert(max.subtract(stored), Action.EXECUTE, AutomationType.MANUAL);
    }

    /**
     * 把本 tick 耗掉的电按<b>发电效率</b>转成废热。
     *
     * <p>与迁移前逐字同款：旧 {@code serverTick} 里是
     * {@code machine.energy.extractEnergy(energyPerTick, false);
     * machine.heatComponent.addHeatFromEnergy(energyPerTick); machine.progress++;}，
     * 而 {@code MekCkHeatComponent.addHeatFromEnergy} 内部按
     * {@code HEAT_EFFICIENCY = 0.6} 折算 —— 这里显式用同一个常数，避免两处漂移。</p>
     */
    private void addHeatFromEnergy(int energyUsed) {
        if (energyUsed > 0 && heatCapacitor != null) {
            heatCapacitor.handleHeat(energyUsed * cn.ism.mekck.util.MekCkHeatComponent.HEAT_EFFICIENCY);
        }
    }

    // ── 温度 ────────────────────────────────────────────────────────────

    /**
     * 当前机身温度（开尔文）。
     *
     * <p>方法名是外部契约（{@code GrillMenu.getTemperature()} 在调它），不能改。
     * 客户端读到的是 Mek 同步下来的电容温度（见 {@link #getInitialHeatCapacitors}）。</p>
     */
    public double getTemperature() {
        return heatCapacitor == null ? mekanism.api.heat.HeatAPI.AMBIENT_TEMP : heatCapacitor.getTemperature();
    }

    // ── 配方 ────────────────────────────────────────────────────────────

    /**
     * 找当前该做的配方 —— <b>只在有订单时返回</b>。
     *
     * <p>没下单就返回空是刻意的：这台机器由 ME 终端 / 本机下单面板驱动，
     * 不自动吃料。改成「有料就做」会把它变成一台会自己烧掉玩家材料的机器。</p>
     */
    private Optional<Recipe<?>> findRecipe(Level level) {
        ItemStack input = inputSlot.getStack();
        if (input.isEmpty() || orderRecipeId == null) {
            return Optional.empty();
        }
        Recipe<?> orderedRecipe = findRecipeById(level, orderRecipeId);
        if (orderedRecipe != null && matchesInput(orderedRecipe, input)) {
            return Optional.of(orderedRecipe);
        }
        return Optional.empty();
    }

    /**
     * 配方配料是否匹配输入。
     *
     * <p>用反射读 {@code SimpleGrillingRecipe.ingredient} 字段，失败回退
     * {@code getIngredients().get(0)}。反射那条路不能删：Barbeque's Delight 的
     * {@code SimpleGrillingRecipe} 把配料放在一个<b>非接口</b>字段上，
     * 而 {@code getIngredients()} 在它上面返回的是空列表（见
     * {@code recipe/MekCkGrillingRecipe} 的类注释），只走回退路径会一张配方都匹配不上。</p>
     */
    private static boolean matchesInput(Recipe<?> recipe, ItemStack input) {
        try {
            java.lang.reflect.Field field = recipe.getClass().getField("ingredient");
            Ingredient ingredient = (Ingredient) field.get(recipe);
            return ingredient.test(input);
        } catch (Exception ignored) {
            // 字段不存在 / 不可访问：走下面的标准接口回退，不吞掉任何东西。
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        return !ingredients.isEmpty() && ingredients.get(0).test(input);
    }

    @Nullable
    private Recipe<?> findRecipeById(Level level, ResourceLocation recipeId) {
        RecipeType<?> grillingType = cn.ism.mekck.util.RecipeCache.type(GRILLING_TYPE_ID);
        if (grillingType == null) {
            return null;
        }
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, grillingType)) {
            if (recipe.getId().equals(recipeId)) {
                return recipe;
            }
        }
        return null;
    }

    /** 供「本机下单」面板的 Max 按钮：输入槽现有材料能做几份（烧烤配方 1 输入 = 1 份）。 */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        if (recipe == null || getLevel() == null) {
            return 0;
        }
        ItemStack input = inputSlot.getStack();
        if (input.isEmpty() || !matchesInput(recipe, input)) {
            return 0;
        }
        return Math.max(0, input.getCount());
    }

    /** 输入槽现有材料能做的全部烧烤配方（本机下单面板的列表来源）。 */
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> available = new ArrayList<>();
        Level level = getLevel();
        ItemStack input = inputSlot.getStack();
        if (input.isEmpty() || level == null) {
            return available;
        }
        RecipeType<?> grillingType = cn.ism.mekck.util.RecipeCache.type(GRILLING_TYPE_ID);
        if (grillingType == null) {
            return available;
        }
        for (Recipe<?> recipe : cn.ism.mekck.util.RecipeCache.all(level, grillingType)) {
            if (matchesInput(recipe, input)) {
                available.add(recipe);
            }
        }
        return available;
    }

    private void completeRecipe(Level level, Recipe<?> recipe) {
        ItemStack input = inputSlot.getStack();
        if (input.isEmpty()) {
            return;
        }
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) {
            return;
        }
        // 消耗 1 份输入。AutomationType 用 INTERNAL：MekCkSlot.input 的 canExtract 是
        // notExternal，INTERNAL 与 MANUAL 都放行，选 INTERNAL 是为了表明「机器自己吃料」。
        inputSlot.extractItem(1, Action.EXECUTE, AutomationType.INTERNAL);
        insertOutput(result.copy());

        if (orderQuantity > 0) {
            orderCompleted++;
            if (orderCompleted >= orderQuantity) {
                orderQuantity = 0;
                orderCompleted = 0;
                orderRecipeId = null;
            }
        }
    }

    private boolean canFitOutput(ItemStack result) {
        ItemStack existing = outputSlot.getStack();
        if (existing.isEmpty()) {
            return true;
        }
        if (ItemStack.isSameItemSameTags(existing, result)) {
            return (long) existing.getCount() + result.getCount() <= Integer.MAX_VALUE;
        }
        return false;
    }

    /**
     * 把产物并入输出槽。
     *
     * <p>走 {@code setStack} 而不是改 {@code getStack()} 返回的活引用：后者会绕过槽的
     * {@code onContentsChanged}，区块不会被标记为脏，玩家看到的是「产物在 GUI 里出现了，
     * 退出重进就没了」。{@code MekCkSlot} 的 validator 是 {@code alwaysTrue}，
     * 所以 {@code setStack} 不会抛（实测 {@code BasicInventorySlot.setStack} 只在
     * {@code !isItemValid} 时抛 RuntimeException）。</p>
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

    // ── 订单 ────────────────────────────────────────────────────────────

    @Override
    public boolean isMeOrderEnabled() {
        return meOrderEnabled;
    }

    @Override
    public void setMeOrderEnabled(boolean enabled) {
        this.meOrderEnabled = enabled;
        setChanged();
    }

    /**
     * 下单：{@code recipeId == null} 表示取消订单（两个 GUI 都发 (null, 0)）。
     *
     * <p>数量下界与 {@link MekCkOrderState#setOrder} 对齐：<b>取消时清零、激活时夹到 ≥ 1</b>。
     * 本类原先是 {@code orderQuantity = quantity} 原样存，与其余 9 台实现的契约不一致 ——
     * 它<b>只靠调用方恰好夹过</b>（{@code OrderRecipePacket} 与
     * {@code NetworkOrderPacket} 都在入口做了 {@code max(1, ·)}）才没出事。
     * 那种隐式依赖很脆：下次新增一个不经包的调用点（AE2 内部反射分派、命令、未来重构）
     * 就会把 0 或负数直接写进来，而后果是<b>永久静默</b>的：
     * {@code orderQuantity > 0} 门禁与 {@code orderCompleted >= orderQuantity} 同时失效，
     * 订单永远不完成、AE2 job 永不释放（见 {@code MekckAe2.orderStateOf}）。</p>
     */
    public void setOrder(@Nullable ResourceLocation recipeId, int quantity) {
        this.orderRecipeId = recipeId;
        this.orderQuantity = recipeId == null ? 0 : Math.max(1, quantity);
        this.orderCompleted = 0;
        setChanged();
    }

    @Nullable
    public ResourceLocation getOrderRecipeId() {
        return orderRecipeId;
    }

    public int getOrderQuantity() {
        return orderQuantity;
    }

    public int getOrderCompleted() {
        return orderCompleted;
    }

    // ── 升级 ────────────────────────────────────────────────────────────

    /**
     * 本机已装的某种升级数量。
     *
     * <p>读 {@code TileComponentUpgrade.getUpgrades(type)}——Mek 自己的升级组件里
     * 同样有 20 tick 安装读条（实测 {@code TileComponentUpgrade.tickServer} 里
     * {@code getUpgrades(type) < getMax()} 才推进），因此不需要再单独实现一套。
     * 刻意不缓存：缓存会与那 20 tick 读条打架，刚放进去还没装好的那 20 tick 内不该提前生效。</p>
     */
    private int installedUpgrades(Upgrade type) {
        TileComponentUpgrade component = getComponent();
        return component == null ? 0 : component.getUpgrades(type);
    }

    public double getEffectiveSpeedMultiplier() {
        return UpgradeHelper.speedMultiplier(installedUpgrades(Upgrade.SPEED));
    }

    public double getEffectiveEnergyConsumptionMultiplier() {
        return UpgradeHelper.energyConsumptionMultiplier(installedUpgrades(Upgrade.ENERGY));
    }

    /** 实际生效的批次长度（tick）。 */
    public int getEffectiveProcessTime() {
        return Math.max(1, (int) (PROCESS_TIME / getEffectiveSpeedMultiplier()));
    }

    /** 本 tick 的真实扣电量（FE）—— 速度倍率平方 × 能量卡倍率，与旧实现同式。 */
    private int energyPerTick() {
        double speedMult = getEffectiveSpeedMultiplier();
        return (int) Math.ceil(ENERGY_PER_TICK * speedMult * speedMult
                * getEffectiveEnergyConsumptionMultiplier());
    }

    // ── 创造升级 ────────────────────────────────────────────────────────

    public boolean hasCreativeUpgrade() {
        return creativeSlot != null && !creativeSlot.getStack().isEmpty();
    }

    public static boolean isCreativeUpgrade(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.equals(ResourceLocation.tryParse("mekanism_extras:upgrade_creative"));
    }

    /**
     * 该物品是否是「任何升级模块」。
     *
     * <p>输入槽据此拒绝升级卡：升级卡有自己该去的地方（Mek 的升级槽 / 创造升级槽），
     * 丢进输入槽只会占着格子、永远不参与加工。</p>
     */
    public static boolean isAnyUpgradeItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        return id.equals(ResourceLocation.tryParse("mekanism:upgrade_speed"))
                || id.equals(ResourceLocation.tryParse("mekanism:upgrade_energy"))
                || id.equals(ResourceLocation.tryParse("mekanism_extras:upgrade_stack"))
                || id.equals(ResourceLocation.tryParse("mekanism_extras:upgrade_creative"))
                || id.equals(ResourceLocation.tryParse("mekanism:upgrade_gas"));
    }

    // ── 进度（GUI 读数）────────────────────────────────────────────────

    /** 本批次已走的 tick 数。按端分流：服务端读权威值、客户端读镜像。 */
    public int getWorkProgress() {
        return clientMirroring() ? clientProgress : progress;
    }

    /** 本批次的总 tick 数（进度条分母）。按端分流，理由同 {@link #getWorkProgress()}。 */
    public int getWorkCycle() {
        return clientMirroring() ? clientCycle : getEffectiveProcessTime();
    }

    /**
     * 此刻是否应读客户端镜像。
     *
     * <p>多考虑了 {@code level == null}（GUI 构造期可能早于 BE 绑定）：拿不到世界就按客户端处理，
     * 返回镜像值而不是去碰权威状态 —— 宁可显示 0 也不能在 GUI 构造期抛异常。</p>
     */
    private boolean clientMirroring() {
        return level == null || level.isClientSide;
    }

    /**
     * 把进度挂进 Mek 的容器同步通道。
     *
     * <p>这是 Mek 机器同步数据的<b>唯一</b>正规入口：{@code MekanismTileContainer.addContainerTrackers()}
     * 会调 {@code tile.addContainerTrackers(this)}，而 {@code MekanismContainer.track(ISyncableData)}
     * 把条目收进 {@code trackedData}，由 Mek 自己的容器属性包按脏值增量下发。
     * 自己发包要另写一套「谁在什么时候发、玩家关屏后怎么办」的状态机，
     * 而 Mek 这套已经处理好了开屏/关屏/重开屏。</p>
     *
     * <p>分母（批次长度）也走同步而不是让客户端自己算：它依赖已装速度卡数，
     * 而客户端那份升级计数只是区块加载时的快照，两边一旦漂移，
     * 屏幕上就会画出与实际进度无关的比例。</p>
     */
    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(SyncableInt.create(this::getWorkProgress, value -> this.clientProgress = value));
        container.track(SyncableInt.create(this::getWorkCycle, value -> this.clientCycle = value));
    }

    // ── AE2 网络拉料 ────────────────────────────────────────────────────

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity getNetworkPullable() {
        return this;
    }

    /** 网络拉料把材料插入 [0, 1) —— 只有输入槽，产物槽不参与。 */
    @Override
    public int[] getInputSlotRange() {
        return new int[]{INPUT_SLOT, OUTPUT_SLOT};
    }

    @Override
    public ItemStackHandler getNetworkPullItems() {
        return ae2View;
    }

    @Override
    public boolean supportsAutoPull() {
        return true;
    }

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        ItemStack slot0 = inputSlot.getStack();
        if (!slot0.isEmpty()) {
            return List.of(new cn.ism.mekck.util.AE2InputSpec(Ingredient.of(slot0.getItem())));
        }
        Ingredient union = cn.ism.mekck.util.RecipeInputMatcher.unionFirstIngredients(getLevel(), GRILLING_TYPE_ID);
        return union.isEmpty() ? List.of() : List.of(new cn.ism.mekck.util.AE2InputSpec(union));
    }

    /** AE2 侧看到的槽位视图（{@code MekckAe2} 的产物回写走它）。 */
    public ItemStackHandler getItems() {
        return ae2View;
    }

    // ── 持久化 ──────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>只写本机自有状态：能量、侧配、升级、热容、自定义名字仍然由
     * {@code TileEntityMekanism} 自己的 {@code saveAdditional} 写，重复写会互相覆盖。</p>
     */
    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (orderRecipeId != null) {
            tag.putString(TAG_ORDER_RECIPE, orderRecipeId.toString());
        }
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean(TAG_ME_ORDER_ENABLED, meOrderEnabled);
        tag.putInt(TAG_ORDER_QUANTITY, orderQuantity);
        tag.putInt(TAG_ORDER_COMPLETED, orderCompleted);
        tag.putInt(TAG_PROGRESS, progress);
        tag.putInt(TAG_NATIVE_VERSION, NATIVE_VERSION);
        // AE2 网格节点的 NBT 必须与节点一同存活：节点里存着频道占用与
        // 「已勾选的自动处理材料」，不写就等于每次重载都换一批频道。
        // AE2Compat 未装时整个方法短路为空操作。
        cn.ism.mekck.compat.AE2Compat.saveAdditional(this, tag);
        // 放置者归属（网络厨师学徒）：与旧 BE 的 saveAdditional 逐字同款。
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
    }

    /**
     * {@inheritDoc}
     *
     * <p>1.20.1 的读档入口是 {@code load}，<b>没有</b> 1.20.4+ 的 {@code loadAdditional}。</p>
     *
     * <h3>旧存档迁移</h3>
     * 方块注册名没变（仍是 {@code mekck:electric_grill}）、变的是
     * {@code BlockEntityType} 的实现类，所以旧存档的内容<b>没有任何代码会去读</b>——
     * 方块还在，里面空了。判据是「本机格式版本键是否存在」：缺了就是旧格式，
     * 走 {@link #readLegacyState} 把旧键翻译进新槽位/新组件。
     *
     * <p><b>翻译必须在 {@code super.load} 之后</b>：{@code super.load} 会按 Mek 的格式
     * 往槽位里灌一遍（旧格式的 {@code Items} 是 CompoundTag、新格式是 ListTag，
     * 同名不同型 ⇒ 它读到空列表、槽位保持空），随后我们按旧下标把内容放回去。
     * 顺序反了等于没写这一层。</p>
     *
     * <p><b>红石模式不在这里读</b>：{@code controlType} 是 {@code TileEntityMekanism} 的
     * 私有字段，由它自己的 {@code loadGeneralPersistentData} 负责，本类重复读会互相覆盖。
     * 旧格式的 {@code RedstoneControl} 是另一个键名，由 {@link #readLegacyState} 翻译。</p>
     */
    @Override
    public void load(CompoundTag tag) {
        boolean legacy = !tag.contains(TAG_NATIVE_VERSION);
        super.load(tag);
        if (legacy) {
            readLegacyState(tag);
        }
        readOwnState(tag);
    }

    /** 本机自有状态（订单 / 进度 / ME 开关）—— 新旧格式同名同型，两条路径共用。 */
    private void readOwnState(CompoundTag tag) {
        if (tag.contains(TAG_ORDER_RECIPE)) {
            orderRecipeId = ResourceLocation.tryParse(tag.getString(TAG_ORDER_RECIPE));
        }
        // 缺失键取 true（= 默认开），与旧实现的 !contains || getBoolean 同口径。
        meOrderEnabled = !tag.contains(TAG_ME_ORDER_ENABLED) || tag.getBoolean(TAG_ME_ORDER_ENABLED);
        orderQuantity = tag.getInt(TAG_ORDER_QUANTITY);
        // 读档同样过 setOrder 的契约闸门：orderRecipeId != null 时数量必须 ≥ 1，
        // 否则订单门禁（orderQuantity > 0）与完成推进同时失效 ⇒ 机器无限加工、订单永不完成。
        if (orderRecipeId != null) orderQuantity = Math.max(1, orderQuantity);
        orderCompleted = tag.getInt(TAG_ORDER_COMPLETED);
        progress = Math.max(0, tag.getInt(TAG_PROGRESS));
    }

    /**
     * 旧格式（自研 {@code BlockEntity}）的键 → 新槽位 / 新组件。
     *
     * <h3>为什么逐键翻译而不是「兼容读旧键」</h3>
     * 兼容分支会让此后每一个「存档读出来是空的」类 bug 都要查两遍格式，
     * 而两套格式的键还会互相撞名（{@code Items} 就是同名不同型的那一对）。
     * 一次性翻译完，此后只有一种格式。
     *
     * <h3>旧槽位下标 → 新槽位</h3>
     * <pre>
     *   旧 [0] 输入        → 新 INPUT_SLOT
     *   旧 [1] 输出        → 新 OUTPUT_SLOT
     *   旧 [2] 速度升级卡  → componentUpgrade 的 0 号槽
     *   旧 [3] 能量升级卡  → componentUpgrade 的 1 号槽
     *   旧 [4] 创造升级    → 新 CREATIVE_SLOT
     *   旧 [5] 能源槽      → 新 ENERGY_SLOT
     * </pre>
     * 升级卡必须搬进 {@code componentUpgrade}：新体系里它们由
     * {@code TileComponentUpgrade} 持有，留在物品槽里只会变成两张占格子的废卡。
     * 搬法走 {@code addUpgrades(type, n)} 而不是往升级槽里塞物品——
     * 后者会绕过 20 tick 安装读条，而且 {@code TileComponentUpgrade.read} 的
     * {@code upgrades.clear()} 会把先灌进去的抹掉。
     */
    private void readLegacyState(CompoundTag tag) {
        readLegacySlots(tag);
        readLegacyEnergy(tag);
        readLegacyHeat(tag);
        readLegacySideConfig(tag);
        if (tag.contains(LEGACY_REDSTONE_CONTROL, Tag.TAG_INT)) {
            setControlType(IRedstoneControl.RedstoneControl.values()[
                    cn.ism.mekck.machine.MekCkLegacyMachineNbt.toNativeRedstoneControl(
                            tag.getInt(LEGACY_REDSTONE_CONTROL))]);
        }
    }

    private void readLegacySlots(CompoundTag tag) {
        if (!tag.contains(LEGACY_ITEMS, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag old = tag.getCompound(LEGACY_ITEMS);
        net.minecraft.nbt.ListTag oldList = old.getList(LEGACY_ITEMS_LIST, Tag.TAG_COMPOUND);
        int speedCards = 0;
        int energyCards = 0;
        for (int i = 0; i < oldList.size(); i++) {
            CompoundTag entry = oldList.getCompound(i);
            // 用 BigStackItemHandler.readStack 而不是 ItemStack.of：旧格式的权威数量写在
            // int 型 McCount 键上，原版 Count 只是 byte，超过 127 的堆叠靠它才活得下来。
            ItemStack stack = cn.ism.mekck.util.BigStackItemHandler.readStack(entry);
            if (stack.isEmpty()) {
                continue;
            }
            switch (entry.getInt(LEGACY_SLOT_INDEX)) {
                case 0 -> inputSlot.setStack(stack);
                case 1 -> outputSlot.setStack(stack);
                case 2 -> speedCards += stack.getCount();
                case 3 -> energyCards += stack.getCount();
                case 4 -> creativeSlot.setStack(stack);
                case 5 -> energySlot.setStack(stack);
                default -> {
                    // 越界下标：旧格式只有 6 格，走到这里说明存档被外力改过。
                    // 宁可丢一格也不要把内容放进错误的槽位。
                }
            }
        }
        TileComponentUpgrade component = getComponent();
        if (component != null) {
            if (speedCards > 0) {
                component.addUpgrades(Upgrade.SPEED, Math.min(speedCards, Upgrade.SPEED.getMax()));
            }
            if (energyCards > 0) {
                component.addUpgrades(Upgrade.ENERGY, Math.min(energyCards, Upgrade.ENERGY.getMax()));
            }
        }
    }

    private void readLegacyEnergy(CompoundTag tag) {
        int stored = tag.getInt(LEGACY_ENERGY);
        if (stored > 0) {
            energyContainer.insert(FloatingLong.create(stored), Action.EXECUTE, AutomationType.MANUAL);
        }
    }

    private void readLegacyHeat(CompoundTag tag) {
        if (heatCapacitor != null && tag.contains(LEGACY_HEAT_CAPACITOR, Tag.TAG_COMPOUND)) {
            // 旧 MekCkHeatComponent.save() 返回的就是 BasicHeatCapacitor.serializeNBT()，
            // 同一个类、同一个形状，直接反序列化即可。
            heatCapacitor.deserializeNBT(tag.getCompound(LEGACY_HEAT_CAPACITOR));
        }
    }

    /**
     * 旧 {@code byte[6]} 侧配 → Mek 的 {@code componentConfig}。
     *
     * <p>两边的下标基准不同（旧的是 {@link Direction#ordinal()} 六面，Mek 的是
     * {@link mekanism.api.RelativeSide} 的 FRONT/LEFT/RIGHT/BACK/TOP/BOTTOM），
     * 因此必须逐面翻译，不能直接复制字节。翻译走 {@code RelativeSide.getDirection}
     * 反查，不自己写「FRONT 就是 facing」之类的映射表——那正是会悄悄写错的一个面。</p>
     */
    private void readLegacySideConfig(CompoundTag tag) {
        if (!tag.contains(LEGACY_SIDE_CONFIG, Tag.TAG_BYTE_ARRAY)) {
            return;
        }
        byte[] legacyBytes = tag.getByteArray(LEGACY_SIDE_CONFIG);
        ConfigInfo itemConfig = configComponent == null ? null : configComponent.getConfig(TransmissionType.ITEM);
        if (itemConfig == null) {
            return;
        }
        Direction facing = getDirection() == null ? Direction.NORTH : getDirection();
        for (mekanism.api.RelativeSide side : mekanism.api.RelativeSide.values()) {
            Direction worldSide = side.getDirection(facing);
            int ordinal = worldSide.ordinal() < legacyBytes.length ? legacyBytes[worldSide.ordinal()] : -1;
            itemConfig.setDataType(
                    cn.ism.mekck.machine.MekCkLegacyMachineNbt.toNativeDataType(
                            cn.ism.mekck.machine.MekCkLegacyMachineNbt.legacySideMode(ordinal)),
                    side);
        }
    }

    // ── ISustainedData：掉落 → 再放置 的状态恢复 ────────────────────────

    /**
     * {@inheritDoc}
     *
     * <h3>为什么必须自己接这一钩子</h3>
     * 迁到 Mek 的 {@code BlockTile} 之后，破坏方块走的是战利品表，BE 的内容不会自动
     * 跟着物品走；恢复走 {@code BlockMekanism.setPlacedBy}（实测偏移 102 取
     * {@code ItemDataUtils.getDataMapIfPresent}，242~296 读 componentUpgrade /
     * componentConfig / componentEjector，299~361 读 {@code SubstanceType} 容器，
     * 364~391 调 {@code ISustainedData.readSustainedData}）。
     *
     * <p><b>槽位不在 {@code SubstanceType} 里</b>（枚举只有 ENERGY/FLUID/GAS/INFUSION/
     * PIGMENT/SLURRY/HEAT），所以 {@code Items} 必须由本方法自己读回来——
     * 少了这一句的表现是「挖掉再放下，机器里的料全没了」。</p>
     *
     * <p>本机只有 4 个槽，远低于 Mek 槽位存档的 byte 下标上限 127，
     * 因此直接走 {@code DataHandlerUtils.readContainers} 即可，
     * 不需要工厂那套 int 下标的 {@code MekCkSlots} 兜底。</p>
     */
    @Override
    public void readSustainedData(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        if (tag.contains(LEGACY_ITEMS, Tag.TAG_LIST)) {
            mekanism.api.DataHandlerUtils.readContainers(getInventorySlots(null),
                    tag.getList(LEGACY_ITEMS, Tag.TAG_LIST));
        }
        readOwnState(tag);
        cn.ism.mekck.compat.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
    }

    /**
     * <b>有意为空实现</b>：本机不往「可复制的配置数据」里写任何自有键。
     *
     * <p>本钩子<b>不是</b>「只在挖掉时调用」——它同时被
     * {@code TileEntityMekanism.getConfigurationData(Player)}（配置卡复制）与
     * {@code BlockMekanism.getCloneItemStack}（中键取方块）调用。
     * 只要这里写了槽位键，配置卡就会连带复制整机库存——从一台满机器复制一张卡、
     * 再往空机器上反复粘贴，就是一次干净的物品复制漏洞。
     * 「挖掉再放下」这条路径由战利品表的 {@code copy_nbt} 直接把 BE 存档键拷进
     * {@code mekData.*}，不需要本方法出力。</p>
     */
    @Override
    public void writeSustainedData(CompoundTag tag) {
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mek 10.4.6 里没有任何外部消费方（对全 jar 做常量池扫描 + 逐类 {@code javap -p -c}
     * 复核：按接口调用或虚调用的外部消费方为 0，仅有的 4 处是 QIO 继承链上的
     * {@code super.getTileDataRemap()}）。本模组的键名两侧同名
     * （战利品表 {@code copy_nbt} 的 target 就是 {@code mekData.<同名键>}），
     * 因此照接口返回空表。</p>
     */
    @Override
    public java.util.Map<String, String> getTileDataRemap() {
        return java.util.Map.of();
    }

    /**
     * AE2 网格节点随方块卸载一并销毁。
     *
     * <p>不断的话，AE2 侧会一直认为这台机器还占着频道，表现为拆掉再放一台就接不上网络。</p>
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        cn.ism.mekck.compat.AE2Compat.onRemoved(this);
    }

    /** 供 GUI 与外部读的能量容器。 */
    public MachineEnergyContainer<GrillBlockEntity> getEnergyContainer() {
        return energyContainer;
    }

    /** 供 GUI 读的能源槽对象。 */
    public EnergyInventorySlot getEnergySlot() {
        return energySlot;
    }

    /** 供 GUI 读的创造升级槽对象。 */
    public IInventorySlot getCreativeSlot() {
        return creativeSlot;
    }
}
