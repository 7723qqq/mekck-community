package cn.ism.mekck.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 失温：每级降低移动速度 / 护甲值 / 盔甲韧性 5%（1~20 级）。
 * 属性修饰符按「每级 -0.05」乘算注册（原版按 (等级)×0.05 计算，20 级时为 -100%）。
 */
public class HypothermiaEffect extends MobEffect {

    private static final String SPEED_UUID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
    private static final String ARMOR_UUID = "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e";
    private static final String TOUGHNESS_UUID = "3c4d5e6f-7a8b-4c9d-0e1f-2a3b4c5d6e7f";

    public HypothermiaEffect() {
        super(MobEffectCategory.HARMFUL, 0x56CBF9);
        this.addAttributeModifier(Attributes.MOVEMENT_SPEED, SPEED_UUID, -0.05D, AttributeModifier.Operation.MULTIPLY_TOTAL);
        this.addAttributeModifier(Attributes.ARMOR, ARMOR_UUID, -0.05D, AttributeModifier.Operation.MULTIPLY_TOTAL);
        this.addAttributeModifier(Attributes.ARMOR_TOUGHNESS, TOUGHNESS_UUID, -0.05D, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        // 属性减益由 AttributeModifier 自动结算，无需额外逻辑
    }
}
