package cn.ism.mekck.blockentity;

import cn.ism.mekck.MachineKind;
import cn.ism.mekck.machine.MatchedRecipe;
import cn.ism.mekck.RedstoneControl;
import cn.ism.mekck.SideMode;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.SimpleMachineBlock;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.menu.SimpleMachineMenu;
import cn.ism.mekck.util.PowerSlotUtil;
import cn.ism.mekck.upgrade.UpgradeHelper;
import net.minecraft.core.BlockPos;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IMekanismHeatHandler;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import cn.ism.mekck.registry.MekCkLegacyMachines;

/**
 * 四合一基础机器方块实体（寿司卷制机 / 平均切段机 / 饭团成型机 / 凝乳成型机）。
 * <p>
 * 槽位：INPUT_0..4（5 个输入：寿司机 0=底材、1..4=部件；其余机器用 0 即可）、
 * OUTPUT、速度/能量/创造升级、能源。配方按 {@link MachineKind} 分派：
 * <ul>
 *   <li>SUSHI_MAKER：youkaishomecoming:cuisine_ordered/mixed/fixed（智能料理台，底材米饭/海苔机内成型）；</li>
 *   <li>AVERAGE_SLICER：farmersdelight:cutting 中输出含 "_slice" 的切段配方（2×速度、0.5×能耗）；</li>
 *   <li>RICE_BALL_MAKER：farmersdelight:cooking 中结果为 cooked_rice / *_rice_ball 的配方（含做米饭）；</li>
 *   <li>CURD_MAKER：trailandtales_delight:curd_block → cheese_wheel（模拟暗处老化，直接产出）。</li>
 * </ul>
 */
public final class SimpleMachineBlockEntity extends BlockEntity implements MenuProvider, IRedstoneControllable, mekanism.api.heat.IMekanismHeatHandler, cn.ism.mekck.ae2.INetworkPullable, mekanism.common.tile.interfaces.IHasDumpButton {
    // ==================== IMekanismHeatHandler ====================
    // 让本方块实体本身实现 Mekanism 热能力接口，使第三方（如气动工艺的
    // MekanismIntegration.isMekHeatHandler 按 instanceof 判定）能识别本机为热处理器；
    // 带方向的重载全部委托给 MekCkHeatComponent，行为与 capability 暴露一致。

    @Override
    public List<IHeatCapacitor> getHeatCapacitors(@Nullable Direction side) {
        return heatComponent == null ? java.util.Collections.emptyList() : heatComponent.getHeatCapacitors(side);
    }

    @Override
    public boolean canHandleHeat() {
        return isHeatingMachine();
    }

    @Override
    public void onContentsChanged() {
        // IContentsListener 要求的回调；内容变更通知由 MekCkHeatComponent 构造时传入的
        // onChanged（本机 setChanged）负责，此处无需额外处理。
    }


    public static final int INPUT_COUNT = 5;
    public static final int OUTPUT_SLOT = 5;
    public static final int SLOT_SPEED_UPGRADE = 6;
    public static final int SLOT_ENERGY_UPGRADE = 7;
    public static final int SLOT_CREATIVE_UPGRADE = 8;
    public static final int SLOT_POWER = 9;
    /** 扩展输入槽起始索引（搅拌机 blender 使用：9 输入 = 0..4 + 10..13；保持 0..9 旧索引不变以兼容存档）。 */
    public static final int EXT_INPUT_START = 10;
    /** 扩展输入槽数量。 */
    public static final int EXT_INPUT_COUNT = 4;
    public static final int TOTAL_SLOTS = EXT_INPUT_START + EXT_INPUT_COUNT;
    /**
     * 智能陈酿机（winery）专用果汁格索引：复用 winery 空闲的扩展槽 10
     * （winery 不启用扩展输入槽，该索引本就为空 → 不改总槽数、旧存档天然兼容）。
     */
    public static final int JUICE_SLOT = EXT_INPUT_START;
    /**
     * 智能陈酿机（winery）专用返还槽索引：复用 winery 空闲的扩展槽 11（仅陈酿机启用），
     * 收果汁流体桶灌入 fluids.getInputTank() 抽空后返还的空桶（铁桶）。OutputSlot 语义（只出不进）。
     * winery 不启用扩展输入槽 → 该索引本就为空，不改总槽数、旧存档天然兼容。
     */
    public static final int RETURN_SLOT = EXT_INPUT_START + 1;
    /**
     * 智能陈酿机（winery）专用「流体物品输入格」索引：复用 winery 空闲的扩展槽 12。
     * <p><b>已废弃（2026-09-24 用户 GUI 验收裁定）</b>：vinery 陈酿桶背景只有 6 个格位，放不下第 7 格，
     * 流体桶改与 {@link #JUICE_SLOT} 共用一格。索引与总槽数保持不变以兼容旧存档，但该槽不再渲染、也不可放入。
     */
    public static final int FLUID_ITEM_SLOT = EXT_INPUT_START + 2;
    /** winery 复刻 vinery 陈酿桶：配料区 0..2、载重瓶/酒瓶槽 = {@code TavernBarrelPlan.MAX_INGREDIENT_SLOTS}(=3)，槽 4 弃用。 */
    public static final int WINERY_CARRIER_SLOT = 3;
    /** winery 弃用的第 5 通用输入槽（复刻 vinery 只用 3 配料 + 1 瓶，此槽不可放料）。 */
    public static final int WINERY_DEPRECATED_SLOT = 4;

    public static final int ENERGY_CAPACITY = 100_000;
    public static final int MAX_RECEIVE = 1_000;

    public static final int DATA_PROGRESS = 0;
    public static final int DATA_PROCESS_TIME = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_SIDE_CONFIG = 3;
    public static final int DATA_SPEED_UPGRADE = 4;
    public static final int DATA_ENERGY_UPGRADE = 5;
    public static final int DATA_CREATIVE_UPGRADE = 6;
    public static final int DATA_REDSTONE_CONTROL = 7;
    public static final int DATA_INPUT_FLUID = 8;
    public static final int DATA_OUTPUT_FLUID = 9;
    /** 机身温度（单位：0.01 ℃；仅加热类机器有意义）。 */
    public static final int DATA_TEMPERATURE = 10;
    /** 升级安装进度（0~100，供升级界面进度条）。 */
    public static final int DATA_UPGRADE_PROGRESS = 11;
    /** 流体侧面配置编码（每面 4 bit）。 */
    public static final int DATA_FLUID_SIDE_CONFIG = 12;
    /** 陈酿机：果汁液位（0~100；非陈酿机恒为 0）。 */
    public static final int DATA_JUICE_LEVEL = 13;
    /** 陈酿机：果汁类型编号（VineryJuice.TYPES 下标；-1 = 空桶）。 */
    public static final int DATA_JUICE_TYPE = 14;
    /**
     * 陈酿机：fluids.getInputTank() 当前流体的 registry id（空流体为 {@code minecraft:empty}）。
     * <p>必须与 {@link #DATA_INPUT_FLUID}（只给量）配套：本类没覆写任何 BE 同步（getUpdateTag /
     * sendUpdatePacket），客户端那份 fluids.getInputTank() 只在区块加载时随存档到达一次、之后永不更新，
     * 所以 GUI 的液位条光读客户端 BE 永远是空（用户 2026-09-24：抽取已正常但格子里看不到流体）。
     * 做法与 Mekanism 一致：把 FluidStack 挂进容器同步数据口（它用 dynamic container，我们这里用
     * 原版 ContainerData 的 id+量两个 int，与果汁类型 DATA_JUICE_TYPE 同一路子）。</p>
     */
    public static final int DATA_INPUT_FLUID_ID = 15;
    /**
     * {@link #DATA_ENERGY} 的<b>高 16 位</b> —— 能量槽被拆成两个槽传输。
     *
     * <p><b>为什么</b>：原版 {@code ContainerData} 经 {@code ClientboundContainerSetDataPacket}
     * 时对每个值只 {@code writeShort}，<b>16 位有符号</b>，上限 32767；而
     * {@code energy.getEnergyStored()} 上限是 {@link #ENERGY_CAPACITY} = 100_000。
     * 不拆的话 100_000 到客户端变成 {@code (short)0x86A0 = -31072} ⇒ 能源条
     * {@code getLevel()} 为负、条纹显示为空、tooltip 显示负 FE。详见
     * {@link cn.ism.mekck.util.WideDataSlot} 类注释。</p>
     *
     * <p><b>为什么取值是 16（旧 DATA_SIZE）</b>：高位槽一律<b>追加</b>到槽表末尾，
     * 上面所有 {@code DATA_*} 常量与各处 {@code data.get(1)} 字面量下标全部不动 ——
     * 追加不会移位，移位会静默读到别的量。客户端
     * {@code new SimpleContainerData(DATA_SIZE)} 是符号引用，会自动跟着长大。</p>
     */
    public static final int DATA_ENERGY_HI = 16;
    /**
     * 物品/流体侧配编码（6 面 × 4 bit = 24 bit）的高 16 位。
     *
     * <p><b>为什么</b>：侧配编码最大 24 bit，经 {@code ContainerData} 的 16 位有符号通道后只剩低
     * 16 位（前 4 面），WEST/EAST 两面恒被读成 NONE ⇒ 客户端显示与实际不符，且循环配置永远到不了
     * {@code PUSH_OUTPUT}。拆两槽后 6 面全部可读回。见 {@link cn.ism.mekck.util.WideDataSlot}。</p>
     *
     * <p><b>为什么取值是 17/18</b>：一律<b>追加</b>到槽表末尾，现有下标不动。</p>
     */
    public static final int DATA_SIDE_CONFIG_HI = 17;
    public static final int DATA_FLUID_SIDE_CONFIG_HI = 18;
    public static final int DATA_SIZE = 19;


    final MachineKind kind;

    private int progress;

    // ================== 升级读条（Mekanism 式：放入槽位 → 20 tick 读条 → 安装） ==================
    private final cn.ism.mekck.upgrade.MekCkUpgradeTracker speedTracker =
            new cn.ism.mekck.upgrade.MekCkUpgradeTracker(() -> MekckConfig.getBasicSpeedUpgradeMax());
    private final cn.ism.mekck.upgrade.MekCkUpgradeTracker energyTracker =
            new cn.ism.mekck.upgrade.MekCkUpgradeTracker(() -> MekckConfig.getBasicEnergyUpgradeMax());
    private final cn.ism.mekck.upgrade.MekCkUpgradeTracker creativeTracker =
            new cn.ism.mekck.upgrade.MekCkUpgradeTracker(1);
    /** 当前生产配方的身份（用于换配方时重置进度；旧存档缺省为 null）。 */
    private net.minecraft.resources.ResourceLocation currentRecipeId;
    /** WINERY 专属 Tavern 酿造批次（第一阶段：仅持有/保存/加载/只读查询，不参与真实生产）。 */
    private final cn.ism.mekck.blockentity.TavernBrewBatch tavernBatch = new cn.ism.mekck.blockentity.TavernBrewBatch();
    /** 启动事务恢复失败标志：置位后禁止自动重试启动（防止反复扣料），直到玩家干预/重启。 */
    private boolean tavernStartFault = false;
    /** 分装故障（含启动故障）：持久化，重启不自动消失；禁止自动重试分装/启动。 */
    private boolean tavernDispenseFault = false;
    /** 分装冷却（tick）：固定节奏逐瓶，避免每 tick 无限制产出。 */
    private int tavernDispenseCooldown = 0;
    private Component customName;

    // ME 终端下单（AE2 pattern provider 驱动）
    net.minecraft.resources.ResourceLocation orderRecipeId;
    int orderQuantity;
    int orderCompleted;
    /** ME 终端下单开关（关闭后不在 ME 终端显示本机配方）。 */
    private boolean meOrderEnabled = true;
    private RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    private boolean redstonePowered = false;
    private boolean redstonePoweredLastTick = false;
    private boolean pulseRunning = false;

    private final SideMode[] sideConfig = new SideMode[6];

