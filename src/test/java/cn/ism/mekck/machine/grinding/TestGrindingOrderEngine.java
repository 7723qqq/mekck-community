package cn.ism.mekck.machine.grinding;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 研磨家族<b>订单系统</b>的测试 —— 切菜家族没有这一层，所以这整类断言无处可抄。
 *
 * <h3>为什么测得到</h3>
 * 被测的订单状态只用到 {@link CompoundTag} 与 {@link ResourceLocation}，两者都不碰
 * Mekanism 注册表，也不需要 {@code BlockEntityType}。执行器本身是个普通类：
 * 构造它不会触发 {@code KaleidoscopeCompat}（那是方法体里首次引用才加载的），
 * 也不会碰 {@code MekckConfig}（只在 {@code tick} 里读）。所以裸 JVM 里能直接跑。
 *
 * <h3>为什么这层必须测</h3>
 * 订单的失败形态<b>全是静默的</b>：订单丢失不影响机器开工
 * （{@code orderRecipeId == null} 意味着「不设限」），订单卡住也不影响机器开工
 * （只是只认一张配方）。两种都没有异常、没有日志，只有玩家会发现
 * 「我下的单不见了」或「这台机器再也不做别的了」。
 */
public class TestGrindingOrderEngine {

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    private static GrindingFactoryExecutor executor() {
        return new GrindingFactoryExecutor();
    }

    // ── 存 / 取 ─────────────────────────────────────────────────────────

    /** 有订单时三个键都要写出去，缺一个就等于丢一份「还剩几份」的信息。 */
    @Test
    public void orderSaveWritesTheThreeKeys() {
        GrindingFactoryExecutor executor = executor();
        executor.setOrder(new ResourceLocation("kaleidoscope_cookery", "millstone"), 7);

        CompoundTag tag = new CompoundTag();
        executor.save(tag);

        assertTrue(tag.contains(GrindingFactoryExecutor.TAG_ORDER_RECIPE, Tag.TAG_STRING));
        assertEquals("kaleidoscope_cookery:millstone",
                tag.getString(GrindingFactoryExecutor.TAG_ORDER_RECIPE));
        assertEquals(7, tag.getInt(GrindingFactoryExecutor.TAG_ORDER_QUANTITY));
        assertEquals(0, tag.getInt(GrindingFactoryExecutor.TAG_ORDER_COMPLETED));
    }

    /**
     * 没订单时<b>一个键都不写</b>。
     *
     * <p>凭空写三个 0 值键会让「这台机器下过单」与「这三个键是 0」再也分不开，
     * 而存档要为此多挂 3 个键 × 12 档 × 每台机器。</p>
     */
    @Test
    public void orderSaveWritesNothingWhenThereIsNoOrder() {
        CompoundTag tag = new CompoundTag();
        executor().save(tag);
        assertTrue("无订单时执行器子标签必须保持空", tag.isEmpty());
    }

    /** 存了再取必须逐项还原（配方 id / 份数 / 完成数）。 */
    @Test
    public void orderRoundTripsThroughTheTag() {
        GrindingFactoryExecutor written = executor();
        written.setOrder(new ResourceLocation("mekck", "grinding"), 5);

        CompoundTag tag = new CompoundTag();
        written.save(tag);

        GrindingFactoryExecutor read = executor();
        read.load(tag);

        assertEquals(new ResourceLocation("mekck", "grinding"), read.getOrderRecipeId());
        assertEquals(5, read.getOrderQuantity());
        assertEquals(0, read.getOrderCompleted());
    }

    /** 完成计数也要能往返：它就是「这一单已经做了几份」。 */
    @Test
    public void completedCountSurvivesTheRoundTrip() {
        CompoundTag tag = new CompoundTag();
        tag.putString(GrindingFactoryExecutor.TAG_ORDER_RECIPE, "mekck:grinding");
        tag.putInt(GrindingFactoryExecutor.TAG_ORDER_QUANTITY, 9);
        tag.putInt(GrindingFactoryExecutor.TAG_ORDER_COMPLETED, 2);

        GrindingFactoryExecutor read = executor();
        read.load(tag);
        assertEquals(2, read.getOrderCompleted());
        assertEquals(9, read.getOrderQuantity());
    }

