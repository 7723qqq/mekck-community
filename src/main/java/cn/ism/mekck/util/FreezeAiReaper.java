package cn.ism.mekck.util;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 「冰块/费列罗关 AI」的<b>兜底回收器</b> —— 专治服务器重启留下的永久无 AI 生物。
 *
 * <h3>为什么需要它</h3>
 * {@code IceCubeEntity.applyHit} 与 {@code FerreroEntity.applyHit} 关 AI 的做法是
 * 「{@code setNoAi(true)} + 往 {@code MinecraftServer.tellables} 塞一个 {@link
 * net.minecraft.server.TickTask} 到期恢复」。两个前提都不成立：
 * <ol>
 *   <li>那个队列是<b>纯内存</b>的，服务器一重启就没了；</li>
 *   <li>而 {@code NoAI} <b>会写进存档</b>（实测 {@code Mob.addAdditionalSaveData} 写
 *       {@code "NoAI"} 字符串键）—— 也就是说「关」这件事活过了重启，「恢复」却没有。</li>
 * </ol>
 * 永冻（{@code effect/EternalFreezeEffect}）的那一份已经改成用 effect 自身的 per-tick
 * 钩子恢复（见该类注释），但这两处是<b>一次性事件</b>驱动的，没有 per-tick 钩子可挂。
 *
 * <p>兜底方案：区块加载时扫一遍该区块的实体，凡是「仍然 NoAI、且恢复刻已到（或恢复刻
 * 干脆不存在，说明是被旧存档留下的）」就清掉 AI。挂在 {@link LevelEvent.Load} 上而不是
 * 定时器上，是因为它<b>天然按区块分片</b>——一次只处理刚加载的那个区块，代价与区块实体
 * 数成正比，与全服实体数无关。</p>
 *
 * <h3>为什么判据是「恢复刻不存在 <b>或</b> 已到」</h3>
 * 旧版本写下的存档里，恢复刻是随 {@code getPersistentData()} 一起存的，所以正常情况下
 * 键是存在的。但键<b>可能整个缺失</b>（手工改档、更早的版本、外部工具）。
 * 此时若严格要求「键存在且已到」，那只生物就永远恢复不了 —— 正是我们要消灭的形态。
 * 所以「没有键」一律按「无人认领的 NoAI」处理并清掉。</p>
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FreezeAiReaper {

    private static final Logger LOGGER = LoggerFactory.getLogger(FreezeAiReaper.class);

    /** 冰块关 AI 用的恢复刻键（与 {@code EternalFreezeEffect.AI_RESTORE_KEY} 独立）。 */
    public static final String ICE_CUBE_AI_RESTORE_KEY = "mekck:ai_restore_tick";
    /**
     * 费列罗关 AI 用的恢复刻键。
     *
     * <p>与 {@link #ICE_CUBE_AI_RESTORE_KEY} <b>刻意分开</b>：两个来源的免 AI 窗口
     * 是各自定义的（冰块 2 秒、费列罗 2 秒，将来极易一边改一边没改），
     * 共键会让「谁后命中谁说了算」，短窗口覆盖掉长窗口的恢复刻。
     * 分开之后本回收器才能分别判断该由哪一方负责恢复。</p>
     */
    public static final String FERRERO_AI_RESTORE_KEY = "mekck:ferrero_ai_restore_tick";

    private FreezeAiReaper() {
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        int restored = 0;
        for (var entity : serverLevel.getEntities().getAll()) {
            if (entity instanceof Mob mob && mob.isNoAi() && reapIfOrphaned(mob, serverLevel.getGameTime())) {
                restored++;
            }
        }
        if (restored > 0 && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[mekck] 区块 {} 加载时回收了 {} 只残留 NoAI 的生物",
                    serverLevel.dimension().location(), restored);
        }
    }

    /**
     * 把「无人认领的 NoAI」恢复掉。返回是否真的动过手。
     *
     * <p>纯逻辑部分抽成包级静态，好让裸 JVM 的断言能覆盖判据本身（真 {@code Mob}
     * 在无 Minecraft 运行时的测试环境里造不出来）。</p>
     */
    static boolean reapIfOrphaned(Mob mob, long gameTime) {
        CompoundTag data = mob.getPersistentData();
        boolean frozenKey = data.contains(ICE_CUBE_AI_RESTORE_KEY, net.minecraft.nbt.Tag.TAG_LONG);
        boolean ferreroKey = data.contains(FERRERO_AI_RESTORE_KEY, net.minecraft.nbt.Tag.TAG_LONG);
        if (frozenKey || ferreroKey) {
            long due = frozenKey
                    ? data.getLong(ICE_CUBE_AI_RESTORE_KEY)
                    : data.getLong(FERRERO_AI_RESTORE_KEY);
            // 键还在、且恢复刻未到：说明它的快速恢复任务还在这一会话的队列里排着，不插手。
            if (due > gameTime) {
                return false;
            }
        }
        mob.setNoAi(false);
        data.remove(ICE_CUBE_AI_RESTORE_KEY);
        data.remove(FERRERO_AI_RESTORE_KEY);
        return true;
    }
}
