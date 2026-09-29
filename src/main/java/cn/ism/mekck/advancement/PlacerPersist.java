package cn.ism.mekck.advancement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 机器放置者 UUID 的通用持久化辅助（网络厨师学徒进度归属）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>与 AE2 完全解耦：所有 AE2 接入 BE 在 {@code saveAdditional}/{@code load} 中直接调用
 *       {@link #save}/{@link #load}，不依赖 AE2 能力/FactoryGridHost 是否存在；</li>
 *   <li>唯一权威来源 = BE 自己的 NBT 键 {@code MekckPlacerUuid}（与上一轮 AE2 NBT 键一致，
 *       旧存档可迁移读取）；运行时仅 WeakHashMap 缓存（按 BE 对象，天然隔离同位置新旧机器）；</li>
 *   <li>放置者写入必须调用 {@code be.setChanged()} 标记区块保存；</li>
 *   <li>BE 被破坏/卸载后对象失效，WeakHashMap 弱键随 GC 清理（value 不强引用 key 以外对象，无循环）。</li>
 * </ul>
 */
public final class PlacerPersist {

    private static final String KEY_UUID = "MekckPlacerUuid";

    /**
     * 运行时缓存。
     *
     * <p><b>必须同步</b>：{@code WeakHashMap} 不是线程安全的，而 {@link #save} 会在
     * 区块保存路径上被调用、{@link #get} 会在机器 tick 上被调用，两者不保证同线程
     * （区块卸载/保存与主线程逻辑之间没有互斥）。并发 {@code put} 触发的扩容会让
     * {@code get} 读到半成品链表——表现为「放置者归属偶发丢失」，且不报错。
     * 本模组其它 WeakHashMap 缓存（{@code RecipeCache} / {@code TavernBarrelCompat}）
     * 同样包了 {@code synchronizedMap}，这里保持一致。</p>
     */
    private static final java.util.Map<BlockEntity, UUID> PLACERS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private PlacerPersist() {
    }

    /** 记录放置者并标记 BE 需要保存。 */
    public static void set(BlockEntity be, UUID uuid) {
        if (be == null || uuid == null) return;
        PLACERS.put(be, uuid);
        be.setChanged();
    }

    /** 读取运行时归属（无则 null）。 */
    public static UUID get(BlockEntity be) {
        return be == null ? null : PLACERS.get(be);
    }

    /** 保存到 BE NBT（唯一权威来源）。 */
    public static void save(BlockEntity be, CompoundTag tag) {
        if (be == null || tag == null) return;
        UUID uuid = PLACERS.get(be);
        if (uuid != null) {
            tag.putUUID(KEY_UUID, uuid);
        }
    }

    /** 从 BE NBT 读取（兼容旧存档同键数据）并载入运行时缓存。 */
    public static void load(BlockEntity be, CompoundTag tag) {
        if (be == null || tag == null) return;
        if (tag.hasUUID(KEY_UUID)) {
            PLACERS.put(be, tag.getUUID(KEY_UUID));
        } else {
            PLACERS.remove(be);
        }
    }
}
