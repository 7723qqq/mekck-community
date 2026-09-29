package cn.ism.mekck.item;

/**
 * 冷萃升级等级。共 6 种，需在机器上按链式顺序安装（1→2→3→4→5）。
 * 槽位序号（1..5）对应制冰机/制冰工厂上的 5 个冷萃升级槽；
 * 第 4 档龙霜/女王二选一，第 5 档失温（需先装龙霜/女王）。
 */
public enum ColdBrewTier {
    COLD("cold_brew_upgrade", 1),
    LOW_TEMP("low_temp_cold_brew_upgrade", 2),
    FROST("frost_cold_brew_upgrade", 3),
    DRAGON_FROST("dragon_frost_cold_brew_upgrade", 4),
    QUEEN("queen_cold_brew_upgrade", 4),
    HYPOTHERMIA("hypothermia_upgrade", 5);

    public final String id;
    /** 该升级所属的槽位序号（1..5）。 */
    public final int slot;

    ColdBrewTier(String id, int slot) {
        this.id = id;
        this.slot = slot;
    }
}
