package cn.ism.mekck.blockentity;

import cn.ism.mekck.TestSourceText;
import cn.ism.mekck.machine.cooking.CookingFactoryExecutor;
import cn.ism.mekck.util.BigStackItemHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 智能厨锅修复的护栏（源码形态 + 行为断言）。
 *
 * <h3>为什么大多是源码形态</h3>
 * 这些修复都在 {@code SmartCookingPotBlockEntity} 的加工路径上，而该类的构造链
 * 需要 {@code BlockEntityType} / {@code Level} / 物品注册表，裸 JVM 里造不出来
 * （同 {@code TestNbtPersistenceInvariants} 的说明）。所以这里钉的是<b>结构</b>：
 * 调用顺序、返回值是否被消费、口径函数是否被换掉 —— 这三类错误都不需要运行游戏
 * 就能判出来，而且正是真实修过的形态。
 *
 * <p>贪心匹配的「模拟账本」例外：{@code findAndConsumeOne} 是包级可见的静态方法，
 * 只用到 {@link ItemStackHandler} / {@link Ingredient}，可以按 {@link #boot()}
 * 把注册表拉起来后做<b>真行为断言</b>（同 {@code TestMatchIngredientsBacktracking}
 * 的口径）—— 模拟门禁是否真的能抓住「canMatch 过、贪心不过」的形态，只有行为
 * 断言说了算。</p>
 *
 * <h3>它钉的是哪些 bug</h3>
 * <ol>
 *   <li><b>C1（Critical）</b>：{@code completeRecipe} 先扣流体、后复检流体，且
 *       {@code consumeAllMaterials} 的返回值被丢弃 ⇒ 罐内流体不足一份时零固体消耗
 *       出产物（物品复制）。</li>
 *   <li><b>m4</b>：{@code load} 原样读 {@code OrderQuantity}；旧档留下
 *       {@code OrderRecipeId} 非空 + {@code OrderQuantity=0} 时，{@code orderQuantity > 0}
 *       门禁失效 ⇒ 无限加工、订单永不完成。</li>
 *   <li><b>m7</b>：{@code getMaxConsumableCountForOrder} 用 {@code totalOf}（跨罐求和）
 *       估算流体份数，而实际 {@code drainOf} 要求单罐足量 ⇒ 估算偏大。</li>
 *   <li><b>canFitAll 64 上限</b>：模拟副本未覆写 {@code getSlotLimit}（默认 64），
 *       输出槽堆到 64 个同种产物后预检永远失败 ⇒ 机器静默停摆。</li>
 *   <li><b>半扣中间态</b>：{@code canMatch} 回溯判定可合成、{@code consumeAllMaterials}
 *       贪心真扣，两者结论可能不同 ⇒ 贪心扣到一半失败留下「扣了一半」的中间态。
 *       模拟模式必须跟踪每槽已用次数，否则模拟门禁在可达路径上永不失败（死代码），
 *       抓不到这类形态。</li>
 *   <li><b>零产出订单完成 + 空转</b>：{@code completeRecipe} 失败仍无条件
 *       {@code orderCompleted++}，且开工门禁不校验流体 ⇒ 订单零产出被标记完成、
 *       机器空转耗能。</li>
 * </ol>
 */
public class TestKitchenPotGuards {

    private static final String POT =
            "src/main/java/cn/ism/mekck/blockentity/SmartCookingPotBlockEntity.java";

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    private static String read(String path) throws IOException {
        return TestSourceText.read(path);
    }

    private static String methodBody(String src, String signature) {
        return TestSourceText.methodBody(src, signature);
    }

    // ── C1 + 半扣：扣料门禁必须在扣流体之前，且先模拟再真扣 ──────────────

    /**
     * {@code completeRecipe} 必须把 {@code consumeAllMaterials} 的返回值当门禁，
     * 且扣料在 {@code consumeFluidForRecipe} <b>之前</b>；真扣之前必须先模拟一遍
     * （同一贪心顺序），失败时零消耗。
     *
     * <p>旧顺序：先 {@code consumeFluidForRecipe}（抽走一份流体）→
     * {@code consumeAllMaterials} 第一步 {@code hasRequiredFluid} 复检同一阈值，
     * 罐内不足 2×需求时返回 false → 返回值被丢弃 → 照出产物。固定一份固体库存
     * 可无限产出。</p>
     *
     * <p>半扣中间态：{@code canMatch} 用回溯判定可合成，而 {@code consumeAllMaterials}
     * 用贪心真扣、不跟踪已用槽 —— 两者对同一库存可能结论不同，贪心扣到一半失败
     * 会留下「扣了一半」的中间态（材料被扣、无产物）。先模拟再真扣后，模拟失败
     * 时零消耗。</p>
     *
     * <p>本用例只钉门禁的<b>存在与顺序</b>；模拟门禁的<b>有效性</b>（跟踪每槽已用
     * 次数，能抓住「canMatch 过、贪心不过」的形态）由
     * {@link #simulateTracksUsedSlotsSoGreedyCannotOverMatch} 与
     * {@link #consumeAllMaterialsSimulationTracksUsedSlots} 钉住 —— 只钉存在不钉
     * 有效性会放过「模拟永不失败」的死代码形态。</p>
     */
    @Test
    public void completeRecipeGatesOnConsumeAllMaterialsBeforeDrainingFluid() throws IOException {
        String body = methodBody(read(POT), "private boolean completeRecipe(");
        assertFalse("找不到 completeRecipe（必须返回 boolean，失败与成功可区分）", body.isEmpty());

        String simulateGate = "if (!consumeAllMaterials(recipe, 1, true)) return false;";
        String executeGate = "if (!consumeAllMaterials(recipe, 1, false)) return false;";
        assertTrue("completeRecipe 必须先模拟扣料（同一贪心顺序）：模拟失败零消耗，"
                        + "旧实现直接真扣，贪心扣到一半失败留下半扣中间态",
                body.contains(simulateGate));
        assertTrue("completeRecipe 必须把真扣的返回值当门禁", body.contains(executeGate));
        int simulateIdx = body.indexOf(simulateGate);
        int executeIdx = body.indexOf(executeGate);
        int drainIdx = body.indexOf("consumeFluidForRecipe(recipe)");
        assertTrue("找不到 consumeFluidForRecipe(recipe)", drainIdx >= 0);
        assertTrue("模拟必须在真扣之前", simulateIdx < executeIdx);
        assertTrue("扣料门禁必须在扣流体之前：先扣流体再复检会因「罐内 < 2×需求」而整单跳过固体消耗",
                executeIdx < drainIdx);
        assertFalse("不得再出现丢弃返回值的裸调用 consumeAllMaterials(recipe, 1, false);",
                body.contains("consumeAllMaterials(recipe, 1, false);"));
    }

    // ── m4：读档夹订单份数 ──────────────────────────────────────────────

    /**
     * {@code load} 必须把订单份数夹到 ≥1（有订单时），无订单时归 0 ——
     * 与 {@code setOrder} / {@code MekCkOrderState.load} 同一契约。
     */
    @Test
    public void loadClampsOrderQuantityToAtLeastOne() throws IOException {
        String body = methodBody(read(POT), "public void load(");
        assertFalse("找不到 load", body.isEmpty());
        assertTrue("load 必须把订单份数夹到 ≥1（对齐 setOrder / MekCkOrderState.load）："
                        + "旧档 OrderRecipeId 非空 + OrderQuantity=0 会让 orderQuantity > 0 门禁失效 ⇒ 无限加工",
                body.contains("Math.max(1, tag.getInt(\"OrderQuantity\"))"));
        assertTrue("无订单（OrderRecipeId 缺失/解析失败）时份数必须归 0，与 setOrder 契约一致",
                body.contains("orderRecipeId == null"));
        assertFalse("不得再原样读订单份数 orderQuantity = tag.getInt(\"OrderQuantity\");",
                body.contains("orderQuantity = tag.getInt(\"OrderQuantity\");"));
    }

    // ── m7：流体估算与消耗同口径（单罐足量，跨罐取最大） ─────────────────

    /**
     * 流体份数上限必须走单罐口径：{@code getMaxConsumableCountForOrder} 不得再用
     * {@code totalOf}（跨罐求和），且必须调用 {@code fluidBatchFor}；后者复用工厂侧
     * 已修好的 {@code batchForFluidAmounts}。
     */
    @Test
    public void orderFluidEstimateUsesSingleTankSemantics() throws IOException {
        String src = read(POT);
        String body = methodBody(src, "public int getMaxConsumableCountForOrder(");
        assertFalse("找不到 getMaxConsumableCountForOrder", body.isEmpty());
        assertFalse("流体估算不得再用跨罐求和 totalOf（与 drainOf 的单罐足量口径不一致："
                        + "150+150 对 250 的需求会高估成 1 份，实际一滴都抽不出）",
                body.contains("totalOf("));
        assertTrue("水/奶上限必须走单罐口径的 fluidBatchFor",
                body.contains("fluidBatchFor(true,") && body.contains("fluidBatchFor(false,"));

        String helper = methodBody(src, "private int fluidBatchFor(");
        assertFalse("找不到 fluidBatchFor", helper.isEmpty());
        assertTrue("fluidBatchFor 必须复用工厂侧已修好的 batchForFluidAmounts（跨罐取最大）",
                helper.contains("batchForFluidAmounts("));
        assertFalse("fluidBatchFor 不得退回跨罐求和", helper.contains("totalOf("));

        String required = methodBody(src, "private boolean hasRequiredFluid(Recipe<?> recipe, int multiplier)");
        assertFalse("找不到 hasRequiredFluid(recipe, multiplier)", required.isEmpty());
        assertTrue("hasRequiredFluid 必须用单罐足量判定 hasEnoughOf", required.contains("hasEnoughOf"));
        assertFalse("hasRequiredFluid 不得用跨罐求和", required.contains("totalOf("));
    }

    /**
     * 口径的算术本身：跨罐求和 vs 单罐取最大。
     *
     * <p>两罐各 150 mB、每份要 250 mB —— 求和口径会给出 1 份（300/250），
     * 单罐口径是 0 份；而 {@code drainOf} 正是单罐口径。这条直接打
     * {@link CookingFactoryExecutor#batchForFluidAmounts}（厨锅的 {@code fluidBatchFor}
     * 就是它的数组包装），与 {@code TestCookingFluidBatchArithmetic} 同源。</p>
     */
    @Test
    public void splitTanksCannotSatisfyASingleTankRequirement() {
        assertEquals("两罐各 150 mB 凑不出单罐 250 mB 的一份（跨罐求和会算成 1 份）",
                0, CookingFactoryExecutor.batchForFluidAmounts(
                        new int[]{150, 150}, new boolean[]{true, true}, 250));
        assertEquals("单罐 250 mB 恰好一份",
                1, CookingFactoryExecutor.batchForFluidAmounts(
                        new int[]{250, 0}, new boolean[]{true, true}, 250));
    }

    // ── 模拟副本必须回答与真槽相同的上限 ────────────────────────────────

    /**
     * {@code canFitAll} 的模拟副本必须覆写 {@code getSlotLimit} 委托真 handler。
     *
     * <p>{@code BigStackItemHandler} 未覆写 {@code getSlotLimit}，继承
     * {@code ItemStackHandler} 的默认 64；而真 items 的 OUTPUT_SLOT 上限是
     * {@code Integer.MAX_VALUE}。输出槽里同种产物 ≥64 时，模拟判定「装不下」⇒
     * {@code serverTick} 门禁失败、{@code progress} 每 tick 清零，机器静默停摆
     * （玩家不取走产物就永远不再加工）。</p>
     */
    @Test
    public void canFitAllSimulationCopyDelegatesSlotLimit() throws IOException {
        String body = methodBody(read(POT), "private boolean canFitAll(");
        assertFalse("找不到 canFitAll", body.isEmpty());
        assertTrue("canFitAll 的模拟副本必须覆写 getSlotLimit 委托真 handler："
                        + "BigStackItemHandler 默认 64，而输出槽上限是 Integer.MAX_VALUE，"
                        + "输出槽堆到 64 个同种产物后预检永远失败、机器静默停摆",
                body.contains("public int getSlotLimit(int slot)")
                        && body.contains("return items.getSlotLimit(slot);"));
    }

    // ── 开工门禁校验流体 / 订单计数只在真产出后推进 ──────────────────────

    /**
     * {@code serverTick} 的开工门禁必须校验流体：旧实现只查 {@code canFitAll}
     * （输出空间），罐内 0 mB 奶 + 固体齐时机器照常开工，跑满 200 tick（20 FE/tick）
     * 后零产出 —— 空转耗能。
     */
    @Test
    public void startGateChecksRequiredFluid() throws IOException {
        String tick = methodBody(read(POT), "public static void serverTick(");
        assertFalse("找不到 serverTick", tick.isEmpty());
        assertTrue("开工门禁必须校验流体（hasRequiredFluid）：旧实现只查 canFitAll，"
                        + "流体不足时机器照常开工、跑满 200 tick 后零产出（空转耗能）",
                tick.contains("machine.hasRequiredFluid(recipe)"));
    }

    /**
     * {@code serverTick} 必须把 {@code completeRecipe} 的返回值当门禁：
     * 失败（输出装不下 / 材料或流体不足）时零产出，不得推进 {@code orderCompleted}。
     * 旧实现无条件 {@code orderCompleted++}，订单会在零产出下被标记完成。
     */
    @Test
    public void orderCountAdvancesOnlyOnSuccessfulCompletion() throws IOException {
        String tick = methodBody(read(POT), "public static void serverTick(");
        assertFalse("找不到 serverTick", tick.isEmpty());
        assertTrue("serverTick 必须把 completeRecipe 的返回值当门禁：失败（零产出）不推进订单计数",
                tick.contains("if (machine.completeRecipe(level, recipe) && machine.orderQuantity > 0)"));
        assertFalse("不得再无条件推进订单计数：旧实现 completeRecipe 失败仍 orderCompleted++，"
                        + "订单在零产出下被标记完成",
                tick.contains("machine.completeRecipe(level, recipe);"));
    }

    // ── 模拟必须跟踪已用槽（否则模拟门禁是死代码） ──────────────────────

    /**
     * 行为断言：模拟模式必须跟踪每槽已用次数，抓住 finding 的反例形态。
     *
     * <p>反例：I1={A,B}、I2={A}，库存 A×1+B×1（A 在前）。{@code canMatch} 回溯
     * 通过（I1→B、I2→A）；不跟踪用量的贪心模拟也通过（I1→A、I2→A —— 同一件 A
     * 被用两次），而真扣时 I1 扣走 A、I2 找不到 A ⇒ 半扣中间态。跟踪用量后
     * 模拟必须判 I2 无槽可用（返回 -1），且不得改动真库存。</p>
     */
    @Test
    public void simulateTracksUsedSlotsSoGreedyCannotOverMatch() {
        ItemStackHandler handler = new BigStackItemHandler(SmartCookingPotBlockEntity.TOTAL_SLOTS);
        handler.setStackInSlot(0, new ItemStack(Items.CARROT, 1));
        handler.setStackInSlot(1, new ItemStack(Items.POTATO, 1));
        Ingredient i1 = Ingredient.of(Items.CARROT, Items.POTATO);
        Ingredient i2 = Ingredient.of(Items.CARROT);
        int[] used = new int[SmartCookingPotBlockEntity.INPUT_SLOT_COUNT
                + SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT];

        assertEquals("I1 贪心先匹配到 A（槽 0）",
                0, SmartCookingPotBlockEntity.findAndConsumeOne(handler, i1, true, null, used));
        assertEquals("模拟必须跟踪已用槽：同一件 A 不得被 I2 重复匹配 —— "
                        + "否则模拟会放过「canMatch 过、贪心不过」的半扣形态（finding 反例）",
                -1, SmartCookingPotBlockEntity.findAndConsumeOne(handler, i2, true, null, used));
        assertEquals("模拟不得改动真库存（槽 0）", 1, handler.getStackInSlot(0).getCount());
        assertEquals("模拟不得改动真库存（槽 1）", 1, handler.getStackInSlot(1).getCount());
    }

    /**
     * 对照：A×2 时同一模拟序列必须通过 —— 跟踪用量不得把「同一槽的第二件」
     * 误判成不够。
     */
    @Test
    public void simulateStillPassesWhenTheSecondCopyExists() {
        ItemStackHandler handler = new BigStackItemHandler(SmartCookingPotBlockEntity.TOTAL_SLOTS);
        handler.setStackInSlot(0, new ItemStack(Items.CARROT, 2));
        handler.setStackInSlot(1, new ItemStack(Items.POTATO, 1));
        Ingredient i1 = Ingredient.of(Items.CARROT, Items.POTATO);
        Ingredient i2 = Ingredient.of(Items.CARROT);
        int[] used = new int[SmartCookingPotBlockEntity.INPUT_SLOT_COUNT
                + SmartCookingPotBlockEntity.STORAGE_SLOT_COUNT];

        assertEquals(0, SmartCookingPotBlockEntity.findAndConsumeOne(handler, i1, true, null, used));
        assertEquals("A×2：I2 应匹配到同一槽的第二件 A",
                0, SmartCookingPotBlockEntity.findAndConsumeOne(handler, i2, true, null, used));
    }

    /**
     * 源码形态：{@code consumeAllMaterials} 必须分配账本并传给 {@code findAndConsumeOne}；
     * 后者模拟时用 {@code count > usedCount(used, i)} 判定并记账。
     */
    @Test
    public void consumeAllMaterialsSimulationTracksUsedSlots() throws IOException {
        String src = read(POT);
        String body = methodBody(src, "private boolean consumeAllMaterials(");
        assertFalse("找不到 consumeAllMaterials", body.isEmpty());
        assertTrue("模拟模式必须分配「每槽已用次数」账本",
                body.contains("int[] used = simulate ? new int["));
        assertTrue("账本必须传给 findAndConsumeOne",
                body.contains("findAndConsumeOne(items, ing, simulate, consumedHolder, used)"));

        String find = methodBody(src, "static int findAndConsumeOne(");
        assertFalse("找不到 findAndConsumeOne(handler, ...)", find.isEmpty());
        assertTrue("模拟匹配必须用「已用次数」判定（count > usedCount(used, i)）",
                find.contains("usedCount(used, "));
        assertTrue("匹配后必须记入账本",
                find.contains("used[i]++") && find.contains("used[storageBase + j]++"));
    }

    /**
     * 返还槽必须<b>直写</b>，不得走 {@code items.insertItem}。
     *
     * <p>{@code ItemStackHandler.insertItem} 第一步就查 {@code isItemValid}，而本机对
     * {@code OUTPUT_SLOT} / {@code RETURN_SLOT} 返回 {@code false}（那两个槽对玩家禁入）
     * ⇒ <b>机器自己的返还物被一并拒掉</b>，剩余永不为空 ⇒ 每个空桶 / 空瓶都掉在机器上方，
     * GUI 里的返还槽<b>永远是空的</b>。本仓在 {@code IceFactoryBlockEntity} 的注释里已把
     * 这条机制写死；切菜机 / 种植切配站 / 三明治组装机的产出与返还也都走直写。</p>
     */
    @Test
    public void returnSlotIsWrittenDirectlyNotThroughInsertItem() throws IOException {
        String src = read(POT);
        assertFalse("返还物不得走 items.insertItem —— isItemValid 对 RETURN_SLOT 返回 false，"
                        + "机器自己的返还物会被拒掉并掉在地上",
                src.contains("items.insertItem(RETURN_SLOT"));
        assertTrue("两个返还点都必须走 insertReturn（直写槽 + 同类合并）",
                src.contains("insertReturn(info.emptyContainer())") && src.contains("insertReturn(ret)"));
    }
}
