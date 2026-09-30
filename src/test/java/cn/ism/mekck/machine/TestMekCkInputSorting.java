package cn.ism.mekck.machine;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 输入槽自动分选（{@code MekCkMachineTile.sortedLayout}）的护栏。
 *
 * <h3>为什么需要它</h3>
 * 分选会<b>搬动玩家的物品</b>。这类代码最严重的失败形态不是「分得不均匀」，而是
 * <b>把物品弄丢</b>——玩家看不见、日志里也没有，只能事后发现少了一组材料。
 * 所以这里把「总量守恒」钉成不变量，逐条覆盖会走到它的分支：
 * <ul>
 *   <li>同种物品摊平（含除不尽时的余数分配）；</li>
 *   <li>多种物品共存时，空槽先到先得、没抢到的不受影响；</li>
 *   <li>单槽容量小于均分量时，多出来的必须落到后面的槽而不是被截掉；</li>
 *   <li>摆法已经是最优时，输出必须与输入<b>逐格相同</b>——否则调用方会每 tick 都写一遍槽位。</li>
 * </ul>
 */
public class TestMekCkInputSorting {

    private static final int LIMIT = 64;

    /**
     * 裸 JVM 里把原版注册表拉起来 —— 与 {@code TestCuttingBatchPacking.boot()} 同一套两步。
     *
     * <p>第二步会在 Forge 网络钩子阶段抛
     * {@code NoSuchMethodException: net.minecraftforge.network.NetworkEvent.<init>()}
     * （测试 classpath 上的 Minecraft 没经 Forge 的 mixin/ASM 处理），
     * 但 {@code BuiltInRegistries} 与原版物品此时已注册完毕，吞掉即可。</p>
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

    private static List<ItemStack> slots(ItemStack... stacks) {
        List<ItemStack> list = new ArrayList<>(stacks.length);
        for (ItemStack stack : stacks) {
            list.add(stack);
        }
        return list;
    }

    /** 某种物品在所有槽里的总数。 */
    private static int totalOf(List<ItemStack> layout, ItemStack kind) {
        int total = 0;
        for (ItemStack stack : layout) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, kind)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 所有槽里的物品总数（含种类数）。 */
    private static int totalCount(List<ItemStack> layout) {
        int total = 0;
        for (ItemStack stack : layout) {
            total += stack.getCount();
        }
        return total;
    }

    private static ItemStack of(net.minecraft.world.item.Item item, int count) {
        return new ItemStack(item, count);
    }

    /** 同种物品在多个槽里不均时，摊成尽量平均（除不尽时前面的槽多 1）。 */
    @Test
    public void sameItemIsSpreadEvenly() {
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(
                slots(of(Items.APPLE, 10), of(Items.APPLE, 1), ItemStack.EMPTY), LIMIT);
        assertEquals(11, totalOf(layout, of(Items.APPLE, 1)));
        assertEquals(4, layout.get(0).getCount());
        assertEquals(4, layout.get(1).getCount());
        assertEquals(3, layout.get(2).getCount());
    }

    /** 一个叠 + 若干空槽 ⇒ 摊到与物品数相同的槽数上（每槽 1 个）。 */
    @Test
    public void aFullStackSpreadsAcrossEmptySlots() {
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(
                slots(of(Items.APPLE, 5), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY), LIMIT);
        assertEquals(5, totalOf(layout, of(Items.APPLE, 1)));
        for (int i = 0; i < 5; i++) {
            assertEquals("第 " + i + " 槽应各 1 个", 1, layout.get(i).getCount());
        }
    }

    /** 空槽比物品数少时，摊到「槽数」为止，除不尽的部分落在前面的槽。 */
    @Test
    public void spreadingStopsAtTheSlotCount() {
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(
                slots(of(Items.APPLE, 5), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY), LIMIT);
        assertEquals(5, totalOf(layout, of(Items.APPLE, 1)));
        assertEquals(2, layout.get(0).getCount());
        assertEquals(1, layout.get(1).getCount());
        assertEquals(1, layout.get(2).getCount());
        assertEquals(1, layout.get(3).getCount());
    }

