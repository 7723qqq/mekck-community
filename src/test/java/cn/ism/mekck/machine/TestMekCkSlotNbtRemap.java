package cn.ism.mekck.machine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MekCkSlotNbt#remapForLayoutChange} 的回归测试。
 *
 * <h3>被测的是什么</h3>
 * 换档（工厂安装器 / 无尽升级组件）走
 * {@code TierInstallerHandler.upgradeMachine}：{@code newTile.load(oldTile.saveWithoutMetadata())}。
 * 并行方阵家族的槽位边界<b>随档位移动</b>，布局恒为
 * {@code [0,in) 输入、[in,in+out) 输出、[in+out] 能量、其余家族专属槽}，
 * 其中 {@code in == out == 并行数}。不重映射就是静默错位：
 * BASIC(3) 的 3 号槽（输出 0）在 ADVANCED(5) 里是<b>输入 3</b>，
 * 玩家会看到「升级后输出槽里的东西跑进输入槽」。
 *
 * <h3>为什么能在裸 JVM 里跑</h3>
 * 本方法只做 NBT → NBT 的搬运，不碰注册表、不碰 {@code ItemStack}，
 * 所以不需要 {@code Bootstrap.bootStrap()}，也不需要造真槽位对象
 * （对比 {@link TestMekCkSlotNbt} 里那些要真 {@code IInventorySlot} 的用例）。
 * 条目负载用任意 {@code CompoundTag} 占位即可——本方法<b>只改下标</b>，
 * 负载原样保留正是要验的性质之一。
 */
public class TestMekCkSlotNbtRemap {

    /** {@code CuttingMachineFactoryTier.BASIC.processes}。 */
    private static final int BASIC = 3;
    /** {@code CuttingMachineFactoryTier.ADVANCED.processes}。 */
    private static final int ADVANCED = 5;

    /** 造一份「槽位总数 = recorded、条目下标 = indices」的存档块。 */
    private static CompoundTag block(int recorded, int... indices) {
        ListTag items = new ListTag();
        for (int index : indices) {
            CompoundTag entry = new CompoundTag();
            entry.putInt(MekCkSlotNbt.ENTRY_INDEX, index);
            // 负载：用下标当哨兵值，验「负载原样保留」。
            entry.putInt("Payload", index * 1000);
            items.add(entry);
        }
        CompoundTag root = new CompoundTag();
        root.put(MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.block(recorded, items));
        return root;
    }

    private static ListTag entries(CompoundTag root) {
        return root.getCompound(MekCkSlotNbt.TAG_SLOTS).getList(MekCkSlotNbt.ENTRY_LIST, Tag.TAG_COMPOUND);
    }

    private static int indexAt(CompoundTag root, int i) {
        return entries(root).getCompound(i).getInt(MekCkSlotNbt.ENTRY_INDEX);
    }

    private static int recordedCount(CompoundTag root) {
        return root.getCompound(MekCkSlotNbt.TAG_SLOTS).getInt(MekCkSlotNbt.ENTRY_COUNT);
    }

    // ── 核心：按角色重映射 ──────────────────────────────────────────────

    /**
     * BASIC(3) → ADVANCED(5)：输入左对齐不变、输出整体右移 2、能量槽跟边界走、
     * 家族专属槽跟在能量槽之后。
     *
     * <p>旧布局 {@code [0,3) 输入 / [3,6) 输出 / [6] 能量 / [7,9) 专属}，
     * 新布局 {@code [0,5) 输入 / [5,10) 输出 / [10] 能量 / [11,13) 专属}。</p>
     */
    @Test
    public void basicToAdvancedRemapsByRole() {
        // 9 = 3 输入 + 3 输出 + 1 能量 + 2 专属
        CompoundTag root = block(9, 0, 1, 2, 3, 4, 5, 6, 7, 8);

        MekCkSlotNbt.remapForLayoutChange(root, BASIC, BASIC, ADVANCED, ADVANCED);

        assertEquals("输入 0", 0, indexAt(root, 0));
        assertEquals("输入 1", 1, indexAt(root, 1));
        assertEquals("输入 2", 2, indexAt(root, 2));
        assertEquals("输出 0：旧 3 → 新 5", 5, indexAt(root, 3));
        assertEquals("输出 1：旧 4 → 新 6", 6, indexAt(root, 4));
        assertEquals("输出 2：旧 5 → 新 7", 7, indexAt(root, 5));
        assertEquals("能量槽：旧 6 → 新 10", 10, indexAt(root, 6));
        assertEquals("专属槽 0：旧 7 → 新 11", 11, indexAt(root, 7));
        assertEquals("专属槽 1：旧 8 → 新 12", 12, indexAt(root, 8));

        assertEquals("槽位总数必须跟着改成 5+5+1+2", 13, recordedCount(root));
    }

