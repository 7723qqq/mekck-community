package cn.ism.mekck.upgrade;

import mekanism.api.Upgrade;

/**
 * MekCK 注入 {@link Upgrade} 的 2 个常量，由 {@code MixinUpgrade} 在
 * {@code Upgrade.<clinit>} 的 TAIL 处赋值。字段形态与 {@link MekCkAPILang} 同理，
 * 原因见该类注释。
 *
 * <p><b>为什么常量名与物品 id 不一致</b>：随机化卡的枚举常量叫
 * {@code RANDOMIZE} 而不是 {@code CREATIVE}，因为 Mek Extras 已经注入了一个同名的
 * {@code CREATIVE}。撞车发生在<b>物品注册 id / 注册表命名空间</b>上，
 * <b>不是</b>存档键上。
 *
 * <p><b>撞车机制</b>：{@code getRawName()} <b>根本不参与</b> Mekanism 的存档路径。
 * 全 jar 只有 2 个 class 引用它——{@code Upgrade} 自身与 {@code MekanismItems}；
 * 后者在 {@code registerUpgrade} 里把它与字面量 {@code "upgrade_"} 拼出
 * {@code mekanism:upgrade_<rawName>} 这个<b>注册 id</b>。两个 mod 都注入
 * {@code CREATIVE}，就会去抢同一个注册 id（若各自用独立命名空间，则是各自域内
 * 同名常量并存，容易被按名查找的逻辑误认）。而物品是按<b>注册 id</b>进存档的，
 * 所以后果是注册冲突与按名查找的歧义。
 *
 * <p><b>不要按「Mek 按 rawName 存存档」去推理</b>：Mek 的升级持久化走的是
 * <b>ordinal</b>——{@code getTag} 写 {@code putInt("type", ordinal())}，
 * {@code buildMap} 读 {@code getInt("type")} 再喂给 {@code byIndexStatic}。
 * 详见 {@code MixinUpgrade} 的类注释。物品 id 用 {@code upgrade_randomize}，
 * 与旧存档的 {@code mekanism_extras:upgrade_creative} 也自然解耦。
 *
 * <p><b>消费方一律调方法（{@link #storage()}），不要直接读字段，
 * 也不要 {@code Upgrade.valueOf("STORAGE")}。</b>
 * 同名的静态字段与静态方法只差一个括号，写错时编译器不报错，
 * 直接读字段拿到的是 {@code null}；走方法至少有 {@link #require} 兜底。
 * {@code valueOf} 同样不可依赖：它走 {@code java.lang.Class} 上的一次性缓存
 * {@code enumConstantDirectory}（实测字段：{@code private volatile transient
 * Map<String, T> enumConstantDirectory}），而该缓存不因改写 {@code $VALUES} 而重建，
 * 能否命中取决于 {@code <clinit>} 内是否发生过重入——这个时序没有契约保证。
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
