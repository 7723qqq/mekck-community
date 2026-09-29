package cn.ism.mekck.upgrade;

import mekanism.api.Upgrade;

/**
 * MekCK 注入 {@link Upgrade} 的 2 个常量，由 {@code MixinUpgrade} 在
 * {@code Upgrade.<clinit>} 的 TAIL 处赋值。字段形态与 {@link MekCkAPILang} 同理，
 * 原因见该类注释。
 *
 * <p><b>为什么常量名与物品 id 不一致</b>：随机化卡的枚举常量叫
 * {@code RANDOMIZE} 而不是 {@code CREATIVE}，因为 Mek Extras 已经注入了一个
 * 同名的 {@code CREATIVE}。两个同名不同实例的常量，在按 {@code getRawName()}
 * 存档的体系里会被当成同一张卡。物品 id 用 {@code upgrade_randomize}，
 * 与旧存档的 {@code mekanism_extras:upgrade_creative} 也自然解耦。
 *
 * <p><b>消费方一律调方法（{@link #storage()}），不要直接读字段。</b>
 * 同名的静态字段与静态方法只差一个括号，写错时编译器不报错，
 * 直接读字段拿到的是 {@code null}；走方法至少有 {@link #require} 兜底。
 */
public final class MekCkUpgradeRefs {

    /** 存储卡：提升并行线程数与缓冲容量。 */
    public static Upgrade storage;
    /** 随机化卡：随机化本局 49 种可用食物。 */
    public static Upgrade randomize;

    private MekCkUpgradeRefs() {
    }

    public static Upgrade storage() {
        return require(storage, "STORAGE");
    }

    public static Upgrade randomize() {
        return require(randomize, "RANDOMIZE");
    }

    private static Upgrade require(Upgrade value, String name) {
        if (value == null) {
            throw new IllegalStateException(
                    "Upgrade 常量 " + name + " 未注入：MixinUpgrade 未生效。"
                            + "检查 mekck.mixins.json 是否挂载了它。");
        }
        return value;
    }
}
