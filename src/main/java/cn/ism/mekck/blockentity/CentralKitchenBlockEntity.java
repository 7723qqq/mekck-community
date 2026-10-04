package cn.ism.mekck.blockentity;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

/**
 * 中央厨房（终极机器）：MekCK 的产能中枢。
 *
 * <p>结构：20 个机器模块槽（在升级界面内以模块形式安装）+ 300 格多类型存储区（带滑条滚动、
 * 搜索与排序）+ 输出存储区；双独立温度（发热侧供产热系列、制冷侧供制冰系列，
 * 冷端在 {@code FACING} 侧、热端在其反向，与急冻制冰机一致——
 * 注意面配置面板把 {@code FACING} 侧标为「背面」，所以**面板里的「背面」才是冷端**）。</p>
 *
 * <p>能力完全由已安装的机器模块决定：模块提供 x 个线程 × 每线程 y 并行，
 * 且线程**专用**于该模块对应的配方类型，不跨类型借用。</p>
 */
public class CentralKitchenBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity
        implements MenuProvider, cn.ism.mekck.ae2.INetworkPullable {

    /** 材料兜底掉落的告警出口：玩家读不到，只为服务端排查「守恒被打破」。 */
    private static final org.slf4j.Logger KITCHEN_LOG = org.slf4j.LoggerFactory.getLogger("mekck.centralkitchen");

    /** 机器模块槽数量（预留扩展空间）。 */
    public static final int MODULE_SLOTS = 20;
    /** 存储区格数。 */
    public static final int STORAGE_SLOTS = 300;
    /** 输出区格数。 */
    public static final int OUTPUT_SLOTS = 30;
    /** 超大堆叠上限（与模组其余机器一致）。 */
    public static final int BIG_STACK = Integer.MAX_VALUE - 1;

    public static final int MODULE_START = 0;
    public static final int STORAGE_START = MODULE_START + MODULE_SLOTS;
    public static final int OUTPUT_START = STORAGE_START + STORAGE_SLOTS;
    /** 全量槽位（含末尾的三明治样品槽）。 */
    public static final int TOTAL_SLOTS = OUTPUT_START + OUTPUT_SLOTS + 1;

    /** 双独立温度：发热侧（产热系列）与制冷侧（制冰系列）。 */
    private final cn.ism.mekck.util.MekCkHeatComponent heatComponent;
    private final cn.ism.mekck.util.MekCkHeatComponent coldComponent;
    private final LazyOptional<mekanism.api.heat.IHeatHandler> heatCapability;
    private final LazyOptional<mekanism.api.heat.IHeatHandler> coldCapability;

    /** 一个加工线程的状态：空闲或正在执行一个配方。 */
    public static final class KitchenThread {
        public net.minecraft.resources.ResourceLocation recipeId;
        public java.util.List<int[]> consumes;
        public java.util.List<net.minecraft.world.item.ItemStack> outputs;
        public int progress;
        public int totalTime;

        public boolean busy() {
            return recipeId != null;
        }

        public double progressRatio() {
            return totalTime <= 0 ? 0.0 : Math.min(1.0, (double) progress / totalTime);
        }
    }

    /**
     * 订单数上限。
     *
     * <p>此前 {@code orders} <b>没有任何上限</b>：{@code placeOrder} 由玩家包下发（受
     * {@code PacketGuard.expensiveRequest} 5 tick 节流），但 AE2 那条路径
     * （{@code MekckAe2} 的 {@code pushPattern → startOrder}）<b>不受玩家节流限制</b> ——
     * 一个 ME 终端持续派发合成任务就能让订单列表无界增长。每个 {@code KitchenOrder}
     * 带一个 18 槽 {@code BigStackItemHandler} 加步骤列表，且全部随存档序列化，
     * 于是内存与存档体积一起涨。</p>
     *
     * <p>取 64：正常玩法（含自动加工）远达不到；到顶只是拒绝新订单并给出明确原因，
     * 已排队的订单照常跑完，不会丢正在加工的产物。</p>
     */
    public static final int MAX_ORDERS = 64;

    // ── 停滞订单的提示文案（常量，按需解析一次）────────────────────────────
    //
    // 这四条原本每 tick 对每个停滞订单都 Component.translatable(...).getString() 一次：
    // 该调用要查语言表并做格式化，不是免费操作，而结果每次都完全相同。
    // 厨房里积压几十个 PAUSED 订单时，就是每 tick 几十次翻译 + 字符串拼接。
    // 语言表在客户端切换语言后才变，故用 lazy holder 解析一次即可（服务端启动时
    // 语言尚未就绪，不能放在静态初始化里）。
    private static volatile String noteWaitingIntermediate;
    private static volatile String noteMissingFluid;
    private static volatile String noteWaitingThread;
    private static volatile String noteNoPower;
    private static volatile String noteBufferFull;

    private static String noteWaitingIntermediate() {
        String v = noteWaitingIntermediate;
        if (v == null) {
            v = Component.translatable("gui.mekck.kitchen.note.waiting_intermediate").getString();
            noteWaitingIntermediate = v;
        }
        return v;
    }

    private static String noteMissingFluid() {
        String v = noteMissingFluid;
        if (v == null) {
            v = Component.translatable("gui.mekck.kitchen.note.missing_fluid").getString();
            noteMissingFluid = v;
        }
        return v;
    }

    private static String noteWaitingThread() {
        String v = noteWaitingThread;
        if (v == null) {
            v = Component.translatable("gui.mekck.kitchen.note.waiting_thread").getString();
            noteWaitingThread = v;
        }
        return v;
    }

    private static String noteNoPower() {
        String v = noteNoPower;
        if (v == null) {
            v = Component.translatable("gui.mekck.kitchen.note.no_power").getString();
            noteNoPower = v;
        }
        return v;
    }

    private static String noteBufferFull() {
        String v = noteBufferFull;
        if (v == null) {
            v = Component.translatable("gui.mekck.kitchen.note.buffer_full").getString();
            noteBufferFull = v;
        }
        return v;
    }

    /** 订单列表（每订单独立暂存区，不跨订单共享中间产物）。 */
    private final java.util.List<cn.ism.mekck.kitchen.KitchenOrder> orders = new java.util.ArrayList<>();
    private int nextOrderId = 1;

    /**
     * 读档暂存的订单 NBT。
     *
     * <p>Forge 在 {@code setLevel} 之前调 {@code load}，读档阶段 {@code getLevel()} 为 null，
     * 而订单步骤重建需要配方管理器 —— 所以 load 只暂存 NBT，等 {@code onLoad}（level 已就绪）
     * 再重建。原实现直接在 load 里取 {@code getLevel()}，于是每次读档都因 level 为 null
     * 而把全部订单当「重建失败」丢弃。</p>
     */
    private net.minecraft.nbt.ListTag pendingOrdersTag;

    /** 每系列的「自动加工」开关（方案 C，默认关闭）。 */
    private final java.util.Map<cn.ism.mekck.kitchen.KitchenFamily, Boolean> autoMode =
            new java.util.EnumMap<>(cn.ism.mekck.kitchen.KitchenFamily.class);

    /** 每个系列一组线程（大小 = 模块提供的线程数）。 */
    private final java.util.Map<cn.ism.mekck.kitchen.KitchenFamily, java.util.List<KitchenThread>> threads =
            new java.util.EnumMap<>(cn.ism.mekck.kitchen.KitchenFamily.class);

    /** 能源容量与接收上限。 */
    public static final int ENERGY_CAPACITY = 5_000_000;
    public static final int MAX_RECEIVE = 200_000;
    /** 单线程每 tick 基础能耗（FE）；工厂线程按同值计。 */
    public static final int ENERGY_PER_THREAD = 20;
    /** 单个配方批次的基础加工时间（刻）；实际耗时按线程数 × 并行数折算。 */
    public static final int STEP_TIME_PER_CRAFT = 200;

    // ================== 流体 / 气体 ==================
    /** 独立流体罐数量（水 / 奶 / 油 / 其它）。 */
    public static final int FLUID_TANK_COUNT = 4;
    /** 每罐容量（mb）。 */
    public static final int FLUID_CAPACITY = 1_000_000;
    /** 营养液气体罐容量（mB）。 */
    public static final long GAS_CAPACITY = 1_000_000;

    /** 多流体罐（与厨锅同一套 MultiFluidHandler）。 */
    public final cn.ism.mekck.util.MultiFluidHandler fluidTank =
            new cn.ism.mekck.util.MultiFluidHandler(FLUID_TANK_COUNT, FLUID_CAPACITY, this::setChanged);
    private final LazyOptional<net.minecraftforge.fluids.capability.IFluidHandler> fluidCapability =
            LazyOptional.of(() -> fluidTank);

    /** 营养液气体罐（供种植切配系列）。 */
    private final mekanism.api.chemical.gas.IGasTank gasTank;
    private final LazyOptional<mekanism.api.chemical.gas.IGasHandler> gasCapability;
    /** 营养液气体（mekmm:nutrient_solution；未安装 mekmm 时为 null）。 */
    private final mekanism.api.chemical.gas.Gas nutrientGas;

    // ================== 侧面配置（物品 / 流体 / 气体） ==================
    // 包级可见（原 private）：侧配读写搬到了同包的 {@link CentralKitchenSideConfig}，
    // 三个数组仍是本机的存档字段（saveAdditional/load 编解码、getCapability/tickFluidIO 直读），
    // 故数组不搬，仅放宽可见性供伴生类按下标读写。
    final cn.ism.mekck.SideMode[] itemSideConfig = new cn.ism.mekck.SideMode[6];
    final cn.ism.mekck.SideMode[] fluidSideConfig = new cn.ism.mekck.SideMode[6];
    final cn.ism.mekck.SideMode[] gasSideConfig = new cn.ism.mekck.SideMode[6];
    /** 流体自动 IO 每次转移量（mb）。 */
    /**
     * 流体自动 IO 每面每 tick 的转移量（mB）。
     * 改为跟随 {@link cn.ism.mekck.util.LagMonitor#getMaxFluidPerDirection()}：一次 fill/drain 的开销
     * 与转移量无关，原先固定 1000 mB 会让 16~64 桶的罐体需要几十 tick 才能灌满/排空。
     */
    private int fluidIoRate() {
        return cn.ism.mekck.util.LagMonitor.getMaxFluidPerDirection();
    }

    // 流体邻居能力缓存（与物品 AutoIO 同口径：BE 实例 + TTL，避免每 tick getBlockEntity + getCapability）
    private final net.minecraft.world.level.block.entity.BlockEntity[] fluidAdjBE =
            new net.minecraft.world.level.block.entity.BlockEntity[6];
    private final net.minecraftforge.fluids.capability.IFluidHandler[] fluidAdjHandler =
            new net.minecraftforge.fluids.capability.IFluidHandler[6];
    private final long[] fluidAdjTick = new long[6];
    /** 气体自动 IO（抽取）。 */
    private final cn.ism.mekck.util.AutoGasIO gasAutoIO;
    /** 物品自动 IO（抽取至存储区 / 弹出产物）。 */
    private final cn.ism.mekck.util.AutoIO itemAutoIO;

    // ── 拆分出的子系统（均不持有自己的状态，构造见 {@link #CentralKitchenBlockEntity}）──
    /** 定向热交换（见 {@link CentralKitchenHeatTransfer}）。 */
    private final CentralKitchenHeatTransfer heatTransfer;
    /** 存储浏览器增量同步的触发端（见 {@link CentralKitchenStorageSync}）。 */
    private final CentralKitchenStorageSync storageSync;
    /** AE2 网络拉料规格（见 {@link CentralKitchenNetworkPull}）。 */
    private final CentralKitchenNetworkPull networkPull;
    /** 物品 / 流体 / 气体侧配读写（见 {@link CentralKitchenSideConfig}）。 */
    private final CentralKitchenSideConfig sideConfig;

    private final net.minecraftforge.energy.EnergyStorage energy =
            new net.minecraftforge.energy.EnergyStorage(ENERGY_CAPACITY, MAX_RECEIVE, MAX_RECEIVE) {
                @Override
                public int receiveEnergy(int maxReceive, boolean simulate) {
                    int received = super.receiveEnergy(maxReceive, simulate);
                    if (!simulate && received > 0) setChanged();
                    return received;
                }

                @Override
                public int extractEnergy(int maxExtract, boolean simulate) {
                    int extracted = super.extractEnergy(maxExtract, simulate);
                    if (!simulate && extracted > 0) setChanged();
                    return extracted;
                }
            };
    private final LazyOptional<net.minecraftforge.energy.IEnergyStorage> energyCapability =
            LazyOptional.of(() -> energy);

    /** 三明治样品槽（安装「三明治组装机」模块后用于定义要量产的三明治）。 */
    public static final int SANDWICH_SAMPLE_SLOT = OUTPUT_START + OUTPUT_SLOTS;

    /** 全量物品：模块槽 + 存储区 + 输出区。 */
    public final ItemStackHandler items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
        @Override
        public int getSlotLimit(int slot) {
            return slot >= MODULE_START && slot < STORAGE_START ? 1 : BIG_STACK;
        }

        @Override
        protected void onContentsChanged(int slot) {
            // 模块槽变化会让"已安装模块列表"失效（该列表原先每 tick 被构造多次）
            if (slot >= MODULE_START && slot < STORAGE_START) moduleVersion++;
            // 存储区/输出区变化要让存储浏览器重推一页（修 I-N4）。
            // 用内容版本号而不是直接每 tick 推：AutoIO 可能在开界面期间每 tick 都在
            // 搬东西，无条件推就是每 tick 54 个 ItemStack 的带宽。
            if (slot >= STORAGE_START) storageVersion++;
            setChanged();
        }
    };

    /** 模块槽内容版本号：只增不减，用于给 installedAbilities() 的结果做缓存失效。 */
    private int moduleVersion;
    /**
     * 存储区（含输出区、样品槽）内容版本号：只增不减，<b>不落盘</b>。
     *
     * <p>给 {@code CentralKitchenMenu#pushStorageSync} 判断「这一页要不要重发」用。
     * 刻意不进 NBT——它只是个进程内的推送去重计数，落盘没有意义，
     * 反而会让人以为它参与了存档兼容。</p>
     */
    private int storageVersion;

    /** 存储区内容版本号（见 {@link #storageVersion}）。 */
    public int storageVersion() {
        return storageVersion;
    }
    private java.util.List<cn.ism.mekck.kitchen.KitchenModule.Ability> abilitiesCache;
    private int abilitiesCacheVersion = -1;

    private final LazyOptional<IItemHandler> itemCapability = LazyOptional.of(() -> items);

    public CentralKitchenBlockEntity(BlockPos pos, BlockState state) {
        super(MekCkStandaloneMachines.CENTRAL_KITCHEN_BLOCK_ENTITY.get(), pos, state);
        this.heatComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
        this.coldComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
        this.heatCapability = LazyOptional.of(heatComponent::getHandler);
        this.coldCapability = LazyOptional.of(coldComponent::getHandler);
        // 营养液气体罐：仅接受 mekmm 营养液
        this.nutrientGas = resolveNutrientGas();
        this.gasTank = mekanism.api.chemical.ChemicalTankBuilder.GAS.create(GAS_CAPACITY,
                gas -> nutrientGas != null && nutrientGas == gas, this::setChanged);
        this.gasCapability = LazyOptional.of(() -> new GasHandlerSingle());
        for (int i = 0; i < 6; i++) {
            itemSideConfig[i] = cn.ism.mekck.SideMode.NONE;
            fluidSideConfig[i] = cn.ism.mekck.SideMode.NONE;
            gasSideConfig[i] = cn.ism.mekck.SideMode.NONE;
        }
        this.gasAutoIO = new cn.ism.mekck.util.AutoGasIO(gasTank);
        this.itemAutoIO = new cn.ism.mekck.util.AutoIO(this,
                new int[][]{{STORAGE_START, STORAGE_SLOTS}},
                new int[][]{{OUTPUT_START, OUTPUT_SLOTS}});
        this.heatTransfer = new CentralKitchenHeatTransfer(this);
        this.storageSync = new CentralKitchenStorageSync(this);
        this.networkPull = new CentralKitchenNetworkPull(this);
        this.sideConfig = new CentralKitchenSideConfig(this);
    }

    // ================== 温度 ==================

    /** 发热侧温度（开尔文）。 */
    public double getHeatTemperature() {
        return heatComponent.getTemperature();
    }

    /** 制冷侧温度（开尔文）。 */
    public double getColdTemperature() {
        return coldComponent.getTemperature();
    }

    /** 供能类型：产热系列的模块运行时调用。 */
    public void addHeatFromEnergy(int energyUsed) {
        heatComponent.addHeatFromEnergy(energyUsed);
    }

    /** 供能类型：制冷系列的模块运行时调用。 */
    public void addColdFromEnergy(int energyUsed, double efficiency) {
        coldComponent.handleHeat(-energyUsed * efficiency);
    }

    // ================== 生命周期 ==================

    public static void serverTick(Level level, BlockPos pos, BlockState state, CentralKitchenBlockEntity kitchen) {
        // 双温度：各自自然回归环境 + 与相邻热力设备传导
        kitchen.heatComponent.tick(level, pos);
        kitchen.coldComponent.tick(level, pos);
        // 定向热交换：正面冷端吸热、背面热端放热（与急冻制冰机一致）
        kitchen.heatTransfer.applyDirectedHeat(level, pos, state);
        // 侧面配置驱动的自动输入输出（物品 / 流体 / 气体）
        if (kitchen.itemAutoIO.run(level, pos, kitchen.itemSideConfig, kitchen.items)) kitchen.setChanged();
        if (kitchen.tickFluidIO(level, pos)) kitchen.setChanged();
        if (kitchen.gasAutoIO.run(level, pos, kitchen.gasSideConfig)) kitchen.setChanged();
        // 订单驱动加工（默认模式）
        kitchen.tickOrders(level, pos, state);
        // 自动模式（方案 C，默认关闭；仅对打开开关的系列生效，按模块槽顺序消耗材料）
        kitchen.tickAutoMode(level, pos, state);
        // 存储浏览器增量同步（修 I-N4）：存储区在开界面期间被 AutoIO / 订单 / AE2
        // 改动时，把新的一页推给正在看这个界面的玩家。
        // 版本号没变时 pushStorageSync 立即返回，节流再兜住 AutoIO 每 tick 改动的情形。
        kitchen.storageSync.syncOpenStorageBrowsers();
    }

    // ================== 订单系统（阶段 4） ==================

    /**
     * 下单：求解合成链；成功则**立即从存储区把叶子材料移入订单暂存区**（预留，避免被抢用）。
     *
     * @return 失败原因；成功返回 null
     */
    public String placeOrder(Level level, net.minecraft.resources.ResourceLocation recipeId, int count) {
        if (level == null) return "世界未加载";
        var abilities = installedAbilities();
        if (abilities.isEmpty()) return "尚未安装任何机器模块";
        net.minecraft.world.item.crafting.Recipe<?> recipe =
                level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) return "找不到配方：" + recipeId;
        java.util.Map<net.minecraft.world.item.Item, Integer> snapshot =
                cn.ism.mekck.kitchen.KitchenCraftingPlan.snapshotStorage(items, STORAGE_START, OUTPUT_START);
        cn.ism.mekck.kitchen.KitchenCraftingPlan.Result plan =
                cn.ism.mekck.kitchen.KitchenCraftingPlan.solve(level, abilities, snapshot, recipe,
                        Math.max(1, count), 3);
        if (!plan.ok()) return plan.failure;
        if (plan.steps.isEmpty()) return "该配方无需加工";
        if (orders.size() >= MAX_ORDERS) {
            // 到顶即拒绝，不挤掉已有订单（已排队的正在加工，丢弃会丢产物）。
            return "订单队列已满（上限 " + MAX_ORDERS + "）：请等待现有订单完成";
        }

        var order = new cn.ism.mekck.kitchen.KitchenOrder(nextOrderId++, plan.steps);
        // 预留叶子材料：从存储区移入订单暂存区
        String reserveErr = reserveLeaves(plan, order);
        if (reserveErr != null) {
            refundBuffer(order);
            return reserveErr;
        }
        order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.RUNNING);
        orders.add(order);
        setChanged();
        return null;
    }

    /** 预览订单：只求解合成链，不消耗任何材料，返回可读文本。 */
    public String previewOrder(Level level, net.minecraft.resources.ResourceLocation recipeId, int count) {
        if (level == null) return "世界未加载";
        var abilities = installedAbilities();
        if (abilities.isEmpty()) return "尚未安装任何机器模块";
        net.minecraft.world.item.crafting.Recipe<?> recipe =
                level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) return "找不到配方：" + recipeId;
        java.util.Map<net.minecraft.world.item.Item, Integer> snapshot =
                cn.ism.mekck.kitchen.KitchenCraftingPlan.snapshotStorage(items, STORAGE_START, OUTPUT_START);
        cn.ism.mekck.kitchen.KitchenCraftingPlan.Result plan =
                cn.ism.mekck.kitchen.KitchenCraftingPlan.solve(level, abilities, snapshot, recipe, count, 3);
        if (!plan.ok()) return "不可行：" + plan.failure;
        StringBuilder sb = new StringBuilder("可执行，共 ").append(plan.steps.size()).append(" 步：");
        for (int i = 0; i < plan.steps.size(); i++) {
            var step = plan.steps.get(i);
            sb.append("\n ").append(i + 1).append(". [").append(step.family.id).append("] ");
            for (var in : step.inputs) {
                sb.append(in.getHoverName().getString()).append('×').append(in.getCount()).append(' ');
            }
            sb.append("→ ").append(step.output.getHoverName().getString())
              .append('×').append(cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT, step.output.getCount(), Math.max(1, step.batches)));
            for (var extra : step.extraOutputs) {
                if (extra.isEmpty()) continue;
                sb.append(" + ").append(extra.getHoverName().getString())
                  .append('×').append(cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT, extra.getCount(), Math.max(1, step.batches)));
            }
        }
        return sb.toString();
    }

    /** 把订单任务链所需的叶子材料从存储区预留到订单暂存区。 */
    private String reserveLeaves(cn.ism.mekck.kitchen.KitchenCraftingPlan.Result plan,
                                 cn.ism.mekck.kitchen.KitchenOrder order) {
        // 统计每步需要、且不是由前序步骤产出的材料
        java.util.Map<net.minecraft.world.item.Item, Integer> produced = new java.util.HashMap<>();
        java.util.Map<net.minecraft.world.item.Item, Integer> needed = new java.util.LinkedHashMap<>();
        for (var step : plan.steps) {
            for (var in : step.inputs) {
                int already = produced.getOrDefault(in.getItem(), 0);
                int shortage = Math.max(0, in.getCount() - already);
                if (shortage > 0) {
                    needed.merge(in.getItem(), shortage, Integer::sum);
                    produced.put(in.getItem(), 0);
                } else {
                    produced.put(in.getItem(), already - in.getCount());
                }
            }
            produced.merge(step.output.getItem(),
                    cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT,
                            step.output.getCount(), Math.max(1, step.batches)),
                    cn.ism.mekck.util.CountMath::addClamp);
        }
        boolean bufferFull = false;
        for (var e : needed.entrySet()) {
            int remaining = e.getValue();
            for (int slot = STORAGE_START; slot < OUTPUT_START && remaining > 0; slot++) {
                var stack = items.getStackInSlot(slot);
                if (stack.isEmpty() || stack.getItem() != e.getKey()) continue;
                int take = Math.min(stack.getCount(), remaining);
                var taken = items.extractItem(slot, take, false);
                int leftover = insertIntoBuffer(order, taken);
                remaining -= (taken.getCount() - leftover);
                if (leftover > 0) {
                    // 为什么：leftover 已经从存储区抽走了，既不回插也不掉落就等于凭空销毁玩家材料。
                    // 暂存区满时同一物品的后续槽也不可能再装下，故直接结束该材料的预留。
                    var spill = taken.copyWithCount(leftover);
                    var back = insertIntoStorage(spill);
                    if (!back.isEmpty()) {
                        dropReservedOverflow(back);
                    }
                    bufferFull = true;
                    break;
                }
            }
            if (remaining > 0) {
                String name = new net.minecraft.world.item.ItemStack(e.getKey()).getHoverName().getString();
                return bufferFull
                        ? "订单暂存区已满：" + name + " ×" + remaining
                        : "存储区缺少材料：" + name + " ×" + remaining;
            }
        }
        return null;
    }

    /** 放入订单暂存区，返回未放入的数量。 */
    /**
     * 把物品放进订单暂存区，<b>返回未能放入的数量</b>。
     *
     * <h3>⚠️ 归并必须逐格夹紧（第三轮修，同一 bug 的第三条路径）</h3>
     * 原实现是 {@code existing.grow(remainder.getCount()); remainder.setCount(0);}——
     * {@code ItemStack.grow(n)} 就是 {@code setCount(getCount() + n)}，而 1.20.1 的
     * {@code ItemStack.setCount} <b>不做任何夹紧</b>；buffer 的槽上限是
     * {@code Integer.MAX_VALUE - 1}，两个大堆叠一合并就<b>必然溢出为负</b>。
     * 负数 ⇒ {@code isEmpty()} 为真 ⇒ 那一格被当成空格；落盘时
     * {@code BigStackItemHandler.readStack} 的 {@code if (count <= 0) return EMPTY;}
     * 再确认一次删除 ⇒ <b>既有那堆和新产物一起消失</b>。
     *
     * <p>为什么这次才暴露：前两轮分别修了 {@code KitchenRecipeMatcher.insertOutputs} 与
     * {@code CentralKitchenBlockEntity.insertIntoStorage}，<b>唯独漏了这一条</b>。
     * 三条路径处理的是同一件事（把一批同物合并进容器），修法也必须一致：
     * 逐格算剩余空间、只搬得动的量、剩余量留在参数里继续往下找。</p>
     */
    private int insertIntoBuffer(cn.ism.mekck.kitchen.KitchenOrder order, net.minecraft.world.item.ItemStack stack) {
        var remainder = stack.copy();
        for (int i = 0; i < order.buffer.getSlots() && !remainder.isEmpty(); i++) {
            var existing = order.buffer.getStackInSlot(i);
            if (!existing.isEmpty() && net.minecraft.world.item.ItemStack.isSameItemSameTags(existing, remainder)) {
                int space = Math.min(order.buffer.getSlotLimit(i), Integer.MAX_VALUE) - existing.getCount();
                if (space <= 0) {
                    continue;
                }
                int moved = Math.min(space, remainder.getCount());
                existing.grow(moved);
                order.buffer.setStackInSlot(i, existing);
                remainder.shrink(moved);
            }
        }
        for (int i = 0; i < order.buffer.getSlots() && !remainder.isEmpty(); i++) {
            if (order.buffer.getStackInSlot(i).isEmpty()) {
                order.buffer.setStackInSlot(i, remainder.copy());
                remainder.setCount(0);
            }
        }
        return remainder.getCount();
    }

    /**
     * 本步完成前的预检：扣掉本步输入后，暂存区能否装下本步全部产物。
     *
     * <p>用副本模拟，不改动真实暂存区。装不下时调用方暂停本步（不推进、不扣料、不产出）——
     * 否则产物会被 {@link #insertIntoBuffer} 丢弃，或改投存储区后下一步的
     * {@code bufferHas} 永远为假，订单卡死。</p>
     */
    private boolean bufferCanHoldStepOutputs(cn.ism.mekck.kitchen.KitchenOrder order,
                                             cn.ism.mekck.kitchen.KitchenCraftingPlan.Step step) {
        var sim = new cn.ism.mekck.util.BigStackItemHandler(cn.ism.mekck.kitchen.KitchenOrder.BUFFER_SLOTS) {
            @Override
            public int getSlotLimit(int slot) {
                return Integer.MAX_VALUE - 1;
            }
        };
        sim.deserializeNBT(order.buffer.serializeNBT());
        // 与 consumeFromBuffer 同口径：按物品类型扣除本步输入
        for (var need : step.inputs) {
            int remaining = need.getCount();
            for (int i = 0; i < sim.getSlots() && remaining > 0; i++) {
                var stack = sim.getStackInSlot(i);
                if (stack.isEmpty() || stack.getItem() != need.getItem()) continue;
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
            }
        }
        // 与完成分支同口径：产物按批次数放大后试插
        int mult = Math.max(1, step.batches);
        var outputs = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        var produced = step.output.copy();
        produced.setCount(cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT,
                produced.getCount(), mult));
        outputs.add(produced);
        for (var extra : step.extraOutputs) {
            if (extra.isEmpty()) continue;
            var extraCopy = extra.copy();
            extraCopy.setCount(cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT,
                    extraCopy.getCount(), mult));
            outputs.add(extraCopy);
        }
        return cn.ism.mekck.kitchen.KitchenRecipeMatcher.insertOutputs(sim, 0, sim.getSlots(), outputs, 1).isEmpty();
    }

    /**
     * 暂存区放不下的产物余量：先回插存储区，仍放不下则掉落 + 告警。
     *
     * <p>完成前预检（{@link #bufferCanHoldStepOutputs}）已保证正常路径下余量为 0；
     * 这里是守恒不变量（产物 = 进暂存区 + 回存储 + 掉落 + 缺口，缺口必须为 0）的兜底。</p>
     */
    private void spillBufferOverflow(net.minecraft.world.item.ItemStack produced, int leftover) {
        if (leftover <= 0) return;
        var spill = produced.copyWithCount(leftover);
        var back = insertIntoStorage(spill);
        if (!back.isEmpty()) {
            dropReservedOverflow(back);
        }
    }

    /**
     * 订单材料回插存储区后仍放不下的最后兜底：掉落到世界并告警。
     *
     * <p>为什么不变量要求这样做：这些材料在上一步已经被 {@code extractItem} 从存储区抽走，
     * 「进 buffer + 回存储 + 掉落 + 缺口」必须等于抽出的量，缺口必须为 0。
     * 既不回插也不掉落就等于凭空销毁玩家材料，且不会有任何可见症状。</p>
     */
    private void dropReservedOverflow(net.minecraft.world.item.ItemStack stack) {
        Level level = getLevel();
        if (level != null) {
            cn.ism.mekck.util.BigStackDrops.dropAbove(level, getBlockPos(), stack);
        }
        KITCHEN_LOG.warn("[mekck] 中央厨房订单预留材料既装不进暂存区也退不回存储区，已掉落到世界：{} ×{} @{}",
                stack.getHoverName().getString(), stack.getCount(), getBlockPos());
    }

    /** 把订单暂存区里的东西退回存储区（下单失败时回滚）。 */
    private void refundBuffer(cn.ism.mekck.kitchen.KitchenOrder order) {
        for (int i = 0; i < order.buffer.getSlots(); i++) {
            var stack = order.buffer.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            var back = insertIntoStorage(stack.copy());
            if (!back.isEmpty()) {
                // 存储区满：退回的物品不能静默消失，走掉落 + 告警兜底
                dropReservedOverflow(back);
            }
            order.buffer.setStackInSlot(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    /** 订单驱动执行：按任务链顺序，用对应系列的线程加工。 */
    private void tickOrders(Level level, BlockPos pos, BlockState state) {
        if (orders.isEmpty()) return;
        // 本 tick 各系列已被占用的线程数：订单与自动加工共用同一套系列线程，
        // 同一系列的订单数超过线程数时排队等待（先到先得）。
        java.util.Map<cn.ism.mekck.kitchen.KitchenFamily, Integer> busyThreads = new java.util.HashMap<>();
        var iter = orders.iterator();
        while (iter.hasNext()) {
            var order = iter.next();
            if (order.finished()) {
                // ⚠️ 只有**全部交付完**才移除订单。deliverOrder 此前是 void，
                // 它把装不下的 leftover 放回 order.buffer 就返回，而这里紧接着
                // iter.remove() —— 那个 order 对象自此再无任何引用（saveAdditional
                // 只遍历 orders），buffer 里的成品与订单预留的剩余叶子材料随对象一起
                // 被 GC，连存档都不记录。输出区被填满时这是必现的静默物品删除。
                if (deliverOrder(order)) {
                    iter.remove();
                }
                setChanged();
                continue;
            }
            var step = order.currentStep();
            if (step == null) continue;
            var ability = abilityOf(step.family);
            if (ability == null) {
                order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.PAUSED);
                order.setNote(Component.translatable("gui.mekck.kitchen.note.module_removed", step.family.id).getString());
                continue;
            }
            // 暂存区是否已备齐该步骤材料
            if (!bufferHas(order, step.inputs)) {
                order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.PAUSED);
                order.setNote(noteWaitingIntermediate());
                continue;
            }
            // 流体校验（步骤若需要水 / 奶，必须由流体罐提供）
            //
            // 这里只做校验、绝不扣流体：下方 tickStep() 在加工完成前每 tick 都返回 false
            // 并 continue 回本段，若在此处扣除，一个 200 tick 的步骤会扣掉 200 份流体
            // （等待与排队期间同样照扣），罐子会在瞬间见底且扣掉的水一去不回。
            // 实际扣除统一放在「本步骤完成」分支（下方），与扣料同一时机。
            if (step.fluidNeed != null && !step.fluidNeed.isEmpty()) {
                boolean enough = fluidTank.hasEnoughOf(true, step.fluidNeed.waterMb)
                        && fluidTank.hasEnoughOf(false, step.fluidNeed.milkMb);
                if (!enough) {
                    order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.PAUSED);
                    order.setNote(noteMissingFluid());
                    continue;
                }
            }
            // ===== 线程占用：同一系列同时推进的订单数不超过线程数 =====
            int used = busyThreads.getOrDefault(step.family, 0);
            int threads = Math.max(1, ability.threads());
            if (used >= threads) {
                order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.PENDING);
                order.setNote(noteWaitingThread());
                continue;
            }

            // ===== 加工时间：批次数 × 单次耗时 ÷（线程数 × 并行数） =====
            if (order.stepTotalTime() <= 0) {
                long work = (long) Math.max(1, step.batches) * STEP_TIME_PER_CRAFT;
                long throughput = (long) threads * Math.max(1, ability.parallel());
                long total = Math.max(1, (work + throughput - 1) / throughput);
                order.setStepTotalTime((int) Math.min(Integer.MAX_VALUE - 1, total));
            }

            // ===== 完成前预检：本步产物必须装得下暂存区 =====
            // 装不下时若照常完成，产物会被 insertIntoBuffer 丢弃（或改投存储区后下一步
            // 永远等不到），订单也会卡死在下一步。这里在推进前先模拟一次「扣输入 + 插产物」，
            // 装不下就暂停本步：不扣料、不产出、不推进，等暂存区有空间再继续。
            if (order.stepProgress() + 1 >= order.stepTotalTime() && !bufferCanHoldStepOutputs(order, step)) {
                order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.PAUSED);
                order.setNote(noteBufferFull());
                continue;
            }

            // 线程占用放在预检之后：被暂存区挡住的订单不该占着系列线程名额
            busyThreads.put(step.family, used + 1);

            // ===== 耗电：每 tick 按系列能耗扣电，电量不足则暂停 =====
            int need = energyPerTickFor(step.family);
            if (energy.getEnergyStored() < need) {
                order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.PAUSED);
                order.setNote(noteNoPower());
                continue;
            }
            energy.extractEnergy(need, false);
            if (step.family.heatProducing) {
                heatComponent.addHeatFromEnergy(need);
            }

            order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.RUNNING);
            order.setNote("");
            if (!order.tickStep()) {
                setChanged();
                continue;
            }

            // ===== 本步骤完成：扣料 + 出产物 =====
            if (step.fluidNeed != null && !step.fluidNeed.isEmpty()) {
                cn.ism.mekck.kitchen.KitchenRecipeMatcher.consumeFluid(fluidTank, step.fluidNeed);
            }
            consumeFromBuffer(order, step.inputs);
            int mult = Math.max(1, step.batches);
            var produced = step.output.copy();
            produced.setCount(cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT, produced.getCount(), mult));
            spillBufferOverflow(produced, insertIntoBuffer(order, produced));
            // 副产物（多产物配方）：一并写入暂存区，避免丢失
            for (var extra : step.extraOutputs) {
                if (extra.isEmpty()) continue;
                var extraCopy = extra.copy();
                extraCopy.setCount(cn.ism.mekck.util.CountMath.mulClamp(cn.ism.mekck.util.CountMath.MAX_COUNT, extraCopy.getCount(), mult));
                spillBufferOverflow(extraCopy, insertIntoBuffer(order, extraCopy));
            }
            order.advanceStep();
            setChanged();
        }
    }

    /** 暂存区是否备齐指定材料。 */
    private boolean bufferHas(cn.ism.mekck.kitchen.KitchenOrder order, java.util.List<net.minecraft.world.item.ItemStack> inputs) {
        for (var need : inputs) {
            int have = 0;
            for (int i = 0; i < order.buffer.getSlots(); i++) {
                var stack = order.buffer.getStackInSlot(i);
                if (!stack.isEmpty() && stack.getItem() == need.getItem()) have += stack.getCount();
            }
            if (have < need.getCount()) return false;
        }
        return true;
    }

    /** 从暂存区扣除材料。 */
    private void consumeFromBuffer(cn.ism.mekck.kitchen.KitchenOrder order, java.util.List<net.minecraft.world.item.ItemStack> inputs) {
        for (var need : inputs) {
            int remaining = need.getCount();
            for (int i = 0; i < order.buffer.getSlots() && remaining > 0; i++) {
                var stack = order.buffer.getStackInSlot(i);
                if (stack.isEmpty() || stack.getItem() != need.getItem()) continue;
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
            }
        }
    }

    /**
     * 订单完成：把最终产物从暂存区移入输出区。
     *
     * <h3>⚠️ 交付不完整时**不能**让订单消失（第三轮修）</h3>
     * 原实现在每一格交付后把 leftover <b>写回 {@code order.buffer}</b> 就返回（void），
     * 而调用方 {@code tickOrders} 紧接着 {@code iter.remove()}。此后：
     * <ul>
     *   <li>再没有任何字段引用 {@code order}（{@code saveAdditional} 只遍历 {@code orders}）；</li>
     *   <li>于是 buffer 里的成品<b>连同订单预留的、没被链吃掉的全部叶子材料</b>
     *       随对象一起被 GC，<b>连存档都不会记录</b>，玩家无法找回。</li>
     * </ul>
     * 触发门槛极低：把 30 个输出槽填满后下任意一单，等它走完最后一步 ⇒
     * 30 次 {@code insertOutputs} 全部返回 leftover ⇒ 全塞回 buffer ⇒ 全丢。
     * 另一条更隐蔽的：产物种类数 ≥ 30 的订单。
     *
     * <p>修法与同文件 {@code advanceThread} 的做法<b>刻意对称</b> —— 那条路径早就
     * 「装不下就保留线程、下 tick 重试」，并写了注释说明为什么不能丢；订单路径缺了同一处理。
     * {@link cn.ism.mekck.kitchen.KitchenOrder#finished()} 只看 {@code stepIndex} 不看
     * {@code state}，所以置 DONE 之后每 tick 会自动重试，不会死循环。</p>
     *
     * @return true = 已全部交付，订单可以移除
     */
    private boolean deliverOrder(cn.ism.mekck.kitchen.KitchenOrder order) {
        boolean complete = true;
        for (int i = 0; i < order.buffer.getSlots(); i++) {
            var stack = order.buffer.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            var remainder = cn.ism.mekck.kitchen.KitchenRecipeMatcher.insertOutputs(items,
                    OUTPUT_START, OUTPUT_START + OUTPUT_SLOTS, java.util.List.of(stack), 1);
            if (remainder.isEmpty()) {
                order.buffer.setStackInSlot(i, net.minecraft.world.item.ItemStack.EMPTY);
            } else {
                // 放不下：留在暂存区，等下次 tick 重试（见方法注释）。
                order.buffer.setStackInSlot(i, remainder.get(0));
                complete = false;
            }
        }
        order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.DONE);
        if (!complete) {
            setChanged();
        }
        return complete;
    }

    // ================== 系列过滤器 ==================

    /** 每个系列的处理能力过滤器（只影响自动加工模式）。 */
    private final java.util.EnumMap<cn.ism.mekck.kitchen.KitchenFamily,
            cn.ism.mekck.kitchen.KitchenFilter> filters =
            new java.util.EnumMap<>(cn.ism.mekck.kitchen.KitchenFamily.class);

    /** 取某系列的过滤器（不存在时创建）。 */
    public cn.ism.mekck.kitchen.KitchenFilter filterOf(cn.ism.mekck.kitchen.KitchenFamily family) {
        return filters.computeIfAbsent(family, f -> new cn.ism.mekck.kitchen.KitchenFilter());
    }

    /** 把全部系列的过滤器同步给指定玩家（打开窗口时、以及每次修改后调用）。 */
    public void sendFilterSync(net.minecraft.server.level.ServerPlayer player) {
        if (player == null) return;
        for (cn.ism.mekck.kitchen.KitchenFamily family : cn.ism.mekck.kitchen.KitchenFamily.values()) {
            var filter = filterOf(family);
            cn.ism.mekck.network.ModMessages.sendToPlayer(
                    new cn.ism.mekck.network.KitchenFilterSyncPacket(
                            getBlockPos(), family.ordinal(), filter.mode().ordinal(),
                            new java.util.ArrayList<>(filter.items()), isAutoMode(family)),
                    player);
        }
    }

    /** 自动模式（方案 C）：仅对打开开关的系列生效。 */
    private void tickAutoMode(Level level, BlockPos pos, BlockState state) {
        for (var ability : installedAbilities()) {
            if (!isAutoMode(ability.family())) continue;
            var list = threads.computeIfAbsent(ability.family(), f -> new java.util.ArrayList<>());
            int capacity = Math.max(1, ability.threads());
            while (list.size() < capacity) list.add(new KitchenThread());
            for (int i = 0; i < list.size(); i++) {
                KitchenThread t = list.get(i);
                if (t.busy()) {
                    // 在跑的**一律推进到完成**，包括超编的那些。
                    // 读档缩容时不能删 busy 线程（材料已扣），所以列表可能暂时长于
                    // 模块提供的容量；让它们跑完是唯一不丢材料的做法。
                    advanceThread(level, pos, ability, t);
                } else if (i < capacity) {
                    startThread(level, ability, t);
                }
                // i >= capacity 且空闲 = 缩容留下的余量：**不开工**。
                // 否则换个低等级模块反而凭空多出线程（每个余量线程都会去
                // startThread 抢料），而模块明明只提供了 capacity 条。
                // 它晾着等下一次 load 的缩容回收。
            }
        }
        // 方块激活态：任一线程在跑即为激活。这段原先写在 tickThreads 里，而该方法全仓
        // 没有任何调用者（serverTick 只调 tickOrders + 本方法）⇒ ACTIVE 永不更新，
        // 模型永远停在未激活态。现挂在「本 tick 最后推进线程」的一步之后；
        // 订单驱动不占线程（走 order.buffer），故只看线程表。
        // 刻意不迁移旧 tickThreads 里的缩容逻辑（while (size > ability.threads()) remove）：
        // 在 tick 路径上缩容会把**正在加工**的线程（材料已扣、产物未出）直接删掉 ⇒ 材料凭空损失。
        // 槽位长度只在 load 时按已安装模块对齐（那时没有在跑的线程）。
        boolean anyRunning = false;
        for (var list : threads.values()) {
            for (KitchenThread t : list) {
                if (t.busy()) {
                    anyRunning = true;
                    break;
                }
            }
            if (anyRunning) break;
        }
        if (state.hasProperty(cn.ism.mekck.block.CentralKitchenBlock.ACTIVE)
                && state.getValue(cn.ism.mekck.block.CentralKitchenBlock.ACTIVE) != anyRunning) {
            level.setBlock(pos, state.setValue(cn.ism.mekck.block.CentralKitchenBlock.ACTIVE, anyRunning), 3);
        }
    }

    public boolean isAutoMode(cn.ism.mekck.kitchen.KitchenFamily family) {
        return Boolean.TRUE.equals(autoMode.get(family));
    }

    /**
     * 某系列的模块被拆掉时，把该系列<b>正在加工</b>的线程原样退回共享存储区。
     *
     * <h3>为什么必须退款而不是丢弃</h3>
     * {@code startThread} 在<b>开工那一刻</b>就把材料从存储区扣走了
     * （{@code KitchenRecipeMatcher.consume(items, match.consumes())}），
     * 而产物要到 {@code advanceThread} 跑完才落进输出区。所以一条 busy 线程代表
     * 「一份已经从玩家手里拿走的材料 + 一份还没产出的东西」。模块一被拆掉，
     * 这条线程在本机就再无加工能力 —— 丢弃它等于<b>凭空销毁</b>玩家那份材料。
     *
     * <p>退款是逐槽精确退的：{@link KitchenThread#consumes} 记的就是当初扣料的
     * {@code [槽位, 数量]} 对，所以不需要猜「原来是什么」——
     * 槽里现在是什么就退什么，退不进去的部分由 {@link #insertIntoStorage} 交给调用方处理。</p>
     */
    private void refundOrphanedThreads(cn.ism.mekck.kitchen.KitchenFamily family) {
        var list = threads.remove(family);
        if (list == null || list.isEmpty()) {
            return;
        }
        int refunded = 0;
        for (KitchenThread t : list) {
            if (!t.busy() || t.consumes == null) {
                continue;
            }
            for (int[] pair : t.consumes) {
                // 槽位索引可能因换机器而失效（load 时已逐条校验过，这里再兜一次，
                // 因为退款发生在「模块被拆」这条与索引校验不同的路径上）。
                if (pair == null || pair.length < 2 || pair[0] < 0 || pair[0] >= items.getSlots()) {
                    continue;
                }
                var stack = items.getStackInSlot(pair[0]);
                if (stack.isEmpty()) {
                    continue;
                }
                int amount = Math.min(stack.getCount(), pair[1]);
                if (amount <= 0) {
                    continue;
                }
                var give = stack.copy();
                give.setCount(amount);
                stack.shrink(amount);
                if (stack.isEmpty()) {
                    items.setStackInSlot(pair[0], net.minecraft.world.item.ItemStack.EMPTY);
                } else {
                    items.setStackInSlot(pair[0], stack);
                }
                var back = insertIntoStorage(give);
                if (!back.isEmpty()) {
                    // 存储区满：退款不能静默消失，走掉落 + 告警兜底
                    dropReservedOverflow(back);
                }
                refunded++;
            }
        }
        if (refunded > 0) {
            setChanged();
        }
    }

    public void setAutoMode(cn.ism.mekck.kitchen.KitchenFamily family, boolean enabled) {
        autoMode.put(family, enabled);
        setChanged();
    }

    public java.util.List<cn.ism.mekck.kitchen.KitchenOrder> getOrders() {
        return orders;
    }

    /** 计算某系列每 tick 的能耗（线程数 × 20 FE）。 */
    private int energyPerTickFor(cn.ism.mekck.kitchen.KitchenFamily family) {
        var ability = abilityOf(family);
        return ability == null ? ENERGY_PER_THREAD : ENERGY_PER_THREAD * Math.max(1, ability.parallel());
    }

    // ── 空闲线程匹配的负缓存 ──
    // startThread 每 tick 会对每条空闲线程调用一次，每次都在该系列的全部配方里线性查找；
    // 当"存储区内容没变、上一次也没匹配到"时，这一整轮扫描是纯浪费（大型基地里会有几十条线程）。
    // 这里按「配方管理器 + 存储区指纹 + 系列」缓存"无匹配"结果，任何物品变化都会让指纹失效。
    private long kitchenNoMatchKey = Long.MIN_VALUE;
    private Object kitchenNoMatchManager;
    private String kitchenNoMatchFamily;

    /** 存储区（含样品槽）指纹：物品注册名 + 数量 + NBT 哈希。 */
    private long storageFingerprint() {
        long h = 1125899906842597L;
        int end = Math.min(TOTAL_SLOTS, items.getSlots());
        for (int i = STORAGE_START; i < end; i++) {
            net.minecraft.world.item.ItemStack st = items.getStackInSlot(i);
            long itemHash = 0L;
            if (!st.isEmpty()) {
                net.minecraft.resources.ResourceLocation id =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(st.getItem());
                itemHash = (id == null ? 0 : id.hashCode()) * 31L
                        + (st.getTag() == null ? 0 : st.getTag().hashCode());
            }
            h = h * 31L + itemHash;
            h = h * 31L + st.getCount();
        }
        return h;
    }

    /** 为一条空闲线程匹配配方并启动（启动时立即扣料，避免多线程重复匹配同一批原料）。 */
    private void startThread(Level level, cn.ism.mekck.kitchen.KitchenModule.Ability ability, KitchenThread t) {
        Object manager = level.getRecipeManager();
        long fingerprint = storageFingerprint();
        boolean isSandwich = ability.family() == cn.ism.mekck.kitchen.KitchenFamily.SANDWICH;
        if (manager == kitchenNoMatchManager && fingerprint == kitchenNoMatchKey
                && ability.family().id.equals(kitchenNoMatchFamily)) {
            return; // 存储区没变、上次也没匹配到：本 tick 不再重复全表扫描
        }
        // 三明治系列没有配方类型，走样品槽专用匹配
        var match = isSandwich
                ? cn.ism.mekck.kitchen.KitchenRecipeMatcher.findSandwich(items,
                        STORAGE_START, OUTPUT_START, SANDWICH_SAMPLE_SLOT)
                : cn.ism.mekck.kitchen.KitchenRecipeMatcher.find(level, ability.family(), items,
                        STORAGE_START, OUTPUT_START, fluidTank, filterOf(ability.family()));
        if (match == null) {
            kitchenNoMatchManager = manager;
            kitchenNoMatchKey = fingerprint;
            kitchenNoMatchFamily = ability.family().id;
            return;
        }
        kitchenNoMatchKey = Long.MIN_VALUE; // 命中即失效（扣料后存储区指纹也会变）
        cn.ism.mekck.kitchen.KitchenRecipeMatcher.consume(items, match.consumes());
        cn.ism.mekck.kitchen.KitchenRecipeMatcher.consumeFluid(fluidTank, match.fluidNeed());
        t.recipeId = match.recipeId();
        t.consumes = match.consumes();
        t.outputs = match.outputs();
        t.progress = 0;
        t.totalTime = Math.max(1, match.processTime());
        setChanged();
    }

    /** 推进一条线程；完成后产物写入输出区并复位。 */
    private void advanceThread(Level level, BlockPos pos, cn.ism.mekck.kitchen.KitchenModule.Ability ability,
                               KitchenThread t) {
        // 耗电：每 tick 按系列能耗扣电；电量不足则暂停推进
        int need = energyPerTickFor(ability.family());
        if (energy.getEnergyStored() < need) return;
        energy.extractEnergy(need, false);
        // 温度：产热系列按电阻型加热器比例产热；制冷系列耗电制冷
        if (ability.family().heatProducing) {
            heatComponent.addHeatFromEnergy(need);
        } else if (ability.family().cooling) {
            // 制冷侧：效率为电阻型加热器的 1/3，按温差限幅（与急冻制冰机一致）
            double targetK = 273.15 - 173.15; // 默认目标 -173℃
            double current = coldComponent.getTemperature();
            if (current > targetK) {
                double needHeat = (current - targetK) * cn.ism.mekck.util.MekCkHeatComponent.HEAT_CAPACITY;
                double maxHeat = 4_000 * 0.2;
                double cooling = Math.min(needHeat, maxHeat);
                int coolEnergy = (int) Math.ceil(cooling / 0.2);
                if (energy.getEnergyStored() >= coolEnergy) {
                    energy.extractEnergy(coolEnergy, false);
                    coldComponent.handleHeat(-cooling);
                }
            }
        }
        t.progress++;
        if (t.progress < t.totalTime) return;
        int parallel = Math.max(1, ability.parallel());
        java.util.List<net.minecraft.world.item.ItemStack> leftover =
                cn.ism.mekck.kitchen.KitchenRecipeMatcher.insertOutputs(items, OUTPUT_START,
                        OUTPUT_START + OUTPUT_SLOTS, t.outputs, parallel);
        if (!leftover.isEmpty()) {
            // 输出区装不下：保留线程状态，下 tick 重试插入。
            // 绝不能像以前那样把 leftover 丢掉再无条件重置线程——料是在开工时就扣掉的，
            // 产物被丢弃等于「吃料不交货」，而且六面默认 NONE 不会自动推出输出区，
            // 30 格输出槽一旦被占满，这条自动加工线就会永远空转吃料。
            return;
        }
        t.recipeId = null;
        t.consumes = null;
        t.outputs = null;
        t.progress = 0;
        t.totalTime = 0;
        setChanged();
    }

    /** 解析 mekmm 营养液气体；未安装时返回 null。 */
    private static mekanism.api.chemical.gas.Gas resolveNutrientGas() {
        try {
            return mekanism.api.MekanismAPI.gasRegistry().getValue(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekmm", "nutrient_solution"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 单罐气体处理器。 */
    private final class GasHandlerSingle implements mekanism.api.chemical.gas.IGasHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public mekanism.api.chemical.gas.GasStack getChemicalInTank(int tank) {
            return gasTank.getStack();
        }

        @Override
        public void setChemicalInTank(int tank, mekanism.api.chemical.gas.GasStack stack) {
            gasTank.setStack(stack);
        }

        @Override
        public long getTankCapacity(int tank) {
            return gasTank.getCapacity();
        }

        @Override
        public boolean isValid(int tank, mekanism.api.chemical.gas.GasStack stack) {
            return gasTank.isValid(stack);
        }

        @Override
        public mekanism.api.chemical.gas.GasStack insertChemical(int tank,
                mekanism.api.chemical.gas.GasStack stack, mekanism.api.Action action) {
            return gasTank.insert(stack, action, mekanism.api.AutomationType.EXTERNAL);
        }

        @Override
        public mekanism.api.chemical.gas.GasStack extractChemical(int tank, long amount,
                mekanism.api.Action action) {
            return gasTank.extract(amount, action, mekanism.api.AutomationType.EXTERNAL);
        }
    }

    // ================== AE2 网络拉料 ==================

    /**
     * 必须注销 AE2 网格宿主，否则 {@code MekckAe2.HOSTS} 条目永久残留。
     *
     * <p>本类是 {@code INetworkPullable}，因此 {@code NetworkChefProgress.isAe2Machine} 判定通过，
     * {@code MekckAe2.attachCapabilities} 会 {@code HOSTS.computeIfAbsent(be, ...)} 建条目。而
     * {@code HOSTS} 是 WeakHashMap、其 value 又强引用 key（owner），条目<b>无法被 GC 回收</b>，
     * 只能靠 {@code AE2Compat.onRemoved → destroy → HOSTS.remove} 显式清理。其余 12 个同类 BE
     * 都有这个覆写，本类此前缺失 ⇒ 每放置一台就把 BlockEntity 永久钉在静态 map 里。</p>
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        cn.ism.mekck.compat.AE2Compat.onRemoved(this);
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity getNetworkPullable() {
        return this;
    }

    /**
     * 网络拉料目标：当前**订单任务链**所需的叶子材料（实现见 {@link CentralKitchenNetworkPull}）。
     * 没有订单时返回空（中央厨房默认为下单驱动，不主动从网络补料）。
     */
    @Override
    public java.util.List<cn.ism.mekck.ae2.AE2InputSpec> getNetworkPullInputs() {
        return networkPull.networkPullInputs();
    }

    @Override
    public boolean supportsAutoPull() {
        return true;
    }

    /** 拉料落点：存储区。 */
    @Override
    public int[] getInputSlotRange() {
        return new int[]{STORAGE_START, OUTPUT_START};
    }

    @Override
    public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() {
        return items;
    }

    /** ME 下单开关（阶段 3 已实现于接口默认值，此处显式提供持久化字段）。 */
    private boolean meOrderEnabled = true;

    @Override
    public boolean isMeOrderEnabled() {
        return meOrderEnabled;
    }

    @Override
    public void setMeOrderEnabled(boolean enabled) {
        this.meOrderEnabled = enabled;
        setChanged();
    }

    private static byte[] encodeSide(cn.ism.mekck.SideMode[] config) {
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) out[i] = (byte) config[i].ordinal();
        return out;
    }

    private static void decodeSide(cn.ism.mekck.SideMode[] config, byte[] bytes) {
        var values = cn.ism.mekck.SideMode.values();
        for (int i = 0; i < Math.min(bytes.length, 6); i++) {
            int ord = bytes[i];
            if (ord >= 0 && ord < values.length) config[i] = values[ord];
        }
    }

    public void setItemSideMode(Direction dir, cn.ism.mekck.SideMode mode) {
        sideConfig.setItemSideMode(dir, mode);
    }

    /** 流体自动输入输出：抽取面从相邻抽入、弹出面向相邻推送（每面每刻 1000 mb）。 */
    private boolean tickFluidIO(Level level, BlockPos pos) {
        boolean moved = false;
        long now = level.getGameTime();
        int rate = fluidIoRate();
        for (Direction side : cn.ism.mekck.util.Directions.VALUES) {
            var mode = fluidSideConfig[side.ordinal()];
            if (mode == cn.ism.mekck.SideMode.NONE || mode == cn.ism.mekck.SideMode.PULL_INPUT_STORAGE) continue;
            var adj = fluidHandlerAt(level, pos.relative(side), side, now);
            if (adj == null) continue;
            if (mode == cn.ism.mekck.SideMode.PUSH_OUTPUT) {
                for (int t = 0; t < fluidTank.getTanks(); t++) {
                    var stack = fluidTank.getFluidInTank(t);
                    if (stack.isEmpty()) continue;
                    var offer = fluidTank.drain(new net.minecraftforge.fluids.FluidStack(
                            stack.getFluid(), Math.min(rate, stack.getAmount())),
                            net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);
                    if (offer.isEmpty()) continue;
                    int filled = adj.fill(offer,
                            net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                    if (filled > 0) {
                        fluidTank.drain(new net.minecraftforge.fluids.FluidStack(offer.getFluid(), filled),
                                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                        moved = true;
                    }
                }
            } else if (mode == cn.ism.mekck.SideMode.PULL_INPUT) {
                var offer = adj.drain(rate,
                        net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);
                if (offer.isEmpty()) continue;
                int filled = fluidTank.fill(offer,
                        net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) {
                    adj.drain(filled, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                    moved = true;
                }
            }
        }
        return moved;
    }

    /** 取相邻流体能力（BE 实例 + TTL 缓存，与物品 AutoIO / 流体 AutoFluidIO 同口径）。 */
    private net.minecraftforge.fluids.capability.IFluidHandler fluidHandlerAt(Level level, BlockPos adjPos,
                                                                            Direction dir, long now) {
        int d = dir.ordinal();
        // 未加载区块：getBlockState 会触发区块加载/生成，先判 hasChunkAt（与 Bioreactor.emitEnergy 同口径）
        if (!level.hasChunkAt(adjPos)) {
            fluidAdjBE[d] = null;
            fluidAdjHandler[d] = null;
            return null;
        }
        if (!level.getBlockState(adjPos).hasBlockEntity()) {
            fluidAdjBE[d] = null;
            fluidAdjHandler[d] = null;
            return null;
        }
        var be = level.getBlockEntity(adjPos);
        if (be == null) {
            fluidAdjBE[d] = null;
            fluidAdjHandler[d] = null;
            return null;
        }
        if (be == fluidAdjBE[d] && now - fluidAdjTick[d] < cn.ism.mekck.util.AutoIO.CAP_TTL_TICKS) {
            return fluidAdjHandler[d];
        }
        var h = be.getCapability(ForgeCapabilities.FLUID_HANDLER, dir.getOpposite()).orElse(null);
        fluidAdjBE[d] = be;
        fluidAdjHandler[d] = h;
        fluidAdjTick[d] = now;
        return h;
    }

    public cn.ism.mekck.SideMode getItemSideMode(Direction dir) {
        return sideConfig.getItemSideMode(dir);
    }

    public void setFluidSideMode(Direction dir, cn.ism.mekck.SideMode mode) {
        sideConfig.setFluidSideMode(dir, mode);
    }

    public cn.ism.mekck.SideMode getFluidSideMode(Direction dir) {
        return sideConfig.getFluidSideMode(dir);
    }

    public void setGasSideMode(Direction dir, cn.ism.mekck.SideMode mode) {
        sideConfig.setGasSideMode(dir, mode);
    }

    public cn.ism.mekck.SideMode getGasSideMode(Direction dir) {
        return sideConfig.getGasSideMode(dir);
    }

    public cn.ism.mekck.util.MultiFluidHandler getFluidTank() {
        return fluidTank;
    }

    public mekanism.api.chemical.gas.IGasTank getGasTank() {
        return gasTank;
    }

    public net.minecraftforge.energy.IEnergyStorage getEnergyStorage() {
        return energy;
    }

    /** 首个订单的当前步骤进度（0~1000，无订单时为 0）。 */
    public int firstOrderProgressMilli() {
        for (var order : orders) {
            if (order.stepTotalTime() > 0) {
                return (int) Math.round(order.stepRatio() * 1000.0);
            }
        }
        return 0;
    }

    /** 已安装系列的位掩码（用于界面与数据槽同步）。 */
    public int installedFamilyMask() {
        int mask = 0;
        for (var a : installedAbilities()) {
            mask |= 1 << a.family().ordinal();
        }
        return mask;
    }

    /** 订单数量。 */
    public int orderCount() {
        return orders.size();
    }

    /** 当前运行中的线程数（供界面显示）。 */
    public int runningThreads() {
        int n = 0;
        for (var list : threads.values()) {
            for (KitchenThread t : list) {
                if (t.busy()) n++;
            }
        }
        return n;
    }

    /** 总线程数（供界面显示）。 */
    public int totalThreads() {
        int n = 0;
        for (var list : threads.values()) n += list.size();
        return n;
    }

    // ================== 机器模块能力 ==================

    /** 当前已安装的机器模块能力（每个系列最多一个）。 */
    public java.util.List<cn.ism.mekck.kitchen.KitchenModule.Ability> installedAbilities() {
        // 缓存：本方法在 tick 路径上被多处调用（tickOrders / tickAutoMode / hasFamily 等），
        // 原先每次都重新扫模块槽并新建 ArrayList + EnumSet；模块槽一变（onContentsChanged / load）即失效。
        if (abilitiesCache != null && abilitiesCacheVersion == moduleVersion) {
            return abilitiesCache;
        }
        java.util.List<cn.ism.mekck.kitchen.KitchenModule.Ability> list = new java.util.ArrayList<>();
        java.util.Set<cn.ism.mekck.kitchen.KitchenFamily> seen = java.util.EnumSet.noneOf(cn.ism.mekck.kitchen.KitchenFamily.class);
        for (int i = MODULE_START; i < STORAGE_START; i++) {
            var stack = items.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            var ability = cn.ism.mekck.kitchen.KitchenModule.identify(stack);
            if (ability == null) continue;
            if (!seen.add(ability.family())) continue; // 同系列只取一个
            list.add(ability);
        }
        abilitiesCache = java.util.Collections.unmodifiableList(list);
        abilitiesCacheVersion = moduleVersion;
        return abilitiesCache;
    }

    /** 指定系列是否已安装模块。 */
    public boolean hasFamily(cn.ism.mekck.kitchen.KitchenFamily family) {
        for (var a : installedAbilities()) {
            if (a.family() == family) return true;
        }
        return false;
    }

    /** 指定系列的能力；未安装返回 null。 */
    public cn.ism.mekck.kitchen.KitchenModule.Ability abilityOf(cn.ism.mekck.kitchen.KitchenFamily family) {
        for (var a : installedAbilities()) {
            if (a.family() == family) return a;
        }
        return null;
    }

    /** 该模块物品能否放入指定模块槽（空槽 + 可识别 + 系列未安装）。 */
    public boolean canInstallModule(int slot, net.minecraft.world.item.ItemStack stack) {
        if (slot < MODULE_START || slot >= STORAGE_START) return false;
        if (!items.getStackInSlot(slot).isEmpty()) return false;
        var ability = cn.ism.mekck.kitchen.KitchenModule.identify(stack);
        return ability != null && !hasFamily(ability.family());
    }

    // ================== 内容 ==================

    /** 存储区是否还有空间放入指定物品（供输入逻辑使用）。 */
    public boolean canAcceptIntoStorage(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (int i = STORAGE_START; i < OUTPUT_START; i++) {
            var existing = items.getStackInSlot(i);
            if (existing.isEmpty()) return true;
            if (net.minecraft.world.item.ItemStack.isSameItemSameTags(existing, stack)) return true;
        }
        return false;
    }

    /** 把物品放入存储区（按类型归并），返回未能放入的余量。 */
    public net.minecraft.world.item.ItemStack insertIntoStorage(net.minecraft.world.item.ItemStack stack) {
        var remainder = stack.copy();
        for (int i = STORAGE_START; i < OUTPUT_START && !remainder.isEmpty(); i++) {
            var existing = items.getStackInSlot(i);
            if (!existing.isEmpty() && net.minecraft.world.item.ItemStack.isSameItemSameTags(existing, remainder)) {
                int space = BIG_STACK - existing.getCount();
                int moved = Math.min(space, remainder.getCount());
                existing.grow(moved);
                remainder.shrink(moved);
            }
        }
        for (int i = STORAGE_START; i < OUTPUT_START && !remainder.isEmpty(); i++) {
            if (items.getStackInSlot(i).isEmpty()) {
                items.setStackInSlot(i, remainder.copy());
                remainder.setCount(0);
            }
        }
        return remainder;
    }

    /** 破坏时掉落全部内容。 */
    public void dropContents() {
        Level level = getLevel();
        if (level == null) return;
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            var stack = items.getStackInSlot(i);
            if (!stack.isEmpty()) {
                // 大堆叠感知：21 亿若按原版 64 分堆会瞬间生成数千万个物品实体
                cn.ism.mekck.util.BigStackDrops.drop(level, getBlockPos().getX() + 0.5D, getBlockPos().getY() + 0.5D,
                        getBlockPos().getZ() + 0.5D, stack);
                items.setStackInSlot(i, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
    }

    // ================== NBT ==================

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Items", items.serializeNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        // 系列过滤器
        net.minecraft.nbt.CompoundTag filterTag = new net.minecraft.nbt.CompoundTag();
        for (var e : filters.entrySet()) {
            var f = e.getValue();
            if (f.mode() == cn.ism.mekck.kitchen.KitchenFilter.Mode.OFF && f.isEmpty()) continue;
            filterTag.put(e.getKey().name(), f.save());
        }
        tag.put("Filters", filterTag);
        tag.put("FluidTanks", fluidTank.writeToNBT());
        tag.put("GasTank", gasTank.serializeNBT());
        tag.putByteArray("ItemSideConfig", encodeSide(itemSideConfig));
        tag.putByteArray("FluidSideConfig", encodeSide(fluidSideConfig));
        tag.putByteArray("GasSideConfig", encodeSide(gasSideConfig));
        // 自动加工开关（方案 C）
        net.minecraft.nbt.CompoundTag autoTag = new net.minecraft.nbt.CompoundTag();
        for (var e : autoMode.entrySet()) {
            autoTag.putBoolean(e.getKey().id, Boolean.TRUE.equals(e.getValue()));
        }
        tag.put("AutoMode", autoTag);
        // 订单持久化（步骤只存系列 + 配方 id + 份数，材料/产物在读取时由配方重建）
        net.minecraft.nbt.ListTag orderList = new net.minecraft.nbt.ListTag();
        for (var order : orders) {
            net.minecraft.nbt.CompoundTag ot = new net.minecraft.nbt.CompoundTag();
            ot.putInt("Id", order.id());
            ot.putInt("StepIndex", order.stepIndex());
            ot.putInt("StepProgress", order.rawProgress());
            ot.putInt("StepTotal", order.stepTotalTime());
            ot.putString("State", order.state().name());
            ot.put("Buffer", order.buffer.serializeNBT());
            net.minecraft.nbt.ListTag stepList = new net.minecraft.nbt.ListTag();
            for (var step : order.steps()) {
                net.minecraft.nbt.CompoundTag st = new net.minecraft.nbt.CompoundTag();
                st.putString("Recipe", step.recipeId.toString());
                st.putInt("Batches", step.batches);
                stepList.add(st);
            }
            ot.put("Steps", stepList);
            orderList.add(ot);
        }
        tag.put("Orders", orderList);
        tag.putInt("NextOrderId", nextOrderId);
        // 加工线程持久化：料在 startThread 时就扣了，只存 recipeId 会让已扣的材料凭空消失
        // （区块卸载 / 机器被搬走时该线程直接消失，等于「吃料不交货」且不可追回）。
        // 存 Outputs 而非只存 recipeId，是为了输出区被占满时的重试状态（见 advanceThread 的 leftover 分支）也能续上。
        net.minecraft.nbt.CompoundTag threadTag = new net.minecraft.nbt.CompoundTag();
        for (var e : threads.entrySet()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (var t : e.getValue()) {
                if (!t.busy()) continue;
                net.minecraft.nbt.CompoundTag tt = new net.minecraft.nbt.CompoundTag();
                tt.putString("Recipe", t.recipeId.toString());
                net.minecraft.nbt.ListTag consumeList = new net.minecraft.nbt.ListTag();
                if (t.consumes != null) {
                    for (int[] c : t.consumes) {
                        net.minecraft.nbt.IntArrayTag at = new net.minecraft.nbt.IntArrayTag(new int[]{c[0], c[1]});
                        consumeList.add(at);
                    }
                }
                tt.put("Consumes", consumeList);
                net.minecraft.nbt.ListTag outputList = new net.minecraft.nbt.ListTag();
                if (t.outputs != null) {
                    for (var o : t.outputs) {
                        net.minecraft.nbt.CompoundTag ot = new net.minecraft.nbt.CompoundTag();
                        o.save(ot);
                        outputList.add(ot);
                    }
                }
                tt.put("Outputs", outputList);
                tt.putInt("Progress", t.progress);
                tt.putInt("Total", t.totalTime);
                list.add(tt);
            }
            if (!list.isEmpty()) threadTag.put(e.getKey().id, list);
        }
        tag.put("Threads", threadTag);
        tag.put("HeatSide", heatComponent.save());
        tag.put("ColdSide", coldComponent.save());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Items")) {
            items.deserializeNBT(tag.getCompound("Items"));
            moduleVersion++; // 反序列化可能不触发 onContentsChanged：直接让模块列表缓存失效
        }
        if (tag.contains("Energy")) {
            // 单次 receiveEnergy 受 maxReceive（200,000 FE）夹断，读档必须循环灌满，
            // 否则每次区块重载最多只恢复 200,000 FE（容量 5,000,000）。
            int remaining = tag.getInt("Energy");
            while (remaining > 0) {
                int received = energy.receiveEnergy(remaining, false);
                if (received == 0) {
                    break;
                }
                remaining -= received;
            }
        }
        if (tag.contains("MeOrderEnabled")) meOrderEnabled = tag.getBoolean("MeOrderEnabled");
        if (tag.contains("Filters", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            filters.clear();
            var filterTag = tag.getCompound("Filters");
            for (String key : filterTag.getAllKeys()) {
                try {
                    var family = cn.ism.mekck.kitchen.KitchenFamily.valueOf(key);
                    filterOf(family).load(filterTag.getCompound(key));
                } catch (Throwable ignored) {
                }
            }
        }
        if (tag.contains("FluidTanks")) fluidTank.readFromNBT(tag.getCompound("FluidTanks"));
        if (tag.contains("GasTank")) gasTank.deserializeNBT(tag.getCompound("GasTank"));
        if (tag.contains("ItemSideConfig", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) {
            decodeSide(itemSideConfig, tag.getByteArray("ItemSideConfig"));
        }
        if (tag.contains("FluidSideConfig", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) {
            decodeSide(fluidSideConfig, tag.getByteArray("FluidSideConfig"));
        }
        if (tag.contains("GasSideConfig", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) {
            decodeSide(gasSideConfig, tag.getByteArray("GasSideConfig"));
        }
        // 订单恢复：load 阶段 level 尚不可用（Forge 先 load 后 setLevel），
        // 重建步骤需要配方管理器，故只暂存 NBT，等 onLoad 再重建。
        orders.clear();
        pendingOrdersTag = tag.contains("Orders", net.minecraft.nbt.Tag.TAG_LIST)
                ? tag.getList("Orders", net.minecraft.nbt.Tag.TAG_COMPOUND)
                : null;
        if (tag.contains("NextOrderId")) nextOrderId = Math.max(nextOrderId, tag.getInt("NextOrderId"));
        if (tag.contains("Threads", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var threadTag = tag.getCompound("Threads");
            for (cn.ism.mekck.kitchen.KitchenFamily f : cn.ism.mekck.kitchen.KitchenFamily.values()) {
                if (!threadTag.contains(f.id, net.minecraft.nbt.Tag.TAG_LIST)) continue;
                var ability = abilityOf(f);
                if (ability == null) {
                    // 该系列模块已不在机器里。
                    //
                    // ⚠️ 这里**不能**直接丢线程：busy 线程的材料在 startThread 开工那一刻
                    // 就扣了，产物还没出。丢掉它 = 材料凭空消失、产物永远不会出现。
                    // 所以把它们**原样退回共享存储区**——玩家至少拿回材料。
                    refundOrphanedThreads(f);
                    continue;
                }
                var list = threads.computeIfAbsent(f, k -> new java.util.ArrayList<>());
                // 线程数由当前已安装模块决定（可能因拆模块 / 换机器而变化），先对齐到应有长度。
                int want = Math.max(1, ability.threads());
                while (list.size() < want) list.add(new KitchenThread());
                // ⚠️ 缩容**只能删空闲线程**。
                //
                // 原来的写法是 `while (list.size() > want) list.remove(list.size() - 1);` ——
                // 无条件从尾部删。而 busy 线程的材料已扣、产物未出，删它就是静默销毁在制品：
                // 8 线程的高等级模块开工后换成 1 线程的基础模块，读档一次就丢 7 条线程的材料。
                //
                // 讽刺的是同文件 tickAutoMode 上方那段注释明确写着
                // 「在 tick 路径上缩容会把正在加工的线程直接删掉 ⇒ 材料凭空损失」，
                // 然后在读档路径上做了同一件被自己禁止的事（只是被「自动模式不可达」掩盖着，
                // 补上开关后立刻升级为 Critical）。
                while (list.size() > want) {
                    int victim = -1;
                    for (int i = list.size() - 1; i >= 0; i--) {
                        if (!list.get(i).busy()) {
                            victim = i;
                            break;
                        }
                    }
                    if (victim < 0) {
                        // 全在忙：一条都不删。多出来的线程这一轮不参与 tickAutoMode
                        // （它按 ability.threads() 只推进前 want 条），但会继续推进到完成
                        // —— 完成后自然变空闲，下一次读档/缩容才被回收。
                        break;
                    }
                    list.remove(victim);
                }
                var src = threadTag.getList(f.id, net.minecraft.nbt.Tag.TAG_COMPOUND);
                for (int i = 0; i < src.size() && i < list.size(); i++) {
                    var tt = src.getCompound(i);
                    var t = list.get(i);
                    var rid = net.minecraft.resources.ResourceLocation.tryParse(tt.getString("Recipe"));
                    if (rid == null) continue;
                    t.recipeId = rid;
                    t.progress = Math.max(0, tt.getInt("Progress"));
                    t.totalTime = Math.max(1, tt.getInt("Total"));
                    var consumeList = tt.getList("Consumes", net.minecraft.nbt.Tag.TAG_INT_ARRAY);
                    if (!consumeList.isEmpty()) {
                        java.util.List<int[]> consumes = new java.util.ArrayList<>(consumeList.size());
                        for (int j = 0; j < consumeList.size(); j++) {
                            int[] pair = consumeList.getIntArray(j);
                            // 槽位索引必须仍在当前布局内，否则说明机器被换过型
                            if (pair.length < 2 || pair[0] < 0 || pair[0] >= items.getSlots()) {
                                consumes.clear();
                                break;
                            }
                            consumes.add(pair);
                        }
                        t.consumes = consumes;
                    }
                    var outputList = tt.getList("Outputs", net.minecraft.nbt.Tag.TAG_COMPOUND);
                    java.util.List<net.minecraft.world.item.ItemStack> outputs = new java.util.ArrayList<>(outputList.size());
                    for (int j = 0; j < outputList.size(); j++) {
                        var st = net.minecraft.world.item.ItemStack.of(outputList.getCompound(j));
                        if (!st.isEmpty()) outputs.add(st);
                    }
                    t.outputs = outputs;
                }
            }
        }
        if (tag.contains("AutoMode", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var autoTag = tag.getCompound("AutoMode");
            for (cn.ism.mekck.kitchen.KitchenFamily f : cn.ism.mekck.kitchen.KitchenFamily.values()) {
                if (autoTag.contains(f.id)) autoMode.put(f, autoTag.getBoolean(f.id));
            }
        }
        if (tag.contains("HeatSide", net.minecraft.nbt.Tag.TAG_COMPOUND)) heatComponent.load(tag.getCompound("HeatSide"));
        if (tag.contains("ColdSide", net.minecraft.nbt.Tag.TAG_COMPOUND)) coldComponent.load(tag.getCompound("ColdSide"));
    }

    /**
     * 读档后的订单重建（此时 level 已就绪，见 {@link #pendingOrdersTag}）。
     *
     * <p>Forge 在 {@code setLevel} 之前调 {@code load}，而 {@code onLoad} 在方块实体
     * 进入世界后、首次 tick 前调用（{@code LevelChunk#registerAllBlockEntitiesAfterLevelLoad}
     * → {@code Level#addFreshBlockEntities} → {@code Level#tickBlockEntities}）。</p>
     */
    @Override
    public void onLoad() {
        super.onLoad();
        var pending = pendingOrdersTag;
        if (pending != null) {
            pendingOrdersTag = null;
            restoreOrders(pending);
        }
    }

    /**
     * 重建订单列表。
     *
     * <p>steps 重建失败的订单（配方被删/改名）不能再跑，但它的暂存区里还留着已预留的
     * 叶子材料与在制品 —— 丢弃前必须回插存储区/掉落，不得静默销毁。</p>
     */
    private void restoreOrders(net.minecraft.nbt.ListTag orderList) {
        var level = getLevel();
        if (level == null) {
            return;
        }
        for (int i = 0; i < orderList.size(); i++) {
            var ot = orderList.getCompound(i);
            java.util.List<cn.ism.mekck.kitchen.KitchenCraftingPlan.Step> steps = new java.util.ArrayList<>();
            var stepList = ot.getList("Steps", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int j = 0; j < stepList.size(); j++) {
                var st = stepList.getCompound(j);
                var rid = net.minecraft.resources.ResourceLocation.tryParse(st.getString("Recipe"));
                if (rid == null) continue;
                // 配方管理器找不到时，回退到烟火的虚拟配方（它不注册原版配方类型）
                net.minecraft.world.item.crafting.Recipe<?> recipe =
                        level.getRecipeManager().byKey(rid).orElse(null);
                if (recipe == null) {
                    recipe = cn.ism.mekck.compat.KaleidoscopeGrillingCompat.findVirtualById(rid);
                }
                if (recipe == null) continue;
                var step = cn.ism.mekck.kitchen.KitchenCraftingPlan.rebuildStep(level, rid,
                        st.getInt("Batches"), recipe);
                if (step != null) steps.add(step);
            }
            if (steps.isEmpty()) {
                refundDiscardedOrderBuffer(ot);
                continue;
            }
            var order = new cn.ism.mekck.kitchen.KitchenOrder(ot.getInt("Id"), steps);
            if (ot.contains("Buffer")) order.buffer.deserializeNBT(ot.getCompound("Buffer"));
            while (order.stepIndex() < ot.getInt("StepIndex") && order.stepIndex() < steps.size()) {
                order.advanceStep();
            }
            if (ot.contains("StepProgress")) {
                order.restoreProgress(ot.getInt("StepProgress"), ot.getInt("StepTotal"));
            }
            order.setState(cn.ism.mekck.kitchen.KitchenOrder.State.RUNNING);
            // 读档同样受上限约束：老存档可能是在没有上限时写下的，超出的部分不能
            // 带着 buffer 一起丢（正在加工的产物与预留材料都在里面）。
            if (orders.size() < MAX_ORDERS) {
                orders.add(order);
            } else {
                refundDiscardedOrderBuffer(ot);
            }
        }
    }

    /** 读档丢弃订单前，把它的暂存区内容退回存储区（放不下则掉落 + 告警），不得静默销毁。 */
    private void refundDiscardedOrderBuffer(net.minecraft.nbt.CompoundTag orderTag) {
        if (!orderTag.contains("Buffer")) {
            return;
        }
        var buffer = new cn.ism.mekck.util.BigStackItemHandler(cn.ism.mekck.kitchen.KitchenOrder.BUFFER_SLOTS) {
            @Override
            public int getSlotLimit(int slot) {
                return Integer.MAX_VALUE - 1;
            }
        };
        buffer.deserializeNBT(orderTag.getCompound("Buffer"));
        for (int i = 0; i < buffer.getSlots(); i++) {
            var stack = buffer.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            var back = insertIntoStorage(stack.copy());
            if (!back.isEmpty()) {
                dropReservedOverflow(back);
            }
        }
    }

    // ================== 能力 ==================

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            return itemCapability.cast();
        }
        if (capability == ForgeCapabilities.ENERGY) {
            return energyCapability.cast();
        }
        if (capability == ForgeCapabilities.FLUID_HANDLER) {
            // 流体能力对所有面开放：侧配只驱动自动 IO 的搬运方向，不限制外部访问。
            // （原先这里按 fluidSideConfig 分了两支，但两支返回同一个对象 —— 死分支；
            //  注释还声称「抽取面只接受注入、弹出面只允许抽取」，与实现相反。）
            return fluidCapability.cast();
        }
        if (capability == mekanism.common.capabilities.Capabilities.GAS_HANDLER) {
            // 气体侧配：只有标记为「抽取」的面暴露气体能力
            if (side != null && gasSideConfig[side.ordinal()] != cn.ism.mekck.SideMode.PULL_INPUT) {
                return LazyOptional.empty();
            }
            return gasCapability.cast();
        }
        if (capability == mekanism.common.capabilities.Capabilities.HEAT_HANDLER) {
            // 正面暴露制冷侧、背面暴露发热侧；其余面暴露发热侧（默认）
            if (side != null && level != null) {
                BlockState state = getBlockState();
                Direction facing = state.hasProperty(cn.ism.mekck.block.CentralKitchenBlock.FACING)
                        ? state.getValue(cn.ism.mekck.block.CentralKitchenBlock.FACING) : Direction.NORTH;
                if (side == facing) return coldCapability.cast();
            }
            return heatCapability.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemCapability.invalidate();
        energyCapability.invalidate();
        fluidCapability.invalidate();
        gasCapability.invalidate();
        heatCapability.invalidate();
        coldCapability.invalidate();
    }

    // ================== 菜单 ==================

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.mekck.central_kitchen");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new cn.ism.mekck.menu.CentralKitchenMenu(containerId, inventory, this);
    }
}
