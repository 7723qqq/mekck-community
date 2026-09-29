package cn.ism.mekck.machine;

import mekanism.api.DataHandlerUtils;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * MekCK 基类自存 <b>int 下标</b>槽位数据的测试（阶段 2 Task 4.8）。
 *
 * <h3>被测的是什么</h3>
 * {@link MekCkSlotNbt} 是纯 NBT → NBT 的读写两端：吃一组 Mek 的真实
 * {@link IInventorySlot}，吐一份 int 下标的存档；反过来再灌回去。
 * 真正的槽位序列化 {@code serializeNBT}/{@code deserializeNBT} 用的是
 * {@code mekanism.common.inventory.slot.BasicInventorySlot} 的实现，
 * <b>不是本仓库复刻的一份</b>——所以超叠数量（{@code SizeOverride}）这类细节测的是 Mek 自己的行为。
 *
 * <h3>为什么能在裸 JVM 里造出真槽位</h3>
 * {@code IInventorySlot.at(IContentsListener, int, int)} 不需要 {@code Level}：
 * 实测 {@code javap -c mekanism.common.inventory.slot.InputInventorySlot}，
 * {@code at(listener, x, y)} 走的是
 * {@code at(alwaysTrue, listener, x, y) → at(alwaysTrue, alwaysTrue, listener, x, y)}，
 * 两个谓词都是 {@code BasicInventorySlot.alwaysTrue}，即
 * {@code isItemValid} 恒真；而 {@code onContentsChanged()} 只在
 * {@code listener != null} 时回调，传 {@code () -> {}} 即可。
 * {@code BasicInventorySlot} 的 {@code <clinit>} 只建了几个常量谓词对象，不碰注册表。
 * 剩下 {@code ItemStack}/{@code Items} 需要注册表，用 {@link #boot()} 那两步拉起来。
 *
 * <p>真 tile（{@link MekCkMachineTile}）在裸 JVM 里造不出来——它的构造链要
 * {@code CuttingMachineFactoryBlock} 与 Mek 的 {@code Attribute} 注册表。
 * 所以「{@code load} 里覆盖必须排在 {@code super.load} 之后」这条顺序约束
 * 改用与 {@code TestCuttingRecipeInvariants} 相同的读源文本手法钉死，见
 * {@link #mekckSlotReadIsAppliedAfterSuperLoad()}。</p>
 *
 * <h3>槽位排布与真机器一致</h3>
 * {@code MekCkMachineTile.getInitialInventory} 往 builder 里加槽的顺序是
 * {@code [0,N) 输入 → [N,2N) 输出 → [2N] 能量槽}，本测试的 {@link #slots(int)} 照搬。
 * 能量槽用 {@link InputInventorySlot} 顶替：真 {@code EnergyInventorySlot} 需要一个
 * {@code IEnergyContainer}，而它与下标编码无关——本类要验的是「第 162 号这一格能不能寻址」，
 * 不是「第 162 号这一格会不会把能量容器填满」。
 */
public class TestMekCkSlotNbt {

    /** CuttingMachineFactoryTier.SINGULARITY.processes；2N = 162 ⇒ 能量槽在第 162 号。 */
    private static final int SINGULARITY = 81;

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    /** 空机器的槽位组：{@code [0,N) 输入 + [N,2N) 输出 + 1 个能量槽}。 */
    private static List<IInventorySlot> slots(int parallelCount) {
        List<IInventorySlot> all = new ArrayList<>(2 * parallelCount + 1);
        for (int i = 0; i < parallelCount; i++) {
            all.add(InputInventorySlot.at(() -> {
            }, 38 + i * 18, 41));
        }
        for (int i = 0; i < parallelCount; i++) {
            all.add(OutputInventorySlot.at(() -> {
            }, 38 + i * 18, 41));
        }
        all.add(InputInventorySlot.at(() -> {
        }, 7, 13));
        return all;
    }

    private static ItemStack stackAt(List<IInventorySlot> slots, int index) {
        return slots.get(index).getStack();
    }

    /** 断言第 index 号槽里就是这件物品。 */
    private static void assertHolds(List<IInventorySlot> slots, int index, Item item, int count) {
        ItemStack actual = stackAt(slots, index);
        assertFalse("第 " + index + " 号槽读回来是空的", actual.isEmpty());
        assertEquals("第 " + index + " 号槽的物品", item, actual.getItem());
        assertEquals("第 " + index + " 号槽的数量", count, actual.getCount());
    }

    // ── 核心断言：81 并行下的尾段槽位 ────────────────────────────────────

    /**
     * 核心回归：{@code SINGULARITY}（81 并行）的第 128 号与第 161 号槽，
     * 存 → 读之后<b>仍然存在</b>。
     *
     * <p>修复前这两格每次存读档都会消失：Mek 用 {@code putByte} 存下标，
     * 128 存成 {@code -128}，读侧 {@code getByte} 拿到负值直接跳过
     * （实测 {@code javap -c mekanism.api.DataHandlerUtils}：
     * {@code writeContents} 偏移 50~53 {@code iload_3; i2b; putByte}、
     * {@code readContents} 偏移 35~37 {@code iload 6; iflt 64}）。
     * 修复方式见 {@link MekCkSlotNbt} 的类注释：基类在专属键下自存 int 下标，
     * 不走第 5 个 Mixin。</p>
     */
    @Test
    public void singularityTailSlotsSurviveSaveAndLoad() {
        List<IInventorySlot> before = slots(SINGULARITY);
        before.get(128).setStack(new ItemStack(Items.BREAD, 7));
        before.get(161).setStack(new ItemStack(Items.DIAMOND, 1));
        before.get(2 * SINGULARITY).setStack(new ItemStack(Items.BUCKET, 1));

        CompoundTag tag = new CompoundTag();
        tag.put(MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.write(before));

        List<IInventorySlot> after = slots(SINGULARITY);
        assertTrue("专属键存在时 read 必须认领", MekCkSlotNbt.read(tag, after));

        assertHolds(after, 128, Items.BREAD, 7);
        assertHolds(after, 161, Items.DIAMOND, 1);
        assertHolds(after, 2 * SINGULARITY, Items.BUCKET, 1);
    }

    /**
     * 同一份存档读进同一组槽位两次，结果必须完全一样。
     *
     * <p>做法是「先清空再灌」而不是「只灌存档里有的」——否则玩家「取走物品 → 存 → 读」
     * 会把上一份内容原样吐回来，读两次的结果也不一样。</p>
     */
    @Test
    public void readingTheSameSaveTwiceGivesTheSameResult() {
        List<IInventorySlot> before = slots(SINGULARITY);
        before.get(3).setStack(new ItemStack(Items.CARROT, 5));
        before.get(130).setStack(new ItemStack(Items.EMERALD, 2));
        CompoundTag tag = new CompoundTag();
        tag.put(MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.write(before));

        List<IInventorySlot> after = slots(SINGULARITY);
        assertTrue(MekCkSlotNbt.read(tag, after));
        assertTrue("第二次读必须同样被认领", MekCkSlotNbt.read(tag, after));

        assertHolds(after, 3, Items.CARROT, 5);
        assertHolds(after, 130, Items.EMERALD, 2);
        assertEquals("其余槽位必须仍然是空的", 2, countNonEmpty(after));
    }

    /** 存档里没有的槽位 = 真的空：读档后必须被清掉，不能留着上一份存档的残留。 */
    @Test
    public void readClearsSlotsThatAreAbsentFromTheSave() {
        List<IInventorySlot> target = slots(SINGULARITY);
        target.get(5).setStack(new ItemStack(Items.WHEAT, 64));
        target.get(140).setStack(new ItemStack(Items.GOLD_INGOT, 3));

        List<IInventorySlot> empty = slots(SINGULARITY);
        CompoundTag tag = new CompoundTag();
        tag.put(MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.write(empty));
        assertTrue(MekCkSlotNbt.read(tag, target));

        assertEquals("空存档必须把所有槽位清空", 0, countNonEmpty(target));
    }

    // ── 回归保护：低端位行为不变 ─────────────────────────────────────────

    /**
     * 0~127 号槽在两份存档里必须指向同一件物品、同一数量。
     *
     * <p>这一条是「低端位行为不变」的回归保护：MekCK 那份不是要取代 Mek 的存档格式，
     * 只是补上 byte 够不着的那一段。两份在能寻址的区间内必须逐条一致，
     * 否则就是新引入的行为漂移。</p>
     *
     * <p>一致性比的是 {@code serializeNBT()} 而不是 {@code ItemStack}：
     * {@code ItemStack.equals} 走 {@code isSameItemSameTags}，那条链除
     * {@code Objects.equals(tag)} 外还要过 {@code areCapsCompatible}
     * （实测 {@code javap -c net.minecraft.world.item.ItemStack} 的
     * {@code isSameItemSameTags} 偏移 45~50），裸 JVM 里不可靠。
     * 而「两份存档一致」的定义本来就是持久化出来的那份 NBT。</p>
     */
    @Test
    public void slotsBelow128AgreeByteForByteWithMekNativeStorage() {
        List<IInventorySlot> before = slots(SINGULARITY);
        int[] occupied = {0, 1, 42, 80, 126, 127};
        for (int i = 0; i < occupied.length; i++) {
            before.get(occupied[i]).setStack(new ItemStack(Items.BREAD, i + 1));
        }
        CompoundTag mekNative = new CompoundTag();
        mekNative.put("Items", DataHandlerUtils.writeContents(before, "Slot"));
        CompoundTag mekck = new CompoundTag();
        mekck.put(MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.write(before));

        List<IInventorySlot> viaMek = slots(SINGULARITY);
        DataHandlerUtils.readContents(viaMek, mekNative.getList("Items", Tag.TAG_COMPOUND), "Slot");
        List<IInventorySlot> viaMekCk = slots(SINGULARITY);
        assertTrue(MekCkSlotNbt.read(mekck, viaMekCk));

        for (int i = 0; i < occupied.length; i++) {
            int index = occupied[i];
            assertHolds(viaMekCk, index, Items.BREAD, i + 1);
            assertEquals("第 " + index + " 号槽两份存档持久化出来的内容必须逐键一致",
                    viaMek.get(index).serializeNBT(), viaMekCk.get(index).serializeNBT());
        }
    }

    /**
     * 反面对照：光靠 Mek 自己那份 byte 存档，128 号以上<b>必然</b>寻址不到。
     *
     * <p>这不是在钉 MekCK 的 bug（MekCK 已经不依赖这条路径了），而是钉住
     * 「为什么必须有专属键」这个前提。哪天有人把 {@code DataHandlerUtils} 改成 int 了，
     * 这里会先炸出来，届时专属键就可以退休；反之若有人误以为它本来就是好的，
     * 这条会立刻否掉那个误解。</p>
     */
    @Test
    public void mekNativeByteFormatAloneCannotAddressSlot128() {
        List<IInventorySlot> before = slots(SINGULARITY);
        before.get(127).setStack(new ItemStack(Items.BREAD, 1));
        before.get(128).setStack(new ItemStack(Items.DIAMOND, 1));

        CompoundTag mekNative = new CompoundTag();
        mekNative.put("Items", DataHandlerUtils.writeContents(before, "Slot"));
        ListTag items = mekNative.getList("Items", Tag.TAG_COMPOUND);
        assertEquals("Mek 只写非空槽，两条", 2, items.size());
        // writeContents 按槽位下标升序写，所以 127 在前、128 在后
        assertEquals("第 127 号还够得着", 127, items.getCompound(0).getByte("Slot"));
        assertEquals("第 128 号在 byte 下标下就是 -128", -128, items.getCompound(1).getByte("Slot"));

        List<IInventorySlot> after = slots(SINGULARITY);
        DataHandlerUtils.readContents(after, mekNative.getList("Items", Tag.TAG_COMPOUND), "Slot");
        assertHolds(after, 127, Items.BREAD, 1);
        assertTrue("第 128 号被 getByte 读到负值后整条跳过——这正是专属键存在的理由",
                stackAt(after, 128).isEmpty());
    }

    // ── 旧格式回落 ──────────────────────────────────────────────────────

    /**
     * 只有 Mek 那份 byte 存档的档（ MekCK 装之前存的、或本次修复前存的）：
     * {@code read} 必须返回 {@code false} 并且<b>一个字都不动</b>。
     *
     * <p>退化到修复前的行为、不报错，是刻意的：玩家卸载 MekCK 装回旧版、或
     * 装的是别人的低并行档，都不该在读档路径上炸。</p>
     */
    @Test
    public void aSaveWithoutTheMekckKeyIsLeftEntirelyToMek() {
        List<IInventorySlot> before = slots(SINGULARITY);
        before.get(9).setStack(new ItemStack(Items.APPLE, 4));
        CompoundTag onlyMek = new CompoundTag();
        onlyMek.put("Items", DataHandlerUtils.writeContents(before, "Slot"));
        onlyMek.putInt(MekCkMachineTile.TAG_NATIVE_VERSION, 1);

        List<IInventorySlot> after = slots(SINGULARITY);
        after.get(9).setStack(new ItemStack(Items.CARROT, 1));
        assertFalse("没有专属键就不能认领", MekCkSlotNbt.read(onlyMek, after));
        assertHolds(after, 9, Items.CARROT, 1);

        // 该档仍按 Mek 的 byte 路径正常读入（低端位完好）
        List<IInventorySlot> viaMek = slots(SINGULARITY);
        DataHandlerUtils.readContents(viaMek, onlyMek.getList("Items", Tag.TAG_COMPOUND), "Slot");
        assertHolds(viaMek, 9, Items.APPLE, 4);
    }

    // ── 边界与畸形数据 ──────────────────────────────────────────────────

    /**
     * 缺下标键或下标越界的条目必须被跳过，而不是把 0 号槽盖掉。
     *
     * <p>{@code getInt} 对缺键返回 0，直接用就会让一条坏条目静默写进 0 号槽——
     * 与本缺陷同一种病（坏数据不见了，没人知道）。</p>
     */
    @Test
    public void entriesWithoutAUsableIndexAreSkippedInsteadOfHittingSlotZero() {
        List<IInventorySlot> before = slots(SINGULARITY);
        before.get(0).setStack(new ItemStack(Items.CARROT, 1));
        before.get(2).setStack(new ItemStack(Items.CARROT, 2));
        CompoundTag block = new CompoundTag();
        ListTag entries = new ListTag();
        entries.add(MekCkSlotNbt.entry(0, new ItemStack(Items.BREAD, 1)));
        // 缺 MekCkSlotIndex 的条目
        CompoundTag noIndex = new CompoundTag();
        noIndex.put(MekCkSlotNbt.NATIVE_SLOT_ITEM, new ItemStack(Items.DIAMOND).save(new CompoundTag()));
        entries.add(noIndex);
        // 下标越界的条目
        entries.add(MekCkSlotNbt.entry(9999, new ItemStack(Items.EMERALD)));
        // 下标为负的条目
        entries.add(MekCkSlotNbt.entry(-3, new ItemStack(Items.NETHERITE_INGOT)));
        block.putInt(MekCkSlotNbt.ENTRY_COUNT, 2 * SINGULARITY + 1);
        block.put(MekCkSlotNbt.ENTRY_LIST, entries);
        CompoundTag tag = new CompoundTag();
        tag.put(MekCkSlotNbt.TAG_SLOTS, block);

        List<IInventorySlot> after = slots(SINGULARITY);
        assertTrue(MekCkSlotNbt.read(tag, after));
        assertHolds(after, 0, Items.BREAD, 1);
        assertEquals("坏条目一条都不许落到别的槽位上", 1, countNonEmpty(after));
    }

    /**
     * 存档记录的槽位数与本机实际不符时（例如等级被改过）不能抛异常，
     * 仍在范围内的条目要照常读回。
     */
    @Test
    public void aRecordedSlotCountMismatchStillReadsTheEntriesInRange() {
        List<IInventorySlot> before = slots(SINGULARITY);
        before.get(3).setStack(new ItemStack(Items.CARROT, 1));
        CompoundTag tag = new CompoundTag();
        CompoundTag block = MekCkSlotNbt.write(before);
        block.putInt(MekCkSlotNbt.ENTRY_COUNT, 5);
        tag.put(MekCkSlotNbt.TAG_SLOTS, block);

        List<IInventorySlot> after = slots(SINGULARITY);
        assertTrue("数量对不上不等于拒绝读", MekCkSlotNbt.read(tag, after));
        assertHolds(after, 3, Items.CARROT, 1);
    }

    /** 超叠数量靠 Mek 自己的 {@code SizeOverride} 承载。 */
    @Test
    public void stacksLargerThanOneSlotRoundTripThroughSizeOverride() {
        int big = 5000;
        List<IInventorySlot> before = slots(SINGULARITY);
        before.get(133).setStack(new ItemStack(Items.BREAD, big));
        CompoundTag tag = new CompoundTag();
        tag.put(MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.write(before));

        List<IInventorySlot> after = slots(SINGULARITY);
        assertTrue(MekCkSlotNbt.read(tag, after));
        assertHolds(after, 133, Items.BREAD, big);
    }

    /** 空槽不写条目，否则一台空机器会写 163 条空标签。 */
    @Test
    public void emptySlotsAreNotWrittenAtAll() {
        CompoundTag block = MekCkSlotNbt.write(slots(SINGULARITY));
        assertEquals("2N + 1", 2 * SINGULARITY + 1, block.getInt(MekCkSlotNbt.ENTRY_COUNT));
        assertEquals("空机器一条条目都不写", 0,
                block.getList(MekCkSlotNbt.ENTRY_LIST, Tag.TAG_COMPOUND).size());
    }

    /**
     * 专属键名与 Mek 的键名一个都不能重。
     *
     * <p>重了就退化成「两套格式共用一个键」，而本项目最贵的一类 bug 恰好是
     * 同名不同型（见 {@code MekCkLegacyMachineNbt} 的类注释）——那种情况下
     * {@code getList} 对上 {@code CompoundTag} 会静默返回空列表，不报错。</p>
     *
     * <p>注意条目内的 {@code Item} / {@code SizeOverride} <b>不在此列</b>：
     * 那是槽位负载的形状，由 Mek 的 {@code deserializeNBT} 按固定名字取，改名必空。
     * 隔离靠的是外层 {@code MekCkSlots} 命名空间。</p>
     */
    @Test
    public void noMekckKeyCollidesWithAMekKey() {
        String[] ours = {
                MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.ENTRY_COUNT, MekCkSlotNbt.ENTRY_LIST, MekCkSlotNbt.ENTRY_INDEX
        };
        String[] mek = {
                MekCkLegacyMachineNbt.LEGACY_ITEMS, MekCkLegacyMachineNbt.NATIVE_UPGRADE,
                MekCkLegacyMachineNbt.NATIVE_ENERGY, MekCkLegacyMachineNbt.NATIVE_CONFIG,
                MekCkLegacyMachineNbt.NATIVE_SLOT_INDEX, MekCkLegacyMachineNbt.NATIVE_CONTAINER_INDEX,
                MekCkLegacyMachineNbt.NATIVE_UPGRADE_SLOT_LIST, "controlType", "redstone"
        };
        for (String mine : ours) {
            assertNotNull(mine);
            for (String theirs : mek) {
                assertFalse("MekCK 的键 " + mine + " 与 Mek 的键 " + theirs + " 重名了",
                        mine.equals(theirs));
            }
        }
    }

    // ── 源码不变量：覆盖必须排在 super.load 之后 ─────────────────────────

    /**
     * {@code MekCkSlotNbt.read(...)} 必须排在 {@code super.load(tag)} <b>之后</b>。
     *
     * <p>为什么测不到行为：真 tile 在裸 JVM 里造不出来（见类注释）。
     * 为什么这条必须钉死：{@code super.load} 会让 Mek 按 byte 下标把 {@code Items}
     * 灌进槽位——第 128 号起的那些被整条跳过。我们随后要靠 int 下标那份<b>覆盖</b>回来；
     * 顺序反了就等于没写这一层，症状与修复前<b>完全一致</b>：静默丢槽位，没有任何日志。
     *
     * <p>与 {@code TestLegacyMachineNbtMigration#legacyUpgradesAreInstalledAfterSuperLoad}
     * 同一手法、同一理由（{@code TileComponentUpgrade.read} 的第一件事是
     * {@code upgrades.clear()}）。</p>
     */
    @Test
    public void mekckSlotReadIsAppliedAfterSuperLoad() throws IOException {
        String source = Files.readString(Path.of("src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java"),
                StandardCharsets.UTF_8);
        int bodyStart = source.indexOf("public void load(CompoundTag tag) {");
        assertTrue("应当找得到 load 方法", bodyStart > 0);
        int bodyEnd = source.indexOf("\n    }", bodyStart);
        String body = source.substring(bodyStart, bodyEnd);

        int superLoad = body.indexOf("super.load(tag);");
        int slotRead = body.indexOf("MekCkSlotNbt.read(");
        assertTrue("应当有两处调用", superLoad > 0 && slotRead > 0);
        assertTrue("MekCkSlotNbt.read 必须排在 super.load 之后，否则 MekCK 的 int 下标存档被 byte 那份盖掉",
                slotRead > superLoad);
    }

    private static int countNonEmpty(List<IInventorySlot> slots) {
        int count = 0;
        for (IInventorySlot slot : slots) {
            if (!slot.isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
