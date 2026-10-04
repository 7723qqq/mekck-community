package cn.ism.mekck.item;

import net.minecraft.world.item.ItemStack;

/**
 * 根据 5 个冷萃升级槽中的物品计算攻击档位。
 * 槽位链式：CB1=冷萃, CB2=低温冷萃(需CB1), CB3=凛冷萃(需CB2), CB4=龙霜/女王(需CB3), CB5=失温(需CB4)。
 */
public final class ColdBrewHelper {

    private ColdBrewHelper() {
    }

    public static final class Profile {
        public final float damage;
        public final boolean aoe;
        /** 溅射（AOE）伤害：低温 5、凛冰 20、龙霜 40；无 AOE 时为 0。 */
        public final float splashDamage;
        public final boolean slow;
        public final boolean removeAI;
        public final int targetCount;
        /** 目标不足时是否用多余冰块集火最高血量目标（凛冷萃及其以上）。 */
        public final boolean allowExtras;
        /** 失温升级（CB5）：命中实体与溅射目标附加失温（最多 20 级，amplifier 达 19 时附加永冻）。 */
        public final boolean hypothermia;

        public Profile(float damage, boolean aoe, float splashDamage, boolean slow, boolean removeAI, int targetCount, boolean allowExtras, boolean hypothermia) {
            this.damage = damage;
            this.aoe = aoe;
            this.splashDamage = splashDamage;
            this.slow = slow;
            this.removeAI = removeAI;
            this.targetCount = targetCount;
            this.allowExtras = allowExtras;
            this.hypothermia = hypothermia;
        }
    }

    public static Profile compute(ItemStack cb1, ItemStack cb2, ItemStack cb3, ItemStack cb4, ItemStack cb5) {
        return compute(cb1.isEmpty() ? null : ColdBrewUpgradeItem.getTier(cb1),
                cb2.isEmpty() ? null : ColdBrewUpgradeItem.getTier(cb2),
                cb3.isEmpty() ? null : ColdBrewUpgradeItem.getTier(cb3),
                cb4.isEmpty() ? null : ColdBrewUpgradeItem.getTier(cb4),
                cb5.isEmpty() ? null : ColdBrewUpgradeItem.getTier(cb5));
    }

    /**
     * 按「已安装的冷萃等级」计算攻击档案（升级需经读条安装后生效，安装后槽位物品被消耗，
     * 因此以已安装等级为准，而不是槽位物品）。
     *
     * @param cb1 冷萃（null 表示未安装）
     * @param cb2 低温冷萃
     * @param cb3 凛冰冷萃
     * @param cb4 龙霜冷萃或女王冷萃
     * @param cb5 失温升级
     */
    public static Profile compute(ColdBrewTier cb1, ColdBrewTier cb2, ColdBrewTier cb3, ColdBrewTier cb4, ColdBrewTier cb5) {
        if (cb1 == null) return null;
        float damage = 20.0F;
        boolean aoe = false;
        float splashDamage = 0.0F;
        boolean slow = false;
        boolean removeAI = false;
        int targetCount = 4;
        boolean allowExtras = false;
        boolean hypothermia = false;

        if (cb2 != null) {
            // 低温冷萃：直击 40 + 落点 3×3×3 溅射 5
            damage = 40.0F;
            aoe = true;
            splashDamage = 5.0F;
        }
        if (cb3 != null) {
            // 凛冰冷萃：直击 200，溅射由 5 提升至 20
            damage = 200.0F;
            splashDamage = 20.0F;
            targetCount = 5;
            allowExtras = true;
        }
        if (cb4 == ColdBrewTier.DRAGON_FROST) {
            // 龙霜冷萃：直击 300 + 冰封，溅射进一步提升至 40
            damage = 300.0F;
            splashDamage = 40.0F;
            slow = true;
        } else if (cb4 == ColdBrewTier.QUEEN) {
            removeAI = true; // 伤害沿用凛冷萃的 200，溅射沿用凛冷萃的 20
        }
        if (cb5 == ColdBrewTier.HYPOTHERMIA) {
            hypothermia = true;
        }
        return new Profile(damage, aoe, splashDamage, slow, removeAI, targetCount, allowExtras, hypothermia);
    }
}