    /** 降档（ADVANCED → BASIC）走同一套映射，方向相反。 */
    @Test
    public void advancedToBasicRemapsByRole() {
        CompoundTag root = block(13, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        MekCkSlotNbt.remapForLayoutChange(root, ADVANCED, ADVANCED, BASIC, BASIC);

        assertEquals("输入 0", 0, indexAt(root, 0));
        assertEquals("输入 4", 4, indexAt(root, 4));
        assertEquals("输出 0：旧 5 → 新 3", 3, indexAt(root, 5));
        assertEquals("输出 4：旧 9 → 新 7", 7, indexAt(root, 9));
        assertEquals("能量槽：旧 10 → 新 6", 6, indexAt(root, 10));
        assertEquals("专属槽 0：旧 11 → 新 7", 7, indexAt(root, 11));
        assertEquals("专属槽 1：旧 12 → 新 8", 8, indexAt(root, 12));

        assertEquals("槽位总数必须跟着改成 3+3+1+2", 9, recordedCount(root));
    }

    /**
     * 输入/输出槽数不等（烹饪 6 进 / 12 出、穿串 3 进 / 2 出）时同样按角色走。
     *
     * <p>这两个家族的槽数与并行数脱钩，但布局仍是
     * {@code [0,in) 输入 / [in,in+out) 输出 / [in+out] 能量}，映射规则不变。</p>
     */
    @Test
    public void asymmetricLayoutRemapsByRole() {
        // 烹饪：6 输入 + 12 输出 + 1 能量 + 1 专属 = 20
        CompoundTag root = block(20, 0, 5, 6, 17, 18, 19);

        MekCkSlotNbt.remapForLayoutChange(root, 6, 12, 6, 12 + 3);

        assertEquals("输入 0 不变", 0, indexAt(root, 0));
        assertEquals("输入 5 不变", 5, indexAt(root, 1));
        assertEquals("输出 0：旧 6 → 新 6（newIn == oldIn，不位移）", 6, indexAt(root, 2));
        assertEquals("输出 11：旧 17 → 新 17", 17, indexAt(root, 3));
        assertEquals("能量槽：旧 18 → 新 21", 21, indexAt(root, 4));
        assertEquals("专属槽：旧 19 → 新 22", 22, indexAt(root, 5));
        assertEquals("槽位总数 6+15+1+1", 23, recordedCount(root));
    }

    // ── 边界与幂等 ──────────────────────────────────────────────────────

    /** 布局相同 ⇒ 一个字节都不动（含 {@code ENTRY_COUNT}）。 */
    @Test
    public void sameLayoutIsNoOp() {
        CompoundTag root = block(9, 0, 3, 6, 8);
        CompoundTag before = root.copy();

        MekCkSlotNbt.remapForLayoutChange(root, BASIC, BASIC, BASIC, BASIC);

        assertEquals("布局相同必须原样返回", before, root);
    }

    /** 没有专属键 ⇒ 什么都不做（旧存档只有 Mek 那份 byte 存档）。 */
    @Test
    public void missingKeyIsNoOp() {
        CompoundTag root = new CompoundTag();
        root.putInt("Items", 1);

        MekCkSlotNbt.remapForLayoutChange(root, BASIC, BASIC, ADVANCED, ADVANCED);

        assertFalse("不该凭空造出专属键", root.contains(MekCkSlotNbt.TAG_SLOTS));
        assertEquals(1, root.getInt("Items"));
    }

    /** {@code root == null} 不抛异常。 */
    @Test
    public void nullRootIsNoOp() {
        MekCkSlotNbt.remapForLayoutChange(null, BASIC, BASIC, ADVANCED, ADVANCED);
    }

    /** 负载（{@code Item} / {@code SizeOverride} / 任意自定义键）必须原样保留。 */
    @Test
    public void payloadIsPreserved() {
        CompoundTag root = block(9, 3, 6);

        MekCkSlotNbt.remapForLayoutChange(root, BASIC, BASIC, ADVANCED, ADVANCED);

        assertEquals("旧 3 号槽的负载", 3000, entries(root).getCompound(0).getInt("Payload"));
        assertEquals("旧 6 号槽的负载", 6000, entries(root).getCompound(1).getInt("Payload"));
    }

    /**
     * 缺 {@code ENTRY_INDEX} 的条目原样保留。
     *
     * <p>与 {@code read} 侧同口径：那种条目读的时候会被跳过并记 WARN，
     * 重映射阶段不该替它编一个下标出来（编出来的下标会指向一个真实槽位，
     * 把「一条坏数据」变成「一个被覆盖的槽」）。</p>
     */
    @Test
    public void entryWithoutIndexIsKeptVerbatim() {
        ListTag items = new ListTag();
        CompoundTag broken = new CompoundTag();
        broken.putInt("Payload", 42);
        items.add(broken);
        CompoundTag good = new CompoundTag();
        good.putInt(MekCkSlotNbt.ENTRY_INDEX, 3);
        items.add(good);
        CompoundTag root = new CompoundTag();
        root.put(MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.block(9, items));

        MekCkSlotNbt.remapForLayoutChange(root, BASIC, BASIC, ADVANCED, ADVANCED);

        assertEquals("条目数不变", 2, entries(root).size());
        assertFalse("坏条目不该被补上下标", entries(root).getCompound(0).contains(MekCkSlotNbt.ENTRY_INDEX));
        assertEquals("坏条目的负载", 42, entries(root).getCompound(0).getInt("Payload"));
        assertEquals("好条目照常重映射", 5, entries(root).getCompound(1).getInt(MekCkSlotNbt.ENTRY_INDEX));
    }

    /**
     * 重映射后的下标必须全部落在新布局的槽位总数之内。
     *
     * <p>这是「{@code ENTRY_COUNT} 与下标同步更新」这条不变量的直接后果：
     * 只改下标不改总数，{@code read} 侧每次读档都会记一条
     * 「记录的槽位数与实际不符」的 WARN；只改总数不改下标，则下标越界被跳过、物品丢失。</p>
     */
    @Test
    public void remappedIndicesFitInsideRecordedCount() {
        CompoundTag root = block(9, 0, 1, 2, 3, 4, 5, 6, 7, 8);

        MekCkSlotNbt.remapForLayoutChange(root, BASIC, BASIC, ADVANCED, ADVANCED);

        int recorded = recordedCount(root);
        for (int i = 0; i < entries(root).size(); i++) {
            int index = indexAt(root, i);
            assertTrue("第 " + i + " 条的下标 " + index + " 越出了新槽位总数 " + recorded,
                    index >= 0 && index < recorded);
        }
    }
}
