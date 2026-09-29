package cn.ism.mekck.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * C2 回归测试：升级持久化的「归属判定」，以及它挡住的那条数据丢失链。
 *
 * <h3>背景</h3>
 * {@code MixinTileComponentUpgradePersistence} 的两个 {@code @Redirect} 打在
 * {@code TileComponentUpgrade} <b>类</b>上（不是「只作用于挂了本 Mixin 的 tile」），
 * 所以整合包里每一个 Mek 机器都会走到本模组的实现。若不加「非 MekCK 机器则原样退回」，
 * {@code MekCkUpgradeTypes.capOf(MUFFLING, null)} 返回 0，而
 * {@code MekCkUpgradeCodec.decode} 对「被裁到 0」的条目是<b>丢弃</b>（既不入 known，
 * 也不入 unknownRaw）⇒ 玩家在 Mek 自己机器上的静音/过滤卡读档即清零、回写后永久消失。
 *
 * <h3>覆盖边界（写在类注释里，免得被当成端到端测试）</h3>
 * <ul>
 *   <li><b>覆盖</b>：归属判定（类链比较、中间子类、null、无关类）、基类类名契约、
 *       以及「名字键 codec 作用在非 MekCK 机器上确实会丢条目」这一因果链的编解码两半。</li>
 *   <li><b>不覆盖</b>：mixin 的 {@code @Redirect} 是否真的调用了那个判定——
 *       它需要 {@code TileComponentUpgrade} 与 {@code Upgrade} 的类初始化，
 *       裸 JVM 里必然抛 {@code ExceptionInInitializerError}（见 {@code TestUpgradeCodecRoundTrip}
 *       的同款说明），只能由 GameTest/实机兜底。</li>
 * </ul>
 *
 * <p>本类只用 {@code CompoundTag}/{@code ListTag} 与泛型入口，不触达 {@code Upgrade}，
 * 因此可以在普通 JUnit 里跑。
 */
public class TestUpgradePersistenceOwnership {

    // ── 归属判定：用本地继承链，因为真实基类必须有游戏环境才能加载 ──

    /** 假装这就是 {@code cn.ism.mekck.machine.MekCkMachineTile}。 */
    private static class FakeMekCkTile {
    }

    /** 假装这是阶段 2/3 的家族 tile（真实形态：CookingFactoryTile 等直接派生自基类）。 */
    private static class FakeFactoryTile extends FakeMekCkTile {
    }

    /** 再隔一层：只比直接父类的实现会在这里落空。 */
    private static class FakeSubFactoryTile extends FakeFactoryTile {
    }

    /** 与本模组无关的机器（Mek 自己的机器就是这个形态）。 */
    private static class UnrelatedMekTile {
    }

    @Test
    public void directSubclassOfTheBaseIsOwned() {
        assertTrue(MekCkUpgradeTypes.isMekCkOwnedTile(FakeMekCkTile.class, FakeMekCkTile.class.getName()));
    }

    @Test
    public void intermediateSubclassIsStillOwned() {
        assertTrue(MekCkUpgradeTypes.isMekCkOwnedTile(FakeFactoryTile.class, FakeMekCkTile.class.getName()));
        assertTrue(MekCkUpgradeTypes.isMekCkOwnedTile(FakeSubFactoryTile.class, FakeMekCkTile.class.getName()));
    }

    @Test
    public void unrelatedTileIsNotOwned() {
        assertFalse(MekCkUpgradeTypes.isMekCkOwnedTile(UnrelatedMekTile.class, FakeMekCkTile.class.getName()));
    }

    @Test
    public void nullInputsAreNotOwned() {
        assertFalse(MekCkUpgradeTypes.isMekCkOwnedTile(null));
        assertFalse(MekCkUpgradeTypes.isMekCkOwnedTile(null, "any.base.Class"));
        assertFalse(MekCkUpgradeTypes.isMekCkOwnedTile(UnrelatedMekTile.class, null));
    }

    @Test
    public void baseClassNameContractIsPinned() {
        // 改这个字符串 = 所有 MekCK 机器的升级持久化静默退回 ordinal 编解码，且不会有任何日志。
        // 它与 MekCkMachineTile 类注释里那条「不可改的跨文件契约」是同一件事。
        assertEquals("cn.ism.mekck.machine.MekCkMachineTile", MekCkUpgradeTypes.MEKCK_TILE_CLASS_NAME);
    }

    @Test
    public void ownershipDoesNotDependOnTier() {
        // 真实场景：MekCK 机器取档位失败时 tier 为 null，但机器仍是 MekCK 的，必须继续走名字键。
        // 因此判定入参只能是「类」，不能是 tier —— 这条用「父类名不同但链更长」的形态证明。
        assertFalse(MekCkUpgradeTypes.isMekCkOwnedTile(UnrelatedMekTile.class));
        assertTrue(MekCkUpgradeTypes.isMekCkOwnedTile(FakeSubFactoryTile.class, FakeFactoryTile.class.getName()));
    }

    // ── 因果链：名字键 codec 作用在非 MekCK 机器上 = 条目永久消失 ──

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

    /**
     * 这条断言是<b>「必须有回退」的理由</b>，不是我们想要的行为：
     * 非 MekCK 机器走 {@code capOf(…, null) == 0}，条目被裁到 0 后既不在 known 也不在 unknownRaw，
     * 于是 encode 之后再读就只剩别的条目。
     */
    @Test
    public void mufflingEntryIsLostForeverIfTheNameKeyCodecIsUsedOnANonMekCkTile() {
        CompoundTag stored = listOf(entry("muffling", 3), entry("speed", 2));
        MekCkUpgradeCodec.Decoded<String> decoded = MekCkUpgradeCodec.decode(stored, name -> name,
                name -> "muffling".equals(name) ? 0 : 8);
        assertFalse("muffling 被裁到 0，不该进 known", decoded.known().containsKey("muffling"));
        assertTrue("也不在 unknownRaw 里 ⇒ 回写就没有了", decoded.unknownRaw().isEmpty());

        CompoundTag resaved = MekCkUpgradeCodec.encode(decoded.known(), name -> name, decoded.unknownRaw());
        ListTag out = resaved.getList(MekCkUpgradeCodec.KEY, Tag.TAG_COMPOUND);
        assertEquals("只剩 speed，muffling 永久消失", 1, out.size());
        assertEquals("speed", out.getCompound(0).getString("type"));
    }
}