    /** 两种物品共存：先到先得拿空槽，但<b>谁都不会丢</b>。 */
    @Test
    public void twoKindsShareEmptySlotsWithoutLosingAnything() {
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(
                slots(of(Items.APPLE, 4), of(Items.BREAD, 4), ItemStack.EMPTY, ItemStack.EMPTY), LIMIT);
        assertEquals(4, totalOf(layout, of(Items.APPLE, 1)));
        assertEquals(4, totalOf(layout, of(Items.BREAD, 1)));
        assertEquals(8, totalCount(layout));
    }

    /** 空槽不够分时，没抢到空槽的物品保留它原有的槽 —— 总量仍然守恒。 */
    @Test
    public void kindThatGetsNoEmptySlotKeepsItsOwnSlots() {
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(
                slots(of(Items.APPLE, 8), of(Items.BREAD, 3), ItemStack.EMPTY), LIMIT);
        assertEquals(8, totalOf(layout, of(Items.APPLE, 1)));
        assertEquals(3, totalOf(layout, of(Items.BREAD, 1)));
        assertEquals(11, totalCount(layout));
    }

    /** 单槽容量小于均分量时，多出来的必须落到后面的槽，不能被截掉。 */
    @Test
    public void smallSlotLimitStillSpreadsAndConserves() {
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(
                slots(of(Items.APPLE, 16), of(Items.APPLE, 16), ItemStack.EMPTY, ItemStack.EMPTY), 16);
        assertEquals(32, totalOf(layout, of(Items.APPLE, 1)));
        for (ItemStack stack : layout) {
            assertTrue("每槽不得超过容量 16，实际 " + stack.getCount(), stack.getCount() <= 16);
        }
        assertEquals("应摊成 8/8/8/8", 8, layout.get(0).getCount());
    }

    /**
     * 总量超过目标槽总容量时，<b>宁可超容量堆叠也不销毁</b>。
     *
     * <p>这个场景在真机上可达：{@code MekCkSlot} 的 {@code obeyStackLimit = false}，
     * 槽里可以存在超过 {@code getLimit} 的叠（例如物品自身堆叠上限是 16 而槽里被塞了 64）。
     * 此时分选必须保证总量不变 —— 少一个都是玩家的损失。</p>
     */
    @Test
    public void overCapacityTotalIsNeverTruncated() {
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(
                slots(of(Items.APPLE, 64), ItemStack.EMPTY), 16);
        assertEquals(64, totalOf(layout, of(Items.APPLE, 1)));
    }

    /** 摆法已经最优时输出必须与输入逐格相同 —— 调用方靠它决定「一个字节都不写」。 */
    @Test
    public void alreadySortedLayoutIsReturnedUnchanged() {
        List<ItemStack> current = slots(of(Items.APPLE, 4), of(Items.APPLE, 4), of(Items.APPLE, 3));
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(current, LIMIT);
        for (int i = 0; i < current.size(); i++) {
            assertTrue("第 " + i + " 槽不该变", ItemStack.matches(current.get(i), layout.get(i)));
        }
    }

    /** 全空输入不产生任何变化。 */
    @Test
    public void emptyInputStaysEmpty() {
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(
                slots(ItemStack.EMPTY, ItemStack.EMPTY), LIMIT);
        assertEquals(0, totalCount(layout));
    }

    /** 不同 NBT 的同种物品算两种，不能互相合并。 */
    @Test
    public void differentNbtIsTreatedAsDifferentKinds() {
        ItemStack plain = of(Items.DIAMOND_SWORD, 1);
        ItemStack named = of(Items.DIAMOND_SWORD, 1);
        named.setHoverName(net.minecraft.network.chat.Component.literal("x"));
        List<ItemStack> layout = MekCkMachineTile.sortedLayout(slots(plain, named), LIMIT);
        assertEquals(2, totalCount(layout));
        assertEquals(1, layout.get(0).getCount());
        assertEquals(1, layout.get(1).getCount());
        assertTrue("带名字的那把必须还在", layout.get(0).hasCustomHoverName() || layout.get(1).hasCustomHoverName());
    }
}
