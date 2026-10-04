package cn.ism.mekck.effect;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;

/**
 * 永冻：失温叠满 20 级（amplifier 19）时附加。
 * <ul>
 *   <li>关闭 AI（Mob）：持久化记录恢复刻并安排 TickTask 到期恢复，重复命中刷新恢复时间；
 *       <b>自救由本效果自身的剩余时长驱动</b>（见 {@link #applyFreeze}），不依赖「效果已被移除」；</li>
 *   <li>非末影龙、非创造玩家的离地实体每 tick 附加 0.2 格向下速度；</li>
 *   <li>受到任何伤害被替换为 int 上限的冰冻伤害（创造模式玩家除外）、战利品按最大随机源重掷
 *       ——概率判定全部通过、数量取上限，同一战利品池内仍按权重择一（见 {@code util/FreezeEvents}）；</li>
 *   <li>冰封视觉：与冰冻效果共享客户端冰壳渲染（RenderLivingEvent.Post）。</li>
 * </ul>
 */
public class EternalFreezeEffect extends MobEffect {

    /** 永冻恢复 AI 的持久化恢复刻键（与女王冷萃的 mekck:ai_restore_tick 独立）。 */
    public static final String AI_RESTORE_KEY = "mekck:eternal_freeze_ai_restore_tick";
    /** 永冻持续时间（tick）：1 小时。 */
    public static final int DURATION = 20 * 60 * 60;

    public EternalFreezeEffect() {
        super(MobEffectCategory.HARMFUL, 0x1F4E79);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.level().isClientSide) return;

        // 关闭 AI / 自救（用效果自身的剩余时长驱动，见 applyFreeze 注释）
        if (entity instanceof Mob mob && entity.level() instanceof ServerLevel serverLevel) {
            applyFreeze(mob, serverLevel);
        }

        // 非末影龙、非创造玩家的离地实体：0.2 格/tick 向下牵引
        if (!(entity instanceof EnderDragon)
                && !(entity instanceof Player player && player.isCreative())
                && !entity.onGround()) {
            entity.setDeltaMovement(entity.getDeltaMovement().add(0, -0.2D, 0));
        }
    }

    /**
     * 永冻对 Mob 的关 AI 与<b>自救</b>。
     *
     * <p><b>为什么不能依赖「效果已被移除」</b>：{@code applyEffectTick} 只在效果仍在
     * {@code activeEffects} 里时被回调（{@code MobEffectInstance#applyEffect} 先判
     * {@code hasRemainingDuration()}），所以此刻 {@code entity.hasEffect(this)} <b>恒真</b>，
     * 原实现的自救分支 {@code due && !stillFrozen} <b>永远进不去</b>。这里改用<b>效果实例自身的
     * 剩余时长</b>驱动：{@code remaining <= 1} 即本 tick 到期（applyEffectTick 保证 duration ≥ 1），
     * 就地恢复 AI 并清键。</p>
     *
     * <p><b>恢复刻的持久基准</b>：{@code due = getGameTime() + remaining}。正常倒计时里它是常量
     * （now 每 tick +1、remaining 每 tick -1），只有「命中刷新链把效果时长重置」时才会顺延 ——
     * 因此重复命中（含目标已是 NoAI 的刷新路径）天然把恢复刻往后推，不会出现
     * 「提前恢复 → 下一 tick 又冻结」的循环。写入侧（本方法）与兜底回收器用的是同一把尺子
     * {@code getGameTime}，跨重启延续。</p>
     */
    private void applyFreeze(Mob mob, ServerLevel serverLevel) {
        MobEffectInstance self = mob.getEffect(this);
        if (self == null) {
            return;
        }
        int remaining = self.getDuration();
        if (remaining <= 1) {
            // 本 tick 到期：此刻效果实例必然还在，故不能等它被移除再恢复
            mob.setNoAi(false);
            mob.getPersistentData().remove(AI_RESTORE_KEY);
            return;
        }
        if (!mob.isNoAi()) {
            mob.setNoAi(true);
        }
        long due = serverLevel.getGameTime() + remaining;
        if (mob.getPersistentData().getLong(AI_RESTORE_KEY) != due) {
            // 首次施加或刷新延长：写新的持久恢复刻，并排一个同会话快速恢复任务兜底
            mob.getPersistentData().putLong(AI_RESTORE_KEY, due);
            int scheduleTick = serverLevel.getServer().getTickCount() + remaining;
            serverLevel.getServer().tell(new net.minecraft.server.TickTask(scheduleTick, () -> {
                // TickTask 只按会话基准调度；到期判据与写入侧同源（getGameTime），
                // 且仅当效果确实已不在身上时才恢复，避免刷新链被旧任务提前解锁。
                if (mob.isAlive()
                        && mob.getPersistentData().getLong(AI_RESTORE_KEY) <= serverLevel.getGameTime()
                        && mob.getEffect(this) == null) {
                    mob.setNoAi(false);
                    mob.getPersistentData().remove(AI_RESTORE_KEY);
                }
            }));
        }
    }
}
