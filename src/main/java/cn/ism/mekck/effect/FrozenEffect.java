package cn.ism.mekck.effect;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/**
 * 冰冻（原生实现，不依赖冰火传说）：复刻 iceandfire FrozenData.tickFrozen 的移动逻辑——
 * 水平移动 ×0.25、离地额外下坠 0.2 格/tick、遇火立即解除（清火）、结束碎冰粒子 + 玻璃碎裂音效。
 * 实体冰封视觉由客户端 {@code RenderLivingEvent.Post} 渲染（client/FrozenIceRender），仅视觉效果。
 */
public class FrozenEffect extends MobEffect {

    public FrozenEffect() {
        super(MobEffectCategory.HARMFUL, 0xA9DDF1);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.level().isClientSide) return;

        // 遇火立即解除冰冻（清火，冰火同款规则）
        if (entity.isOnFire()) {
            entity.clearFire();
            return;
        }

        // 创造玩家不受移动限制
        if (!(entity instanceof Player player && player.isCreative())) {
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(0.25F, 1.0F, 0.25F));
            if (!(entity instanceof EnderDragon) && !entity.onGround()) {
                entity.setDeltaMovement(entity.getDeltaMovement().add(0, -0.2D, 0));
            }
        }

        // 结束：碎冰粒子 + 玻璃碎裂音效（与冰火 clearFrozen 一致）
        MobEffectInstance instance = entity.getEffect(UniversalCuttingMachine.FROZEN_EFFECT.get());
        if (instance != null && instance.getDuration() <= 1 && entity.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()),
                    entity.getX() + (entity.getRandom().nextDouble() - 0.5D) * entity.getBbWidth(),
                    entity.getY() + entity.getRandom().nextDouble() * entity.getBbHeight(),
                    entity.getZ() + (entity.getRandom().nextDouble() - 0.5D) * entity.getBbWidth(),
                    15, 0.1D, 0.1D, 0.1D, 0.05D);
            entity.playSound(SoundEvents.GLASS_BREAK, 3.0F, 1.0F);
        }
    }
}
