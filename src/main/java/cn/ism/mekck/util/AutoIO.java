package cn.ism.mekck.util;

import cn.ism.mekck.SideMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/**
 * autoIO 共享调度器：统一执行各机器的侧面抽取/弹出，并做四层性能优化。
 *
 * 1. 空满预检——输出/返回槽全空则跳过所有 PUSH 方向；
 *    目标槽全部无空间则跳过所有 PULL 方向。
 *    闲置或堵料时完全不触碰世界（零邻居解析、零能力查询）。
 * 2. 邻居能力缓存——按方向缓存相邻 BlockEntity 实例与其 ITEM_HANDLER，
 *    命中时跳过昂贵的 getCapability 调用；BE 实例变化立即失效，
 *    并以 CAP_TTL_TICKS 周期刷新以兼容运行时重挂能力的模组
 *    （如 Mekanism 侧面配置变更后能力对象会重建）。
 * 3. 缓存 Direction 数组——避免每 tick 的枚举 values() 克隆。
 * 4. 单次标记脏——一轮内多次转移只调用一次 setChanged()。
 *
 * PULL_INPUT（抽取至输入格）拉入 {@link #pullInputRanges}；
 * PULL_INPUT_STORAGE（抽取至存储空间）拉入 {@link #pullStorageRanges}；
 * 范围数组 {start,count} 按优先级排列，与旧版逐台手写的调用顺序完全一致。
 */
public final class AutoIO {

    /** 邻居能力缓存的刷新周期（tick）——物品/流体/气体三套 AutoIO 共用同一口径。 */
    public static final long CAP_TTL_TICKS = 16L;

    private static final Direction[] DIRS = Direction.values();

    private final BlockEntity owner;
    private final int[][] pullInputRanges;
    private final int[][] pullStorageRanges;
    private final int[][] pushRanges;

    /**
     * 可选的**主动拉入接管器**：机器若需要“按物品种类决定落点”（如陈酿机的果汁小灶），
     * 注册后 PULL_INPUT 不再走固定区间，改由 {@link #pullWithTargets} 逐种投递。
     * 未注册的机器行为与本改动前完全一致。
     */
    private PullTarget pullTarget;

    private final BlockEntity[] adjBE = new BlockEntity[6];
    private final IItemHandler[] adjHandler = new IItemHandler[6];
    private final long[] adjResolveTick = new long[6];

    /**
     * @param pullRanges 抽取（至输入格）目标槽范围 {start,count}[]
     * @param pushRanges 弹出源槽范围 {start,count}[]
     */
    public AutoIO(BlockEntity owner, int[][] pullRanges, int[][] pushRanges) {
        this(owner, pullRanges, new int[0][], pushRanges);
    }

    /**
     * @param pullInputRanges   PULL_INPUT（抽取至输入格）目标槽范围
     * @param pullStorageRanges PULL_INPUT_STORAGE（抽取至存储空间）目标槽范围，可为空
     * @param pushRanges        弹出源槽范围
     */
    public AutoIO(BlockEntity owner, int[][] pullInputRanges, int[][] pullStorageRanges, int[][] pushRanges) {
        this.owner = owner;
        this.pullInputRanges = pullInputRanges;
        this.pullStorageRanges = pullStorageRanges;
        this.pushRanges = pushRanges;
    }

    /**
     * 目标槽解析器：返回该物品本轮应投入的内部槽号，-1 = 不拉取该物品。
     * source = 本轮被抽的相邻方块实体（箱子/漏斗时为 null 或非机器），供实现方判断「这物品是不是邻居自己还要用的」。
     */
    public interface PullTarget {
        int targetSlot(ItemStack proto, BlockEntity source);
    }

    /** 注册拉入接管器（传 null 取消）。仅影响 PULL_INPUT。 */
    public void setPullTarget(PullTarget target) {
        this.pullTarget = target;
    }

