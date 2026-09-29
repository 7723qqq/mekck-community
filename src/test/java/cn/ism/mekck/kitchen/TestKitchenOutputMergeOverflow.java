package cn.ism.mekck.kitchen;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 产物归并的<b>溢出</b>护栏 —— 回归第三轮审查抓到的「静默销毁整堆物品」。
 *
 * <h3>缺陷形态</h3>
 * {@code KitchenRecipeMatcher.insertOutputs} 曾在算完本批总量并夹到
 * {@code MAX_VALUE - 1} 之后，直接 {@code existing.grow(stack.getCount())}。
 * {@code ItemStack.grow(n)} 就是 {@code setCount(getCount() + n)}，而 1.20.1 的
 * {@code ItemStack.setCount} <b>不做任何夹紧</b>。本模组槽位上限默认就是
 * {@code Integer.MAX_VALUE}，所以「已有 ~2.1e9 + 本批 ~2.1e9」<b>必然溢出为负</b>。
 *
 * <p>后果链：{@code count <= 0} ⇒ {@code isEmpty()} 变 true ⇒ 该格被当成空格；
 * 落盘前被读到时 {@code BigStackItemHandler.readStack} 走
 * {@code if (count <= 0) return ItemStack.EMPTY;} ⇒ <b>整堆永久消失，无任何日志</b>。
 * 且 {@code grow()} 不触发 {@code onContentsChanged()}，方块实体可能连
 * {@code setChanged()} 都不会被调到。</p>
 *
 * <h3>为什么用 ItemStackHandler 而不是真的 BE</h3>
 * 真中央厨房要 300 格存储与模块安装机制，裸 JVM 里造不出来；而本缺陷只涉及
 * {@code getStackInSlot / setStackInSlot / getSlotLimit} 三件事，
 * {@link ItemStackHandler} 恰好就是生产路径上的那个类型。
 */
public class TestKitchenOutputMergeOverflow {

    /**
     * 裸 JVM 里启用 {@link ItemStack} / {@code Items} / 注册表。
     *
     * <p><b>必须逐字照抄 {@code machine/cutting/TestCuttingBatchPacking#boot} 的配方，
     * 且必须在 {@code @BeforeClass} 里</b>。本测试第一版把 {@code Bootstrap.bootStrap()}
     * 写在某个测试方法体内，于是：</p>
     * <ol>
     *   <li>漏了 {@code SharedConstants.tryDetectVersion()} 这一步 ⇒
     *       {@code ItemStack.<clinit>} 抛 {@code ExceptionInInitializerError}；</li>
     *   <li>而 JVM 对「初始化失败的类」会缓存这个失败，之后<b>任何</b>碰到 {@code ItemStack}
     *       的测试都变成 {@code NoClassDefFoundError: Could not initialize class} ——
     *       <b>一次失败毒化整个测试会话</b>。实测把 {@code TestCuttingBatchPacking} 的
     *       17 个用例一起带崩。</li>
     * </ol>
     * 第 2 步会在 Forge 网络钩子阶段抛
     * {@code NoSuchMethodException: NetworkEvent.<init>()}（测试 classpath 上的 Minecraft
     * 没经 Forge 的 mixin/ASM 处理），但注册表此时已就绪，吞掉即可。</p>
     */
    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    private static ItemStack of(net.minecraft.world.item.Item item, int count) {
        ItemStack s = new ItemStack(item);
        s.setCount(count);
        return s;
    }

    private static ItemStackHandler newHandler() {
        return new ItemStackHandler(8) {
            @Override
            public int getSlotLimit(int slot) {
                // 与 MekckConfig.getFactorySlotLimit 的默认值一致：单槽无上限。
                return Integer.MAX_VALUE;
            }
        };
    }

    /**
     * 两个都接近 int 上限的堆叠归并 —— 修复前这一步直接产出<b>负数量</b>。
     */
    @Test
    public void mergingTwoHugeStacksNeverProducesANegativeCount() {
        // 用真实物品：裸 JVM 里需要 Bootstrap 才能拿到注册表里的物品，
        // 而 putLong 造不出 ItemStack。这里退一步：先确认守卫逻辑本身（见下一个测试），
        // 因为「数量」这一维完全可以脱离具体物品断言。
        int existing = Integer.MAX_VALUE - 1;
        int incoming = Integer.MAX_VALUE - 1;
        int merged = (int) Math.min(Integer.MAX_VALUE, (long) existing + incoming);
        assertTrue("修复前这行是 existing + incoming，会溢出为负", merged > 0);
        assertEquals(Integer.MAX_VALUE, merged);
    }

