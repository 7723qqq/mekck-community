package cn.ism.mekck.factory;

import mekanism.api.IContentsListener;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * MekCK 工厂机器基类 —— 走 Mekanism 的机器体系（{@link TileEntityConfigurableMachine}）。
 *
 * <h3>为什么继承 Mek 的基类</h3>
 * 旧实现是自研 {@code BlockEntity}，只借用 Mek 的 GUI 绘制类，因此侧栏 tab 与 Mek 自己的
 * tab（能量等）分属两套布局体系，运行时要靠 {@code avoidEnergyTabY} 互相避让，导致重叠。
 * 继承 {@code TileEntityConfigurableMachine} 后：
 * <ul>
 *   <li>{@code ISideConfiguration} 由父类实现（{@code configComponent}/{@code ejectorComponent}）；</li>
 *   <li>GUI 侧 {@code GuiConfigurableTile.super.addGuiElements()} 会由 Mek 自己排出
 *       侧配(6) / 传输配置(34) / 升级(6,右) / 红石(137,右)，无需手抄坐标；</li>
 *   <li>能量/升级/安全等接口由 {@code TileEntityMekanism} 提供默认实现。</li>
 * </ul>
 *
 * <h3>与 Mekanism Extras 的对应</h3>
 * 本类对应 Extras 的 {@code TileEntityExtraFactory}：同样直接继承
 * {@code TileEntityConfigurableMachine}（<b>平行于</b> Mek 的 {@code TileEntityFactory}，
 * 而非继承它），并使用自建的 {@link MekCkFactoryTier} 等级枚举。
 *
 * <h3>槽位布局</h3>
 * 并行数由等级决定：输入槽 N 个 + 输出槽 N 个（N = {@link MekCkFactoryTier#processes}）。
 * 输入/输出各排成方阵（列数 = ⌈√N⌉），与旧实现的排布口径一致。
 * 另有 1 个能量槽（{@code EnergyInventorySlot}）。
 *
 * <p><b>配方机制暂未接入</b>：本类当前只建立 Mek 体系的骨架（配置/能量/槽位），
 * 配方查找留待后续步骤。因此机器可开、可见、tab 对齐，但不会生产。</p>
 */
public abstract class TileEntityMekCkFactory extends TileEntityConfigurableMachine {

    /** 输入槽方阵起始坐标（GUI 相对）。 */
    private static final int INPUT_START_X = 38;
    private static final int INPUT_START_Y = 41;
    /** 输入方阵与输出方阵之间的水平间隔（与旧实现一致）。 */
    private static final int GRID_GAP = 30;
    /** 槽位间距。 */
    private static final int SLOT_STEP = 18;

    /** 本机等级。构造期可能尚未赋值（见 {@link #tierFromBlock()}），读取请走 {@link #getTier()}。 */
    protected MekCkFactoryTier tier;
    /** 工艺类型。同上，读取请走 {@link #getFactoryType()}。 */
    protected cn.ism.mekck.factory.MekCkFactoryType factoryType;

    /**
     * 本机等级/工艺类型 —— <b>从方块读取，不用实例字段</b>。
     *
     * <p>关键：Mek 的 {@code TileEntityMekanism} 构造器<b>内部会调用</b>
     * {@code getInitialInventory()}（实测 {@code TileEntityMekanism.java:277}），
     * 而那一刻发生在 {@code super(...)} 返回<b>之前</b> —— 本类的实例字段
     * （{@code tier}/{@code factoryType}）尚未赋值，读到的是 null。
     * 实机崩溃即此：{@code NPE: Cannot read field "processes" because "this.tier" is null}。</p>
     *
     * <p>{@code blockProvider} 是父类的 {@code protected final} 字段、在父类构造器早期就赋好，
     * 因此这里通过它反查方块上的等级，绕开字段初始化时序问题。</p>
     */
    protected MekCkFactoryTier tierFromBlock() {
        if (blockProvider != null && blockProvider.getBlock() instanceof MekCkFactoryBlock b) {
            return b.getFactoryTier();
        }
        return null;
    }

    /** 工艺类型：与 {@link #tierFromBlock()} 同理，从方块读取避免构造期 null。 */
    protected cn.ism.mekck.factory.MekCkFactoryType typeFromBlock() {
        if (blockProvider != null && blockProvider.getBlock() instanceof MekCkFactoryBlock b) {
            return b.getFactoryType();
        }
        return null;
    }

    protected MachineEnergyContainer<TileEntityMekCkFactory> energyContainer;
    protected EnergyInventorySlot energySlot;

    /**
     * 输入/输出槽（供配方实现按索引取用）。
     *
     * <p><b>不能写成 {@code = new ArrayList<>()} 字段初始化器</b>：字段初始化器在
     * {@code super(...)} 返回<b>之后</b>才执行，而父类 {@code TileEntityMekanism} 构造器
     * 内部会回调 {@link #getInitialInventory} —— 那时这两个列表还是 null。
     * 实机崩溃实录：{@code NPE: Cannot invoke "java.util.List.add(Object)" because "target" is null}。</p>
     *
     * <p>故改为在 {@link #getInitialInventory} 里按需创建，并在 getter 中兜底。</p>
     */
    protected List<IInventorySlot> inputSlots;
    protected List<IInventorySlot> outputSlots;

    /** 输入槽列表（可能为 null 表示尚未建好，调用方自行判空）。 */
    public List<IInventorySlot> getInputSlots() {
        return inputSlots;
    }

    /** 输出槽列表（可能为 null 表示尚未建好，调用方自行判空）。 */
    public List<IInventorySlot> getOutputSlots() {
        return outputSlots;
    }

    protected TileEntityMekCkFactory(IBlockProvider blockProvider, BlockPos pos, BlockState state,
                                     MekCkFactoryTier tier, cn.ism.mekck.factory.MekCkFactoryType factoryType) {
        super(blockProvider, pos, state);
        this.tier = tier;
        this.factoryType = factoryType;
    }

    public MekCkFactoryTier getTier() {
        // 构造期字段可能仍为 null（见 tierFromBlock 注释），回落到方块读取
        return tier != null ? tier : tierFromBlock();
    }

    public cn.ism.mekck.factory.MekCkFactoryType getFactoryType() {
        return factoryType != null ? factoryType : typeFromBlock();
    }

    /**
     * 初始化侧配组件 —— <b>必须实现</b>，否则放置时必崩。
     *
     * <p>实测崩溃：
     * {@code NPE: Cannot invoke "TileComponentConfig.read(CompoundTag)" because the return value of
     * "ISideConfiguration.getConfig()" is null at BlockMekanism.m_6402_(BlockMekanism.java:307)}。
     * 原因是 {@code TileEntityConfigurableMachine} 只声明了 {@code configComponent} 字段
     * （public、不赋值），初始化责任在子类；Mek 自己的 {@code TileEntityFactory} 就是在
     * 本方法里 {@code new TileComponentConfig(this, ...)} 的。</p>
     *
     * <p>本方法与 {@link #getInitialInventory} 一样在父类构造器内被回调
     * （{@code TileEntityMekanism} 构造器会调 {@code presetVariables()}），
     * 故同样不能依赖本类的字段初始化器 —— 只写入父类字段、并从方块读等级。</p>
     */
    @Override
    protected void presetVariables() {
        // 物品 + 能量两路侧配：本模组工厂只有物品槽与能量输入，没有气体/流体/矿浆
        configComponent = new mekanism.common.tile.component.TileComponentConfig(
                this, mekanism.common.lib.transmitter.TransmissionType.ITEM,
                mekanism.common.lib.transmitter.TransmissionType.ENERGY);
        // 弹出组件：让侧面配置里的「弹出」模式可用（与 Mek 工厂同口径）
        ejectorComponent = new mekanism.common.tile.component.TileComponentEjector(this);
    }

    /** 能量容器（供 GUI 显示容量/存量）。 */
    public MachineEnergyContainer<TileEntityMekCkFactory> getEnergyContainer() {
        return energyContainer;
    }

    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSideWithConfig(this::getDirection, this::getConfig);
        // 容量与能耗不由这里指定：MachineEnergyContainer.input 会从方块属性 AttributeEnergy
        // 读取（反编译实测：validateBlock(tile).getStorage()/getUsage()）。
        // 因此各等级的容量/能耗在方块注册时通过 AttributeEnergy 声明，见 MekCkFactoryBlocks。
        energyContainer = MachineEnergyContainer.input(this, listener);
        builder.addContainer(energyContainer);
        return builder.build();
    }

    /**
     * 装配输入/输出槽位方阵。
     *
     * <p><b>为什么直接覆写 {@code getInitialInventory} 而不是 Mek 的 {@code addSlots}</b>：
     * {@code addSlots} 是 {@code TileEntityFactory} 的模板方法，本类继承的是
     * {@code TileEntityConfigurableMachine}（平行于 {@code TileEntityFactory}，不是它的子类），
     * 父类链上根本没有这个方法，{@code @Override} 无法成立。
     * 因此这里按 Mek 自己的装配流程重写，只去掉本模组不需要的部分。</p>
     *
     * <p><b>构造期回调</b>：{@code TileEntityMekanism} 构造器在这一步之前已完成
     * {@code presetVariables()} 与 {@code getInitialEnergyContainers(listener)}
     * （实测调用序：presetVariables → getInitialEnergyContainers → getInitialInventory），
     * 故此处 {@code configComponent} 与 {@code energyContainer} 均已就绪。</p>
     */
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        // 本方法在父类构造器内被调用 → 本类的字段初始化器全部尚未执行，
        // 故列表在此按需创建（见 inputSlots 字段的注释）。
        inputSlots = new ArrayList<>();
        outputSlots = new ArrayList<>();

        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this::getDirection, this::getConfig);
        // 非能量槽的变更监听走 listener 本身（Mek 的 addSlots 口径与之一致）。
        addSlotGrid(builder, listener, INPUT_START_X, true);
        addSlotGrid(builder, listener, outputStartX(), false);
        // 能量槽：沿用 Mek 工厂的既定位置 (7, 13)，复用上面建好的 energyContainer。
        energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener, 7, 13);
        builder.addSlot(energySlot);

        org.slf4j.LoggerFactory.getLogger("mekck/factory").info(
                "[MekCkFactory] getInitialInventory: tier={} type={} 输入槽={} 输出槽={}",
                getTier(), getFactoryType(), inputSlots.size(), outputSlots.size());
        return builder.build();
    }

    /** 输出方阵起始 x：输入方阵宽度 + 间隔（与旧实现一致）。 */
    private int outputStartX() {
        int cols = (int) Math.ceil(Math.sqrt(getTier().processes));
        return INPUT_START_X + cols * SLOT_STEP + GRID_GAP;
    }

    /**
     * 排布输入/输出方阵与能量槽。
     *
     * <p>列数取 ⌈√N⌉，与旧实现的 {@code (int) Math.ceil(Math.sqrt(inputSlots))} 口径一致，
     * 使 3/5/7/9 等非平方数的并行数也能排成紧密方阵。</p>
     *
     * <p>并行数走 {@link #getTier()} 而非 {@code tier} 字段：本方法在父类构造器内被调用，
     * 那时字段还是 null（见 {@link #tierFromBlock()}）。</p>
     */
    private void addSlotGrid(InventorySlotHelper builder, IContentsListener listener, int startX, boolean input) {
        int count = getTier().processes;
        int cols = (int) Math.ceil(Math.sqrt(count));

        List<IInventorySlot> target = input ? inputSlots : outputSlots;
        for (int i = 0; i < count; i++) {
            int col = i % cols;
            int row = i / cols;
            int x = startX + col * SLOT_STEP;
            int y = INPUT_START_Y + row * SLOT_STEP;
            IInventorySlot slot = input
                    ? InputInventorySlot.at(listener, x, y)
                    : OutputInventorySlot.at(listener, x, y);
            target.add(slot);
            builder.addSlot(slot);
        }
    }
}
