package cn.ism.mekck.upgrade;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * 钉住「为什么不能用 Mekanism 自己的升级序列化」——把索引回绕的<b>算术</b>钉成可执行断言。
 *
 * <p>机理来自 {@code javap} 实测 {@code mekanism.api.Upgrade}（jar：
 * {@code mekanism-268560-6018299_mapped_official_1.20.1.jar}）：
 * <pre>
 *   写  getTag(int amount)            → tag.putInt("type", ordinal())
 *   读  buildMap(CompoundTag)         → byIndexStatic(c.getInt("type"))
 *   byIndexStatic(int index)          → MathUtils.getByIndexMod(UPGRADES, index)
 *   MathUtils.getByIndexMod(a, i)     → a[i &lt; 0 ? floorMod(i, a.length) : i % a.length]
 * </pre>
 * 即：索引越界<b>取模回绕，不抛异常、不打日志</b>。
 *
 * <p>本测试只验证这段算术，<b>不加载 {@code Upgrade}</b>——它的静态初始化链
 * （{@code Upgrade → EnumColor → DyeColor → ItemTags → Registries}）在裸 JVM 里必然抛
 * {@code ExceptionInInitializerError}，而 {@code Bootstrap.bootStrap()} 同样失败。
 * 「这段算术确实接在 {@code Upgrade} 的持久化路径上」由 {@code javap} 证据固定，
 * 由 GameTest 在游戏内做端到端确认。
 *
 * <p>若哪天有人认为「自己写 codec 多余，直接用 {@code Upgrade.saveMap/buildMap}」，
 * 这个测试就是他们的反驳依据。
 */
public class TestUpgradeIndexWraparoundArithmetic {

    /** 复刻 {@code MathUtils.getByIndexMod} 的正数分支。 */
    private static <T> T getByIndexMod(List<T> array, int index) {
        return array.get(index < 0 ? Math.floorMod(index, array.size()) : index % array.size());
    }

    @Test
    public void outOfRangeIndexWrapsInsteadOfThrowing() {
        List<String> sevenNative = List.of("speed", "energy", "filter", "gas", "muffling", "anchor", "stone_generator");

        // 索引 7 在长度 7 的数组上：回绕到第 0 个，不是 ArrayIndexOutOfBounds
        assertSame("speed", getByIndexMod(sevenNative, 7));
        assertSame("filter", getByIndexMod(sevenNative, 9));
        assertSame("stone_generator", getByIndexMod(sevenNative, 13));
    }

    @Test
    public void negativeIndexUsesFloorMod() {
        List<String> sevenNative = List.of("speed", "energy", "filter", "gas", "muffling", "anchor", "stone_generator");
        assertSame("stone_generator", getByIndexMod(sevenNative, -1));
        assertSame("anchor", getByIndexMod(sevenNative, -2));
    }

    @Test
    public void shrinkingArrayMisattributesPersistedUpgrade() {
        // 玩家存档时：Mek 原生 7 个 + Mek Extras 注入 STACK(ordinal=7) + MekCK 注入 STORAGE(ordinal=8)
        List<String> before = List.of(
                "speed", "energy", "filter", "gas", "muffling", "anchor", "stone_generator",
                "stack", "storage");
        int persistedIndex = before.indexOf("storage");   // 8
        assertEquals(8, persistedIndex);
        assertEquals("storage", getByIndexMod(before, persistedIndex));

        // 玩家卸载 Mek Extras 后，同一份存档的索引 8 落在长度 7 的原生数组上
        List<String> afterRemoval = List.of(
                "speed", "energy", "filter", "gas", "muffling", "anchor", "stone_generator");
        // 8 % 7 = 1 → "energy"。玩家的存储卡数量变成了能量升级数量，无任何报错
        assertEquals("energy", getByIndexMod(afterRemoval, persistedIndex));
    }

    @Test
    public void nameKeyedLookupIsImmuneToArrayShrinkage() {
        // 对照：名字键只按字符串比对，数组怎么变都不影响
        List<String> afterRemoval = List.of(
                "speed", "energy", "filter", "gas", "muffling", "anchor", "stone_generator");
        assertEquals(null, MekCkUpgradeCodec.byName(afterRemoval, s -> s, "storage"));

        // 玩家装回 Mek Extras + MekCK 后，原本那项按名字原样恢复
        List<String> restored = List.of(
                "speed", "energy", "filter", "gas", "muffling", "anchor", "stone_generator",
                "stack", "storage");
        assertEquals("storage", MekCkUpgradeCodec.byName(restored, s -> s, "storage"));
    }
}
