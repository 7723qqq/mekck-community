package cn.ism.mekck.blockentity;

import cn.ism.mekck.TestSourceText;
import cn.ism.mekck.machine.cooking.CookingFactoryExecutor;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 智能厨锅本轮修复的护栏（源码形态 + 一段纯算术）。
 *
 * <h3>为什么是源码形态</h3>
 * 三处修复都在 {@code SmartCookingPotBlockEntity} 的加工路径上，而该类的构造链
 * 需要 {@code BlockEntityType} / {@code Level} / 物品注册表，裸 JVM 里造不出来
 * （同 {@code TestNbtPersistenceInvariants} 的说明）。所以这里钉的是<b>结构</b>：
 * 调用顺序、返回值是否被消费、口径函数是否被换掉 —— 这三类错误都不需要运行游戏
 * 就能判出来，而且正是本轮真实修过的形态。
 *
 * <h3>它钉的是哪三个 bug</h3>
 * <ol>
 *   <li><b>C1（Critical）</b>：{@code completeRecipe} 先扣流体、后复检流体，且
 *       {@code consumeAllMaterials} 的返回值被丢弃 ⇒ 罐内流体不足一份时零固体消耗
 *       出产物（物品复制）。</li>
 *   <li><b>m4</b>：{@code load} 原样读 {@code OrderQuantity}；旧档留下
 *       {@code OrderRecipeId} 非空 + {@code OrderQuantity=0} 时，{@code orderQuantity > 0}
 *       门禁失效 ⇒ 无限加工、订单永不完成。</li>
 *   <li><b>m7</b>：{@code getMaxConsumableCountForOrder} 用 {@code totalOf}（跨罐求和）
 *       估算流体份数，而实际 {@code drainOf} 要求单罐足量 ⇒ 估算偏大。</li>
 * </ol>
 */
public class TestKitchenPotGuards {

    private static final String POT =
            "src/main/java/cn/ism/mekck/blockentity/SmartCookingPotBlockEntity.java";

    private static String read(String path) throws IOException {
        return TestSourceText.read(path);
    }

    private static String methodBody(String src, String signature) {
        return TestSourceText.methodBody(src, signature);
    }

    // ── C1：扣料门禁必须在扣流体之前 ─────────────────────────────────────

    /**
     * {@code completeRecipe} 必须把 {@code consumeAllMaterials} 的返回值当门禁，
     * 且该调用在 {@code consumeFluidForRecipe} <b>之前</b>。
     *
     * <p>旧顺序：先 {@code consumeFluidForRecipe}（抽走一份流体）→
     * {@code consumeAllMaterials} 第一步 {@code hasRequiredFluid} 复检同一阈值，
     * 罐内不足 2×需求时返回 false → 返回值被丢弃 → 照出产物。固定一份固体库存
     * 可无限产出。</p>
     */
    @Test
    public void completeRecipeGatesOnConsumeAllMaterialsBeforeDrainingFluid() throws IOException {
        String body = methodBody(read(POT), "private void completeRecipe(");
        assertFalse("找不到 completeRecipe", body.isEmpty());

        String gate = "if (!consumeAllMaterials(recipe, 1, false)) return;";
        assertTrue("completeRecipe 必须把 consumeAllMaterials 的返回值当门禁："
                        + "旧实现丢弃返回值 ⇒ 罐内流体不足一份时零固体消耗出产物（物品复制）",
                body.contains(gate));
        int gateIdx = body.indexOf(gate);
        int drainIdx = body.indexOf("consumeFluidForRecipe(recipe)");
        assertTrue("找不到 consumeFluidForRecipe(recipe)", drainIdx >= 0);
        assertTrue("扣料门禁必须在扣流体之前：先扣流体再复检会因「罐内 < 2×需求」而整单跳过固体消耗",
                gateIdx < drainIdx);
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
}
