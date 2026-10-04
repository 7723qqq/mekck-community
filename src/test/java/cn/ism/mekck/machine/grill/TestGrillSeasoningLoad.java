package cn.ism.mekck.machine.grill;

import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 烧烤工厂读档时「无订单 ⇒ 清调味料」的守卫 —— 它此前被随后的读取覆盖。
 *
 * <h3>它钉的是哪个 bug</h3>
 * {@code GrillFactoryExecutor.load} 原先写成：
 * <pre>{@code
 * order.load(tag);
 * if (!order.isActive()) {
 *     orderSeasoning = null;          // ← 守卫：无订单不得残留调味料
 * }
 * if (tag != null) {
 *     orderSeasoning = tag.contains(TAG_ORDER_SEASONING, …) ? … : null;   // ← 立刻覆盖
 * }
 * }</pre>
 * 于是「无订单 + 存档里残留 {@code OrderSeasoning}」时清空被立刻覆盖，守卫形同虚设。
 * 后果不止显示上多一行：{@code currentSeasoningFor()} 在<b>自由投料</b>（无订单）时
 * 也会拿它去强制调味，{@code consumeSeasoningUses} 的
 * {@code enabledOnly = orderSeasoning == null || isEmpty()} 随之变成 false
 * ⇒ 去扣<b>未启用</b>槽的次数；{@code save()} 又会把它写回 NBT 持久化下来。
 *
 * <h3>为什么这里能跑真行为测试</h3>
 * {@code GrillFactoryExecutor} 的构造与 {@code load} 只碰 {@link CompoundTag} 与
 * {@link MekCkOrderState} 这类纯状态对象，不碰注册表、世界或配方 —— 裸 JVM 里
 * 直接 {@code new} 出来调 {@code load} 即可（与 {@code TestSkeweringCustomOrderRoundTrip}
 * 只能复刻逻辑不同，这里打的是生产代码本身）。
 */
public class TestGrillSeasoningLoad {

    private static final String RECIPE = "mekck:grilling/beef";
    private static final String SEASONING = "mekck:salt";

    /** 一份带订单的存档标签；{@code seasoning} 为 null 时不写调味料键。 */
    private static CompoundTag orderTag(String seasoning) {
        CompoundTag tag = new CompoundTag();
        tag.putString("OrderRecipeId", RECIPE);
        tag.putInt("OrderQuantity", 4);
        tag.putInt("OrderCompleted", 0);
        if (seasoning != null) {
            tag.putString("OrderSeasoning", seasoning);
        }
        return tag;
    }

    // ── 核心：无订单时残留的调味料必须被清掉 ────────────────────────────

    /**
     * 存档里只有 {@code OrderSeasoning}、没有任何订单键 ⇒ 读档后调味料必须为 null。
     *
     * <p>旧写法在这里会返回 {@code "mekck:salt"}（清空被随后的读取覆盖），
     * 于是它会在自由投料下强制调味并被持久化。</p>
     */
    @Test
    public void staleSeasoningIsClearedWhenThereIsNoOrder() {
        GrillFactoryExecutor exec = new GrillFactoryExecutor();
        CompoundTag tag = new CompoundTag();
        tag.putString("OrderSeasoning", SEASONING);

        exec.load(tag);

        assertEquals("没有订单", 0, exec.getOrderQuantity());
        assertNull("存档里残留的 OrderSeasoning 必须被清掉 —— 否则它会在自由投料下强制调味",
                exec.getOrderSeasoning());
    }

    /**
     * 只有份数键、没有配方 id 的标签同样算「无订单」（{@code MekCkOrderState.load}
     * 以 {@code OrderRecipeId} 为激活判据）⇒ 调味料照样清掉。
     */
    @Test
    public void seasoningIsClearedWhenTheOrderKeysAreIncomplete() {
        GrillFactoryExecutor exec = new GrillFactoryExecutor();
        CompoundTag tag = new CompoundTag();
        tag.putInt("OrderQuantity", 4);
        tag.putInt("OrderCompleted", 2);
        tag.putString("OrderSeasoning", SEASONING);

        exec.load(tag);

        assertEquals(0, exec.getOrderQuantity());
        assertNull("没有配方 id 就不是一张有效的单，调味料不得残留", exec.getOrderSeasoning());
    }

    // ── 对照：有订单时调味料必须读回来 ──────────────────────────────────

    /** 订单激活时，存档里的调味料必须原样读回（守卫不能把正常路径也清掉）。 */
    @Test
    public void seasoningSurvivesWhenTheOrderIsActive() {
        GrillFactoryExecutor exec = new GrillFactoryExecutor();

        exec.load(orderTag(SEASONING));

        assertEquals("订单必须被读回来", 4, exec.getOrderQuantity());
        assertEquals(RECIPE, exec.getOrderRecipeId().toString());
        assertEquals(SEASONING, exec.getOrderSeasoning());
    }

    /** 订单激活但存档没写调味料键 ⇒ null（不是空串、不是别的默认值）。 */
    @Test
    public void activeOrderWithoutSeasoningKeyMeansNone() {
        GrillFactoryExecutor exec = new GrillFactoryExecutor();

        exec.load(orderTag(null));

        assertEquals(4, exec.getOrderQuantity());
        assertNull(exec.getOrderSeasoning());
    }

    /** 空串调味料与「没写」同义。 */
    @Test
    public void emptySeasoningStringMeansNone() {
        GrillFactoryExecutor exec = new GrillFactoryExecutor();

        exec.load(orderTag(""));

        assertEquals(4, exec.getOrderQuantity());
        assertNull(exec.getOrderSeasoning());
    }

    // ── 边界：null 标签与常驻状态 ───────────────────────────────────────

    /** {@code load(null)} 不得抛异常，且把订单与调味料一并清空。 */
    @Test
    public void loadWithoutTagClearsOrderAndSeasoning() {
        GrillFactoryExecutor exec = new GrillFactoryExecutor();
        exec.load(orderTag(SEASONING));

        exec.load(null);

        assertEquals(0, exec.getOrderQuantity());
        assertNull(exec.getOrderSeasoning());
    }

    /**
     * 工作模式与调味料启用位是<b>常驻状态</b>，不是订单的一部分：
     * 无订单时也要读回来（守卫只清调味料，不能顺手把它们也清了）。
     */
    @Test
    public void workModeAndSeasoningFlagsSurviveWithoutAnOrder() {
        GrillFactoryExecutor exec = new GrillFactoryExecutor();
        CompoundTag tag = new CompoundTag();
        tag.putInt("WorkMode", GrillFactoryExecutor.WorkMode.ORDER.ordinal());
        tag.putInt("SeasoningEnabled", 0b101);

        exec.load(tag);

        assertEquals(GrillFactoryExecutor.WorkMode.ORDER, exec.getWorkMode());
        assertTrue("第 0 位应启用", exec.isSeasoningEnabled(0));
        assertFalse("第 1 位应关闭", exec.isSeasoningEnabled(1));
        assertTrue("第 2 位应启用", exec.isSeasoningEnabled(2));
    }
}
