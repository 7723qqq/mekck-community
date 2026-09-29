package cn.ism.mekck.item;

/**
 * 巧克力大炮的费列罗升级档位。5 种升级相互独立，各占一个专属升级槽，可任意组合。
 */
public enum FerreroUpgradeTier {

    /** 椰蓉升级：计入「已装升级数」（推动直击伤害沿 250/500/600/700/800/1000 阶梯上移一档）。 */
    COCONUT("coconut_upgrade"),
    /** 死神升级：每次攻击额外发射 1 颗费列罗，产物消耗变为 2。 */
    REAPER("reaper_upgrade"),
    /** 脆心升级：受到伤害的实体移除 2 秒 AI（机制同女王冷萃升级）。 */
    CRISPY("crispy_upgrade"),
    /** 霸王死神升级：伤害范围从 3×3×3 扩大为 7×7×7。 */
    OVERLORD("overlord_reaper_upgrade"),
    /** 超重力场升级：伤害类型从爆炸变为铁砧，杀死的实体不播放死亡动画。 */
    GRAVITY("gravity_field_upgrade");

    public final String id;

    FerreroUpgradeTier(String id) {
        this.id = id;
    }
}
