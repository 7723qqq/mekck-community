package cn.ism.mekck.machine;

import cn.ism.mekck.blockentity.SimpleMachineRecipes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code SimpleMachineRecipes.matchIngredients} 的分配护栏 —— 重叠配料下贪心会假阴性。
 *
 * <h3>缺陷形态</h3>
 * 旧实现按 {@code required} 顺序逐个配料「拿第一个还没被占的匹配槽」。配料重叠时
 * 先到的配料会把后到配料唯一的槽占掉，于是判无解 —— 而真解存在：
 * <pre>
 *   槽   [胡萝卜, 土豆]
 *   配料 [{胡萝卜,土豆}, {胡萝卜}]
 *   贪心：配料1→槽0，配料2 只剩槽0（已占）⇒ null（假阴性）
 *   真解：配料2→槽0，配料1→槽1
 * </pre>
 * 14 个调用点（寿司 / 米饭 / 烘焙 / 灶台 / 提取 / 包材 / 陈酿……）全部走这条匹配，
 * 假阴性表现为「材料明明够、配方就是不启动」。
 *
 * <h3>为什么能跑真行为测试</h3>
 * 分配核心是纯函数 {@link SimpleMachineRecipes#assignIngredients}，只用到
 * {@link ItemStack} / {@link Ingredient}，不沾任何配方类。裸 JVM 里按 {@link #boot()}
 * 两步把注册表拉起来即可（与 {@code TestCookingBatchFeasibility} 同款）。
 */
public class TestMatchIngredientsBacktracking {

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
        return List.of(stacks);
    }

    /** 旧贪心实现（按顺序各拿第一个空槽）—— 只用于反证夹具确实能区分贪心与回溯。 */
    private static List<Integer> greedyAssignment(List<ItemStack> slotStacks, List<Ingredient> required) {
        boolean[] used = new boolean[slotStacks.size()];
        List<Integer> slots = new ArrayList<>();
        for (Ingredient ing : required) {
            if (ing == null || ing.isEmpty()) continue;
            boolean ok = false;
            for (int s = 0; s < slotStacks.size(); s++) {
                if (used[s]) continue;
                ItemStack st = slotStacks.get(s);
                if (!st.isEmpty() && ing.test(st)) {
                    used[s] = true;
                    slots.add(s);
                    ok = true;
                    break;
                }
            }
            if (!ok) return null;
        }
        return slots.isEmpty() ? null : slots;
    }

    /** 断言分配是「每个非空配料各占一个互不相同的匹配槽」的完美匹配。 */
    private static void assertValidAssignment(List<ItemStack> slotStacks, List<Ingredient> required,
                                              List<Integer> slots) {
        List<Ingredient> effective = new ArrayList<>();
        for (Ingredient ing : required) {
            if (ing != null && !ing.isEmpty()) effective.add(ing);
        }
        assertNotNull("有解时不得返回 null", slots);
        assertEquals("每个非空配料都要分到一个槽", effective.size(), slots.size());
        Set<Integer> distinct = new HashSet<>(slots);
        assertEquals("同一个槽不得被两个配料占用", slots.size(), distinct.size());
        for (int i = 0; i < effective.size(); i++) {
            int slot = slots.get(i);
            assertTrue("槽下标越界：" + slot, slot >= 0 && slot < slotStacks.size());
            assertTrue("第 " + i + " 个配料被分到了不匹配的槽 " + slot,
                    effective.get(i).test(slotStacks.get(slot)));
        }
    }

    // ── 核心：重叠配料必须走回溯，不能假阴性 ──────────────────────────────

    @Test
    public void overlappingIngredientsGetAValidAssignment() {
        List<ItemStack> slotStacks = slots(new ItemStack(Items.CARROT), new ItemStack(Items.POTATO));
        List<Ingredient> required = List.of(
                Ingredient.of(Items.CARROT, Items.POTATO), // 配料1 = {胡萝卜, 土豆}
                Ingredient.of(Items.CARROT));              // 配料2 = {胡萝卜}
        List<Integer> slots = SimpleMachineRecipes.assignIngredients(slotStacks, required);
        assertValidAssignment(slotStacks, required, slots);
    }

    /**
     * 反证：同一夹具上旧贪心判无解。
     *
     * <p>只要 {@code assignIngredients} 退回「按顺序各拿第一个空槽」，上面那条断言
     * 就会跟着变红 —— 这正是本用例能抓住回归的原因。</p>
     */
    @Test
    public void greedyWouldHaveMissedTheOverlappingSolution() {
        List<ItemStack> slotStacks = slots(new ItemStack(Items.CARROT), new ItemStack(Items.POTATO));
        List<Ingredient> required = List.of(
                Ingredient.of(Items.CARROT, Items.POTATO),
                Ingredient.of(Items.CARROT));
        assertNull("旧贪心在重叠配料下判无解 —— 这就是要修的假阴性",
                greedyAssignment(slotStacks, required));
        assertNotNull("回溯必须找到真解",
                SimpleMachineRecipes.assignIngredients(slotStacks, required));
    }

    /** 三配料重叠：贪心同样先占掉窄配料的唯一槽，回溯要能整体让位。 */
    @Test
    public void threeWayOverlapIsAlsoSolved() {
        List<ItemStack> slotStacks = slots(
                new ItemStack(Items.CARROT), new ItemStack(Items.POTATO), new ItemStack(Items.WHEAT));
        List<Ingredient> required = List.of(
                Ingredient.of(Items.CARROT, Items.POTATO, Items.WHEAT),
                Ingredient.of(Items.CARROT),
                Ingredient.of(Items.POTATO));
        assertNull("贪心：配料1 占槽0，配料2 无槽可用", greedyAssignment(slotStacks, required));
        assertValidAssignment(slotStacks, required,
                SimpleMachineRecipes.assignIngredients(slotStacks, required));
    }

    // ── 对照：不重叠 / 无解 / 空配料 的既有口径不得被改坏 ─────────────────

    /** 不重叠时每个配料只有一个候选，分配结果与旧贪心一致（修复不得让正常配方少匹配）。 */
    @Test
    public void nonOverlappingIngredientsKeepTheirNaturalSlots() {
        List<ItemStack> slotStacks = slots(new ItemStack(Items.CARROT), new ItemStack(Items.POTATO));
        List<Ingredient> required = List.of(
                Ingredient.of(Items.CARROT),
                Ingredient.of(Items.POTATO));
        List<Integer> slots = SimpleMachineRecipes.assignIngredients(slotStacks, required);
        assertEquals(List.of(0, 1), slots);
        assertValidAssignment(slotStacks, required, slots);
    }

    /** 真无解（材料确实不够）仍返回 null，不得被回溯「凑」出一个解。 */
    @Test
    public void genuinelyUnsatisfiableInputReturnsNull() {
        List<ItemStack> slotStacks = slots(new ItemStack(Items.CARROT));
        List<Ingredient> required = List.of(
                Ingredient.of(Items.CARROT),
                Ingredient.of(Items.POTATO));
        assertNull(SimpleMachineRecipes.assignIngredients(slotStacks, required));
    }

    /**
     * 空 Ingredient 跳过、不占槽 —— 这是 shaped 配方 {@code getIngredients()} 的填充项
     * （空格子 = {@code Ingredient.EMPTY}）能正常匹配的前提，也是返回列表与
     * 「非空配料」一一对应的契约。
     */
    @Test
    public void emptyIngredientsAreSkippedWithoutTakingASlot() {
        List<ItemStack> slotStacks = slots(new ItemStack(Items.CARROT), new ItemStack(Items.POTATO));
        List<Ingredient> required = List.of(Ingredient.EMPTY, Ingredient.of(Items.CARROT));
        List<Integer> slots = SimpleMachineRecipes.assignIngredients(slotStacks, required);
        assertEquals("只给非空配料分配槽", List.of(0), slots);
    }

    /** 空槽不得被分配。 */
    @Test
    public void emptySlotsAreNotAssigned() {
        List<ItemStack> slotStacks = slots(ItemStack.EMPTY, new ItemStack(Items.CARROT));
        List<Integer> slots = SimpleMachineRecipes.assignIngredients(slotStacks, List.of(Ingredient.of(Items.CARROT)));
        assertEquals(List.of(1), slots);
    }

    /** 空需求 / null 需求返回 null（调用方据此判「不匹配」）。 */
    @Test
    public void emptyRequirementsReturnNull() {
        List<ItemStack> slotStacks = slots(new ItemStack(Items.CARROT));
        assertNull(SimpleMachineRecipes.assignIngredients(slotStacks, null));
        assertNull(SimpleMachineRecipes.assignIngredients(slotStacks, List.of()));
        assertNull("全是空 Ingredient 时没有可分配项",
                SimpleMachineRecipes.assignIngredients(slotStacks, List.of(Ingredient.EMPTY)));
    }
}
