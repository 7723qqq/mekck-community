package cn.ism.mekck.upgrade;

import mekanism.api.text.APILang;

/**
 * MekCK 注入 {@link APILang} 的 4 个译名常量，由 {@code MixinAPILang} 在
 * {@code APILang.<clinit>} 的 TAIL 处赋值。
 *
 * <p><b>字段不能是 final 也不能在静态初始化器里赋值</b>：赋值发生在
 * {@code APILang} 的类初始化过程中，若 {@code MekCkAPILang} 的 {@code <clinit>}
 * 先跑并读 {@code APILang}，就会在注入发生前看到 null。
 * 因此全部通过 {@link #upgradeStorage()} 这类方法在<b>调用时</b>读取，
 * 而调用方必然已经触达过 {@code APILang}（要拿它就得先加载它）。
 *
 * <p>译名 key 用 {@code upgrade.mekck.*} 前缀而非 {@code upgrade.mekanism.*}——
 * 后者需要覆盖 Mekanism 自己的 lang 文件，附属模组不该这么做。
 */
public final class MekCkAPILang {

    /** 存储卡标题，存档/界面用。 */
    public static APILang upgradeStorage;
    /** 存储卡说明，升级列表的 tooltip 用。 */
    public static APILang upgradeStorageDescription;
    /** 随机化卡标题。 */
    public static APILang upgradeRandomize;
    /** 随机化卡说明。 */
    public static APILang upgradeRandomizeDescription;

    private MekCkAPILang() {
    }

    /**
     * 强制 {@code APILang} 完成类初始化后再取字段。
     *
     * @throws IllegalStateException Mixin 未生效时（正常情况下应为启动期崩溃，此处是兜底）
     */
    public static APILang upgradeStorage() {
        return require(upgradeStorage, "UPGRADE_STORAGE");
    }

    public static APILang upgradeStorageDescription() {
        return require(upgradeStorageDescription, "UPGRADE_STORAGE_DESCRIPTION");
    }

    public static APILang upgradeRandomize() {
        return require(upgradeRandomize, "UPGRADE_RANDOMIZE");
    }

    public static APILang upgradeRandomizeDescription() {
        return require(upgradeRandomizeDescription, "UPGRADE_RANDOMIZE_DESCRIPTION");
    }

    private static APILang require(APILang value, String name) {
        if (value == null) {
            throw new IllegalStateException(
                    "APILang 常量 " + name + " 未注入：MixinAPILang 未生效。"
                            + "检查 mekck.mixins.json 是否挂载了它。");
        }
        return value;
    }
}
