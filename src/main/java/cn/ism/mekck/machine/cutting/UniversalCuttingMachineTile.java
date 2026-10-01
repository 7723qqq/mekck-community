package cn.ism.mekck.machine.cutting;

import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.upgrade.UpgradeHelper;
import cn.ism.mekck.util.MatchKey;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.math.FloatingLong;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.TileComponentUpgrade;
import mekanism.common.tile.interfaces.ISustainedData;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 切菜机 —— 14 台遗留单机之一，第四轮改为继承 {@link TileEntityConfigurableMachine}。
 *
 * <h3>设计原则：只写「与 Mek 不一样」的，其余全继承</h3>
 * 实测（javap 全 jar 扫描）{@code TileEntityMekanism} 已把红石模式与供电状态、比较器、
 * 能力同步、升级卡装卸与持久化、槽位内容存读全部兜住。迁移前这些在
 * {@code UniversalCuttingMachineBlockEntity} 里手搓了约 200 行。
 *
 * <p>继承 {@code TileEntityConfigurableMachine}（而不是 {@code TileEntityMekanism}）
 * 是因为它额外 {@code implements ISideConfiguration}，从而有 {@code getConfig()} 与
 * {@code getEjector()} —— 侧面配置的数据源。没有它 {@code forSideWithConfig} 那两个
 * {@code Supplier} 根本凑不出来。</p>
 *
 * <h3>与 Mek 的三处<b>真实差异</b></h3>
 * <ol>
 *   <li><b>PULSE 需要锁存</b>：收到红石上升沿后要完整跑完一批才停，而 Mek 的
 *       {@code canPulse()} 只给瞬时语义 ⇒ 保留本类的 {@link #pulseRunning}。</li>
 *   <li><b>输入槽准入</b>：旧实现要求「非升级物品 + 是切割配方原料」，
 *       而 {@code MekCkSlot.input} 是 {@code alwaysTrueBi}（什么都收）⇒ 用
 *       {@code inputFiltered} 把这条判据补回来。</li>
 *   <li><b>认卡方式</b>：本仓按注册名比对，Mek 走 {@code IUpgradeItem}。</li>
 * </ol>
 *
 * <h3>「创造卡」是 {@link MekCkUpgradeRefs#randomize()}</h3>
 * 旧实现认的是 {@code mekanism_extras:upgrade_creative}，而 Mek Extras 也往
 * {@link Upgrade} 注入了同名 {@code CREATIVE}，两者会抢同一个注册 id
 * （详见 {@link MekCkUpgradeRefs} 类注释）。本仓注入时改名为 {@code RANDOMIZE}，
 * 所以必须走 {@code randomize()} —— {@code Upgrade.CREATIVE} 在本仓根本不存在。
 */
public final class UniversalCuttingMachineTile extends TileEntityConfigurableMachine
        implements ISustainedData {

    // ── 槽位 GUI 坐标（沿用旧菜单，换了玩家会找不到位置）──────────────
    private static final int INPUT_X = 38, INPUT_Y = 41;
    private static final int OUTPUT_X = 56, OUTPUT_Y = 41;
    private static final int POWER_X = 7, POWER_Y = 13;

    /** 输入/输出槽的物品数量上限 —— 旧实现是极端堆叠。 */
    private static final int SLOT_LIMIT = Integer.MAX_VALUE;

    /** 基础加工耗时（游戏刻）。装速度卡后按倍率缩短。 */
    public static final int PROCESS_TIME = 200;
    /** 能量容量（FE）—— 旧 {@code UniversalCuttingMachineBlockEntity.ENERGY_CAPACITY} 搬过来。 */
    public static final int ENERGY_CAPACITY = 100_000;
    /** 单次接收上限（FE）。 */
    public static final int MAX_RECEIVE = 1_000;
    /** 单件 FE 耗电。 */
    public static final int ENERGY_PER_TICK = 20;

    /**
     * 槽位对象 —— <b>只能在 {@link #getInitialInventory} 里 new</b>。
     *
     * <p>该方法由父类构造器回调，那时本类字段初始化器一个都还没跑；
     * 写成字段初始化器会让所有槽拿到 null（见 {@code MekCkMachineTile} 的
     * 「构造期顺序陷阱」注释）。</p>
     */
    private MekCkSlot inputSlot;
    private MekCkSlot outputSlot;
    private mekanism.api.inventory.IInventorySlot powerSlot;
    private MachineEnergyContainer<UniversalCuttingMachineTile> energyContainer;

    public UniversalCuttingMachineTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
        // 侧配内容必须在这里、且只能在 super(...) 之后登记：
        // setupItemIOConfig 依赖 getInitialInventory 建好的槽位对象，
        // setupInputConfig 依赖 getInitialEnergyContainers 建好的能量容器。
        // （Mek 自己的机器也是在构造器体里做同一件事，见 GrillBlockEntity。）
        configComponent.setupItemIOConfig(List.of(inputSlot), List.of(outputSlot), powerSlot, false);
        configComponent.setupInputConfig(TransmissionType.ENERGY, energyContainer);
        // 只给 ITEM 挂弹出：ENERGY 侧的 ConfigInfo 已被 setupInputConfig 置为 setCanEject(false)。
        ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM);
    }

    // ── 构造期钩子 ──────────────────────────────────────────────────────

    /**
     * 初始化侧配组件 —— <b>必须实现</b>，否则放置时必崩。
     *
     * <p>崩溃形态：{@code NPE: Cannot invoke "TileComponentConfig.read(CompoundTag)"
     * because the return value of "ISideConfiguration.getConfig()" is null}。
     * 父类只声明 {@code configComponent} 字段（public、不赋值），初始化责任在子类。</p>
     */
    @Override
    protected void presetVariables() {
        configComponent = new TileComponentConfig(this, TransmissionType.ITEM, TransmissionType.ENERGY);
        ejectorComponent = new TileComponentEjector(this);
    }

    /** 能量容器；容量与能耗由方块的 {@code AttributeEnergy} 读取，不在这里设。 */
    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSideWithConfig(this::getDirection, this::getConfig);
        energyContainer = MachineEnergyContainer.input(this, listener);
        builder.addContainer(energyContainer);
        return builder.build();
    }

    /** 排槽位：输入 / 输出 / 升级 / 电源。 */
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this::getDirection, this::getConfig);

        // 输入槽用 inputFiltered：旧实现的 isItemValid 要求「非升级物品 + 是切割原料」，
        // 而 MekCkSlot.input 的 canInsert 是 alwaysTrueBi（任何东西都收）。
        // 不校验的后果是玩家/管道能把升级卡丢进输入槽，界面照收不误却永远不参与加工。
        inputSlot = MekCkSlot.inputFiltered(SLOT_LIMIT,
                (stack, automation) -> !isAnyUpgradeItem(stack)
                        && cn.ism.mekck.util.RecipeInputMatcher.matchesCutting(getLevel(), stack),
                listener, INPUT_X, INPUT_Y);
        builder.addSlot(inputSlot);

        outputSlot = MekCkSlot.output(SLOT_LIMIT, listener, OUTPUT_X, OUTPUT_Y);
        builder.addSlot(outputSlot);

        // 升级槽：速度卡与能量卡各一格。旧实现是「插一张卡 = 一级」，
        // 与 Mek 原生一致，所以直接用 Mek 的升级槽。
        builder.addSlot(mekanism.common.inventory.slot.UpgradeInventorySlot.input(listener,
                Set.of(Upgrade.SPEED)));
        builder.addSlot(mekanism.common.inventory.slot.UpgradeInventorySlot.input(listener,
                Set.of(Upgrade.ENERGY)));

        powerSlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener,
                POWER_X, POWER_Y);
        builder.addSlot(powerSlot);
        return builder.build();
    }

    // ── 升级 ────────────────────────────────────────────────────────────

    /**
     * 本机支持哪些升级。
     *
     * <p><b>必须返回 {@link EnumSet}</b>：Mek 的 {@code TileComponentUpgrade} 构造器会做
     * {@code EnumSet.copyOf(tile.getSupportedUpgrade())}，而它对<b>非 EnumSet 的空集合</b>
     * 抛 {@code IllegalArgumentException}。</p>
     */
    @Override
    public Set<Upgrade> getSupportedUpgrade() {
        EnumSet<Upgrade> supported = EnumSet.noneOf(Upgrade.class);
        supported.add(Upgrade.SPEED);
        supported.add(Upgrade.ENERGY);
        supported.add(MekCkUpgradeRefs.randomize());   // 即旧实现的「创造卡」
        return supported;
    }

    public int getSpeedUpgradeCount() {
        return upgradeComponent == null ? 0 : upgradeComponent.getUpgrades(Upgrade.SPEED);
    }

    public int getEnergyUpgradeCount() {
        return upgradeComponent == null ? 0 : upgradeComponent.getUpgrades(Upgrade.ENERGY);
    }

    public boolean hasCreativeUpgrade() {
        return upgradeComponent != null
                && upgradeComponent.getUpgrades(MekCkUpgradeRefs.randomize()) > 0;
    }

    public double getEffectiveSpeedMultiplier() {
        return UpgradeHelper.speedMultiplier(getSpeedUpgradeCount());
    }

    public double getEffectiveEnergyConsumptionMultiplier() {
        return UpgradeHelper.energyConsumptionMultiplier(getEnergyUpgradeCount());
    }

    public int getEffectiveProcessTime() {
        return Math.max(1, (int) (PROCESS_TIME / getEffectiveSpeedMultiplier()));
    }

    // ── 运行态（ITileActive 的两个抽象方法）────────────────────────────

    /**
     * 本机运行态。<b>刻意自己存一份</b>：父类的 {@code currentActive} 是 <b>private</b>，
     * 子类读不到，而 {@code getActive()} 又是必须覆写的抽象方法。
     */
    private boolean active;

    @Override
    public boolean getActive() {
        return active;
    }

    @Override
    public void setActive(boolean active) {
        this.active = active;
    }

    // ── 持久化（ISustainedData 三项全抽象）──────────────────────────────

    @Override
    public void writeSustainedData(CompoundTag tag) {
        tag.putInt("Progress", progress);
        if (orderRecipeId != null) {
            tag.putString("OrderRecipeIdKey", orderRecipeId.toString());
        }
        tag.putInt("OrderQuantity", orderQuantity);
        tag.putInt("OrderCompleted", orderCompleted);
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        tag.putBoolean("PulseRunning", pulseRunning);
    }

    @Override
    public void readSustainedData(CompoundTag tag) {
        progress = tag.getInt("Progress");
        orderRecipeId = tag.contains("OrderRecipeIdKey")
                ? ResourceLocation.tryParse(tag.getString("OrderRecipeIdKey")) : null;
        orderQuantity = tag.getInt("OrderQuantity");
        orderCompleted = tag.getInt("OrderCompleted");
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        pulseRunning = tag.getBoolean("PulseRunning");
    }

    /** 旧存档键 → 新键的改名表；Mek 10.4.6 全 jar 无外部消费方。 */
    @Override
    public Map<String, String> getTileDataRemap() {
        return Map.of();
    }

    // ==================== 加工逻辑 ====================

    private int progress;
    private boolean pulseRunning;
    private ResourceLocation orderRecipeId;
    private int orderQuantity;
    private int orderCompleted;
    private boolean meOrderEnabled = true;
    private RecipeWrapper cachedWrapper;
    private long cachedKey = Long.MIN_VALUE;
    private CuttingBoardRecipe cachedRecipe;
    private boolean cachedValid;

    @Override
    protected void onUpdateServer() {
        super.onUpdateServer();
        if (getLevel() == null || getLevel().isClientSide || inputSlot == null) {
            return;
        }

        drainPowerSlot();

        boolean creative = hasCreativeUpgrade();
        if (creative && energyContainer != null) {
            energyContainer.insert(energyContainer.getNeeded(), Action.EXECUTE, AutomationType.INTERNAL);
        }

        double speedMult = getEffectiveSpeedMultiplier();
        double energyMult = getEffectiveEnergyConsumptionMultiplier();
        int processTime = creative ? 1 : getEffectiveProcessTime();
        int perTick = creative ? 0
                : (int) Math.ceil(ENERGY_PER_TICK * speedMult * speedMult * energyMult);

        // PULSE：上升沿锁存
        if (getControlType() == RedstoneControl.PULSE && isPowered() && !wasPowered()) {
            pulseRunning = true;
        }

        Optional<CuttingBoardRecipe> recipe = findRecipe();
        boolean enoughEnergy = perTick <= 0 || energyContainer == null
                || energyContainer.getEnergy().greaterOrEqual(FloatingLong.create(perTick));

        if (canFunctionRedstone() && recipe.isPresent() && enoughEnergy
                && canFitAll(recipe.get().getResults())) {
            if (perTick > 0 && energyContainer != null) {
                energyContainer.extract(FloatingLong.create(perTick), Action.EXECUTE, AutomationType.INTERNAL);
            }
            progress++;
            setActive(true);
            if (progress >= processTime) {
                completeRecipe(recipe.get());
                progress = 0;
                setActive(false);
                if (getControlType() == RedstoneControl.PULSE) {
                    pulseRunning = false;
                }
            }
        } else {
            if (progress != 0) {
                progress = 0;
                setActive(false);
            }
            if (getControlType() == RedstoneControl.PULSE && pulseRunning) {
                pulseRunning = false;
            }
        }
    }

    /**
     * 红石是否允许运行 —— <b>本机与 Mek 的真实差异</b>。
     *
     * <p>PULSE 需要「锁存」语义：收到上升沿后完整跑完一批才停，
     * 而 Mek 的 {@code canPulse()} 只给瞬时语义 ⇒ 该模式读本类 {@link #pulseRunning}。</p>
     */
    public boolean canFunctionRedstone() {
        return switch (getControlType()) {
            case DISABLED -> true;
            case HIGH -> isPowered();
            case LOW -> !isPowered();
            case PULSE -> pulseRunning;
        };
    }

    // ── 配方 ────────────────────────────────────────────────────────────

    private RecipeWrapper wrapper() {
        if (cachedWrapper == null) {
            cachedWrapper = new RecipeWrapper(new cn.ism.mekck.machine.MekCkSlotHandler(List.of(inputSlot)));
        }
        return cachedWrapper;
    }

    private Optional<CuttingBoardRecipe> findRecipe() {
        if (getLevel() == null || inputSlot.getStack().isEmpty()) {
            return Optional.empty();
        }
        long key = MatchKey.of(inputSlot.getStack());
        if (cachedValid && cachedKey == key) {
            Optional<CuttingBoardRecipe> cached = Optional.ofNullable(cachedRecipe);
            if (orderRecipeId != null && (cached.isEmpty()
                    || !orderRecipeId.equals(cached.get().getId()))) {
                return Optional.empty();
            }
            return cached;
        }
        Optional<CuttingBoardRecipe> found = getLevel().getRecipeManager()
                .getRecipeFor(ModRecipeTypes.CUTTING.get(), wrapper(), getLevel());
        cachedValid = true;
        cachedKey = key;
        cachedRecipe = found.orElse(null);
        if (orderRecipeId != null && (found.isEmpty() || !orderRecipeId.equals(found.get().getId()))) {
            return Optional.empty();
        }
        return found;
    }

    private void completeRecipe(CuttingBoardRecipe recipe) {
        if (!canFitAll(recipe.getResults())) {
            return;
        }
        inputSlot.extractItem(1, Action.EXECUTE, AutomationType.INTERNAL);
        for (ItemStack result : recipe.getResults()) {
            // 只有机器自己能往产物槽写（MekCkSlot.output 的 canInsert = internalOnly），
            // 而 AutomationType.INTERNAL 正是那个判据认的身份。
            outputSlot.insertItem(result.copy(), Action.EXECUTE, AutomationType.INTERNAL);
        }
        if (orderRecipeId != null && ++orderCompleted >= orderQuantity) {
            orderRecipeId = null;
            orderQuantity = 0;
            orderCompleted = 0;
        }
    }

    /**
     * 预演：产物能不能全塞进输出槽。塞不下就不开工。
     *
     * <p>用 {@link Action#SIMULATE} 直接向真槽试插 —— Mek 的
     * {@code insertItem(SIMULATE)} 返回装不下的余量而<b>不改动槽位</b>，
     * 不必像旧实现那样每 tick 复制一份 handler。</p>
     */
    private boolean canFitAll(List<ItemStack> results) {
        for (ItemStack result : results) {
            ItemStack leftover = outputSlot.insertItem(result.copy(),
                    Action.SIMULATE, AutomationType.INTERNAL);
            if (!leftover.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ── 电源槽 ──────────────────────────────────────────────────────────

    /**
     * 从电源槽抽能量注入本机。
     *
     * <p>旧实现走 {@code PowerSlotUtil.drain(stack, Forge EnergyStorage, …)}；
     * 换成 Mek 容器后那条通路不再适用（两种能量模型不同源），这里改为直接对容器抽取，
     * 准入判据仍用 {@code PowerSlotUtil}（它自己就写着「与 Mek 的 EnergyInventorySlot 对齐」）。</p>
     */
    private void drainPowerSlot() {
        if (powerSlot == null || energyContainer == null) {
            return;
        }
        ItemStack stack = powerSlot.getStack();
        if (stack.isEmpty() || !PowerSlotUtil.isValidEnergyItem(stack)) {
            return;
        }
        energyContainer.extract(FloatingLong.create(PowerSlotUtil.REDSTONE_PER_TICK),
                Action.EXECUTE, AutomationType.INTERNAL);
    }

    // ── 升级物品判据（旧实现按注册名比对）────────────────────────────────

    public static boolean isSpeedUpgrade(ItemStack stack) {
        return isItem(stack, "mekanism:upgrade_speed");
    }

    public static boolean isEnergyUpgrade(ItemStack stack) {
        return isItem(stack, "mekanism:upgrade_energy");
    }

    public static boolean isCreativeUpgrade(ItemStack stack) {
        return isItem(stack, "mekanism_extras:upgrade_creative");
    }

    /** 任意升级物品；输入槽据此拒绝。 */
    public static boolean isAnyUpgradeItem(ItemStack stack) {
        return isSpeedUpgrade(stack) || isEnergyUpgrade(stack) || isCreativeUpgrade(stack);
    }

    private static boolean isItem(ItemStack stack, String id) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null && key.equals(ResourceLocation.tryParse(id));
    }

    // ── 订单 / 进度（供菜单与 AE2 读）──────────────────────────────────


    public ResourceLocation getOrderRecipeId() {
        return orderRecipeId;
    }

    public int getOrderQuantity() {
        return orderRecipeId == null ? 0 : orderQuantity;
    }

    public int getOrderCompleted() {
        return orderCompleted;
    }

    public boolean hasOrder() {
        return orderRecipeId != null;
    }

    public void setOrder(ResourceLocation recipeId, int quantity) {
        this.orderRecipeId = recipeId;
        this.orderQuantity = recipeId == null ? 0 : Math.max(1, quantity);
        this.orderCompleted = 0;
    }

    public boolean isMeOrderEnabled() {
        return meOrderEnabled;
    }

    public void setMeOrderEnabled(boolean enabled) {
        this.meOrderEnabled = enabled;
    }

    // ── 供外部（AE2 / 升级安装）取用的访问器 ───────────────────────────

    /** 升级组件（父类的 {@code upgradeComponent} 是 protected，只有本类能读）。 */
    public TileComponentUpgrade getUpgradeComponent() {
        return upgradeComponent;
    }

    /**
     * 本机物品槽的容器视图（AE2 自动补料用）—— <b>缓存在字段里</b>。
     *
     * <p>旧实现返回同一个 {@code ItemStackHandler} 字段；这里每次 {@code new} 会让 AE2
     * 在每次拉料时分配一个新 handler，既浪费也让「同一台机器前后拿到不同对象」
     * 这类比较失效。槽位对象在 {@code getInitialInventory} 里建好后就固定了，
     * 所以这里可以安全缓存。</p>
     */
    public net.minecraftforge.items.ItemStackHandler getItems() {
        if (ae2View == null) {
            ae2View = new cn.ism.mekck.machine.MekCkSlotHandler(List.of(inputSlot, outputSlot));
        }
        return ae2View;
    }

    /**
     * 存量能量（FE）。菜单与屏幕读它。
     *
     * <p>Mek 的 {@code getEnergyContainer()} 在接口上是 <b>final</b>、且返回
     * {@code List<IEnergyContainer>}，不适合 GUI 直接用；本方法给出标量视图。</p>
     */
    public FloatingLong getEnergyStored() {
        return energyContainer == null ? FloatingLong.ZERO : energyContainer.getEnergy();
    }

    /** 容量上限（FE）。 */
    public FloatingLong getEnergyCapacity() {
        return FloatingLong.create(ENERGY_CAPACITY);
    }

    /** 无参取本机能量容器 —— 给 GUI 的 GuiVerticalPowerBar 用。
     *  Mek 的 getEnergyContainer(Direction) 是接口上的 final 方法、且要方向参数；
     *  本机没有分面能量，直接返回唯一那只。 */
    public IEnergyContainer getEnergyContainer() {
        return energyContainer;
    }

    // ── 客户端同步镜像（容器同步通道）──────────────────────────────────

    /**
     * 客户端侧的进度镜像 —— 服务端 {@link #progress} 不跨网，客户端那份只作兜底。
     *
     * <p>为什么必须有这个字段：<b>客户端的 tile 上 {@code progress} 永远是 0</b> ——
     * 它只在 {@link #onUpdateServer} 里递增，而那只在服务端跑。GUI 读的
     * {@link #getProgress()} 若直接返回它，进度条就会永远停在 0。
     * 旧实现靠 {@code ContainerData} 同步，Mek 体系下对应的正规入口是
     * {@code addContainerTrackers}（见下）。</p>
     */
    private int clientProgress;
    /** AE2 拉料用的槽位视图缓存（见 {@link #getItems()}）。 */
    private net.minecraftforge.items.ItemStackHandler ae2View;

    /**
     * 把进度挂进 Mek 的容器同步通道 —— <b>不做这件事进度条就永远是 0</b>。
     *
     * <p>这是 Mek 机器同步数据的<b>唯一</b正规入口：{@code MekanismTileContainer.addContainerTrackers()}
     * 会调本方法，{@code MekanismContainer.track} 把条目收进 {@code trackedData}，
     * 由 Mek 自己的容器属性包按脏值增量下发。自己发包要另写一套
     * 「谁在什么时候发、玩家关屏后怎么办」的状态机。</p>
     */
    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(SyncableInt.create(
                () -> getLevel() != null && getLevel().isClientSide ? clientProgress : progress,
                value -> clientProgress = value));
    }

    /** 进度（tick）。服务端读真值、客户端读同步镜像。 */
    public int getProgress() {
        if (getLevel() != null && getLevel().isClientSide) {
            return clientProgress;
        }
        return progress;
    }

}
