package cn.ism.mekck.machine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.INBTSerializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MekCK 机器槽位的 <b>int 下标</b>存档 —— 绕开 Mek 用 byte 存取槽位下标造成 128 号以上不可寻址。
 *
 * <h3>为什么必须自己写一份</h3>
 * Mek 的槽位序列化 {@code mekanism.api.DataHandlerUtils}（注意包名不是
 * {@code mekanism.common.util}）用 {@code byte} 存槽位下标，
 * {@code javap -c} 在 {@code Mekanism-1.20.1-10.4.6.20_mapped_official_1.20.1.jar} 上实测：
 * <pre>
 *   writeContents:
 *     50: aload_1        // 槽位列表
 *     51: iload_3        // 下标 i
 *     52: i2b            // ← 这里截断成 byte
 *     53: invokevirtual  CompoundTag.putByte:(Ljava/lang/String;B)V
 *   readContents:
 *     27: aload_0 ... 30: invokevirtual CompoundTag.getByte:(Ljava/lang/String;)B
 *     35: iload 6
 *     37: iflt 64        // ← 负值直接跳过该条，不报错
 *     40: iload 6 ... 43: if_icmpge 64   // ← 还要求 i &lt; 槽位数
 * </pre>
 * {@code byte} 上限 127，所以 {@code 2N ≤ 127} 即 {@code N ≤ 63} 并行才安全。
 * 本模组三档高并行里 {@code CRYSTAL_MATRIX(36)} 与 {@code NEBULA(49)} 都没问题，
 * <b>{@code SINGULARITY}(81 并行 ⇒ 2N = 162) 每次存读档都会静默丢掉
 * 第 128~161 号那 34 个输出槽，以及第 162 号的能量槽</b>。
 * 这不是迁移引入的——新建的 81 并行机器照样丢。
 *
 * <h3>为什么不用第 5 个 Mixin 去改 {@code DataHandlerUtils}</h3>
 * {@code writeContents}/{@code readContents} 是 {@code static}，签名只接
 * {@code List<? extends INBTSerializable<CompoundTag>>} 与 {@code String tagName}，
 * <b>没有任何 tile 上下文</b>，无法把改写收窄到 MekCK 的机器上。强行重定向会改掉整个
 * 整合包里所有 Mekanism 机器（含 Mek 自带的）的存档格式，制造出
 * 「装了 MekCK 存的档，没装 MekCK 打开时 Mek 自己的机器读不出来」这种更糟的问题。
 * 因此本类走基类自存：Mek 原生那份（byte）照写不误，只是对 MekCK 的机器<b>不再是权威来源</b>。
 *
 * <h3>存档形状</h3>
 * <pre>
 *   MekCkSlots: CompoundTag {
 *     MekCkSlotCount: int                    // 写出时的槽位数，用来发现档位变化导致的下标错位
 *     MekCkSlotItems: ListTag&lt;{
 *         MekCkSlotIndex: int                 // 本类自有的 int 下标
 *         Item: &lt;ItemStack&gt;                // 与 BasicInventorySlot.serializeNBT 同形
 *         SizeOverride: int?                  // 数量超单叠上限时才写
 *     }&gt;
 *   }
 * </pre>
 * 三个键名刻意与 Mek 的 {@code "Items"} / {@code "componentUpgrade"} /
 * {@code "EnergyContainers"} 以及条目内的 {@code "Slot"} / {@code "Container"} 全都不同名，
 * 任何一侧误读对方的数据都会因为「键不存在」而退化成空列表，而不是读到半个错位的数据。
 * 条目里多出来的 {@code MekCkSlotIndex} 键对 {@code deserializeNBT} 是惰性的——
 * 它只读 {@code Item} 与 {@code SizeOverride}（实测 {@code BasicInventorySlot.deserializeNBT}
 * 偏移 4~13 判 {@code contains("Item", 10)}、偏移 27~42 走 {@code NBTUtils.setIntIfPresent}）。
 *
 * <p>条目内那两个 {@code Item} / {@code SizeOverride} <b>刻意与 Mek 同名</b>：
 * 它们不是索引键，而是槽位负载本身的形状，由 Mek 的读侧按固定名字取。
 * 改名的唯一后果是 Mek 的 {@code deserializeNBT} 认不出来、槽位全空。
 * 隔离靠的是外层 {@code MekCkSlots} 这一层命名空间，不是条目内部。</p>
 */
