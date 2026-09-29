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
        if (entity instanceof Mob mob && !mob.isNoAi() && entity.level() instanceof ServerLevel serverLevel) {
            mob.setNoAi(true);
            long restoreTick = serverLevel.getServer().getTickCount() + DURATION;
            mob.getPersistentData().putLong(AI_RESTORE_KEY, restoreTick);
            final long scheduled = restoreTick;
            serverLevel.getServer().tell(new net.minecraft.server.TickTask((int) scheduled, () -> {
                if (mob.isAlive()
                        && mob.getPersistentData().getLong(AI_RESTORE_KEY) <= serverLevel.getServer().getTickCount()
                        && !mob.hasEffect(UniversalCuttingMachine.ETERNAL_FREEZE_EFFECT.get())) {
                    mob.setNoAi(false);
                }
            }));
        }

        // 非末影龙、非创造玩家的离地实体：0.2 格/tick 向下牵引
        if (!(entity instanceof EnderDragon)
                && !(entity instanceof Player player && player.isCreative())
                && !entity.onGround()) {
            entity.setDeltaMovement(entity.getDeltaMovement().add(0, -0.2D, 0));
        }
    }
}
