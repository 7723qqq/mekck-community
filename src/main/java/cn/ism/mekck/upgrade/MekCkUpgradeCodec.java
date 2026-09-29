package cn.ism.mekck.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 名字键的升级编解码，取代 {@code Upgrade.saveMap} / {@code Upgrade.buildMap}。
 *
 * <h3>为什么必须自己写</h3>
 * Mekanism 自己的编解码按 {@code ordinal()} 索引存取，而读取走
 * {@code MathUtils.getByIndexMod} —— <b>越界取模回绕，不抛异常、不打日志</b>。
 * 任何注入 {@code Upgrade} 的 mod 增删都会让后续常量的 ordinal 整体位移，
 * 玩家旧存档里的升级数量会<b>静默映射到另一种升级</b>上。
 * 机理见 {@code TestUpgradeCodecRoundTrip} 与设计文档 §5.4。
 *
 * <p>本类按字符串名字存取，因此注入者缺席时该项<b>原样保留在存档里</b>
 * （进 {@link Decoded#unknownRaw()}），玩家装回该 mod 后自动恢复，
 * 既不误配也不丢数据。
 *
 * <h3>为什么用泛型而不是直接吃 {@code Upgrade}</h3>
 * {@code Upgrade} 的静态初始化链是
 * {@code Upgrade → EnumColor → DyeColor → ItemTags → Registries}，
 * 在无游戏环境的普通 JVM 里必然抛 {@code ExceptionInInitializerError}
 * （{@code net.minecraft.server.Bootstrap#bootStrap()} 同样失败——
 * 它需要 {@code Util.fetchChoiceType} 与完整注册表）。
 * 因此核心逻辑对类型键泛型化，可用 {@code String} 键在普通 JUnit 里完整覆盖；
 * {@code Upgrade} 的绑定放在 {@link MekCkUpgradeTypes}，只在游戏内加载。
 *
 * <h3>与 Mek 组件的关系</h3>
 * 只替换<b>持久化这一层</b>。Mek 的 {@code TileComponentUpgrade} 运行时行为
 * （20 tick 安装读条、槽位合法性、GUI 升级 tab）全部照旧，由
 * {@code MixinTileComponentUpgradePersistence} 把它的 read/write 重定向到这里。
 */
public final class MekCkUpgradeCodec {

    /** 复合标签内的列表键，与 Mek 自己的 "upgrades" 保持一致，便于人工排查存档。 */
    public static final String KEY = "upgrades";
    private static final String K_TYPE = "type";
    private static final String K_AMOUNT = "amount";

    /**
     * 解码结果。
     *
     * @param known      解析成功且数量大于 0 的条目
     * @param unknownRaw 解析不出来的原始条目（注入者缺席），<b>必须原样回写</b>，否则数据丢失
     */
    public record Decoded<K>(Map<K, Integer> known, List<CompoundTag> unknownRaw) {
    }

    private MekCkUpgradeCodec() {
    }

    /**
     * 编码为名字键列表。
     *
     * @param nameOf     类型键 → 存档里存的字符串名
     * @param known      已安装数量；数量 ≤ 0 的条目跳过
     * @param unknownRaw 之前解出但无法解析的原始条目，原样附加在已知条目之后
     */
    public static <K> CompoundTag encode(Map<K, Integer> known, Function<K, String> nameOf,
                                         List<CompoundTag> unknownRaw) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        if (known != null) {
            for (Map.Entry<K, Integer> entry : known.entrySet()) {
                int amount = entry.getValue() == null ? 0 : entry.getValue();
                if (amount <= 0) {
                    continue;
                }
                CompoundTag one = new CompoundTag();
                one.putString(K_TYPE, nameOf.apply(entry.getKey()));
                one.putInt(K_AMOUNT, amount);
                list.add(one);
            }
        }
        if (unknownRaw != null) {
            for (CompoundTag raw : unknownRaw) {
                if (raw != null) {
                    list.add(raw.copy());
                }
            }
        }
        tag.put(KEY, list);
        return tag;
    }

    /**
     * 从名字键列表解码。
     *
     * @param resolver 存档里的名字 → 类型键；<b>返回 {@code null} 表示该名字当前不存在</b>
     *                 （注入者缺席），此时条目进 {@link Decoded#unknownRaw()} 而非丢弃
     * @param capOf    各类型的安装上限。不用 {@code Upgrade.getMax()}，因为 MekCK 允许
     *                 配置值超过枚举自带的 {@code maxStack}（MekCK 自注入的常量其
     *                 {@code maxStack} 由自己指定）
     * @return 解析结果，永不为 null
     */
    public static <K> Decoded<K> decode(CompoundTag tag, Function<String, K> resolver,
                                        Function<K, Integer> capOf) {
        Map<K, Integer> known = new LinkedHashMap<>();
        List<CompoundTag> unknown = new ArrayList<>();
        if (tag == null) {
            return new Decoded<>(known, unknown);
        }
        ListTag list = tag.getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            K type = resolver.apply(one.getString(K_TYPE));
            if (type == null) {
                unknown.add(one.copy());
                continue;
            }
            int cap = Math.max(0, capOf.apply(type) == null ? 0 : capOf.apply(type));
            int amount = Math.min(Math.max(0, one.getInt(K_AMOUNT)), cap);
            if (amount > 0) {
                known.put(type, amount);
            }
        }
        return new Decoded<>(known, unknown);
    }

    /** 空的解码结果，供新建存档使用。 */
    public static <K> Decoded<K> empty() {
        return new Decoded<>(Collections.emptyMap(), Collections.emptyList());
    }

    /**
     * 在候选类型里按存档里的名字反查。
     *
     * <p>用 {@code ==} 之外的逐项比对而非 {@code Map}，因为注入进来的类型在
     * {@code HashMap} 构造时可能还不存在（枚举的 {@code <clinit>} 时序），
     * 每次调用现查现比可以避开这个坑。
     *
     * @return 找不到时返回 {@code null}，调用方按「注入者缺席」处理
     */
    public static <K> K byName(List<K> candidates, Function<K, String> nameOf, String rawName) {
        if (rawName == null || rawName.isEmpty() || candidates == null) {
            return null;
        }
        for (K candidate : candidates) {
            if (nameOf.apply(candidate).equals(rawName)) {
                return candidate;
            }
        }
        return null;
    }
}
