package cn.ism.mekck.machine;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.util.BigStackItemHandler;
import mekanism.api.RelativeSide;
import mekanism.api.math.FloatingLong;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.IRedstoneControl;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 旧存档 NBT → Mek 原生格式的迁移测试（阶段 2 Task 4.5）。
 *
 * <h3>为什么测得到</h3>
 * 被测的 {@link MekCkLegacyMachineNbt} 是<b>纯 NBT → NBT 函数</b>：吃一份
 * {@link CompoundTag}、吐一份 {@link CompoundTag}，除了 {@link ItemStack} 之外
 * 不碰任何方块实体状态。这正是它被单独拆出来的原因——真 tile 在裸 JVM 里造不出来
 * （{@code MekCkMachineTile} 的构造链要 {@code MekCkFactoryBlock} 与 Mek 的
 * {@code Attribute} 注册表，见 {@code TestCuttingBatchPacking} 的同类说明），
 * 但迁移逻辑本身必须能测，否则「玩家旧存档有没有被读回来」就只能靠实机。
 *
 * <p>{@link ItemStack} 需要注册表，用 {@link #boot()} 那两步把它拉起来。</p>
 *
 * <h3>旧格式样本是<b>字面量</b>，不是从旧类里读的</h3>
 * 旧 {@code CuttingMachineFactoryBlockEntity} 要到 Task 5 才删，但现在就去 import
 * 它的 {@code save} 写出来的结构，等于让测试跟着待删代码一起走。这里全部按
 * {@code blockentity/CuttingMachineFactoryBlockEntity.java:1125-1207} 的形状手写：
 * <pre>
 *   Items  : CompoundTag { Size:int, Items:ListTag&lt;{Slot:int, id, Count, McCount?}&gt; }
 *   Energy : int
 *   Progress / RedstoneControl : int
 *   SideConfig : byte[6]（下标 = Direction.ordinal()，值 = SideMode.ordinal()）
 *   RedstonePowered / AutoDistribute : boolean
 *   AutoSelectedItems : ListTag&lt;String&gt;
 *   CustomName : String
 *   *UpgradeTracker : CompoundTag { Installed:int }
 * </pre>
 * {@code McCount} 是 {@code BigStackItemHandler} 写的权威数量（int），
 * {@code Count} 只是原版 {@code ItemStack.save()} 的 byte 兼容字段。
 */
public class TestLegacyMachineNbtMigration {

    /** 与 MekCkMachineTile.saveAdditional 写出的版本标记同名；编译期常量，不会触发类加载。 */
    private static final String NATIVE_MARK = MekCkMachineTile.TAG_NATIVE_VERSION;
    private static final String WORK_PROGRESS = MekCkMachineTile.TAG_WORK_PROGRESS;

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    // ── 旧格式样本构造 ───────────────────────────────────────────────────

    /** 旧输入/输出方阵的每边槽数。选 2 是为了让「输入 / 输出 / 升级卡 / 能源」四段都非空。 */
    private static final int N = 2;
    /** 旧处理器槽数：2N 个输入输出 + 3 张升级卡（该档不支持存储卡）+ 1 个能源槽。 */
    private static final int OLD_SIZE = 2 * N + 3 + 1;
    private static final int OLD_POWER_SLOT = OLD_SIZE - 1;

    private static CompoundTag legacyItem(int slot, ItemStack stack, int authoritativeCount) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("Slot", slot);
        CompoundTag item = new CompoundTag();
        stack.save(item);
        for (String key : item.getAllKeys()) {
            entry.put(key, item.get(key).copy());
        }
        if (authoritativeCount >= 0) {
            entry.putInt(BigStackItemHandler.BIG_COUNT_KEY, authoritativeCount);
        }
        return entry;
    }

    private static CompoundTag legacy(int size, CompoundTag... entries) {
        ListTag list = new ListTag();
        for (CompoundTag entry : entries) {
            list.add(entry);
        }
        CompoundTag items = new CompoundTag();
        items.putInt("Size", size);
        items.put("Items", list);
        CompoundTag root = new CompoundTag();
        root.putString("id", "mekck:basic_cutting_factory");
        root.putInt("x", 12);
        root.putInt("y", 64);
        root.putInt("z", -30);
        root.put("Items", items);
        return root;
    }

    private static void tracker(CompoundTag root, String key, int installed) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Installed", installed);
        root.put(key, tag);
    }

    /** 读迁移结果里 Mek 槽位列表的第 index 项；不存在时返回 {@code null}。 */
    private static CompoundTag nativeSlot(CompoundTag migrated, int index) {
        ListTag items = migrated.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            if (entry.getByte("Slot") == index) {
                return entry;
            }
        }
        return null;
    }

    private static ItemStack itemOf(CompoundTag nativeEntry) {
        return ItemStack.of(nativeEntry.getCompound("Item"));
    }

    // ── 格式判据 ────────────────────────────────────────────────────────

    /** 空标签（新放下的机器）走迁移路径，不报错，只产出一个空的能量列表。 */
    @Test
    public void freshTagIsTreatedAsLegacyAndMigratesToNothing() {
        CompoundTag empty = new CompoundTag();
        assertTrue("没有版本标记就该判旧格式", MekCkLegacyMachineNbt.isLegacy(empty));
        assertTrue("null 标签也按旧格式处理", MekCkLegacyMachineNbt.isLegacy(null));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(empty, Direction.NORTH, N);
        assertEquals("不得凭空造出侧配 / 红石 / 进度",
                1, migrated.getAllKeys().size());
        assertEquals(0, migrated.getList(MekCkLegacyMachineNbt.NATIVE_ENERGY, Tag.TAG_COMPOUND).size());
    }

    /** 有 MekCkNative 标记的存档走正常路径，不再迁移。 */
    @Test
    public void nativeMarkMeansNoMigration() {
        CompoundTag nativeTag = MekCkLegacyMachineNbt.migrate(legacy(OLD_SIZE), Direction.NORTH, N);
        nativeTag.putInt(NATIVE_MARK, 1);
        assertFalse("带版本标记的存档不得再被判成旧格式", MekCkLegacyMachineNbt.isLegacy(nativeTag));
    }

    // ── 槽位 ────────────────────────────────────────────────────────────

    /**
     * {@code Items} 键同名不同型：旧的是 CompoundTag、新的是 ListTag。
     * 这一条单独立测试，因为漏掉的后果是<b>无日志的静默归零</b>——
     * {@code getList} 对 CompoundTag 返回空列表，整机槽位看着像「本来就没东西」。
     */
    @Test
    public void itemsKeyIsReplacedByAListTagNotAPatchedCompound() {
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(legacy(OLD_SIZE), Direction.NORTH, N);
        assertEquals("迁移后的 Items 必须是 ListTag", Tag.TAG_LIST, migrated.getTagType("Items"));
        assertFalse("CompoundTag 形态下的 Size 必须一起消失", migrated.contains("Size"));
    }

    @Test
    public void inputAndOutputSlotsKeepTheirIndices() {
        CompoundTag root = legacy(OLD_SIZE,
                legacyItem(0, new ItemStack(Items.CARROT, 12), 12),
                legacyItem(N, new ItemStack(Items.BREAD, 3), 3));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);

        ItemStack input = itemOf(nativeSlot(migrated, 0));
        assertEquals(Items.CARROT, input.getItem());
        assertEquals(12, input.getCount());
        ItemStack output = itemOf(nativeSlot(migrated, N));
        assertEquals("第 N 号槽在旧格式是输出，迁移后仍是输出", Items.BREAD, output.getItem());
        assertEquals(3, output.getCount());
    }

    /**
     * 能源槽从「旧处理器的最后一个」搬到新排布的第 {@code 2N} 号。
     * 这一段不是恒等映射——旧布局是 {@code [2N, 2N+3) 升级卡} + {@code Size-1 能源}。
     */
    @Test
    public void powerSlotMovesFromTheLastOldIndexToSlot2N() {
        CompoundTag root = legacy(OLD_SIZE,
                legacyItem(OLD_POWER_SLOT, new ItemStack(Items.BUCKET), 1));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);
        CompoundTag entry = nativeSlot(migrated, 2 * N);
        assertTrue("能源物品必须落到新排布的第 2N 号槽", entry != null);
        assertEquals(Items.BUCKET, itemOf(entry).getItem());
        assertNull("旧能源槽下标不应再有残留", nativeSlot(migrated, OLD_POWER_SLOT));
    }

    /** 旧升级卡槽搬进 componentUpgrade.Items（输入槽 + 输出槽两格）。 */
    @Test
    public void upgradeCardsMoveIntoTheUpgradeComponent() {
        CompoundTag root = legacy(OLD_SIZE,
                legacyItem(2 * N, new ItemStack(Items.NETHERITE_INGOT, 1), 1),
                legacyItem(2 * N + 1, new ItemStack(Items.DIAMOND, 1), 1));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);

        ListTag cards = migrated.getCompound(MekCkLegacyMachineNbt.NATIVE_UPGRADE)
                .getList(MekCkLegacyMachineNbt.NATIVE_UPGRADE_SLOT_LIST, Tag.TAG_COMPOUND);
        assertEquals("两张卡都要保住", 2, cards.size());
        assertEquals(Items.NETHERITE_INGOT, itemOf(cards.getCompound(0)).getItem());
        assertEquals(Items.DIAMOND, itemOf(cards.getCompound(1)).getItem());
        assertTrue("卡不再占用机器槽位", nativeSlot(migrated, 2 * N) == null);
    }

    /**
     * 超过单叠上限的数量靠 {@code SizeOverride} 承载。
     *
     * <p>原版 {@code ItemStack.save} 把数量写成 byte，不带这个键的话
     * 5000 个面包读回来会变成 1 个——这正是本模组 {@code BigStackItemHandler}
     * 当初要自己写 {@code McCount} 的同一个坑，Mek 用 {@code SizeOverride} 解决。</p>
     */
    @Test
    public void bigStackSurvivesThroughSizeOverride() {
        int big = 5000;
        ItemStack proto = new ItemStack(Items.BREAD, 1);
        CompoundTag root = legacy(OLD_SIZE, legacyItem(0, proto, big));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);

        CompoundTag entry = nativeSlot(migrated, 0);
        assertEquals("超叠数量必须写成 int 型 SizeOverride", big, entry.getInt("SizeOverride"));
        assertTrue("Item 子标签必须存在", entry.contains("Item", Tag.TAG_COMPOUND));
    }

    /** 没超叠的普通数量不写 SizeOverride，避免留下无意义的键。 */
    @Test
    public void normalStacksDoNotWriteSizeOverride() {
        CompoundTag root = legacy(OLD_SIZE, legacyItem(0, new ItemStack(Items.BREAD, 10), 10));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);
        assertFalse(nativeSlot(migrated, 0).contains("SizeOverride"));
    }

    /** 超出新机器槽位数的旧槽位不越界写。 */
    @Test
    public void slotsBeyondTheNewLayoutAreNotWritten() {
        CompoundTag root = legacy(OLD_SIZE,
                legacyItem(2 * N + 2, new ItemStack(Items.EMERALD, 1), 1));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);
        ListTag items = migrated.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            assertTrue("新排布只有 0..2N 三个槽位",
                    items.getCompound(i).getByte("Slot") < 2 * N + 1);
        }
    }

    // ── 能量 ────────────────────────────────────────────────────────────

    @Test
    public void energyMovesIntoTheEnergyContainerList() {
        CompoundTag root = legacy(OLD_SIZE);
        root.putInt("Energy", 12345);
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);

        ListTag containers = migrated.getList(MekCkLegacyMachineNbt.NATIVE_ENERGY, Tag.TAG_COMPOUND);
        assertEquals("应当只有一个能量容器", 1, containers.size());
        CompoundTag entry = containers.getCompound(0);
        assertEquals("能量列表的下标键是 Container 不是 Slot（实测 DataHandlerUtils.getTagByType）",
                0, entry.getByte(MekCkLegacyMachineNbt.NATIVE_CONTAINER_INDEX));
        assertEquals("存量必须能按 FloatingLong 原样解析回来",
                FloatingLong.create(12345),
                FloatingLong.parseFloatingLong(entry.getString("stored")));
    }

    /** 存量为 0 或键缺失时不写容器条目（而不是写一条 0）。 */
    @Test
    public void zeroOrMissingEnergyWritesAnEmptyList() {
        CompoundTag zero = legacy(OLD_SIZE);
        zero.putInt("Energy", 0);
        assertEquals(0, MekCkLegacyMachineNbt.migrate(zero, Direction.NORTH, N)
                .getList(MekCkLegacyMachineNbt.NATIVE_ENERGY, Tag.TAG_COMPOUND).size());
        assertEquals(0, MekCkLegacyMachineNbt.migrate(legacy(OLD_SIZE), Direction.NORTH, N)
                .getList(MekCkLegacyMachineNbt.NATIVE_ENERGY, Tag.TAG_COMPOUND).size());
    }

    // ── 红石 ────────────────────────────────────────────────────────────

    @Test
    public void redstoneControlAndPoweredFlagAreTranslated() {
        CompoundTag root = legacy(OLD_SIZE);
        root.putInt("RedstoneControl", 1);
        root.putBoolean("RedstonePowered", true);
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);

        assertEquals("HIGH 必须落到 Mek 的 HIGH",
                IRedstoneControl.RedstoneControl.HIGH.ordinal(), migrated.getInt("controlType"));
        assertTrue(migrated.getBoolean("redstone"));
    }

    /**
     * 红石模式按<b>枚举名</b>而不是 ordinal 直传。
     *
     * <p>两个枚举当前恰好同序（实测 Mek 侧只有 DISABLED/HIGH/LOW/PULSE 四个常量），
     * 但这不是契约；按名走则「对不上」会落回 DISABLED 而不是静默变成另一种模式。</p>
     */
    @Test
    public void everyLegacyRedstoneModeMapsToTheSameNamedMode() {
        for (cn.ism.mekck.RedstoneControl legacy : cn.ism.mekck.RedstoneControl.values()) {
            int mapped = MekCkLegacyMachineNbt.toNativeRedstoneControl(legacy.ordinal());
            assertEquals(legacy.name(), IRedstoneControl.RedstoneControl.values()[mapped].name());
        }
    }

    @Test
    public void outOfRangeRedstoneOrdinalFallsBackToDisabled() {
        assertEquals(IRedstoneControl.RedstoneControl.DISABLED.ordinal(),
                MekCkLegacyMachineNbt.toNativeRedstoneControl(-1));
        assertEquals(IRedstoneControl.RedstoneControl.DISABLED.ordinal(),
                MekCkLegacyMachineNbt.toNativeRedstoneControl(99));
    }

    // ── 侧配 ────────────────────────────────────────────────────────────

    /**
     * 侧配两边<b>不同构</b>：旧的 6 字节按 {@link Direction#ordinal()} 索引，
     * Mek 的是 {@code config{transmission}.side{relativeSide}} 且键是枚举 ordinal。
     * 这条测试逐面核对，并遍历全部 6 个朝向，验证用的是 Mek 自己的
     * {@link RelativeSide#getDirection(Direction)} 反查而不是硬编码的映射表。
     */
    @Test
    public void sideConfigIsTranslatedPerFaceForEveryFacing() {
        for (Direction facing : Direction.values()) {
            for (SideMode mode : SideMode.values()) {
                byte[] legacyBytes = new byte[6];
                for (Direction dir : Direction.values()) {
                    legacyBytes[dir.ordinal()] = (byte) mode.ordinal();
                }
                CompoundTag root = legacy(OLD_SIZE);
                root.putByteArray("SideConfig", legacyBytes);
                CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, facing, N);

                Map<RelativeSide, DataType> modes = MekCkLegacyMachineNbt.nativeSideModes(migrated);
                assertEquals("六面必须都翻译到", 6, modes.size());
                for (RelativeSide side : RelativeSide.values()) {
                    DataType expected = MekCkLegacyMachineNbt.toNativeDataType(mode);
                    assertEquals("朝向 " + facing + " 的 " + side + " 应当是 " + expected,
                            expected, modes.get(side));
                }
            }
        }
    }

    /**
     * 逐面独立配置时，每个面各自对上——这条能抓住「所有面都用同一个方向」的偷懒实现。
     */
    @Test
    public void eachFaceKeepsItsOwnMode() {
        byte[] legacyBytes = new byte[6];
        legacyBytes[Direction.DOWN.ordinal()] = (byte) SideMode.PUSH_OUTPUT.ordinal();
        legacyBytes[Direction.UP.ordinal()] = (byte) SideMode.PULL_INPUT.ordinal();
        legacyBytes[Direction.NORTH.ordinal()] = (byte) SideMode.NONE.ordinal();
        legacyBytes[Direction.SOUTH.ordinal()] = (byte) SideMode.NONE.ordinal();
        legacyBytes[Direction.WEST.ordinal()] = (byte) SideMode.NONE.ordinal();
        legacyBytes[Direction.EAST.ordinal()] = (byte) SideMode.NONE.ordinal();

        CompoundTag root = legacy(OLD_SIZE);
        root.putByteArray("SideConfig", legacyBytes);
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);
        Map<RelativeSide, DataType> modes = MekCkLegacyMachineNbt.nativeSideModes(migrated);

        assertEquals(DataType.OUTPUT, modes.get(RelativeSide.BOTTOM));
        assertEquals(DataType.INPUT, modes.get(RelativeSide.TOP));
        for (RelativeSide side : EnumSet.complementOf(
                EnumSet.of(RelativeSide.BOTTOM, RelativeSide.TOP))) {
            assertEquals("其余四面应保持无", DataType.NONE, modes.get(side));
        }
    }

    /**
     * {@code PULL_INPUT_STORAGE} 在新格式里<b>没有对应项</b>。
     *
     * <p>实测 {@code TileComponentConfig.setupItemIOConfig} 只注册了
     * INPUT / OUTPUT / INPUT_OUTPUT / ENERGY 四个 addSlotInfo，没有 EXTRA。
     * 硬塞 EXTRA 会得到 GUI 与自动化都不认的模式，所以落成 NONE。</p>
     */
    @Test
    public void pullInputStorageDegradesToNoneBecauseTheNewConfigHasNoStorageFace() {
        assertEquals(DataType.NONE, MekCkLegacyMachineNbt.toNativeDataType(SideMode.PULL_INPUT_STORAGE));
        assertEquals(DataType.INPUT, MekCkLegacyMachineNbt.toNativeDataType(SideMode.PULL_INPUT));
        assertEquals(DataType.OUTPUT, MekCkLegacyMachineNbt.toNativeDataType(SideMode.PUSH_OUTPUT));
        assertEquals(DataType.NONE, MekCkLegacyMachineNbt.toNativeDataType(SideMode.NONE));
        assertEquals("越界序号按无处理", DataType.NONE,
                MekCkLegacyMachineNbt.toNativeDataType(MekCkLegacyMachineNbt.legacySideMode(42)));
    }

    @Test
    public void legacySideModeRejectsOutOfRangeOrdinals() {
        assertNull(MekCkLegacyMachineNbt.legacySideMode(-1));
        assertNull(MekCkLegacyMachineNbt.legacySideMode(SideMode.values().length));
        assertEquals(SideMode.PUSH_OUTPUT, MekCkLegacyMachineNbt.legacySideMode(2));
    }

    /** 侧配子标签必须挂在 ITEM 这一路传输上（键是 transmissionType.ordinal()）。 */
    @Test
    public void sideConfigIsWrittenUnderTheItemTransmissionKey() {
        CompoundTag root = legacy(OLD_SIZE);
        root.putByteArray("SideConfig", new byte[]{1, 1, 1, 1, 1, 1});
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);
        assertTrue(migrated.contains(MekCkLegacyMachineNbt.NATIVE_CONFIG));
        assertTrue("条目键是 config + transmissionType.ordinal()",
                migrated.getCompound(MekCkLegacyMachineNbt.NATIVE_CONFIG)
                        .contains("config" + TransmissionType.ITEM.ordinal()));
        assertFalse("没有 SideConfig 的存档不得凭空造出侧配",
                MekCkLegacyMachineNbt.migrate(legacy(OLD_SIZE), Direction.NORTH, N)
                        .contains(MekCkLegacyMachineNbt.NATIVE_CONFIG));
    }

    // ── 进度 ────────────────────────────────────────────────────────────

    @Test
    public void progressMovesToTheNativeWorkProgressKey() {
        CompoundTag root = legacy(OLD_SIZE);
        root.putInt("Progress", 137);
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);
        assertEquals(137, migrated.getInt(WORK_PROGRESS));
    }

    // ── 升级计数 ────────────────────────────────────────────────────────

    @Test
    public void upgradeCountsAreReadFromAllFourTrackers() {
        CompoundTag root = legacy(OLD_SIZE);
        tracker(root, MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS[0], 3);
        tracker(root, MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS[1], 1);
        tracker(root, MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS[2], 2);
        tracker(root, MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS[3], 0);
        int[] counts = MekCkLegacyMachineNbt.legacyUpgradeCounts(root);
        assertEquals(4, counts.length);
        assertEquals(3, counts[0]);
        assertEquals(1, counts[1]);
        assertEquals(2, counts[2]);
        assertEquals(0, counts[3]);
    }

    /** 缺失的计数器按 0 处理（更早的存档可能还没写某个计数器）。 */
    @Test
    public void missingTrackersReadAsZero() {
        CompoundTag root = legacy(OLD_SIZE);
        tracker(root, MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS[0], 2);
        int[] counts = MekCkLegacyMachineNbt.legacyUpgradeCounts(root);
        assertEquals(2, counts[0]);
        assertEquals(0, counts[1]);
        assertEquals(0, counts[2]);
        assertEquals(0, counts[3]);
        assertEquals(4, MekCkLegacyMachineNbt.legacyUpgradeCounts(null).length);
    }

    /** 负数计数被夹到 0，不给「装 -1 张」这种状态留口子。 */
    @Test
    public void negativeUpgradeCountIsClampedToZero() {
        CompoundTag root = legacy(OLD_SIZE);
        tracker(root, MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS[0], -5);
        assertEquals(0, MekCkLegacyMachineNbt.legacyUpgradeCounts(root)[0]);
    }

    /**
     * 迁移幂等的那一格：补差额，第二次补 0。
     *
     * <p>{@code MekCkMachineTile.installLegacyUpgrades} 只加
     * {@code upgradesToInstall(已装, 旧值, 上限)}，所以同一份旧存档被 load 两次
     * 不会把升级数量翻倍。</p>
     */
    @Test
    public void upgradeDeltaIsConvergentSoASecondLoadAddsNothing() {
        int first = MekCkLegacyMachineNbt.upgradesToInstall(0, 3, 6);
        assertEquals(3, first);
        assertEquals("第二次补 0，否则会翻倍",
                0, MekCkLegacyMachineNbt.upgradesToInstall(0 + first, 3, 6));
        assertEquals("已达上限后不再补", 0, MekCkLegacyMachineNbt.upgradesToInstall(6, 3, 6));
        assertEquals("存档里的越界值被上限裁掉", 6, MekCkLegacyMachineNbt.upgradesToInstall(0, 99, 6));
        assertEquals("当前已装超过存档值时不减", 0, MekCkLegacyMachineNbt.upgradesToInstall(5, 2, 6));
        assertEquals("上限 0 一张都不装", 0, MekCkLegacyMachineNbt.upgradesToInstall(0, 3, 0));
    }

    // ── 收尾：旧键必须消失 ──────────────────────────────────────────────

    /** 翻译完的旧键一个都不能留——留着就是两套格式并存。 */
    @Test
    public void everyTranslatedLegacyKeyIsRemoved() {
        CompoundTag root = legacy(OLD_SIZE,
                legacyItem(0, new ItemStack(Items.CARROT, 1), 1),
                legacyItem(OLD_POWER_SLOT, new ItemStack(Items.BUCKET), 1));
        root.putInt("Energy", 999);
        root.putInt("Progress", 7);
        root.putByteArray("SideConfig", new byte[6]);
        root.putInt("RedstoneControl", 2);
        root.putBoolean("RedstonePowered", false);
        for (String key : MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS) {
            tracker(root, key, 1);
        }
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);

        Set<String> gone = Set.of("Energy", "Progress", "SideConfig", "RedstoneControl", "RedstonePowered");
        for (String key : gone) {
            assertFalse("旧键 " + key + " 必须消失", migrated.contains(key));
        }
        for (String key : MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS) {
            assertFalse("旧键 " + key + " 必须消失", migrated.contains(key));
        }
    }

    /** 旧格式里没碰过的键原样保留，含方块实体的定位键与 Forge 自己的数据。 */
    @Test
    public void untouchedKeysSurviveTheMigration() {
        CompoundTag root = legacy(OLD_SIZE);
        root.putString("CustomName", "{\"text\":\"我的切菜机\"}");
        root.putBoolean("AutoDistribute", true);
        ListTag selected = new ListTag();
        selected.add(net.minecraft.nbt.StringTag.valueOf("minecraft:wheat"));
        root.put("AutoSelectedItems", selected);
        CompoundTag forgeData = new CompoundTag();
        forgeData.putString("marker", "keep-me");
        root.put("ForgeData", forgeData);

        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);
        assertEquals("mekck:basic_cutting_factory", migrated.getString("id"));
        assertEquals(12, migrated.getInt("x"));
        assertEquals(64, migrated.getInt("y"));
        assertEquals(-30, migrated.getInt("z"));
        assertEquals("CustomName 与 Mek 侧同为 Component.Serializer 的 JSON，可原样透传",
                "{\"text\":\"我的切菜机\"}", migrated.getString("CustomName"));
        assertTrue("Task 4.6 的 AE2 数据必须留着", migrated.getBoolean("AutoDistribute"));
        assertEquals(1, migrated.getList("AutoSelectedItems", Tag.TAG_STRING).size());
        assertEquals("keep-me", migrated.getCompound("ForgeData").getString("marker"));
    }

    /** 迁移是纯函数：同一份输入两次迁移得到同一份输出（不含浮点/顺序等不确定性）。 */
    @Test
    public void migrationIsDeterministic() {
        CompoundTag root = legacy(OLD_SIZE,
                legacyItem(0, new ItemStack(Items.CARROT, 4), 4),
                legacyItem(OLD_POWER_SLOT, new ItemStack(Items.BUCKET), 1));
        root.putInt("Energy", 555);
        root.putInt("Progress", 11);
        root.putByteArray("SideConfig", new byte[]{1, 2, 0, 0, 3, 1});
        root.putInt("RedstoneControl", 3);

        CompoundTag first = MekCkLegacyMachineNbt.migrate(root, Direction.EAST, N);
        CompoundTag second = MekCkLegacyMachineNbt.migrate(root, Direction.EAST, N);
        assertEquals("两次迁移必须逐字节一致", first, second);
    }

    /** 迁移不得改写传入的标签——Mek 的读档链路上同一个标签还带着 ForgeCaps 等内容。 */
    @Test
    public void migrationDoesNotMutateItsInput() {
        CompoundTag root = legacy(OLD_SIZE, legacyItem(0, new ItemStack(Items.CARROT, 4), 4));
        root.putInt("Energy", 42);
        root.putByteArray("SideConfig", new byte[]{1, 1, 1, 1, 1, 1});
        CompoundTag before = root.copy();
        MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, N);
        assertEquals("传入的旧标签必须原封不动", before, root);
    }

    // ── 形状常量（编译期就锁死的键名/类型）──────────────────────────────

    /**
     * Mek 侧的两个下标键不一样：物品列表是 {@code Slot}，能量列表是 {@code Container}
     * （实测 {@code DataHandlerUtils.getTagByType} 对 {@code IInventorySlot} 与
     * {@code IEnergyContainer} 分别返回这两个字面量）。写错一个就是「静默读不到」。
     */
    @Test
    public void theTwoMekIndexKeysAreNotInterchangeable() {
        assertEquals("Slot", MekCkLegacyMachineNbt.NATIVE_SLOT_INDEX);
        assertEquals("Container", MekCkLegacyMachineNbt.NATIVE_CONTAINER_INDEX);
        assertFalse(MekCkLegacyMachineNbt.NATIVE_SLOT_INDEX.equals(MekCkLegacyMachineNbt.NATIVE_CONTAINER_INDEX));
    }

    @Test
    public void everyTrackerKeyIsCoveredByTheCountArray() {
        assertEquals(4, MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS.length);
        for (String key : MekCkLegacyMachineNbt.LEGACY_TRACKER_KEYS) {
            assertTrue("旧键清单里不得有空串", key != null && !key.isEmpty());
        }
    }

    // ── 已知限制：Mek 的槽位下标是 byte ─────────────────────────────────

    /**
     * <b>这条不是断言「行为正确」，而是把一个已知的格式上限钉在测试里。</b>
     *
     * <p>{@code DataHandlerUtils.writeContents} 写下标用的是
     * {@code putByte}，{@code readContents} 读的是 {@code getByte} 且遇负值直接跳过。
     * 最高档 {@code SINGULARITY} 有 81 并行 ⇒ {@code 2N = 162}，
     * 于是第 128 号起的槽位在<b>新格式里就寻址不到</b>（旧格式的 {@code Slot} 是 int，
     * 存得下）。迁移会忠实地把这些槽位写成负 byte，于是读回来时那 34 个输出槽
     * 与能源物品都没了。</p>
     *
     * <p>这不是迁移引入的缺陷——新建的 81 并行机器每次存读档都会丢同样的东西。
     * 修法在基类（换掉槽位持久化方式），属于 Task 4.7 / Task 6 的范围。
     * 本测试的作用是：有人调高并行度或换持久化实现时，这里会先炸出来。</p>
     */
    @Test
    public void veryHighParallelTiersLoseTheirTailBecauseMekIndexesSlotsWithAByte() {
        int processes = 81;                  // CuttingMachineFactoryTier.SINGULARITY.processes
        int oldSize = 2 * processes + 4 + 1; // 旧处理器：2N + 4 张升级卡 + 1 能源槽
        CompoundTag root = legacy(oldSize,
                // 128 号在旧排布里是输出区（[N, 2N) = [81, 162)），但已越过 byte 的 127
                legacyItem(128, new ItemStack(Items.BREAD, 1), 1),
                legacyItem(oldSize - 1, new ItemStack(Items.BUCKET), 1));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, processes);

        ListTag items = migrated.getList("Items", Tag.TAG_COMPOUND);
        assertEquals("两件都写出来了", 2, items.size());
        int firstByte = items.getCompound(0).getByte("Slot");
        int secondByte = items.getCompound(1).getByte("Slot");
        assertTrue("第 128 号之后的槽位在 byte 下标下变成负数，读档侧会整条跳过："
                        + "实际拿到 " + firstByte + " / " + secondByte,
                firstByte < 0 || secondByte < 0);
    }

    // ── 源码不变量：一条裸 JVM 测不到、但错了就静默丢升级的顺序约束 ──────

    /**
     * {@code installLegacyUpgrades} 必须在 {@code super.load} <b>之后</b>调用。
     *
     * <p>为什么测不到行为：真 tile 在裸 JVM 里造不出来（见类注释）。
     * 为什么这条值得钉死：{@code TileComponentUpgrade.read} 做的第一件事就是
     * {@code upgrades.clear()}（实测 {@code lambda$read$1} 偏移 0~17：
     * {@code getfield upgrades} → {@code Map.clear()} → {@code putAll(buildMap(tag))}），
     * 而迁移出来的 {@code componentUpgrade} 子标签里只有物理卡槽的 {@code Items}、
     * 没有 {@code upgrades} 列表，于是 {@code buildMap} 返回空表并把刚灌进去的数量清零——
     * <b>不报错</b>，玩家的存储卡/速度卡就此消失。</p>
     *
     * <p>与 {@code TestCuttingRecipeInvariants} 同一手法：读源文本比对结构。</p>
     */
    @Test
    public void legacyUpgradesAreInstalledAfterSuperLoad() throws IOException {
        String source = Files.readString(Path.of("src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java"),
                StandardCharsets.UTF_8);
        int bodyStart = source.indexOf("public void load(CompoundTag tag) {");
        assertTrue("应当找得到 load 方法", bodyStart > 0);
        int bodyEnd = source.indexOf("\n    }", bodyStart);
        String body = source.substring(bodyStart, bodyEnd);

        int superLoad = body.indexOf("super.load(tag);");
        int install = body.indexOf("installLegacyUpgrades(");
        assertTrue("应当有两处调用", superLoad > 0 && install > 0);
        assertTrue("installLegacyUpgrades 必须排在 super.load 之后，否则会被 upgrades.clear() 抹掉",
                install > superLoad);
    }
}
