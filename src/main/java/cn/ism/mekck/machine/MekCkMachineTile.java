package cn.ism.mekck.machine;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.factory.MekCkFactoryBlock;
import cn.ism.mekck.factory.MekCkFactoryTier;
import cn.ism.mekck.factory.MekCkFactoryType;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.FloatingLong;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
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
import java.util.LinkedHashMap;
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
 *   <li>返回类型对不上（返回 {@link MekCkFactoryTier} 之类）→ 强转抛
 *       {@code ClassCastException}，而该 catch 只接 {@code ReflectiveOperationException}，
 *       会一路冒到读档路径上炸服。</li>
 * </ul>
 * 所以本类返回的是 {@code cn.ism.mekck.CuttingMachineFactoryTier}——与
 * {@code MekCkUpgradeTypes.isSupportedBy/capOf} 和
 * {@code MekckConfig.getFactoryStackUpgradeMax} 的入参类型一致，
 * 也与阶段 2 Task 4 里「{@code CuttingFactoryTile.getTier()} 返回
 * {@code CuttingMachineFactoryTier}」的要求兼容（同类型才允许覆写）。
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

    /** NBT：执行器自有状态的子标签。 */
    private static final String TAG_EXECUTOR = "mekckExecutor";
    /** NBT：本 tile 由 Mek 原生基类承载的存档格式版本。 */
    static final String TAG_NATIVE_VERSION = "MekCkNative";
    /** NBT：进度条已走的 tick 数。 */
    static final String TAG_WORK_PROGRESS = "MekCkWorkProgress";
    /** 当前存档格式版本。v1 是首个 Mek 原生版本，没有需要迁移的旧格式。 */
    private static final int NATIVE_VERSION = 1;

    /**
     * {@link MekCkFactoryTier} → {@link CuttingMachineFactoryTier} 的按名映射。
     *
     * <p>两个枚举是并行新增的（前者实现 {@code ITier} 供 Mek API 用，后者是旧自研套的
     * 等级、也是升级与配置体系的入参类型），常量名一一对应但<b>是两个不同的类型</b>。
     * 这里按<b>序列化名</b>而不是 {@code ordinal()} 对齐：{@code ordinal()} 一旦在任一侧
     * 插入常量就会静默错位一名档位，而档位错位直接等价于「支持哪些升级」判错。</p>
     */
    private static final Map<String, CuttingMachineFactoryTier> CUTTING_TIER_BY_NAME;

    static {
        Map<String, CuttingMachineFactoryTier> byName = new LinkedHashMap<>();
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            byName.put(tier.name, tier);
        }
        // 缺一档就在类初始化时炸，而不是等到某一台机器安静地少一档能力。
        for (MekCkFactoryTier tier : MekCkFactoryTier.values()) {
            if (!byName.containsKey(tier.getLowerName())) {
                throw new IllegalStateException("工厂等级 " + tier.getLowerName()
                        + " 在 MekCkFactoryTier 里存在、在 CuttingMachineFactoryTier 里缺失："
                        + "两个枚举必须同名同集合。缺一档会让该档机器静默失去存储卡倍增能力。");
            }
        }
        CUTTING_TIER_BY_NAME = Map.copyOf(byName);
    }

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
     * 本机等级 —— <b>从方块反查</b>，不读实例字段（构造期字段尚未初始化的原因见类注释）。
     *
     * <p>子类若绑定的不是 {@link MekCkFactoryBlock}，覆写本方法返回自己的等级即可。
     * 返回 {@code null} 表示「本机档位未知」：此时 {@code MekCkUpgradeTypes.isSupportedBy}
     * 对存储卡判 {@code false}（缺依据即不放行），其余升级不受影响。</p>
     */
    protected CuttingMachineFactoryTier tierFromBlock() {
        if (blockProvider != null && blockProvider.getBlock() instanceof MekCkFactoryBlock block) {
            return CUTTING_TIER_BY_NAME.get(block.getFactoryTier().getLowerName());
        }
        return null;
    }

    /** 工艺类型：与 {@link #tierFromBlock()} 同理，同样是 {@code protected} 以便子类覆写。 */
    protected MekCkFactoryType typeFromBlock() {
        if (blockProvider != null && blockProvider.getBlock() instanceof MekCkFactoryBlock block) {
            return block.getFactoryType();
        }
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
                    + " 未携带 MekCK 等级：MekCkMachineTile 只能从 MekCkFactoryBlock（或子类覆写"
                    + " tierFromBlock()）反查档位。请给该 BlockType 绑定 MekCkFactoryBlock。");
        }

        inputSlots = new ArrayList<>(tier.processes);
        outputSlots = new ArrayList<>(tier.processes);

        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this::getDirection, this::getConfig);
        int count = tier.processes;
        int columns = (int) Math.ceil(Math.sqrt(count));
        // 输入方阵在左、输出方阵右移「输入方阵宽度 + 间隔」，与旧实现一致。
        addSlotGrid(builder, listener, INPUT_START_X, count, columns, true);
        addSlotGrid(builder, listener, INPUT_START_X + columns * SLOT_STEP + GRID_GAP, count, columns, false);
        energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener,
                ENERGY_SLOT_X, ENERGY_SLOT_Y);
        builder.addSlot(energySlot);
        return builder.build();
    }

    /** 排一列方阵。`count` 由调用方从已校验非空的等级读出后传入，本方法不再回查等级。 */
    private void addSlotGrid(InventorySlotHelper builder, IContentsListener listener,
                             int startX, int count, int columns, boolean input) {
        List<IInventorySlot> target = input ? inputSlots : outputSlots;
        for (int i = 0; i < count; i++) {
            int x = startX + (i % columns) * SLOT_STEP;
            int y = GRID_START_Y + (i / columns) * SLOT_STEP;
            IInventorySlot slot = input
                    ? InputInventorySlot.at(listener, x, y)
                    : OutputInventorySlot.at(listener, x, y);
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
        workCycle();
    }

    /** 闸门 + 进度条 + 执行器调度。拆出来只为让 {@link #onUpdateServer} 保持一屏可读。 */
    private void workCycle() {
        int cycle = Math.max(1, ticksPerWorkCycle());
        int cost = energyPerWorkTick();
        // 三个条件与旧 serverTick 的 canOperate && anyValid && 能量够 一一对应，
        // 次序也照旧：红石先判（最便宜），再判有没有活干，最后才去看能量。
        boolean allowed = allowsWork() && hasWorkToDo() && hasEnergyFor(cost);
        if (allowed) {
            if (cost > 0) {
                // AutomationType.EXTERNAL 在这里不承担语义：MachineEnergyContainer.input
                // 把 canExtract 建成 alwaysTrue（实测其 input() 字节码偏移 20~22：
                // 第三个参数 notExternal 给 canInsert，第四个参数 alwaysTrue 给 canExtract），
                // 所以无论传 EXTERNAL / INTERNAL / MANUAL 都会被放行。
                // 真正被 canExtract 拦的是 internal(...) 建的容器（internalOnly 谓词），本类不用。
                energyContainer.extract(FloatingLong.create(cost), Action.EXECUTE, AutomationType.EXTERNAL);
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

    /** 进度条已走的 tick 数（GUI 用）。 */
    public int getWorkProgress() {
        return workProgress;
    }

    /** 完成一个批次需要的 tick 数（GUI 画进度条分母用）。 */
    public int getTicksPerWorkCycle() {
        return Math.max(1, ticksPerWorkCycle());
    }

    // ── 持久化 ──────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>只写执行器自有状态、进度条与格式版本：槽位、能量、侧配、升级、频率全部由
     * {@code TileEntityMekanism} 自己的 {@code saveAdditional} 写，重复写会互相覆盖。</p>
     */
    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        CompoundTag executorTag = new CompoundTag();
        executor().save(executorTag);
        tag.put(TAG_EXECUTOR, executorTag);
        tag.putInt(TAG_WORK_PROGRESS, workProgress);
        tag.putInt(TAG_NATIVE_VERSION, NATIVE_VERSION);
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
     */
    @Override
    public void load(CompoundTag tag) {
        CompoundTag legacyTag = MekCkLegacyMachineNbt.isLegacy(tag) ? tag : null;
        if (legacyTag != null) {
            // 档位为 null 时按「无机器槽位」处理：正常情况下构造器早就抛了
            // IllegalStateException，真到这里说明方块被换成了非工厂方块。
            // 此时宁可让旧内容被逐条记日志丢掉，也不要在区块加载路径上抛 NPE。
            CuttingMachineFactoryTier tier = getTier();
            tag = MekCkLegacyMachineNbt.migrate(legacyTag, getDirection(), tier == null ? 0 : tier.processes);
        }
        super.load(tag);
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
