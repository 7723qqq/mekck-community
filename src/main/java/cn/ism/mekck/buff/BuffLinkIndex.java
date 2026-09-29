package cn.ism.mekck.buff;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F10 攻击增益连线的**客户端渲染索引**：目标机器位置 → 其 buff 源（juicer / bakery_oven）位置。
 *
 * <p>本类**只存坐标**（{@link BlockPos} 为通用类），不引用任何客户端渲染类型，
 * 因此可被服务端 / 通用方块实体代码安全访问（专用服务器不会因加载客户端类而崩溃）。
 * 实际的画线由客户端 {@code cn.ism.mekck.client.BuffLinkRenderer} 读取本索引完成。</p>
 */
public final class BuffLinkIndex {

    private BuffLinkIndex() {
    }

    private static final Map<BlockPos, BlockPos> LINKS = new ConcurrentHashMap<>();

    public static void put(BlockPos target, BlockPos source) {
        LINKS.put(target.immutable(), source.immutable());
    }

    public static void remove(BlockPos target) {
        LINKS.remove(target);
    }

    /** 供客户端渲染器遍历（只读语义，勿在服务端写入）。 */
    public static Map<BlockPos, BlockPos> links() {
        return LINKS;
    }

    /** 玩家登出 / 换世界时清空，避免跨存档残留坐标。 */
    public static void clear() {
        LINKS.clear();
    }
}
