package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 14 台<b>遗留单机</b>的公共基类 —— 只收敛「与档位、槽位排布都无关」的那部分。
 *
 * <h3>为什么是这四项，而不是整台迁到 Mek</h3>
 * 第四轮实测：那 14 台<b>没有档位</b>（无 {@code CuttingMachineFactoryTier}），
 * 而 {@code MekCkMachineTile} 的整个槽位模型建立在档位上、且
 * {@code createExecutor()} 是抽象方法（强制配方语义）。
 * 把「1 台 9 格存储箱的陈化窖」硬塞进去，要么编一个假 tier，要么实现一个用不到的
 * 执行器 —— 都比现在更糟。所以<b>整台迁移这条路对它们不成立</b>。
 *
 * <p>但它们共享的四项能力是<b>逐字同形</b>的（见下），且不依赖档位、也不依赖槽位排布，
 * 于是单独收敛到这里。</p>
 *
 * <h3>收敛了什么（14 份逐字相同的副本）</h3>
 * <ol>
 *   <li><b>能量存取</b>：写盘 {@code tag.putInt("Energy", …)}，
 *       读盘用「{@code receiveEnergy} 循环灌入」——
 *       14 台都写成了 {@code int remaining = …; while (remaining > 0) { … }}，
 *       逐字相同；</li>
 *   <li><b>红石模式</b>：写 {@code ordinal()}、读 {@link RedstoneControl#byOrdinal}，
 *       值域与 {@link IRedstoneControllable} 共享；</li>
 *   <li><b>红石供电状态</b>：{@code RedstonePowered} 的存取；</li>
 *   <li><b>能量能力（capability）</b>：{@code ForgeCapabilities.ENERGY} 的 {@code LazyOptional}
 *       暴露与 {@code invalidateCaps} 收口。</li>
 * </ol>
 *
 * <h3>刻意<b>不</b>收敛的</h3>
 * <ul>
 *   <li><b>物品槽</b>：每台的槽数、堆叠上限、{@code isItemValid} 判据都不同
 *       （陈化窖 9+1 钉死 64、中央厨房 30 输出、…），且有侧向敏感的实现 ——
 *       统一成基类字段反而要一堆钩子。如需物品能力，基类留
 *       {@link #exposeItemCapability} 钩子由子类覆写。</li>
 *   <li><b>侧面配置</b>：各家的编码口径不同（有的写 {@code byte[]}、有的写 int），
 *       强行统一会动到存档格式 —— 存档兼容的优先级高于代码整洁。</li>
 *   <li><b>热量 / AE2 / 订单 / 进度条</b>：各自差异更大，且多数已在别处收口
 *       （订单见 {@code MekCkOrderState}）。</li>
 * </ul>
 *
 * <h3>存档键</h3>
 * 全部沿用各机器<b>原有</b>的键名（{@code Energy} / {@code RedstoneControl} /
 * {@code RedstonePowered}），一个字节都不改 —— 既有存档不受影响。
 */
public abstract class MekCkLegacyMachine extends BlockEntity implements IRedstoneControllable {

    /**
     * 本机对外暴露的能量存储。
     *
     * <p>{@code maxExtract = 0}：这些机器<b>只充不放</b>，对外不可抽（抽能量是机器自己的
     * 内部行为，见 {@link #drainInternal}）。子类若要允许外部抽取，覆写
     * {@link #createEnergyStorage} 传入非 0 的 maxExtract。</p>
     */
    protected final EnergyStorage energy;

    /** 供 {@link #getCapability} 用的能量能力包装。 */
    private final LazyOptional<IEnergyStorage> energyCapability;

    // ── 红石（沿用 14 台既有的字段与键）──────────────────────────────

    protected RedstoneControl redstoneControl = RedstoneControl.DISABLED;
    /** 本 tick 是否有红石信号。 */
    protected boolean redstonePowered;
    /** 上一 tick 是否有红石信号（{@code PULSE} 的上升沿判定用）。 */
    protected boolean redstonePoweredLastTick;

    /**
     * 本机是否支持红石控制 —— 决定存档里写不写那两个红石键。
     *
     * <p>默认 {@code true}。但<b>没有红石功能的机器（如陈化窖）必须覆写返回 {@code false}</b>：
     * 否则基类会给它凭空写上 {@code RedstoneControl} / {@code RedstonePowered} 两个键。
     * 读取侧无害（没红石逻辑的机器压根不看），但那是一份<b>凭空多出来的存档格式</b> ——
     * 对存档做 diff、对账、第三方工具都不该有这种噪声。</p>
     */
    protected boolean usesRedstone() {
        return true;
    }

    protected MekCkLegacyMachine(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                 int energyCapacity, int maxReceive) {
        super(type, pos, state);
        this.energy = createEnergyStorage(energyCapacity, maxReceive);
        this.energyCapability = LazyOptional.of(() -> this.energy);
    }

    /**
     * 造能量容器。默认<b>只充不放</b>。
     *
     * <p>抽成方法是为了让需要自定义「内部扣能量」语义的机器（如陈化窖的
     * {@code drainInternal}）能覆写容器类，而不是覆写整个字段初始化。</p>
     */
    protected EnergyStorage createEnergyStorage(int capacity, int maxReceive) {
        return new EnergyStorage(capacity, maxReceive, 0);
    }

    // ── 能量 ────────────────────────────────────────────────────────────

    public int getEnergyStored() {
        return energy.getEnergyStored();
    }

    public int getEnergyCapacity() {
        return energy.getMaxEnergyStored();
    }

    @Override
    public RedstoneControl getRedstoneControl() {
        return redstoneControl;
    }

    @Override
    public void setRedstoneControl(RedstoneControl control) {
        this.redstoneControl = control == null ? RedstoneControl.DISABLED : control;
        setChanged();
    }

    // ── 存档：14 台逐字相同的四个键 ───────────────────────────────────

    /**
     * 写公共键。子类在自己的 {@code saveAdditional} 里<b>先调 super</b> 再写专有键。
     *
     * <p>键名与各机器迁移前<b>逐字一致</b>，所以既有存档不受影响。</p>
     */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Energy", energy.getEnergyStored());
        if (usesRedstone()) {
            tag.putInt("RedstoneControl", redstoneControl.ordinal());
            tag.putBoolean("RedstonePowered", redstonePowered);
        }
    }

    /**
     * 读公共键。子类在自己的 {@code load} 里<b>先读专有键、再调 super</b>。
     *
     * <p>⚠️ 能量必须用「{@code receiveEnergy} 循环」而不是直接写字段：
     * {@code EnergyStorage} 的 {@code energy} 字段没有公开的 setter（Forge 刻意如此），
     * 只能通过 {@code receiveEnergy} 灌 —— 这是 14 台原有的写法，也是唯一可行的写法。
     * 循环是必要的：单次 {@code receiveEnergy} 受 {@code maxReceive} 夹断，
     * 灌 4 亿容量的机器单次灌不满。</p>
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        int remaining = tag.getInt("Energy");
        while (remaining > 0) {
            int received = energy.receiveEnergy(remaining, false);
            if (received == 0) {
                break;
            }
            remaining -= received;
        }
        if (usesRedstone()) {
            if (tag.contains("RedstoneControl")) {
                redstoneControl = RedstoneControl.byOrdinal(tag.getInt("RedstoneControl"));
            }
            if (tag.contains("RedstonePowered")) {
                redstonePowered = tag.getBoolean("RedstonePowered");
            }
        }
    }

    // ── 能力 ────────────────────────────────────────────────────────────

    /**
     * 能量能力由基类统一暴露；物品能力交给子类钩子（各家槽位差异太大）。
     *
     * <p>子类若要放行物品能力，覆写 {@link #exposeItemCapability}。</p>
     */
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return energyCapability.cast();
        }
        return exposeItemCapability(capability, side);
    }

    /**
     * 物品能力钩子 —— 默认<b>不放行</b>。
     *
     * <p>不给基类一个默认的 {@code ItemStackHandler} 引用，是因为各机器的物品容器
     * 类型不同（有的侧向敏感、有的钉死堆叠上限），统一引用会逼着子类到处覆写。
     * 需要物品能力的机器覆写本方法返回自己的 {@code LazyOptional<IItemHandler>}。</p>
     */
    protected <T> LazyOptional<T> exposeItemCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        energyCapability.invalidate();
    }
}
