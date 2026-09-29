package cn.ism.mekck.util;

import mekanism.api.functions.TriConsumer;
import mekanism.common.block.BlockBounding;
import mekanism.common.block.states.BlockStateHelper;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tile.TileEntityBoundingBlock;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class MekCkMultiblock {

    private static final Logger LOGGER = LogManager.getLogger("mekck.Multiblock");

    /**
 * Mekanism 风格“绑定方块”多方块辅助。
 *
 * <p>机器放置后 = 主方块 + 若干 Mekanism 绑定方块（{@code mekanism:bounding_block}）组成整体：
 * <ul>
 *   <li>点击任意绑定方块 → 转发到主方块打开 GUI；</li>
 *   <li>破坏任意绑定方块 → 主方块被移除 → 整体一起破坏；</li>
 *   <li>破坏主方块 → 自动清理其余绑定方块。</li>
 * </ul>
 */

    /** 种植切配站：1×2×1（主方块 + 上方 1 个绑定方块）。 */
    public static final TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> SHAPE_2_TALL =
            (pos, state, builder) -> builder.add(pos.above());

    /** 种植切配工厂：3×3×2（与 Mekanism 数字型采矿机同款，主方块位于底部中心）。 */
    public static final TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> SHAPE_3X3X2 =
            (pos, state, builder) -> {
                for (int x = -1; x <= 1; x++) {
                    for (int y = 0; y <= 1; y++) {
                        for (int z = -1; z <= 1; z++) {
                            if (x != 0 || y != 0 || z != 0) {
                                builder.add(pos.offset(x, y, z));
                            }
                        }
                    }
                }
            };

    /** 生物反应堆：2×2×3（主方块位于底层 2×2 的西南角，绑定方块占其余 11 格）。 */
    public static final TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> SHAPE_2X2X3 =
            (pos, state, builder) -> {
                for (int y = 0; y < 3; y++) {
                    for (int x = 0; x < 2; x++) {
                        for (int z = 0; z < 2; z++) {
                            if (x != 0 || y != 0 || z != 0) {
                                builder.add(pos.offset(x, y, z));
                            }
                        }
                    }
                }
            };

    private MekCkMultiblock() {
    }

    public static List<BlockPos> getBoundingPositions(BlockPos pos, BlockState state,
                                                      TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> shape) {
        Stream.Builder<BlockPos> builder = Stream.builder();
        shape.accept(pos, state, builder);
        List<BlockPos> out = new ArrayList<>();
        builder.build().forEach(out::add);
        return out;
    }

    /** 所有绑定方块位置均可放置（空气/可替换）时返回 true。 */
    public static boolean canPlace(BlockGetter level, BlockPos pos, BlockState state,
                                   TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> shape) {
        for (BlockPos p : getBoundingPositions(pos, state, shape)) {
            if (p.getY() < level.getMinBuildHeight() || p.getY() >= level.getMaxBuildHeight()) {
                return false;
            }
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !s.canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    public static void placeBoundingBlocks(Level level, BlockPos orig, BlockState state,
                                            TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> shape) {
        placeBoundingBlocks(level, orig, state, shape, MekanismBlocks.BOUNDING_BLOCK.getBlock());
    }

    public static void placeBoundingBlocks(Level level, BlockPos orig, BlockState state,
                                           TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> shape, Block boundingBlock) {
        List<BlockPos> positions = getBoundingPositions(orig, state, shape);
        int placed = 0;
        for (BlockPos p : positions) {
            // 与 Mekanism AttributeHasBounding.placeBoundingBlocks 一致：用 getStateForPlacement 取得绑定块放置态
            BlockState newState = BlockStateHelper.getStateForPlacement(boundingBlock, boundingBlock.defaultBlockState(), level, p, null, Direction.NORTH);
            boolean set = level.setBlock(p, newState, Block.UPDATE_ALL);
            if (!level.isClientSide) {
                TileEntityBoundingBlock tile = WorldUtils.getTileEntity(TileEntityBoundingBlock.class, level, p);
                if (tile != null) {
                    tile.setMainLocation(orig);
                    placed++;
                    // 兜底 1：放置瞬间 setMainLocation 发出的 ClientboundBlockEntityDataPacket 可能早于客户端方块实体到达而被丢弃，
                    // 导致客户端 receivedCoords 仍为 false → 无碰撞箱、右键打不开 GUI、线缆抽不到电。
                    // 这里再用“方块更新包”通道重发一次：该包携带当前 TE 的 getUpdateTag()（已含主块坐标），
                    // 而客户端此前已通过同类型包创建了方块实体，重复到达时会再次 handleUpdateTag 覆盖坐标，确保必达。
                    level.sendBlockUpdated(p, newState, newState, Block.UPDATE_CLIENTS);
                    // 兜底 2：在接下来若干 tick 持续重发，覆盖网络/分块加载延迟，客户端建好方块实体后必能收到。
                    final BlockPos fp = p;
                    final Level lvl = level;
                    final int base = level.getServer().getTickCount();
                    for (int d : new int[]{1, 2, 3, 5, 8, 12, 20}) {
                        final int delay = d;
                        level.getServer().tell(new net.minecraft.server.TickTask(base + delay, () -> {
                            TileEntityBoundingBlock t = WorldUtils.getTileEntity(TileEntityBoundingBlock.class, lvl, fp);
                            if (t != null) {
                                t.setMainLocation(orig);
                                level.sendBlockUpdated(fp, newState, newState, Block.UPDATE_CLIENTS);
                            }
                        }));
                    }
                } else if (set) {
                    LOGGER.warn("[mekck-bb] 在 {} 放置绑定块成功但找不到其 TileEntity（setBlock={}）", p, set);
                }
            }
        }
        if (!level.isClientSide) {
            LOGGER.info("[mekck-bb] placeBoundingBlocks @{} 尝试 {} 个，成功放置 {}", orig, positions.size(), placed);
        }
    }

    public static void removeBoundingBlocks(Level level, BlockPos orig, BlockState state,
                                            TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> shape) {
        removeBoundingBlocks(level, orig, state, shape, MekanismBlocks.BOUNDING_BLOCK.getBlock());
    }

    public static void removeBoundingBlocks(Level level, BlockPos orig, BlockState state,
                                            TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> shape, Block boundingBlock) {
        for (BlockPos p : getBoundingPositions(orig, state, shape)) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() == boundingBlock) {
                level.removeBlock(p, false);
            }
        }
    }
}
