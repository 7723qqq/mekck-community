package cn.ism.mekck.machine;

import cn.ism.mekck.RedstoneControl;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧版自研工厂方块实体的 NBT → Mek 原生存档格式的<b>一次性</b>迁移。
 *
 * <h3>为什么必须一次性迁完，而不是留「兼容读旧键」的分支</h3>
 * {@code mekck:<tier>_cutting_factory} 的方块注册名没变、变的是 {@code BlockEntityType}
 * 的实现类，于是旧存档里的内容<b>没有任何代码会去读</b>——方块还在，里面空了。
 * 修法有两条：把旧 BE 的 {@code load} 留着当兼容分支，或者把旧 NBT 整体翻译成新格式。
 * 本类选后者。理由是兼容分支会让此后每一个「存档读出来是空的」类 bug 都要查两遍格式，
 * 而两套格式的键还会互相撞名（本类里就有三对同名不同型的键，见下）。
 *
 * <h3>⚠️ 新旧格式<b>键名相同、类型不同</b>的三处（照抄键名必炸）</h3>
 * 全部由 {@code javap -p -c} 在
 * {@code mekanism-268560-6018299_mapped_official_1.20.1.jar} 上核实：
 * <pre>
 *   旧 Items       : CompoundTag { Size:int, Items:ListTag&lt;{Slot:int, id, Count, McCount?}&gt; }
 *   新 Items       : ListTag    &lt;{Slot:byte, Item:CompoundTag, SizeOverride:int?}&gt;
 *   旧 Energy      : int
 *   新 EnergyContainers : ListTag &lt;{Container:byte, stored:String, energyUsage:String?}&gt;
 *   旧 RedstoneControl : int
 *   新 controlType     : int（Mek 的 IRedstoneControl.RedstoneControl.ordinal()）
 *   旧 RedstonePowered : boolean
 *   新 redstone        : boolean
 * </pre>
 * 其中 {@code Items} 那一对最危险：同名，若迁移时忘了改类型，
 * {@code TileEntityMekanism.load} 里的 {@code tag.getList("Items", 10)} 会对着
 * CompoundTag 取到空 ListTag，<b>不报错、静默丢光槽位</b>。本类因此总是
 * 用 {@code out.put("Items", newListTag)} 整体覆盖，而不是「先 remove 再 put」。
 *
 * <h3>侧配（SideConfig）两边确实不同构</h3>
 * 旧的是本模组自己的 {@code byte[6]}：下标 = {@link Direction#ordinal()}，
 * 值 = {@link SideMode#ordinal()}。Mek 的 {@code TileComponentConfig} 走
 * {@code tag.put("config" + transmissionType.ordinal(), { "side" + relativeSide.ordinal()
 * -> dataType.ordinal() })}（实测常量池里的三个拼接模板分别是
 * {@code eject} / {@code side} / {@code config}）。
 * 两边的下标基准都不同（{@link Direction} 六面 vs
 * {@link RelativeSide} 的 FRONT/LEFT/RIGHT/BACK/TOP/BOTTOM），因此必须逐面翻译，
 * 不能直接复制字节。翻译走 {@link RelativeSide#getDirection(Direction)} 反查，
 * 不自己写「FRONT 就是 facing」之类的映射表——那正是会悄悄写错的一个面。
 *
 * <h3>本类刻意不碰的两个旧键</h3>
 * {@code AutoDistribute} / {@code AutoSelectedItems} 属于 AE2 自动化层
 * （阶段 2 Task 4.6 的范围）。迁移时原样留在标签里，Task 4.6 接上对应存储后即可直接读到，
 * 期间本类不读也不写它们。
 */
public final class MekCkLegacyMachineNbt {

    private static final Logger LOGGER = LoggerFactory.getLogger(MekCkLegacyMachineNbt.class);

    // ── 旧键（逐字取自 blockentity/CuttingMachineFactoryBlockEntity.saveAdditional）──

    public static final String LEGACY_ITEMS = "Items";
    public static final String LEGACY_ENERGY = "Energy";
    public static final String LEGACY_PROGRESS = "Progress";
    public static final String LEGACY_SIDE_CONFIG = "SideConfig";
    public static final String LEGACY_REDSTONE_CONTROL = "RedstoneControl";
    public static final String LEGACY_REDSTONE_POWERED = "RedstonePowered";

    /**
     * 旧 {@code Items} 复合标签<b>内部</b>的列表键。
     *
     * <p>与外层的 {@link #LEGACY_ITEMS} 同名不是笔误：Forge 的
     * {@code ItemStackHandler.serializeNBT()} 写出的就是
     * {@code {Items: [...], Size: n}}，本模组的 {@code BigStackItemHandler}
     * 原样沿用了这个形状。</p>
     */
    public static final String LEGACY_ITEMS_LIST = "Items";

    /** 旧升级计数器的键；下标即 {@link #legacyUpgradeCounts} 返回数组的下标。 */
    public static final String[] LEGACY_TRACKER_KEYS = {
            "SpeedUpgradeTracker", "EnergyUpgradeTracker", "StackUpgradeTracker", "CreativeUpgradeTracker"
    };

    /** 旧升级计数器内部的计数键（{@code MekCkUpgradeTracker.save()} 写的）。 */
    public static final String LEGACY_TRACKER_INSTALLED = "Installed";

    // ── 新键（与 TileEntityMekanism / MekCkMachineTile 写出的逐字一致）──

    /** Mek 槽位列表的下标键：{@code DataHandlerUtils.getTagByType} 对 {@code IInventorySlot} 返回它。 */
    public static final String NATIVE_SLOT_INDEX = "Slot";
    /** Mek 能量列表的下标键：同一个 getTagByType 对 {@code IEnergyContainer} 返回的是这个。 */
    public static final String NATIVE_CONTAINER_INDEX = "Container";
    /** Mek 侧配子标签。 */
    public static final String NATIVE_CONFIG = "componentConfig";
    /** Mek 升级子标签。 */
    public static final String NATIVE_UPGRADE = "componentUpgrade";
    /**
     * {@code componentUpgrade} 子标签<b>内部</b>的槽位列表键。
     *
     * <p>与条目里的下标键 {@link #NATIVE_SLOT_INDEX} 不是一回事：列表键是
     * {@code Items}（实测 {@code TileComponentUpgrade.write} 的
     * {@code newTag.put("Items", DataHandlerUtils.writeContainers(getSlots()))}），
     * 而每一条里的下标键才是 {@code Slot}。两者写反了会读出一堆下标为 0 的卡。</p>
     */
    public static final String NATIVE_UPGRADE_SLOT_LIST = "Items";
    /** Mek 红石控制模式（实测 {@code NBTUtils.writeEnum(nbt, "controlType", controlType)}）。 */
    public static final String NATIVE_CONTROL_TYPE = "controlType";
    /** Mek 的「有红石信号」标志。 */
    public static final String NATIVE_REDSTONE = "redstone";
    /** Mek 能量容器列表（实测 {@code SubstanceType.ENERGY} 的 containerTag 就是它）。 */
    public static final String NATIVE_ENERGY = "EnergyContainers";

    /** {@code BasicEnergyContainer.serializeNBT} 写的存量键，值是 {@link FloatingLong#toString()}。 */
    public static final String NATIVE_ENERGY_STORED = "stored";
    /** {@code BasicInventorySlot.serializeNBT} 在数量超过单叠上限时补写的权威数量。 */
    public static final String NATIVE_SLOT_SIZE_OVERRIDE = "SizeOverride";
    /** {@code BasicInventorySlot.serializeNBT} 里的物品子标签。 */
    public static final String NATIVE_SLOT_ITEM = "Item";

    /** 升级卡槽只用得到两个（{@code TileComponentUpgrade.getSlots()} = 输入槽 + 输出槽）。 */
    private static final int UPGRADE_CARD_SLOTS = 2;

    private MekCkLegacyMachineNbt() {
    }

    /**
     * 该存档是否仍是旧格式。
     *
     * <p>判据是<b>新格式的版本标记是否存在</b>，而不是「有没有旧键」：方块刚放下时
     * 存档是空的（只有 {@code id}/{@code x}/{@code y}/{@code z}），两种判据都判「旧」，
     * 但只有「有没有旧键」会在旧存档被别的模组塞进一个同名的 {@code Progress} 键时判错。
     */
    public static boolean isLegacy(CompoundTag tag) {
        return tag == null || !tag.contains(MekCkMachineTile.TAG_NATIVE_VERSION);
    }

    /**
     * 把一份旧格式标签整体翻译成 Mek 原生格式。
     *
     * <p><b>返回新标签，不改传入的那个</b>。Mek 的读档链路上
     * {@code BlockEntity.load} 会从同一个标签里取 {@code ForgeData}（Forge 自定义数据）、
     * {@code ForgeCaps}，所以不能就地删键，只能复制一份再改。</p>
     *
     * @param legacy          旧格式标签，允许为 {@code null}
     * @param facing          本机朝向，决定 {@link RelativeSide} 与六个世界面的对应关系
     * @param inputSlotCount  本档位的输入槽数；输出槽数与之相同（{@code MekCkMachineTile.getInitialInventory}）
     * @return 只含原生键的全新标签
     */
    public static CompoundTag migrate(CompoundTag legacy, Direction facing, int inputSlotCount) {
        if (legacy == null) {
            return new CompoundTag();
        }
        CompoundTag out = legacy.copy();
        migrateSlots(legacy, out, Math.max(0, inputSlotCount));
        migrateEnergy(legacy, out);
        migrateRedstone(legacy, out);
        migrateSideConfig(legacy, out, facing);
        migrateProgress(legacy, out);
        dropLegacyKeys(out);
        return out;
    }

    // ── 槽位 ────────────────────────────────────────────────────────────

    /**
     * 旧 {@code Items} → 新 {@code Items}。
     *
     * <h3>下标不是恒等映射，必须按下标逐段判断</h3>
     * 旧处理器（{@code BigStackItemHandler}，槽数 {@code 2N + 3 或 2N + 4 + 1}）的排布是：
     * <pre>
     *   [0, N)        输入
     *   [N, 2N)       输出
     *   [2N, 2N+3/4)  升级卡槽（速度 / 能量 / 存储 / 创造），有没有存储卡取决于档位
     *   Size-1        能量物品（能源）槽
     * </pre>
     * 新 tile 的排布（{@code MekCkMachineTile.getInitialInventory} 往 builder 里 addSlot 的顺序）是：
     * <pre>
     *   [0, N)        输入
     *   [N, 2N)       输出
     *   2N            能量槽
     *   另有独立的 componentUpgrade.Items（升级卡输入槽 + 输出槽）
     * </pre>
     * 所以输入/输出那一段恰好是恒等的，能源槽从「最后一个」挪到了 {@code 2N}，
     * 升级卡则搬进了另一个组件。{@code hasStackUpgrade} 决定旧处理器到底是
     * {@code 2N+4} 还是 {@code 2N+3} 个升级卡槽——而这个信息只能从存档的
     * {@code Size} 反推，代码里拿不到，所以能源槽一律取 {@code Size - 1}。
     *
     * <h3>⚠️ 已知上限：{@code 2N + 1 > 127} 的档位会丢尾段（不是本类引入的）</h3>
     * Mek 的槽位下标是 <b>byte</b>：{@code DataHandlerUtils.writeContents} 写的是
     * {@code compound.putByte(tagName, (byte) i)}，{@code readContents} 读的
     * {@code getByte} 遇负值直接跳过。本模组最高档 {@code SINGULARITY} 有 81 并行，
     * 即 {@code 2N = 162}，于是第 128 号之后的 35 个槽位在<b>新格式里根本寻址不到</b>
     * （旧格式的 {@code Slot} 是 int，所以旧存档存得下）。
     * 这条限制属于基类的持久化设计（Task 1/4 引入），不是迁移造成的；但迁移会让
     * 旧存档里那部分槽位的内容一并消失，因此在 {@code MekCkMachineTile} 换掉
     * 槽位持久化方式之前，81 并行机器的产物与能源物品都保不住。
     * 断言见 {@code TestLegacyMachineNbtMigration#veryHighParallelTiersLoseTheirTail...}。
     */
    private static void migrateSlots(CompoundTag legacy, CompoundTag out, int inputSlotCount) {
        if (!legacy.contains(LEGACY_ITEMS, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag old = legacy.getCompound(LEGACY_ITEMS);
        ListTag oldList = old.getList(LEGACY_ITEMS_LIST, Tag.TAG_COMPOUND);
        int machineSlots = inputSlotCount * 2;
        int powerSlot = Math.max(0, old.getInt("Size")) - 1;

        ListTag items = new ListTag();
        ListTag upgradeCards = new ListTag();
        int dropped = 0;
        for (int i = 0; i < oldList.size(); i++) {
            CompoundTag entry = oldList.getCompound(i);
            // 用 BigStackItemHandler.readStack 而不是 ItemStack.of：旧格式的权威数量写在
            // int 型 McCount 键上，原版 Count 只是 byte，超过 127 的堆叠靠它才活得下来。
            ItemStack stack = BigStackItemHandler.readStack(entry);
            if (stack.isEmpty()) {
                continue;
            }
            int slot = entry.getInt(NATIVE_SLOT_INDEX);
            if (slot >= 0 && slot < machineSlots) {
                items.add(nativeSlot((byte) slot, stack));
            } else if (slot == powerSlot) {
                items.add(nativeSlot((byte) machineSlots, stack));
            } else if (slot >= machineSlots && slot < powerSlot && upgradeCards.size() < UPGRADE_CARD_SLOTS) {
                upgradeCards.add(nativeSlot((byte) upgradeCards.size(), stack));
            } else {
                dropped++;
                LOGGER.warn("切菜工厂旧存档的槽位 {}（共 {} 槽）里的 {} 无处安放，已丢弃。",
                        slot, powerSlot + 1, stack);
            }
        }
        // 同名不同型：必须整体覆盖，否则读档侧会拿到旧格式的 CompoundTag 并静默丢光槽位。
        out.put(LEGACY_ITEMS, items);
        if (!upgradeCards.isEmpty()) {
            CompoundTag component = new CompoundTag();
            component.put(NATIVE_UPGRADE_SLOT_LIST, upgradeCards);
            out.put(NATIVE_UPGRADE, component);
        }
    }

    /**
     * 造一个 Mek 槽位列表条目。
     *
     * <p>形状取自 {@code DataHandlerUtils.writeContents}（它往
     * {@code serializeNBT()} 的结果上补一个 {@code putByte(tagName, (byte) i)}）与
     * {@code BasicInventorySlot.serializeNBT}：
     * <pre>
     *   { Item: &lt;ItemStack&gt;, SizeOverride: &lt;int, 仅当数量 &gt; 单叠上限&gt;, Slot: &lt;byte&gt; }
     * </pre>
     * {@code SizeOverride} 不能省：{@code ItemStack.save} 把数量写成 byte，
     * 不带这个键的话超过单叠上限的产物读回来会变成 1 个（这正是本模组
     * {@code BigStackItemHandler} 当初要自己持久化的同一个坑，Mek 用
     * {@code SizeOverride} 解决了同一件事）。
     */
    private static CompoundTag nativeSlot(byte index, ItemStack stack) {
        CompoundTag entry = new CompoundTag();
        CompoundTag item = new CompoundTag();
        stack.save(item);
        entry.put(NATIVE_SLOT_ITEM, item);
        if (stack.getCount() > stack.getMaxStackSize()) {
            entry.putInt(NATIVE_SLOT_SIZE_OVERRIDE, stack.getCount());
        }
        entry.putByte(NATIVE_SLOT_INDEX, index);
        return entry;
    }

    // ── 能量 ────────────────────────────────────────────────────────────

    /**
     * 旧 {@code Energy}（int）→ 新 {@code EnergyContainers} 列表。
     *
     * <p>能量本来不需要本类操心：{@code TileEntityMekanism.saveAdditional} 会遍历
     * {@code EnumUtils.SUBSTANCES}，其中 {@code ENERGY} 的 containerTag 就是
     * {@code EnergyContainers}，由 {@code DataHandlerUtils.writeContainers} 写出。
     * 但那只管<b>新</b>格式的存取——旧格式存的是根上一个叫 {@code Energy} 的 int，
     * 没有任何代码会把它翻译成容器列表，所以必须在这里补。</p>
     */
    private static void migrateEnergy(CompoundTag legacy, CompoundTag out) {
        ListTag containers = new ListTag();
        if (legacy.contains(LEGACY_ENERGY, Tag.TAG_INT)) {
            int stored = legacy.getInt(LEGACY_ENERGY);
            if (stored > 0) {
                CompoundTag entry = new CompoundTag();
                // 存量写成 FloatingLong 的字符串形式：序列化侧用的就是
                // FloatingLong.toString()，用它才能保证往返一致（"1000" 与 "1000 TF"
                // 在 FloatingLong 里等价，但本类不赌解析器的宽容度）。
                entry.putString(NATIVE_ENERGY_STORED, FloatingLong.create(stored).toString());
                entry.putByte(NATIVE_CONTAINER_INDEX, (byte) 0);
                containers.add(entry);
            }
        }
        out.put(NATIVE_ENERGY, containers);
    }

    // ── 红石 ────────────────────────────────────────────────────────────

    private static void migrateRedstone(CompoundTag legacy, CompoundTag out) {
        if (legacy.contains(LEGACY_REDSTONE_CONTROL, Tag.TAG_INT)) {
            out.putInt(NATIVE_CONTROL_TYPE,
                    toNativeRedstoneControl(legacy.getInt(LEGACY_REDSTONE_CONTROL)));
        }
        if (legacy.contains(LEGACY_REDSTONE_POWERED, Tag.TAG_BYTE)) {
            out.putBoolean(NATIVE_REDSTONE, legacy.getBoolean(LEGACY_REDSTONE_POWERED));
        }
    }

    /**
     * 旧红石模式序号 → Mek 红石模式序号。
     *
     * <p><b>不按 {@code ordinal()} 直传，按枚举名走</b>。两个枚举当前恰好同序
     * （{@code DISABLED, HIGH, LOW, PULSE}，实测
     * {@code javap mekanism.common.tile.interfaces.IRedstoneControl$RedstoneControl}
     * 只有这 4 个常量），但「恰好同序」不是契约：Mek 往里插一个常量，
     * 直传就会把玩家的 HIGH 静默变成 LOW。按名走则要么对上，要么落回 DISABLED。</p>
     */
    public static int toNativeRedstoneControl(int legacyOrdinal) {
        RedstoneControl[] legacy = RedstoneControl.values();
        if (legacyOrdinal < 0 || legacyOrdinal >= legacy.length) {
            return IRedstoneControl.RedstoneControl.DISABLED.ordinal();
        }
        String name = legacy[legacyOrdinal].name();
        for (IRedstoneControl.RedstoneControl candidate : IRedstoneControl.RedstoneControl.values()) {
            if (candidate.name().equals(name)) {
                return candidate.ordinal();
            }
        }
        return IRedstoneControl.RedstoneControl.DISABLED.ordinal();
    }

    // ── 侧配 ────────────────────────────────────────────────────────────

    private static void migrateSideConfig(CompoundTag legacy, CompoundTag out, Direction facing) {
        if (!legacy.contains(LEGACY_SIDE_CONFIG, Tag.TAG_BYTE_ARRAY)) {
            return;
        }
        byte[] legacyBytes = legacy.getByteArray(LEGACY_SIDE_CONFIG);
        Direction face = facing == null ? Direction.NORTH : facing;
        CompoundTag sides = new CompoundTag();
        int lostStorage = 0;
        for (RelativeSide side : RelativeSide.values()) {
            // 用 Mek 自己的 getDirection 反查世界面，而不是自己写「FRONT = facing」
            // 之类的映射表：Mek 对 TOP/BOTTOM 在朝上朝下时另有特判
            // （实测 RelativeSide.getDirection 的 tableswitch：TOP 遇 DOWN/UP 返回 EAST、
            // 否则 getClockWise；BOTTOM 遇 DOWN/UP 返回 WEST、否则 getCounterClockWise），
            // 自己抄一遍迟早抄错那一个面。
            Direction worldSide = side.getDirection(face);
            int ordinal = worldSide.ordinal() < legacyBytes.length ? legacyBytes[worldSide.ordinal()] : -1;
            SideMode mode = legacySideMode(ordinal);
            if (mode == SideMode.PULL_INPUT_STORAGE) {
                lostStorage++;
            }
            sides.putInt("side" + side.ordinal(), toNativeDataType(mode).ordinal());
        }
        CompoundTag config = new CompoundTag();
        config.put("config" + TransmissionType.ITEM.ordinal(), sides);
        out.put(NATIVE_CONFIG, config);
        if (lostStorage > 0) {
            LOGGER.warn("切菜工厂旧存档有 {} 个面配成了「抽进存储区」，而本机的物品侧配"
                    + "没有存储区概念，这些面已落成「无」。请在 GUI 里重新配置。", lostStorage);
        }
    }

    /** 旧 {@code byte[6]} 里的序号 → 旧侧配模式；越界返回 {@code null}。 */
    public static SideMode legacySideMode(int ordinal) {
        SideMode[] values = SideMode.values();
        return ordinal < 0 || ordinal >= values.length ? null : values[ordinal];
    }

    /**
     * 旧侧配模式 → Mek 的 {@link DataType}。
     *
     * <p><b>{@code PULL_INPUT_STORAGE} 没有对应项，这是实测结论而不是省略</b>：
     * {@code TileComponentConfig.setupItemIOConfig} 只注册了 5 个 DataType 的
     * {@code addSlotInfo} —— {@code INPUT} / {@code OUTPUT} / {@code INPUT_OUTPUT}
     * （输入+输出合一的槽信息）与 {@code ENERGY}（那个能源槽），
     * 于是 {@code ConfigInfo.getSupportedDataTypes()} = {NONE} ∪ 上面 4 个。
     * 没有 {@code EXTRA}，硬塞进去会得到一个 GUI 不认识、自动化也不认的模式。
     * 落成 {@code NONE} 是唯一安全解：那个面从此不吐不收，而不是伪装成「能出料」。</p>
     */
    public static DataType toNativeDataType(SideMode mode) {
        if (mode == null) {
            return DataType.NONE;
        }
        return switch (mode) {
            case PULL_INPUT -> DataType.INPUT;
            case PUSH_OUTPUT -> DataType.OUTPUT;
            case NONE, PULL_INPUT_STORAGE -> DataType.NONE;
        };
    }

    // ── 进度 ────────────────────────────────────────────────────────────

    /**
     * 旧 {@code Progress}（批次内已走的 tick 数）→ 新 {@code MekCkWorkProgress}。
     *
     * <p>两者单位相同：旧实现每 tick {@code progress++} 直到 {@code effectiveProcessTime}，
     * 新实现的 {@code workProgress} 也是按 tick 累加到 {@code ticksPerWorkCycle}。
     * 因此原样搬运即可复现「读档后第一 tick 就跑完这一批」这一与旧版一致的行为，
     * 这里不做任何上限裁剪。</p>
     */
    private static void migrateProgress(CompoundTag legacy, CompoundTag out) {
        if (legacy.contains(LEGACY_PROGRESS, Tag.TAG_INT)) {
            out.putInt(MekCkMachineTile.TAG_WORK_PROGRESS, Math.max(0, legacy.getInt(LEGACY_PROGRESS)));
        }
    }

    // ── 升级计数 ────────────────────────────────────────────────────────

    /**
     * 读旧 4 个升级计数器的 {@code Installed}。
     *
     * <p>返回下标顺序与 {@link #LEGACY_TRACKER_KEYS} 一致：速度 / 能量 / 存储 / 创造。
     * 缺失的键按 0 处理——旧存档由更早的版本写出时可能还没有某个计数器。</p>
     *
     * <p><b>不返回 {@code Map<Upgrade, Integer>}</b>： MekCK 自注入的两个
     * {@code Upgrade} 常量靠 Mixin 赋值，普通 JUnit 里
     * {@code MekCkUpgradeRefs.storage()} 会抛「未注入」。把「读存档」与
     * 「拿到 Upgrade 常量」拆成两步，两者才都测得到。</p>
     */
    public static int[] legacyUpgradeCounts(CompoundTag legacy) {
        int[] counts = new int[LEGACY_TRACKER_KEYS.length];
        if (legacy == null) {
            return counts;
        }
        for (int i = 0; i < LEGACY_TRACKER_KEYS.length; i++) {
            counts[i] = Math.max(0, legacy.getCompound(LEGACY_TRACKER_KEYS[i]).getInt(LEGACY_TRACKER_INSTALLED));
        }
        return counts;
    }

    /**
     * 从「当前已装」补到「旧存档要求」还需要装多少张。
     *
     * <p><b>这就是迁移幂等的那一格</b>：用「补差额」而不是「直接装 N 张」，
     * 同一份旧存档喂进 {@code load} 两次，第二次的差额是 0，不会翻倍。</p>
     *
     * @param current 升级组件里当前已装的张数
     * @param wanted  旧存档里的张数（调用方已按 {@code MekCkUpgradeTypes.capOf} 裁过）
     * @param cap     本机该类型的安装上限
     */
    public static int upgradesToInstall(int current, int wanted, int cap) {
        return Math.max(0, Math.min(wanted, cap) - current);
    }

    // ── 收尾 ────────────────────────────────────────────────────────────

    /**
     * 删掉所有已翻译过的旧键。
     *
     * <p>留下任何一个都意味着「两套格式并存」：将来 {@code save} 写回的是新格式，
     * 某个下次的 bug 却能同时被旧键影响。{@code Items} 不在此列——它已被
     * {@link #migrateSlots} 整体覆盖成新格式的 ListTag。</p>
     *
     * <p>{@code AutoDistribute} / {@code AutoSelectedItems} 刻意保留：
     * 它们属于 Task 4.6 的 AE2 层，届时新 tile 会有对应存储；现在删掉等于丢数据。</p>
     */
    private static void dropLegacyKeys(CompoundTag out) {
        out.remove(LEGACY_ENERGY);
        out.remove(LEGACY_PROGRESS);
        out.remove(LEGACY_SIDE_CONFIG);
        out.remove(LEGACY_REDSTONE_CONTROL);
        out.remove(LEGACY_REDSTONE_POWERED);
        for (String key : LEGACY_TRACKER_KEYS) {
            out.remove(key);
        }
    }

    /** 迁移诊断用：把新格式里「某面是什么模式」反查出来，供 GUI 排查与测试断言。 */
    public static Map<RelativeSide, DataType> nativeSideModes(CompoundTag nativeTag) {
        Map<RelativeSide, DataType> result = new LinkedHashMap<>();
        CompoundTag sides = nativeTag.getCompound(NATIVE_CONFIG)
                .getCompound("config" + TransmissionType.ITEM.ordinal());
        for (RelativeSide side : RelativeSide.values()) {
            String key = "side" + side.ordinal();
            result.put(side, sides.contains(key, Tag.TAG_INT)
                    ? DataType.byIndexStatic(sides.getInt(key))
                    : null);
        }
        return result;
    }
}
