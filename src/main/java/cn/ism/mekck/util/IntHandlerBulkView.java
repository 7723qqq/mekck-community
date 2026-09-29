package cn.ism.mekck.util;

import cn.ism.mekck.api.IBulkItemHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

/**
 * 把任意 {@link IItemHandler}（含各机器对外暴露的过滤包装器、以及外部模组的容器）
 * 适配成 {@link IBulkItemHandler}：数量用 {@code long} 表达，按区间批量搬运。
 *
 * <p><b>为什么需要它</b>：外部接口只有 int 级 {@code insertItem/extractItem}，
 * 若按"每个源槽都遍历一遍全部目标槽"去搬，多材料场景是 O(类型 × 源槽 × 目标槽)；
 * 有了本视图，先<b>扫一遍</b>算出区间容量/存量（O(槽数)），再<b>按槽投递</b>（O(触及槽数)），
 * 总成本降为 O(类型 × (源槽 + 目标槽))，且每次投递都用满 int 上限（21.47 亿）。</p>
 *
 * <p><b>语义</b>：与逐槽调用完全一致——按槽顺序填充、尊重 {@code getSlotLimit} 与
 * {@code isItemValid}、同物品优先叠加；<b>绝不混合不同物品</b>。</p>
 */
public final class IntHandlerBulkView implements IBulkItemHandler {

    /** 单次投递的最大片大小（int 上限）。 */
    private static final int CHUNK = Integer.MAX_VALUE - 1;

    private final IItemHandler handler;

    public IntHandlerBulkView(IItemHandler handler) {
        this.handler = handler;
    }

    /** 若该 handler 本身已支持大宗接口则直接返回它，否则套一层视图（零分配：仅一次包装）。 */
    public static IBulkItemHandler of(IItemHandler handler) {
        if (handler instanceof IBulkItemHandler bulk) return bulk;
        return new IntHandlerBulkView(handler);
    }

    @Override
    public long bulkSpace(ItemStack proto, int start, int count) {
        if (proto == null || proto.isEmpty() || handler == null) return 0L;
        long space = 0L;
        int slots = handler.getSlots();
        int end = Math.min(start + count, slots);
        for (int slot = Math.max(0, start); slot < end; slot++) {
            if (!handler.isItemValid(slot, proto)) continue;
            ItemStack cur = handler.getStackInSlot(slot);
            long limit = handler.getSlotLimit(slot);
            if (cur.isEmpty()) {
                space += limit;
            } else if (ItemStack.isSameItemSameTags(cur, proto)) {
                space += Math.max(0L, limit - cur.getCount());
            }
            if (space < 0L) return Long.MAX_VALUE;
        }
        return space;
    }

    @Override
    public long bulkAvailable(ItemStack proto, int start, int count) {
        if (proto == null || proto.isEmpty() || handler == null) return 0L;
        long total = 0L;
        int slots = handler.getSlots();
        int end = Math.min(start + count, slots);
        for (int slot = Math.max(0, start); slot < end; slot++) {
            ItemStack cur = handler.getStackInSlot(slot);
            if (cur.isEmpty() || !ItemStack.isSameItemSameTags(cur, proto)) continue;
            total += cur.getCount();
        }
        return total;
    }

    @Override
    public long bulkInsert(ItemStack proto, long amount, int start, int count, boolean simulate) {
        if (proto == null || proto.isEmpty() || amount <= 0L || handler == null) return 0L;
        long inserted = 0L;
        int slots = handler.getSlots();
        int end = Math.min(start + count, slots);
        for (int slot = Math.max(0, start); slot < end && inserted < amount; slot++) {
            if (!handler.isItemValid(slot, proto)) continue;
            long want = Math.min(amount - inserted, CHUNK);
            ItemStack remainder = handler.insertItem(slot, proto.copyWithCount((int) want), simulate);
            long moved = want - remainder.getCount();
            if (moved > 0L) inserted += moved;
        }
        return inserted;
    }

    @Override
    public long bulkExtract(ItemStack proto, long amount, int start, int count, boolean simulate) {
        if (proto == null || proto.isEmpty() || amount <= 0L || handler == null) return 0L;
        long extracted = 0L;
        int slots = handler.getSlots();
        int end = Math.min(start + count, slots);
        for (int slot = Math.max(0, start); slot < end && extracted < amount; slot++) {
            ItemStack cur = handler.getStackInSlot(slot);
            if (cur.isEmpty() || !ItemStack.isSameItemSameTags(cur, proto)) continue;
            long want = Math.min(amount - extracted, cur.getCount());
            ItemStack got = handler.extractItem(slot, (int) want, simulate);
            if (!got.isEmpty()) extracted += got.getCount();
        }
        return extracted;
    }

    /** 便利：区间内是否存在可被该物品使用的空位（用于快速早退）。 */
    public static boolean hasAnySpace(IItemHandler handler, ItemStack proto) {
        if (handler == null || proto == null || proto.isEmpty()) return false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.isItemValid(slot, proto)) continue;
            ItemStack cur = handler.getStackInSlot(slot);
            if (cur.isEmpty()) return true;
            if (ItemStack.isSameItemSameTags(cur, proto) && cur.getCount() < handler.getSlotLimit(slot)) return true;
        }
        return false;
    }

    /** 便利：区间内是否存在该物品。 */
    public static boolean hasAnyOf(IItemHandler handler, ItemStack proto) {
        if (handler == null || proto == null || proto.isEmpty()) return false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack cur = handler.getStackInSlot(slot);
            if (!cur.isEmpty() && ItemStack.isSameItemSameTags(cur, proto)) return true;
        }
        return false;
    }
}
