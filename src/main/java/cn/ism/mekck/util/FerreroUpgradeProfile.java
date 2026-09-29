package cn.ism.mekck.util;

/**
 * §F17 费列罗升级伤害阶梯（逐档查表，单点定义伤害来源，禁止乘法/三元叠加）。
 * <p>
 * 巧克力大炮的 5 个费列罗升级（椰蓉/死神/脆心/霸王死神/超重力场）各占独立槽、可任意组合，
 * 直击伤害只按「已安装升级数」查表取值，与具体是哪几档无关：
 * </p>
 * <pre>
 * 已装档数:  0     1     2     3     4     5
 * 直击伤害: 250   500   600   700   800  1000
 * </pre>
 * <p>
 * 0 档（无任何费列罗升级）与 {@link cn.ism.mekck.entity.FerreroEntity#BASE_DAMAGE} 同为 250。
 * 伤害倍数 {@code ferrero_damage_mult} 在调用方（{@code ChocolateCannonBlockEntity.performAttack}）
 * 查档后**一处**乘入，令直击、范围结算、处决判定与预留/评分共用同一乘算后伤害。
 * </p>
 */
public final class FerreroUpgradeProfile {

    private FerreroUpgradeProfile() {
    }

    /** 索引 = 已安装升级数（0..5），值 = 直击伤害。多于 5 按顶档 1000 处理。 */
    private static final float[] DAMAGE_LADDER = {250.0F, 500.0F, 600.0F, 700.0F, 800.0F, 1000.0F};

    /** 按已安装费列罗升级数查直击伤害（未乘 {@code ferrero_damage_mult}）。 */
    public static float damageOf(int installedTierCount) {
        if (installedTierCount <= 0) {
            return DAMAGE_LADDER[0];
        }
        if (installedTierCount >= DAMAGE_LADDER.length) {
            return DAMAGE_LADDER[DAMAGE_LADDER.length - 1];
        }
        return DAMAGE_LADDER[installedTierCount];
    }
}