    final ItemStackHandler items = new cn.ism.mekck.util.BigStackItemHandler(TOTAL_SLOTS) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (slot < INPUT_COUNT) {
                // winery 复刻 vinery：第 5 通用输入槽（4）弃用，不放料
                if (kind == MachineKind.WINERY && slot == WINERY_DEPRECATED_SLOT) return false;
                // 配料区 0..2：批次运行/停滞期间**照常放料**（用户 2026-09-24 拍板：酿造中允许放也允许取）。
                // 早先这里有一道 tavernInputLocked()：批次非 IDLE 就把 0..2 一律拒收，而 TavernBrewBatch 的状态
                // 既不同步到客户端也不给任何提示 ⇒ 玩家体感「小麦/冰块放不进输入槽」，日志还整条路径全静默
                // （存档取证：r.-1.-1.mca 里那台 MekckTavernBatch.State=1 / RecipeId=kaleidoscope_tavern:barrel/ice_wine）。
                // 放开是安全的：批次用料在起批瞬间已由 consumeSlots 固化并清空，往 0..2 补料/取料都不影响当前批次，
                // 本批结束 reset→IDLE 后会接着用新备料起下一批。参考实现 vinery 桶确有「酿造中不加料」的语义
                // （addIngredient 里 isBrewing() 直接 return），但它是右键交互且会 tip 提示；我们有真实 GUI 槽，
                // 把锁搬进来就变成了没有出口的静默死锁。
                // 槽 3（容器槽）例外保留：Tavern 模式下只允许放入当前配方要求的合法 carrier。
                if (kind == MachineKind.WINERY && slot == TavernBarrelPlan.MAX_INGREDIENT_SLOTS
                        && !tavernBatch.isIdle() && !isValidTavernCarrier(stack)) {
                    return false; // Tavern 容器槽只接受当前配方要求的合法 carrier
                }
                return !isAnyUpgrade(stack) && acceptsInput(slot, stack);
            }
            // winery 果汁格（10）：一格两用——既收 vinery 瓶装果汁（→液位池），也收带流体的果汁容器桶（→抽入 fluids.getInputTank()）。
            // 两路在 absorbWineryJuice 里按 juiceTypeOf 判定天然互斥，不会争抢同一物品。
            // 放宽到 isWineryJuiceInput：任何被认定为果汁的物品（含果汁名物品、其它模组的果汁流体容器）
            // 都先落到这一格，避免无家可归（用户 2026-09-24：酒馆流体桶放不进流体输入格）。
            if (kind == MachineKind.WINERY && slot == JUICE_SLOT) {
                boolean juiceOk = !isAnyUpgrade(stack)
                        && (cn.ism.mekck.util.VineryJuice.juiceTypeOf(stack) != null
                        || isFluidContainerItem(stack) || isWineryJuiceInput(stack));
                if (!juiceOk) noteJuiceReject(slot, stack, "准入：果汁格三判定全不命中");
                return juiceOk;
            }
            // winery 流体物品输入格（12）：已废弃——流体桶改与果汁格（10）共用一格（用户 2026-09-24 GUI 验收裁定）。
            // 索引保留以兼容旧存档与 TOTAL_SLOTS 约定，但不再渲染也不可放入；旧存档残留由玩家自行取回。
            if (kind == MachineKind.WINERY && slot == FLUID_ITEM_SLOT) {
                noteJuiceReject(slot, stack, "准入：已废弃的流体格(12)");
                return false;
            }
            if (slot >= EXT_INPUT_START && slot < EXT_INPUT_START + EXT_INPUT_COUNT) {
                // 扩展输入槽：搅拌机（9 槽）与智能烤炉（支持 6 输入的烤制/煮茶/烹饪配方）可用
                return usesExtendedInputSlots() && !isAnyUpgrade(stack) && acceptsInput(slot, stack);
            }
            if (slot == OUTPUT_SLOT) return false;
            if (slot == SLOT_SPEED_UPGRADE) return UpgradeHelper.isSpeedUpgrade(stack);
            if (slot == SLOT_ENERGY_UPGRADE) return UpgradeHelper.isEnergyUpgrade(stack);
            if (slot == SLOT_CREATIVE_UPGRADE) return UpgradeHelper.isCreativeUpgrade(stack);
            if (slot == SLOT_POWER) return PowerSlotUtil.isValidEnergyItem(stack);
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot < INPUT_COUNT || slot == OUTPUT_SLOT
                    || (slot >= EXT_INPUT_START && slot < EXT_INPUT_START + EXT_INPUT_COUNT)) {
                return Integer.MAX_VALUE;
            }
            if (slot == SLOT_CREATIVE_UPGRADE) return 1;
            if (slot == SLOT_POWER) return 64;
            return MekckConfig.getBasicSpeedUpgradeMax();
        }

        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            if (slot < INPUT_COUNT || slot == OUTPUT_SLOT || slot == SLOT_CREATIVE_UPGRADE
                    || (slot >= EXT_INPUT_START && slot < EXT_INPUT_START + EXT_INPUT_COUNT)) {
                return getSlotLimit(slot);
            }
            return super.getStackLimit(slot, stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final EnergyStorage energy = new EnergyStorage(ENERGY_CAPACITY, MAX_RECEIVE, 0) {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = super.receiveEnergy(maxReceive, simulate);
            if (!simulate && received > 0) setChanged();
            return received;
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        @Override
        public boolean canExtract() {
            return false;
        }
    };

    // ── 陈酿机专属：葡园酒香发酵桶的「果汁液位池」 ──
    // 原版 FermentationBarrel 不把果汁当物品/流体，而是桶内两项状态：juiceType（类型字符串）+ fluidLevel（液位）。
    // 玩家把果汁物品放进桶的投料槽，每件充 JUICE_PER_ITEM 液位（上限 JUICE_MAX_LEVEL），
    // 配方按 getJuiceAmount() 扣液位。数值取自 vinery 配置默认值 maxFluidPerJuice=25 / maxFluidLevel=100。
    static final int JUICE_PER_ITEM = 25;
    static final int JUICE_MAX_LEVEL = 100;
    /** 桶内果汁液位（0..JUICE_MAX_LEVEL）。 */
    int juiceLevel = 0;
    /** 桶内果汁类型（空串 = 未装果汁）。 */
    String juiceType = "";

    /** 物品主动输入输出（抽取至输入格 / 弹出产物），与工厂类机器的 AutoIO 一致。 */
    private final cn.ism.mekck.util.AutoIO itemAutoIO;

    private LazyOptional<IItemHandler> fullItemCapability;
    private LazyOptional<IItemHandler> inputItemCapability;
    private LazyOptional<IItemHandler> outputItemCapability;
    private LazyOptional<IEnergyStorage> energyCapability;

    /** 流体子系统（两个罐 + capability + 侧配 + 自动 IO）。初始化见构造器。 */
    final SimpleMachineFluids fluids;

    /** 配方适配器族（见 {@link SimpleMachineRecipes}）。 */
    final SimpleMachineRecipes recipes;

    /** 陈酿机是否正处于酒馆批次陈化中（只有这种状态下要接管进度柱）。 */
    private boolean tavernBatchDrivesBar() {
        return kind == MachineKind.WINERY && tavernBatch.isBrewing();
    }

    /**
     * {@code DATA_PROGRESS} 的上报值：酒馆批次陈化中改报**整批陈化已走 tick**。
     * <p>陈酿机只有「一根柱子」可用：vinery 皮肤下那根陈酿桶竖直进度柱（简报 §七）与机甲风的 Mekanism
     * 横向箭头都只读 {@code menu.getProgress()}（= {@code DATA_PROGRESS * 24 / DATA_PROCESS_TIME}），
     * 而普通配方路径与批次路径天然互斥（批次活跃时 {@code progress} 恒为 0、不会争同一个槽），
     * ⇒ 把这对照射给批次即可，客户端一行不改、不需新增数据槽（用户 2026-09-25 工单：
     * 「酒馆酿造模式时没有进度条而葡园酒香有，让酒馆模式也用葡园酒香的进度条」）。</p>
     */
    private int containerProgress() {
        return tavernBatchDrivesBar() ? tavernBatch.getBrewElapsedTicks() : progress;
    }

    /** {@code DATA_PROCESS_TIME} 的上报值：与 {@link #containerProgress()} 配对的分母（整批总时长，不吃速度升级倍率）。 */
    private int containerProcessTime() {
        return tavernBatchDrivesBar() ? tavernBatch.getBrewTotalTicks() : getEffectiveProcessTime();
    }

    private final ContainerData data = new ContainerData() {
        private int[] stored;

        private int[] getStored() {
            if (stored == null) stored = new int[DATA_SIZE];
            return stored;
        }

        @Override
        public int get(int index) {
            if (level != null && level.isClientSide) {
                if (stored != null && index >= 0 && index < stored.length) return stored[index];
                return 0;
            }
            int value = switch (index) {
                case DATA_PROGRESS -> containerProgress();
                case DATA_PROCESS_TIME -> containerProcessTime();
                // 能量拆两槽：writeShort 只送低 16 位且会符号扩展，见 WideDataSlot。
                case DATA_ENERGY -> energy.getEnergyStored() & 0xFFFF;
                case DATA_ENERGY_HI -> (energy.getEnergyStored() >>> 16) & 0xFFFF;
                case DATA_SIDE_CONFIG -> encodeSideConfig() & 0xFFFF;
                case DATA_SIDE_CONFIG_HI -> (encodeSideConfig() >>> 16) & 0xFFFF;
                case DATA_SPEED_UPGRADE -> getSpeedUpgradeCount();
                case DATA_ENERGY_UPGRADE -> getEnergyUpgradeCount();
                case DATA_CREATIVE_UPGRADE -> hasCreativeUpgrade() ? 1 : 0;
                case DATA_REDSTONE_CONTROL -> redstoneControl.ordinal();
                case DATA_INPUT_FLUID -> fluids.getInputTank().getFluidAmount();
                case DATA_INPUT_FLUID_ID -> net.minecraft.core.registries.BuiltInRegistries.FLUID
                        .getId(fluids.getInputTank().getFluid().getFluid());
                case DATA_OUTPUT_FLUID -> fluids.getOutputTank().getFluidAmount();
                case DATA_TEMPERATURE -> (int) Math.round((getTemperatureK() - 273.15) * 100.0);
                case DATA_UPGRADE_PROGRESS -> (int) Math.round(getUpgradeInstallProgress() * 100.0);
                case DATA_FLUID_SIDE_CONFIG -> encodeSideConfig(fluids.getFluidSideConfig()) & 0xFFFF;
                case DATA_FLUID_SIDE_CONFIG_HI -> (encodeSideConfig(fluids.getFluidSideConfig()) >>> 16) & 0xFFFF;
                case DATA_JUICE_LEVEL -> juiceLevel;
                case DATA_JUICE_TYPE -> cn.ism.mekck.util.VineryJuice.indexOf(juiceType);
                default -> 0;
            };
            getStored()[index] = value;
            return value;
        }

        @Override
        public void set(int index, int value) {
            getStored()[index] = value;
            // 批次陈化期间 DATA_PROGRESS 装的是整批陈化量，不是普通配方的 progress ⇒ 不回写客户端 BE 副本
            if (index == DATA_PROGRESS && !tavernBatchDrivesBar()) progress = value;
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    /** 热组件（仅加热类机器使用：烘焙机 / 智能烤炉 / 智能蒸箱 / 食品脱水机）。 */
    private final cn.ism.mekck.util.MekCkHeatComponent heatComponent;
    private final LazyOptional<mekanism.api.heat.IHeatHandler> heatCapability;

    public SimpleMachineBlockEntity(BlockPos pos, BlockState state) {
        super(MekCkLegacyMachines.SIMPLE_MACHINE_BLOCK_ENTITY.get(), pos, state);
        this.kind = state.getBlock() instanceof cn.ism.mekck.block.SimpleMachineBlock sb ? sb.getKind() : MachineKind.SUSHI_MAKER;
        for (int i = 0; i < 6; i++) sideConfig[i] = SideMode.NONE;
        this.heatComponent = new cn.ism.mekck.util.MekCkHeatComponent(this::getLevel, this::getBlockPos, this::setChanged);
        this.heatCapability = LazyOptional.of(heatComponent::getHandler);
        this.fullItemCapability = LazyOptional.of(() -> items);
        this.inputItemCapability = LazyOptional.of(() -> new InputItemHandler());
        this.outputItemCapability = LazyOptional.of(() -> new OutputItemHandler());
        this.energyCapability = LazyOptional.of(() -> energy);

        // 物品主动 IO：抽取范围为有效输入槽（含扩展槽），弹出范围为产物槽
        this.fluids = new SimpleMachineFluids(this);
        this.recipes = new SimpleMachineRecipes(this);
        this.itemAutoIO = new cn.ism.mekck.util.AutoIO(this,
                new int[][]{{0, INPUT_COUNT}, {EXT_INPUT_START, EXT_INPUT_COUNT}},
                new int[][]{{OUTPUT_SLOT, 1}});
        // 陈酿机小灶（用户 2026-09-24）：侧面 IO 的自动输入按「有糖/无糖 + 两格存量」分流果汁。
        // 只在 winery 注册接管器，其余机器的主动拉入路径一行未变。
        if (kind == MachineKind.WINERY) this.itemAutoIO.setPullTarget(this::autoInputTargetSlot);
    }

    public MachineKind getKind() {
        return kind;
    }

    /** 是否为加热类机器（运行时按电阻型加热器比例产热，并与 Mekanism 热力设备传导）。 */
    public boolean isHeatingMachine() {
        return kind == MachineKind.BAKERY_OVEN || kind == MachineKind.STOVE
                || kind == MachineKind.STEAMER || kind == MachineKind.DEHYDRATOR;
    }

    /** 当前机身温度（开尔文，仅加热类机器有效）。 */
    public double getTemperatureK() {
        return heatComponent.getTemperature();
    }

    // ================== 输入判定 ==================

    private boolean acceptsInput(int slot, ItemStack stack) {
        if (stack.isEmpty()) return true;
        // 升级物品一律不允许进输入槽
        if (isAnyUpgrade(stack)) return false;
        switch (kind) {
            case CURD_MAKER -> {
                // F9：凝乳成型机除 meadow / 樱途旅事凝乳外，兼容 createcafe 的 create:compacting（粉/团 → 定型半成品）。
                return isCurdInput(stack) || isCompactingInput(stack);
            }
            case SUSHI_MAKER -> {
                // 底材槽：熟米饭 / 寿司底材物品；部件槽：宽松放行（由配方匹配校验）
                return true;
            }
            case RICE_BALL_MAKER, AVERAGE_SLICER, DEHYDRATOR, STEAMER -> {
                return true;
            }
            case FERMENTER -> {
                return true;
            }
            case WINERY -> {
                // 果汁原则上只能进果汁格（JUICE_SLOT）；普通材料格 0..4 拒收果汁（用户 2026-09-22 验收要求）。
                // 但“一刀切”会误杀**双重身份**的果汁：vinery 官方配方 `wine_fermentation/apple_wine.json` 同时要求
                // `juice{type:apple, amount:15}`（液位）**与** `ingredients:[{item:"vinery:apple_juice"}]`（物品配料），
                // 苹果汁进不了配料格 ⇒ 苹果酒永远凑不齐（用户 2026-09-24）。改为：只要本机确实有陈酿配方
                // 把它当 ingredient，就允许它进配料区；其余纯液位型果汁（红/白葡萄汁等）仍只走果汁格。
                if (isWineryJuiceInput(stack) && !machineInputMatches(stack)) {
                    noteJuiceReject(slot, stack, "准入：果汁被配料格拒收（无配方把它当配料）");
                    return false;
                }
                // 载具（vinery 空酒瓶 / 森罗酒馆 empty_bottle）只准进酒瓶格（槽 3），配料区 0..2 一律拒收
                // ——用户 2026-09-24 验收要求。早先为了让瓶子能放进去，这里对 vinery:wine_bottle 开过
                // “无条件放行到任意槽”的后门，瓶子因此漏进配料格；现改为按槽位角色收人。
                if (isWineryCarrierItem(stack)) return slot == WINERY_CARRIER_SLOT;
                // 反向亦成立：酒瓶格（槽 3）只收载具，普通配料请放配料区 0..2（两格职责互斥）
                if (slot == WINERY_CARRIER_SLOT) {
                    noteJuiceReject(slot, stack, "准入：果汁被酒瓶格(3)拒收");
                    return false;
                }
                if (!machineInputMatches(stack)) {
                    // 普通材料（小麦/冰块之类）被拒时 noteJuiceReject 因 looksRelevant 过滤会整个静默 ⇒ 另走通用诊断
                    noteInputReject(slot, stack, "陈酿：无配方把它当配料");
                    return false;
                }
                return true;
            }
            case JUICER -> {
                // 简报需求2 §2.2：仅放行本机可用配方（apple_mashing/apple_fermenting/grape_pressing/pressing_tub）的 ingredient
                // 载具例外：vinery 的 apple_fermenting 把酒瓶存在 requiresBottle() 布尔里（javap 证实不在 ingredients 中），
                // 通用扫描识别不到 → 不显式放行就永远凑不齐瓶子（简报 §六③）。仅当确实存在「要瓶」的发酵配方时才放行。
                if (isVineryWineBottle(stack)) return juicerHasBottleRequiringRecipe();
                if (!machineInputMatches(stack)) {
                    noteInputReject(slot, stack, "榨汁：无配方把它当配料");
                    return false;
                }
                return true;
            }
        }
        return true;
    }

    /**
     * 陈酿机的「载具」物品：{@code vinery:wine_bottle}（vinery 配方的 wine_bottle 独立字段）
     * ∪ 森罗酒馆 barrel 配方的 {@code carrier}（{@code kaleidoscope_tavern:empty_bottle}）。
     * <p>
     * 两类配方都把载具存在 {@code getIngredients()} **之外**（vinery 是 {@code isWineBottleRequired()} 布尔、
     * tavern 是 {@code carrier()} 字段），所以不能靠 {@link #machineInputMatches} 识别。
     * 早先正因为识别不了，才要么给它开“任意槽放行”的后门，要么让酒瓶格根本放不进瓶子——
     * 批次启动后因缺容器长挂，连带锁死配料区与流体抽取（用户 2026-09-24 反馈的 2/3/4 条同源）。
     * </p>
     */
    private boolean isWineryCarrierItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        // vinery 酒瓶的判定与榨汁机载具共用同一个单一口径（见 {@link #isVineryWineBottle}），避免两处各写一份漂移
        if (isVineryWineBottle(stack)) return true;
        return cn.ism.mekck.compat.TavernBarrelCompat.isCarrier(level, stack);
    }

    /** 简报需求2：该物品是否命中本机 {@link #allRecipesOfKind()} 任一配方的 ingredient（WINERY 另含 tavern barrel 配料）；判定异常时保守放行。 */
    boolean machineInputMatches(ItemStack stack) {
        if (stack.isEmpty()) return true;
        try {
            for (Recipe<?> r : allRecipesOfKind()) {
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty() && ing.test(stack)) return true;
                }
            }
            // 森罗酒馆的 barrel 配料：它的 RecipeType 是从不进注册表的匿名对象（javap 证实），按 id 查注册表
            // 永远拿不到 ⇒ 这一段整块落空 ⇒ 酒馆材料一律进不了输入槽（用户 2026-09-24）。改走
            // TavernBarrelCompat 的统一解析与缓存，与「流体是否参与陈酿」同源、单一口径。
            if (kind == MachineKind.WINERY && cn.ism.mekck.compat.TavernBarrelCompat.hasIngredient(level, stack)) return true;
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    public static boolean isAnyUpgrade(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (cn.ism.mekck.item.ColdBrewUpgradeItem.getTier(stack) != null) return true;
        if (cn.ism.mekck.item.FerreroUpgradeItem.getTier(stack) != null) return true;
        return UpgradeHelper.isUpgrade(stack);
    }

    private static boolean isCurdInput(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        // 樱途旅事凝乳块
        if ("trailandtales_delight:curd_block".equals(id.toString())
                || "trailandtales_delight:cherry_curd_block".equals(id.toString())) {
            return true;
        }
        // 青青草甸奶酪：凝乳酶 + 奶桶（含 meadow:milk 标签）
        if ("meadow".equals(id.getNamespace())
                && ("rennet".equals(id.getPath()) || id.getPath().contains("milk_bucket"))) {
            return true;
        }
        return stack.is(net.minecraft.tags.ItemTags.create(new ResourceLocation("meadow", "milk")));
    }

    /** F9：该物品是否为某条 {@code createcafe:} 的 {@code create:compacting} 配方输入（放行入输入槽）。 */
    private boolean isCompactingInput(ItemStack stack) {
        if (level == null || stack.isEmpty()) return false;
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("create", "compacting"));
        if (rt == null) return false;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                ResourceLocation rid = r.getId();
                if (rid == null || !"createcafe".equals(rid.getNamespace())) continue;
                for (Ingredient ing : r.getIngredients()) {
                    if (!ing.isEmpty() && ing.test(stack)) return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ================== 升级 ==================

    /** 已安装的速度升级数量（读条完成后生效，与槽位物品数量区分）。 */
    public int getSpeedUpgradeCount() {
        return speedTracker.getInstalled();
    }

    public int getEnergyUpgradeCount() {
        return energyTracker.getInstalled();
    }

    public boolean hasCreativeUpgrade() {
        return creativeTracker.getInstalled() > 0;
    }

    /** 升级安装进度（0~1，供 GUI 进度条）。 */
    public double getUpgradeInstallProgress() {
        return Math.max(speedTracker.getProgress(),
                Math.max(energyTracker.getProgress(), creativeTracker.getProgress()));
    }

    /**
     * 卸载升级（复刻 Mekanism removeUpgrade）：从已安装数量扣除，并把升级物品放回升级槽
     * （槽空或同种物品时），放不下则尝试产物槽；均放不下则不卸载。
     *
     * @param mode 0 = 卸载 1 个；1 = 卸载全部；2 = 卸载指定槽位（slot = 升级槽索引）
     * @param slot mode 2 时的升级槽物品槽索引
     */
    public void uninstallUpgrade(byte mode, int slot) {
        int upgradeSlot;
        cn.ism.mekck.upgrade.MekCkUpgradeTracker tracker;
        if (mode == 2) {
            upgradeSlot = slot;
            tracker = trackerForSlot(slot);
        } else {
            upgradeSlot = SLOT_SPEED_UPGRADE;
            tracker = speedTracker;
        }
        if (tracker == null) return;
        int installed = tracker.getInstalled();
        if (installed <= 0) return;
        int amount = mode == 1 ? installed : 1;
        net.minecraft.world.item.Item upgradeItem = upgradeItemForSlot(upgradeSlot);
        if (upgradeItem == null || upgradeItem == net.minecraft.world.item.Items.AIR) return;
        ItemStack give = new ItemStack(upgradeItem, amount);
        // 放回升级槽（空或同种）
        ItemStack inSlot = items.getStackInSlot(upgradeSlot);
        int canPlace;
        if (inSlot.isEmpty()) {
            canPlace = Math.min(amount, give.getMaxStackSize());
        } else if (ItemStack.isSameItemSameTags(inSlot, give)) {
            canPlace = Math.min(amount, give.getMaxStackSize() - inSlot.getCount());
        } else {
            canPlace = 0;
        }
        if (canPlace <= 0) return;
        int removed = tracker.uninstall(canPlace);
        if (removed <= 0) return;
        if (inSlot.isEmpty()) {
            items.setStackInSlot(upgradeSlot, new ItemStack(give.getItem(), removed));
        } else {
            inSlot.grow(removed);
        }
        setChanged();
    }

    /** 槽位 → 升级读条组件（非升级槽返回 null）。 */
    private cn.ism.mekck.upgrade.MekCkUpgradeTracker trackerForSlot(int slot) {
        if (slot == SLOT_SPEED_UPGRADE) return speedTracker;
        if (slot == SLOT_ENERGY_UPGRADE) return energyTracker;
        if (slot == SLOT_CREATIVE_UPGRADE) return creativeTracker;
        return null;
    }

    /** 槽位 → 对应的升级物品。 */
    private net.minecraft.world.item.Item upgradeItemForSlot(int slot) {
        if (slot == SLOT_SPEED_UPGRADE) {
            return net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    new ResourceLocation("mekanism", "upgrade_speed"));
        }
        if (slot == SLOT_ENERGY_UPGRADE) {
            return net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    new ResourceLocation("mekanism", "upgrade_energy"));
        }
        if (slot == SLOT_CREATIVE_UPGRADE) {
            return net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    new ResourceLocation("mekanism", "upgrade_creative"));
        }
        return null;
    }

    public int addUpgradesFromHand(ItemStack held) {
        return UpgradeHelper.install(items, SLOT_SPEED_UPGRADE, MekckConfig.getBasicSpeedUpgradeMax(),
                SLOT_ENERGY_UPGRADE, MekckConfig.getBasicEnergyUpgradeMax(), -1, 0, SLOT_CREATIVE_UPGRADE, held);
    }

    public double getEffectiveSpeedMultiplier() {
        return cn.ism.mekck.upgrade.UpgradeHelper.speedMultiplier(getSpeedUpgradeCount());
    }

    public double getEffectiveEnergyConsumptionMultiplier() {
        return cn.ism.mekck.upgrade.UpgradeHelper.energyConsumptionMultiplier(getEnergyUpgradeCount());
    }

    public int getEffectiveProcessTime() {
        return Math.max(1, (int) (kind.processTime / getEffectiveSpeedMultiplier()));
    }

    public int getEnergyPerTickBase() {
        return kind.energyPerTick;
    }

    // ================== 红石控制 ==================

    @Override
    public RedstoneControl getRedstoneControl() {
        return redstoneControl;
    }

    @Override
    public void setRedstoneControl(RedstoneControl control) {
        if (control == null) control = RedstoneControl.DISABLED;
        this.redstoneControl = control;
        setChanged();
    }

    public void updateRedstone() {
        this.redstonePoweredLastTick = this.redstonePowered;
        this.redstonePowered = level != null && level.hasNeighborSignal(this.worldPosition);
    }

    public boolean canFunctionRedstone() {
        if (redstoneControl == RedstoneControl.PULSE) return pulseRunning;
        return redstoneControl.canFunction(redstonePowered, redstonePoweredLastTick);
    }

    // ================== 能源槽位 ==================

    public int getPowerSlot() {
        return SLOT_POWER;
    }

    public boolean drainPowerSlot() {
        ItemStack powerStack = items.getStackInSlot(SLOT_POWER);
        return PowerSlotUtil.drain(powerStack, energy, PowerSlotUtil.REDSTONE_PER_TICK);
    }

    public static boolean isUsablePowerItem(ItemStack stack) {
        return PowerSlotUtil.isValidEnergyItem(stack);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // AE2 网格节点销毁（未安装 AE2 时为空操作；节点 NBT 由 saveAdditional 保存，重载后 init 重建）
        cn.ism.mekck.compat.AE2Compat.onRemoved(this);
    }

    /** 当前机器类型（供方块交互等只读判断）。 */
    public MachineKind getMachineKind() {
        return kind;
    }

    /** 陈酿机：当前果汁液位（非陈酿机恒为 0）。 */
    public int getJuiceLevel() {
        return juiceLevel;
    }

    /** 陈酿机：清空果汁液位池（复刻原版发酵桶潜行右键清桶）。返回是否真的清掉了东西。 */
    public boolean clearJuicePool() {
        if (kind != MachineKind.WINERY || (juiceLevel == 0 && (juiceType == null || juiceType.isEmpty()))) {
            return false;
        }
        juiceLevel = 0;
        juiceType = "";
        setChanged();
        return true;
    }

    /**
     * 实现 Mekanism {@link mekanism.common.tile.interfaces.IHasDumpButton}：供 Metallurgic Infuser 同款
     * 清空按钮（{@code GuiDumpButton}）复用。除清空果汁液位池外，**还排空 fluids.getInputTank()**。
     * <p>
     * 排罐是本机必需的自救手段（用户 2026-09-24）：果汁格抽液要求罐内为空或同种流体（{@code FluidTank} 不混装），
     * 一旦误投一桶水就会把整罐占住、后续果汁桶永远抽不动。而本机的流体入口只有「往里灌」一条路
     * （{@link #tryInsertHeldFluid} 与侧面 PULL），**没有任何把罐内流体取回容器的反向操作**——
     * 即使像森罗酒馆那样确实有装该流体的桶（实测 {@code kaleidoscope_tavern:ice_grape_bucket}），它也只会往里灌。
     * 所以玩家本身仍无法把罐内流体弄出去（除非去改侧面配置接管道），必须留一个 dump。
     * <p>
     * ⚠ 与 Mekanism dump 一致：罐内流体直接丢弃。但 Tavern 批次活跃（BREWING / STALLED）时**只清液位池、不动罐**，
     * 避免误毁批次正在用的 4000mB 母液。潜行右键另一条路径（{@link #clearJuicePool()}）仍只清液位池。
     * <p>
     * 入口：GUI 上的 {@code MekCkWineryDumpButton} 发 {@code WineryClearJuicePacket}，服务端已改调本方法
     * （早期只调 {@link #clearJuicePool()} ⇒ 排罐逻辑无入口）。
     */
    @Override
    public void dump() {
        boolean did = clearJuicePool();
        if (kind == MachineKind.WINERY && !tavernBatch.isBrewing() && !tavernBatch.isStalled()
                && !fluids.getInputTank().isEmpty()) {
            fluids.getInputTank().setFluid(net.minecraftforge.fluids.FluidStack.EMPTY);
            did = true;
        }
        if (did) setChanged();
    }

    /**
     * 手持流体容器（桶/罐）对 winery 方块右键：把其内流体灌进内部 fluids.getInputTank()（复刻 Mekanism「手持流体桶右键灌机器」）。
     * 全程 simulate 预演；成功后空容器（crafting remainder，如铁桶）放回玩家手（创造模式不返还）。非 winery 返回 false。
     */
    public boolean tryInsertHeldFluid(ItemStack held, Player player, InteractionHand hand) {
        if (kind != MachineKind.WINERY || held == null || held.isEmpty() || level == null || level.isClientSide) return false;
        var capOpt = held.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER_ITEM, null);
        if (!capOpt.isPresent()) return false;
        net.minecraftforge.fluids.capability.IFluidHandlerItem handler = capOpt.resolve().orElse(null);
        if (handler == null) return false;
        net.minecraftforge.fluids.FluidStack sim = handler.drain(Integer.MAX_VALUE,
                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);
        if (sim == null || sim.isEmpty()) return false;
        int filled = fluids.getInputTank().fill(sim, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);
        if (filled <= 0) return false;
        net.minecraftforge.fluids.FluidStack real = handler.drain(filled,
                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        int done = fluids.getInputTank().fill(real, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        if (done <= 0) return false;
        ItemStack remainder = handler.getContainer();
        if (!player.isCreative() && remainder != null && !remainder.isEmpty()) {
            player.setItemInHand(hand, remainder);
        }
        setChanged();
        return true;
    }

    /** WINERY 专属：Tavern 酿造批次只读入口（第一阶段不参与生产；null 安全供其他 kind）。 */
    public cn.ism.mekck.blockentity.TavernBrewBatch getTavernBatch() {
        return tavernBatch;
    }

    public ItemStackHandler getItems() {
        return items;
    }

    // ================== AE2 通用网络拉料 ==================

    @Override
    public BlockEntity getNetworkPullable() {
        return this;
    }

    @Override
    public int[] getInputSlotRange() {
        // winery 复刻 vinery：只拉配料 0..2 + 载重瓶 3（槽 4 弃用）；[start,end) 开区间。
        if (kind == MachineKind.WINERY) return new int[]{0, WINERY_CARRIER_SLOT + 1};
        return new int[]{0, INPUT_COUNT};
    }

    /**
     * 启用扩展输入槽的机器（搅拌机 / 智能烤炉 / 智能料理台 / 发酵机）额外声明 10..13。
     *
     * <p>这两段不连续（5..9 是产物 / 速度 / 能量 / 创造 / 电力槽），所以不能用 {@code {0,14}} 表达 ——
     * 见 {@link cn.ism.mekck.ae2.INetworkPullable#getExtraInputSlots()}。</p>
     */
    @Override
    public int[] getExtraInputSlots() {
        if (!usesExtendedInputSlots()) return new int[0];
        int[] slots = new int[EXT_INPUT_COUNT];
        for (int i = 0; i < EXT_INPUT_COUNT; i++) {
            slots[i] = EXT_INPUT_START + i;
        }
        return slots;
    }

    @Override
    public net.minecraftforge.items.ItemStackHandler getNetworkPullItems() {
        return items;
    }

    @Override
    public boolean supportsAutoPull() {
        // 简单单输入配方机器才支持勾选持续自动补料
        return switch (kind) {
            case AVERAGE_SLICER, CURD_MAKER, DEHYDRATOR, STEAMER, JUICER, TEA_BREWER -> true;
            default -> false;
        };
    }

    @Override
    public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {
        if (level == null) return List.of();
        return switch (kind) {
            case AVERAGE_SLICER -> simpleSingleInput("farmersdelight", "cutting",
                    r -> cuttingResults(r).stream().anyMatch(s -> {
                        net.minecraft.resources.ResourceLocation rid = ForgeRegistries.ITEMS.getKey(s.getItem());
                        return rid != null && rid.getPath().contains("_slice");
                    }));
            case CURD_MAKER -> curdPullInputs();
            case DEHYDRATOR -> simpleSingleInput("youkaishomecoming", "drying_rack", r -> true);
            case STEAMER -> simpleSingleInput("youkaishomecoming", "steaming", r -> true);
            case JUICER -> juicerPullInputs();
            case RICE_BALL_MAKER -> ricePullInputs();
            case SUSHI_MAKER -> sushiPullInputs();
            case WINERY -> wineryPullInputs();
            case BAKERY_OVEN -> multiIngredient("bakery", "baking_station", r -> true);
            case STOVE -> multiIngredient("farm_and_charm", "stove", r -> true);
            case FERMENTER -> fermenterPullInputs();
            case COCKTAIL_SHAKER -> multiIngredient("kaleidoscope_tavern", "shaker", r -> true);
            case BLENDER -> multiIngredient("bakeries", "blender", r -> true);
            case TEA_BREWER -> teaPullInputs();
            case SMART_EXTRACTOR -> java.util.List.of();
            case BEVERAGE_BLENDER -> java.util.List.of();
            case PACKAGING_STATION -> java.util.List.of();
        };
    }

    /** 简单单输入：槽 0 已放料则取该配方第一个成分；空槽则取所有可处理配方的并集（一次拉任一）。 */
    private List<cn.ism.mekck.util.AE2InputSpec> simpleSingleInput(String ns, String path, java.util.function.Predicate<Recipe<?>> filter) {
        RecipeType<?> rt = recipeTypeOf(new ResourceLocation(ns, path));
        if (rt == null) return List.of();
        List<Recipe<?>> recipes = cn.ism.mekck.util.RecipeCache.all(level, rt);
        ItemStack slot0 = items.getStackInSlot(0);
        if (!slot0.isEmpty()) {
            for (Recipe<?> r : recipes) {
                if (!filter.test(r)) continue;
                List<Ingredient> ings = r.getIngredients();
                if (!ings.isEmpty() && !ings.get(0).isEmpty() && ings.get(0).test(slot0)) {
                    return List.of(new cn.ism.mekck.util.AE2InputSpec(ings.get(0)));
                }
            }
            return List.of();
        }
        // 空槽：并集成分（可拉任一可处理材料）
        Ingredient union = Ingredient.EMPTY;
        List<Ingredient> all = new ArrayList<>();
        for (Recipe<?> r : recipes) {
            if (!filter.test(r)) continue;
            List<Ingredient> ings = r.getIngredients();
            if (!ings.isEmpty() && !ings.get(0).isEmpty()) {
                all.add(ings.get(0));
            }
        }
        if (all.isEmpty()) return List.of();
        return List.of(new cn.ism.mekck.util.AE2InputSpec(Ingredient.merge(all)));
    }

    private List<cn.ism.mekck.util.AE2InputSpec> multiIngredient(String ns, String path, java.util.function.Predicate<Recipe<?>> filter) {
        RecipeType<?> rt = recipeTypeOf(new ResourceLocation(ns, path));
        if (rt == null) return List.of();
        List<Recipe<?>> recipes = cn.ism.mekck.util.RecipeCache.all(level, rt);
        ItemStack slot0 = items.getStackInSlot(0);
        if (slot0.isEmpty()) return List.of();
        for (Recipe<?> r : recipes) {
            if (!filter.test(r)) continue;
            List<Ingredient> ings = r.getIngredients();
            if (!ings.isEmpty() && !ings.get(0).isEmpty() && ings.get(0).test(slot0)) {
                List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) {
                    if (!ing.isEmpty()) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
                }
                return specs;
            }
        }
        return List.of();
    }

    /**
     * 取配方类型。森罗物语系（酒馆 + 厨房，同一作者）的 RecipeType 是模组用 {@code RecipeType.simple()}
     * 造的**匿名对象**、从不进注册表（javap 两者的 {@code init.ModRecipes} 证实，详见
     * {@link cn.ism.mekck.compat.TavernBarrelCompat#typeById}）⇒ 按 id 查恒为 null，那整条配方路径会静默
     * 当成「未安装」。该兜底现已内置在 {@link cn.ism.mekck.util.RecipeCache#type} 里，本方法只是保留一个
     * 可读的调用点写法（行为与直接调 RecipeCache 完全一致）。
     */
    private RecipeType<?> recipeTypeOf(ResourceLocation id) {
        RecipeType<?> tavern = cn.ism.mekck.compat.TavernBarrelCompat.typeById(id);
        return tavern != null ? tavern : cn.ism.mekck.util.RecipeCache.type(id);
    }

    private List<cn.ism.mekck.util.AE2InputSpec> curdPullInputs() {
        ItemStack slot0 = items.getStackInSlot(0);
        if (!slot0.isEmpty()) {
            // 槽 0 已有料（含 F9 的 oreo_dough / tapioca_flour）：按其物品类型续料，天然覆盖 compacting 补料。
            return List.of(new cn.ism.mekck.util.AE2InputSpec(Ingredient.of(slot0.getItem())));
        }
        // 空槽：并集候选（凝乳块 + F9 createcafe compacting 输入），ME 可拉任一以起批。
        java.util.List<Ingredient> candidates = new java.util.ArrayList<>();
        candidates.add(Ingredient.of(ForgeRegistries.ITEMS.getValue(new ResourceLocation("trailandtales_delight", "curd_block"))));
        candidates.add(Ingredient.of(ForgeRegistries.ITEMS.getValue(new ResourceLocation("trailandtales_delight", "cherry_curd_block"))));
        collectCompactingInputs(candidates);
        return List.of(new cn.ism.mekck.util.AE2InputSpec(Ingredient.merge(candidates)));
    }
    
    /** F9：收集 {@code createcafe:} 的 {@code create:compacting} 配方输入（空槽时并入 ME 拉料候选）。 */
    private void collectCompactingInputs(java.util.List<Ingredient> out) {
        if (level == null) return;
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("create", "compacting"));
        if (rt == null) return;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                ResourceLocation rid = r.getId();
                if (rid == null || !"createcafe".equals(rid.getNamespace())) continue;
                for (Ingredient ing : r.getIngredients()) {
                    if (!ing.isEmpty()) out.add(ing);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private List<cn.ism.mekck.util.AE2InputSpec> juicerPullInputs() {
        ItemStack slot0 = items.getStackInSlot(0);
        if (!slot0.isEmpty()) {
            List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
            specs.add(new cn.ism.mekck.util.AE2InputSpec(Ingredient.of(slot0.getItem())));
            // 简报 §六③：酒瓶是载具、不在 ingredients 里 → 通用规格列不出来，
            // 不补这一条的话 AE2 自动补料永远缺瓶子、批次空转（槽 0 已占，瓶子自然进下一个空槽）。
            if (findVineryBottleSlot(1) < 0 && juicerHasBottleRequiringRecipe()) {
                Ingredient bottle = itemIng("vinery", "wine_bottle");
                if (bottle != null) specs.add(new cn.ism.mekck.util.AE2InputSpec(bottle));
            }
            return specs;
        }
        // 空槽：苹果 / 苹果浆 都可
        return List.of(new cn.ism.mekck.util.AE2InputSpec(Ingredient.merge(java.util.List.of(
                Ingredient.of(net.minecraft.world.item.Items.APPLE),
                Ingredient.of(ForgeRegistries.ITEMS.getValue(new ResourceLocation("vinery", "apple_mash")))))));
    }

    private List<cn.ism.mekck.util.AE2InputSpec> ricePullInputs() {
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("farmersdelight", "cooking"));
        if (rt == null) return List.of();
        List<Recipe<?>> recipes = cn.ism.mekck.util.RecipeCache.all(level, rt);
        ItemStack slot0 = items.getStackInSlot(0);
        for (Recipe<?> r : recipes) {
            net.minecraft.resources.ResourceLocation rid = ForgeRegistries.ITEMS.getKey(r.getResultItem(level.registryAccess()).getItem());
            if (rid == null) continue;
            boolean isRice = "farmersdelight:cooked_rice".equals(rid.toString()) || rid.getPath().contains("rice_ball");
            if (!isRice) continue;
            List<Ingredient> ings = r.getIngredients();
            if (ings.isEmpty() || ings.get(0).isEmpty()) continue;
            if (slot0.isEmpty()) {
                // 返回第一个可做米饭配方的全部材料（做米饭 = 大米）
                List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
                return specs;
            }
            if (ings.get(0).test(slot0)) {
                List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
                return specs;
            }
        }
        return List.of();
    }

    private List<cn.ism.mekck.util.AE2InputSpec> sushiPullInputs() {
        ItemStack slot0 = items.getStackInSlot(0);
        if (slot0.isEmpty()) {
            // 空槽：熟米饭（底材）
            return List.of(new cn.ism.mekck.util.AE2InputSpec(riceIngredient()));
        }
        // 已放底材/米饭：返回该配方全部部件
        String[] types = {"cuisine_ordered", "cuisine_mixed", "cuisine_fixed"};
        for (String t : types) {
            RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("youkaishomecoming", t));
            if (rt == null) continue;
            for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
                try {
                    net.minecraft.resources.ResourceLocation base = (ResourceLocation) cn.ism.mekck.util.Reflect.call(r, "base");
                    if (base == null) continue;
                    net.minecraft.resources.ResourceLocation slot0Id = ForgeRegistries.ITEMS.getKey(slot0.getItem());
                    boolean baseDirect = slot0Id != null && slot0Id.equals(base);
                    boolean riceBase = !baseDirect && (slot0Id != null && "farmersdelight:cooked_rice".equals(slot0Id.toString()));
                    if (!baseDirect && !riceBase) continue;
                    @SuppressWarnings("unchecked")
                    List<Ingredient> parts = (List<Ingredient>) cn.ism.mekck.util.Reflect.call(r, "getCustomIngredients");
                    List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
                    if (!baseDirect) specs.add(new cn.ism.mekck.util.AE2InputSpec(riceIngredient()));
                    if (parts != null) {
                        for (Ingredient ing : parts) {
                            if (!ing.isEmpty()) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
                        }
                    }
                    return specs;
                } catch (Exception ignored) {
                }
            }
        }
        return List.of();
    }

    private List<cn.ism.mekck.util.AE2InputSpec> wineryPullInputs() {
        // WINERY Tavern 模式：批次活跃时禁止 AE2 拉料混入输入区
        if (!tavernBatch.isIdle()) return List.of();
        ItemStack slot0 = items.getStackInSlot(0);
        if (slot0.isEmpty()) {
            // 空槽：拉任意果汁瓶（红/白葡萄汁等）
            List<Ingredient> juices = new ArrayList<>();
            for (String id : new String[]{"red_grapejuice", "white_grapejuice", "red_jungle_grapejuice",
                    "red_savanna_grapejuice", "red_taiga_grapejuice", "white_jungle_grapejuice",
                    "white_savanna_grapejuice", "white_taiga_grapejuice", "apple_juice"}) {
                net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("vinery", id));
                if (item != null && item != net.minecraft.world.item.Items.AIR) {
                    juices.add(Ingredient.of(item));
                }
            }
            if (juices.isEmpty()) return List.of();
            return List.of(new cn.ism.mekck.util.AE2InputSpec(Ingredient.merge(juices)));
        }
        // 已放果汁：取该果汁对应配方的配料
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("vinery", "wine_fermentation"));
        if (rt == null) return List.of();
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                String type = cn.ism.mekck.util.VineryJuice.recipeJuiceType(r);
                if (type == null) continue;
                String juiceId = cn.ism.mekck.util.VineryJuice.itemIdForType(type);
                net.minecraft.world.item.Item juiceItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(juiceId));
                if (juiceItem != null && juiceItem != net.minecraft.world.item.Items.AIR && slot0.getItem() == juiceItem) {
                    List<Ingredient> ings = r.getIngredients();
                    List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
                    for (Ingredient ing : ings) {
                        if (!ing.isEmpty()) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
                    }
                    return specs;
                }
            } catch (Exception ignored) {
            }
        }
        return List.of();
    }

    private List<cn.ism.mekck.util.AE2InputSpec> fermenterPullInputs() {
        // 发酵机：返回原料 + 输入流体提示（流体拉取暂不支持，仅物品）
        ItemStack slot0 = items.getStackInSlot(0);
        if (slot0.isEmpty()) return List.of();
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("youkaishomecoming", "simple_fermentation"));
        if (rt == null) return List.of();
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            try {
                @SuppressWarnings("unchecked")
                List<Ingredient> ings = (List<Ingredient>) r.getClass().getField("ingredients").get(r);
                if (ings == null || ings.isEmpty() || ings.get(0).isEmpty()) continue;
                if (!ings.get(0).test(slot0)) continue;
                List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
                return specs;
            } catch (Exception ignored) {
            }
        }
        return List.of();
    }

    public ContainerData getData() {
        return data;
    }

    public void setCustomName(Component customName) {
        this.customName = customName;
    }

    @Override
    public Component getDisplayName() {
        return customName != null ? customName : Component.translatable("block.mekck." + kind.id);
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SimpleMachineMenu(containerId, inventory, this, data);
    }

    // ================== 侧面配置 ==================

    public void setSideMode(Direction dir, SideMode mode) {
        if (dir == null) return;
        sideConfig[dir.ordinal()] = mode;
        setChanged();
    }

    public SideMode getSideMode(Direction dir) {
        return sideConfig[dir.ordinal()];
    }

    /** 流体侧面配置（独立于物品侧配）。 */
    public void setFluidSideMode(Direction dir, SideMode mode) {
        fluids.setFluidSideMode(dir, mode);
    }

    public SideMode getFluidSideMode(Direction dir) {
        return fluids.getFluidSideMode(dir);
    }

    /** 本机是否具备流体处理能力（SimpleMachine 均内置输入/输出流体罐）。 */
    public boolean hasFluidHandler() {
        return fluids.hasFluidHandler();
    }

    private int encodeSideConfig() {
        return encodeSideConfig(sideConfig);
    }

    private static int encodeSideConfig(SideMode[] config) {
        int v = 0;
        for (int i = 0; i < 6; i++) v |= (config[i].ordinal() & 0xF) << (i * 4);
        return v;
    }

    private void decodeSideConfig(int v) {
        decodeSideConfig(sideConfig, v);
    }

    private static void decodeSideConfig(SideMode[] config, int v) {
        for (int i = 0; i < 6; i++) {
            int ord = (v >> (i * 4)) & 0xF;
            if (ord >= 0 && ord < SideMode.values().length) config[i] = SideMode.values()[ord];
        }
    }

    // ================== 处理 ==================


    public static void clientTick(Level level, BlockPos pos, BlockState state, SimpleMachineBlockEntity machine) {
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SimpleMachineBlockEntity machine) {
        machine.updateRedstone();
        // AE2 网格节点生命周期 / 联网检测 / 自动补料（未安装 AE2 时为空操作）
        cn.ism.mekck.compat.AE2Compat.serverTick(machine, level, pos);
        // WINERY 专属：Tavern 酿造批次（完工扣料口径：陈化期不扣料，陈到满级才一次性扣料）+ 自动逐瓶分装
        if (machine.kind == MachineKind.WINERY) {
            // 陈化期持守校验：材料被拿走/换放其他材料/流体减少 ⇒ 失配，中止批次、进度归 0（材料原样留在槽内）
            if (machine.tavernBatch.isBrewing() && !machine.tavernBatch.isConsumed()
                    && !machine.tavernHoldIntact()) {
                machine.tavernBatch.reset();
                machine.currentRecipeId = null;
                machine.progress = 0;
                machine.setChanged();
            }
            // 陈化期间同步耗电（用户 2026-09-25 拍板：与葡园酒香同口径 ⇒ 每 tick 抽
            // energyPerTick×speedMult²×energyMult，整批总耗 = kind.energyPerTick×2400×speedMult，
            // 速度升级加快的同时按线性放大单批电费；完工（markConsumed）后分装阶段停扣）
            if (machine.tavernBatch.isBrewing() && !machine.tavernBatch.isConsumed()) {
                double brewSpeedMult = machine.getEffectiveSpeedMultiplier();
                int brewEnergyPerTick = machine.hasCreativeUpgrade() ? 0
                        : (int) Math.ceil(machine.kind.energyPerTick * brewSpeedMult * brewSpeedMult
                                * machine.getEffectiveEnergyConsumptionMultiplier());
                if (machine.hasCreativeUpgrade()
                        || machine.energy.getEnergyStored() >= brewEnergyPerTick) {
                    if (!machine.hasCreativeUpgrade()) {
                        machine.energy.extractEnergy(brewEnergyPerTick, false);
                    }
                    double brewSpeed = machine.hasCreativeUpgrade()
                            ? (double) Math.max(1, machine.tavernBatch.getBrewTotalTicks()) // 创造升级：单 tick 烧完整批预算（与普通配方 1 tick 同调）
                            : brewSpeedMult;
                    machine.tavernBatch.tickBatch(brewSpeed);
                }
                // 电力不足：陈化暂停（进度保留，与输出满同口径，不归零）
            }
            // 陈化完毕（满级）且尚未扣料 ⇒ 执行完工扣料事务，此后批次才可分装
            if (machine.tavernBatch.isBrewing() && !machine.tavernBatch.isConsumed()
                    && machine.tavernBatch.isMaxBrewLevel()) {
                machine.commitTavernFinishConsume();
            }
            machine.tryTavernDispense();
        }
        if (machine.drainPowerSlot()) machine.setChanged();

        // 升级读条：槽位放入升级后 20 tick 安装一次（复刻 Mekanism TileComponentUpgrade）
        boolean upgradesChanged = false;
        upgradesChanged |= machine.speedTracker.tick(machine.items.getStackInSlot(SLOT_SPEED_UPGRADE),
                UpgradeHelper::isSpeedUpgrade);
        upgradesChanged |= machine.energyTracker.tick(machine.items.getStackInSlot(SLOT_ENERGY_UPGRADE),
                UpgradeHelper::isEnergyUpgrade);
        upgradesChanged |= machine.creativeTracker.tick(machine.items.getStackInSlot(SLOT_CREATIVE_UPGRADE),
                UpgradeHelper::isCreativeUpgrade);
        if (upgradesChanged) machine.setChanged();

        // 物品主动输入输出（抽取至输入格 / 弹出产物）
        if (machine.itemAutoIO.run(level, pos, machine.sideConfig, machine.items)) machine.setChanged();
        // 流体自动输入输出（抽取/弹出）
        if (machine.fluids.getAutoIO().run(level, pos, machine.fluids.getFluidSideConfig())) machine.setChanged();

        // 温度系统：加热类机器每 tick 与相邻 Mekanism 热力设备传导并自然回归环境
        if (machine.isHeatingMachine()) {
            machine.heatComponent.tick(level, pos);
        }

        boolean hasCreative = machine.hasCreativeUpgrade();
        if (hasCreative) {
            machine.energy.receiveEnergy(machine.energy.getMaxEnergyStored() - machine.energy.getEnergyStored(), false);
        }
        double speedMult = machine.getEffectiveSpeedMultiplier();
        double energyConsumptionMult = machine.getEffectiveEnergyConsumptionMultiplier();
        int energyPerTick = hasCreative ? 0 : (int) Math.ceil(machine.kind.energyPerTick * speedMult * speedMult * energyConsumptionMult);

        if (machine.redstoneControl == RedstoneControl.PULSE && machine.redstonePowered && !machine.redstonePoweredLastTick) {
            machine.pulseRunning = true;
        }

        // 模式互斥：WINERY 有 Tavern 批次（酿造/停滞）时，普通 Vinery 配方流程停止
        boolean tavernBatchActive = machine.kind == MachineKind.WINERY
                && (machine.tavernBatch.isBrewing() || machine.tavernBatch.isStalled());
        // 陈酿机：每 tick 把果汁格/遗留果汁瓶即时吸收进液位池（独立于配方启动，复刻 vinery 投料语义）。
        // 不再因 Tavern 批次活跃而整体停摆：批次酿造期间仍要能继续投桶补液（用户 2026-09-24：
        // 果汁格只有一格，一旦挂住一个不被抽取的桶，后续流体桶既抽不动也放不进去）。
        if (machine.kind == MachineKind.WINERY) {
            machine.recipes.absorbWineryJuice();
        }
        MatchedRecipe recipe = tavernBatchActive ? null : machine.findRecipe();
        // Tavern 批次启动（vinery 优先真正确立）：仅当 vinery 未匹配（recipe==null）、
        // 无批次、无普通进度时尝试；启动成功后本 tick recipe 仍为 null（不会同 tick 又推进普通加工）
        if (machine.kind == MachineKind.WINERY && !tavernBatchActive && machine.progress == 0 && recipe == null) {
            machine.tryStartTavernBatch();
        }
        // 配方身份跟踪：换配方时重置进度（不继承不同配方的加工时间）；
        // 缺料/输出满（recipe==null 或 canWork=false）保持暂停，不重置。
        if (recipe != null && recipe.recipeId != null) {
            if (machine.currentRecipeId != null && !machine.currentRecipeId.equals(recipe.recipeId)) {
                machine.progress = 0;
            }
            machine.currentRecipeId = recipe.recipeId;
        }
        // 完工扣料口径（用户 2026-09-25 工单，限陈酿机）：「匹配不上配方」（材料被拿走/换料）即进度归 0，
        // 不再暂停保留；输出满/红石压制（recipe!=null 仅 canWork=false）不算材料失配，仍暂停保留进度。
        if (machine.kind == MachineKind.WINERY && recipe == null && !tavernBatchActive
                && machine.progress > 0) {
            machine.progress = 0;
        }
        int effectiveProcessTime = hasCreative ? 1
                : (recipe != null && recipe.processTime > 0 ? Math.max(1, (int) (recipe.processTime / speedMult))
                        : machine.getEffectiveProcessTime());
        boolean canWork = machine.canFunctionRedstone() && recipe != null && machine.recipes.canAcceptOutputs(recipe);
        if (machine.kind == MachineKind.WINERY) machine.noteWineryStall(recipe, canWork);

        if (canWork) {
            boolean hasEnergy = hasCreative || machine.energy.getEnergyStored() >= energyPerTick;
            if (hasEnergy) {
                if (machine.redstoneControl == RedstoneControl.PULSE) {
                    machine.pulseRunning = false;
                }
                if (!hasCreative) machine.energy.extractEnergy(energyPerTick, false);
                // 温度系统：加热类机器按消耗电能产热（与电阻型加热器比例完全相同：1 FE → 0.6 J）
                if (machine.isHeatingMachine()) {
                    machine.heatComponent.addHeatFromEnergy(hasCreative ? 0 : energyPerTick);
                }
                machine.progress++;
                if (machine.progress >= effectiveProcessTime) {
                    machine.recipes.complete(recipe);
                    machine.progress = 0;
                    machine.setChanged();
                }
            }
        } else if (machine.redstoneControl != RedstoneControl.PULSE) {
            machine.progress = Math.min(machine.progress, effectiveProcessTime);
        }

        BlockState newState = state.setValue(SimpleMachineBlock.ACTIVE, machine.progress > 0);
        if (newState != state) level.setBlock(pos, newState, 3);

        if (!level.isClientSide) {
            machine.data.get(DATA_ENERGY);
        }
    }

    // ================== ME 终端下单 ==================

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
     * 下单。数量下界与 {@link MekCkOrderState#setOrder} 对齐：
     * <b>取消（{@code recipeId == null}）时清零、激活时夹到 ≥ 1</b>。
     *
     * <p>本类原先是 {@code orderQuantity = quantity} 原样存，与其余实现契约不一致 ——
     * 只靠 {@code OrderRecipePacket} / {@code NetworkOrderPacket} 入口恰好夹过才没出事。
     * 写坏之后是<b>静默</b>故障：门禁与推进同时失效，订单永不完成。</p>
     */
    public void setOrder(@Nullable net.minecraft.resources.ResourceLocation recipeId, int quantity) {
        this.orderRecipeId = recipeId;
        this.orderQuantity = recipeId == null ? 0 : Math.max(1, quantity);
        this.orderCompleted = 0;
        setChanged();
    }

    @Nullable
    public net.minecraft.resources.ResourceLocation getOrderRecipeId() {
        return orderRecipeId;
    }

    public int getOrderQuantity() {
        return orderQuantity;
    }

    public int getOrderCompleted() {
        return orderCompleted;
    }

    // ── 匹配结果短路缓存 ──
    // 输入槽指纹 + 果汁池 + 配方管理器身份都不变时，直接复用上一次匹配结果：
    // 避免每 tick 把该类型全部配方线性扫一遍（茶艺机、料理台、陈酿机等尤其贵）。
    // 数据包重载会换 RecipeManager 实例 → 自动失效；输入/液位一变 → 指纹变化 → 重新匹配。
    private long matchKey = Long.MIN_VALUE;
    private Object matchManager;
    private int matchJuiceLevel = Integer.MIN_VALUE;
    private String matchJuiceType;
    private MatchedRecipe matchCached;

    /** 输入槽指纹（物品注册名 + 数量 + NBT 哈希；启用扩展槽时一并计入）。 */
    private long inputFingerprint() {
        long h = 1125899906842597L;
        int total = INPUT_COUNT + (usesExtendedInputSlots() ? EXT_INPUT_COUNT : 0);
        for (int i = 0; i < total; i++) {
            ItemStack st = items.getStackInSlot(i);
            long itemHash = 0L;
            if (!st.isEmpty()) {
                net.minecraft.resources.ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
                itemHash = (id == null ? 0 : id.hashCode()) * 31L
                        + (st.getTag() == null ? 0 : st.getTag().hashCode());
            }
            h = h * 31L + itemHash;
            h = h * 31L + st.getCount();
        }
        return h;
    }

    /**
     * 输入罐 + 输出罐的「流体 id + 量」指纹。
     *
     * <p><b>为什么并入配方缓存键</b>：{@code matchFerment} / {@code matchExtractor} /
     * {@code matchBeverageAssembly} 在每次匹配时都读输入罐（同型 + 足量）与输出罐（同型 + 容量预检），
     * 结果随罐内流体变化。原先缓存键只有物品指纹，罐一变就复用旧匹配 ⇒ 机器卡在错误配方或空转。
     * 见 {@link SimpleMachineRecipes} 中对 {@code be.fluids} 的读取。</p>
     */
    private long fluidFingerprint() {
        long h = 31L + tankFingerprint(fluids.getInputTank().getFluid());
        return h * 31L + tankFingerprint(fluids.getOutputTank().getFluid());
    }

    private static long tankFingerprint(net.minecraftforge.fluids.FluidStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        return (long) net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(stack.getFluid()) * 31L
                + stack.getAmount();
    }

    private MatchedRecipe findRecipe() {
        if (level == null) return null;
        if (orderRecipeId != null) {
            return findOrderedRecipe(orderRecipeId);
        }
        Object manager = level.getRecipeManager();
        // 物品指纹并入两罐指纹（见 fluidFingerprint 的「为什么」）。
        long key = inputFingerprint() * 31L + fluidFingerprint();
        if (manager == matchManager && key == matchKey
                && matchJuiceLevel == juiceLevel && java.util.Objects.equals(matchJuiceType, juiceType)) {
            return matchCached;
        }
        MatchedRecipe matched = switch (kind) {
            case SUSHI_MAKER -> recipes.matchSushi();
            case AVERAGE_SLICER -> recipes.matchSlicer();
            case RICE_BALL_MAKER -> recipes.matchRice();
            case CURD_MAKER -> recipes.matchCurd();
            case DEHYDRATOR -> recipes.matchDry();
            case FERMENTER -> recipes.matchFerment();
            case STEAMER -> recipes.matchSteam();
            case WINERY -> recipes.matchWinery();
            case JUICER -> recipes.matchJuicer();
            case BAKERY_OVEN -> recipes.matchBakery();
            case STOVE -> recipes.matchStove();
            case COCKTAIL_SHAKER -> recipes.matchShaker();
            case BLENDER -> recipes.matchBlender();
            case TEA_BREWER -> recipes.matchTea();
            case SMART_EXTRACTOR -> recipes.matchExtractor();
            case BEVERAGE_BLENDER -> recipes.matchBeverageAssembly();
            case PACKAGING_STATION -> recipes.matchPackaging();
        };
        matchManager = manager;
        matchKey = key;
        matchJuiceLevel = juiceLevel;
        matchJuiceType = juiceType;
        matchCached = matched;
        return matched;
    }

    private MatchedRecipe findOrderedRecipe(net.minecraft.resources.ResourceLocation id) {
        for (net.minecraft.world.item.crafting.Recipe<?> r : allRecipesOfKind()) {
            if (!r.getId().equals(id)) continue;
            List<cn.ism.mekck.util.AE2InputSpec> specs = recipeInputSpecs(r);
            if (specs.isEmpty()) continue;
            List<Integer> slots = new ArrayList<>();
            boolean[] used = new boolean[INPUT_COUNT];
            boolean ok = true;
            for (cn.ism.mekck.util.AE2InputSpec spec : specs) {
                boolean found = false;
                for (int s = 0; s < INPUT_COUNT; s++) {
                    if (used[s]) continue;
                    ItemStack st = items.getStackInSlot(s);
                    if (!st.isEmpty() && spec.ingredient.test(st)) {
                        used[s] = true;
                        slots.add(s);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            ItemStack res = r.getResultItem(level.registryAccess());
            if (res.isEmpty()) continue;
            return new MatchedRecipe(slots, res, recipeTime(r), net.minecraftforge.fluids.FluidStack.EMPTY, net.minecraftforge.fluids.FluidStack.EMPTY);
        }
        return null;
    }

    public List<Recipe<?>> allRecipesOfKind() {
        if (level == null) return List.of();
        List<Recipe<?>> out = new ArrayList<>();
        java.util.List<String> typeIds = switch (kind) {
            case AVERAGE_SLICER -> java.util.List.of("farmersdelight:cutting", "bakeries:bread_knife");
            case RICE_BALL_MAKER -> java.util.List.of("farmersdelight:cooking");
            case SUSHI_MAKER -> java.util.List.of("youkaishomecoming:cuisine_ordered", "youkaishomecoming:cuisine_mixed", "youkaishomecoming:cuisine_fixed");
            case DEHYDRATOR -> java.util.List.of("youkaishomecoming:drying_rack", "farm_and_charm:drying");
            case FERMENTER -> java.util.List.of("youkaishomecoming:simple_fermentation", "bakeries:fermentation",
                    "brewery:brewing", "drinkbeer:brewing");
            case STEAMER -> java.util.List.of("youkaishomecoming:steaming", "kaleidoscope_cookery:steamer");
            case WINERY -> java.util.List.of("vinery:wine_fermentation");
            case JUICER -> java.util.List.of("vinery:apple_mashing", "vinery:apple_fermenting",
                    "mekck:grape_pressing", "kaleidoscope_tavern:pressing_tub");
            case BAKERY_OVEN -> java.util.List.of("bakery:baking_station", "bakeries:oven", "bakeries:coffee");
            case STOVE -> java.util.List.of("farm_and_charm:stove", "bakeries:stone_kiln",
                    "farm_and_charm:roaster", "herbalbrews:kettle_brewing", "meadow:cooking");
            case COCKTAIL_SHAKER -> java.util.List.of("kaleidoscope_tavern:shaker");
            case BLENDER -> java.util.List.of("bakeries:blender");
            case CURD_MAKER -> java.util.List.of("meadow:cheese", "create:compacting");
            case TEA_BREWER -> java.util.List.of("minecraft:crafting");
            case SMART_EXTRACTOR -> java.util.List.of("create:mixing", "mekck:extracting");
            case BEVERAGE_BLENDER -> java.util.List.of("mekck:beverage_assembly");
            case PACKAGING_STATION -> java.util.List.of("mekck:packaging");
        };
        for (String tid : typeIds) {
            RecipeType<?> rt = recipeTypeOf(new ResourceLocation(tid));
            if (rt != null) {
                out.addAll(cn.ism.mekck.util.RecipeCache.all(level, rt));
            }
        }
        // 茶艺机：原版合成配方有数千条，绝不能每 tick 复制+过滤一遍 —— 走按管理器缓存的过滤表
        if (kind == MachineKind.TEA_BREWER) {
            return teaRecipes();
        }
        return out;
    }

    /** 茶艺机专用：「简单的茶」产物配方表（按 RecipeManager 缓存，数据包重载自动失效）。 */
    private static final java.util.Map<net.minecraft.world.item.crafting.RecipeManager, List<Recipe<?>>> TEA_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private List<Recipe<?>> teaRecipes() {
        net.minecraft.world.item.crafting.RecipeManager manager = level.getRecipeManager();
        synchronized (TEA_CACHE) {
            List<Recipe<?>> cached = TEA_CACHE.get(manager);
            if (cached != null) return cached;
            List<Recipe<?>> filtered = new ArrayList<>();
            for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, "minecraft", "crafting")) {
                if (isSimplyTeaResult(r)) filtered.add(r);
            }
            List<Recipe<?>> immutable = java.util.Collections.unmodifiableList(filtered);
            TEA_CACHE.put(manager, immutable);
            return immutable;
        }
    }

    /** 该配方产物是否属于「简单的茶」模组。 */
    boolean isSimplyTeaResult(Recipe<?> r) {
        try {
            ItemStack res = r.getResultItem(level.registryAccess());
            if (res.isEmpty()) return false;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(res.getItem());
            return id != null && "simplytea".equals(id.getNamespace());
        } catch (Throwable t) {
            return false;
        }
    }

    // ================== 本机下单（面板「本机 / ME」的本机一侧） ==================

    /**
     * 供「本机下单」面板展示：用机器**输入槽里现有的材料**能做的配方（按配方表顺序、去重）。
     * <p>与 {@code findOrderedRecipe} 同一套匹配口径（每个输入槽只用一次），因此面板里列出的配方
     * 一定能被 {@link #setOrder} 接单。</p>
     */
    public List<Recipe<?>> getAvailableRecipes() {
        List<Recipe<?>> out = new ArrayList<>();
        if (level == null) return out;
        java.util.Set<ResourceLocation> seen = new java.util.HashSet<>();
        for (Recipe<?> r : allRecipesOfKind()) {
            try {
                List<cn.ism.mekck.util.AE2InputSpec> specs = recipeInputSpecs(r);
                if (specs.isEmpty()) continue;
                if (r.getResultItem(level.registryAccess()).isEmpty()) continue;
                if (!canMatchFromInputs(specs)) continue;
                if (seen.add(r.getId())) out.add(r);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 配方需求是否全都能被输入槽 0..INPUT_COUNT-1 满足（每个槽只用一次，与 findOrderedRecipe 一致）。 */
    private boolean canMatchFromInputs(List<cn.ism.mekck.util.AE2InputSpec> specs) {
        boolean[] used = new boolean[INPUT_COUNT];
        for (cn.ism.mekck.util.AE2InputSpec spec : specs) {
            boolean found = false;
            for (int s = 0; s < INPUT_COUNT; s++) {
                if (used[s]) continue;
                ItemStack st = items.getStackInSlot(s);
                if (!st.isEmpty() && spec.ingredient.test(st)) {
                    used[s] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /**
     * 供「本机下单」面板的 Max 按钮：按输入槽现有材料算该配方最多可做几份。
     * <p>口径与其他机器一致（保守上界：逐需求项把匹配物品的数量相加后取最小份数；
     * 多个需求项抢同一种材料时会高估，机器做到材料不够自然停）。</p>
     */
    public int getMaxConsumableCountForOrder(Recipe<?> recipe) {
        if (recipe == null) return 0;
        try {
            List<cn.ism.mekck.util.AE2InputSpec> specs = recipeInputSpecs(recipe);
            if (specs.isEmpty()) return 0;
            int max = Integer.MAX_VALUE;
            for (cn.ism.mekck.util.AE2InputSpec spec : specs) {
                int need = Math.max(1, spec.count);
                long have = 0L;
                for (int s = 0; s < INPUT_COUNT; s++) {
                    ItemStack st = items.getStackInSlot(s);
                    if (!st.isEmpty() && spec.ingredient.test(st)) have += st.getCount();
                }
                max = (int) Math.min(max, have / need);
                if (max <= 0) return 0;
            }
            return max == Integer.MAX_VALUE ? 0 : max;
        } catch (Throwable t) {
            return 0;
        }
    }

    public List<cn.ism.mekck.util.AE2InputSpec> recipeInputSpecs(Recipe<?> r) {
        List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
        switch (kind) {
            case AVERAGE_SLICER, DEHYDRATOR, STEAMER, BAKERY_OVEN, STOVE, TEA_BREWER, BEVERAGE_BLENDER, PACKAGING_STATION -> {
                for (Ingredient ing : r.getIngredients()) {
                    if (!ing.isEmpty()) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
                }
            }
            case RICE_BALL_MAKER -> {
                net.minecraft.resources.ResourceLocation rid = ForgeRegistries.ITEMS.getKey(r.getResultItem(level.registryAccess()).getItem());
                if (rid != null && ("farmersdelight:cooked_rice".equals(rid.toString()) || rid.getPath().contains("rice_ball"))) {
                    for (Ingredient ing : r.getIngredients()) {
                        if (!ing.isEmpty()) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
                    }
                }
            }
            case JUICER -> {
                if (ForgeRegistries.RECIPE_TYPES.getKey(r.getType()) != null
                        && "vinery:apple_mashing".equals(ForgeRegistries.RECIPE_TYPES.getKey(r.getType()).toString())
                        && !r.getIngredients().isEmpty()) {
                    specs.add(new cn.ism.mekck.util.AE2InputSpec(r.getIngredients().get(0)));
                }
            }
            default -> {
            }
        }
        return specs;
    }

    private int recipeTime(Recipe<?> r) {
        if (kind == MachineKind.FERMENTER) {
            try {
                return Math.max(0, r.getClass().getField("time").getInt(r));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    // ── 寿司卷制机：youkaishomecoming cuisine_* ──


    static Ingredient riceIngredient() {
        ItemStack rice = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("farmersdelight", "cooked_rice")));
        return rice.isEmpty() ? Ingredient.EMPTY : Ingredient.of(rice);
    }


    // ── 平均切段机：FD cutting 且输出含 _slice ──


    /**
     * 烘焙坊 bakeries:bread_knife（7 配方）。参考 BreadKnifeRecipe 的真实语义：
     * 单输入（只看槽 0，inputItems.test(container.getItem(0))）；assemble/getResultItem 只取
     * output.get(0)（多输出配方也取第一项）；无时间字段（机器默认时长）。
     */

    @SuppressWarnings("unchecked")
    List<ItemStack> cuttingResults(Recipe<?> r) {
        try {
            Object o = cn.ism.mekck.util.Reflect.call(r, "getResults");
            if (o instanceof List<?> list) {
                List<ItemStack> out = new ArrayList<>();
                for (Object e : list) if (e instanceof ItemStack is) out.add(is.copy());
                return out;
            }
        } catch (Exception ignored) {
        }
        return java.util.Collections.singletonList(r.getResultItem(level.registryAccess()));
    }

    // ── 饭团成型机：FD cooking 结果为 cooked_rice / *_rice_ball ──


    // ── 凝乳成型机：凝乳块 → 奶酪轮 ──


    /**
     * F9：凝乳成型机兼容 createcafe 的 {@code create:compacting} 配方（粉/团 → 定型半成品），
     * 例 {@code createcafe:oreo_dough → oreo_half_raw}、{@code createcafe:tapioca_flour x4 → raw_boba x4}。
     * <p>命名空间过滤：仅收 recipe id 前缀 {@code createcafe:} 的 compacting，避开 Create 本体的压碎/面团等。</p>
     * <p>⚠ Create 的 {@code ProcessingRecipeSerializer} 用 vanilla {@code Ingredient.fromJson} 解析
     * ingredients，**丢弃 {@code count}**（实测字节码），故 {@code getIngredients()} 只表达物品类型；
     * 本模组按「产物数量 = 输入消耗数量」反推（这两条 compacting 均为 1:1 计数），并强制槽内数量足够，
     * 以杜绝「1 木薯粉 → 4 珍珠」的 dupe。</p>
     */

    /**
     * 青青草甸 meadow:cheese（7 配方）。参考 CheeseFormRecipe 的真实语义：
     * getIngredients() = [bucket（奶桶）, ingredient（凝乳酶）]，matches 检查槽 1、2 的物品是否
     * 各自匹配任一 ingredient（**无序 2 槽**）；结果由 getResultItem 返回（奶酪块）。
     * MekCK 用通用无序匹配（RecipeMatcher）+ 非空槽数 == ingredient 数（防吞料）。
     */

    // ── 食品脱水机：youkaishomecoming:drying_rack（FD 式 ingredient+cookingtime+result）──


    /**
     * 沉浸农艺 farm_and_charm:drying（15 配方）。参考 JSON 结构与晾晒架语义：
     * 单输入（ingredient）+ 固定结果（result）；recipe_type 字段为分类标签，不参与匹配。
     */

    // ── 智能萃取机（F8 / F11 §四.1）：create:mixing 中「产物为流体·茶/咖啡/溶糖/糖浆系」（+ oreo 酱破例白名单）──

    /**
     * 智能萃取机匹配：读 {@code create:mixing}，按用户拍板口径过滤——产物必须是流体，且
     * 命名空间 {@code createcafe:} 且产物流体属茶/咖啡/溶糖/糖浆系；另白名单破例收录
     * {@code createcafe:oreo_filling_mixing}（奥利奥夹心酱，Q13）。{@code heatRequirement} 忽略不校验（我方电热）。
     * 输入 = 物品（getIngredients）+ 输入流体（getFluidIngredients）；输出 = 流体（getFluidResults）→ fluids.getOutputTank()。
     * Create 非编译期依赖，一律反射（{@code Reflect.call}）。
     */

    /** §四.1 过滤谓词：产物流体 ∧（createcafe: ∧ 流体属茶/咖啡/溶糖/糖浆系）∨ oreo 酱破例白名单。 */

    /** ProcessingRecipe.getFluidResults() → NonNullList&lt;FluidStack&gt;（输出流体，反射）。 */

    /** ProcessingRecipe.getFluidIngredients() → NonNullList&lt;FluidIngredient&gt;（输入流体，反射）。 */

    /** FluidIngredient → 首个匹配的 FluidStack（数量取 getRequiredAmount），用于输入罐校验/消耗。 */

    /** 萃取机加工时长：优先取 create:mixing 的 getProcessingDuration（秒→tick），否则用机器基础时长。 */

    // ── 饮品调配机（F8 / F11 §四.2）：自有类型 mekck:beverage_assembly（杯+小料+饮品流体→杯装饮品）──

    /**
     * 调配机匹配：读 {@code mekck:beverage_assembly}（同模块类，无需反射），消耗物品成分（杯+小料）
     * 与输入罐饮品流体，产出杯装饮品物品。不再读 {@code create:filling}。
     */

    /**
     * 烘焙坊 bakeries:fermentation（3 配方：bottle_yeast/cheese_cube/fresh_cheese_cube）。
     * 参考 FermentationBarrelBlockEntity.craftItem 的真实语义：
     * - 无序一对一匹配（RecipeMatcher 回溯，要求非空输入槽数 == ingredient 数，防止吞掉多余材料）；
     * - 固定时长 3600 tick（原版硬编码 cookingTotalTime < 3600）；
     * - 消耗每个匹配槽 1 个，但水桶例外不消耗（原版跳过 Items.WATER_BUCKET）；
     * - container 字段（如 glass_bottle）在原版仅写入容器展示槽、不参与匹配与产出，此处不消耗/不返还。
     */

    // ── 酿酒机：vinery:wine_fermentation（果汁瓶 + 配料 → 酒）──

    /**
     * Tavern Barrel 真实批次启动（WINERY 专属，服务端主线程 tick 调用）。
     * 规则：
     * 1. 仅当无 Tavern 批次（IDLE）、无 STALLED 批次、且普通 Vinery 未在加工（progress==0）时尝试；
     * 2. 一次匹配生成完整启动计划（含配方语义签名/输入槽/流体/瓶数/结果/unitTime/carrier），不拼接；
     * 3. 提交前完整验证（配方语义未变 + 资源与快照一致 + 批次空闲 + 进度 0）；
     * 4. 事务提交（每步可逆，恢复必须验证实际结果；恢复失败置 fault 禁止自动重试）；
     * 5. 批次启动后仅一次：后续 tick/重载/同配方匹配均因 isBrewing 跳过。
     * 不产酒、不写输出槽、不计 ME 订单。
     */
    // Tavern 计划负缓存：酒桶已注满流体但没有可酿配方时，原先每 tick 都要把全部酒桶配方扫一遍
    // （每条 5 次反射 + 配料回溯）。改为按「配方管理器 + 配料槽指纹 + 罐内流体」记忆"无匹配"，
    // 任何物品/流体变化都会让指纹失效；数据包重载换管理器同样失效。
    private Object tavernPlanManager;
    private long tavernPlanKey = Long.MIN_VALUE;
    private boolean tavernPlanMiss;

    /** 配料槽（0..3）与罐内流体的指纹。 */
    private long tavernPlanFingerprint() {
        long h = 1125899906842597L;
        int slots = Math.min(TavernBarrelPlan.MAX_INGREDIENT_SLOTS, INPUT_COUNT);
        for (int i = 0; i < slots; i++) {
            ItemStack st = items.getStackInSlot(i);
            long itemHash = 0L;
            if (!st.isEmpty()) {
                net.minecraft.resources.ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
                itemHash = (id == null ? 0 : id.hashCode()) * 31L
                        + (st.getTag() == null ? 0 : st.getTag().hashCode());
            }
            h = h * 31L + itemHash;
            h = h * 31L + st.getCount();
        }
        net.minecraftforge.fluids.FluidStack fluid = fluids.getInputTank().getFluid();
        h = h * 31L + fluids.getInputTank().getFluidAmount();
        h = h * 31L + (fluid.isEmpty() ? 0 : fluid.getFluid().hashCode());
        return h;
    }

    private void tryStartTavernBatch() {
        if (level == null || level.isClientSide) return;
        if (tavernStartFault) return; // 前次恢复失败：禁止自动重试，等待人工干预
        if (!tavernBatch.isIdle()) return; // BREWING/STALLED 均不覆盖
        Object manager = level.getRecipeManager();
        long fingerprint = tavernPlanFingerprint();
        if (manager == tavernPlanManager && fingerprint == tavernPlanKey && tavernPlanMiss) {
            return; // 上次也没匹配到、且配料与流体都没变：跳过本轮全表扫描
        }
        TavernBarrelPlan plan = recipes.matchTavernBarrelPlan();
        if (plan == null) {
            tavernPlanManager = manager;
            tavernPlanKey = fingerprint;
            tavernPlanMiss = true;
            return;
        }
        tavernPlanMiss = false;
        // 提交前复核（同一计划贯穿验证与提交；配方语义未变 + 资源与快照一致）
        if (!verifyTavernStart(plan)) {
            return;
        }
        // ── 建批（完工扣料口径，用户 2026-09-25 工单：起批不扣任何资源，只登记持守快照） ──
        // 持守范围 = 计划内配料槽 + 容器槽 + 流体要求：陈化期任一变化都算失配（进度归 0）；
        // 完工扣料只清配料槽（0..2），容器槽由分装逐瓶扣、流体在完工时整笔抽取。
        java.util.List<Integer> holdSlots = new java.util.ArrayList<>(plan.getConsumeSlots());
        java.util.List<ItemStack> holdSnaps = new java.util.ArrayList<>(plan.getSlotSnapshots());
        if (!holdSlots.contains(TAVERN_CARRIER_SLOT)) {
            holdSlots.add(TAVERN_CARRIER_SLOT);
            holdSnaps.add(items.getStackInSlot(TAVERN_CARRIER_SLOT).copy());
        }
        net.minecraftforge.fluids.FluidStack planFluid = plan.getFluid();
        String holdFluidName = "";
        int holdFluidAmount = 0;
        if (planFluid != null && !planFluid.isEmpty()) {
            net.minecraft.resources.ResourceLocation fid =
                    net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(planFluid.getFluid());
            holdFluidName = fid == null ? "" : fid.toString();
            holdFluidAmount = planFluid.getAmount();
        }
        if (!tavernBatch.createBatch(plan.getRecipeId(), plan.getResultItemId(), plan.getBottles(),
                plan.getUnitTime(), plan.getCarrierJson(),
                holdSlots, holdSnaps, holdFluidName, holdFluidAmount, true)) {
            // 已验证计划被组件拒绝（防御：理论上不可达，此处无任何资源损失）
            tavernStartFault = true;
            return;
        }
        setChanged();
    }

    /**
     * 陈化期持守校验（完工扣料口径）：每个持守快照槽必须严格一致（同物、同 NBT、同数量），
     * 且罐内流体类型一致、量不少于计划要求（多放流体不算失配；完工只抽计划量）。
     */
    private boolean tavernHoldIntact() {
        java.util.List<Integer> slots = tavernBatch.getHoldSlots();
        java.util.List<ItemStack> snaps = tavernBatch.getHoldSnapshots();
        if (slots.size() != snaps.size()) return false; // 防御：损坏快照视为失配（批次会被中止）
        for (int i = 0; i < slots.size(); i++) {
            int s = slots.get(i);
            if (s < 0 || s >= items.getSlots()) return false;
            ItemStack snap = snaps.get(i);
            ItemStack cur = items.getStackInSlot(s);
            if (!ItemStack.isSameItemSameTags(snap, cur) || cur.getCount() != snap.getCount()) return false;
        }
        int need = tavernBatch.getHoldFluidAmount();
        if (need > 0) {
            net.minecraftforge.fluids.FluidStack cur = fluids.getInputTank().getFluid();
            if (cur.isEmpty() || cur.getAmount() < need) return false;
            net.minecraft.resources.ResourceLocation fid =
                    net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(cur.getFluid());
            if (fid == null || !fid.toString().equals(tavernBatch.getHoldFluidName())) return false;
        }
        return true;
    }

    /**
     * 完工扣料事务（陈到满级 tick 执行，与持守校验同 tick 严格一致 ⇒ 必成功）：
     * 清计划配料槽（0..2）、整笔抽取计划流体；容器槽不动（分装逐瓶扣）。
     * 防御性复核不过则中止批次归 0（不凭空扣/退任何材料）。
     */
    private void commitTavernFinishConsume() {
        if (!tavernHoldIntact()) {
            tavernBatch.reset();
            currentRecipeId = null;
            progress = 0;
            setChanged();
            return;
        }
        for (int s : tavernBatch.getHoldSlots()) {
            if (s >= 0 && s < TavernBarrelPlan.MAX_INGREDIENT_SLOTS) {
                items.setStackInSlot(s, ItemStack.EMPTY);
            }
        }
        int need = tavernBatch.getHoldFluidAmount();
        if (need > 0) {
            net.minecraftforge.fluids.FluidStack drained = fluids.getInputTank().drain(need,
                    net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            if (drained.getAmount() != need) {
                // 同 tick 刚验证过足够量：不可达；防御回滚并进入故障（批次作废，槽内配料回填同 tick 状态）
                if (!drained.isEmpty()) {
                    fluids.getInputTank().fill(drained, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                }
                tavernStartFault = true;
                tavernBatch.reset();
                currentRecipeId = null;
                progress = 0;
                setChanged();
                return;
            }
        }
        tavernBatch.markConsumed();
        setChanged();
    }

    // ================== WINERY Tavern 空瓶分装 ==================

    /** 分装节奏：每 20 tick（1 秒）自动逐瓶分装一次。 */
    private static final int DISPENSE_INTERVAL = 20;

    /** 槽 4 = Tavern 容器槽索引。 */
    private static final int TAVERN_CARRIER_SLOT = TavernBarrelPlan.MAX_INGREDIENT_SLOTS;

    /** 自动分装：批次可取酒、容器足够、输出空间足够时一次排空本批次可出的全部瓶（§F27）。
     *  cooldown 保留，语义从「逐瓶节奏」改为「两次自动排空之间的最小间隔/防抖」。 */
    private void tryTavernDispense() {
        if (tavernStartFault || tavernDispenseFault) return; // 故障：禁止自动分装
        if (tavernDispenseCooldown > 0) {
            tavernDispenseCooldown--;
            return;
        }
        tavernDispenseCooldown = DISPENSE_INTERVAL;
        if (!tavernBatch.isBrewing()) return;
        // §F27：把单次分装改为循环排空——直到批次耗尽/容器空/输出不再能容纳（三者任一）。
        // 终止性：每次 commit 必 consumeOne（剩余 -1）且容器 shrink(1)，prepare 只在可分时装时返回非 null，不会死循环；
        // guard 兼顶（批次量极端时单 tick 产出上限），命中即维持现状、下个间隔边界继续排。
        int guard = 0;
        while (true) {
            DispensePlan plan = prepareTavernDispense();
            if (plan == null) break;                  // 批次耗尽或容器不合法
            if (!canInsertOutput(plan.result)) break; // 输出空间不足：无损停下，剩余下次再排
            commitTavernDispense(plan);
            if (tavernDispenseFault) break;           // commit 内部异常已置故障：立即停、不重试
            if (++guard > 4096) break;                // 极端批次兑底
        }
    }

    /** 分装计划（只读，不消耗任何资源）。 */
    private static final class DispensePlan {
        final ItemStack result;
        final int carrierSlot;
        DispensePlan(ItemStack result, int carrierSlot) {
            this.result = result;
            this.carrierSlot = carrierSlot;
        }
    }

    /** 准备：只读验证，不消耗任何资源。任一条件不满足返回 null。 */
    private DispensePlan prepareTavernDispense() {
        if (level == null || level.isClientSide) return null;
        if (!tavernBatch.canDispense()) return null; // 未陈到满级（6 典藏）或无剩余瓶 ⇒ 继续等（用户 2026-09-25 拍板）
        if (tavernStartFault || tavernDispenseFault) return null;
        // 配方结果与品质数据可解析
        ItemStack result = buildDispenseResult();
        if (result == null || result.isEmpty()) return null;
        // 容器槽有匹配的合法 carrier
        net.minecraft.world.item.crafting.Ingredient carrier = parseTavernCarrier();
        if (carrier == null) return null;
        ItemStack carrierStack = items.getStackInSlot(TAVERN_CARRIER_SLOT);
        if (carrierStack.isEmpty() || !carrier.test(carrierStack)) return null;
        return new DispensePlan(result, TAVERN_CARRIER_SLOT);
    }

    /** 生成当前批次品质的酒瓶（原版语义：BottleBlockItem.getFilledStack(brewLevel)）。 */
    private ItemStack buildDispenseResult() {
        net.minecraft.resources.ResourceLocation resultId = tavernBatch.getResultItemId();
        if (resultId == null) return null;
        net.minecraft.world.item.Item resultItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(resultId);
        if (resultItem == null || resultItem == net.minecraft.world.item.Items.AIR) return null;
        int brewLevel = tavernBatch.getBrewLevel();
        try {
            java.lang.reflect.Method filled = cn.ism.mekck.util.Reflect.method(
                    resultItem.getClass(), "getFilledStack", int.class);
            if (filled == null) return null;
            Object stack = filled.invoke(resultItem, brewLevel);
            if (stack instanceof ItemStack is && !is.isEmpty()) {
                return is.copy(); // 含原版品质 NBT（BrewLevel）
            }
        } catch (Throwable ignored) {
            // 非 BottleBlockItem：回落普通结果
        }
        ItemStack plain = new ItemStack(resultItem, 1);
        return plain;
    }

    /** 解析批次保存的 carrier（Ingredient JSON）。null = 无法解析。 */
    private net.minecraft.world.item.crafting.Ingredient parseTavernCarrier() {
        String json = tavernBatch.getCarrierJson();
        if (json == null || json.isBlank()) return null;
        try {
            return net.minecraft.world.item.crafting.Ingredient.fromJson(
                    net.minecraft.util.GsonHelper.parse(json));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 槽 4 容器槽合法性校验（isItemValid 用）：仅 Tavern 模式且批次活跃时限制。 */
    private boolean isValidTavernCarrier(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.item.crafting.Ingredient carrier = parseTavernCarrier();
        return carrier != null && carrier.test(stack);
    }

    /** 提交：使用同一份计划，一次成功只产 1 瓶 + 扣 1 carrier + 扣 1 剩余。 */
    private void commitTavernDispense(DispensePlan plan) {
        // 提交前复核（同一计划）：容器仍在、输出空间仍可容纳、批次仍可分装
        net.minecraft.world.item.crafting.Ingredient carrier = parseTavernCarrier();
        if (carrier == null) return;
        ItemStack carrierStack = items.getStackInSlot(plan.carrierSlot);
        if (carrierStack.isEmpty() || !carrier.test(carrierStack)) return;
        if (!tavernBatch.canDispense()) return;
        if (!canInsertOutput(plan.result)) return;
        // 事务提交（内存操作；失败防御进入故障）
        try {
            // ① 扣容器 1 个
            carrierStack.shrink(1);
            // ② 写入品质酒瓶（在 consumeOne 之前，保留最后一瓶品质快照）
            insertOutputDirectly(plan.result.copy());
            // ③ 扣减批次剩余（至 0 时内部 reset 清空批次）
            tavernBatch.consumeOne();
            setChanged();
        } catch (RuntimeException ex) {
            tavernDispenseFault = true; // 异常恢复无法确认：进入持久化故障，禁止自动重试
        }
    }

    /**
     * 提交前复核（同一计划贯穿验证与提交，避免“检查 A 配方、启动 B 配方”）。
     * 配方语义比对：同 ID 配方重新读取生成签名，与计划签名一致才放行。
     */
    private boolean verifyTavernStart(TavernBarrelPlan plan) {
        if (!tavernBatch.isIdle()) return false; // BREWING/STALLED 均不接受
        if (progress != 0) return false; // 普通 Vinery 加工进行中不抢占
        if (level == null) return false;
        net.minecraft.world.item.crafting.RecipeType<?> barrelT = cn.ism.mekck.compat.TavernBarrelCompat.type();
        if (barrelT == null) return false; // tavern 未安装
        // 配方仍有效，且同 ID 配方语义未变（ingredients/fluid/result/unitTime/carrier 一致）
        java.util.Optional<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeOpt =
                level.getRecipeManager().byKey(plan.getRecipeId());
        if (recipeOpt.isEmpty()) return false;
        if (!recipes.matchesRecipeSignature(recipeOpt.get(), plan.getRecipeSignature())) {
            return false; // 数据包重载后同 ID 内容变化 → 拒绝启动
        }
        // 流体仍满且种类匹配（参考 matches 用 isSame）
        net.minecraftforge.fluids.FluidStack planFluid = plan.getFluid();
        if (fluids.getInputTank().getFluidAmount() < planFluid.getAmount()) return false;
        if (!fluids.getInputTank().getFluid().getFluid().isSame(planFluid.getFluid())) return false;
        // 配料槽与计划快照严格复核（同物、同 NBT、同数量——完工扣料口径下起批不扣料，但建批时数量必须与计划一致，
        // 否则瓶数（= 最少配料数）与持守比对基准都会歧义）：
        java.util.List<Integer> consumeSlots = plan.getConsumeSlots();
        java.util.List<ItemStack> snapshots = plan.getSlotSnapshots();
        if (consumeSlots.isEmpty()) {
            for (int s = 0; s < Math.min(TavernBarrelPlan.MAX_INGREDIENT_SLOTS, INPUT_COUNT); s++) {
                if (!items.getStackInSlot(s).isEmpty()) return false;
            }
        } else {
            for (int i = 0; i < consumeSlots.size(); i++) {
                int slot = consumeSlots.get(i);
                ItemStack snap = snapshots.get(i);
                ItemStack cur = items.getStackInSlot(slot);
                if (!net.minecraft.world.item.ItemStack.isSameItemSameTags(snap, cur)) return false;
                if (cur.getCount() != snap.getCount()) return false; // 数量必须与计划一致
            }
            // 多余槽检查：非计划内槽（0..3）也不得放入物品（防吞未参与材料）
            for (int s = 0; s < Math.min(TavernBarrelPlan.MAX_INGREDIENT_SLOTS, INPUT_COUNT); s++) {
                if (consumeSlots.contains(s)) continue;
                if (!items.getStackInSlot(s).isEmpty()) return false;
            }
        }
        return true;
    }

    /** 重新读取同 ID 配方的语义签名并与计划签名比对（类型/Ingredient/流体/结果/unitTime/carrier）。 */

    /**
     * 葡园酒香 vinery:wine_fermentation（26 配方）。完全复刻原版发酵桶的果汁语义：
     * - 果汁**既不是物品也不是流体**，而是桶内两项状态 juiceType（类型字符串）+ fluidLevel（液位）；
     * - 玩家把果汁物品投进果汁格/配料格 → 由 server tick 里的即时吸收逐件充 JUICE_PER_ITEM(=25) 液位，
     *   上限 JUICE_MAX_LEVEL(=100)（数值取自 vinery 配置默认值 maxFluidPerJuice / maxFluidLevel）；
     * - 液位非 0 时**不接受其它类型**的果汁（原版拒收，需先清桶）；
     * - 配方要求**液位池内**同类型 fluidLevel >= getJuiceAmount() 且输入材料满足，酿成后扣掉该数量；
     *   液位池是配方的唯一果汁来源——运行时**不再**就地消费槽内果汁（用户 2026-09-24 定的时序口径）。
     * 因此**每件果汁能酿多少次与原版完全一致**（25 / juiceAmount 次）。
     * 空酒瓶：wineBottleRequired=true 时另需并消耗 1 个 vinery:wine_bottle。
     */
    /**
     * winery 果汁格可接受的物品（“仅果汁”）：vinery 瓶装果汁（标签驱动，充液位池）
     * ∪ 果汁类流体容器（森罗物语酒馆的 grape_juice/watermelon_juice… 及 vinery 果汁：以流体注册名含 "juice" 判定）。
     * 非果汁的普通流体桶（水/奶等）不算果汁→不进果汁格也不进普通格。
     */
    public static boolean isWineryJuiceInput(ItemStack stack) {
        if (stack.isEmpty()) return false;
        // vinery 瓶装果汁（vinery JuiceUtil 标签驱动，isJuice 布尔优先，再取 juiceTypeOf）
        if (cn.ism.mekck.util.VineryJuice.isJuice(stack)) return true;
        if (cn.ism.mekck.util.VineryJuice.juiceTypeOf(stack) != null) return true;
        // 反射不可用时按物品注册名兑底：vinery red/white_grapejuice、apple_juice、森罗酒馆果汁饮品等 path 含 juice
        ResourceLocation juiceItemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (juiceItemId != null && juiceItemId.getPath().toLowerCase().contains("juice")) return true;
        // 含果汁流体的容器（森罗酒馆/vinery 等果汁流体名均含 "juice"）
        try {
            var lazy = stack.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER_ITEM);
            var fr = lazy.orElse(null);
            if (fr != null) {
                net.minecraftforge.fluids.FluidStack fs = fr.getFluidInTank(0);
                if (fs != null && !fs.isEmpty()) {
                    ResourceLocation fl = ForgeRegistries.FLUIDS.getKey(fs.getFluid());
                    if (fl != null && fl.getPath().toLowerCase().contains("juice")) {
                        lazy.invalidate();
                        return true;
                    }
                }
            }
            lazy.invalidate();
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** winery 流体物品输入格合法集：带 {@code FLUID_HANDLER_ITEM} 且内含非空流体的容器（桶/储罐）。 */
    public static boolean isFluidContainerItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        try {
            var lazy = stack.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER_ITEM);
            var holder = lazy.orElse(null);
            if (holder == null) { lazy.invalidate(); return false; }
            net.minecraftforge.fluids.FluidStack fs = holder.getFluidInTank(0);
            lazy.invalidate();
            return fs != null && !fs.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 陈酿机：把果汁格（含旧档遗留在 0..4 的果汁瓶）里的果汁**即时吸收**进液位池，
     * 复刻 vinery 发酵桶「投料即充液位、锁定类型」的语义（不依赖配方是否凑齐启动）。
     * 规则：液位为 0 时首次吸收确立 juiceType；液位&gt;0 时只接受同类型（异类需先潜行清桶）；上限 JUICE_MAX_LEVEL。
     * 吸收后扣减对应槽数量，setChanged 触发 ContainerData 同步。
     */

    // ── 流体桶抽取诊断（用户 2026-09-24 二次反馈：桶放得进果汁格却没被抽走）───────────────────
    // 抽取判据每 tick 都会跑，直接打日志会刷屏 ⇒ 记住上次原因 + 冷却计数，仅原因变化或每 100 tick 补一条。
    @Nullable
    String lastDrainReject;
    int drainRejectCooldown;

    /**
     * 果汁准入拒收诊断（用户 2026-09-24：区域果汁「点上去没反应、瓶子还在鼠标上」= {@code isItemValid} 回了 false，
     * 不是被吸收后又弹回）。静态排查已直读 lets-do-vinery-1.4.41 的 jar：{@code JuiceUtil} 字节码里
     * red/white 的 general/savanna/taiga/jungle + crimson/warped 全部注册（{@code getJuiceType} 会返回
     * {@code red_savanna} 这类非空串），物品 id 与 tag 一一对应，而本机果汁格准入是「标签命中 ∪ 物品 id 含 juice
     * ∪ 内含流体名含 juice」三重放行——代码里**找不到**只认普通葡萄汁的分支 ⇒ 只能靠拒收现场定病灶：
     * 到底玩家点中的是哪一格、那件物品的真实 id 是什么、四个判定各自的结果、当时液位/类型。
     * <p>
     * {@code isItemValid} 每次鼠标悬停都会被调用 ⇒ 与 {@link #noteFluidIntake} 同款限流（原因不变则 100 次只报一次）。
     * 客户端与 integrate server 两端各跑一遍判定，差值本身也是证据 ⇒ 不过滤 side，只在日志里标出来。
     */
    @Nullable
    private String lastJuiceRejectKey;
    private int juiceRejectCooldown;

    void noteJuiceReject(int slot, ItemStack stack, String branch) {
        if (kind != MachineKind.WINERY || stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        // 只盯本问题相关的物品（果汁名 / 瓶子名），否则 winery 格子上任何一次杂项拖拽都会串扰诊断
        boolean looksRelevant = (id != null && (id.getPath().toLowerCase().contains("juice")
                || id.getPath().toLowerCase().contains("bottle"))) || isWineryJuiceInput(stack);
        if (!looksRelevant) return;
        String juiceTypeOf = String.valueOf(cn.ism.mekck.util.VineryJuice.juiceTypeOf(stack));
        String isJuice = String.valueOf(cn.ism.mekck.util.VineryJuice.isJuice(stack));
        String nameHit = String.valueOf(id != null && id.getPath().toLowerCase().contains("juice"));
        String fluidHit = String.valueOf(isFluidContainerItem(stack));
        String key = branch + "|slot" + slot + "|" + id;
        // 冷却与 key 无关地先递减：hover 驱动的准入判定会在几帧内轮翻换 slot/item，
        // 「换 key 即重报」曾被击穿（2026-09-24 单次会话 1.8 万行）。现在同因 100 tick、换因也要静默 20 tick。
        if (juiceRejectCooldown > 0) {
            juiceRejectCooldown--;
            return;
        }
        juiceRejectCooldown = key.equals(lastJuiceRejectKey) ? 100 : 20;
        lastJuiceRejectKey = key;
        String side = level != null && level.isClientSide ? "client" : "server";
        // §F24：跨模组兼容已定案，脚手架无需常驻 info（同因每 100 tick 重发仍刷屏）→ 降 debug；
        // 需要现场排查时把 mekck 日志级别调到 debug 即可复现，key/cooldown 逻辑不变。
        com.mojang.logging.LogUtils.getLogger().debug(
                "[mekck] 陈酿机果汁拒收 branch={} slot={} item={} x{} 液位={}/{} 判定{{juiceTypeOf={}, isJuice={}, 名字含juice={}, 流体容器={}}} side={}",
                branch, slot, id, stack.getCount(), juiceLevel, juiceType,
                juiceTypeOf, isJuice, nameHit, fluidHit, side);
    }

    /**
     * 通用输入槽拒收诊断。{@link #noteJuiceReject} 只盯名字含 juice/bottle 的物品，
     * 于是「普通材料被配料表拒收」这条路径完全静默——2026-09-24 排查「小麦/冰块放不进陈酿机」时，
     * 日志里一个字都没有，只能靠直读 jar 配方 + 解存档 region 才定位到病灶（当时真凶是批次锁，不是白名单）。
     * 补上这一层，将来白名单真的坏了才有现场可看。
     * <p>
     * {@code isItemValid} 由鼠标悬停驱动，几帧内会轮翻换 slot/item ⇒ 限流比果汁那层更保守：
     * 同因 200 tick、换因也静默 50 tick；且与果汁诊断各用独立计数器，互不抢占冷却窗口。
     */
    @Nullable
    private String lastInputRejectKey;
    private int inputRejectCooldown;

    private void noteInputReject(int slot, ItemStack stack, String branch) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String key = branch + "|slot" + slot + "|" + id;
        if (inputRejectCooldown > 0) {
            inputRejectCooldown--;
            return;
        }
        inputRejectCooldown = key.equals(lastInputRejectKey) ? 200 : 50;
        lastInputRejectKey = key;
        String side = level != null && level.isClientSide ? "client" : "server";
        // §F24：与果汁诊断同为排查脚手架（真凶已定位定案），降 debug 不常驻 info
        com.mojang.logging.LogUtils.getLogger().debug(
                "[mekck] {} 输入槽拒收 branch={} slot={} item={} x{} 批次={} side={}",
                kind, branch, slot, id, stack.getCount(), tavernBatch.getState(), side);
    }

    // ── 侧面 IO 自动输入的落点（用户 2026-09-24：只给陈酿机开小灶，全局 EXTRA 语义已记入待办） ──

    /**
     * 陈酿机自动输入（外部经输入面推入、以及本机 PULL_INPUT 主动拉入）该把物品放进哪一格。
     * <ul>
     * <li>果汁：配料区已有<b>糖</b>（{@code minecraft:sugar}，已核实 jar 内 9 条用糖配方全写作它）
     *     → <b>只进果汁格</b>（配料位留给糖，例如苹果西打）；</li>
     * <li>果汁：无糖 → 进「果汁格 / 配料区」中<b>同类数量更少</b>的一格（相等时优先果汁格）——
     *     苹果酒既要 apple 液位又要把 1 瓶苹果汁当配料，均衡投料才能让流水线一次喂齐两瓶；</li>
     * <li>载具（酒瓶类）→ 酒瓶槽；其余走配料区（先补已有同类的那格，再找第一个空格）。</li>
     * </ul>
     * 返回 -1 表示不干预（非 winery / 无落点），由调用方按常规区间处理。本方法只作用于<b>自动</b>输入，
     * 玩家手动放置走 GUI 的 Slot，不经过这里（否则想往配料格留一瓶苹果汁都做不到）。
     * <p>
     * {@code source} = 主动拉取时被抽的相邻方块实体（推入路径无此信息，传 null）；
     * 用于「别抽走邻居自己还要用的载具」保护，见 {@link #machineNeedsThisCarrier}。
     */
    private int autoInputTargetSlot(ItemStack stack, BlockEntity source) {
        if (kind != MachineKind.WINERY || stack == null || stack.isEmpty()) return -1;
        if (isWineryCarrierItem(stack)) {
            // 用户 2026-09-24：抽取模式不得把鲜果榨汁机里的葡萄酒瓶抽走——瓶子是它苹果汁配方的载具，被抽干就永久断供。
            // 只限载具：扩到普通配料会让「陈酿机不再抽邻居的水果」变成新的意外。
            if (source instanceof SimpleMachineBlockEntity other && other.machineNeedsThisCarrier(stack)) return -1;
            return slotRoomFor(WINERY_CARRIER_SLOT, stack) > 0 ? WINERY_CARRIER_SLOT : -1;
        }
        if (isWineryJuiceInput(stack)) {
            int t = autoJuiceTargetSlot(stack);
            if (t >= 0) return t;
        }
        return autoIngredientTargetSlot(stack);
    }

    /** 果汁落点：有糖只进果汁格；无糖进「果汁格 / 配料区」中同类更少的一格（相等优先果汁格）。 */
    private int autoJuiceTargetSlot(ItemStack stack) {
        boolean hasSugar = false;
        for (int s = 0; s < TavernBarrelPlan.MAX_INGREDIENT_SLOTS; s++) {
            ItemStack in = items.getStackInSlot(s);
            if (!in.isEmpty() && in.is(net.minecraft.world.item.Items.SUGAR)) {
                hasSugar = true;
                break;
            }
        }
        int juiceRoom = items.isItemValid(JUICE_SLOT, stack) ? slotRoomFor(JUICE_SLOT, stack) : 0;
        if (hasSugar) return juiceRoom > 0 ? JUICE_SLOT : -1;
        int inGrid = 0;
        for (int s = 0; s < TavernBarrelPlan.MAX_INGREDIENT_SLOTS; s++) inGrid += sameItemCountIn(s, stack);
        int ing = autoIngredientTargetSlot(stack);
        if (sameItemCountIn(JUICE_SLOT, stack) <= inGrid && juiceRoom > 0) return JUICE_SLOT;
        if (ing >= 0) return ing;
        return juiceRoom > 0 ? JUICE_SLOT : -1;
    }

    /** 常规配料落点：先补已有同类的那格，再找第一个可放的空格（批次锁定时 isItemValid 自然为 false）。 */
    private int autoIngredientTargetSlot(ItemStack stack) {
        int empty = -1;
        for (int s = 0; s < TavernBarrelPlan.MAX_INGREDIENT_SLOTS; s++) {
            if (!items.isItemValid(s, stack)) continue;
            ItemStack in = items.getStackInSlot(s);
            if (in.isEmpty()) {
                if (empty < 0) empty = s;
            } else if (ItemStack.isSameItemSameTags(in, stack) && slotRoomFor(s, stack) > 0) {
                return s;
            }
        }
        return empty;
    }

    /** 某槽对该物品的<b>实际</b>余量（simulate 插入实测，兼容各槽各自的上限覆写；探测量按一组封顶）。 */
    private int slotRoomFor(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.getSlots() || stack.isEmpty()) return 0;
        int probe = Math.min(64, Math.max(1, stack.getMaxStackSize()));
        return probe - items.insertItem(slot, stack.copyWithCount(probe), true).getCount();
    }

    /** 某槽内与参照物同种同 NBT 的数量（不像则 0）。 */
    private int sameItemCountIn(int slot, ItemStack ref) {
        ItemStack in = items.getStackInSlot(slot);
        return !in.isEmpty() && ItemStack.isSameItemSameTags(in, ref) ? in.getCount() : 0;
    }

    /**
     * 本机是否<b>仍然需要</b>该载具物品（供相邻机器拉取前询问，见 {@link #autoInputTargetSlot}）。
     * 载具都存在 {@code getIngredients()} 之外（vinery 是布尔字段、tavern 是 carrier 字段），
     * 通用的 {@link #machineInputMatches} 认不出，必须按 kind 单独问；判定读不到时按「不需要」回答，
     * 宁可照旧抽走，也不要把整条 IO 静默锁死。
     */
    private boolean machineNeedsThisCarrier(ItemStack stack) {
        if (isVineryWineBottle(stack)) {
            return switch (kind) {
                case JUICER -> juicerHasBottleRequiringRecipe();
                case WINERY -> wineryHasBottleRequiringRecipe();
                default -> false;
            };
        }
        return kind == MachineKind.WINERY && cn.ism.mekck.compat.TavernBarrelCompat.isCarrier(level, stack);
    }

    /** 本机配方表里是否存在「要求空酒瓶」的 {@code vinery:wine_fermentation} 配方（与榨汁机同口径）。 */
    private boolean wineryHasBottleRequiringRecipe() {
        if (level == null) return false;
        RecipeType<?> rt = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("vinery", "wine_fermentation"));
        if (rt == null) return false;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, rt)) {
            if (callNoArg(r, "isWineBottleRequired") instanceof Boolean b && b) return true;
        }
        return false;
    }

    /** 流体容器抽取预演结果：本次可抽出的流体 + 抽空后容器的形态（EMPTY = 容器自消耗）。 */

    /** 走 {@code FLUID_HANDLER_ITEM} 能力的预演抽取。 */

    /**
     * 返还物应落到哪个槽。
     * <p>
     * {@code vinery:wine_bottle} 回**空瓶槽**（{@link #WINERY_CARRIER_SLOT}）而不是返还格——酒瓶是陈酿的
     * 载具，槽 3 本就是「载具只进这一格、这一格只收载具」的角色格（见 {@link #acceptsInput} WINERY 分支），
     * 而 {@code matchIngredients} 的扫描范围含槽 3 ⇒ 倒完汁的空瓶就近续回载具位，下一批陈酿直接取用，
     * 玩家不必再从返还格搬一次（用户 2026-09-24 要求）。其余返还物（抽空后的铁桶等）仍走 {@link #RETURN_SLOT}。
     * </p>
     */

    /** 目标返还槽（含溢出位）是否放得下该返还物。 */

    /**
     * 返还物的可落位总数：主槽（vinery 酒瓶 → 空瓶槽 3，其余 → 退还槽 11）先收，装不下的余量溢到通用退还槽。
     * <p>吸收链拿它当「还瓶放得下才吸」的前提，并按它**收敛**吸收量（宁少吸也不吞瓶）。
     * <b>读（本方法）与写（{@link #insertReturn}）必须同用 {@link #returnSlotRoom} 一个口径</b>：
     * 上一版两侧各自算，于是出现「主槽已被还瓶灌成超大堆、另一格又只能收 16」的组合时，两项 16 上限相加
     * 溢出为负 ⇒ 余量判定恒假 ⇒ 果汁**整体卡死不吸收**（实机日志 x937 那一条）。
     */

    /** 把返还物并入目标返还槽（主槽优先，超出部分溢到通用退还槽；均由 {@link #returnSlotRoom} 预演，不吞物品）。 */

    /** take 个 vinery 果汁瓶倒空后的空瓶集；取不到 {@code vinery:wine_bottle} 时返回空集（退化为不还瓶）。 */


    // ── 鲜果榨汁机：苹果 → 苹果汁（apple_mashing + apple_fermenting 两步合一）＋ tavern pressing_tub ──


    /** vinery 果汁：直接苹果浆（fermenting）优先，苹果两步合一（mashing→fermenting）次之。缺任一类型只跳过。 */

    /**
     * vinery {@code apple_fermenting} 是否要求空酒瓶作载具。判据名以 {@code requiresBottle()} 为准
     * （本仓 {@code javap} 反编译 vinery 1.4.41 {@code ApplePressFermentingRecipe} 坐实：
     * {@code private final boolean requiresBottle} + 同名 public 访问器），兼容回退陈酿桶写法的
     * {@code isWineBottleRequired}；两者都读不到时按「不要瓶」处理，避免 vinery 改名后直接锁死榨汁机。
     */
    static boolean fermRequiresBottle(net.minecraft.world.item.crafting.Recipe<?> ferm) {
        Object v = callNoArg(ferm, "requiresBottle");
        if (!(v instanceof Boolean)) v = callNoArg(ferm, "isWineBottleRequired");
        return v instanceof Boolean b && b;
    }

    /** 输入槽 {@code from..INPUT_COUNT-1} 里第一个 {@code vinery:wine_bottle}，没有返回 -1。 */
    int findVineryBottleSlot(int from) {
        for (int s = from; s < INPUT_COUNT; s++) {
            if (isVineryWineBottle(items.getStackInSlot(s))) return s;
        }
        return -1;
    }

    /** 是否 {@code vinery:wine_bottle}（精确 id；不含酒馆 {@code empty_bottle}，后者由 {@link #isWineryCarrierItem} 管）。 */
    static boolean isVineryWineBottle(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "vinery".equals(id.getNamespace()) && "wine_bottle".equals(id.getPath());
    }

    /** 本机配方表里是否存在「要求空酒瓶」的 {@code vinery:apple_fermenting} 配方（决定要不要给榨汁机放行酒瓶）。 */
    private boolean juicerHasBottleRequiringRecipe() {
        if (level == null) return false;
        RecipeType<?> fermT = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("vinery", "apple_fermenting"));
        if (fermT == null) return false;
        for (Recipe<?> r : cn.ism.mekck.util.RecipeCache.all(level, fermT)) {
            if (fermRequiresBottle(r)) return true;
        }
        return false;
    }

    // ── 烘焙机：bakery:baking_station（多输入 → 蛋糕/司康等）──


    /** 反射调用无参方法，失败返回 null。 */
    static Object callNoArg(Recipe<?> r, String method) {
        // 走 Reflect 的元数据缓存：getMethod 已按"类 → 方法名"缓存，热路径只做一次 Map 查找
        return cn.ism.mekck.util.Reflect.call(r, method);
    }

    /** 按注册名取物品并包成 Ingredient；物品不存在（未装对应模组）时返回 null。 */
    static Ingredient itemIng(String namespace, String path) {
        net.minecraft.world.item.Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(namespace, path));
        return it == null || it == net.minecraft.world.item.Items.AIR ? null : Ingredient.of(it);
    }

    /** 把反射取到的容器 ItemStack 包成 Ingredient；空/null 返回 null。 */

    // ── 搅拌机：bakeries:blender（26 配方，1~9 输入 → 1 输出）──

    /**
     * 该机器是否启用扩展输入槽（10..13，合计 9 槽）。
     * 需要超过 5 个材料的配方：搅拌机（bakeries:blender 最多 9）、智能烤炉（多输入锅类）、
     * 智能料理台（youkaishomecoming:cuisine_mixed 最多 7）、发酵机（youkaishomecoming:simple_fermentation 最多 7）。
     */
    public boolean usesExtendedInputSlots() {
        return kind == MachineKind.BLENDER || kind == MachineKind.STOVE
                || kind == MachineKind.SUSHI_MAKER || kind == MachineKind.FERMENTER;
    }

    /** 该机器的有效输入槽索引（启用扩展槽 = 0..4 + 10..13，共 9 槽；否则 = 0..4）。 */
    java.util.List<Integer> activeInputSlots() {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        for (int i = 0; i < INPUT_COUNT; i++) list.add(i);
        if (usesExtendedInputSlots()) {
            for (int i = EXT_INPUT_START; i < EXT_INPUT_START + EXT_INPUT_COUNT; i++) list.add(i);
        }
        return list;
    }

    /** 茶艺机的网络拉料目标：当前输入能合成的茶配方材料。 */
    private List<cn.ism.mekck.util.AE2InputSpec> teaPullInputs() {
        int filled = 0;
        ItemStack sample = ItemStack.EMPTY;
        for (int s : activeInputSlots()) {
            ItemStack st = items.getStackInSlot(s);
            if (!st.isEmpty()) { filled++; sample = st; }
        }
        if (filled == 0) return List.of();
        for (Recipe<?> r : allRecipesOfKind()) {
            if (!isSimplyTeaResult(r)) continue;
            try {
                List<Ingredient> ings = r.getIngredients();
                boolean hit = false;
                for (Ingredient ing : ings) {
                    if (ing != null && !ing.isEmpty() && ing.test(sample)) { hit = true; break; }
                }
                if (!hit) continue;
                List<cn.ism.mekck.util.AE2InputSpec> specs = new ArrayList<>();
                for (Ingredient ing : ings) {
                    if (ing != null && !ing.isEmpty()) specs.add(new cn.ism.mekck.util.AE2InputSpec(ing));
                }
                return specs;
            } catch (Throwable ignored) {
            }
        }
        return List.of();
    }

    /**
     * 提交前输入验证：每个消耗槽当前仍满足配方的 Ingredient 条件与数量；输入流体仍充足。
     * 失败表示加工过程中输入被抽走/替换/数量不足，不允许扣料提交。
     */

    /** 提交前统一验证：输入（物品+流体）仍满足 + 输出（物品+流体）可完整接收。 */

    boolean canInsertOutput(ItemStack result) {
        if (result.isEmpty()) return true;
        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT);
        if (existing.isEmpty()) return true;
        if (ItemStack.isSameItemSameTags(existing, result)) {
            // 上限必须取 handler 的 getSlotLimit（产物槽 = Integer.MAX_VALUE），而不是 stack 的 getMaxStackSize（64）：
            // 产物堆到 ≥64 时这里恒判「放不下」⇒ 机器停摆；而真正写产物的 insertOutputDirectly 走 existing.grow()
            // 本就不设 64 闸 ⇒ 判定与写入口径分裂（用户 2026-09-24 §F14 #3）。同因下游：陈酿机
            // 「果汁不转液面」也是卡在 canCommitRecipe → canAcceptOutputs → 本方法上（输入侧无 64 闸）。
            return cn.ism.mekck.util.CountMath.canStack(existing.getCount(), result.getCount(), items.getSlotLimit(OUTPUT_SLOT));
        }
        return false;
    }

    /**
     * 陈酿机「为什么不开工」诊断（2026-09-25）：液位池满了而果汁不再被消耗时，过去只有一句
     * 「吸收：液位已满」，玩家看不出机器到底卡在哪 ⇒ 把停摆的真因（异种产物占格 / 红石 / 供电 /
     * 同种液位不够）直接写进日志。只在 WINERY、配方已匹配却停工时触发，限流同 {@link #noteJuiceReject}。
     */
    private void noteWineryStall(@Nullable MatchedRecipe recipe, boolean canWork) {
        if (canWork || recipe == null || level == null || level.isClientSide) return;
        String why;
        ItemStack out = items.getStackInSlot(OUTPUT_SLOT);
        if (!canInsertOutput(recipe.result)) {
            why = out.isEmpty() ? "输出格收不下产物" : "输出格被异种物品占用：" + out.getDescriptionId() + " ×" + out.getCount();
        } else if (!canFunctionRedstone()) {
            why = "红石控制不满足";
        } else if (!hasCreativeUpgrade() && energy.getEnergyStored() < (int) Math.ceil(kind.energyPerTick)) {
            why = "电量不足或未接电源";
        } else {
            why = "同种液位不够（已存 " + juiceLevel + "，先潜行右键清空或等配方消耗）";
        }
        noteJuiceReject(OUTPUT_SLOT, recipe.result, "停摆：" + why);
    }

    /** 机器内部产物写入：直接 set/grow 产物格（insertItem 会被产物槽 isItemValid=false 拦截）。 */
    void insertOutputDirectly(ItemStack result) {
        if (result.isEmpty()) return;
        ItemStack existing = items.getStackInSlot(OUTPUT_SLOT);
        if (existing.isEmpty()) {
            items.setStackInSlot(OUTPUT_SLOT, result.copy());
        } else if (ItemStack.isSameItemSameTags(existing, result)) {
            existing.grow(result.getCount());
        }
    }

    // ================== 能力 ==================

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (!this.remove) {
            if (cap == ForgeCapabilities.ITEM_HANDLER) {
                if (side == null) return fullItemCapability.cast();
                SideMode mode = sideConfig[side.ordinal()];
                return switch (mode) {
                    case PULL_INPUT -> inputItemCapability.cast();
                    case PUSH_OUTPUT -> outputItemCapability.cast();
                    default -> fullItemCapability.cast();
                };
            }
            if (cap == ForgeCapabilities.ENERGY) {
                return energyCapability.cast();
            }
            if (cap == ForgeCapabilities.FLUID_HANDLER) {
                // 流体侧配：PULL_INPUT 只暴露输入罐（可注入）、PUSH_OUTPUT 只暴露输出罐（可抽取）
                if (side != null) {
                    SideMode fluidMode = fluids.getFluidSideConfig()[side.ordinal()];
                    if (fluidMode == SideMode.PULL_INPUT) return fluids.getInputFluidCapability().cast();
                    if (fluidMode == SideMode.PUSH_OUTPUT) return fluids.getOutputFluidCapability().cast();
                }
                return fluids.getFluidCapability().cast();
            }
            // 加热类机器：暴露 Mekanism 热能力，供热力设备（电阻加热器/热导管等）传导
            if (isHeatingMachine() && cap == mekanism.common.capabilities.Capabilities.HEAT_HANDLER) {
                return heatCapability.cast();
            }
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        fullItemCapability.invalidate();
        inputItemCapability.invalidate();
        outputItemCapability.invalidate();
        energyCapability.invalidate();
        fluids.invalidateCaps();
    }

    /** 输入/输出流体罐合并能力（发酵机用；输入 0 号罐，输出 1 号罐）。 */
    /** 只允许注入（供 PULL_INPUT 侧）。 */


    public net.minecraftforge.fluids.capability.templates.FluidTank getInputTank() {
        return fluids.getInputTank();
    }

    public net.minecraftforge.fluids.capability.templates.FluidTank getOutputTank() {
        return fluids.getOutputTank();
    }

    private class InputItemHandler implements IItemHandler {
        private final IItemHandler handler = items;

        @Override public int getSlots() { return INPUT_COUNT; }
        @Override public ItemStack getStackInSlot(int slot) { return handler.getStackInSlot(slot); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= INPUT_COUNT) return stack;
            // 陈酿机小灶：外部经输入面推来的物品按规则改投落点（果汁可能进视图外的果汁格）。
            // 玩家手动放置不走这个包装层，手感不变；非 winery 间接永远返回 -1（行为不变）。
            // 推入路径拿不到推送方，source 传 null（不启用载具保护）。
            int t = autoInputTargetSlot(stack, null);
            if (t >= 0) return items.insertItem(t, stack, simulate);
            return handler.insertItem(slot, stack, simulate);
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return handler.getSlotLimit(slot); }
        @Override public boolean isItemValid(int slot, ItemStack stack) {
            // 推送方会先问预检：若只按传入的 0..4 回答，被分流到果汁格的果汁会在预检阶段就被拒
            int t = autoInputTargetSlot(stack, null);
            if (t >= 0) return items.isItemValid(t, stack);
            return handler.isItemValid(slot, stack);
        }
    }

    private class OutputItemHandler implements IItemHandler {
        private final IItemHandler handler = items;

        @Override public int getSlots() { return 1; }
        @Override public ItemStack getStackInSlot(int slot) { return handler.getStackInSlot(OUTPUT_SLOT); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return handler.extractItem(OUTPUT_SLOT, amount, simulate);
        }
        @Override public int getSlotLimit(int slot) { return handler.getSlotLimit(OUTPUT_SLOT); }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    }

    // ================== NBT ==================

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        cn.ism.mekck.compat.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        // 不写 MachineKind：kind 是 final 字段、构造期由方块决定，load 从不读该键。
        // 写入只会让「存档里的键」与「可恢复的状态」产生误导性偏差。
        tag.put("Items", items.serializeNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Progress", progress);
        tag.put("SpeedUpgrade", speedTracker.save());
        tag.put("EnergyUpgrade", energyTracker.save());
        tag.put("CreativeUpgrade", creativeTracker.save());
        if (isHeatingMachine()) tag.put("HeatCapacitor", heatComponent.save());
        if (currentRecipeId != null) {
            tag.putString("CurrentRecipeId", currentRecipeId.toString());
        }
        // WINERY 专属：果汁液位池（原版发酵桶 juiceType + fluidLevel，独立键）
        if (kind == MachineKind.WINERY) {
            tag.putInt("MekckJuiceLevel", juiceLevel);
            tag.putString("MekckJuiceType", juiceType);
        }
        // WINERY 专属：Tavern 酿造批次（独立键，避免与 progress/CurrentRecipeId/流体/AE2 键冲突）
        if (kind == MachineKind.WINERY) {
            net.minecraft.nbt.CompoundTag batch = new net.minecraft.nbt.CompoundTag();
            tavernBatch.save(batch);
            tag.put("MekckTavernBatch", batch);
            // Tavern 故障持久化：启动/分装故障重启不自动消失（防自动重试扣料/分装）
            if (tavernStartFault || tavernDispenseFault) {
                net.minecraft.nbt.CompoundTag fault = new net.minecraft.nbt.CompoundTag();
                fault.putBoolean("StartFault", tavernStartFault);
                fault.putBoolean("DispenseFault", tavernDispenseFault);
                tag.put("MekckTavernFault", fault);
            }
        }
        tag.putInt("Redstone", redstoneControl.ordinal());
        tag.putInt("SideConfig", encodeSideConfig());
        tag.putInt("FluidSideConfig", encodeSideConfig(fluids.getFluidSideConfig()));
        if (orderRecipeId != null) {
            tag.putString("OrderRecipeId", orderRecipeId.toString());
            tag.putInt("OrderQuantity", orderQuantity);
            tag.putInt("OrderCompleted", orderCompleted);
        }
        // ME 自动下单开关与订单无关：必须无条件写出，否则无订单时重载会静默复位为默认 true。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        fluids.writeTanks(tag);
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        cn.ism.mekck.compat.AE2Compat.load(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.load(this, tag);
        items.deserializeNBT(tag.getCompound("Items"));
        energy.receiveEnergy(tag.getInt("Energy"), false);
        progress = tag.getInt("Progress");
        if (tag.contains("SpeedUpgrade", net.minecraft.nbt.Tag.TAG_COMPOUND)) speedTracker.load(tag.getCompound("SpeedUpgrade"));
        if (tag.contains("EnergyUpgrade", net.minecraft.nbt.Tag.TAG_COMPOUND)) energyTracker.load(tag.getCompound("EnergyUpgrade"));
        if (tag.contains("CreativeUpgrade", net.minecraft.nbt.Tag.TAG_COMPOUND)) creativeTracker.load(tag.getCompound("CreativeUpgrade"));
        if (isHeatingMachine() && tag.contains("HeatCapacitor", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            heatComponent.load(tag.getCompound("HeatCapacitor"));
        }
        currentRecipeId = tag.contains("CurrentRecipeId")
                ? net.minecraft.resources.ResourceLocation.tryParse(tag.getString("CurrentRecipeId")) : null;
        // WINERY 专属：果汁液位池恢复（旧存档无字段 → 空桶）
        if (kind == MachineKind.WINERY) {
            juiceLevel = Math.max(0, Math.min(JUICE_MAX_LEVEL, tag.getInt("MekckJuiceLevel")));
            juiceType = tag.getString("MekckJuiceType");
        } else {
            juiceLevel = 0;
            juiceType = "";
        }
        // WINERY 专属：Tavern 酿造批次恢复（旧存档无字段 → 空闲，不影响 Vinery）
        if (kind == MachineKind.WINERY && tag.contains("MekckTavernBatch", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            tavernBatch.load(tag.getCompound("MekckTavernBatch"));
        } else {
            tavernBatch.reset();
        }
        // Tavern 故障恢复：重启/重载不得自动消失（禁止自动重试）
        tavernStartFault = false;
        tavernDispenseFault = false;
        if (kind == MachineKind.WINERY && tag.contains("MekckTavernFault", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            net.minecraft.nbt.CompoundTag fault = tag.getCompound("MekckTavernFault");
            tavernStartFault = fault.getBoolean("StartFault");
            tavernDispenseFault = fault.getBoolean("DispenseFault");
        }
        int rs = tag.getInt("Redstone");
        if (rs >= 0 && rs < RedstoneControl.values().length) redstoneControl = RedstoneControl.values()[rs];
        decodeSideConfig(tag.getInt("SideConfig"));
        if (tag.contains("FluidSideConfig")) {
            decodeSideConfig(fluids.getFluidSideConfig(), tag.getInt("FluidSideConfig"));
        }
        if (tag.contains("OrderRecipeId")) {
            orderRecipeId = net.minecraft.resources.ResourceLocation.tryParse(tag.getString("OrderRecipeId"));
            orderQuantity = tag.getInt("OrderQuantity");
            orderCompleted = tag.getInt("OrderCompleted");
        }
        meOrderEnabled = !tag.contains("MeOrderEnabled") || tag.getBoolean("MeOrderEnabled");
        fluids.readTanks(tag);
        if (tag.contains("CustomName")) {
            customName = Component.Serializer.fromJson(tag.getString("CustomName"));
        }
    }

    /** 保存到掉落物品（含自定义名与物品数据）。 */
    public void saveToItem(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        tag.put("Items", items.serializeNBT());
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Progress", progress);
        tag.put("SpeedUpgrade", speedTracker.save());
        tag.put("EnergyUpgrade", energyTracker.save());
        tag.put("CreativeUpgrade", creativeTracker.save());
        if (isHeatingMachine()) tag.put("HeatCapacitor", heatComponent.save());
        if (currentRecipeId != null) {
            tag.putString("CurrentRecipeId", currentRecipeId.toString());
        }
        // WINERY 专属：果汁液位池（原版发酵桶 juiceType + fluidLevel，独立键）
        if (kind == MachineKind.WINERY) {
            tag.putInt("MekckJuiceLevel", juiceLevel);
            tag.putString("MekckJuiceType", juiceType);
        }
        // WINERY 专属：Tavern 酿造批次（独立键，避免与 progress/CurrentRecipeId/流体/AE2 键冲突）
        if (kind == MachineKind.WINERY) {
            net.minecraft.nbt.CompoundTag batch = new net.minecraft.nbt.CompoundTag();
            tavernBatch.save(batch);
            tag.put("MekckTavernBatch", batch);
            // Tavern 故障持久化：启动/分装故障重启不自动消失（防自动重试扣料/分装）
            if (tavernStartFault || tavernDispenseFault) {
                net.minecraft.nbt.CompoundTag fault = new net.minecraft.nbt.CompoundTag();
                fault.putBoolean("StartFault", tavernStartFault);
                fault.putBoolean("DispenseFault", tavernDispenseFault);
                tag.put("MekckTavernFault", fault);
            }
        }
        tag.putInt("Redstone", redstoneControl.ordinal());
        tag.putInt("SideConfig", encodeSideConfig());
        tag.putInt("FluidSideConfig", encodeSideConfig(fluids.getFluidSideConfig()));
        // 以下三项必须与 saveAdditional 保持一致，否则挖下来的机器与留在世界里的状态不同：
        // 流体罐内容会直接蒸发，ME 自动下单开关会静默复位。
        // MachineKind 不在此列：kind 是 final 字段、构造期从方块读出，load 从不读该键。
        tag.putBoolean("MeOrderEnabled", meOrderEnabled);
        fluids.writeTanks(tag);
        // 同理必须调用两个外部持久化助手：AE2 的自动补料清单（缺失会丢玩家逐条配的补料规则）
        // 与放置器 UUID（缺失会让已放置的机器被当成新机器）。load 侧两者都会读回，
        // 此处不写就等于「挖起来再放下」必丢，而 getDrops 为空时物品是状态的唯一载体。
        cn.ism.mekck.compat.AE2Compat.saveAdditional(this, tag);
        cn.ism.mekck.advancement.PlacerPersist.save(this, tag);
        if (orderRecipeId != null) {
            tag.putString("OrderRecipeId", orderRecipeId.toString());
            tag.putInt("OrderQuantity", orderQuantity);
            tag.putInt("OrderCompleted", orderCompleted);
        }
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName));
        }
        stack.getOrCreateTag().put("BlockEntityTag", tag);
    }
}
