package cn.ism.mekck.machine;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.util.BigStackItemHandler;
import mekanism.api.DataHandlerUtils;
import mekanism.api.RelativeSide;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.FloatingLong;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.IRedstoneControl;
import net.minecraft.core.Direction;
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
import java.util.EnumSet;
import java.util.List;
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
 * （{@code MekCkMachineTile} 的构造链要 {@code CuttingMachineFactoryBlock} 与 Mek 的
 * {@code Attribute} 注册表，见 {@code TestCuttingBatchPacking} 的同类说明），
 * 但迁移逻辑本身必须能测，否则「玩家旧存档有没有被读回来」就只能靠实机。
 *
 * <p>{@link ItemStack} 需要注册表，用 {@link #boot()} 那两步把它拉起来。</p>
 *
 * <h3>旧格式样本是<b>字面量</b>，不是从旧类里读的</h3>
 * 旧的 {@code CuttingMachineFactoryBlockEntity} 已在阶段 2 Task 5 删除，
 * 所以这里既不 import 它也不读它的源码——否则测试会跟着待删/已删代码一起走。
 * 下面的形状是按它当年 {@code save} 写出的结构手写的：
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

    /**
     * 与 {@code MekCkMachineTile.getInitialInventory} 同排布的空机器槽位组：
     * {@code [0,N) 输入 + [N,2N) 输出 + [2N] 能量槽}。
     *
     * <p>用真 {@code InputInventorySlot}/{@code OutputInventorySlot} 而不是自造的实现，
     * 这样 {@code DataHandlerUtils} 走的是它自己的序列化路径、槽位读回的是 Mek 自己的
     * {@code deserializeNBT}——测的是真行为而不是复刻。能量槽用
     * {@link InputInventorySlot} 顶替：真 {@code EnergyInventorySlot} 需要一个
     * {@code IEnergyContainer}，与本测试要验的「下标能不能寻址」无关。</p>
     */
    private static List<IInventorySlot> mekckTestSlots(int parallelCount) {
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

    private static void assertHolds(List<IInventorySlot> slots, int index, Item item, int count) {
        ItemStack actual = slots.get(index).getStack();
        assertFalse("第 " + index + " 号槽读回来是空的", actual.isEmpty());
        assertEquals("第 " + index + " 号槽的物品", item, actual.getItem());
        assertEquals("第 " + index + " 号槽的数量", count, actual.getCount());
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

    // ── 高并行档的尾段槽位：已修复（阶段 2 Task 4.8）────────────────────

    /**
     * <b>已修复。</b>81 并行档的第 128 号与第 161 号槽，存 → 读之后仍然存在。
     *
     * <p><b>缺陷是什么</b>：Mek 用 <b>byte</b> 存取槽位下标。
     * {@code javap -c mekanism.api.DataHandlerUtils} 实测
     * （注意包名是 {@code mekanism.api}，不是 {@code mekanism.common.util}）：
     * <pre>
     *   writeContents:  50: iload_3  51: iload_3  52: i2b
     *                   53: invokevirtual CompoundTag.putByte:(Ljava/lang/String;B)V
     *   readContents:   30: invokevirtual CompoundTag.getByte:(Ljava/lang/String;)B
     *                   35: iload 6   36: iflt ... 37: iflt 64   // 负值整条跳过，不报错
     * </pre>
     * {@code byte} 上限 127 ⇒ {@code 2N ≤ 127} 即 {@code N ≤ 63} 并行才安全。
     * {@code CRYSTAL_MATRIX(36)}、{@code NEBULA(49)} 没问题，
     * {@code SINGULARITY(81 ⇒ 2N = 162)} 每次存读档丢第 128~161 号共 34 个输出槽与能源槽。
     * 这不是迁移引入的——新建的 81 并行机器照样丢。</p>
     *
     * <p><b>怎么修的</b>：{@code MekCkMachineTile} 在 {@code saveAdditional} 里
     * {@code super.saveAdditional} <b>之后</b>，把同一组槽位按 <b>int 下标</b>写进
     * {@link MekCkSlotNbt#TAG_SLOTS} 专属键；{@code load} 里 {@code super.load} <b>之后</b>
     * 再从专属键读回来覆盖。迁移侧（{@code MekCkLegacyMachineNbt.migrateSlots}）
     * 也一并写那一份，所以旧档在迁移那一刻就不丢。详见 {@link MekCkSlotNbt} 的类注释。</p>
     *
     * <p><b>为什么不走第 5 个 Mixin</b>：{@code DataHandlerUtils.writeContents/readContents}
     * 是 {@code static}，签名只接 {@code List<? extends INBTSerializable<CompoundTag>>}
     * 与槽位下标字符串，<b>没有任何 tile 上下文</b>，无法把改写收窄到 MekCK 的机器上。
     * 重定向会改掉整个整合包里所有 Mekanism 机器（含 Mek 自带的）的存档格式，
     * 制造出「装了 MekCK 存的档，没装 MekCK 打开时 Mek 自己的机器读不出来」这种更糟的问题。
     * 阶段 1 的 4 个 Mixin 名额因此不动。</p>
     *
     * <p>本测试刻意跑<b>整条链路</b>（旧档 → 迁移 → {@code super.load} 的 byte 读 →
     * 专属键覆盖），而不只测迁移：迁移只是这条链的一环，中间任何一环把顺序做反，
     * 症状都与修复前完全一致——静默丢槽位，没有任何日志。</p>
     */
    @Test
    public void veryHighParallelTiersKeepTheirTailAfterTheIntIndexedSlotFix() {
        int processes = 81;                  // CuttingMachineFactoryTier.SINGULARITY.processes
        int oldSize = 2 * processes + 4 + 1; // 旧处理器：2N + 4 张升级卡 + 1 能源槽
        int powerSlot = 2 * processes;       // 新排布里能源槽在第 2N 号
        CompoundTag root = legacy(oldSize,
                // 128 与 161 在旧排布里都属于输出区（[N, 2N) = [81, 162)）
                legacyItem(128, new ItemStack(Items.BREAD, 1), 1),
                legacyItem(161, new ItemStack(Items.DIAMOND, 1), 1),
                legacyItem(oldSize - 1, new ItemStack(Items.BUCKET), 1));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, processes);

        // 第一步：super.load 会做的 byte 读——第 128 / 161 号与第 162 号的能量槽在这里必然丢掉。
        List<IInventorySlot> slots = mekckTestSlots(processes);
        DataHandlerUtils.readContents(slots, migrated.getList("Items", Tag.TAG_COMPOUND), "Slot");
        assertTrue("第 128 号在 Mek 的 byte 路径上确实寻址不到（这正是专属键存在的理由）",
                slots.get(128).isEmpty());
        assertTrue("第 161 号同理", slots.get(161).isEmpty());
        assertTrue("第 162 号（能量槽）同样越过 127，一并丢掉", slots.get(powerSlot).isEmpty());

        // 第二步：MekCkMachineTile.load 紧接着做的事——int 下标那份覆盖回来。
        assertTrue("迁移出来的档必须带专属键", MekCkSlotNbt.read(migrated, slots));

        assertHolds(slots, 128, Items.BREAD, 1);
        assertHolds(slots, 161, Items.DIAMOND, 1);
        assertHolds(slots, powerSlot, Items.BUCKET, 1);
    }

    /**
     * 反面对照：迁移产出的 {@code Items} <b>仍然</b>是 byte 下标，第 128 号就是 -128。
     *
     * <p>这不是在钉 MekCK 的 bug（MekCK 早就不靠这条路径了），而是钉住
     * 「为什么必须另写一份」这个前提：Mek 的格式本身改不动（它服务于整个整合包），
     * 专属键是唯一的出路。哪天 Mek 自己换成 int 下标，这里会先炸出来，
     * 届时专属键可以退休；反之若有人以为 {@code Items} 本来就够用，这条立刻否掉那个误解。
     * 见 {@code TestMekCkSlotNbt#mekNativeByteFormatAloneCannotAddressSlot128}。</p>
     */
    @Test
    public void theMigratedItemsListIsStillMekByteFormatAndCannotReachSlot128() {
        int processes = 81;
        int oldSize = 2 * processes + 4 + 1;
        CompoundTag root = legacy(oldSize, legacyItem(128, new ItemStack(Items.BREAD, 1), 1));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, processes);

        ListTag items = migrated.getList("Items", Tag.TAG_COMPOUND);
        assertEquals("一条", 1, items.size());
        assertEquals("第 128 号在 byte 下标下就是 -128", -128, items.getCompound(0).getByte("Slot"));
    }

    /** 低端位（< 128）在两份存档里必须完全一致——「行为不变」的回归保护。 */
    @Test
    public void lowSlotsAreIdenticalInBothStorages() {
        int processes = 81;
        int oldSize = 2 * processes + 4 + 1;
        CompoundTag root = legacy(oldSize,
                legacyItem(0, new ItemStack(Items.CARROT, 4), 4),
                legacyItem(100, new ItemStack(Items.WHEAT, 9), 9),
                legacyItem(127, new ItemStack(Items.EMERALD, 2), 2));
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(root, Direction.NORTH, processes);

        List<IInventorySlot> viaMek = mekckTestSlots(processes);
        DataHandlerUtils.readContents(viaMek, migrated.getList("Items", Tag.TAG_COMPOUND), "Slot");
        List<IInventorySlot> viaMekCk = mekckTestSlots(processes);
        assertTrue(MekCkSlotNbt.read(migrated, viaMekCk));

        for (int index : new int[]{0, 100, 127}) {
            // 比的是持久化出来的那份 NBT 而不是 ItemStack：后者的 equals 链
            // （isSameItemSameTags → areCapsCompatible）在裸 JVM 里不可靠。
            assertEquals("第 " + index + " 号槽两份存档持久化出来的内容必须逐键一致",
                    viaMek.get(index).serializeNBT(), viaMekCk.get(index).serializeNBT());
        }
        assertHolds(viaMekCk, 0, Items.CARROT, 4);
        assertHolds(viaMekCk, 100, Items.WHEAT, 9);
        assertHolds(viaMekCk, 127, Items.EMERALD, 2);
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

    /**
     * <b>凡是调用旧存档迁移器的 tile，都必须写出原生格式的版本标记。</b>
     *
     * <h3>缺陷形态（本测试诞生于一次真实踩坑）</h3>
     * {@link MekCkLegacyMachineNbt#isLegacy} 的判据是「存档里<b>没有</b>
     * {@code MekCkNative} 键」。于是一台 tile 只要「读侧会迁移」而「写侧不落这个键」，
     * 它的存档就<b>永远是旧格式</b> —— 每次区块加载都白跑一遍迁移器，
     * 并可能反复刷出「记录的槽位数与实际不符」的 WARN。
     *
     * <p>迁移器本身是幂等的（旧键与原生键名不重叠），所以这<b>不损坏数据</b>，
     * 也就没有任何功能性症状能把它暴露出来 —— 属于只靠人眼很难发现的类别。
     * 阶段 3 电力研磨机样板迁移时实测踩到：{@code GrindingMachineTile} 的
     * {@code load} 调了 {@code migrate(...)}，{@code saveAdditional} 却漏写这个键。</p>
     *
     * <p>判据读源文本：扫出所有调用 {@code MekCkLegacyMachineNbt.migrate(} 的文件，
     * 每一个都必须在同文件里写出 {@code TAG_NATIVE_VERSION}。</p>
     */
    /**
     * 「往存档里落原生版本标记」的调用形态。
     *
     * <p>接受两种写法：引用常量（{@code putInt(TAG_NATIVE_VERSION, …)}）或直接写字面量
     * （{@code putInt("MekCkNative", …)}）。<b>不接受「只是声明了常量」或「注释里提过」</b>
     * —— 那两种都在变异测试里放过真实缺陷。</p>
     */
    private static final java.util.regex.Pattern WRITES_NATIVE_MARK = java.util.regex.Pattern.compile(
            "put\\w*\\(\\s*(?:TAG_NATIVE_VERSION|\"MekCkNative\")\\s*,");

    @Test
    public void everyTileThatMigratesLegacySavesMustAlsoWriteTheNativeMark() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        Path root = Path.of("src/main/java/cn/ism/mekck");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // ⚠️ 必须剥掉注释再判：本断言第一版直接用 Files.readString，
                // 于是「javadoc 里解释了 TAG_NATIVE_VERSION 是什么」被当成了「代码写了它」——
                // 变异测试（把 tag.putInt(TAG_NATIVE_VERSION, …) 整行删掉）实测**照旧全绿**。
                // 这正是本仓反复记录的那条教训：豁免/命中判据不能用全文子串匹配。
                String src = cn.ism.mekck.TestSourceText.read(file.toString());
                if (!src.contains("MekCkLegacyMachineNbt.migrate(")) {
                    continue;
                }
                scanned++;
                // ⚠️ 判据必须是「**写出**这个标记」，不能是「出现过这个名字」：
                // 本断言第二版用 src.contains("TAG_NATIVE_VERSION")，而**常量声明那一行**
                // 就含这个名字 —— 把 putInt(...) 整行删掉，它照旧全绿。变异测试第二次抓到。
                // 所以这里要求出现「往 tag 里落这个键」的调用形态。
                boolean writesMark = WRITES_NATIVE_MARK.matcher(src).find();
                if (!writesMark) {
                    offenders.add(file.toString());
                }
            }
        }
        assertTrue("一台调用迁移器的 tile 都没扫到，判据已失效（扫描面变了？）", scanned >= 2);
        assertEquals("这些文件读了旧存档（调 MekCkLegacyMachineNbt.migrate）却从不写原生版本标记"
                        + " —— 它们会被 isLegacy 永远判成旧格式，每次读档都白跑一趟迁移：\n  ",
                List.of(), offenders);
    }
}
