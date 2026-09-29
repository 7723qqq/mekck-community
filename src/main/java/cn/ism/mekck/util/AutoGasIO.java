package cn.ism.mekck.util;

import cn.ism.mekck.SideMode;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.gas.IGasTank;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 气体自动输入输出（与物品 AutoIO / 流体 AutoFluidIO 语义一致）：
 * <ul>
 *   <li>PULL_INPUT（抽取至输入格）：从相邻气体容器主动抽入本机气体罐；</li>
 *   <li>PUSH_OUTPUT（弹出）：把本机气体罐主动推送到相邻气体容器。</li>
 * </ul>
 * 每 tick 每面最多转移 {@link #TRANSFER_RATE} mB。
 */
public final class AutoGasIO {

    /**
     * 兼容旧引用的默认速率（正常档，mB）。
     * 实际速率见 {@link LagMonitor#getMaxFluidPerDirection()}——一次 insertChemical/extractChemical
     * 的开销与转移量无关，固定 1000 mB 只会人为拉长灌满/排空时间。
     */
    public static final long TRANSFER_RATE = 256_000L;

    private static final Direction[] DIRS = Direction.values();
    private final IGasTank tank;

    // 邻居能力缓存：与物品 AutoIO / 流体 AutoFluidIO 同口径（BE 实例 + TTL）
    private final BlockEntity[] adjBE = new BlockEntity[6];
    private final IGasHandler[] adjHandler = new IGasHandler[6];
    private final long[] adjResolveTick = new long[6];

    public AutoGasIO(IGasTank tank) {
        this.tank = tank;
    }

    /** 解析某方向的相邻气体处理器（BE 实例 + TTL 缓存）。 */
    private IGasHandler resolve(Level level, BlockPos adjPos, Direction dir, long now) {
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
        IGasHandler h = be.getCapability(Capabilities.GAS_HANDLER, dir.getOpposite()).orElse(null);
        adjBE[d] = be;
        adjHandler[d] = h;
        adjResolveTick[d] = now;
        return h;
    }

    /**
     * 执行一轮气体自动输入输出。返回是否有气体移动。
     * <p>弹出（PUSH_OUTPUT）保留实现供将来「有气体产物」的机器使用；当前接入气体侧配的机器
     * （种植切配工厂）只有单一营养液储罐（营养液 = 原料），其菜单不提供弹出模式
     * （见 {@code ISideConfigurableMenu#supportsGasPush()}），因此实际不会把原料推出去。</p>
     */
    public boolean run(Level level, BlockPos pos, SideMode[] gasSideConfig) {
        boolean hasGas = !tank.isEmpty();
        boolean hasSpace = tank.getStored() < tank.getCapacity();
        if (!hasGas && !hasSpace) return false;

        long now = level.getGameTime();
        long rate = LagMonitor.getMaxFluidPerDirection();
        boolean moved = false;
        for (int d = 0; d < 6; d++) {
            SideMode mode = gasSideConfig[d];
            if (mode == SideMode.NONE || mode == SideMode.PULL_INPUT_STORAGE) continue;
            if (mode == SideMode.PUSH_OUTPUT ? !hasGas : !hasSpace) continue;

            IGasHandler adj = resolve(level, pos.relative(DIRS[d]), DIRS[d], now);
            if (adj == null) continue;

            if (mode == SideMode.PUSH_OUTPUT) {
                GasStack offer = tank.extract(rate, Action.SIMULATE, AutomationType.INTERNAL);
                if (offer.isEmpty()) continue;
                GasStack remain = adj.insertChemical(offer, Action.EXECUTE);
                long amount = offer.getAmount() - remain.getAmount();
                if (amount > 0) {
                    tank.extract(amount, Action.EXECUTE, AutomationType.INTERNAL);
                    moved = true;
                    hasGas = !tank.isEmpty();
                }
            } else {
                GasStack offer = adj.extractChemical(rate, Action.SIMULATE);
                if (offer.isEmpty()) continue;
                GasStack remain = tank.insert(offer, Action.EXECUTE, AutomationType.EXTERNAL);
                long amount = offer.getAmount() - remain.getAmount();
                if (amount > 0) {
                    adj.extractChemical(amount, Action.EXECUTE);
                    moved = true;
                    hasSpace = tank.getStored() < tank.getCapacity();
                }
            }
        }
        return moved;
    }
}
