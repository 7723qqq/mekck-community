package cn.ism.mekck.advancement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * “网络厨师学徒”离线待授予队列（服务器级持久化）。
 * <p>
 * 作用域：Overworld 的 DimensionDataStorage，所有维度共用同一份数据；
 * 仅在集合增删时 {@link #setDirty()}，不每 tick 写盘。
 */
public class PendingGrantData extends SavedData {

    private static final String DATA_ID = "mekck_pending_grants";
    private static final String KEY_UUIDS = "PendingUuids";

    final Set<UUID> pending = new HashSet<>();

    public PendingGrantData() {
    }

    public static PendingGrantData load(CompoundTag tag) {
        PendingGrantData d = new PendingGrantData();
        ListTag list = tag.getList(KEY_UUIDS, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String s = list.getString(i);
            if (!s.isEmpty()) {
                try {
                    d.pending.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID uuid : pending) {
            list.add(StringTag.valueOf(uuid.toString()));
        }
        tag.put(KEY_UUIDS, list);
        return tag;
    }

    public boolean contains(UUID uuid) {
        return pending.contains(uuid);
    }

    public void addPending(UUID uuid) {
        if (pending.add(uuid)) {
            setDirty();
        }
    }

    public void removePending(UUID uuid) {
        if (pending.remove(uuid)) {
            setDirty();
        }
    }

    /** 服务器统一作用域：Overworld 存档（所有维度共用）。 */
    public static PendingGrantData get(MinecraftServer server) {
        if (server == null) return null;
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) return null;
            return overworld.getDataStorage().computeIfAbsent(PendingGrantData::load, PendingGrantData::new, DATA_ID);
        } catch (Throwable t) {
            return null;
        }
    }
}
