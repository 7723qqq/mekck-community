package cn.ism.mekck.effect;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;

/**
 * 永冻：失温叠满 20 级（amplifier 19）时附加。
 * <ul>
 *   <li>关闭 AI（Mob）：持久化记录恢复刻并安排 TickTask 到期恢复，重复命中刷新恢复时间；</li>
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

        // 关闭 AI 并安排到期恢复（持久化恢复刻校验，重复命中刷新）
        //
        // ⚠️ 「施加」与「恢复」必须分成两个互不依赖的判断（本轮修掉的自锁）。
        // 原实现把两者都挂在 `!mob.isNoAi()` 里，于是：
        //   1) Mob 的 NoAI 是**会写进存档**的（实测 Mob.addAdditionalSaveData 写 "NoAI"）；
        //   2) 而恢复只靠内存里的 TickTask（MinecraftServer.tellables），**重启即丢**；
        //   3) 重启后生物带着 NoAI=true 回来 ⇒ `!isNoAi()` 恒假 ⇒ 恢复逻辑**再也不会跑**。
        // 结果是一只**没有任何游戏内手段能恢复 AI** 的生物，被动挨打直到被打死掉战利品 ——
        // 等于一个半合法的刷怪点。
        //
        // 现在：恢复判定挂在「恢复刻已到」这个与当前 AI 状态**无关**的条件上，
        // 于是重启后 effect 仍在（效果持续时间同样不随离线流逝）时，applyEffectTick
        // 会自己把它救回来。TickTask 降级为「同一会话内的快速路径」，不再是唯一依靠。
        if (entity instanceof Mob mob) {
            if (entity.level() instanceof ServerLevel serverLevel) {
                long now = serverLevel.getServer().getTickCount();
                long dueTick = mob.getPersistentData().getLong(AI_RESTORE_KEY);
                boolean due = dueTick > 0 && dueTick <= now;
                boolean stillFrozen = entity.hasEffect(UniversalCuttingMachine.ETERNAL_FREEZE_EFFECT.get());

                if (due && !stillFrozen) {
                    // 到期：清 AI 并抹掉恢复刻，否则下次进服会重复走这条。
                    if (mob.isNoAi()) {
                        mob.setNoAi(false);
                    }
                    mob.getPersistentData().remove(AI_RESTORE_KEY);
                } else if (!mob.isNoAi()) {
                    // 首次施加：关 AI、记录恢复刻、并在同一会话内排一个快速恢复任务。
                    mob.setNoAi(true);
                    long restoreTick = now + DURATION;
                    mob.getPersistentData().putLong(AI_RESTORE_KEY, restoreTick);
                    final long scheduled = restoreTick;
                    serverLevel.getServer().tell(new net.minecraft.server.TickTask((int) scheduled, () -> {
                        if (mob.isAlive()
                                && mob.getPersistentData().getLong(AI_RESTORE_KEY) <= serverLevel.getServer().getTickCount()
                                && !mob.hasEffect(UniversalCuttingMachine.ETERNAL_FREEZE_EFFECT.get())) {
                            mob.setNoAi(false);
                            mob.getPersistentData().remove(AI_RESTORE_KEY);
                        }
                    }));
                }
            }
        }

        // 非末影龙、非创造玩家的离地实体：0.2 格/tick 向下牵引
        if (!(entity instanceof EnderDragon)
                && !(entity instanceof Player player && player.isCreative())
                && !entity.onGround()) {
            entity.setDeltaMovement(entity.getDeltaMovement().add(0, -0.2D, 0));
        }
    }
}