    /**
     * 键不存在时把订单<b>清空</b>，而不是「什么都不做」。
     *
     * <p>旧 {@code GrindingFactoryBlockEntity.load} 漏了这一步：只在
     * {@code contains} 为真时赋值。执行器与方块实体同寿，一次读档之后它还活着，
     * 于是同一次会话里重载一次就会留下一个<b>无法取消的幽灵订单</b>——
     * 机器从此只认一张配方，玩家唯一能摆脱它的办法是拆了重放。</p>
     */
    @Test
    public void orderLoadWithNoKeyClearsAStaleOrder() {
        GrindingFactoryExecutor executor = executor();
        executor.setOrder(new ResourceLocation("mekck", "grinding"), 3);
        assertEquals(3, executor.getOrderQuantity());

        // 空标签 = 「这台机器没有订单」这一存档形态（刚放下的机器、取消订单后存盘）
        executor.load(new CompoundTag());

        assertNull("空标签必须把订单清掉", executor.getOrderRecipeId());
        assertEquals(0, executor.getOrderQuantity());
        assertEquals(0, executor.getOrderCompleted());
    }

    /** null 标签也不许炸：{@code MekCkRecipeExecutor#load} 的契约是「旧存档无此键时保持默认态」。 */
    @Test
    public void orderLoadToleratesNull() {
        GrindingFactoryExecutor executor = executor();
        executor.load(null);
        assertNull(executor.getOrderRecipeId());
    }

    /** 解析不出来的配方 id 一律当「无订单」，不能留半个订单。 */
    @Test
    public void orderLoadRejectsAMalformedRecipeId() {
        CompoundTag tag = new CompoundTag();
        tag.putString(GrindingFactoryExecutor.TAG_ORDER_RECIPE, "这不是一个合法的:id");
        tag.putInt(GrindingFactoryExecutor.TAG_ORDER_QUANTITY, 4);

        GrindingFactoryExecutor executor = executor();
        executor.load(tag);

        assertNull("解析失败必须退化成无订单", executor.getOrderRecipeId());
        assertEquals(0, executor.getOrderQuantity());
    }

    /**
     * 存档里的负数份数被夹到<b>可完成的最小值</b>（1），而不是 0。
     *
     * <p><b>本断言的期望值在第三轮审查中从 0 改成了 1</b>，理由是 0 本身是个坏状态：
     * {@code CookingFactoryExecutor.tick} 里有
     * {@code batch = Math.min(batch, orderQuantity - orderCompleted)}，
     * 份数为 0 会把 batch 夹成 0 ⇒ {@code if (batch <= 0) return;} ⇒
     * 机器<b>永远不再开工</b>，而玩家除了重下一单没有任何办法解除（订单也永远不会自然完成，
     * 因为「完成 0 份」这个条件恒不成立）。旧实现按家族各自为政：研磨靠
     * {@code advanceOrder} 的 {@code max(1, quantity)} 侥幸自愈，烹饪则彻底卡死。
     * 统一到 {@link MekCkOrderState} 之后，两家都是「读档时夹到 ≥ 1」这一种行为。
     *
     * <p>已完成为负数同样夹到 0（那个方向没有上面的问题：0 表示「一份都没做」）。</p>
     */
    @Test
    public void negativeOrderNumbersAreClampedToZero() {
        CompoundTag tag = new CompoundTag();
        tag.putString(GrindingFactoryExecutor.TAG_ORDER_RECIPE, "mekck:grinding");
        tag.putInt(GrindingFactoryExecutor.TAG_ORDER_QUANTITY, -5);
        tag.putInt(GrindingFactoryExecutor.TAG_ORDER_COMPLETED, -1);

        GrindingFactoryExecutor executor = executor();
        executor.load(tag);
        assertEquals(1, executor.getOrderQuantity());
        assertEquals(0, executor.getOrderCompleted());
    }

    /**
     * 0 份的旧存档同样被抬到 1 —— 这正是上面那条要防的「永久卡死」。
     *
     * <p>它是这条护栏真正针对的输入：{@code quantity = 0} 是旧实现真实会写出来的值
     * （旧 {@code load} 写的是 {@code Math.max(0, tag.getInt(...))}），
     * 而不是只存在于测试里的假想值。</p>
     */
    @Test
    public void zeroQuantityInAnOldSaveIsLiftedToOne() {
        CompoundTag tag = new CompoundTag();
        tag.putString(GrindingFactoryExecutor.TAG_ORDER_RECIPE, "mekck:grinding");
        tag.putInt(GrindingFactoryExecutor.TAG_ORDER_QUANTITY, 0);
        tag.putInt(GrindingFactoryExecutor.TAG_ORDER_COMPLETED, 0);

        GrindingFactoryExecutor executor = executor();
        executor.load(tag);
        assertEquals("0 份订单会让 batch 夹成 0 而永久卡死，必须抬到 1",
                1, executor.getOrderQuantity());
        assertTrue("抬到 1 之后第一份做完就应清单",
                GrindingFactoryExecutor.advanceOrder(0, executor.getOrderQuantity()));
    }

