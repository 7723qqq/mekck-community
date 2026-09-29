package cn.ism.mekck.machine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * 工厂「订单」的唯一状态载体 —— <b>6 个执行器共用同一份契约</b>。
 *
 * <h3>为什么要有这个类</h3>
 * 订单系统此前在 6 个执行器里各写一遍，而<b>它们已经漂移了</b>。第三轮审查实测到的三种约定：
 * <table border="1">
 *   <caption>漂移清单（修复前）</caption>
 *   <tr><th>执行器</th><th>{@code setOrder(null, q)}</th><th>数量下界</th></tr>
 *   <tr><td>{@code CookingFactoryExecutor:230}</td><td>走 {@code clearOrder()}</td><td>{@code max(1,·)}</td></tr>
 *   <tr><td>{@code SkeweringFactoryExecutor:428}</td><td>走 {@code clearOrder()}</td><td>{@code max(1,·)}</td></tr>
 *   <tr><td>{@code GrindingFactoryExecutor:222}</td><td><b>只清 id，quantity 残留 1</b></td><td>{@code max(1,·)}</td></tr>
 *   <tr><td>{@code PlantingCuttingFactoryExecutor:192}</td><td><b>只清 id，quantity 残留 1</b></td><td>{@code max(1,·)}</td></tr>
 *   <tr><td>{@code GrillFactoryExecutor:213}</td><td>清 id + 调味料</td><td><b>{@code max(0,·)}</b></td></tr>
 * </table>
 * 三套 null 约定 + 两套数量下界。烤炉那套 {@code max(0,·)} 原本是为「取消按钮发的是
 * {@code (null, 0, null)}」量身定做的 —— 有了下面统一的 null 约定之后它就不再需要，
 * 因为 {@code clear()} 会把数量一并清成 0。
 *
 * <p>漂移不是美观问题。上一轮审查在烤炉上抓到过一个同源缺陷：<b>容量预演与实际落槽
 * 走了两套 NBT 口径</b>，表现是「预演说装得下、落槽时只塞进去一部分，剩下凭空消失」，
 * <b>不报错、不留日志</b>。6 份同型副本正是这类漂移的温床：修一处必须记得修另外 5 处，
 * 而「记不记得」靠人守。所以这里把契约收进一个类，让漂移在结构上不再可能。</p>
 *
 * <h3>三条不变量</h3>
 * <ol>
 *   <li><b>{@code setOrder(null, ·)} 永远等价于 {@link #clear()}</b>，不留任何残留字段；</li>
 *   <li><b>{@code quantity} 恒 ≥ 1（激活时）</b>，由 {@link #setOrder} 与
 *       {@link #setActiveWithoutRecipe} 两个入口各自保证，读侧不必再兜底；</li>
 *   <li><b>推进走 {@link #advance} 一条路</b>，加法在 {@code long} 上做
 *       —— 这一点修掉了一个真实缺陷，见 {@link #advance}。</li>
 * </ol>
 *
 * <h3>「有订单」与「有配方 id」为什么是两个概念</h3>
 * 穿串工厂支持「自选组合单」：配方是现场拼出来的虚拟配方，<b>没有 id 可存</b>，
 * 于是它 {@code orderRecipeId == null} 却确实有一张单在跑。若把
 * 「激活」等同于「recipeId 非 null」，穿串的订单门禁会在每次读档后失效。
 * 因此本类显式持有 {@link #active}，与 {@link #hasRecipe()} 分开。
 */
public final class MekCkOrderState {

    /** 订单配方 id。自选组合单为 {@code null}。 */
    private ResourceLocation recipeId;
    /** 订单总份数。{@link #active} 为真时恒 ≥ 1。 */
    private int quantity;
    /** 已完成份数。 */
    private int completed;
    /** 是否有一张单在跑。与 {@code recipeId != null} 分开，见类注释。 */
    private boolean active;

    public MekCkOrderState() {
    }

    // ── 写入（统一契约的两个入口）────────────────────────────────────────

    /**
     * 下固定配方单 —— <b>6 个执行器的唯一 {@code setOrder} 实现</b>。
     *
     * @param recipeId  {@code null} 等价于 {@link #clear()}，不留残留字段
     * @param quantity  份数，激活时夹到 {@code ≥ 1}
     */
    public void setOrder(ResourceLocation recipeId, int quantity) {
        if (recipeId == null) {
            clear();
            return;
        }
        this.recipeId = recipeId;
        this.quantity = Math.max(1, quantity);
        this.completed = 0;
        this.active = true;
    }

    /**
     * 下「无配方 id」的订单（穿串的自选组合单专用）。
     *
     * <p>{@link #active} 为真但 {@link #hasRecipe()} 为假 —— 调用方判定
     * 「有没有单」必须用 {@link #isActive()}，判定「要不要卡配方门禁」才用
     * {@link #hasRecipe()}。</p>
     */
    public void setActiveWithoutRecipe(int quantity) {
        this.recipeId = null;
        this.quantity = Math.max(1, quantity);
        this.completed = 0;
        this.active = true;
    }

    /**
     * 取消订单 —— <b>把三个字段与 active 一次清干净</b>。
     *
     * <p>修复前 {@code GrindingFactoryExecutor} 与 {@code PlantingCuttingFactoryExecutor}
     * 的 {@code setOrder} 没有 null 分支，直接 {@code orderRecipeId = null} 而把
     * {@code quantity} 留在 {@code max(1, q)} 上。读侧靠 {@code getOrderQuantity()} 的
     * null 判断兜住了显示，于是那个残留状态<b>永远看不出来</b>，直到某个读数侧忘了判
     * null 才暴露成「无订单却卡着 1 份不加工」。</p>
     */
    public void clear() {
        this.recipeId = null;
        this.quantity = 0;
        this.completed = 0;
        this.active = false;
    }

    // ── 推进 ────────────────────────────────────────────────────────────

    /**
     * 推进 {@code delta} 份，返回订单是否**因此满**（满则调用方应 {@link #clear()}）。
     *
     * <h3>这里修掉了一个真实缺陷</h3>
     * 修复前 {@code GrindingFactoryExecutor.completeRecipe} 与
     * {@code PlantingCuttingFactoryExecutor} 写的是：
     * <pre>{@code orderCompleted++;                        // ← int 自增，先溢出
     * if (advanceOrder(orderCompleted, orderQuantity)) ... // ← 这里的 long 转换已经太晚}</pre>
     * 两处都配着「加法走 {@code long}，否则订单永远完不成」的注释，但 {@code ++} 本身
     * 就是 {@code int} 加法：份数配成 {@link Integer#MAX_VALUE} 且真跑满时，
     * {@code orderCompleted++} 先绕成 {@link Integer#MIN_VALUE}，
     * 随后 {@code (long) MIN_VALUE + 1 >= MAX_VALUE} 为假 ⇒ <b>订单永远完不成，
     * 机器永远只认这一张配方，且不报任何错</b>。注释声称的防护并不存在。
     *
     * <p>本方法把加法整体挪到 {@code long} 上，并夹到 {@link Integer#MAX_VALUE}
     * （超过 {@code MAX_VALUE} 的累计数在 int 里无处可放，而 {@code MAX_VALUE} 已是
     * 可表示的最大进度）。</p>
     *
     * @param delta 本次完成的份数；{@code ≤ 0} 视为不推进（返回「是否已满」的当前值）
     */
    public boolean advance(int delta) {
        if (!active) {
            return false;
        }
        if (delta > 0) {
            completed = (int) Math.min(Integer.MAX_VALUE, (long) completed + delta);
        }
        return (long) completed >= Math.max(1, quantity);
    }

    /**
     * 纯函数形态的「推进后是否已满」—— 给裸 JVM 里的断言用（真执行器造不出来）。
     *
     * @see #advance(int)
     */
    public static boolean advancedTo(int completed, int quantity, int delta) {
        long sum = (long) completed + Math.max(0, delta);
        return Math.min(Integer.MAX_VALUE, sum) >= Math.max(1, quantity);
    }

    // ── 读侧 ────────────────────────────────────────────────────────────

    /** 是否有一张单在跑（自选组合单也为真）。 */
    public boolean isActive() {
        return active;
    }

    /** 是否有一张<b>带配方 id</b> 的单（配方门禁的唯一判据）。 */
    public boolean hasRecipe() {
        return recipeId != null;
    }

    /** 订单配方 id；无固定配方单时为 {@code null}。 */
    public ResourceLocation getRecipeId() {
        return recipeId;
    }

    /**
     * 订单总份数 —— <b>无订单时恒返 0</b>。
     *
     * <p>读侧因此不必再写「{@code orderRecipeId == null ? 0 : orderQuantity}」这种
     * 到处重复、且历史上漏过两处的判空。</p>
     */
    public int getQuantity() {
        return active ? quantity : 0;
    }

    /** 已完成份数。 */
    public int getCompleted() {
        return completed;
    }

    /**
     * 本批还能做几份 —— 无订单时返 {@link Integer#MAX_VALUE}（不设上限）。
     *
     * <p>这正是「自由投料」与「按订单」的分野：调用方拿它去夹批量。</p>
     */
    public int remainingOrUnlimited(int batch) {
        if (!active) {
            return batch;
        }
        return Math.max(0, Math.min(batch, quantity - completed));
    }

    // ── 存档 ────────────────────────────────────────────────────────────

    /**
     * 写订单。**无订单时不写任何键** —— 否则「这台机器下过单」与「这三个键是 0」
     * 再也分不开（旧实现已遵守此约定，这里保持并说明理由）。
     */
    public void save(CompoundTag tag) {
        if (!active && completed == 0) {
            return;
        }
        if (hasRecipe()) {
            tag.putString(TAG_ORDER_RECIPE, recipeId.toString());
        }
        tag.putInt(TAG_ORDER_QUANTITY, quantity);
        tag.putInt(TAG_ORDER_COMPLETED, completed);
    }

    /**
     * 读订单。
     *
     * <p><b>键不存在即「无订单」</b>（整体清空），而不是「什么都不做」：执行器与方块实体
     * 同寿，一次读档之后它还活着，字段非默认值就会一直卡着「只加工这一张配方」。
     * 旧 {@code GrindingFactoryBlockEntity.load} 正是漏了这一步，同一会话里重载一次
     * 就留下一个<b>无法取消的幽灵订单</b>，玩家唯一能摆脱它的办法是拆了重放。</p>
     */
    public void load(CompoundTag tag) {
        clear();
        if (tag == null || !tag.contains(TAG_ORDER_RECIPE, Tag.TAG_STRING)) {
            return;
        }
        ResourceLocation parsed = ResourceLocation.tryParse(tag.getString(TAG_ORDER_RECIPE));
        if (parsed == null) {
            return;
        }
        recipeId = parsed;
        quantity = Math.max(1, tag.getInt(TAG_ORDER_QUANTITY));
        completed = Math.max(0, tag.getInt(TAG_ORDER_COMPLETED));
        active = true;
    }

    /** 旧存档的订单配方键名。六个执行器沿用同一套键，避免同名不同型。 */
    public static final String TAG_ORDER_RECIPE = "OrderRecipeId";
    /** 旧存档的订单份数键名。 */
    public static final String TAG_ORDER_QUANTITY = "OrderQuantity";
    /** 旧存档的订单已完成键名。 */
    public static final String TAG_ORDER_COMPLETED = "OrderCompleted";

    /**
     * 三键的公共读侧入口，供自选组合单这类「无 id 也要记份数」的家族复用。
     *
     * <p>调用方读完再自行决定 {@link #setActiveWithoutRecipe(int)} 的份数来源。</p>
     */
    public static int readQuantity(CompoundTag tag) {
        return tag == null ? 0 : Math.max(0, tag.getInt(TAG_ORDER_QUANTITY));
    }

    /** {@link #readQuantity} 的已完成份数版。 */
    public static int readCompleted(CompoundTag tag) {
        return tag == null ? 0 : Math.max(0, tag.getInt(TAG_ORDER_COMPLETED));
    }
}
