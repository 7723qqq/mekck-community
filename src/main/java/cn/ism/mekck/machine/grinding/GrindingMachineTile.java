package cn.ism.mekck.machine.grinding;

import cn.ism.mekck.compat.KaleidoscopeCompat;
import cn.ism.mekck.machine.MekCkLegacyMachineNbt;
import cn.ism.mekck.machine.MekCkNetworkPullableTile;
import cn.ism.mekck.machine.MekCkOrderState;
import cn.ism.mekck.machine.MekCkSlot;
import cn.ism.mekck.menu.slot.MekCkSlots;
import cn.ism.mekck.menu.slot.SlotDef;
import cn.ism.mekck.upgrade.UpgradeHelper;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.util.RecipeCache;
import cn.ism.mekck.util.RecipeInputMatcher;
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
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 电力研磨机（Mek 体系版）—— 处理石磨 / 筛粉 / 绞碎 / mekck 磨粉四类配方。
 *
 * <h3>与旧 {@code ElectricGrindingMachineBlockEntity} 的关系：只换能力层，配方逐字照搬</h3>
 * 四类配方的<b>查找顺序</b>（石磨 → 筛粉 → 绞碎 → mekck 磨粉）、「先命中先用」的短路、
 * 按 id 反查时的类型白名单、输入匹配只看 {@code ingredients[0]}，全部与迁移前逐字同义。
 * 换掉的只是机器能力层：侧配 / 升级 / 红石 / 能量 / 弹出 / 槽位方阵由 Mek 基类提供，
 * 本类不再自建 {@code AutoIO} / {@code SideMode} / {@code ItemStackHandler} / {@code ContainerData}。
 *
 * <h3>为什么继承 {@link TileEntityConfigurableMachine} 而不是 {@code MekCkMachineTile}</h3>
 * 后者是<b>带档位的工厂家族</b>基类（{@code createExecutor()} 抽象、槽位模型建立在
 * {@code CuttingMachineFactoryTier} 上）。本机是单档位单机，硬塞进去要么编一个假 tier、
 * 要么实现一个用不到的执行器。陈化窖、电力烧烤架走的是同一条「直接继承
 * {@code TileEntityConfigurableMachine}」的路，本机照此。
 *
 * <h3>槽位顺序是存档契约</h3>
 * {@code [输入, 输出, 能源]} —— 与迁移前菜单的 addSlot 顺序一致。
 * 旧存档里升级卡槽（2/3/4）的内容由 {@link MekCkLegacyMachineNbt} 迁进 Mek 的
 * {@code TileComponentUpgrade}，不再占物品槽下标。
 */
