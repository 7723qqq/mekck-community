package cn.ism.mekck.blockentity;

import cn.ism.mekck.compat.WineAgeCompat;
import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.menu.WineCellarMenu;
import cn.ism.mekck.menu.slot.MekCkSlots;
import cn.ism.mekck.menu.slot.SlotDef;
import cn.ism.mekck.util.PowerSlotUtil;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.FloatingLong;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableFloat;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.interfaces.ISustainedData;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 陈化窖（时间悖论产生器，F20）方块实体：独立<b>容器</b>方块，非配方加工机。
 *
 * <h3>为什么继承 {@code TileEntityConfigurableMachine} 而不是本模组的 {@code MekCkMachineTile}</h3>
 * 本模组有两条机器基类：
 * <ul>
 *   <li>{@code MekCkMachineTile}（工厂家族）—— 在 Mek 基类之上追加了<b>配方执行器</b>
 *       （{@code createExecutor()} 是抽象方法）与输入/输出方阵。适合「按配方加工」的机器；</li>
 *   <li>{@code TileEntityConfigurableMachine}（Mek 原生层）—— 只有侧配与弹出，<b>没有配方概念</b>。</li>
 * </ul>
 * 陈化窖不匹配任何配方：它按时间推进、按瓶数与倍速耗电，只有「9 个存储格 + 1 个电源槽」。
 * 继承 {@code MekCkMachineTile} 就必须实现一个用不上的执行器——那正是本次重写要消灭的
 * 「为迁就基类而写的凑合代码」。所以直接落在 Mek 原生层，与 Mek 自己的容器型机器同级。
 *
 * <h3>本次从旧体系迁来的四件事</h3>
 * <ol>
 *   <li><b>存储</b>：{@code ItemStackHandler} → Mek {@link IInventorySlot}（见 {@link #getInitialInventory}）；</li>
 *   <li><b>能量</b>：Forge {@code EnergyStorage} → {@link MachineEnergyContainer}；</li>
 *   <li><b>同步</b>：原版 {@code ContainerData}（每个值 writeShort，4 亿容量要拆高/低 16 位）
 *       → {@link #addContainerTrackers} 的 Mek 同步通道（{@code SyncableInt}/{@code SyncableFloat}，
 *       走 varint 与自定义包，<b>不再有 16 位截断</b>，旧 {@code WideDataSlot} 那套拆位随之作废）；</li>
 *   <li><b>存档</b>：保留旧键名（见 {@link #load}），旧存档的酒与进度不丢。</li>
 * </ol>
 *
 * <h3>不变量（与迁移前逐条对应，任何一条丢掉都是行为回退）</h3>
 * <ul>
 *   <li>9 个存储格是<b>普通 64 堆叠</b>，刻意不接本仓的极端堆叠——不同年份靠 NBT tag 区分，
 *       若能并格就会把不同年份的酒合并；</li>
 *   <li>电源槽只收能量物品/红石（{@code PowerSlotUtil.isValidEnergyItem}）；</li>
 *   <li>倍速 S∈[1,50]；耗电 {@code 62.5 × 瓶数 × S}（定点 {@code 625L*count*S/10} 避免浮点误差）；</li>
 *   <li>供能不足按 {@code rate = 实抽/应抽} <b>降速</b>而非停摆；完全没电则<b>全局暂停且不清进度</b>；</li>
 *   <li>进度按<b>游戏日</b>推进，攒满 {@value #DAYS_PER_WINE_YEAR} 日 = 1 酒年，才对每格推 {@code Year}；</li>
 *   <li>已封顶的格（{@link WineAgeCompat#isAgedOut}）<b>既不计时也不耗电</b>，绝不白烧；</li>
 *   <li>能量对外<b>只充不抽</b>（{@code maxExtract = 0}），仅本机内部可扣。</li>
 * </ul>
 */
public final class WineCellarBlockEntity extends TileEntityConfigurableMachine
        implements MenuProvider, ISustainedData {

    public static final int SLOT_COUNT = 9;
    /** 电源槽（能量物品/红石充能），下标 = 9 个存储格之后。 */
    public static final int SLOT_POWER = 9;
    public static final int TOTAL_SLOTS = 10;

    /** 一圈 = 1 游戏日（vinery 1 日 = 24,000 tick；@1× = 24,000t，除倍速即得各档）。 */
    private static final long TICKS_PER_WINE_DAY_BASE = 24_000L;
    /** vinery 日历：1 游戏年 = 24 游戏日（WineYears.DAYS_PER_YEAR，jar 字节码实锤）。 */
    private static final int DAYS_PER_WINE_YEAR = 24;
    /** 每满 24 日（=1 酒年）前推的年数。 */
    private static final int YEARS_PER_CYCLE = 1;

    /**
     * 能量容量。
     *
     * <p>{@code FloatingLong} 而非 {@code int}：Mek 的能量单位就是 {@code FloatingLong}
     * （{@code FloatingLong.create(long)} 支持到 {@code long} 量级）。
     * 迁到 Mek 体系后不再受原版 {@code ContainerData.writeShort} 的 16 位限制，
     * 因此旧实现里「能量拆成 {@code DATA_ENERGY} + {@code DATA_ENERGY_HI} 两槽」的补偿
     * 已无必要——那是为绕开截断而存在的，不是设计。</p>
     */
    public static final long ENERGY_CAPACITY = 400_000_000L;

    /**
     * 外部管线/电源槽物品单侧最大输入速率。
     *
     * <p>§F47：200k→1M——旧值恰等于满架@50×最大耗电（{@code 62.5×64×50 = 200,000} FE/t），
     * Forge {@code receiveEnergy} 按此 clamp ⇒ 创造能量立方/线缆供能与耗电阻尼成零和拉扯、
     * 表现为「带不动」（瓶颈是自家 clamp，非 Mekanism）。取 5× 余量。</p>
     */
    public static final long MAX_RECEIVE = 1_000_000L;

    // ── 运行状态 ────────────────────────────────────────────────────────

    /** 玩家选定倍速 1..50（默认 = 最低 = 1）。 */
    private int speedSetting = 1;
    /** §F46：全局统一陈化进度（单位 tick，浮点以支持降速打折）——跑满 ticksPerDay = +1 日。 */
    private float globalProgress;
    /** §F47：本年内已累计的陈化日 0..23，攒满 24 日 = 推 Year−1。 */
    private int agedDays;
    /** 本刻正在陈化的格数（服务端算，经同步通道给客户端画闪电弧）。 */
    private int activeCount;

    // ── 存档键 ──────────────────────────────────────────────────────────
    //
    // 三个键名**逐字沿用迁移前的旧键**：旧存档能直接读回，战利品表的 copy_nbt 也按它们搬运。
    // 常量化的理由与 Mek 自己一样——键名在 saveAdditional / load / 战利品表三处出现，
    // 写错任何一处都是静默丢状态。

    /** 倍速设置（int，1..50）。 */
    private static final String SPEED_KEY = "Speed";
    /** 全局陈化进度（float，单位 tick）。 */
    private static final String PROGRESS_KEY = "Progress";
    /** 年内已累计的陈化日（int，0..23）。 */
    private static final String AGED_DAYS_KEY = "AgedDays";

    /** 存储格（下标 0..8）—— 构造期由 {@link #getInitialInventory} 填充。 */
    private List<IInventorySlot> storageSlots;
    /** 电源槽（下标 9）。 */
    private IInventorySlot powerSlot;
    /** 能量容器。 */
    private MachineEnergyContainer<WineCellarBlockEntity> energyContainer;

    public WineCellarBlockEntity(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
        // 侧配内容必须在这里、且只能在 super(...) 之后登记：
        // setupItemIOConfig 依赖 getInitialInventory 建好的槽位对象，
        // setupInputConfig 依赖 getInitialEnergyContainers 建好的能量容器。
        //
        // ⚠️ 少了这两句的<b>症状是静默的</b>：机器照常放置、GUI 照常打开、槽位照常显示，
        // 但 configComponent 的 itemConfig 里<b>一个 DataType 都没有登记</b> ——
        // 于是 ConfigInventorySlotHolder.getSlotInfo(side) 永远返回 null，
        // 「侧配面板里六个面全是灰的、漏斗/管道对任何一面都看不到本机」，
        // 而且没有任何报错。Mek 自己的工厂与 GrillBlockEntity 都在构造器体里做同一件事。
        configComponent.setupItemIOConfig(storageSlots, List.of(), powerSlot, false);
        // 能量侧只进不出：setupInputConfig 会把该 ConfigInfo 的 setCanEject 置 false，
        // 与旧实现「外部管线只能充、不能抽」逐字一致。
        configComponent.setupInputConfig(TransmissionType.ENERGY, energyContainer);
        // 只给 ITEM 挂弹出：ENERGY 侧的 ConfigInfo 已被 setupInputConfig 置为 setCanEject(false)。
        ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM);
    }

    // ================== 基类钩子 ==================

    /**
     * 侧配与弹出的组件。
     *
     * <p>与 {@code MekCkMachineTile} 同款：本方法在父类构造器内被回调，
     * 只写父类字段、不碰本类字段（此刻本类字段初始化器还没跑）。</p>
     */
    @Override
    protected void presetVariables() {
        // 物品 + 能量两路侧配：本机没有气体/流体/矿浆。
        configComponent = new TileComponentConfig(this, TransmissionType.ITEM, TransmissionType.ENERGY);
        ejectorComponent = new TileComponentEjector(this);
    }

    /**
     * 能量容器。
     *
     * <p><b>容量与输入速率不在这里设</b>：{@code MachineEnergyContainer.input} 从方块的
     * {@code AttributeEnergy} 读（实测内部走 {@code validateBlock(tile).getStorage()/getUsage()}），
     * 所以两者只能在 {@code WineCellarBlock.blockTypeFor} 里声明。</p>
     *
     * <p><b>「对外只充不抽」怎么表达</b>：旧实现是自建 {@code CellarEnergy extends EnergyStorage}
     * 并把 {@code maxExtract} 设为 0，另开 {@code drainInternal} 给本机扣电。
     * Mek 的 {@code MachineEnergyContainer.input} 就是这个语义——
     * 它按 {@code AutomationType} 区分，外部（{@code EXTERNAL}）不能抽取，
     * 而本机用 {@code INTERNAL} 调 {@link IEnergyContainer#extract} 即可扣电，
     * 不需要再自建容器类。</p>
     */
    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSideWithConfig(this::getDirection, this::getConfig);
        energyContainer = MachineEnergyContainer.input(this, listener);
        builder.addContainer(energyContainer);
        return builder.build();
    }

    /**
     * 槽位：9 个存储格（行优先 3×3）+ 1 个电源槽。
     *
     * <p>坐标<b>全部取自 {@link MekCkSlots.WineCellar}</b>，不再在本类或菜单里写任何字面量——
     * 这是本次重写的核心契约：一个槽只有一个坐标出处。</p>
     *
     * <p><b>顺序是存档契约</b>：Mek 的 byte 下标存档按 {@code addSlot} 顺序编号，
     * 必须保持「存储 0..8 在前、电源槽最后」，与 {@link #SLOT_POWER} = 9 一致。
     * 调换顺序会让旧存档的槽位整体错位。</p>
     */
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this::getDirection, this::getConfig);
        storageSlots = new java.util.ArrayList<>(SLOT_COUNT);

        // 存储格：普通 64 堆叠（`obeyStackLimit = false` 的默认 limit 传 64 即等价），
        // 任何物品都能放（酒/非酒都收，是否陈化由 WineAgeCompat 判定）。
        for (int i = 0; i < SLOT_COUNT; i++) {
            SlotDef def = MekCkSlots.WineCellar.STORAGE.get(i);
            IInventorySlot slot = MekCkSlot.input(64, listener, def.x(), def.y());
            storageSlots.add(slot);
            builder.addSlot(slot);
        }

        // 电源槽：只收能量物品/红石 —— 用 inputFiltered 表达准入谓词
        // （旧 PowerSlot.mayPlace 的语义，见 MekCkSlot#inputFiltered 的说明）。
        SlotDef powerDef = MekCkSlots.WineCellar.POWER;
        powerSlot = MekCkSlot.inputFiltered(64,
                (stack, type) -> PowerSlotUtil.isValidEnergyItem(stack),
                listener, powerDef.x(), powerDef.y());
        builder.addSlot(powerSlot);

        return builder.build();
    }

    /**
     * 迁到 Mek 体系后自动获得升级能力。
     *
     * <p><b>为什么给全量</b>：旧实现「不支持升级槽」是因为自研体系里那是一个要逐项实现的功能；
     * 迁到 {@code TileEntityConfigurableMachine} 后，升级是 Mek 基类按方块属性自动开通的
     * （实测 {@code setSupportedTypes(Block)} 全程只读 {@code Attribute.has(block, X.class)}），
     * 刻意关掉反而要额外写代码。按「按 Mek 走」的口径，给 Mek 的标准集合。</p>
     */
    @Override
    public java.util.Set<mekanism.api.Upgrade> getSupportedUpgrade() {
        return java.util.EnumSet.of(
                mekanism.api.Upgrade.SPEED,
                mekanism.api.Upgrade.ENERGY);
    }

    // ================== 对外读数（菜单与屏幕读这些） ==================

    /** 设定倍速：钳制到 [1,50]，越界回退默认 1。 */
    public void setSpeed(int s) {
        int clamped = Math.max(1, Math.min(50, s));
        if (clamped != speedSetting) {
            speedSetting = clamped;
            setChanged();
        }
    }

    public long getEnergyStored() {
        return energyContainer == null ? 0L : energyContainer.getEnergy().longValue();
    }

    public static long getEnergyCapacity() {
        return ENERGY_CAPACITY;
    }

    /** 存量/容量的展示用整数（GUI 只画，不做算术）。 */
    public int getEnergyStoredInt() {
        return (int) Math.min(Integer.MAX_VALUE, getEnergyStored());
    }

    /**
     * 本刻正在陈化的格数（屏幕按它决定闪电弧的条数）。
     *
     * <p>按端分流：服务端 {@code activeCount} 是当 tick 算出的权威值，
     * 客户端那份恒为 0（它只在 {@code tickAging} 里被写，而那只在服务端跑）——
     * 直接返回它会让闪电弧在联机时永不出现。</p>
     */
    public int getActiveCount() {
        return clientMirroring() ? clientActiveCount : activeCount;
    }

    /** 某格陈化进度百分比 0..100；空格/非酒返回 -1。§F46：全局统一 ⇒ 在陈化格皆同一值。 */
    public int progressPercent(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT || storageSlots == null) return 0;
        ItemStack st = storageSlots.get(slot).getStack();
        if (st.isEmpty() || !WineAgeCompat.hasWineAge(st)) return -1;
        // 进度也要按端分流：客户端的 globalProgress 恒为 0（同上，只在服务端推进），
        // 不分流的话进度条在联机时永远空着。
        float progress = clientMirroring() ? clientProgress : globalProgress;
        long ticksPerDay = TICKS_PER_WINE_DAY_BASE / Math.max(1, getSpeed());
        return (int) Math.min(100L, (long) (progress * 100f / ticksPerDay));
    }

    /** 年内已累计的陈化日（按端分流，理由同 {@link #getActiveCount()}）。 */
    public int getAgedDaysForDisplay() {
        return clientMirroring() ? clientAgedDays : agedDays;
    }

    /** 电量是否不足以支撑本刻陈化（供 Mek 的能源警告 tab）。 */
    public boolean isNotEnoughEnergy() {
        return requiredEnergyPerTick() > getEnergyStored();
    }

    /**
     * 本刻应抽能耗 —— 客户端也用它估算显示，故为纯函数（不依赖服务端状态）。
     *
     * <p>与 {@link #tickAging} 同公式、同口径（只计可陈化且未封顶的格），
     * 定点 {@code 625L * count * S / 10} = {@code 62.5 × 数量 × 倍速}。</p>
     */
    public long requiredEnergyPerTick() {
        if (storageSlots == null) return 0L;
        int s = Math.max(1, Math.min(50, speedSetting));
        long need = 0L;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ItemStack st = storageSlots.get(slot).getStack();
            if (st.isEmpty() || !WineAgeCompat.hasWineAge(st)) continue;
            if (WineAgeCompat.isAgedOut(st, level)) continue;
            need += 625L * st.getCount() * s / 10;
        }
        return need;
    }

    // ================== tick ==================

    /**
     * 服务端 tick —— <b>走 Mek 的 tick 链，不自己写静态 ticker</b>。
     *
     * <h3>为什么必须走 {@code TileEntityMekanism.tickServer}</h3>
     * 注册成自己的静态方法（旧写法）会<b>整条绕过 Mek 的 tick 链</b>，
     * 而那条链里排在本方法<b>之前</b>的有：{@code frequencyComponent.tickServer()}、
     * {@code upgradeComponent.tickServer()}、chunkloader tick、以及
     * {@code Attribute.setActive(...) + Level.setBlockAndUpdate(...)} 那一段。
     * 绕过它们的症状是静默的：
     * <ul>
     *   <li>升级卡的<b>20 tick 安装读条永不推进</b> —— 卡放进升级槽后永远停在那儿不生效
     *       （读条逻辑就在 {@code TileComponentUpgrade.tickServer} 里）；</li>
     *   <li>方块的 {@code active} 属性永不更新，{@code AttributeStateActive} 形同虚设；</li>
     *   <li>比较器/红石读数与辐射、热容的每 tick 推进一并失效。</li>
     * </ul>
     * 所以注册处给的是 {@code TileEntityMekanism.tickServer}，本机的每 tick 逻辑放在
     * {@link #onUpdateServer()}（它被那条链在偏移 97 处调用）——与
     * {@code GrillBlockEntity} 等已迁机器逐字同款。
     */
    @Override
    protected void onUpdateServer() {
        super.onUpdateServer();
        // §F45：先由电源槽充能再陈化，同 tick 内能量即可用。
        if (drainPowerSlot()) setChanged();
        tickAging();
    }

    @Override
    protected void onUpdateClient() {
        super.onUpdateClient();
        // 客户端无逻辑：进度/能量/在陈化格数全部经 addContainerTrackers 的同步通道下发。
    }

    /**
     * 从电源槽物品抽能/烧红石入机（同急冻制冰机口径）。
     *
     * <p>迁移前走 {@code PowerSlotUtil.drain(ItemStack, EnergyStorage, int)}，
     * 而那个重载的第二个形参是 Forge 的 {@code EnergyStorage}——本机现在持有 Mek 的
     * {@link MachineEnergyContainer}，类型接不上。{@link PowerSlotUtil#drainTo} 是为这种
     * 情形新增的重载：它把机器侧抽象成「还能装多少」+「接收回调」，
     * 能量物品与红石两条分支的口径与 {@code drain} 逐位一致。</p>
     *
     * <p>插能用 {@code AutomationType.INTERNAL}：{@code MachineEnergyContainer.input}
     * 限制了<b>外部</b>（{@code EXTERNAL}）的插入速率与抽取，而「本机从自己的电源槽吃电」
     * 是内部行为，不走对外闸门——这也正是迁移前 {@code CellarEnergy} 自建容器所要表达的意思。</p>
     */
    public boolean drainPowerSlot() {
        if (powerSlot == null || energyContainer == null) return false;
        ItemStack powerStack = powerSlot.getStack();
        if (powerStack.isEmpty()) return false;
        long space = ENERGY_CAPACITY - getEnergyStored();
        if (space <= 0L) return false;
        return PowerSlotUtil.drainTo(powerStack, space, PowerSlotUtil.REDSTONE_PER_TICK,
                fe -> energyContainer.insert(FloatingLong.create(fe), Action.EXECUTE,
                        AutomationType.INTERNAL).longValue());
    }

    /**
     * §F47：全局统一计时——进度条每跑满一次 = 陈化 +1 游戏日；
     * 攒满 {@value #DAYS_PER_WINE_YEAR} 日（=1 酒年）才对参与陈化的每格酒推 {@code Year}。
     *
     * <p>耗电是全局 {@code Σ 62.5×数量×S}；供能不足按 {@code rate} 降速；
     * 封顶格剔除、不白烧；空闲早退。</p>
     */
    private void tickAging() {
        if (storageSlots == null) return;
        int s = Math.max(1, Math.min(50, speedSetting));
        long ticksPerDay = TICKS_PER_WINE_DAY_BASE / s;

        // 先收集可陈化格（有酒 + 未封顶）与应抽能耗
        java.util.List<Integer> eligible = new java.util.ArrayList<>();
        long need = 0L;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ItemStack st = storageSlots.get(slot).getStack();
            if (st.isEmpty() || !WineAgeCompat.hasWineAge(st)) continue;
            if (WineAgeCompat.isAgedOut(st, level)) continue; // 效果封顶（§F44）：不计时不耗能
            eligible.add(slot);
            need += 625L * st.getCount() * s / 10; // = 62.5 × 数量 × S，定点避免浮点误差
        }
        activeCount = 0;
        if (eligible.isEmpty() || need <= 0L) {
            return;
        }

        // 内部扣电：INTERNAL 自动化类型不受「对外只充」限制
        long avail = getEnergyStored();
        long drained = Math.min(need, avail);
        if (drained <= 0L) {
            return; // 完全没电：全局暂停（不清进度）
        }
        energyContainer.extract(FloatingLong.create(drained), Action.EXECUTE, AutomationType.INTERNAL);

        float rate = (float) drained / (float) need; // [0,1]，供能不足自动降速
        activeCount = eligible.size();
        boolean dirty = false;
        globalProgress += rate;
        while (globalProgress >= ticksPerDay && !eligible.isEmpty()) {
            globalProgress -= ticksPerDay; // 进度条跑满一次 = 1 个陈化日
            agedDays++;
            if (agedDays < DAYS_PER_WINE_YEAR) {
                continue; // 未满一年：只记日，不动 Year（vinery 粒度所限）
            }
            agedDays = 0; // 攒满 24 日 = 1 游戏年：统一推年
            for (java.util.Iterator<Integer> it = eligible.iterator(); it.hasNext(); ) {
                int slot = it.next();
                ItemStack aged = WineAgeCompat.accelerate(storageSlots.get(slot).getStack(), YEARS_PER_CYCLE);
                storageSlots.get(slot).setStack(aged);
                if (WineAgeCompat.isAgedOut(aged, level)) {
                    it.remove(); // 本轮推至封顶：后续轮次不再为其推年
                }
            }
            dirty = true;
        }
        if (dirty) setChanged();
    }

    // ================== 同步 ==================

    /**
     * 客户端要看的量走 Mek 的同步通道。
     *
     * <p>替换掉旧的原版 {@code ContainerData}：那条通道对每个值只 {@code writeShort}
     * （16 位有符号），4 亿容量必须拆成两个槽才不截断（旧代码的 `WideDataSlot` 就是干这个的）。
     * Mek 的 {@code SyncableInt}/{@code SyncableFloat} 走自己的包，没有该限制，
     * 所以「拆位」这套补偿连同它的 14 个下标常量一起作废。</p>
     */
    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        // 能量：FloatingLong，客户端画能量条与 tab 用
        container.track(mekanism.common.inventory.container.sync.SyncableFloatingLong.create(
                this::getEnergyLong, this::setClientEnergy));
        container.track(SyncableInt.create(this::getSpeed, this::setClientSpeed));
        // SyncableFloat 用的是 Mek 自己的 FloatSupplier/FloatConsumer（不是 JDK 的
        // Supplier<Float>/Consumer<Float>）—— 实测其 create 签名为
        // (FloatSupplier, FloatConsumer)。用 lambda 时由目标类型推断，故无需显式转换。
        container.track(mekanism.common.inventory.container.sync.SyncableFloat.create(
                () -> globalProgress, v -> clientProgress = v));
        container.track(SyncableInt.create(this::getAgedDays, this::setClientAgedDays));
        container.track(SyncableInt.create(this::getClientActiveCountSource, this::setClientActiveCount));
    }

    private FloatingLong clientEnergy = FloatingLong.ZERO;
    private int clientSpeed = 1;
    private float clientProgress;
    private int clientAgedDays;
    private int clientActiveCount;

    private FloatingLong getEnergyLong() {
        return energyContainer == null ? FloatingLong.ZERO : energyContainer.getEnergy();
    }

    private void setClientEnergy(FloatingLong v) {
        this.clientEnergy = v;
    }

    private void setClientSpeed(int v) {
        this.clientSpeed = v;
    }

    private void setClientAgedDays(int v) {
        this.clientAgedDays = v;
    }

    private void setClientActiveCount(int v) {
        this.clientActiveCount = v;
    }

    private int getAgedDays() {
        return agedDays;
    }

    private int getClientActiveCountSource() {
        return activeCount;
    }

    /** 客户端读能量（服务端读真实容器；两侧同一入口，菜单不必分端）。 */
    public long getEnergyForDisplay() {
        return level != null && level.isClientSide ? clientEnergy.longValue() : getEnergyStored();
    }

    /**
     * 此刻是否该读<b>客户端镜像</b>。
     *
     * <p>与 {@code MekCkMachineTile#clientMirroring} 同款判据，并且多考虑了
     * {@code level == null}（GUI 构造期可能早于 BE 绑定）：拿不到世界就按客户端处理，
     * 宁可显示默认值也不要在 GUI 构造期去碰权威状态。</p>
     */
    private boolean clientMirroring() {
        return level == null || level.isClientSide;
    }

    /**
     * 客户端读倍速镜像。
     *
     * <h3>为什么必须分流（这是一个真实的回归点）</h3>
     * {@code speedSetting} 只在服务端被改（{@link #setSpeed} 由网络包驱动）。
     * 客户端的 tile 上它是初始值 1，而 {@link #addContainerTrackers} 下发到的
     * {@code clientSpeed} 此前<b>没有任何读取方</b> —— 于是「镜像写进去了没人读」：
     * GUI 里的倍速输入框会恒显示 1×，且「实时能耗估算」也按 1× 算。
     * 迁移前这条由原版 {@code ContainerData} 兜住（它在客户端返回
     * {@code stored[index]}），换成 Mek 同步通道后必须自己分流，与
     * {@code MekCkMachineTile#getOrderQuantity()} 那批读数是同一条理由。</p>
     */
    public int getSpeed() {
        return clientMirroring() ? clientSpeed : speedSetting;
    }

    // ================== 存档 ==================

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    /**
     * 存档。<b>保留旧键名</b>，使旧存档可直接读回。
     *
     * <p>物品由 Mek 的槽体系写出（{@code ISustainedInventory}），
     * 不再走旧的 {@code ItemStackHandler.serializeNBT()}。为了<b>读</b>旧档，
     * {@link #load} 仍解析旧格式的 {@code "Items"} 复合标签。</p>
     */
    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(SPEED_KEY, speedSetting);
        tag.putFloat(PROGRESS_KEY, globalProgress); // §F46：单值全局进度（替旧逐格 Prog0..8）
        tag.putInt(AGED_DAYS_KEY, agedDays);        // §F47：年内已累计陈化日 0..23
    }

    /**
     * 读档 —— <b>同时兼容旧格式</b>。
     *
     * <p>旧档的形态（迁移前写出的）：</p>
     * <ul>
     *   <li>{@code "Items"} —— {@code ItemStackHandler.serializeNBT()} 的复合标签
     *       （内含 {@code Size} 与 {@code Items} 列表，列表项有 {@code Slot} 下标）。
     *       <b>迁移前是 9 槽，电源槽加上后变 10 槽</b>，旧档需要扩容迁移；</li>
     *   <li>{@code "Energy"} —— Forge 的 int 存量；</li>
     *   <li>{@code "Prog0".."Prog8"} —— 更早的逐格进度（float），取最大值迁移到全局进度。</li>
     * </ul>
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // 旧格式物品：Mek 的 super.load 只认它自己的槽格式，所以这里额外解析旧键。
        if (tag.contains("Items", Tag.TAG_COMPOUND)) {
            readLegacyItems(tag.getCompound("Items"));
        }
        // 旧格式能量：Mek 的 super.load 认它自己的键名，旧键 "Energy" 需要单独接。
        if (tag.contains("Energy", Tag.TAG_ANY_NUMERIC) && energyContainer != null) {
            long stored = Math.min(ENERGY_CAPACITY, tag.getInt("Energy"));
            if (stored > 0) {
                energyContainer.setEnergy(FloatingLong.create(stored));
            }
        }
        setSpeedInternal(tag.getInt(SPEED_KEY));
        // §F46：全局进度；旧档逐格 Prog0..8 取最大值迁移（多格同起时各格进度本就近似）
        if (tag.contains(PROGRESS_KEY)) {
            globalProgress = tag.getFloat(PROGRESS_KEY);
        } else {
            float max = 0f;
            for (int i = 0; i < SLOT_COUNT; i++) {
                if (tag.contains("Prog" + i)) max = Math.max(max, tag.getFloat("Prog" + i));
            }
            globalProgress = max;
        }
        // §F47：旧档（一圈=1 年）进度按新周期取模无缝衔接（多出的整圈视作刚推完年、清零）
        long ticksPerDay = TICKS_PER_WINE_DAY_BASE / Math.max(1, speedSetting);
        if (ticksPerDay > 0 && globalProgress > 0f) {
            globalProgress %= ticksPerDay;
        }
        agedDays = tag.getInt(AGED_DAYS_KEY);
    }

    /**
     * 读旧格式的 {@code ItemStackHandler} 标签并灌进 Mek 槽。
     *
     * <p>两种旧形态都要兼容：</p>
     * <ul>
     *   <li>{@code Size == 9}（加电源槽之前的存档）⇒ 逐项按下标放入存储格，电源槽留空；</li>
     *   <li>{@code Size == 10} ⇒ 0..8 进存储格、9 进电源槽。</li>
     * </ul>
     *
     * <p>迁移前的实现是「重写一份 10 槽的标签再 deserializeNBT」——
     * 那是被 {@code ItemStackHandler} 的 API 形状逼出来的（它只能整体反序列化）。
     * 现在槽是独立对象，逐格 {@code setStack} 即可，那层绕路不需要了。</p>
     */
    private void readLegacyItems(CompoundTag itemsTag) {
        if (storageSlots == null || powerSlot == null) {
            return;
        }
        ListTag list = itemsTag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot < 0 || slot >= TOTAL_SLOTS) continue;
            ItemStack stack = ItemStack.of(itemTag);
            if (stack.isEmpty()) continue;
            if (slot < SLOT_COUNT) {
                storageSlots.get(slot).setStack(stack);
            } else {
                powerSlot.setStack(stack); // slot == SLOT_POWER
            }
        }
    }

    /** load 期不设脏标志地应用倍速。 */
    private void setSpeedInternal(int s) {
        this.speedSetting = Math.max(1, Math.min(50, s == 0 ? 1 : s));
    }

    // ================== ISustainedData：掉落 → 再放置 ==================
    //
    // 迁到 Mek 的 BlockTile 之后，破坏方块走的是战利品表（BlockMekanism.onRemove 只做拆绑定块
    // 与 blockRemoved()，不会把方块实体序列化进掉落物），恢复走
    // BlockMekanism.setPlacedBy：它按名字读 componentUpgrade / componentConfig /
    // componentEjector /控制类型 / 各 SubstanceType 容器（含 Items 与 EnergyContainers），
    // 最后调 ISustainedData.readSustainedData(tag) 交还本机自有键。
    //
    // ⚠️ 键契约（与 data/mekck/loot_tables/blocks/wine_cellar.json 的 source 逐字对齐）：
    //   Items      (ListTag)  Mek 槽位，setPlacedBy 走 SubstanceType / persistInventory 读回
    //   EnergyContainers      Mek 能量，同上
    //   Speed      (int)      倍速设置      ← 下面两个方法
    //   Progress   (float)    全局陈化进度  ←
    //   AgedDays   (int)      年内已累计日  ←
    // 战利品表少写一条 = 挖掉再放下时那一项静默归零（copy_nbt 对不存在的 source 是跳过、不报错）。

    /**
     * 把本机自有键写进「可复制的配置数据」。
     *
     * <p><b>为什么这里是空的</b>：本钩子<b>不是</b>「只在挖掉时调用」——它同时被
     * {@code TileEntityMekanism.getConfigurationData(Player)}（配置卡复制）与
     * {@code BlockMekanism.getCloneItemStack}（中键取方块）调用。只要这里写了
     * {@code Items} 这类含内容的键，配置卡就会连带复制整机库存——从一台满机器复制一张卡、
     * 再往空机器上反复粘贴，就是一次干净的物品复制漏洞。
     * 「挖掉再放下」不需要本方法出力：战利品表的 {@code copy_nbt} 直接把 BE 存档键
     * 拷进 {@code mekData.*}，读侧 {@link #readSustainedData} 照读即可。</p>
     */
    @Override
    public void writeSustainedData(CompoundTag tag) {
    }

    /**
     * 掉落物再放置时，把本机自有键读回来。
     *
     * <h3>为什么槽位也要在这里读（{@code Items}）</h3>
     * {@code BlockMekanism.setPlacedBy} 恢复槽位有<b>两条</b>分支：
     * <ol>
     *   <li>{@code SubstanceType} 那条只管流体/气体/能量等容器，
     *       {@code Items} <b>不在</b>其中（枚举里没有 ITEM）；</li>
     *   <li>另一条要求方块物品实现 {@code IItemSustainedInventory}
     *       （Mek 自己的 {@code ItemBlockMachine} 就实现了），它才调
     *       {@code setSustainedInventory} 去读 {@code mekData.Items}。</li>
     * </ol>
     * 本模组的方块物品是 {@code MekCkBlockItem}，<b>没有</b>实现该接口 ——
     * 所以第 2 条分支恒被跳过，「挖起来再放下」时九格酒会<b>全部蒸发</b>。
     * 这就是这里必须自己读一遍 {@code Items} 的理由（与 {@code GrillBlockEntity} 同款）。
     *
     * <p><b>先判有没有本机的键再动手</b>：配置卡粘贴时走
     * {@code setConfigurationData → loadGeneralPersistentData → 本方法}，
     * 那份载荷里不会有 {@code Items}。此时若无条件执行读容器，就会把目标机器的库存
     * 按一张<b>空表</b>清掉——那是一个本不该有的副作用。</p>
     */
    @Override
    public void readSustainedData(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        boolean hasOwnKeys = tag.contains(SPEED_KEY, Tag.TAG_INT)
                || tag.contains(PROGRESS_KEY, Tag.TAG_FLOAT)
                || tag.contains(AGED_DAYS_KEY, Tag.TAG_INT);
        boolean hasItems = tag.contains("Items", Tag.TAG_LIST);
        if (!hasOwnKeys && !hasItems) {
            return;
        }
        if (hasItems) {
            // 槽位小于 128，不走工厂那套 int 下标的 MekCkSlots 兜底。
            mekanism.api.DataHandlerUtils.readContainers(getInventorySlots(null),
                    tag.getList("Items", Tag.TAG_LIST));
        }
        if (hasOwnKeys) {
            setSpeedInternal(tag.getInt(SPEED_KEY));
            if (tag.contains(PROGRESS_KEY, Tag.TAG_FLOAT)) {
                globalProgress = tag.getFloat(PROGRESS_KEY);
            }
            if (tag.contains(AGED_DAYS_KEY, Tag.TAG_INT)) {
                agedDays = tag.getInt(AGED_DAYS_KEY);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mek 10.4.6 里没有任何外部消费方（全 jar 常量池扫描 + 逐类 {@code javap -p -c}
     * 复核：按接口调用或虚调用的外部消费方为 0，仅有的 4 处是 QIO 继承链上的
     * {@code super.getTileDataRemap()}）。本模组的键名两侧同名
     * （战利品表 {@code copy_nbt} 的 target 就是 {@code mekData.<同名键>}），
     * 因此照接口返回空表。</p>
     */
    @Override
    public java.util.Map<String, String> getTileDataRemap() {
        return java.util.Map.of();
    }

    // ================== MenuProvider ==================

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.mekck.wine_cellar");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new WineCellarMenu(containerId, inventory, this);
    }

    @NotNull
    @Override
    public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
            @NotNull net.minecraftforge.common.capabilities.Capability<T> capability,
            @org.jetbrains.annotations.Nullable net.minecraft.core.Direction side) {
        return super.getCapability(capability, side);
    }
}