public final class MekCkSlotNbt {

    private static final Logger LOGGER = LoggerFactory.getLogger(MekCkSlotNbt.class);

    /** 顶层键。刻意不同于 Mek 的 {@code "Items"}。 */
    public static final String TAG_SLOTS = "MekCkSlots";
    /** 写入时的槽位总数。 */
    static final String ENTRY_COUNT = "MekCkSlotCount";
    /** 槽位列表键。刻意不同于 Mek 的 {@code "Items"}。 */
    static final String ENTRY_LIST = "MekCkSlotItems";
    /** 条目内的下标键。刻意不同于 Mek 的 {@code "Slot"} / {@code "Container"}。 */
    static final String ENTRY_INDEX = "MekCkSlotIndex";

    /**
     * 槽位自身的物品子标签键。
     *
     * <p>由 {@code BasicInventorySlot.serializeNBT} 偏移 16 处的字符串常量决定，
     * 读侧是 {@code deserializeNBT} 偏移 5 处的 {@code contains("Item", 10)}。
     * {@link MekCkLegacyMachineNbt} 的 {@code NATIVE_SLOT_ITEM} 指的是同一个字面量。</p>
     */
    public static final String NATIVE_SLOT_ITEM = "Item";
    /**
     * 超叠数量的权威值键。
     *
     * <p>{@code BasicInventorySlot.serializeNBT} 偏移 31~52：数量超过单叠上限时补写它。
     * 没有它，原版 {@code ItemStack} 的 byte 型 Count 会把 5000 个面包读成 1 个。</p>
     */
    public static final String NATIVE_SLOT_SIZE_OVERRIDE = "SizeOverride";

    private MekCkSlotNbt() {
    }

