package cn.ism.mekck.util;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.effect.EternalFreezeEffect;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「冰块/费列罗/永冻关 AI」的<b>兜底回收器</b> —— 专治区块卸载或服务器重启留下的永久无 AI 生物。
 *
 * <h3>为什么需要它</h3>
 * {@code IceCubeEntity.applyHit}、{@code FerreroEntity.removeAI} 与
 * {@code effect/EternalFreezeEffect} 关 AI 的做法都是「{@code setNoAi(true)} + 写一个持久化恢复刻
 * + 往 {@code MinecraftServer} 塞一个 {@link net.minecraft.server.TickTask} 到期恢复」。那个队列是
 * <b>纯内存</b>的，服务器一重启就没了；而 {@code NoAI} <b>会写进存档</b>（实测
 * {@code Mob.addAdditionalSaveData} 写 {@code "NoAI"} 字符串键）—— 也就是说「关」这件事活过了
 * 重启，「恢复」却没有。区块卸载同理：实体的 TickTask 随会话消失，重载后无人认领。
 *
 * <h3>事件选择依据（不采信注释，据 Forge sources jar）</h3>
 * <p>本回收器挂在 {@link EntityJoinLevelEvent} 上。该事件的 Forge 源码 javadoc 原文：
 * 「This event is fired whenever an entity is added to a level in {@code Level#addFreshEntity(Entity)}
 * and {@code PersistentEntitySectionManager#addNewEntity(Entity, boolean)}.」而 {@code PersistentEntitySectionManager}
 * 在「区块从磁盘载入」（含服务器重启、区块卸载后重载）时即以 {@code loadedFromDisk = true} 走这条路径 ——
 * 这正是「区块/实体重新载入」的覆盖面，故这里以 {@link EntityJoinLevelEvent#loadedFromDisk()} 为闸门，
 * 只回收「重新载入」的实体，不碰当场刷出的新实体。该事件可能早于区块升到 {@code FULL}，
 * 但本处理只改实体自身状态（NoAI / 持久数据），不做任何世界交互，故不触发其 javadoc 警示的区块加载死锁。</p>
 * <p>旧实现挂 {@code LevelEvent} 的维度加载子事件：其 javadoc 原文为「fired whenever a level loads in
 * {@code ClientLevel}'s constructor and {@code MinecraftServer#createLevels}」—— 每个维度<b>仅一次</b>，
 * 且触发时区块与实体尚未载入，兜底因此<b>全程空转</b>。</p>
 *
 * <h3>判据（反误伤）：只碰「本模组写过键」的 NoAI 生物</h3>
 * 只有携带 {@link #ICE_CUBE_AI_RESTORE_KEY}、{@link #FERRERO_AI_RESTORE_KEY} 或
 * {@link EternalFreezeEffect#AI_RESTORE_KEY} 三者之一的实体才参与回收 —— 这三种键只由本模组的
 * 冰块 / 费列罗 / 永冻写入。{@link EntityJoinLevelEvent} 覆盖<b>全部</b>区块载入路径，若把「无键的
 * NoAI」也一并解锁，会把其它模组、刷怪蛋、命令造出的 NoAI 生物（NPC、雕像、假人）误伤解冻，
 * 故<b>无键一律不碰</b>（不 {@code setNoAi(false)}、不清键）。代价：更早版本（尚未分区写键）
 * 留下的无键残留不再由本回收器兜底 —— 那是本轮之前就存在的形态，且永冻那一路钥匙由效果自身
 * 逐 tick 自救，不依赖无键路径。带键者则「任一来源恢复刻仍在未来 ⇒ 仍被该来源认领，不插手；
 * 全部已到 ⇒ 回收并清键」。
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FreezeAiReaper {

    private static final Logger LOGGER = LoggerFactory.getLogger(FreezeAiReaper.class);

    /** 冰块关 AI 用的恢复刻键（与费列罗、永冻各自独立）。 */
    public static final String ICE_CUBE_AI_RESTORE_KEY = "mekck:ai_restore_tick";
    /**
     * 费列罗关 AI 用的恢复刻键。
     *
     * <p>与 {@link #ICE_CUBE_AI_RESTORE_KEY} <b>刻意分开</b>：两个来源的免 AI 窗口
     * 是各自定义的，共键会让「谁后命中谁说了算」，短窗口覆盖掉长窗口的恢复刻。
     * 分开之后本回收器才能分别判断该由哪一方负责恢复。</p>
     */
    public static final String FERRERO_AI_RESTORE_KEY = "mekck:ferrero_ai_restore_tick";

    private FreezeAiReaper() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob) || !mob.isNoAi()) {
            return;
        }
        if (reapIfOrphaned(mob, serverLevel.getGameTime()) && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[mekck] 实体 {} 重新载入时回收了残留 NoAI", mob.getType());
        }
    }

    /**
     * 把「无人认领的 NoAI」恢复掉。返回是否真的动过手。
     *
     * <p>前置闸门：<b>无三键者一律不碰</b>（反误伤，见类注释）。比较基准是写入侧同一把尺子
     * （{@link ServerLevel#getGameTime()}，跨重启延续），不是每会话归零的那个会话内计数。</p>
     */
    static boolean reapIfOrphaned(Mob mob, long gameTime) {
        CompoundTag data = mob.getPersistentData();
        boolean ice = data.contains(ICE_CUBE_AI_RESTORE_KEY, Tag.TAG_LONG);
        boolean ferrero = data.contains(FERRERO_AI_RESTORE_KEY, Tag.TAG_LONG);
        boolean eternal = data.contains(EternalFreezeEffect.AI_RESTORE_KEY, Tag.TAG_LONG);
        if (!shouldReap(gameTime, ice || ferrero || eternal,
                ice ? data.getLong(ICE_CUBE_AI_RESTORE_KEY) : 0L,
                ferrero ? data.getLong(FERRERO_AI_RESTORE_KEY) : 0L,
                eternal ? data.getLong(EternalFreezeEffect.AI_RESTORE_KEY) : 0L)) {
            return false;
        }
        mob.setNoAi(false);
        data.remove(ICE_CUBE_AI_RESTORE_KEY);
        data.remove(FERRERO_AI_RESTORE_KEY);
        data.remove(EternalFreezeEffect.AI_RESTORE_KEY);
        return true;
    }

    /**
     * 纯逻辑判据：<b>无键（{@code anyKeyPresent == false}）一律返回 {@code false}</b>（反误伤，
     * 不碰其它模组/刷怪蛋/命令造出的 NoAI 生物）；带键时，只要还有任一来源的恢复刻在未来
     * （{@code > gameTime}）就是「仍被认领」，返回 {@code false}；全部已到才返回 {@code true}。
     *
     * <p>抽成包级静态，好让裸 JVM 的断言能覆盖判据本身（真 {@code Mob} 在无 Minecraft
     * 运行时的测试环境里造不出来）。</p>
     */
    static boolean shouldReap(long gameTime, boolean anyKeyPresent, long... dueTicks) {
        if (!anyKeyPresent) {
            return false;
        }
        for (long due : dueTicks) {
            if (due > gameTime) {
                return false;
            }
        }
        return true;
    }
}