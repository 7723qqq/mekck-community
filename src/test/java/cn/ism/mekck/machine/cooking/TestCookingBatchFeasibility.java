package cn.ism.mekck.machine.cooking;

import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 烹饪工厂<b>批量可行性预检</b>的护栏 —— 钉住「预检份数 == 可消耗份数」。
 *
 * <h3>它钉的是哪个 bug</h3>
 * 旧 {@code batchSize} 用一个循环反复调用纯函数 {@code findAssignment}，但对的是
 * <b>同一份未被消耗的 scan</b>：每轮结果恒同，于是循环只证明「1 份可行」，
 * 却把解析上界 {@code bound} 当成可行份数返回。随后 {@code consumeIngredients}
 * 逐单位真扣，第 k 份失败就 {@code return false} —— 此时整批流体（{@code run} 先
 * {@code consumeFluid}）与部分固体料已扣、零产出、无日志。
 *
 * <h3>为什么夹具不是「I1={A,B}、I2={A}、I3={A}，A×2+B×1」</h3>
 * 该写法假定了「同一槽能同时供两个 Ingredient 各取 1 个」，但
 * {@code findAssignment} 的语义是<b>每个 Ingredient 必须落在互不相同的槽</b>
 * （{@code boolean[] used = new boolean[slotCount]}）。在 I2={A}、I3={A} 且只有一个
 * A 槽时，二者在本单位内就冲突 ⇒ 真实可行 0 份，**区分不出**空转 bug
 * （旧代码在 unit0 也会返回 0）。所以这里改用真正体现「上界 &gt; 真实份数」的
 * 重叠夹具：I1={A,B}、I2={A}，槽位 [A×2, B×1]。
 * <ul>
 *   <li>解析上界：I1 可匹配 {A,A,B} 共 3 个、I2 可匹配 {A,A} 共 2 个 ⇒ bound = 2；</li>
 *   <li>真实可行：第 1 份 I2→A槽、I1→B槽（互异）；第 2 份 I2 还想拿 A槽，但 I1
 *       已无第二个可用的 A/B 槽（互异）⇒ 只剩 1 份。</li>
 * </ul>
 * 即 bound=2、真实 1，旧空转循环会返回 2 —— 这正是本用例能变红的原因。
 *
 * <h3>为什么能跑真行为测试</h3>
 * {@code planUnits} / {@code consumePlan} / {@code findAssignment} 只用到
 * {@link ItemStack} / {@link Ingredient} / {@link IInventorySlot}，不沾任何配方类。
 * 裸 JVM 里按 {@link #boot()} 两步把注册表拉起来即可（Forge 网络钩子的异常吞掉，
 * 与 {@code TestCuttingBatchPacking} 同款）。
 */
public class TestCookingBatchFeasibility {

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    /** 最小可用的 {@link IInventorySlot}：只做栈的读写，容量不参与本用例。 */
    private static final class TestSlot implements IInventorySlot {

        private ItemStack stack;

        TestSlot(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public ItemStack getStack() {
            return stack;
        }

        @Override
        public void setStack(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public int getLimit(ItemStack stack) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isItemValid(ItemStack stack) {
            return true;
        }

        @Override
        public Slot createContainerSlot() {
            throw new UnsupportedOperationException("本测试不建 GUI 槽位");
        }

        @Override
        public void onContentsChanged() {
        }

        @Override
        public CompoundTag serializeNBT() {
            return new CompoundTag();
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
        }
    }

    /** A = 胡萝卜，B = 土豆；{@code all} 与槽位按调用方给出的重叠方式构造。 */
    private static List<Ingredient> overlapping() {
        return List.of(
                Ingredient.of(Items.CARROT, Items.POTATO), // I1 = {A, B}
                Ingredient.of(Items.CARROT));              // I2 = {A}
    }

    private static List<IInventorySlot> twoCarrotsOnePotato() {
        List<IInventorySlot> scan = new ArrayList<>();
        scan.add(new TestSlot(new ItemStack(Items.CARROT, 2)));
        scan.add(new TestSlot(new ItemStack(Items.POTATO, 1)));
        return scan;
    }

    // ── 核心：预检返回真实可行份数（不是解析上界） ────────────────────────

    /**
     * 重叠配料 A×2 + B×1、配方 I1={A,B}+I2={A} ⇒ 真实可行 <b>1</b> 份。
     *
     * <p>旧空转实现会返回解析上界 2（同一份未消耗 scan 的 {@code findAssignment}
     * 每轮都非 null）。这条断言就是那个 bug 的直接反证。</p>
     */
    @Test
    public void overlappingIngredientsYieldRealFeasibleCount() {
        List<IInventorySlot> scan = twoCarrotsOnePotato();
        List<Ingredient> all = overlapping();
        int[][] plan = CookingFactoryExecutor.planUnits(scan, all, 8);
        assertEquals("重叠配料只有 1 份能真扣动；返回 2 就是「按未消耗 scan 空转」的旧缺陷",
                1, plan.length);
    }

    /**
     * 「预检份数 == 可消耗份数」：把预检产出的计划原样喂给消耗侧，必须完整扣完。
     *
     * <p>这正是修复的语义：预检与消耗共用同一份计划，`plan.length` 份一定都扣得动，
     * 不再有「扣到一半失败、流体已扣、零产出」的中间态。</p>
     */
    @Test
    public void precheckCountEqualsConsumableCount() {
        List<IInventorySlot> scan = twoCarrotsOnePotato();
        List<Ingredient> all = overlapping();
        int[][] plan = CookingFactoryExecutor.planUnits(scan, all, 8);

        List<ItemStack> returns = new ArrayList<>();
        int consumed = CookingFactoryExecutor.consumePlan(scan, all, plan, returns);

        assertEquals("预检说 1 份，就必须恰好扣动 1 份（严格相等）", plan.length, consumed);
        assertEquals(1, consumed);
        // 第 1 份按计划 I2→A槽（2→1）、I1→B槽（1→空）；没有第 2 份被误扣。
        assertEquals("B 槽应被扣空", 0, scan.get(1).getStack().getCount());
        assertEquals("A 槽只被扣 1 个", 1, scan.get(0).getStack().getCount());
    }

    /**
     * <b>反例</b>：把预检改回「对未被消耗的同一 scan 反复调用 findAssignment」时，
     * 每轮都非 null ⇒ 空转循环会返回解析上界 2，{@link #overlappingIngredientsYieldRealFeasibleCount}
     * 随之变红。
     *
     * <p>这里直接在<b>未被消耗</b>的镜像上复现旧循环形态，断言它给出解析上界 2，
     * 再断言 {@code planUnits} 给出真实份数 1。只要 {@code planUnits} 里那句
     * {@code --remaining[slot]} 被删掉/未生效，第二条断言就会变红。</p>
     */
    @Test
    public void staleScanAllowsMoreUnitsThanAreConsumable() {
        List<IInventorySlot> scan = twoCarrotsOnePotato();
        List<Ingredient> all = overlapping();

        ItemStack[] sample = {scan.get(0).getStack(), scan.get(1).getStack()};
        int[] remaining = {2, 1};
        // 旧循环形态：不做任何递减，unit=0、unit=1 都判为可行 ⇒ 把解析上界 2 当可行份数。
        int staleUnits = 0;
        for (int unit = 0; unit < 2; unit++) {
            if (CookingFactoryExecutor.findAssignment(sample, remaining, all) == null) {
                break;
            }
            staleUnits++;
        }
        assertEquals("空转循环会把解析上界 2 当成可行份数——这正是要修的缺陷", 2, staleUnits);
        assertEquals("修复后的真实份数必须严格小于空转结果",
                1, CookingFactoryExecutor.planUnits(twoCarrotsOnePotato(), all, 8).length);
    }

    // ── 对照：不重叠时预检不得少算 ───────────────────────────────────────

    /** A×2 + B×2、I1={A}+I2={B} ⇒ 2 份；修复不能让正常配方被少算。 */
    @Test
    public void nonOverlappingIngredientsAreCountedFully() {
        List<IInventorySlot> scan = new ArrayList<>();
        scan.add(new TestSlot(new ItemStack(Items.CARROT, 2)));
        scan.add(new TestSlot(new ItemStack(Items.POTATO, 2)));
        List<Ingredient> all = List.of(
                Ingredient.of(Items.CARROT),
                Ingredient.of(Items.POTATO));

        int[][] plan = CookingFactoryExecutor.planUnits(scan, all, 8);
        assertEquals(2, plan.length);

        List<ItemStack> returns = new ArrayList<>();
        assertEquals(2, CookingFactoryExecutor.consumePlan(scan, all, plan, returns));
        assertTrue("两个槽都应被扣空", scan.get(0).getStack().isEmpty() && scan.get(1).getStack().isEmpty());
    }

    /** 需求为空 / 上限 <= 0 时计划为空，不返回 null（调用方无需判空）。 */
    @Test
    public void emptyInputsYieldEmptyPlan() {
        List<IInventorySlot> scan = twoCarrotsOnePotato();
        assertEquals(0, CookingFactoryExecutor.planUnits(scan, List.of(), 5).length);
        assertEquals(0, CookingFactoryExecutor.planUnits(scan, overlapping(), 0).length);
        assertEquals(0, CookingFactoryExecutor.planUnits(null, overlapping(), 5).length);
    }
}