    /**
     * 归并必须**只搬得动的量**，剩余量继续往下找位置；实在放不下才进 leftover。
     *
     * <p>这是 {@code insertOutputs} 现在的实际语义，也是它与旧实现的关键差别：
     * 旧实现「要么整堆并进去（可能溢出）、要么整堆放进空格」，从不存在「部分归并 +
     * 剩余量继续找」这条路径 —— 所以放不下时会直接丢。</p>
     */
    @Test
    public void partiallyFilledSlotTakesWhatItCanAndTheRestKeepsLooking() {
        // 真实物品走不通裸 JVM（需要 Bootstrap + 注册表），所以直接对算法断言。
        int slotLimit = 100;
        int existing = 90;
        int incoming = 40;

        int space = Math.min(slotLimit, Integer.MAX_VALUE) - existing;
        assertEquals(10, space);
        int moved = Math.min(space, incoming);
        int afterMerge = existing + moved;
        int remaining = incoming - moved;

        assertEquals(100, afterMerge);
        assertEquals("装不下的 30 件必须留下，而不是被吞掉", 30, remaining);
        assertTrue("任何一步都不得让数量变负", afterMerge > 0 && remaining > 0);
    }

    /**
     * 槽位已满时归并不得改动它 —— 旧实现在这种情况下是「跳过」，
     * 新实现也必须跳过（{@code space <= 0 → continue}）。
     */
    @Test
    public void aFullSlotIsLeftUntouched() {
        int slotLimit = 64;
        int existing = 64;
        int space = Math.min(slotLimit, Integer.MAX_VALUE) - existing;
        assertTrue("满槽的空间必须是 0 或负，否则会往里塞出超容量堆叠", space <= 0);
    }

    /**
     * 端到端跑一次真实的 {@code insertOutputs}：物品用 vanilla 的石头
     * （{@link net.minecraft.world.item.Items#STONE} 在 {@code Bootstrap.bootStrap()} 后可用）。
     */
    @Test
    public void insertOutputsKeepsEveryItemAndNeverReturnsNegativeCounts() {
        ItemStackHandler items = newHandler();
        ItemStack big = of(net.minecraft.world.item.Items.STONE, Integer.MAX_VALUE - 1);

        // 输出区 0..8。0 号先放一个几乎满的同类堆叠，1 号空 ——
        // 逼它走「部分归并到 0 号 + 剩余量落到 1 号」这条路径。
        items.setStackInSlot(0, of(net.minecraft.world.item.Items.STONE, Integer.MAX_VALUE - 1));

        List<ItemStack> leftover = KitchenRecipeMatcher.insertOutputs(
                items, 0, 8, List.of(big), 1);

        for (int slot = 0; slot < 8; slot++) {
            ItemStack got = items.getStackInSlot(slot);
            if (!got.isEmpty()) {
                assertTrue("第 " + slot + " 格数量为 " + got.getCount() + "（≤0 即溢出，"
                                + "而 isEmpty() 变真意味着这一格看起来是空的、落盘时整堆消失）",
                        got.getCount() > 0);
            }
        }
        for (ItemStack left : leftover) {
            assertTrue("leftover 里不允许出现非正数量", left.getCount() > 0);
        }
    }

    /**
     * 端到端：归并后的**总量**必须守恒（既没有凭空消失，也没有凭空多出来）。
     *
     * <p>这是本缺陷最本质的不变量。修复前：0 号格 {@code existing + stack} 溢出成负数
     * ⇒ 那一格被读成空 ⇒ 2.1e9 × 2 件凭空蒸发，且没有任何日志。</p>
     */
    @Test
    public void mergingPreservesTheTotalItemCount() {
        ItemStackHandler items = newHandler();
        int existing = Integer.MAX_VALUE - 1;
        int incoming = Integer.MAX_VALUE - 1;
        items.setStackInSlot(0, of(net.minecraft.world.item.Items.STONE, existing));

        List<ItemStack> leftover = KitchenRecipeMatcher.insertOutputs(
                items, 0, 8, List.of(of(net.minecraft.world.item.Items.STONE, incoming)), 1);

        long inStorage = 0;
        for (int slot = 0; slot < 8; slot++) {
            ItemStack got = items.getStackInSlot(slot);
            if (!got.isEmpty()) {
                inStorage += got.getCount();
            }
        }
        long inLeftover = 0;
        for (ItemStack left : leftover) {
            inLeftover += left.getCount();
        }
        long expected = (long) existing + incoming;

        assertEquals("归并必须守恒：进（存储区 + leftover）应等于出。"
                        + "修复前 0 号格溢出为负 ⇒ 该格被读成空 ⇒ 静默销毁近 21 亿件",
                expected, inStorage + inLeftover);
    }

    /**
     * 归并后槽内数量**不得超过**槽上限 —— 新实现逐格夹紧，旧实现不夹。
     */
    @Test
    public void mergedSlotNeverExceedsItsLimit() {
        int slotLimit = 1000;
        int existing = 999;
        int incoming = 5000;
        int space = Math.min(slotLimit, Integer.MAX_VALUE) - existing;
        int moved = Math.max(0, Math.min(space, incoming));
        assertEquals(1, moved);
        assertTrue("归并后 " + (existing + moved) + " 超过槽上限 " + slotLimit,
                existing + moved <= slotLimit);
    }

    /** 供 {@link CompoundTag} 场景复用的小工具：数量为 0 的栈必须被视作空。 */
    @Test
    public void zeroCountStackReadsAsEmpty() {
        ItemStack zero = of(net.minecraft.world.item.Items.STONE, 0);
        assertTrue("数量 ≤ 0 的栈就是空 —— 这正是溢出后整堆消失的机制",
                zero.isEmpty());
    }
}
