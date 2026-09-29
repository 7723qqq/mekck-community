package cn.ism.mekck.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link MekCkUpgradeCodec} 的编解码契约（普通 JUnit，无需游戏环境）。
 *
 * <p>用 {@code String} 作类型键：{@code Upgrade} 的静态初始化链
 * （{@code Upgrade → EnumColor → DyeColor → ItemTags → Registries}）在裸 JVM 里必然抛
 * {@code ExceptionInInitializerError}，而 {@code Bootstrap.bootStrap()} 也失败
 * （需要 {@code Util.fetchChoiceType} 与完整注册表）。这是 codec 对类型键泛型化的原因。
 */
public class TestUpgradeCodecRoundTrip {

    /** 模拟「当前存在的类型」：只认这三个名字，其余视为注入者缺席。 */
    private static final List<String> PRESENT = List.of("speed", "energy", "gas");

    private static MekCkUpgradeCodec.Decoded<String> decode(CompoundTag tag) {
        return MekCkUpgradeCodec.decode(tag, name -> PRESENT.contains(name) ? name : null, k -> switch (k) {
            case "speed" -> 8;
            case "energy" -> 8;
            case "gas" -> 4;
            default -> 0;
        });
    }

    private static CompoundTag listOf(CompoundTag... entries) {
        ListTag list = new ListTag();
        for (CompoundTag e : entries) {
            list.add(e);
        }
        CompoundTag tag = new CompoundTag();
        tag.put(MekCkUpgradeCodec.KEY, list);
        return tag;
    }

    private static CompoundTag entry(String name, int amount) {
        CompoundTag c = new CompoundTag();
        c.putString("type", name);
        c.putInt("amount", amount);
        return c;
    }

    @Test
    public void encodeDecodeRoundTripsExactly() {
        Map<String, Integer> original = new LinkedHashMap<>();
        original.put("speed", 3);
        original.put("gas", 1);

        CompoundTag encoded = MekCkUpgradeCodec.encode(original, k -> k, List.of());
        Map<String, Integer> back = decode(encoded).known();

        assertEquals(original, back);
    }

    @Test
    public void nullTagYieldsEmptyNotNull() {
        var decoded = decode(null);
        assertTrue(decoded.known().isEmpty());
        assertTrue(decoded.unknownRaw().isEmpty());
    }

    @Test
    public void unknownNameIsPreservedNotDroppedAndNotMisattributed() {
        // 「stack」是某个当前缺席的注入类型
        CompoundTag stored = listOf(entry("stack", 2), entry("speed", 1));
        var decoded = decode(stored);

        assertEquals(1, decoded.known().size());
        assertEquals(Integer.valueOf(1), decoded.known().get("speed"));
        // 关键：进 unknownRaw 而不是被塞进某个已知类型
        assertEquals(1, decoded.unknownRaw().size());
        assertEquals("stack", decoded.unknownRaw().get(0).getString("type"));
        assertEquals(2, decoded.unknownRaw().get(0).getInt("amount"));
    }

    @Test
    public void unknownRawSurvivesARepencodeCycle() {
        // 玩家卸载注入者：读档 → 存档 → 再读档，未知条目必须仍在
        CompoundTag first = listOf(entry("stack", 2), entry("speed", 1));
        var decoded = decode(first);

        CompoundTag resaved = MekCkUpgradeCodec.encode(decoded.known(), k -> k, decoded.unknownRaw());
        var second = decode(resaved);

        assertEquals(Integer.valueOf(1), second.known().get("speed"));
        assertEquals(1, second.unknownRaw().size());
        assertEquals("stack", second.unknownRaw().get(0).getString("type"));
        assertEquals(2, second.unknownRaw().get(0).getInt("amount"));
    }

    @Test
    public void amountIsClampedByCap() {
        // gas 的 cap 是 4，存档里写了 9
        CompoundTag stored = listOf(entry("gas", 9));
        assertEquals(Integer.valueOf(4), decode(stored).known().get("gas"));
    }

    @Test
    public void nonPositiveAmountsAreDropped() {
        CompoundTag stored = listOf(entry("speed", 0), entry("gas", -3));
        assertTrue(decode(stored).known().isEmpty());
    }

    @Test
    public void encodeSkipsNonPositiveAmounts() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("speed", 0);
        map.put("gas", 2);
        CompoundTag encoded = MekCkUpgradeCodec.encode(map, k -> k, List.of());
        assertEquals(1, encoded.getList(MekCkUpgradeCodec.KEY, 10).size());
    }

    @Test
    public void capOfZeroDropsTheEntry() {
        CompoundTag stored = listOf(entry("muffling", 3));
        var decoded = MekCkUpgradeCodec.decode(stored, name -> PRESENT.contains(name) ? name : null, k -> 0);
        assertTrue(decoded.known().isEmpty());
    }

    @Test
    public void tagWithoutUpgradesKeyDecodesToEmpty() {
        var decoded = decode(new CompoundTag());
        assertTrue(decoded.known().isEmpty());
        assertTrue(decoded.unknownRaw().isEmpty());
    }

    @Test
    public void emptyHelperIsUsable() {
        var empty = MekCkUpgradeCodec.<String>empty();
        assertTrue(empty.known().isEmpty());
        assertTrue(empty.unknownRaw().isEmpty());
    }
}
