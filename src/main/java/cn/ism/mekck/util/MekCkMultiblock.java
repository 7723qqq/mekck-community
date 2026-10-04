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

    /**
     * 生物反应堆：3×3×3（<b>主方块位于底部正中</b>，绑定方块占其余 26 格）。
     *
     * <p>与 {@link #SHAPE_2X2X3} 的区别不只是尺寸：旧形状主方块在西南角，
     * 改成居中后 {@code BioreactorRenderer} 的旋转中心就是主方块自身，
     * 渲染时不必再做偏移。
     *
     * <p>本形状同时是 {@code BioreactorBlockEntity#emitEnergy} 的推送足迹，
     * 改这里等于改反应堆向四周供电的覆盖范围。
     */
    public static final TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> SHAPE_3X3X3 =
            (pos, state, builder) -> {
                for (int y = 0; y < 3; y++) {
                    for (int x = -1; x <= 1; x++) {
                        for (int z = -1; z <= 1; z++) {
                            if (x != 0 || y != 0 || z != 0) {
                                builder.add(pos.offset(x, y, z));
                            }
                        }
                    }
                }
            };

    /**
     * 生物反应堆的<b>旧</b> 2×2×3 形状（主方块在底层西南角，11 个绑定块）。
     *
     * <p>仅供 {@code BioreactorBlock#onRemove} 清理存量机器用：改成 3×3×3 之前
     * 已经放下的生物反应堆仍带着旧布局的绑定块，只按新形状清理会漏掉 3 个，
     * 在世界里留下拿不掉的 Mekanism 绑定方块。
     */
    public static final TriConsumer<BlockPos, BlockState, Stream.Builder<BlockPos>> SHAPE_2X2X3_LEGACY =
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
        // 服务端才有 tick 队列可派发重试；客户端方块实体的坐标由收到的包决定，不需要也排不了。
        // 顺带把 getServer() 的 null 面收在这里：原实现直接在循环里调 level.getServer().getTickCount()，
        // 换成先取一次局部变量，客户端/非服务器上下文下直接短路而不是在循环里 NPE。
        net.minecraft.server.MinecraftServer server = level.isClientSide ? null : level.getServer();
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
                    //
                    // 关于这个梯子（1/2/3/5/8/12/20，共 7 次/方块）不是性能问题：3×3×2 的生物反应堆
                    // 一次放置 = 17×7 = 119 条 TickTask，且全部在 20 tick 内被消费掉。TickTask 是
                    // MinecraftServer 每 tick 常规排空的短命队列，119 条远在正常量级内。
                    // 刻意不缩短它——这条重发梯子是为覆盖「客户端方块实体尚未创建」这个
                    // 真实竞态兜底的，缩短会把它变回间歇性打不开 GUI 的老问题。
                    if (server != null) {
                        final BlockPos fp = p;
                        final Level lvl = level;
                        final int base = server.getTickCount();
                        for (int d : RETRY_DELAYS) {
                            final int delay = d;
                            server.tell(new net.minecraft.server.TickTask(base + delay, () -> {
                                TileEntityBoundingBlock t = WorldUtils.getTileEntity(TileEntityBoundingBlock.class, lvl, fp);
                                if (t != null) {
                                    t.setMainLocation(orig);
                                    lvl.sendBlockUpdated(fp, newState, newState, Block.UPDATE_CLIENTS);
                                }
                            }));
                        }
                    }
                } else if (set) {
                    LOGGER.warn("[mekck-bb] 在 {} 放置绑定块成功但找不到其 TileEntity（setBlock={}）", p, set);
                }
            }
        }
        if (!level.isClientSide && LOGGER.isDebugEnabled()) {
            // 原为无条件 LOGGER.info：生产日志默认开 INFO，于是每放一台多方块机器
            // （生物反应堆 18 次、种植切配 2 次）就刷一行。降为 DEBUG。
            LOGGER.debug("[mekck-bb] placeBoundingBlocks @{} 尝试 {} 个，成功放置 {}", orig, positions.size(), placed);
        }
    }

    /**
     * 绑定块主坐标的重发延迟阶梯（tick）。
     *
     * <p>覆盖「客户端方块实体还没建好」这一竞态所需的完整窗口：跨度覆盖
     * 分块加载 + 网络往返。抽成常量是为了让「为什么是这 7 个数」有一处可查的说明，
     * 见 {@link #placeBoundingBlocks} 里兜底 2 的注释。</p>
     */
    private static final int[] RETRY_DELAYS = {1, 2, 3, 5, 8, 12, 20};

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