    // ── 推进 ────────────────────────────────────────────────────────────

    /** 恰好做完最后一份才清：{@code completed + 1 >= quantity}。 */
    @Test
    public void orderClearsExactlyOnTheLastUnit() {
        assertFalse("还差两份", GrindingFactoryExecutor.advanceOrder(0, 2));
        assertTrue("做完第一份", GrindingFactoryExecutor.advanceOrder(1, 2));
        assertTrue("单份订单做完即清", GrindingFactoryExecutor.advanceOrder(0, 1));
    }

    /**
     * {@code completed} 已到 int 上界时不得因加法溢出而永远判「未满」。
     *
     * <p>旧实现直接 {@code orderCompleted++}，溢出成负数后
     * {@code negative >= quantity} 恒为假 ⇒ 订单永远完不成、机器永远只认这一张配方。
     * 用 {@code long} 加法把这条路径消掉，对所有可达输入与旧实现逐位相同。</p>
     */
    @Test
    public void orderAdvanceDoesNotOverflowAtIntMax() {
        assertTrue("int 上界 + 1 必须仍能判满",
                GrindingFactoryExecutor.advanceOrder(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
    }

    /** 份数至少为 1：{@code setOrder(id, 0)} 语义是「做一份」，不是「做零份然后卡住」。 */
    @Test
    public void orderQuantityIsAtLeastOne() {
        GrindingFactoryExecutor executor = executor();
        executor.setOrder(new ResourceLocation("mekck", "grinding"), 0);
        assertEquals(1, executor.getOrderQuantity());
        executor.setOrder(new ResourceLocation("mekck", "grinding"), -3);
        assertEquals(1, executor.getOrderQuantity());
    }

    /** 换单必须把完成计数归零，否则新单一上来就判「已完成」。 */
    @Test
    public void placingANewOrderResetsTheCompletedCount() {
        GrindingFactoryExecutor executor = executor();

        CompoundTag tag = new CompoundTag();
        tag.putString(GrindingFactoryExecutor.TAG_ORDER_RECIPE, "mekck:grinding");
        tag.putInt(GrindingFactoryExecutor.TAG_ORDER_QUANTITY, 3);
        tag.putInt(GrindingFactoryExecutor.TAG_ORDER_COMPLETED, 2);
        executor.load(tag);
        assertEquals(2, executor.getOrderCompleted());

        executor.setOrder(new ResourceLocation("mekck", "grinding"), 8);
        assertEquals("换单后完成计数必须归零", 0, executor.getOrderCompleted());
        assertEquals(8, executor.getOrderQuantity());
    }

    /** 取消订单后所有三个读数都归零，GUI 才能把「剩余 0 份」画对。 */
    @Test
    public void clearingAnOrderZeroesEveryReading() {
        GrindingFactoryExecutor executor = executor();
        executor.setOrder(new ResourceLocation("mekck", "grinding"), 4);
        executor.clearOrder();

        assertNull(executor.getOrderRecipeId());
        assertEquals(0, executor.getOrderQuantity());
        assertEquals(0, executor.getOrderCompleted());
    }

    // ── 键名 ────────────────────────────────────────────────────────────

    /**
     * 新格式的三个键逐字沿用旧键名。
     *
     * <p>迁移只换位置（根标签 → {@code mekckExecutor} 子标签），不换名字。
     * 把这条写成断言是为了防去后人顺手把某个键改名——那样会让迁移变成
     * 「改名 + 换位置」两件事，而两套词表之间的对应关系就无法从代码里看出来。</p>
     */
    @Test
    public void orderKeysReuseTheLegacyVocabulary() {
        assertEquals("OrderRecipeId", GrindingFactoryExecutor.TAG_ORDER_RECIPE);
        assertEquals("OrderQuantity", GrindingFactoryExecutor.TAG_ORDER_QUANTITY);
        assertEquals("OrderCompleted", GrindingFactoryExecutor.TAG_ORDER_COMPLETED);
    }

    /** 三个键互相不同名：写反了就会在读侧互相覆盖，且不报错。 */
    @Test
    public void theThreeOrderKeysAreDistinct() {
        assertNotEquals(GrindingFactoryExecutor.TAG_ORDER_RECIPE, GrindingFactoryExecutor.TAG_ORDER_QUANTITY);
        assertNotEquals(GrindingFactoryExecutor.TAG_ORDER_QUANTITY, GrindingFactoryExecutor.TAG_ORDER_COMPLETED);
        assertNotEquals(GrindingFactoryExecutor.TAG_ORDER_RECIPE, GrindingFactoryExecutor.TAG_ORDER_COMPLETED);
    }
}