public final class GrindingMachineTile extends MekCkNetworkPullableTile
        implements net.minecraft.world.MenuProvider {

    /** 输入槽下标（新格式）。 */
    public static final int INPUT_SLOT = 0;
    /** 输出槽下标（新格式）。 */
    public static final int OUTPUT_SLOT = 1;

    /** 单次加工耗时（tick）。与迁移前 {@code PROCESS_TIME} 逐字同值。 */
    public static final int PROCESS_TIME = 200;
    /** 每 tick 基础耗电。与迁移前 {@code ENERGY_PER_TICK} 逐字同值。 */
    public static final int ENERGY_PER_TICK = 20;
    /** 能量容量。与迁移前 {@code ENERGY_CAPACITY} 逐字同值（由方块的 AttributeEnergy 声明）。 */
    public static final long ENERGY_CAPACITY = 100_000L;
    /** 能量最大输入速率。与迁移前 {@code MAX_RECEIVE} 逐字同值（由方块的 AttributeEnergy 声明）。 */
    public static final long MAX_RECEIVE = 1_000L;

    /** 四类配方类型的注册名 —— <b>顺序即优先级，不可重排</b>。 */
    private static final String[] RECIPE_TYPE_IDS = {
            "kaleidoscope_cookery:millstone",
            "bakeries:flour_sieve",
            "farm_and_charm:mincer",
            "mekck:grinding"
    };

    private final MekCkOrderState order = new MekCkOrderState();
    private boolean meOrderEnabled = true;
    private int progress;
    /** 上一 tick 实际扣掉的电量 —— 能源 tab 读数，见 {@link #getLastUsage()}。 */
    private FloatingLong lastUsage = FloatingLong.ZERO;
    private Component customName;

    /** 网络拉料只看输入槽（产物与能源槽不参与）。 */
    @Override
    protected List<IInventorySlot> networkPullSlots() {
        return inputSlot == null ? List.of() : List.of(inputSlot);
    }

    /**
     * 空输入槽时的补料并集必须与本机实际处理的四类配方一致
     * —— 取错了会让抽出来的料被 {@code isItemValid} 拒收、掉在机器旁（物品离开 ME 网络）。
     */
    @Override
    protected List<String> networkPullRecipeTypeIds() {
        return List.of(RECIPE_TYPE_IDS);
    }

    private MekCkSlot inputSlot;
    private MekCkSlot outputSlot;
    private EnergyInventorySlot energySlot;
    private MachineEnergyContainer<GrindingMachineTile> energyContainer;

    public GrindingMachineTile(IBlockProvider blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
    }

    // ==================== 基类钩子（构造期回调，只碰父类字段） ====================

    @Override
    protected void presetVariables() {
        configComponent = new TileComponentConfig(this, TransmissionType.ITEM, TransmissionType.ENERGY);
        ejectorComponent = new TileComponentEjector(this);
    }

    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSideWithConfig(this::getDirection, this::getConfig);
        energyContainer = MachineEnergyContainer.input(this, listener);
        builder.addContainer(energyContainer);
        return builder.build();
    }

    /**
     * 槽位装配 —— 坐标取自 {@link MekCkSlots.GrindingMachine}，本类不写坐标字面量。
     *
     * <p><b>顺序即存档契约</b>：输入 → 输出 → 能源。</p>
     *
     * <p>输入/输出沿用迁移前的 {@code Integer.MAX_VALUE} 单槽容量（本模组的大堆叠行为），
     * 由 {@link #slotLimit()} 读配置提供。</p>
     */
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this::getDirection, this::getConfig);
        SlotDef in = MekCkSlots.GrindingMachine.INPUT;
        SlotDef out = MekCkSlots.GrindingMachine.OUTPUT;

        inputSlot = MekCkSlot.inputFiltered(slotLimit(),
                (stack, type) -> !UpgradeHelper.isUpgrade(stack) && matchesAnyInput(stack),
                listener, in.x(), in.y());
        builder.addSlot(inputSlot);

        outputSlot = MekCkSlot.output(slotLimit(), listener, out.x(), out.y());
        builder.addSlot(outputSlot);

        // 能源槽：坐标 (7,13) 与旧菜单一致，槽型与覆盖图标由 Mek 的 EnergyInventorySlot 承担。
        energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener,
                MekCkSlots.GrindingMachine.POWER.x(), MekCkSlots.GrindingMachine.POWER.y());
        builder.addSlot(energySlot);

        return builder.build();
    }

    /** 输入/输出槽的单槽容量 —— 迁移前是 {@code Integer.MAX_VALUE}（大堆叠）。 */
    private static int slotLimit() {
        return Integer.MAX_VALUE;
    }

    /** 本机支持的升级 —— 与迁移前一致：速度 + 能量。 */
    @Override
    public Set<Upgrade> getSupportedUpgrade() {
        return EnumSet.of(Upgrade.SPEED, Upgrade.ENERGY);
    }

    // ==================== tick ====================

    @Override
    protected void onUpdateServer() {
        super.onUpdateServer();
        Level level = getLevel();
        if (level == null) return;

        cn.ism.mekck.compat.AE2Compat.serverTick(this, level, getBlockPos());

        if (energySlot != null) {
            drainPowerSlot();
        }

        long energyPerTick = energyPerTick();
        Optional<Recipe<?>> recipe = findRecipe(level);
        boolean canRun = MekanismUtils.canFunction(this)
                && recipe.isPresent()
                && energyContainer.getEnergy().compareTo(FloatingLong.create(energyPerTick)) >= 0
                && canFitWorstCase(recipe.get());
        if (canRun) {
            energyContainer.extract(FloatingLong.create(energyPerTick), Action.EXECUTE, AutomationType.INTERNAL);
            lastUsage = FloatingLong.create(energyPerTick);
            progress++;
            if (progress >= PROCESS_TIME) {
                completeRecipe(level, recipe.get());
                progress = 0;
            }
            setChanged();
        } else if (progress != 0) {
            progress = 0;
            lastUsage = FloatingLong.ZERO;
            setChanged();
        } else {
            lastUsage = FloatingLong.ZERO;
        }

        setActive(progress > 0);
    }

    /**
     * 上一 tick 实际消耗的能量 —— 能源 tab 的「使用量」读数。
     *
     * <p>与 {@code MekCkMachineTile.getLastUsage()} / Mek 的
     * {@code TileEntityFactory.getLastUsage()} 同款：上游 {@code GuiElectricMachine} 传给
     * {@code GuiEnergyTab} 的就是这个值（真实的上一 tick 扣电量），而不是声明的耗电速率。</p>
     */
    public FloatingLong getLastUsage() {
        return lastUsage;
    }

    /** 每 tick 耗电 —— 迁移前的 {@code ENERGY_PER_TICK}（速度升级不缩短耗时，故不乘倍率）。 */
    private long energyPerTick() {
        return ENERGY_PER_TICK;
    }

    /** 从能源槽抽能/烧红石入机（迁移前 {@code drainPowerSlot} 的同义实现）。 */
    private void drainPowerSlot() {
        if (energySlot == null || energyContainer == null) return;
        energySlot.fillContainerOrConvert();
    }

    // ==================== 配方查找（语义逐字照搬迁移前） ====================

    private Optional<Recipe<?>> findRecipe(Level level) {
        if (inputSlot == null || inputSlot.getStack().isEmpty()) {
            return Optional.empty();
        }
        // ME 下单：只执行订单指定的配方
        ResourceLocation ordered = order.getRecipeId();
        if (ordered != null) {
            return findRecipeById(level, ordered).filter(this::matchesInput);
        }
        Optional<Recipe<?>> millstone = KaleidoscopeCompat.findMillstoneRecipe(level, inputSlot.getStack());
        if (millstone.isPresent()) return millstone;
        Optional<Recipe<?>> sieve = findFirstMatching(level, "bakeries:flour_sieve");
        if (sieve.isPresent()) return sieve;
        Optional<Recipe<?>> mincer = findFirstMatching(level, "farm_and_charm:mincer");
        if (mincer.isPresent()) return mincer;
        return findFirstMatching(level, "mekck:grinding");
    }

    /** 按配方 id 在四类配方中查找（顺序与 {@link #RECIPE_TYPE_IDS} 一致）。 */
    private Optional<Recipe<?>> findRecipeById(Level level, ResourceLocation id) {
        for (String typeId : RECIPE_TYPE_IDS) {
            RecipeType<?> rt = RecipeCache.type(new ResourceLocation(typeId));
            if (rt == null) continue;
            Optional<? extends Recipe<?>> found = level.getRecipeManager().byKey(id);
            if (found.isPresent() && found.get().getType() == rt) return Optional.of(found.get());
        }
        return Optional.empty();
    }

    private Optional<Recipe<?>> findFirstMatching(Level level, String typeId) {
        RecipeType<?> rt = RecipeCache.type(new ResourceLocation(typeId));
        if (rt == null) return Optional.empty();
        for (Recipe<?> r : RecipeCache.all(level, rt)) {
            if (matchesInput(r)) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** 输入槽物品是否满足该配方（只看第 0 个 Ingredient，与迁移前逐字同义）。 */
    private boolean matchesInput(Recipe<?> recipe) {
        if (inputSlot == null) return false;
        ItemStack input = inputSlot.getStack();
        if (input.isEmpty()) return false;
        return matches(recipe, input);
    }

    /** 输入槽物品能否被四类配方中任一处理（迁移前 isItemValid(INPUT_SLOT) 的四个分支）。 */
    private boolean matchesAnyInput(ItemStack stack) {
        Level level = getLevel();
        if (level == null || stack.isEmpty()) return false;
        return RecipeInputMatcher.matchesMillstone(level, stack)
                || matchesTypeAny(level, "bakeries:flour_sieve", stack)
                || matchesTypeAny(level, "farm_and_charm:mincer", stack)
                || matchesTypeAny(level, "mekck:grinding", stack);
    }

    private boolean matchesTypeAny(Level level, String typeId, ItemStack stack) {
        RecipeType<?> rt = RecipeCache.type(new ResourceLocation(typeId));
        if (rt == null) return false;
        for (Recipe<?> r : RecipeCache.all(level, rt)) {
            if (matches(r, stack)) return true;
        }
        return false;
    }

    private static boolean matches(Recipe<?> recipe, ItemStack stack) {
        try {
            List<Ingredient> ings = recipe.getIngredients();
            return ings.isEmpty() || ings.get(0).test(stack);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 石磨类配方的产出<b>带概率</b>，所以容量判定必须按「最坏情况全命中」预留
     * —— 与研磨工厂逐字同款，共用 {@link GrindingRecipes#canFitWorstCase}。
     *
     * <p>⚠️ 迁移第一版这里用的是 {@code recipe.getResultItem()}（单一定值产物），
     * 那是错的：石磨的产出是 {@code List<MillstoneOutput>}、每项各带一个 chance，
     * 用 getResultItem 会把手里的石磨配方判成「无产物」。</p>
     */
    private boolean canFitWorstCase(Recipe<?> recipe) {
        return GrindingRecipes.canFitWorstCase(List.of(outputSlot), recipe, 1);
    }

    /**
     * 完成一次加工：消耗 1 个输入、掷出产出写入输出槽、推进订单。
     *
     * <p>掷骰与订单推进都走 {@link GrindingRecipes}（与研磨工厂同一份实现）。</p>
     */
    private void completeRecipe(Level level, Recipe<?> recipe) {
        int consumed = GrindingRecipes.consumeInput(inputSlot, 1, order);
        if (consumed <= 0) {
            return;
        }
        GrindingRecipes.rollOutputs(List.of(outputSlot), recipe, consumed, level.random);
        setChanged();
    }

    // ==================== 订单（AE2） ====================

    public ResourceLocation getOrderRecipeId() {
        return order.getRecipeId();
    }

    public int getOrderQuantity() {
        return order.isActive() ? order.getQuantity() : 0;
    }

    public boolean isMeOrderEnabled() {
        return meOrderEnabled;
    }

    public void setMeOrderEnabled(boolean enabled) {
        this.meOrderEnabled = enabled;
        setChanged();
    }

    /** 下单：取消（id 为 null）时清零、激活时夹到 ≥ 1 —— 与 {@link MekCkOrderState#setOrder} 同契约。 */
    public void setOrder(ResourceLocation recipeId, int quantity) {
        order.setOrder(recipeId, quantity);
        setChanged();
    }

    // ==================== 供「本机下单」面板 ====================

    /**
     * 输入槽里的物品能做的<b>全部</b>配方（石磨 / 筛粉 / 绞碎 / mekck 磨粉四类）。
     *
     * <p>与迁移前的同名方法逐字同义：只统计输入匹配的配方，去重后返回。</p>
     */
    public List<Recipe<?>> getAvailableRecipes() {
        Level level = getLevel();
        List<Recipe<?>> out = new java.util.ArrayList<>();
        if (level == null || inputSlot == null) return out;
        ItemStack input = inputSlot.getStack();
        if (input.isEmpty()) return out;
        for (String typeId : RECIPE_TYPE_IDS) {
            RecipeType<?> rt = RecipeCache.type(new ResourceLocation(typeId));
            if (rt == null) continue;
            for (Recipe<?> r : RecipeCache.all(level, rt)) {
                if (matchesInput(r)) out.add(r);
            }
        }
        return out;
    }

    /** 供「本机下单」面板的 Max 按钮：输入槽现有材料能做几份（每份消耗 1 个输入）。 */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        if (recipe == null || inputSlot == null || !matchesInput(recipe)) return 0;
        return Math.max(0, inputSlot.getStack().getCount());
    }

    // ==================== 存档 ====================

    /**
     * 新格式的版本标记。
     *
     * <p><b>这个键必须写出</b>：{@link MekCkLegacyMachineNbt#isLegacy} 的判据就是
     * 「有没有它」。不写 ⇒ 每次读档都被当成旧格式、反复跑一遍迁移器 ——
     * 迁移器是幂等的（键名不重叠），所以症状不是数据损坏，而是
     * 每次区块加载都白跑一趟并可能刷出「槽位数不符」的 WARN。工厂家族写的是同一个键
     * （{@code MekCkMachineTile.TAG_NATIVE_VERSION = "MekCkNative"}），本机沿用同名同值域。</p>
     */
    public static final String TAG_NATIVE_VERSION = "MekCkNative";

    /** 本机的存档格式版本。1 = 迁移前的旧格式（隐含），2 = 迁到 Mek 后的原生格式。 */
    private static final int NATIVE_VERSION = 2;

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        cn.ism.mekck.compat.AE2Compat.saveAdditional(this, tag);
        tag.putInt("Progress", progress);
        order.save(tag);
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        tag.putInt(TAG_NATIVE_VERSION, NATIVE_VERSION);
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        CompoundTag legacy = MekCkLegacyMachineNbt.isLegacy(tag) ? tag : null;
        if (legacy != null) {
            tag = MekCkLegacyMachineNbt.migrate(legacy, getDirection(), 1, TOTAL_SLOTS);
        }
        super.load(tag);
        progress = tag.getInt("Progress");
        order.load(tag);
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        if (tag.contains("CustomName")) {
            customName = Component.Serializer.fromJson(tag.getString("CustomName"));
        }
    }

    /** 新格式的槽位总数 —— 供旧存档迁移器换算下标。 */
    private static final int TOTAL_SLOTS = 3;

    public Component getCustomName() {
        return customName;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
        setChanged();
    }

    // ==================== 客户端读侧（经 Mek 同步通道下发） ====================

    /** 菜单/屏幕读进度（0..PROCESS_TIME）。 */
    public int getProgress() {
        return progress;
    }

    /** 进度总量的读侧 —— 屏幕用它把进度换算成百分比。 */
    public int getProcessTime() {
        return PROCESS_TIME;
    }

    /**
     * 存量（展示用）。
     *
     * <p>Mek 的能量是 {@link FloatingLong}（定点，最大约 4.29e9），而旧菜单/屏幕的
     * 能量条按 {@code int} 读。这里做一次饱和转换——超出 int 的部分对<b>显示</b>无意义，
     * 而本机容量 {@value #ENERGY_CAPACITY} 远在 int 范围内。</p>
     */
    public long getEnergyForDisplay() {
        return energyContainer == null ? 0L : energyContainer.getEnergy().longValue();
    }

    /**
     * 能量容器 —— 供屏幕的能源条直接取用（{@code GuiVerticalPowerBar} 吃它）。
     *
     * <p>与 {@code GrillBlockEntity.getEnergyContainer()} 同款：屏幕拿容器的存量/上限
     * 与 tooltip 全部由 Mek 自己组装，屏幕侧不必再手拼一份 {@code IBarInfoHandler}。</p>
     */
    public MachineEnergyContainer<GrindingMachineTile> getEnergyContainer() {
        return energyContainer;
    }

    /**
     * 打开界面 —— {@code AttributeGui}（方块 {@code withGui(containerRef)}）会走到这里。
     *
     * <p>Mek 的 {@code TileEntityMekanism} 通过 {@code BlockMekanism.use} →
     * {@code AttributeGui.openGui} → 本方法建菜单；这是「方块属性接管右键开界面」的落点，
     * 迁移前是方块自己覆写 {@code use()} 调 {@code NetworkHooks.openScreen}。</p>
     */
    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
            int windowId, net.minecraft.world.entity.player.Inventory inv,
            net.minecraft.world.entity.player.Player player) {
        return new cn.ism.mekck.menu.ElectricGrindingMachineMenu(windowId, inv, this);
    }

    // ==================== 容器同步 ====================

    /**
     * 把进度与订单状态推给客户端。
     *
     * <p><b>这里换掉了旧实现的 {@code ContainerData} + {@code WideDataSlot} 拆位传输</b>：
     * 旧路走 {@code ClientboundContainerSetDataPacket}，对每个值只 {@code writeShort}
     * （16 位有符号），所以能量必须拆成低/高两个槽才不截断。Mek 的
     * {@code SyncableInt} 走 varint 与自定义包，<b>没有 16 位截断</b>，
     * 拆位那套随之作废。</p>
     *
     * <p>能量不在这里同步：它由 Mek 基类自己的能量容器通道路过，屏幕直接问 tile。</p>
     */
    @Override
    public void addContainerTrackers(mekanism.common.inventory.container.MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(mekanism.common.inventory.container.sync.SyncableInt.create(
                this::getProgress, v -> clientProgress = v));
    }

    private int clientProgress;

    public int getClientProgress() {
        return clientProgress;
    }
}
