package cn.ism.mekck.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 中央厨房的<b>定向热交换</b>子系统：{@code FACING} 侧为冷端、其反向为热端，
 * 两个温度各自与环境温差成比例地向相邻热力设备传导。
 *
 * <h3>为什么要从 {@code CentralKitchenBlockEntity} 里独立出来</h3>
 * 这段约 35 行是纯「朝向 + 能力传导」计算，与 BE 的其余关注点（订单求解、自动加工、
 * NBT、模块能力）无关：BE 管「这台机器当前在做什么」，这里只管「按朝向把热量传出去」。
 * 拆出后，读 {@code serverTick} 主循环不必先跳过这段朝向相关的 {@code FACING} 兜底与注释。
 *
 * <h3>搬运边界</h3>
 * {@code transferToNeighbour} 只被 {@code applyDirectedHeat} 调，{@code applyDirectedHeat}
 * 只被 {@code serverTick} 调 —— 两者全部调用点都在本子系统内，故整体搬入。
 * 温度改走 BE 已公开的 {@code getHeatTemperature()} / {@code getColdTemperature()}
 * （两者返回的就是原先直读组件温度的值，逐字同值）。
 *
 * <p>本类<b>不持有任何自己的状态</b>：温度与坐标都从 {@code be} 取，
 * 因此搬运不改变任何读写时序 —— 与 {@link SimpleMachineFluids} / {@link SimpleMachineRecipes}
 * 一致，也是这个拆分敢在只有源码断言、没有实机环境的情况下做的原因。</p>
 */
public final class CentralKitchenHeatTransfer {

    /** 定向热交换系数（每 tick 传递的温差比例）。 */
    private static final double HEAT_EXCHANGE_RATE = 0.05;

    private final CentralKitchenBlockEntity be;

    public CentralKitchenHeatTransfer(CentralKitchenBlockEntity be) {
        this.be = be;
    }

    /**
     * 定向热交换（与急冻制冰机一致）：**{@code FACING} 侧为冷端、其反向为热端**。
     * 面配置面板把 {@code FACING} 侧标为「背面」——即**面板里「背面」= 冷端、「正面」= 热端**。
     * 两个温度各自与环境温差成比例地向相邻热力设备传递：冷端低于环境时吸热、热端高于环境时放热。
     * 其余四个面不参与热交换。
     */
    void applyDirectedHeat(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.hasProperty(cn.ism.mekck.block.CentralKitchenBlock.FACING)
                ? state.getValue(cn.ism.mekck.block.CentralKitchenBlock.FACING) : Direction.NORTH;
        double ambient = mekanism.api.heat.HeatAPI.getAmbientTemp(level, pos);
        // 冷端（正面）：温度低于环境时从相邻设备吸热（负值 = 邻居被吸热）
        double coldDiff = be.getColdTemperature() - ambient;
        transferToNeighbour(level, pos, facing, coldDiff * HEAT_EXCHANGE_RATE);
        // 热端（背面）：温度高于环境时向相邻设备放热
        double heatDiff = be.getHeatTemperature() - ambient;
        transferToNeighbour(level, pos, facing.getOpposite(), heatDiff * HEAT_EXCHANGE_RATE);
    }

    private void transferToNeighbour(Level level, BlockPos pos, Direction side, double heat) {
        if (Math.abs(heat) < 1.0e-3) return;
        BlockPos neighbour = pos.relative(side);
        if (!level.hasChunkAt(neighbour)) return;
        var adj = level.getBlockEntity(neighbour);
        if (adj == null) return;
        adj.getCapability(mekanism.common.capabilities.Capabilities.HEAT_HANDLER, side.getOpposite())
                .ifPresent(handler -> {
                    double current = handler.getTotalTemperature();
                    if (!Double.isFinite(current) || current < 0.0 || current > 1.0e9) return;
                    handler.handleHeat(heat);
                });
    }
}
