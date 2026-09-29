package cn.ism.mekck.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 客户端 → 服务端包的访问校验。
 *
 * <p><b>为什么必须校验</b>：这些包全部由客户端构造，服务端只拿到一个 {@link BlockPos}。
 * 早期各处只做 {@code level.hasChunkAt(pos)} —— 那只能说明「区块已加载」，
 * <b>不构成接近性校验</b>：任何客户端都能把坐标指向世界中任意已加载的机器并改其状态
 * （侧面配置、升级装卸、订单下发、从 ME 网络抽料）。</p>
 *
 * <p><b>半径取 64.0（8 格）</b>：与本模组所有菜单的 {@code stillValid} 和原版
 * {@code AbstractContainerMenu} 的判定一致。取同一数值可保证「菜单还开着 ⟺ 包被接受」，
 * 不会出现「GUI 能操作但按钮无效」的空隙。</p>
 */
public final class PacketGuard {

    /**
     * 允许交互的距离平方上限（8 格）。
     *
     * <p>用平方值做比较可省掉 {@link Math#sqrt}；与原版
     * {@code ServerPlayer#canInteractWith} 系列实现保持同一量纲。</p>
     */
    public static final double INTERACT_RANGE_SQR = 64.0D;

    /**
     * 解码器允许预分配的元素数上限。
     *
     * <p>解码发生在 Netty 线程，此时还谈不上「合法性」，但 {@code readVarInt()} 是纯攻击者
     * 可控值：直接 {@code new ArrayList<>(n)} 会让一个几十字节的包申请 GB 级数组并触发
     * {@code OutOfMemoryError}。因此预分配前一律先夹紧。</p>
     *
     * <p>取 4096：本模组最长的清单是 ME 网络可合成配方表，实际量级在数百条；4096 留足余量，
     * 同时把单包的内存放大倍数从 ~2^31 压到 ~2^12。</p>
     */
    public static final int MAX_DECODE_ELEMENTS = 4096;

    private PacketGuard() {
    }

    /**
     * 把网络上读到的元素数夹到合法区间。
     *
     * <p>只夹紧预分配大小，<b>不改变协议</b>：随后仍按原始计数逐个读取，
     * 因此越界的包会在读流耗尽时正常抛错，而不是被静默截断成一份看似合法的数据。</p>
     */
    public static int clampCount(int raw) {
        return Math.max(0, Math.min(raw, MAX_DECODE_ELEMENTS));
    }

    /**
     * 该玩家是否有权操作此坐标上的方块实体。
     *
     * @return 坐标区块已加载<b>且</b>玩家在 {@link #INTERACT_RANGE_SQR} 之内
     */
    public static boolean allowed(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return false;
        Level level = player.level();
        if (level == null || !level.hasChunkAt(pos)) return false;
        return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                <= INTERACT_RANGE_SQR;
    }

    /**
     * 通过校验后返回该坐标的方块实体，否则返回 null。
     *
     * <p>调用方仍应自行做 {@code instanceof} 类型判定；本方法只负责「能不能碰」，
     * 不负责「是不是我认识的那台机器」。</p>
     */
    public static BlockEntity target(ServerPlayer player, BlockPos pos) {
        return allowed(player, pos) ? player.level().getBlockEntity(pos) : null;
    }
}