    /** 执行一轮自动输入输出。返回是否有物品移动。 */
    public boolean run(Level level, BlockPos pos, SideMode[] sideConfig, ItemStackHandler items) {
        // 第一层：空满预检，无物可推且无处可放时不做任何世界访问
        boolean needPullInput = hasAnySpace(items, pullInputRanges);
        boolean needPullStorage = pullStorageRanges.length > 0 && hasAnySpace(items, pullStorageRanges);
        boolean needPush = hasAnyItem(items, pushRanges);
        if (!needPullInput && !needPullStorage && !needPush) return false;

        long now = level.getGameTime();
        boolean moved = false;
        for (int d = 0; d < 6; d++) {
            SideMode mode = sideConfig[d];
            if (mode == SideMode.NONE) continue;
            boolean isPull = mode.isPull();
            if (isPull) {
                if (mode == SideMode.PULL_INPUT ? !needPullInput : !needPullStorage) continue;
            } else if (!needPush) {
                continue;
            }

            IItemHandler adj = resolve(level, pos.relative(DIRS[d]), DIRS[d], now);
            if (adj == null) continue;

            if (mode == SideMode.PULL_INPUT) {
                if (pullTarget != null) {
                    moved |= pullWithTargets(adj, items, pullTarget, adjBE[d]);
                    continue;
                }
                for (int[] r : pullInputRanges) moved |= FastTransfer.pull(adj, items, r[0], r[1]);
            } else if (mode == SideMode.PULL_INPUT_STORAGE) {
                for (int[] r : pullStorageRanges) moved |= FastTransfer.pull(adj, items, r[0], r[1]);
            } else {
                for (int[] r : pushRanges) moved |= FastTransfer.push(items, r[0], r[1], adj);
            }
        }
        // 第四层：整轮只标记一次脏
        return moved;
    }

    /**
     * 按接管器逐种投递：先按目标槽的**实际余量**（simulate 插入测得）限量，再抽、再投；
     * 投递有剩余则原样塞回源槽，绝不吞物品。单物品每组上限 64（机器输入格本就装不了更多）。
     */
    private static boolean pullWithTargets(IItemHandler adj, ItemStackHandler items, PullTarget target, BlockEntity source) {
        boolean moved = false;
        long budget = LagMonitor.getMaxItemsPerDirectionLong();
        for (int i = 0; i < adj.getSlots() && budget > 0L; i++) {
            ItemStack proto = adj.getStackInSlot(i);
            if (proto.isEmpty()) continue;
            int t = target.targetSlot(proto, source);
            if (t < 0 || t >= items.getSlots()) continue;
            int probe = Math.min(64, Math.min(proto.getMaxStackSize(), (int) Math.min(Integer.MAX_VALUE - 1L, budget)));
            if (probe <= 0) continue;
            int left = items.insertItem(t, proto.copyWithCount(probe), true).getCount();
            int want = probe - left;
            if (want <= 0) continue;
            ItemStack got = adj.extractItem(i, want, false);
            if (got.isEmpty()) continue;
            ItemStack rest = items.insertItem(t, got, false);
            if (!rest.isEmpty()) adj.insertItem(i, rest, false);
            budget -= got.getCount() - rest.getCount();
            moved = true;
        }
        return moved;
    }

    /**
     * 解析某方向的相邻物品处理器。
     * BE 实例相同且缓存未过期时直接复用，跳过 getCapability；
     * 过期或实例变化时重新解析并记录。负结果（无能力）同样受 TTL 保护。
     */
    private IItemHandler resolve(Level level, BlockPos adjPos, Direction dir, long now) {
        int d = dir.ordinal();
        // 未加载区块：getBlockState 会触发区块加载/生成（每 tick 最多 6 次），先判 hasChunkAt
        if (!level.hasChunkAt(adjPos)) {
            adjBE[d] = null;
            adjHandler[d] = null;
            return null;
        }
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
        if (be == adjBE[d] && now - adjResolveTick[d] < CAP_TTL_TICKS) {
            return adjHandler[d];
        }
        IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, dir.getOpposite()).orElse(null);
        adjBE[d] = be;
        adjHandler[d] = h;
        adjResolveTick[d] = now;
        return h;
    }

    /** 目标槽范围内是否还有任意槽位能再容纳物品（与 FastTransfer 预筛同口径：只看 slotLimit）。 */
    private static boolean hasAnySpace(ItemStackHandler items, int[][] ranges) {
        for (int[] r : ranges) {
            for (int i = 0; i < r[1]; i++) {
                int s = r[0] + i;
                ItemStack cur = items.getStackInSlot(s);
                if (cur.isEmpty() || cur.getCount() < items.getSlotLimit(s)) return true;
            }
        }
        return false;
    }

    /** 源槽范围内是否存在任意非空槽位。 */
    private static boolean hasAnyItem(ItemStackHandler items, int[][] ranges) {
        for (int[] r : ranges) {
            for (int i = 0; i < r[1]; i++) {
                if (!items.getStackInSlot(r[0] + i).isEmpty()) return true;
            }
        }
        return false;
    }
}