    /**
     * 把一组槽位按 int 下标写成一份独立于 Mek 的存档。
     *
     * <p><b>空槽不写</b>，与 {@code DataHandlerUtils.writeContents} 偏移 40~45 的
     * {@code if (nbt.isEmpty()) continue} 同口径：否则一台空机器会写 163 条空标签，
     * 而空标签里的下标在读侧毫无意义。</p>
     *
     * <p>入参用 {@code INBTSerializable<CompoundTag>} 而不是 {@code IInventorySlot}，
     * 是为了与 Mek 自己的 {@code DataHandlerUtils} 签名一致；调用方传的
     * {@code IInventorySlot} 天然满足（实测 {@code javap}：
     * {@code interface mekanism.api.inventory.IInventorySlot extends
     * net.minecraftforge.common.util.INBTSerializable<net.minecraft.nbt.CompoundTag>}）。
     * 返回的 {@code serializeNBT()} 静态类型是 {@link Tag}，需要强转回
     * {@link CompoundTag}—— Mek 自己也是这么强转的（{@code writeContents} 偏移 35 的
     * {@code checkcast}），这里沿用同一口径。</p>
     */
    public static CompoundTag write(List<? extends INBTSerializable<CompoundTag>> slots) {
        ListTag items = new ListTag();
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag entry = (CompoundTag) slots.get(i).serializeNBT();
            if (entry.isEmpty()) {
                continue;
            }
            entry.putInt(ENTRY_INDEX, i);
            items.add(entry);
        }
        return block(slots.size(), items);
    }

    /**
     * 由「槽位总数 + 条目列表」组装出 {@link #write} 同形的存档块。
     *
     * <p>单独暴露给旧格式迁移：它手上只有 {@link ItemStack}、没有槽位对象，
     * 但产出的块必须与存档路径逐字同形，否则读侧会整组落空。</p>
     *
     * @param slotCount 本机槽位总数（写档时的值），写进 {@link #ENTRY_COUNT}
     * @param entries   已带 {@link #ENTRY_INDEX} 的条目列表
     */
    public static CompoundTag block(int slotCount, ListTag entries) {
        CompoundTag block = new CompoundTag();
        block.putInt(ENTRY_COUNT, slotCount);
        block.put(ENTRY_LIST, entries);
        return block;
    }

    /**
     * 从专属键读回 int 下标的槽位内容，<b>覆盖</b>传入的槽位组当前的内容。
     *
     * <p><b>先清空再灌，而不是只灌存档里有的那些</b>。理由：专属键存在就意味着
     * 「本机的槽位权威来源是它」，存档里没有的槽位就是<b>真的空</b>。
     * 只灌不清的话，玩家「取走物品 → 存 → 读」会把上一份内容原样吐回来；
     * 同一份标签连读两次的结果也会不一样。</p>
     *
     * <p>调用方<b>必须在 {@code super.load} 之后</b>调用本方法：Mek 的读档会按 byte 下标
     * 往槽位里灌一遍（本方法正是要覆盖它），顺序反了等于没写。详见
     * {@link MekCkMachineTile#load} 与阶段 2 Task 4.5 里
     * {@code TestLegacyMachineNbtMigration#legacyUpgradesAreInstalledAfterSuperLoad}
     * 用的同一条源码不变量手法。</p>
     *
     * @return 专属键是否存在。<b>{@code false} = 只有 Mek 那份 byte 存档</b>，
     *         调用方应当什么都不做（退化到修复前的行为，不报错、不覆盖）
     */
    public static boolean read(CompoundTag root, List<? extends INBTSerializable<CompoundTag>> slots) {
        if (root == null || !root.contains(TAG_SLOTS, Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag block = root.getCompound(TAG_SLOTS);
        ListTag items = block.getList(ENTRY_LIST, Tag.TAG_COMPOUND);
        int recorded = block.getInt(ENTRY_COUNT);
        if (recorded != slots.size()) {
            // 记一条而不是直接放弃：档位变了不该让机器整体归零，能读回几个是几个。
            LOGGER.warn("存档记录的槽位数为 {}，本机实际有 {} 个（方块等级可能被改过），"
                    + "越界的下标会被跳过。", recorded, slots.size());
        }
        CompoundTag empty = new CompoundTag();
        for (INBTSerializable<CompoundTag> slot : slots) {
            slot.deserializeNBT(empty);
        }
        int skipped = 0;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            // contains 检查而不是直接 getInt：getInt 对缺键返回 0，
            // 一条被外力改坏的条目会静默盖掉 0 号槽。
            if (!entry.contains(ENTRY_INDEX, Tag.TAG_INT)) {
                skipped++;
                continue;
            }
            int index = entry.getInt(ENTRY_INDEX);
            if (index < 0 || index >= slots.size()) {
                skipped++;
                continue;
            }
            slots.get(index).deserializeNBT(entry);
        }
        if (skipped > 0) {
            LOGGER.warn("专属槽位存档里有 {} 条下标缺失或越界的条目，已跳过。", skipped);
        }
        return true;
    }

    /**
     * 造一条 int 下标的槽位条目 —— 给手上只有 {@link ItemStack} 的旧格式迁移用。
     *
     * <p>形状与 {@link #write} 产出的条目逐字相同：{@code Item} / 可选
     * {@code SizeOverride} / {@link #ENTRY_INDEX}，因此喂给
     * {@code IInventorySlot.deserializeNBT} 的读路径与存档路径完全一致。</p>
     */
    public static CompoundTag entry(int index, ItemStack stack) {
        CompoundTag entry = new CompoundTag();
        CompoundTag item = new CompoundTag();
        stack.save(item);
        entry.put(NATIVE_SLOT_ITEM, item);
        if (stack.getCount() > stack.getMaxStackSize()) {
            entry.putInt(NATIVE_SLOT_SIZE_OVERRIDE, stack.getCount());
        }
        entry.putInt(ENTRY_INDEX, index);
        return entry;
    }
}
