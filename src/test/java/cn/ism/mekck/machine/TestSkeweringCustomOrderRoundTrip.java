package cn.ism.mekck.machine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 穿串工厂「自选组合单」的存读档往返 —— 第四轮把它迁到 {@link MekCkOrderState} 时
 * 发现的<b>唯一一处「公共状态类的语义不够用」</b>，值得单独钉住。
 *
 * <h3>为什么它是唯一的例外</h3>
 * 6 个执行器里只有穿串有<b>无配方 id 的订单</b>：自选组合的配方是
 * {@code KaleidoscopeGrillingCompat#makeCustomThreadingRecipe} 现场拼出来的虚拟配方，
 * <b>没有 id 可存</b>。其余 5 个的订单都带 id。
 *
 * <p>这带来两个与 {@link MekCkOrderState#save(CompoundTag)} 的正面冲突：</p>
 * <ol>
 *   <li><b>save</b>：它按「有没有配方 id」决定写不写 {@code OrderQuantity}，
 *       而自选组合单 id 恒为 null ⇒ 份数会<b>整个丢失</b>，
 *       症状是「存读档后机器有材料却不加工、且不报任何错」。</li>
 *   <li><b>load</b>：{@code setActiveWithoutRecipe} 会把 {@code completed} 清零
 *       （那是「下新单」的语义），而自选组合单<b>确实要把已完成数存进档</b>。</li>
 * </ol>
 *
 * <h3>为什么 completed 不能用 {@code advance()} 顺带推上去</h3>
 * {@code advance(delta)} 顺带判定「是否已满」。存读档恰好可能发生在
 * 「最后一批刚做完、还没来得及 {@code clear()}」那个 tick 窗口里 ——
 * 此时 {@code completed >= quantity}，{@code advance} 会立刻判定满单，
 * 调用方随即 {@code clear()}，<b>把一张刚读回来的单抹掉</b>。
 * 所以只能赋值、绝不判定，于是有了 {@link MekCkOrderState#restoreCompleted(int)}。
 *
 * <p>这些都在裸 JVM 里跑：{@link MekCkOrderState} 不依赖任何注册表或世界，
 * 正因如此它才被设计成可单测的纯状态载体。</p>
 */
public class TestSkeweringCustomOrderRoundTrip {

    /** 造一份与 {@code SkeweringFactoryExecutor} 写档形状<b>逐字一致</b>的标签。 */
    private static CompoundTag legacyCustomOrderTag(int quantity, int completed, String... itemIds) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("OrderQuantity", quantity);
        tag.putInt("OrderCompleted", completed);
        ListTag list = new ListTag();
        for (String id : itemIds) {
            list.add(StringTag.valueOf(id));
        }
        tag.put("OrderCustomIngredients", list);
        return tag;
    }

    /**
     * 取自选材料列表 —— 元素类型是 {@code StringTag}（{@link Tag#TAG_STRING}），
     * 不是 {@link Tag#TAG_COMPOUND}。写错元素类型时 {@code getList} 会返回空列表，
     * 于是「自选组合单」那条路整个走不到、测试还绿着。
     */
    private static ListTag customListOf(CompoundTag tag) {
        return tag.getList("OrderCustomIngredients", Tag.TAG_STRING);
    }

    /**
     * 复刻 {@code SkeweringFactoryExecutor.load} 对自选组合单的处理路径。
     *
     * <p>刻意与生产代码同形（而不是直接调执行器）：执行器要 {@code MekCkMachineTile}
     * 与注册表，裸 JVM 里造不出来。这条测试的价值在于锁住<b>顺序</b>：
     * 先激活、再复原 completed —— 顺序反了就会踩上面那个「刚读回来就被清掉」的坑。</p>
     */
    private static MekCkOrderState loadCustomOrder(CompoundTag tag, ListTag custom) {
        MekCkOrderState order = new MekCkOrderState();
        order.load(tag);
        if (order.hasRecipe()) {
            return order;                       // 固定配方单，与自选组合互斥
        }
        if (custom != null) {
            for (Tag element : custom) {
                if (element instanceof StringTag) {
                    order.setActiveWithoutRecipe(Math.max(1, MekCkOrderState.readQuantity(tag)));
                    order.restoreCompleted(MekCkOrderState.readCompleted(tag));
                }
            }
        }
        return order;
    }

    // ── 有单但无 id：active 标志是唯一判据 ──────────────────────────────

    @Test
    public void customOrderIsActiveWithoutRecipe() {
        CompoundTag tag = legacyCustomOrderTag(30, 12, "minecraft:wheat", "minecraft:beef");
        MekCkOrderState order = loadCustomOrder(tag, customListOf(tag));

        assertTrue("自选组合单必须算「有单」（否则机器不动也不报错）", order.isActive());
        assertFalse("自选组合单没有配方 id（虚拟配方无 id 可存）", order.hasRecipe());
        assertEquals("份数必须复原", 30, order.getQuantity());
        assertEquals("已完成数必须复原 —— 这是本测试存在的全部理由", 12, order.getCompleted());
    }

    /**
     * 存档键缺失 ⇒ 完全无单。
     *
     * <p>执行器与方块实体<b>同寿</b>，一次读档之后它还活着；「键不存在时什么都不做」
     * 会让字段停在非默认值，玩家看到一个<b>无法取消的幽灵订单</b>。
     * 旧 {@code GrindingFactoryBlockEntity.load} 正是漏了这一步。</p>
     */
    @Test
    public void absentKeysMeanNoOrder() {
        MekCkOrderState order = new MekCkOrderState();
        order.load(new CompoundTag());

        assertFalse(order.isActive());
        assertFalse(order.hasRecipe());
        assertEquals("无单时份数必须返 0（读侧不必再兜底判空）", 0, order.getQuantity());
        assertEquals(0, order.getCompleted());
    }

    // ── 关键：completed == quantity 不能在读档时被判满清掉 ────────────────

    /**
     * 回归：{@code completed >= quantity} 的存读档**不得**把刚读回来的单清掉。
     *
     * <p>这是 {@link MekCkOrderState#restoreCompleted(int)} 存在的理由。
     * 若当初图省事用 {@code advance(readCompleted(tag))} 复原，
     * {@code advance} 会判定「已满」⇒ 调用方 {@code clearOrder()} ⇒ 这张单凭空消失。</p>
     */
    @Test
    public void restoringAFullOrderDoesNotImmediatelyClearIt() {
        // 存读档落在「最后一批做完、clear 还没跑」的窗口
        CompoundTag tag = legacyCustomOrderTag(10, 10, "minecraft:wheat");

        MekCkOrderState viaRestore = loadCustomOrder(tag, customListOf(tag));
        assertTrue("复原路径不该把订单清掉", viaRestore.isActive());
        assertEquals(10, viaRestore.getCompleted());

        // 对照：如果当初用 advance() 顺带推，这里就会是 false（单被清了）
        MekCkOrderState viaAdvance = new MekCkOrderState();
        viaAdvance.setActiveWithoutRecipe(10);
        boolean full = viaAdvance.advance(10);
        assertTrue("advance(quantity) 本身确实会判定「已满」—— 这正是不能用它复原的原因", full);
        if (full) {
            viaAdvance.clear();
        }
        assertFalse("走 advance 路线时这张单会被清掉（被测语义：restoreCompleted 不走这条路）",
                viaAdvance.isActive());
    }

    // ── 读侧不变量 ────────────────────────────────────────────────────────

    @Test
    public void quantityIsAlwaysAtLeastOneWhileActive() {
        CompoundTag tag = legacyCustomOrderTag(0, 0, "minecraft:wheat");
        MekCkOrderState order = loadCustomOrder(tag, customListOf(tag));

        assertTrue(order.isActive());
        assertEquals("0 份订单会让「batch = min(batch, quantity - completed)」夹成 0 而永久惰性",
                1, order.getQuantity());
    }

    @Test
    public void remainingIsClampedSoWeNeverOverproduce() {
        MekCkOrderState order = new MekCkOrderState();
        order.setActiveWithoutRecipe(10);
        order.restoreCompleted(7);

        // 剩余 3：批量夹到 3，不会做出「比订单多」的东西
        assertEquals(3, order.remainingOrUnlimited(100));
        // 无单时不限量
        MekCkOrderState none = new MekCkOrderState();
        assertEquals(64, none.remainingOrUnlimited(64));
    }

    @Test
    public void restoreCompletedIsIgnoredWhenNoOrderIsActive() {
        MekCkOrderState order = new MekCkOrderState();
        order.restoreCompleted(5);
        assertFalse("没单时 restoreCompleted 必须是空操作，否则会凭空造出一张单", order.isActive());
        assertEquals(0, order.getQuantity());
        assertEquals("没单时已完成数也必须保持 0", 0, order.getCompleted());
    }
}
