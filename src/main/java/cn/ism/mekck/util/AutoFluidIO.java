package cn.ism.mekck.util;

import cn.ism.mekck.SideMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;

/**
 * 流体自动输入输出（与物品 AutoIO 语义一致）：
 * <ul>
 *   <li>PULL_INPUT（抽取至输入格）：从相邻流体容器主动抽入本机输入罐；</li>
 *   <li>PUSH_OUTPUT（弹出）：把本机输出罐主动推送到相邻流体容器。</li>
 * </ul>
 *
 * <p><b>转移量</b>：每面每 tick 的转移量由 {@link LagMonitor#getMaxFluidPerDirection()} 决定
 * （正常 256000 mB、轻度 64000、严重 8000）。<b>不再固定 1000 mB</b>：一次 fill/drain 的开销
 * 与转移量无关，固定小额度只会白白拉长灌满/排空时间（本模组机器罐体 16000 mB，原先要 16 tick）。</p>
 *
 * <p><b>邻居能力缓存</b>：与物品 AutoIO 相同——按方向缓存相邻方块实体与其流体能力，
 * 命中时跳过 {@code getBlockEntity + getCapability}；BE 实例变化立即失效，并按
 * {@link AutoIO#CAP_TTL_TICKS} 周期刷新以兼容运行期重建能力的模组。</p>
 */
public final class AutoFluidIO {

    /** 兼容旧引用的默认速率（正常档）；实际速率见 {@link LagMonitor#getMaxFluidPerDirection()}。 */
    public static final int TRANSFER_RATE = 256_000;

    private static final Direction[] DIRS = Direction.values();
    private final BlockEntity owner;
    private final FluidTank inputTank;
    private final FluidTank outputTank;

    private final BlockEntity[] adjBE = new BlockEntity[6];
    private final IFluidHandler[] adjHandler = new IFluidHandler[6];
    private final long[] adjResolveTick = new long[6];

    public AutoFluidIO(BlockEntity owner, FluidTank inputTank, FluidTank outputTank) {
        this.owner = owner;
        this.inputTank = inputTank;
        this.outputTank = outputTank;
    }

    /** 执行一轮流体自动输入输出。返回是否有流体移动。 */
    public boolean run(Level level, BlockPos pos, SideMode[] fluidSideConfig) {
        boolean hasOutput = !outputTank.getFluid().isEmpty();
        boolean hasInputSpace = inputTank.getFluidAmount() < inputTank.getCapacity();
        if (!hasOutput && !hasInputSpace) return false;

        long now = level.getGameTime();
        int rate = LagMonitor.getMaxFluidPerDirection();
        boolean moved = false;
        for (int d = 0; d < 6; d++) {
            SideMode mode = fluidSideConfig[d];
            if (mode == SideMode.NONE || mode == SideMode.PULL_INPUT_STORAGE) continue;
            if (mode == SideMode.PUSH_OUTPUT ? !hasOutput : !hasInputSpace) continue;

            IFluidHandler adj = resolve(level, pos.relative(DIRS[d]), DIRS[d], now);
            if (adj == null) continue;

            if (mode == SideMode.PUSH_OUTPUT) {
                FluidStack offer = outputTank.drain(rate, IFluidHandler.FluidAction.SIMULATE);
                if (offer.isEmpty()) continue;
                int filled = adj.fill(offer, IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) {
                    outputTank.drain(filled, IFluidHandler.FluidAction.EXECUTE);
                    moved = true;
                    hasOutput = !outputTank.getFluid().isEmpty();
                }
            } else {
                FluidStack offer = adj.drain(rate, IFluidHandler.FluidAction.SIMULATE);
                if (offer.isEmpty()) continue;
                int filled = inputTank.fill(offer, IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) {
                    adj.drain(filled, IFluidHandler.FluidAction.EXECUTE);
                    moved = true;
                    hasInputSpace = inputTank.getFluidAmount() < inputTank.getCapacity();
                }
            }
        }
        return moved;
    }

    /** 解析某方向的相邻流体处理器（BE 实例 + TTL 缓存，与物品 AutoIO 同口径）。 */
    private IFluidHandler resolve(Level level, BlockPos adjPos, Direction dir, long now) {
        int d = dir.ordinal();
        if (!level.getBlockState(adjPos).hasBlockEntity()) {
            adjBE[d] = null;
            adjHandler[d] = null;
            return null;
        }
        BlockEntity be = level.getBlockEntity(adjPos);
        if (be == null) {
            adjBE[d] = null;
            adjHandler[d] = null;
            return null;
        }
        if (be == adjBE[d] && now - adjResolveTick[d] < AutoIO.CAP_TTL_TICKS) {
            return adjHandler[d];
        }
        IFluidHandler h = be.getCapability(ForgeCapabilities.FLUID_HANDLER, dir.getOpposite()).orElse(null);
        adjBE[d] = be;
        adjHandler[d] = h;
        adjResolveTick[d] = now;
        return h;
    }
}
